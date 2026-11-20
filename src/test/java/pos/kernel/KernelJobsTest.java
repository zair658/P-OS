package pos.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import pos.base.PosFault;
import pos.proc.Assembler;
import pos.proc.BlockKind;
import pos.proc.HostedTask;
import pos.proc.Pcb;
import pos.proc.Program;
import pos.proc.State;
import pos.proc.StepResult;
import pos.proc.Tcb;
import pos.sched.RoundRobinScheduler;

/**
 * Control de trabajos en el nucleo: detener, reanudar y esperar procesos.
 *
 * Son las tres piezas que la cascara necesita para tener {@code jobs}, {@code bg}
 * y {@code fg}. Lo que se prueba aqui es la parte que no se ve: que un detenido
 * sale de la cola de listos y no vuelve solo, que un reanudado vuelve a la cola, y
 * que quien espera a un proceso se despierta cuando aquel termina y no antes.
 */
@DisplayName("El nucleo sabe detener, reanudar y esperar procesos")
@Timeout(value = 20, unit = java.util.concurrent.TimeUnit.SECONDS)
class KernelJobsTest {

    private static final String ETERNO = """
            SET 0
            loop: ADD 1
            JMP loop
            """;

    private static final String CORTO = """
            SET 0
            ADD 1
            EXIT
            """;

    private static Kernel nuevo() {
        return new Kernel(new RoundRobinScheduler(4), 256);
    }

    private static Program programa(String nombre, String fuente) {
        return Assembler.load(nombre, fuente);
    }

    // ------------------------------------------------------------------
    // Detener y reanudar
    // ------------------------------------------------------------------

    @Test
    @DisplayName("un proceso detenido sale de la cola y no vuelve por su cuenta")
    void detenerLoPara() {
        Kernel kernel = nuevo();
        Pcb bucle = kernel.createProcess("bucle", programa("bucle", ETERNO));

        // Se detiene antes de arrancar: da igual que aun no haya corrido, el
        // efecto es el mismo y la prueba no depende del reparto de turnos.
        int detenidos = kernel.suspend(bucle.id());
        assertEquals(1, detenidos, "un proceso de un solo hilo deja un hilo detenido");
        // El estado que cuenta es el del hilo: el PCB no corre y nadie lo
        // actualiza al despachar, asi que se queda en "nuevo" para siempre.
        assertEquals(State.STOPPED, hilo(bucle).state());

        KernelReport report = kernel.run();

        // Al cerrar, el nucleo apaga hasta lo detenido: la prueba no puede
        // esperar un STOPPED despues de run(), lo que queda es la nota.
        assertTrue(report.rows().stream().noneMatch(row -> row.finish() >= 0),
                "nada termina: lo unico vivo esta detenido");
        assertTrue(report.rows().get(0).failure().contains("seguia detenido al cerrar"),
                () -> "la tabla tiene que decir que se quedo detenido: "
                        + report.rows().get(0).failure());
        assertTrue(trazaCon(kernel, EventKind.IDLE, "en pausa"),
                "el cierre tiene que decir que fue una pausa, no un interbloqueo");
        assertFalse(trazaCon(kernel, EventKind.DEADLOCK, ""),
                "un detenido no es un interbloqueo: nadie espera nada");
    }

    @Test
    @DisplayName("reanudar devuelve el proceso a la cola y termina")
    void reanudarLoVuelveAPoner() {
        Kernel kernel = nuevo();
        Pcb corto = kernel.createProcess("corto", programa("corto", CORTO));
        kernel.suspend(corto.id());

        // La reanudacion se pide desde dentro de la ejecucion: el proceso tiene
        // que estar detenido mientras el sistema corre, no solo antes.
        final int pid = corto.id();
        final List<String> notas = new ArrayList<>();
        HostedTask guardia = (host, thread) -> {
            if (notas.isEmpty()) {
                notas.add("reanudados=" + kernel.resume(pid));
            }
            return StepResult.EXITED;
        };
        kernel.createHostedProcess("guardia", guardia);

        kernel.run();

        assertEquals(List.of("reanudados=1"), notas);
        assertEquals(State.TERMINATED, hilo(corto).state());
        assertNull(kernel.report().row("p" + pid + "/h1").failure());
        assertTrue(trazaCon(kernel, EventKind.CONT, "vuelven a la cola"), "se anota la reanudacion");
    }

    @Test
    @DisplayName("detener un proceso que no existe se avisa y no rompe nada")
    void detenerLoQueNoEsta() {
        Kernel kernel = nuevo();
        PosFault detener = assertThrows(PosFault.class, () -> kernel.suspend(99));
        assertTrue(detener.getMessage().contains("99"));
        PosFault reanudar = assertThrows(PosFault.class, () -> kernel.resume(99));
        assertTrue(reanudar.getMessage().contains("99"));
    }

    @Test
    @DisplayName("un proceso ya terminado no se detiene ni se reanuda")
    void detenerUnMuerto() {
        Kernel kernel = nuevo();
        Pcb corto = kernel.createProcess("corto", programa("corto", CORTO));
        Pcb eterno = kernel.createProcess("eterno", programa("eterno", ETERNO));
        // El bucle eterno mantiene el sistema en marcha el tiempo suficiente
        // para que el corto acabe; luego una tarea nativa lo mata desde fuera,
        // que es la unica forma de cerrar un bucle infinito.
        final int victima = eterno.id();
        // La marca va en una lista y no en los tics de cpu: una tarea nativa que
        // nunca ejecuta una instruccion termina siempre con cpu 0, asi que
        // preguntar por ella se quedaria en bucle.
        final List<String> notas = new ArrayList<>();
        HostedTask verdugo = (host, thread) -> {
            if (notas.isEmpty()) {
                thread.setPendingSleep(40);
                notas.add("dormido");
                return StepResult.BLOCKED;
            }
            kernel.kill(victima);
            return StepResult.EXITED;
        };
        kernel.createHostedProcess("verdugo", verdugo);

        kernel.run();

        assertEquals(State.TERMINATED, hilo(corto).state());
        assertThrows(PosFault.class, () -> kernel.suspend(corto.id()));
        assertThrows(PosFault.class, () -> kernel.resume(corto.id()));
    }

    @Test
    @DisplayName("un hilo detenido no se despierta por el reloj")
    void elRelojNoLoDespierta() {
        Kernel kernel = nuevo();
        Pcb bucle = kernel.createProcess("bucle", programa("bucle", ETERNO));
        kernel.suspend(bucle.id());

        long antes = kernel.clock();
        kernel.run();
        assertEquals(antes, kernel.clock(),
                "sin nadie en listos ni dormido, el reloj no tiene a donde saltar");
    }

    // ------------------------------------------------------------------
    // Esperar a un proceso
    // ------------------------------------------------------------------

    @Test
    @DisplayName("quien espera a un proceso vuelve cuando aquel termina")
    void esperarDespiertaAlFinal() {
        Kernel kernel = nuevo();
        final List<String> notas = new ArrayList<>();
        // El que espera se crea primero para que su primer turno llegue antes de
        // que el otro tenga ocasion de terminar; con el orden al reves la prueba
        // mediria "esperar a un muerto", que es el caso siguiente.
        final int[] objetivo = { -1 };

        HostedTask esperador = (host, thread) -> {
            if (notas.isEmpty()) {
                boolean bloquea = kernel.waitForProcess(thread, objetivo[0]);
                notas.add("bloquea=" + bloquea + " tipo=" + thread.blockedKind()
                        + " espera=" + thread.blockedOn());
                return bloquea ? StepResult.BLOCKED : StepResult.EXITED;
            }
            notas.add("desperto");
            return StepResult.EXITED;
        };
        kernel.createHostedProcess("esperador", esperador);
        Pcb corto = kernel.createProcess("corto", programa("corto", CORTO));
        objetivo[0] = corto.id();

        kernel.run();

        assertEquals(2, notas.size(), "se bloqueo una vez y desperto una vez");
        assertEquals("bloquea=true tipo=PROCESO espera=p" + objetivo[0], notas.get(0));
        assertEquals(State.TERMINATED, hilo(corto).state());
        assertEquals(1, kernel.report().rows().stream()
                .filter(row -> row.thread().equals("p" + objetivo[0] + "/h1")
                        && row.finish() >= 0)
                .count(), "el esperado tiene que haber terminado de verdad");
    }

    @Test
    @DisplayName("esperar a un proceso que ya termino no bloquea")
    void esperarLoQueYaAcabo() {
        Kernel kernel = nuevo();
        Pcb corto = kernel.createProcess("corto", programa("corto", CORTO));
        final int objetivo = corto.id();
        final List<String> notas = new ArrayList<>();

        HostedTask esperador = (host, thread) -> {
            if (notas.isEmpty()) {
                // Primero se deja morir al otro: el que espera duerme un rato y
                // luego pregunta, para que la respuesta no pueda ser "todavia
                // esta vivo". La marca va en una lista porque los tics de cpu de
                // una tarea nativa son siempre cero.
                notas.add("dormido");
                thread.setPendingSleep(50);
                return StepResult.BLOCKED;
            }
            notas.add("bloquea=" + kernel.waitForProcess(thread, objetivo));
            return StepResult.EXITED;
        };
        kernel.createHostedProcess("esperador", esperador);

        kernel.run();

        assertEquals(State.TERMINATED, hilo(corto).state());
        assertEquals(List.of("dormido", "bloquea=false"), notas);
        assertTrue(trazaCon(kernel, EventKind.IDLE, "el reloj salta"),
                "el esperador tiene que haber dormido por reloj, no por proceso");
    }

    @Test
    @DisplayName("nadie se espera a si mismo")
    void noSeEsperaAsiMismo() {
        Kernel kernel = nuevo();
        final List<String> errores = new ArrayList<>();

        HostedTask esperador = (host, thread) -> {
            try {
                // El pid del propio proceso: es el unico caso de esperarse a si
                // mismo que se puede provocar desde una tarea nativa.
                kernel.waitForProcess(thread, thread.process().id());
            } catch (PosFault fault) {
                errores.add(fault.getMessage());
            }
            return StepResult.EXITED;
        };
        kernel.createHostedProcess("esperador", esperador);
        kernel.run();

        assertEquals(1, errores.size());
        assertTrue(errores.get(0).contains("a si mismo"));
    }

    @Test
    @DisplayName("si el esperado no existe, se avisa con un fallo")
    void esperarAlFantasma() {
        Kernel kernel = nuevo();
        final List<String> errores = new ArrayList<>();
        HostedTask esperador = (host, thread) -> {
            try {
                kernel.waitForProcess(thread, 77);
            } catch (PosFault fault) {
                errores.add(fault.getMessage());
            }
            return StepResult.EXITED;
        };
        kernel.createHostedProcess("esperador", esperador);
        kernel.run();

        assertEquals(1, errores.size());
        assertTrue(errores.get(0).contains("77"));
    }

    // ------------------------------------------------------------------
    // Apoyo
    // ------------------------------------------------------------------

    /**
     * El unico hilo del proceso.
     *
     * Las pruebas preguntan por el hilo y no por el PCB a proposito: el PCB no
     * corre y su estado no se actualiza al despachar, asi que preguntarle por el
     * estado devuelve "nuevo" incluso con el proceso terminado.
     */
    private static Tcb hilo(Pcb process) {
        return process.threads().get(0);
    }

    private static boolean trazaCon(Kernel kernel, EventKind kind, String texto) {
        for (Trace.Event event : kernel.trace().events()) {
            if (event.kind() == kind && event.detail().contains(texto)) {
                return true;
            }
        }
        return false;
    }
}
