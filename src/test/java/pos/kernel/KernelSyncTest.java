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
import pos.proc.State;
import pos.proc.Tcb;
import pos.sched.FcfsScheduler;
import pos.sched.RoundRobinScheduler;
import pos.sched.Scheduler;

@DisplayName("El nucleo reparte permisos con semaforos y despierta condiciones con el cerrojo")
@Timeout(value = 20, unit = java.util.concurrent.TimeUnit.SECONDS)
class KernelSyncTest {

    private static final int VUELTAS = 5;
    private static final int ESPERADO = VUELTAS * 2;

    /** La seccion critica de siempre, pero con un semaforo en vez de un cerrojo. */
    private static final String SUMA_CON_SEMAFORO = """
            SEM_INIT mutex 1
            SET 0
            STORE 0
            SET 0
            STORE 1
            SET 0
            STORE 2
            SPAWN hilo
            main:   SEM_WAIT mutex
                    LOAD 0
                    ADD 1
                    STORE 0
                    SEM_POST mutex
                    LOAD 1
                    ADD 1
                    STORE 1
                    CMP 5
                    JZ finmain
                    JMP main
            finmain: EXIT
            hilo:   SEM_WAIT mutex
                    LOAD 0
                    ADD 1
                    STORE 0
                    SEM_POST mutex
                    LOAD 2
                    ADD 1
                    STORE 2
                    CMP 5
                    JZ finhilo
                    JMP hilo
            finhilo: EXIT
            """;

    /** Productor y consumidor de un solo hueco, con dos semaforos contrapuestos. */
    private static final String PRODUCTOR_CONSUMIDOR = """
            SEM_INIT llenos 0
            SEM_INIT huecos 1
            SET 0
            STORE 3
            SET 0
            STORE 4
            SPAWN consumidor
            main:   SEM_WAIT huecos
                    SET 7
                    STORE 3
                    SEM_POST llenos
                    EXIT
            consumidor: SEM_WAIT llenos
                        LOAD 3
                        STORE 4
                        SEM_POST huecos
                        EXIT
            """;

    /** Monitor de un solo dato: el consumidor comprueba la condicion en un bucle. */
    private static final String MONITOR = """
            SET 0
            STORE 5
            SPAWN consumidor
            main:   SLEEP 3
                    LOCK m
                    SET 1
                    STORE 5
                    COND_SIGNAL hay_datos
                    UNLOCK m
                    EXIT
            consumidor: LOCK m
                 bucle: LOAD 5
                        CMP 1
                        JZ listo
                        COND_WAIT hay_datos m
                        JMP bucle
                 listo: LOAD 5
                        STORE 6
                        UNLOCK m
                        EXIT
            """;

    private static Kernel ejecutar(Scheduler scheduler, String fuente, boolean volcar) {
        Kernel kernel = new Kernel(scheduler, MemorySetup.paged(4096, 256, 16));
        kernel.keepFinalMemory(volcar);
        kernel.createProcess("demo", Assembler.load("demo", fuente));
        kernel.run();
        return kernel;
    }

    private static int memoria(Kernel kernel, int direccion) {
        Pcb process = kernel.processes().get(0);
        List<Integer> volcado = kernel.finalMemory(process.id());
        assertNotNull(volcado, "hacia falta el volcado final");
        return volcado.get(direccion);
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
    // Semaforos
    // ------------------------------------------------------------------

    @Test
    @DisplayName("un semaforo de valor uno hace de cerrojo y el contador queda exacto")
    void elSemaforoDaExclusionMutua() {
        Kernel kernel = ejecutar(new RoundRobinScheduler(1), SUMA_CON_SEMAFORO, true);

        assertEquals(ESPERADO, memoria(kernel, 0));
        assertEquals(VUELTAS, memoria(kernel, 1));
        assertEquals(VUELTAS, memoria(kernel, 2));
    }

    @Test
    @DisplayName("el que no encuentra permiso se bloquea sin gastar CPU y despierta al devolverlo")
    void sinPermisoSeDuerme() {
        Kernel kernel = ejecutar(new RoundRobinScheduler(1), SUMA_CON_SEMAFORO, false);

        List<Trace.Event> eventos = kernel.trace().events();
        int bloqueo = -1;
        String quien = null;
        for (int i = 0; i < eventos.size() && bloqueo < 0; i++) {
            Trace.Event event = eventos.get(i);
            if (event.kind() == EventKind.BLOCK) {
                bloqueo = i;
                quien = event.who();
            }
        }
        assertTrue(bloqueo >= 0, "algun hilo tenia que esperar el permiso");

        int despierta = -1;
        for (int i = bloqueo + 1; i < eventos.size() && despierta < 0; i++) {
            Trace.Event event = eventos.get(i);
            if (event.kind() == EventKind.WAKE && event.who().equals(quien)) {
                despierta = i;
            }
        }
        assertTrue(despierta > bloqueo, "y tenia que despertar cuando se lo cedieran");

        for (Trace.Event event : eventos.subList(bloqueo, despierta)) {
            boolean turno = event.kind() == EventKind.SWITCH && event.who().equals(quien);
            assertFalse(turno, "un hilo esperando un permiso no vuelve a la CPU: " + event.line());
        }
    }

    @Test
    @DisplayName("productor y consumidor se dan el turno con dos semaforos contrapuestos")
    void productorYConsumidorSeEsperan() {
        Kernel kernel = ejecutar(new RoundRobinScheduler(1), PRODUCTOR_CONSUMIDOR, true);

        assertEquals(7, memoria(kernel, 3), "el productor escribio");
        assertEquals(7, memoria(kernel, 4), "y el consumidor lo copio, no lo invento");
        assertEquals(1, kernel.semaphores().valueOf("huecos"), "el hueco vuelve a estar libre");
        assertEquals(0, kernel.semaphores().valueOf("llenos"), "y no queda nada por consumir");
        for (Tcb thread : kernel.threads()) {
            assertNull(thread.failure(), thread.id() + " termino por su pie");
        }
    }

    @Test
    @DisplayName("la tabla de semaforos cuenta los permisos que quedan")
    void laTablaDeSemaforosSeLee() {
        Kernel kernel = ejecutar(new RoundRobinScheduler(1), PRODUCTOR_CONSUMIDOR, false);

        List<String> lineas = kernel.semaphores().describe();
        assertEquals(2, lineas.size());
        String todo = String.join(" | ", lineas);
        assertTrue(todo.contains("llenos"), todo);
        assertTrue(todo.contains("huecos"), todo);
    }

    // ------------------------------------------------------------------
    // Condiciones
    // ------------------------------------------------------------------

    @Test
    @DisplayName("el monitor espera la condicion en un bucle y lee el dato cuando llega")
    void elMonitorEsperaYDespierta() {
        Kernel kernel = ejecutar(new RoundRobinScheduler(1), MONITOR, true);

        assertEquals(1, memoria(kernel, 6), "el consumidor leyo el dato bueno, no un cero");
        for (Tcb thread : kernel.threads()) {
            assertEquals(State.TERMINATED, thread.state());
            assertNull(thread.failure(), thread.id() + " termino por su pie");
        }
    }

    @Test
    @DisplayName("al despertar de una condicion el hilo no corre sin el cerrojo: lo vuelve a pedir")
    void alDespertarSeReadquiereElCerrojo() {
        Kernel kernel = ejecutar(new RoundRobinScheduler(1), MONITOR, false);

        assertFalse(sucesos(kernel, EventKind.COND_WAIT).isEmpty(),
                "el consumidor tenia que dormir en la condicion");

        // Quien señala tiene el cerrojo en ese momento, asi que el despertado no
        // puede correr: se le apunta el cerrojo y vuelve a la cola de espera.
        boolean volvioADormir = false;
        for (Trace.Event event : sucesos(kernel, EventKind.WAKE)) {
            if (event.who().equals("p1/h2")
                    && event.detail().contains("vuelve a dormir, ahora por el cerrojo")) {
                volvioADormir = true;
            }
        }
        assertTrue(volvioADormir,
                "sin readquirir el cerrojo el monitor no seria exclusion mutua");

        // Y no hay ni un turno suyo entre la señal y el momento en que lo hereda.
        List<Trace.Event> eventos = kernel.trace().events();
        int senal = -1;
        int hereda = -1;
        for (int i = 0; i < eventos.size(); i++) {
            Trace.Event event = eventos.get(i);
            if (senal < 0 && event.kind() == EventKind.COND_SIGNAL) {
                senal = i;
            } else if (senal >= 0 && event.kind() == EventKind.WAKE
                    && event.who().equals("p1/h2")
                    && !event.detail().contains("vuelve a dormir")) {
                hereda = i;
                break;
            }
        }
        assertTrue(hereda > senal, "tenia que acabar heredando el cerrojo del que señala");
        for (Trace.Event event : eventos.subList(senal, hereda)) {
            boolean turno = event.kind() == EventKind.SWITCH && event.who().equals("p1/h2");
            assertFalse(turno, "no puede correr sin el cerrojo: " + event.line());
        }
    }

    @Test
    @DisplayName("una señal no despierta al que no era: los demas siguen esperando su turno")
    void laSenalDespiertaAUnoSolo() {
        String dos = """
                SET 0
                STORE 5
                SPAWN segundo
                SPAWN consumidor
                main:   SLEEP 5
                        LOCK m
                        SET 1
                        STORE 5
                        COND_SIGNAL hay_datos
                        UNLOCK m
                        EXIT
                segundo: LOCK m
                         COND_WAIT hay_datos m
                         UNLOCK m
                         EXIT
                consumidor: LOCK m
                     bucle: LOAD 5
                            CMP 1
                            JZ listo
                            COND_WAIT hay_datos m
                            JMP bucle
                     listo: UNLOCK m
                            EXIT
                """;
        Kernel kernel = ejecutar(new RoundRobinScheduler(1), dos, false);

        List<Trace.Event> senales = sucesos(kernel, EventKind.COND_SIGNAL);
        assertEquals(1, senales.size());
        assertTrue(senales.get(0).detail().contains("despierta a"),
                "la señal tenia con quien: " + senales.get(0).line());
        // Solo uno despierta; el otro se queda en la cola de la condicion y el
        // cierre tiene que decir por que, no inventarse un fallo del programa.
        long dormidos = kernel.threads().stream()
                .filter(t -> t.failure() != null && t.failure().contains("condicion"))
                .count();
        assertEquals(1, dormidos, "el que no fue señalado sigue esperando una condicion");
    }

    @Test
    @DisplayName("una señal sin nadie esperando se pierde y no rompe nada")
    void laSenalPerdidaNoRompe() {
        String solo = """
                LOCK m
                COND_SIGNAL nadie
                UNLOCK m
                EXIT
                """;
        Kernel kernel = ejecutar(new FcfsScheduler(), solo, false);

        List<Trace.Event> senales = sucesos(kernel, EventKind.COND_SIGNAL);
        assertEquals(1, senales.size());
        assertTrue(senales.get(0).detail().contains("señal perdida"), senales.get(0).line());
        assertNull(kernel.threads().get(0).failure());
    }

    @Test
    @DisplayName("esperar en una condicion sin el cerrojo es un fallo dicho con nombres")
    void sinCerrojoNoSePuedeEsperar() {
        String malo = """
                COND_WAIT hay_datos m
                EXIT
                """;
        Kernel kernel = ejecutar(new FcfsScheduler(), malo, false);

        Tcb thread = kernel.threads().get(0);
        assertNotNull(thread.failure(), "el fallo no puede pasar inadvertido");
        assertTrue(thread.failure().contains("sin tener el cerrojo"), thread.failure());
        assertFalse(sucesos(kernel, EventKind.COND_WAIT).size() > 1,
                "no se apunta en la cola un hilo que no estaba protegido");
    }

    @Test
    @DisplayName("COND_BROADCAST despierta a todos los que esperan y no deja a nadie atras")
    void broadcastDespiertaATodos() {
        String varios = """
                SET 0
                STORE 5
                SPAWN b
                SPAWN c
                main:   SLEEP 5
                        LOCK m
                        SET 1
                        STORE 5
                        COND_BROADCAST hay_datos
                        UNLOCK m
                        EXIT
                b:  LOCK m
                    COND_WAIT hay_datos m
                    LOAD 5
                    STORE 6
                    UNLOCK m
                    EXIT
                c:  LOCK m
                    COND_WAIT hay_datos m
                    LOAD 5
                    STORE 7
                    UNLOCK m
                    EXIT
                """;
        Kernel kernel = ejecutar(new RoundRobinScheduler(1), varios, true);

        assertEquals(1, memoria(kernel, 6));
        assertEquals(1, memoria(kernel, 7));
        for (Tcb thread : kernel.threads()) {
            assertNull(thread.failure(), thread.id() + " tenia que despertar con el resto");
        }
    }

    // ------------------------------------------------------------------
    // Lo que no cambia
    // ------------------------------------------------------------------

    @Test
    @DisplayName("un semaforo que no existe es un fallo, no una espera eterna")
    void semaforoInexistenteEsFallo() {
        String malo = """
                SEM_WAIT fantasma
                EXIT
                """;
        Kernel kernel = ejecutar(new FcfsScheduler(), malo, false);

        Tcb thread = kernel.threads().get(0);
        assertNotNull(thread.failure());
        assertTrue(thread.failure().contains("fantasma"), thread.failure());
        assertTrue(thread.failure().contains("SEM_INIT"), thread.failure());
    }

    @Test
    @DisplayName("el mismo programa con semaforos da siempre la misma traza")
    void laTrazaSigueSiendoReproducible() {
        Kernel primera = ejecutar(new RoundRobinScheduler(1), PRODUCTOR_CONSUMIDOR, true);
        Kernel segunda = ejecutar(new RoundRobinScheduler(1), PRODUCTOR_CONSUMIDOR, true);

        assertEquals(primera.trace().lines(), segunda.trace().lines(),
                "el reparto de permisos no puede depender del azar");
    }

    @Test
    @DisplayName("un programa con semaforos no toca la tabla de cerrojos")
    void sinCerrojosLaTablaSigueVacia() {
        Kernel kernel = ejecutar(new FcfsScheduler(), SUMA_CON_SEMAFORO, false);

        assertTrue(kernel.locks().describe().isEmpty(),
                "el semaforo no es un cerrojo: no debe aparecer en esa tabla");
    }
}
