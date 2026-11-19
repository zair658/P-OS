package pos.launch;

import java.util.List;
import java.util.Locale;
import pos.kernel.Kernel;
import pos.kernel.KernelReport;
import pos.kernel.Trace;
import pos.mm.MemorySetup;
import pos.proc.Assembler;
import pos.proc.Pcb;
import pos.sched.RoundRobinScheduler;

/**
 * Medicion del coste de cada mecanismo de sincronizacion.
 *
 * La fase 6 dejo tres maneras de esperar. Este comando no anade ninguna: mide
 * las que ya hay sobre el mismo trabajo, para que elegir entre ellas deje de ser
 * una opinion.
 *
 * Tres ideas sostienen la medicion y conviene decirlas antes que los numeros:
 *
 * <ul>
 *   <li><b>Correcto no es lo mismo que barato.</b> El primer acto resuelve el
 *       problema del hueco girando sobre un cerrojo, y lo resuelve: el dato
 *       llega entero. Lo que no hace es dejar de gastar CPU mientras espera. Si
 *       solo se midiera la correccion, esa tabla tendria tres filas iguales y no
 *       diria nada.</li>
 *   <li><b>Todo esta contado en ticks del simulador, no en milisegundos.</b> Un
 *       tick es una instruccion retirada, asi que las cifras son comparables
 *       entre si y salen iguales en cada ejecucion. No dicen cuanto tarda en un
 *       procesador real; dicen cuanto trabajo hace el sistema, que es la
 *       magnitud que el capitulo puede defender.</li>
 *   <li><b>Se mide con cuanto uno a proposito.</b> El contador sin proteccion
 *       solo pierde actualizaciones si al hilo lo desalojan entre la lectura y
 *       la escritura, y con cuanto uno lo desalojan en cada instruccion. El
 *       precio es que entonces cada tick es un cambio de contexto y esa columna
 *       sale igual que el reloj, asi que no se imprime: contaria lo mismo dos
 *       veces.</li>
 * </ul>
 *
 * Uso:
 * <pre>
 *   p-os --medicion          tablas en texto
 *   p-os --medicion --csv    las mismas cifras separadas por comas, para hoja de calculo
 * </pre>
 */
public final class MetricsDemo {

    /** Vueltas del contador compartido en el primer acto. */
    private static final int VUELTAS = 5;

    /** Incrementos que deberian quedar: dos hilos, cinco vueltas cada uno. */
    private static final int ESPERADO = VUELTAS * 2;

    /** Cuanto con el que se fuerza el desalojo dentro de la seccion critica. */
    private static final int CUANTO = 1;

    /** Piezas que fabrica el productor en el segundo acto. */
    private static final int PIEZAS = 3;

    /** Unidades donde cada programa deja su resultado. */
    private static final int CONTADOR = 0;
    private static final int CONSUMIDOS = 10;

    private MetricsDemo() {
        // Clase de demostracion: no se instancia.
    }

    public static void main(String[] args) {
        run(args.length > 0 && "--csv".equals(args[0]));
    }

    /** Ejecuta la medicion completa. */
    public static void run(boolean csv) {
        if (csv) {
            cabeceraCsv();
            contador().forEach(fila -> paraCsv("contador", fila));
            consumidor().forEach(fila -> paraCsv("productor-consumidor", fila));
            return;
        }
        actoPrimero();
        System.out.println();
        actoSegundo();
        System.out.println();
        actoTercero();
    }

    // ------------------------------------------------------------------
    // Primer acto: el mismo contador, tres protecciones
    // ------------------------------------------------------------------

    /** El contador compartido sin proteccion: sirve de suelo. */
    private static String contadorSinNada(int vueltas) {
        return """
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
                        CMP %d
                        JZ finmain
                        JMP main
                finmain: EXIT
                hilo:   LOAD 0
                        ADD 1
                        STORE 0
                        LOAD 2
                        ADD 1
                        STORE 2
                        CMP %d
                        JZ finhilo
                        JMP hilo
                finhilo: EXIT
                """
                .formatted(vueltas, vueltas);
    }

    /** El mismo contador con cerrojo. */
    private static String contadorConCerrojo(int vueltas) {
        return """
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
                        CMP %d
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
                        CMP %d
                        JZ finhilo
                        JMP hilo
                finhilo: EXIT
                """
                .formatted(vueltas, vueltas);
    }

    /** Y con un semaforo de valor uno, que es la misma exclusion contada. */
    private static String contadorConSemaforo(int vueltas) {
        return """
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
                        CMP %d
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
                        CMP %d
                        JZ finhilo
                        JMP hilo
                finhilo: EXIT
                """
                .formatted(vueltas, vueltas);
    }

    // ------------------------------------------------------------------
    // Segundo acto: tres piezas por un hueco de uno
    //
    // Mapa de memoria de los cuatro programas: unidad 3 la pieza en curso (5, 10
    // y 15), 4 la copia del consumidor, 8 la bandera de "hay pieza", 9 las
    // fabricadas y 10 las consumidas. Lo que se comprueba al final es la 10.
    // ------------------------------------------------------------------

    /**
     * El consumidor resuelto girando sobre un cerrojo.
     *
     * La unidad 8 es la bandera de "hay pieza". El consumidor no se duerme
     * nunca: pide el cerrojo, mira la bandera, lo suelta y vuelve a empezar. Las
     * piezas llegan todas y en orden, pero cada vuelta en vacio cuesta seis
     * instrucciones. Es el punto de comparacion que hace visible lo que aporta
     * tener una cola de espera.
     */
    private static final String GIRO = """
            SET 0
            STORE 3
            SET 0
            STORE 4
            SET 0
            STORE 8
            SET 0
            STORE 9
            SET 0
            STORE 10
            SPAWN consumidor
            main:
            pbucle: LOCK m
                    LOAD 9
                    CMP 3
                    JZ pfin
                    LOAD 3
                    ADD 5
                    STORE 3
                    SET 1
                    STORE 8
                    LOAD 9
                    ADD 1
                    STORE 9
                    UNLOCK m
                    JMP pbucle
            pfin:   UNLOCK m
                    EXIT
            consumidor:
            cbucle: LOCK m
                    LOAD 8
                    CMP 1
                    JZ tomar
                    UNLOCK m
                    JMP cbucle
            tomar:  LOAD 3
                    STORE 4
                    SET 0
                    STORE 8
                    LOAD 10
                    ADD 1
                    STORE 10
                    UNLOCK m
                    LOAD 10
                    CMP 3
                    JZ cfin
                    JMP cbucle
            cfin:   EXIT
            """;

    /** El mismo problema con dos semaforos contrapuestos, que es lo correcto. */
    private static final String SEMAFOROS = """
            SEM_INIT llenos 0
            SEM_INIT huecos 1
            SET 0
            STORE 3
            SET 0
            STORE 4
            SET 0
            STORE 9
            SET 0
            STORE 10
            SPAWN consumidor
            main:
            pbucle: LOAD 9
                    CMP 3
                    JZ pfin
                    SEM_WAIT huecos
                    LOAD 3
                    ADD 5
                    STORE 3
                    LOAD 9
                    ADD 1
                    STORE 9
                    SEM_POST llenos
                    JMP pbucle
            pfin:   EXIT
            consumidor:
            cbucle: LOAD 10
                    CMP 3
                    JZ cfin
                    SEM_WAIT llenos
                    LOAD 3
                    STORE 4
                    LOAD 10
                    ADD 1
                    STORE 10
                    SEM_POST huecos
                    JMP cbucle
            cfin:   EXIT
            """;

    /**
     * Los mismos dos semaforos, pero con un solo permiso para las tres piezas.
     *
     * El productor avisa una vez, al final, en vez de avisar por pieza. El
     * consumidor se lleva la primera y se queda dormido esperando la segunda
     * hasta que el sistema detecta que no queda nadie que pueda despertarlo: dos
     * piezas de tres. Es el error mas comun al contar permisos, y no se ve
     * leyendo el codigo: se ve midiendo.
     */
    private static final String PERMISO_UNICO = """
            SEM_INIT llenos 0
            SEM_INIT huecos 1
            SET 0
            STORE 3
            SET 0
            STORE 4
            SET 0
            STORE 9
            SET 0
            STORE 10
            SPAWN consumidor
            main:
            pbucle: LOAD 9
                    CMP 3
                    JZ pfin
                    SEM_WAIT huecos
                    LOAD 3
                    ADD 5
                    STORE 3
                    LOAD 9
                    ADD 1
                    STORE 9
                    JMP pbucle
            pfin:   SEM_POST llenos
                    EXIT
            consumidor:
            cbucle: LOAD 10
                    CMP 3
                    JZ cfin
                    SEM_WAIT llenos
                    LOAD 3
                    STORE 4
                    LOAD 10
                    ADD 1
                    STORE 10
                    SEM_POST huecos
                    JMP cbucle
            cfin:   EXIT
            """;

    /** Y con un cerrojo y una variable de condicion, en bucle como manda el protocolo. */
    private static final String MONITOR = """
            SET 0
            STORE 3
            SET 0
            STORE 4
            SET 0
            STORE 8
            SET 0
            STORE 9
            SET 0
            STORE 10
            SPAWN consumidor
            main:
            pbucle: LOCK m
                    LOAD 9
                    CMP 3
                    JZ pfin
                    LOAD 3
                    ADD 5
                    STORE 3
                    SET 1
                    STORE 8
                    LOAD 9
                    ADD 1
                    STORE 9
                    COND_SIGNAL hay_pieza
                    UNLOCK m
                    JMP pbucle
            pfin:   UNLOCK m
                    EXIT
            consumidor:
            cbucle: LOCK m
                    LOAD 8
                    CMP 1
                    JZ tomar
                    COND_WAIT hay_pieza m
                    JMP cbucle
            tomar:  LOAD 3
                    STORE 4
                    SET 0
                    STORE 8
                    LOAD 10
                    ADD 1
                    STORE 10
                    UNLOCK m
                    LOAD 10
                    CMP 3
                    JZ cfin
                    JMP cbucle
            cfin:   EXIT
            """;

    /** Medidas del primer acto. */
    static List<Medida> contador() {
        return List.of(
                medir("sin proteccion", contadorSinNada(VUELTAS), CONTADOR, ESPERADO),
                medir("cerrojo", contadorConCerrojo(VUELTAS), CONTADOR, ESPERADO),
                medir("semaforo de valor 1", contadorConSemaforo(VUELTAS), CONTADOR, ESPERADO));
    }

    /** El contador con cerrojo con un numero de vueltas dado, medido. */
    static Medida contadorMedido(int vueltas) {
        return medir("vueltas " + vueltas, contadorConCerrojo(vueltas), CONTADOR, vueltas * 2);
    }

    /** Medidas del segundo acto: piezas que se lleva el consumidor. */
    static List<Medida> consumidor() {
        return List.of(
                medir("giro sobre cerrojo", GIRO, CONSUMIDOS, PIEZAS),
                medir("dos semaforos", SEMAFOROS, CONSUMIDOS, PIEZAS),
                medir("un permiso por tres", PERMISO_UNICO, CONSUMIDOS, PIEZAS),
                medir("cerrojo y condicion", MONITOR, CONSUMIDOS, PIEZAS));
    }

    private static void actoPrimero() {
        System.out.println("=== acto primero: el contador compartido ===");
        System.out.println("dos hilos, " + VUELTAS + " incrementos cada uno; deberia quedar " + ESPERADO);
        System.out.println();
        List<Medida> filas = contador();
        tabla(filas);
        System.out.println();
        System.out.println("lectura: la fila sin proteccion no es mas rapida, es mas corta. Hace menos");
        System.out.println("trabajo porque pierde " + (ESPERADO - filas.get(0).valor())
                + " actualizaciones por el camino: el desalojo cae");
        System.out.println("entre LOAD y STORE y los dos hilos escriben el mismo valor viejo.");
        System.out.println();
        long extra = filas.get(2).reloj() - filas.get(1).reloj();
        System.out.println("Entre cerrojo y semaforo hay " + extra + (extra == 1 ? " tick" : " ticks")
                + " de diferencia y no es ruido: el semaforo");
        System.out.println("necesita una instruccion mas, la que crea la tabla. Las dos filas ejecutan");
        System.out.println("las mismas " + filas.get(1).sincronizacion()
                + " operaciones de sincronizacion, porque un semaforo de valor");
        System.out.println("uno es un cerrojo con otro nombre. Si aqui no hay diferencia, es que");
        System.out.println("para este trabajo tampoco importa cual de los dos se use.");
    }

    private static void actoSegundo() {
        System.out.println("=== acto segundo: " + PIEZAS + " piezas por un hueco de uno ===");
        System.out.println("el consumidor deberia llevarse las " + PIEZAS + " piezas");
        System.out.println();
        List<Medida> filas = consumidor();
        tabla(filas);
        System.out.println();
        Medida giro = filas.get(0);
        Medida semaforos = filas.get(1);
        Medida permiso = filas.get(2);
        Medida monitor = filas.get(3);
        System.out.println("lectura: el giro acierta y cuesta " + giro.reloj()
                + " ticks, frente a los " + semaforos.reloj());
        System.out.println("de los semaforos. No esta esperando: esta dando vueltas. Cada vuelta pide");
        System.out.println("el cerrojo, mira la bandera y lo suelta, y eso son instrucciones que el");
        System.out.println("dato no necesitaba. Se nota en la espera media, " + giro.esperaMedia()
                + " ticks por hilo frente a");
        System.out.println("los " + semaforos.esperaMedia() + " de quien si se duerme.");
        System.out.println();
        long ahorro = Math.round(100.0 * (semaforos.reloj() - permiso.reloj()) / semaforos.reloj());
        System.out.println("La fila del permiso unico enseña lo que un cronometro no: es la mas barata de");
        System.out.println("la tabla (" + permiso.reloj() + " ticks, un " + ahorro
                + " por ciento menos que los semaforos) y esta rota.");
        System.out.println("El productor avisa una sola vez, al final, en vez de avisar por pieza; el");
        System.out.println("consumidor se duerme esperando la segunda y el productor se queda sin hueco");
        System.out.println("libre. Los dos acaban dormidos, el nucleo lo declara interbloqueo y no se");
        System.out.println("copia ninguna pieza. En la columna de espera pone una raya porque no");
        System.out.println("termino ningun hilo, y sin la columna de resultado ese desastre se leeria");
        System.out.println("como una mejora.");
        System.out.println();
        System.out.println("Entre semaforos (" + semaforos.reloj() + " ticks) y condicion ("
                + monitor.reloj() + " ticks) la diferencia es");
        System.out.println("que el despertado por una condicion no hereda el cerrojo: lo tiene quien");
        System.out.println("señala, asi que da un rodeo de mas para volver a entrar. Se paga a gusto");
        System.out.println("cuando la espera depende de una condicion y no de una cuenta de permisos.");
    }

    private static void actoTercero() {
        System.out.println("=== acto tercero: el coste crece con el trabajo, no con el mecanismo ===");
        System.out.println("el mismo contador con cerrojo, cambiando solo el numero de vueltas");
        System.out.println();
        System.out.println(String.format("%-10s %8s %10s %12s %10s", "vueltas", "valor", "reloj",
                "por vuelta", "sincro"));
        System.out.println("-".repeat(52));
        for (int vueltas : new int[] { 2, 5, 10, 20 }) {
            Medida medida = contadorMedido(vueltas);
            System.out.println(String.format("%-10d %8d %10d %12.2f %10d", vueltas, medida.valor(),
                    medida.reloj(), medida.reloj() / (double) vueltas, medida.sincronizacion()));
        }
        System.out.println();
        System.out.println("lectura: los ticks por vuelta tienden a un valor fijo (el arranque se nota");
        System.out.println("solo en la fila de dos). La sincronizacion no se degrada al crecer la");
        System.out.println("carga porque no queda estado de una vuelta para la siguiente: lo unico");
        System.out.println("que persiste es el cerrojo, y se suelta siempre. Una columna que");
        System.out.println("creciera aqui seria la señal de una fuga.");
    }

    // ------------------------------------------------------------------
    // Apoyo
    // ------------------------------------------------------------------

    /** Lo medido en una ejecucion. Los tiempos son ticks del simulador. */
    record Medida(String nombre, Integer valor, int esperado, long reloj, long bloqueos,
            long despertares, long sincronizacion, double esperaMedia) {

        boolean correcto() {
            return valor != null && valor == esperado;
        }

        String resultado() {
            if (valor == null) {
                // Sin volcado no hay dato: el proceso no llego a terminar.
                return "interbloqueo";
            }
            if (correcto()) {
                return "correcto";
            }
            return "incorrecto: " + valor + " de " + esperado;
        }

        String esperaTexto() {
            // Double.NaN significa que no termino ningun hilo y no hay promedio.
            return Double.isNaN(esperaMedia) ? "-" : String.format("%7.1f", esperaMedia);
        }
    }

    private static void tabla(List<Medida> filas) {
        System.out.println(String.format("%-22s %-18s %6s %8s %10s %7s %7s", "mecanismo",
                "resultado", "reloj", "bloqueos", "despertares", "sincro", "espera"));
        System.out.println("-".repeat(82));
        for (Medida fila : filas) {
            System.out.println(String.format("%-22s %-18s %6d %8d %10d %7d %8s", fila.nombre(),
                    fila.resultado(), fila.reloj(), fila.bloqueos(), fila.despertares(),
                    fila.sincronizacion(), fila.esperaTexto()));
        }
        System.out.println("columnas: ticks de reloj, veces que un hilo se bloqueo, veces que hubo que");
        System.out.println("despertarlo, operaciones de sincronizacion ejecutadas (LOCK, UNLOCK,");
        System.out.println("SEM_*, COND_*) y espera media por hilo en ticks.");
    }

    /** Ejecuta un programa de un proceso y recoge las cifras. */
    static Medida medir(String nombre, String fuente, int direccion, int esperado) {
        Kernel kernel = new Kernel(new RoundRobinScheduler(CUANTO),
                MemorySetup.paged(4096, 256, 16));
        kernel.keepFinalMemory(true);
        Pcb proceso = kernel.createProcess("medicion", Assembler.load("medicion", fuente));
        kernel.run();

        List<Integer> volcado = kernel.finalMemory(proceso.id());
        Integer valor = volcado == null || direccion >= volcado.size() ? null : volcado.get(direccion);

        long bloqueos = 0;
        long despertares = 0;
        long sincro = 0;
        for (Trace.Event event : kernel.trace().events()) {
            switch (event.kind()) {
                case BLOCK -> bloqueos++;
                case WAKE -> despertares++;
                case LOCK, UNLOCK, SEM_INIT, SEM_WAIT, SEM_POST, COND_WAIT, COND_SIGNAL -> sincro++;
                default -> {
                    // los demas sucesos no entran en la cuenta
                }
            }
        }

        KernelReport report = kernel.report();
        double espera = report.finishedCount() == 0 ? Double.NaN : report.averageWait();
        return new Medida(nombre, valor, esperado, kernel.clock(), bloqueos, despertares, sincro,
                espera);
    }

    private static void cabeceraCsv() {
        System.out.println("escenario,mecanismo,resultado,medido,esperado,reloj,bloqueos,"
                + "despertares,sincronizacion,espera_media");
    }

    private static void paraCsv(String escenario, Medida fila) {
        System.out.println(String.format(Locale.ROOT, "%s,%s,%s,%s,%d,%d,%d,%d,%d,%s",
                escenario, fila.nombre(), fila.correcto() ? "correcto" : "incorrecto",
                fila.valor() == null ? "sin-volcado" : fila.valor().toString(), fila.esperado(),
                fila.reloj(), fila.bloqueos(), fila.despertares(), fila.sincronizacion(),
                Double.isNaN(fila.esperaMedia()) ? "-" : String.format(Locale.ROOT, "%.1f",
                        fila.esperaMedia())));
    }
}
