package pos.launch;

import java.util.List;
import pos.kernel.EventKind;
import pos.kernel.Kernel;
import pos.kernel.Trace;
import pos.mm.MemorySetup;
import pos.proc.Assembler;
import pos.proc.Pcb;
import pos.sched.RoundRobinScheduler;
import pos.sched.Scheduler;

/**
 * Demostracion de la fase 6: semaforos y variables de condicion.
 *
 * La fase 5 ensenyo el cerrojo: exclusion mutua y nada mas, que es justo lo que
 * un cerrojo sabe hacer. Con eso no se puede escribir un productor que espere a
 * que haya hueco ni un consumidor que espere a que haya dato: haria falta un
 * hilo girando sobre el cerrojo, gastando CPU para preguntar una y otra vez.
 *
 * Tres actos, en el mismo orden en que se entienden:
 * <ol>
 *   <li>Un semaforo de valor uno hace de cerrojo. Sirve para ver que el contador
 *       generaliza al cerrojo en vez de sustituirlo por otra cosa.</li>
 *   <li>Dos semaforos contrapuestos, uno por cada condicion del problema, dejan
 *       al productor y al consumidor dandose el turno sin gastar CPU.</li>
 *   <li>Una variable de condicion hace lo mismo con un cerrojo y un dato
 *       compartido, y ensena lo que un semaforo no puede: el que despierta no
 *       tiene el cerrojo y tiene que volver a pedirlo antes de seguir.</li>
 * </ol>
 */
public final class SyncDemo {

    /** Vueltas que da cada hilo al contador compartido. */
    private static final int VUELTAS = 5;

    /** Incrementos que deberia tener el contador si nadie pierde ninguno. */
    private static final int ESPERADO = VUELTAS * 2;

    /** Cuanto con el que se fuerza el desalojo dentro de la seccion critica. */
    private static final int CUANTO_CORTO = 1;

    /** El contador de la fase anterior, sin proteccion: aqui es el punto de partida. */
    private static final String SUMA_SIN_NADA = """
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

    /** El mismo contador con un cerrojo, para comparar. */
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

    /** Y con un semaforo de valor uno, que es la misma exclusion con otro nombre. */
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

    /**
     * Productor y consumidor de un solo hueco.
     *
     * 'llenos' empieza en cero porque no hay nada que consumir; 'huecos' empieza
     * en uno porque el hueco esta libre. El consumidor no pregunta si hay dato:
     * se duerme hasta que lo haya. El productor no pregunta si hay hueco: se
     * duerme hasta que lo haya. Ninguno de los dos gasta un solo tick esperando.
     */
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

    /**
     * El mismo problema con un cerrojo y una condicion.
     *
     * El dato va en la unidad 5 y la copia del consumidor en la 6. El consumidor
     * comprueba la condicion en un bucle, no en un 'if': al despertar puede que
     * el dato ya lo haya consumido otro, porque una condicion no guarda la señal.
     */
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

    private SyncDemo() {
        // Clase de demostracion: no se instancia.
    }

    public static void main(String[] args) {
        run();
    }

    /** Ejecuta la demostracion completa. */
    public static void run() {
        primerActo();
        System.out.println();
        segundoActo();
        System.out.println();
        tercerActo();
    }

    // ------------------------------------------------------------------
    // Primer acto: un semaforo de uno hace de cerrojo
    // ------------------------------------------------------------------

    private static void primerActo() {
        System.out.println("=== primer acto: el semaforo generaliza al cerrojo ===");
        System.out.println("el mismo contador compartido, " + VUELTAS
                + " vueltas por hilo, protegido de tres maneras");
        System.out.println();
        System.out.println(String.format("%-32s %8s %8s %6s  %s",
                "proteccion", "valor", "esperado", "tics", "resultado"));
        System.out.println("-".repeat(78));
        fila("ninguna", medir(SUMA_SIN_NADA));
        fila("cerrojo", medir(SUMA_CON_CERROJO));
        fila("semaforo de valor 1", medir(SUMA_CON_SEMAFORO));
        System.out.println();
        System.out.println("lectura: un semaforo que empieza en uno da exactamente la misma");
        System.out.println("exclusion que un cerrojo, y no es casualidad: el cerrojo es el caso");
        System.out.println("particular de contador igual a uno. La diferencia esta en lo que");
        System.out.println("permite el contador: con dos permisos entran dos hilos a la vez, y eso");
        System.out.println("sirve para limitar acceso a un recurso escaso, no para proteger un");
        System.out.println("contador.");
    }

    // ------------------------------------------------------------------
    // Segundo acto: productor y consumidor con dos semaforos
    // ------------------------------------------------------------------

    private static void segundoActo() {
        System.out.println("=== segundo acto: productor y consumidor con dos semaforos ===");
        System.out.println("un solo hueco; 'llenos' empieza en 0 y 'huecos' en 1");
        System.out.println("el consumidor no pregunta: se duerme hasta que haya dato");
        System.out.println();

        Corrida corrida = ejecutar(PRODUCTOR_CONSUMIDOR, new RoundRobinScheduler(CUANTO_CORTO), true);
        Kernel kernel = corrida.kernel();

        System.out.println("sucesos de sincronizacion");
        System.out.println("-".repeat(78));
        for (Trace.Event event : kernel.trace().events()) {
            if (esDeSincronizacion(event.kind())) {
                System.out.println("  " + event.line());
            }
        }
        System.out.println();
        System.out.println("lo que escribio el productor (unidad 3) ... " + corrida.valor(3));
        System.out.println("lo que copio el consumidor (unidad 4) .... " + corrida.valor(4));
        System.out.println("permisos que quedan en 'llenos' .......... "
                + kernel.semaphores().valueOf("llenos"));
        System.out.println("permisos que quedan en 'huecos' .......... "
                + kernel.semaphores().valueOf("huecos"));
        System.out.println();
        System.out.println("lectura: el consumidor empezo antes y se quedo dormido en 'llenos',");
        System.out.println("sin gastar CPU y sin mirar el dato ni una vez. Al devolver el permiso");
        System.out.println("el productor no sube el contador: se lo cede al que espera, porque");
        System.out.println("entre subir el contador y despertar cabe alguien que se cuele. Cada");
        System.out.println("semaforo vigila una condicion distinta del problema, y por eso hay dos.");
    }

    // ------------------------------------------------------------------
    // Tercer acto: la condicion obliga a readquirir el cerrojo
    // ------------------------------------------------------------------

    private static void tercerActo() {
        System.out.println("=== tercer acto: un monitor con variable de condicion ===");
        System.out.println("el consumidor toma el cerrojo, mira el dato, y si no esta se duerme");
        System.out.println("soltandolo; el productor lo despierta dentro del cerrojo");
        System.out.println();

        Corrida corrida = ejecutar(MONITOR, new RoundRobinScheduler(CUANTO_CORTO), true);
        Kernel kernel = corrida.kernel();

        System.out.println("sucesos de sincronizacion");
        System.out.println("-".repeat(78));
        for (Trace.Event event : kernel.trace().events()) {
            if (esDeSincronizacion(event.kind())) {
                System.out.println("  " + event.line());
            }
        }
        System.out.println();
        System.out.println("dato que dejo el productor (unidad 5) .... " + corrida.valor(5));
        System.out.println("dato que leyo el consumidor (unidad 6) ... " + corrida.valor(6));
        System.out.println();
        System.out.println("lectura: el consumidor no se lleva el cerrojo a la cama. Al dormirse lo");
        System.out.println("suelta, y al despertar lo vuelve a pedir; como en ese momento lo tiene");
        System.out.println("el que señalo, espera otra vez, ahora por el cerrojo. Ese paso de mas es");
        System.out.println("toda la diferencia con un semaforo, y es la razon de que el candidato se");
        System.out.println("compruebe en un bucle: entre el despertar y el cerrojo cabe otro hilo.");
    }

    // ------------------------------------------------------------------
    // Apoyo
    // ------------------------------------------------------------------

    /** Nucleo ya corrido y copia de la memoria de su proceso. */
    private record Corrida(Kernel kernel, List<Integer> volcado) {

        /** Valor de una unidad de memoria, o -1 si no hay volcado. */
        int valor(int direccion) {
            return volcado.isEmpty() || direccion >= volcado.size() ? -1 : volcado.get(direccion);
        }
    }

    private static boolean esDeSincronizacion(EventKind kind) {
        return switch (kind) {
            case SEM_INIT, SEM_WAIT, SEM_POST, COND_WAIT, COND_SIGNAL,
                    LOCK, UNLOCK, BLOCK, WAKE, FAULT, DEADLOCK -> true;
            default -> false;
        };
    }

    private static void fila(String nombre, Corrida corrida) {
        Kernel kernel = corrida.kernel();
        int valor = corrida.valor(0);
        String resultado = valor == ESPERADO ? "correcto" : "pierde " + (ESPERADO - valor);
        System.out.println(String.format("%-32s %8d %8d %6d  %s",
                nombre, valor, ESPERADO, kernel.clock(), resultado));
    }

    private static Corrida medir(String fuente) {
        return ejecutar(fuente, new RoundRobinScheduler(CUANTO_CORTO), true);
    }

    /**
     * Ejecuta un programa de un solo proceso y devuelve el nucleo ya corrido.
     *
     * El volcado es opcional porque leer la memoria cuesta accesos de verdad;
     * aqui siempre se pide, salvo que el escenario sea de memoria.
     */
    private static Corrida ejecutar(String fuente, Scheduler scheduler, boolean volcar) {
        Kernel kernel = new Kernel(scheduler, MemorySetup.paged(4096, 256, 16));
        kernel.keepFinalMemory(volcar);
        Pcb proceso = kernel.createProcess("demo", Assembler.load("demo", fuente));
        kernel.run();
        List<Integer> volcado = kernel.finalMemory(proceso.id());
        return new Corrida(kernel, volcado == null ? List.of() : List.copyOf(volcado));
    }
}
