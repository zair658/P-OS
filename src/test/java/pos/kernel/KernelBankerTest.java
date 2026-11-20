package pos.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pos.mm.MemorySetup;
import pos.proc.Assembler;
import pos.proc.Pcb;
import pos.proc.State;
import pos.proc.Tcb;
import pos.sched.FcfsScheduler;

@DisplayName("El nucleo reparte recursos con el banquero y despierta a quien esperaba")
@Timeout(value = 20, unit = java.util.concurrent.TimeUnit.SECONDS)
class KernelBankerTest {

    /**
     * Un solo recurso de una instancia y dos procesos: el primero lo toma, se
     * entretiene y lo suelta; el segundo llega mientras esta ocupado y espera.
     *
     * Tiene que ser otro proceso y no otro hilo: el maximo declarado es del
     * proceso, asi que dos hilos del mismo proceso no compiten por el recurso,
     * simplemente comparten lo que el proceso ya tiene.
     */
    private static final String UNO = """
            RES_CLAIM impresora 1
            RES_TAKE impresora 1
            SLEEP 2
            RES_GIVE impresora 1
            EXIT
            """;

    private static final String DOS = """
            RES_CLAIM impresora 1
            RES_TAKE impresora 1
            RES_GIVE impresora 1
            EXIT
            """;

    /** Un proceso que toma y se va sin devolver: el nucleo tiene que limpiar. */
    private static final String SE_MUERE_CON_EL_RECURSO = """
            RES_CLAIM impresora 1
            RES_TAKE impresora 1
            EXIT
            """;

    private static Kernel ejecutar(int instancias, String... programas) {
        Kernel kernel = new Kernel(new FcfsScheduler(), MemorySetup.paged(4096, 256, 16));
        kernel.declareResource("impresora", instancias);
        for (int i = 0; i < programas.length; i++) {
            String nombre = "p" + (i + 1);
            kernel.createProcess(nombre, Assembler.load(nombre, programas[i]));
        }
        kernel.run();
        return kernel;
    }

    private static List<Trace.Event> sucesos(Kernel kernel, EventKind kind) {
        List<Trace.Event> filtrados = new ArrayList<>();
        for (Trace.Event event : kernel.trace().events()) {
            if (event.kind() == kind) {
                filtrados.add(event);
            }
        }
        return filtrados;
    }

    @Test
    @DisplayName("quien llega a un recurso ocupado espera, y al devolverlo se le concede")
    void esperaYSeLeConcedeAlDevolver() {
        Kernel kernel = ejecutar(1, UNO, DOS);

        List<Trace.Event> negadas = sucesos(kernel, EventKind.RES_WAIT);
        List<Trace.Event> concedidas = sucesos(kernel, EventKind.RES_TAKE);

        assertEquals(1, negadas.size(), "el segundo proceso tiene que quedarse esperando");
        assertTrue(negadas.get(0).detail().contains("no quedan"), negadas.get(0).detail());
        // La primera concesion es directa; la segunda llega como despertar, porque
        // se resuelve al devolver el otro y no en el momento de pedir.
        assertEquals(1, concedidas.size());
        Trace.Event despertar = sucesos(kernel, EventKind.WAKE).stream()
                .filter(evento -> evento.detail().contains("banquero"))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "hacia falta el despertar que concede el recurso"));
        assertTrue(negadas.get(0).tick() < despertar.tick(),
                "la concesion no puede ser anterior a la espera");
        // Los dos procesos acaban: nadie se queda esperando para siempre.
        assertEquals(2, sucesos(kernel, EventKind.EXIT).size());
    }

    @Test
    @DisplayName("el hilo que espera queda marcado como bloqueado por recurso, no dormido")
    void elBloqueoLoDespiertaElBanqueroYNoElReloj() {
        Kernel kernel = ejecutar(1, UNO, DOS);

        Trace.Event bloqueo = sucesos(kernel, EventKind.BLOCK).stream()
                .filter(evento -> evento.detail().contains("impresora"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("hacia falta la traza del bloqueo"));

        // Si dijera "duerme N ticks", el barrido de dormidos lo despertaria solo.
        assertTrue(bloqueo.detail().contains("no quedan"), bloqueo.detail());
    }

    @Test
    @DisplayName("al terminar los dos no queda nada asignado ni nadie esperando")
    void elRepartoQuedaVacioAlAcabar() {
        Kernel kernel = ejecutar(1, UNO, DOS);

        assertEquals(1, kernel.banker().availableOf("impresora"));
        assertEquals(0, kernel.banker().pendingCount());
        assertTrue(kernel.banker().table().isEmpty(), "nadie deberia retener instancias");
        assertTrue(kernel.banker().isSafe());
    }

    @Test
    @DisplayName("un proceso que se va sin devolver no deja el recurso ocupado para siempre")
    void liberaAlTerminarSinDevolver() {
        Kernel kernel = ejecutar(1, SE_MUERE_CON_EL_RECURSO);

        assertEquals(1, kernel.banker().availableOf("impresora"));
        assertTrue(kernel.banker().table().isEmpty());

        List<Trace.Event> devoluciones = sucesos(kernel, EventKind.RES_GIVE);
        assertEquals(1, devoluciones.size());
        assertTrue(devoluciones.get(0).detail().contains("termina"),
                devoluciones.get(0).detail());
    }

    @Test
    @DisplayName("todos los hilos quedan terminados y ninguno bloqueado al final")
    void nadieQuedaBloqueado() {
        Kernel kernel = ejecutar(1, UNO, DOS);

        for (Pcb process : kernel.processes()) {
            for (Tcb thread : process.threads()) {
                assertEquals(State.TERMINATED, thread.state(), "hilo " + thread.id());
                assertFalse(thread.waitsForResource(), "hilo " + thread.id());
            }
        }
    }
}
