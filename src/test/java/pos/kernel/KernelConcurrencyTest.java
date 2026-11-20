package pos.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import pos.mm.MemorySetup;
import pos.proc.Assembler;
import pos.proc.Pcb;
import pos.proc.Program;
import pos.proc.State;
import pos.proc.Tcb;
import pos.sched.FcfsScheduler;
import pos.sched.RoundRobinScheduler;
import pos.sched.Scheduler;

@DisplayName("El nucleo da exclusion mutua con cerrojos y detecta el interbloqueo")
@Timeout(value = 20, unit = java.util.concurrent.TimeUnit.SECONDS)
class KernelConcurrencyTest {

    private static final int VUELTAS = 5;
    private static final int ESPERADO = VUELTAS * 2;

    /**
     * Contador compartido en la unidad 0, contador de vueltas privado en la 1 y
     * en la 2. La seccion critica es LOAD 0 / ADD 1 / STORE 0.
     */
    private static final String SUMA = """
            SET 0
            STORE 0
            SET 0
            STORE 1
            SET 0
            STORE 2
            SPAWN hilo
            main:   LOAD 0
                    ADD 1
                    STORE 0
                    LOAD 1
                    ADD 1
                    STORE 1
                    CMP 5
                    JZ finmain
                    JMP main
            finmain: EXIT
            hilo:   LOAD 0
                    ADD 1
                    STORE 0
                    LOAD 2
                    ADD 1
                    STORE 2
                    CMP 5
                    JZ finhilo
                    JMP hilo
            finhilo: EXIT
            """;

    /** El mismo trabajo con la seccion critica protegida. */
    private static final String SUMA_CON_CERROJO = """
            SET 0
            STORE 0
            SET 0
            STORE 1
            SET 0
            STORE 2
            SPAWN hilo
            main:   LOCK contador
                    LOAD 0
                    ADD 1
                    STORE 0
                    UNLOCK contador
                    LOAD 1
                    ADD 1
                    STORE 1
                    CMP 5
                    JZ finmain
                    JMP main
            finmain: EXIT
            hilo:   LOCK contador
                    LOAD 0
                    ADD 1
                    STORE 0
                    UNLOCK contador
                    LOAD 2
                    ADD 1
                    STORE 2
                    CMP 5
                    JZ finhilo
                    JMP hilo
            finhilo: EXIT
            """;

    /** Dos cerrojos pedidos en orden contrario. */
    private static final String CRUZADO = """
            SPAWN hilo
            main:   LOCK a
                    LOCK b
                    UNLOCK b
                    UNLOCK a
                    EXIT
            hilo:   LOCK b
                    LOCK a
                    UNLOCK a
                    UNLOCK b
                    EXIT
            """;

    private static Kernel kernel(Scheduler scheduler, String fuente, boolean volcar) {
        Kernel kernel = new Kernel(scheduler, MemorySetup.paged(4096, 256, 16));
        kernel.keepFinalMemory(volcar);
        kernel.createProcess("suma", Assembler.load("suma", fuente));
        kernel.run();
        return kernel;
    }

    private static int valorFinal(Kernel kernel) {
        Pcb process = kernel.processes().get(0);
        List<Integer> volcado = kernel.finalMemory(process.id());
        assertNotNull(volcado, "hacia falta el volcado final");
        return volcado.get(0);
    }

    private static List<Trace.Event> sucesos(Kernel kernel, EventKind kind) {
        List<Trace.Event> filtrados = new ArrayList<>();
        for (Trace.Event event : kernel.trace().events()) {
            if (event.kind() == kind) {
                filtrados.add(event);
            }
        }
        return List.copyOf(filtrados);
    }

    // ------------------------------------------------------------------
    // La condicion de carrera
    // ------------------------------------------------------------------

    @Test
    @DisplayName("con desalojo de una instruccion y sin cerrojo se pierden actualizaciones")
    void sinCerrojoSePierdenActualizaciones() {
        Kernel kernel = kernel(new RoundRobinScheduler(1), SUMA, true);

        assertEquals(ESPERADO - VUELTAS, valorFinal(kernel),
                "el desalojo cae entre LOAD y STORE y cada hilo machaca la suma del otro");
    }

    @Test
    @DisplayName("sin desalojo el mismo binario sale bien: el fallo depende del planificador")
    void sinDesalojoElMismoProgramaAcierta() {
        Kernel kernel = kernel(new FcfsScheduler(), SUMA, true);

        assertEquals(ESPERADO, valorFinal(kernel),
                "sin ventana entre LOAD y STORE no se pierde nada, y sin cerrojo");
    }

    @Test
    @DisplayName("con cerrojo y el mismo desalojo el contador queda exacto")
    void conCerrojoElContadorQuedaExacto() {
        Kernel kernel = kernel(new RoundRobinScheduler(1), SUMA_CON_CERROJO, true);

        assertEquals(ESPERADO, valorFinal(kernel));
    }

    // ------------------------------------------------------------------
    // El cerrojo dentro del nucleo
    // ------------------------------------------------------------------

    @Test
    @DisplayName("el que no consigue el cerrojo queda bloqueado y sin gastar CPU")
    void elBloqueadoNoGastaCpu() {
        Kernel kernel = kernel(new RoundRobinScheduler(1), SUMA_CON_CERROJO, true);

        List<Trace.Event> eventos = kernel.trace().events();
        int bloqueo = -1;
        int despierta = -1;
        for (int i = 0; i < eventos.size(); i++) {
            Trace.Event event = eventos.get(i);
            if (event.kind() == EventKind.BLOCK && event.who().equals("p1/h2")) {
                bloqueo = i;
            } else if (event.kind() == EventKind.WAKE && event.who().equals("p1/h2")
                    && bloqueo >= 0) {
                despierta = i;
                break;
            }
        }
        assertTrue(bloqueo >= 0, "el segundo hilo tenia que quedarse esperando");
        assertTrue(despierta > bloqueo, "y tenia que despertar al liberarse el cerrojo");

        // Entre el bloqueo y el despertar no puede haber ni un solo turno suyo.
        for (Trace.Event event : eventos.subList(bloqueo, despierta)) {
            boolean turno = event.kind() == EventKind.SWITCH && event.who().equals("p1/h2");
            assertFalse(turno, "un hilo bloqueado no vuelve a la CPU: " + event.line());
        }
    }

    @Test
    @DisplayName("el cerrojo se cede a quien esperaba y el hilo termina sin fallo")
    void elCerrojoSeCedeYElHiloTermina() {
        Kernel kernel = kernel(new RoundRobinScheduler(1), SUMA_CON_CERROJO, true);

        List<Trace.Event> cesiones = sucesos(kernel, EventKind.UNLOCK);
        assertFalse(cesiones.isEmpty(), "tiene que haber liberaciones en la traza");

        Tcb segundo = kernel.threads().get(1);
        assertEquals(State.TERMINATED, segundo.state());
        assertNull(segundo.failure(), "termino por su pie, no lo mato el cierre");
        assertFalse(segundo.waitsForLock(), "al terminar no espera ningun cerrojo");
    }

    @Test
    @DisplayName("quien hereda el cerrojo no lo vuelve a pedir: la concesion ya esta hecha")
    void elHerederoNoRepiteElLock() {
        Kernel kernel = kernel(new RoundRobinScheduler(1), SUMA_CON_CERROJO, true);

        boolean despierto = false;
        boolean solto = false;
        for (Trace.Event event : kernel.trace().events()) {
            if (!event.who().equals("p1/h2")) {
                continue;
            }
            if (event.kind() == EventKind.WAKE) {
                despierto = true;
            } else if (despierto && event.kind() == EventKind.LOCK) {
                throw new AssertionError("el heredero no debe repetir el LOCK: " + event.line());
            } else if (despierto && event.kind() == EventKind.UNLOCK) {
                solto = true;
                break;
            }
        }
        assertTrue(despierto, "el segundo hilo tenia que heredar el cerrojo");
        assertTrue(solto, "y tenia que soltarlo el mismo, no otro");
    }

    @Test
    @DisplayName("un hilo que termina sin liberar deja el cerrojo denunciado en la traza")
    void terminarSinLiberarSeDenuncia() {
        String abandona = """
                SPAWN hilo
                main:   LOCK contador
                        EXIT
                hilo:   LOCK contador
                        UNLOCK contador
                        EXIT
                """;
        Kernel kernel = kernel(new RoundRobinScheduler(1), abandona, false);

        List<Trace.Event> huerfanos = sucesos(kernel, EventKind.LOCK_ORPHAN);
        assertEquals(1, huerfanos.size());
        assertTrue(huerfanos.get(0).detail().contains("contador"));
    }

    // ------------------------------------------------------------------
    // Interbloqueo
    // ------------------------------------------------------------------

    @Test
    @DisplayName("dos cerrojos en orden contrario acaban en ciclo detectado")
    void cerrojosCruzadosSonInterbloqueo() {
        Kernel kernel = kernel(new RoundRobinScheduler(1), CRUZADO, false);

        List<Trace.Event> avisos = sucesos(kernel, EventKind.DEADLOCK);
        assertFalse(avisos.isEmpty(), "el nucleo tiene que darse cuenta");

        String todo = String.join(" | ", avisos.stream().map(Trace.Event::detail).toList());
        assertTrue(todo.contains("p1/h1"), "el aviso nombra al hilo: " + todo);
        assertTrue(todo.contains("p1/h2"), "y al otro: " + todo);
        assertTrue(todo.contains("anillo"), "y describe el circulo: " + todo);
    }

    @Test
    @DisplayName("el interbloqueo no cuelga el sistema: termina y explica quien espera que")
    void elInterbloqueoNoCuelga() {
        Kernel kernel = kernel(new RoundRobinScheduler(1), CRUZADO, false);

        for (Tcb thread : kernel.threads()) {
            assertEquals(State.TERMINATED, thread.state(), "el cierre tiene que enterrar a todos");
            assertNotNull(thread.failure(), "y decir por que no acabaron");
            assertTrue(thread.failure().contains("esperando el cerrojo"),
                    "el motivo es la espera, no un fallo del programa: " + thread.failure());
        }
    }

    @Test
    @DisplayName("la tabla de cerrojos deja la foto del interbloqueo")
    void laFotoDelInterbloqueoSeLee() {
        Kernel kernel = kernel(new RoundRobinScheduler(1), CRUZADO, false);

        List<String> lineas = kernel.locks().describe();
        assertEquals(2, lineas.size());
        String todo = String.join(" | ", lineas);
        assertTrue(todo.contains("en poder de"), todo);
        assertTrue(todo.contains("esperan"), todo);
    }

    @Test
    @DisplayName("el mismo escenario da siempre la misma traza")
    void laTrazaEsReproducible() {
        Kernel primera = kernel(new RoundRobinScheduler(1), SUMA_CON_CERROJO, true);
        Kernel segunda = kernel(new RoundRobinScheduler(1), SUMA_CON_CERROJO, true);

        assertEquals(primera.trace().lines(), segunda.trace().lines(),
                "sin azar no hay defensa que dependa de la suerte");
        assertEquals(valorFinal(primera), valorFinal(segunda));
    }

    @Test
    @DisplayName("larga vida al interbloqueo: el reloj se para en seco, no avanza dando vueltas")
    void elRelojSeParaEnElInterbloqueo() {
        Kernel kernel = kernel(new RoundRobinScheduler(1), CRUZADO, false);

        assertTrue(kernel.clock() < 100,
                "con nadie listo el reloj no debe seguir avanzando: " + kernel.clock());
    }

    @Test
    @DisplayName("el timeout de la prueba no es el unico guardian: el nucleo corta solo")
    void elNucleoCortaSolo() {
        // Si el detector fallara, run() no volveria y esta prueba se comeria el
        // timeout de la clase. El valor comprobado es solo la guinda.
        Kernel kernel = kernel(new RoundRobinScheduler(1), CRUZADO, false);
        assertEquals(2, kernel.report().rows().size());
    }

    /** Programa con dos hilos que no comparten nada, para el caso sin cerrojos. */
    private static final String SIN_COMPARTIR = """
            SPAWN hilo
            main:   SET 1
                    STORE 1
                    EXIT
            hilo:   SET 2
                    STORE 2
                    EXIT
            """;

    @Test
    @DisplayName("un programa sin cerrojos no toca la tabla de cerrojos")
    void sinCerrojosLaTablaEstaVacia() {
        Kernel kernel = new Kernel(new FcfsScheduler(), MemorySetup.paged(4096, 256, 16));
        Program programa = Assembler.load("simple", SIN_COMPARTIR);
        kernel.createProcess("simple", programa);
        kernel.run();

        assertEquals(0, kernel.locks().count());
        assertTrue(kernel.locks().describe().isEmpty());
    }
}
