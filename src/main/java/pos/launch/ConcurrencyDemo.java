package pos.launch;

import java.util.ArrayList;
import java.util.List;
import pos.kernel.EventKind;
import pos.kernel.Kernel;
import pos.kernel.KernelReport;
import pos.kernel.Trace;
import pos.mm.MemorySetup;
import pos.proc.Assembler;
import pos.proc.Pcb;
import pos.proc.Program;
import pos.sched.FcfsScheduler;
import pos.sched.RoundRobinScheduler;
import pos.sched.Scheduler;

/**
 * Demostracion de la fase 5: cerrojos, exclusion mutua e interbloqueo.
 *
 * Tres actos. En el primero se comprueba que la condicion de carrera existe de
 * verdad y que no depende del programa sino del planificador: el mismo codigo
 * sale bien con FCFS, mal con cuanto de una instruccion y bien otra vez al
 * ponerle un cerrojo. Esa es la leccion del capitulo, y por eso el acto ensena
 * los tres resultados seguidos en vez de solo el fallo.
 *
 * El segundo ensena el mecanismo por dentro: quien toma el cerrojo, quien se
 * queda en la cola, y como el que lo libera se lo cede al primero que esperaba.
 *
 * El tercero toma dos cerrojos en orden contrario y comprueba que el sistema no
 * se queda colgado en silencio: detecta el ciclo, lo nombra hilo por hilo y lo
 * deja escrito en la traza.
 *
 * Nota sobre el determinismo: con un unico testigo de turno, la carrera no
 * aparece por azar. Hace falta que el desalojo caiga dentro de la seccion
 * critica, y con cuanto de una instruccion cae siempre en el mismo sitio. Eso
 * permite ensenar el fallo a voluntad, que es justo lo que un simulador aporta
 * frente a una maquina real.
 */
public final class ConcurrencyDemo {

    /** Vueltas que da cada hilo al contador compartido. */
    private static final int VUELTAS = 5;

    /** Incrementos que deberia tener el contador si nadie pierde ninguno. */
    private static final int ESPERADO = VUELTAS * 2;

    /** Cuanto con el que se fuerza el desalojo dentro de la seccion critica. */
    private static final int CUANTO_CORTO = 1;

    /**
     * Dos hilos suman uno a la unidad 0, cinco veces cada uno.
     *
     * La unidad 0 es el contador compartido; las unidades 1 y 2 son el contador
     * de vueltas de cada hilo, que tiene que ser privado y por eso no lo toca el
     * otro. La seccion critica es el trio LOAD 0 / ADD 1 / STORE 0: si alguien
     * se cuela entre la lectura y la escritura, la suma del primero se pierde.
     */
    private static final String SUMA_SIN_CERROJO = """
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

    /** El mismo trabajo, con la seccion critica protegida por un cerrojo. */
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

    /**
     * Dos cerrojos tomados en orden contrario.
     *
     * El hilo principal toma 'a' y luego pide 'b'; el hijo toma 'b' y luego pide
     * 'a'. Cada uno tiene lo que el otro necesita y ninguno suelta lo suyo: el
     * caso de manual, montado con cinco instrucciones.
     */
    private static final String DOS_CERROJOS = """
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

    private ConcurrencyDemo() {
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
    // Primer acto: la carrera depende del planificador, no del programa
    // ------------------------------------------------------------------

    private static void primerActo() {
        System.out.println("=== primer acto: el contador compartido ===");
        System.out.println("dos hilos suman 1 a la misma unidad de memoria, " + VUELTAS
                + " veces cada uno, sin cerrojo");
        System.out.println("lo que deberia quedar en la unidad 0: " + ESPERADO);
        System.out.println();

        Medida sinDesalojo = medir("FCFS, sin desalojo", new FcfsScheduler(), false);
        Medida conDesalojo = medir("Round Robin, cuanto " + CUANTO_CORTO,
                new RoundRobinScheduler(CUANTO_CORTO), false);
        Medida conCerrojo = medir("Round Robin, cuanto " + CUANTO_CORTO + ", con cerrojo",
                new RoundRobinScheduler(CUANTO_CORTO), true);

        System.out.println(String.format("%-38s %8s %8s %6s  %s",
                "escenario", "valor", "esperado", "tics", "resultado"));
        System.out.println("-".repeat(84));
        fila(sinDesalojo);
        fila(conDesalojo);
        fila(conCerrojo);
        System.out.println();

        System.out.println("turnos, es decir quien tiene la CPU en cada hueco, ya con los dos hilos");
        System.out.println("vivos (doce turnos)");
        System.out.println("-".repeat(84));
        System.out.println("  " + sinDesalojo.nombre() + ": " + turnos(sinDesalojo));
        System.out.println("  " + conDesalojo.nombre() + ": " + turnos(conDesalojo));
        System.out.println("  " + conCerrojo.nombre() + ": " + turnos(conCerrojo));
        System.out.println();

        System.out.println("lectura: sin desalojo no hay ventana, y el programa sale bien aunque");
        System.out.println("no tenga cerrojo. Con cuanto de una instruccion el desalojo cae justo");
        System.out.println("entre la lectura y la escritura: cada hilo lee el contador que el otro");
        System.out.println("leyo, los dos escriben el mismo valor y se pierden "
                + (ESPERADO - conDesalojo.valor()) + " actualizaciones.");
        System.out.println("El mismo binario da dos resultados distintos segun el planificador,");
        System.out.println("y eso es precisamente lo que un cerrojo arregla: no hace falta");
        System.out.println("confiar en el cuanto.");
    }

    // ------------------------------------------------------------------
    // Segundo acto: el mecanismo por dentro
    // ------------------------------------------------------------------

    private static void segundoActo() {
        System.out.println("=== segundo acto: que hace el cerrojo por dentro ===");
        Corrida corrida = ejecutar(SUMA_CON_CERROJO, new RoundRobinScheduler(CUANTO_CORTO), true);
        Kernel kernel = corrida.kernel();

        System.out.println("escenario: el mismo contador, ahora con LOCK y UNLOCK alrededor de la");
        System.out.println("seccion critica");
        System.out.println();
        System.out.println("sucesos de sincronizacion de la traza");
        System.out.println("-".repeat(84));
        for (Trace.Event event : kernel.trace().events()) {
            if (event.kind() == EventKind.LOCK
                    || event.kind() == EventKind.UNLOCK
                    || event.kind() == EventKind.BLOCK
                    || event.kind() == EventKind.WAKE) {
                System.out.println("  " + event.line());
            }
        }
        System.out.println();
        System.out.println("valor final de la unidad 0 .... " + corrida.valor());
        System.out.println();
        System.out.println("lectura: el segundo hilo no se cuela ni espera en vano. Entra en la");
        System.out.println("cola del cerrojo, deja de estar listo y no gasta CPU mientras espera.");
        System.out.println("Al liberar, el duenyo se lo cede al primero de la cola y el nucleo lo");
        System.out.println("devuelve a la cola de listos. La cesion es directa a proposito: nadie");
        System.out.println("puede colarse delante de quien lleva mas tiempo esperando.");
    }

    // ------------------------------------------------------------------
    // Tercer acto: interbloqueo
    // ------------------------------------------------------------------

    private static void tercerActo() {
        System.out.println("=== tercer acto: dos cerrojos en orden contrario ===");
        Kernel kernel = ejecutar(DOS_CERROJOS, new RoundRobinScheduler(CUANTO_CORTO), false)
                .kernel();

        System.out.println("el hilo principal toma 'a' y pide 'b'; el hijo toma 'b' y pide 'a'");
        System.out.println();
        System.out.println("estado de los cerrojos cuando el sistema se detiene");
        System.out.println("-".repeat(84));
        for (String line : kernel.locks().describe()) {
            System.out.println("  " + line);
        }
        System.out.println();
        System.out.println("lo que dijo el nucleo");
        System.out.println("-".repeat(84));
        for (Trace.Event event : kernel.trace().events()) {
            if (event.kind() == EventKind.DEADLOCK || event.kind() == EventKind.LOCK_ORPHAN) {
                System.out.println("  " + event.line());
            }
        }
        System.out.println();
        System.out.println("tabla de resultados");
        KernelReport report = kernel.report();
        report.table().forEach(System.out::println);
        System.out.println();
        System.out.println("lectura: nadie de los dos puede avanzar y ninguno va a soltar lo suyo,");
        System.out.println("asi que esperar no arregla nada. El sistema no se queda colgado: el");
        System.out.println("grafo de espera tiene un ciclo, se informa con nombres y apellidos y la");
        System.out.println("ejecucion termina. La cura seria imponer un orden global de adquisicion,");
        System.out.println("es decir, que los dos pidieran 'a' antes que 'b'.");
    }

    // ------------------------------------------------------------------
    // Apoyo
    // ------------------------------------------------------------------

    /** Resultado de una ejecucion del contador compartido. */
    private record Medida(String nombre, int valor, long reloj, List<String> turnos) {
    }

    /** Nucleo ya corrido y copia de la memoria de su proceso. */
    private record Corrida(Kernel kernel, List<Integer> volcado) {

        /** Valor de la unidad 0, que es el contador compartido. */
        int valor() {
            return volcado.isEmpty() ? -1 : volcado.get(0);
        }
    }

    private static void fila(Medida medida) {
        String resultado = medida.valor() == ESPERADO
                ? "correcto"
                : "pierde " + (ESPERADO - medida.valor());
        System.out.println(String.format("%-38s %8d %8d %6d  %s",
                medida.nombre(), medida.valor(), ESPERADO, medida.reloj(), resultado));
    }

    /**
     * Turnos alrededor del momento en que los dos hilos se disputan la CPU.
     *
     * Se salta el arranque, cuando el hilo principal corre solo porque el hijo
     * todavia no existe: lo que hay que mirar es la franja en la que los dos
     * estan listos, que es donde se decide si una actualizacion se pierde.
     */
    private static String turnos(Medida medida) {
        List<String> vistos = medida.turnos();
        int primero = 0;
        while (primero < vistos.size() && vistos.get(primero).equals(vistos.get(0))) {
            primero++;
        }
        int desde = Math.max(0, primero - 2);
        int hasta = Math.min(vistos.size(), desde + 12);
        String cabeza = String.join(" ", vistos.subList(desde, hasta));
        return hasta < vistos.size() ? cabeza + " ..." : cabeza;
    }

    private static Medida medir(String nombre, Scheduler scheduler, boolean conCerrojo) {
        String fuente = conCerrojo ? SUMA_CON_CERROJO : SUMA_SIN_CERROJO;
        Corrida corrida = ejecutar(fuente, scheduler, true);

        List<String> turnos = new ArrayList<>();
        for (Trace.Event event : corrida.kernel().trace().events()) {
            if (event.kind() == EventKind.SWITCH) {
                turnos.add(event.who());
            }
        }
        return new Medida(nombre, corrida.valor(), corrida.kernel().clock(), List.copyOf(turnos));
    }

    /**
     * Ejecuta un programa de un solo proceso y devuelve el nucleo ya corrido.
     *
     * El volcado es opcional porque leer la memoria de un proceso cuesta accesos
     * de verdad: con paginas por demanda cada lectura puede provocar una falta de
     * pagina, y en las demostraciones de memoria eso ensuciaria la traza.
     */
    private static Corrida ejecutar(String fuente, Scheduler scheduler, boolean volcar) {
        MemorySetup memory = MemorySetup.paged(4096, 256, 16);
        Kernel kernel = new Kernel(scheduler, memory);
        kernel.keepFinalMemory(volcar);
        Program programa = Assembler.load("contador-compartido", fuente);
        Pcb proceso = kernel.createProcess("suma", programa);
        kernel.run();
        List<Integer> volcado = kernel.finalMemory(proceso.id());
        return new Corrida(kernel, volcado == null ? List.of() : volcado);
    }
}
