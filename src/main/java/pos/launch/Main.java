package pos.launch;

import java.io.IOException;

import pos.base.PosVersion;
import pos.kernel.Kernel;
import pos.kernel.KernelReport;
import pos.mm.MemorySetup;
import pos.phys.PhysicalMemory;
import pos.proc.Assembler;
import pos.sched.FcfsScheduler;
import pos.sched.RoundRobinScheduler;
import pos.sched.Scheduler;

/**
 * Punto de entrada del sistema.
 *
 * Arranca con el autodiagnostico de la memoria fisica y, si todo esta en orden,
 * ejecuta el escenario de procesos de la fase 2 con varios planificadores para
 * que la comparacion se vea en la misma pantalla.
 *
 * Uso:
 * <pre>
 *   java -jar p-os.jar                 diagnostico y comparacion de planificadores
 *   java -jar p-os.jar --traza         ademas imprime la traza completa de Round Robin
 *   java -jar p-os.jar --memoria       demostracion de los tres esquemas de memoria
 *   java -jar p-os.jar --swap          paginas por demanda, intercambio y politicas
 *   java -jar p-os.jar --concurrencia  cerrojos, exclusion mutua e interbloqueo
 *   java -jar p-os.jar --sincronizacion  semaforos y variables de condicion
 *   java -jar p-os.jar --medicion      cuanto cuesta cada mecanismo de sincronizacion
 *   java -jar p-os.jar --shell         la terminal: ordenes, tuberias y contabilidad
 *   java -jar p-os.jar --version       version y salida
 * </pre>
 */
public final class Main {

    /** Programa de carga: cinco pasos de un contador guardado en memoria. */
    private static final String CONTADOR = """
            SET 0
            loop: ADD 1
                  STORE 0
                  CMP 5
                  JZ fin
                  JMP loop
            fin:  EXIT
            """;

    /** Programa que solo duerme: se comporta como una tarea que espera entrada. */
    private static final String ESPERA = """
            SLEEP 8
            EXIT
            """;

    private Main() {
        // Clase de arranque: no se instancia.
    }

    public static void main(String[] args) {
        if (flag(args, "--version") || flag(args, "-v")) {
            System.out.println(PosVersion.FULL);
            return;
        }
        if (flag(args, "--help") || flag(args, "-h")) {
            System.out.println("uso: p-os [--ui] [--captura [carpeta]] [--traza] [--memoria] [--swap] [--disco] [--concurrencia] [--sincronizacion] [--medicion [--csv]] [--shell] [--version] [--help]");
            System.out.println("  sin argumentos  diagnostico de memoria y comparacion de planificadores");
            System.out.println("  --traza         anade la traza completa de la ejecucion Round Robin");
            System.out.println("  --memoria       demostracion de los tres esquemas de memoria");
            System.out.println("  --swap          paginas por demanda, area de intercambio y politicas");
            System.out.println("  --disco         demostracion del sistema de ficheros y los tres metodos");
            System.out.println("  --concurrencia  cerrojos, exclusion mutua e interbloqueo");
            System.out.println("  --sincronizacion  semaforos, productor/consumidor y condiciones");
            System.out.println("  --medicion      coste medido de los tres mecanismos, en ticks");
            System.out.println("  --shell         sesion de terminal: ordenes, tuberias y llamadas");
            System.out.println("  --ui            abre la ventana con el sistema corriendo dentro");
            System.out.println("  --captura [dir] las mismas vistas en PNG, sin pantalla (por defecto capturas/)");
            System.out.println("  --csv           con --medicion, saca las cifras para hoja de calculo");
            return;
        }

        // Con --medicion --csv la salida es para una hoja de calculo: sin cabecera.
        if (!(flag(args, "--medicion") && flag(args, "--csv"))) {
            System.out.println(PosVersion.FULL);
            System.out.println();
        }

        // La ventana y las capturas van antes que todo lo demas: no imprimen un
        // informe, abren una vista, y las cabeceras de texto estorbarian.
        if (flag(args, "--ui")) {
            pos.ui.swing.UiLaunch.open();
            return;
        }

        if (flag(args, "--captura")) {
            String dir = valor(args, "--captura", "capturas");
            try {
                pos.ui.swing.UiLaunch.capture(dir);
            } catch (IOException e) {
                System.err.println("no se pudo capturar: " + e.getMessage());
                System.exit(1);
            }
            return;
        }

        if (flag(args, "--memoria")) {
            MemoryDemo.run();
            return;
        }

        if (flag(args, "--swap")) {
            SwapDemo.run();
            return;
        }

        if (flag(args, "--disco")) {
            FileSystemDemo.run();
            return;
        }

        if (flag(args, "--concurrencia")) {
            ConcurrencyDemo.run();
            return;
        }

        if (flag(args, "--sincronizacion")) {
            SyncDemo.run();
            return;
        }

        if (flag(args, "--medicion")) {
            MetricsDemo.run(flag(args, "--csv"));
            return;
        }

        if (flag(args, "--shell")) {
            ShellDemo.run();
            return;
        }

        if (!diagnosticoMemoria()) {
            System.exit(1);
        }

        System.out.println();
        System.out.println("programas cargados");
        System.out.println("  contador-a  " + Assembler.load("contador-a", CONTADOR).size()
                + " instrucciones");
        System.out.println("  contador-b  " + Assembler.load("contador-b", CONTADOR).size()
                + " instrucciones");
        System.out.println("  espera      " + Assembler.load("espera", ESPERA).size()
                + " instrucciones (duerme 8 ticks)");

        System.out.println();
        System.out.println("memoria del escenario ... " + memorySetup().describe());
        System.out.println();
        System.out.println("=== comparacion de planificadores ===");
        System.out.println("escenario: tres procesos que llegan en el tick 0; dos de calculo y uno que espera");

        KernelReport fcfs = ejecutar(new FcfsScheduler());
        KernelReport rr4 = ejecutar(new RoundRobinScheduler(4));
        KernelReport rr1 = ejecutar(new RoundRobinScheduler(1));

        System.out.println();
        System.out.println("--- FCFS, sin expropiacion ---");
        imprimeInforme(fcfs);

        System.out.println();
        System.out.println("--- Round Robin, cuanto 4 ---");
        imprimeInforme(rr4);

        System.out.println();
        System.out.println("--- Round Robin, cuanto 1 ---");
        imprimeInforme(rr1);

        System.out.println();
        System.out.println("--- resumen ---");
        comparativa(fcfs, rr4, rr1);

        if (flag(args, "--traza")) {
            System.out.println();
            System.out.println("--- traza completa de Round Robin(4) ---");
            Kernel kernel = construir(new RoundRobinScheduler(4));
            kernel.run();
            kernel.trace().lines().forEach(System.out::println);
        }

        System.out.println();
        System.out.println("estado: procesos, hilos, planificacion y memoria virtual operativos");
    }

    /** Comprobacion de arranque de la memoria fisica. */
    private static boolean diagnosticoMemoria() {
        PhysicalMemory memory = new PhysicalMemory(4096);
        memory.allocate(1, 0, 64);
        memory.allocate(2, 128, 64);

        System.out.println("memoria fisica ....... " + memory.size() + " unidades");
        System.out.println("unidades libres ...... " + memory.freeUnits());
        System.out.println("huecos libres ........ " + memory.freeSpans().size());
        System.out.println("mapa ................. " + memory.render(64));

        var failures = memory.checkInvariants();
        if (failures.isEmpty()) {
            System.out.println("invariantes .......... OK");
            return true;
        }
        System.out.println("invariantes .......... FALLO");
        failures.forEach(f -> System.out.println("  " + f));
        return false;
    }

    private static Kernel construir(Scheduler scheduler) {
        Kernel kernel = new Kernel(scheduler, memorySetup());
        kernel.createProcess("contador-a", Assembler.load("contador-a", CONTADOR));
        kernel.createProcess("contador-b", Assembler.load("contador-b", CONTADOR));
        kernel.createProcess("espera", Assembler.load("espera", ESPERA));
        return kernel;
    }

    /**
     * Memoria del escenario de planificacion: paginas de 16 unidades.
     *
     * Se usa paginacion porque el escenario no debe depender de que queden
     * tramos contiguos grandes: cada proceso se coloca en marcos sueltos y
     * anadir uno mas nunca obliga a recolocar los anteriores.
     */
    private static MemorySetup memorySetup() {
        return MemorySetup.paged(4096, 256, 16);
    }

    private static KernelReport ejecutar(Scheduler scheduler) {
        return construir(scheduler).run();
    }

    private static void imprimeInforme(KernelReport report) {
        report.table().forEach(System.out::println);
        System.out.println();
        report.summary().forEach(System.out::println);
    }

    private static void comparativa(KernelReport... reports) {
        System.out.println(String.format("%-18s %10s %10s %10s %8s",
                "planificador", "retorno", "espera", "respuesta", "reloj"));
        System.out.println("-".repeat(60));
        for (KernelReport report : reports) {
            System.out.println(String.format("%-18s %10.2f %10.2f %10.2f %8d",
                    report.scheduler(),
                    report.averageTurnaround(),
                    report.averageWait(),
                    report.averageResponse(),
                    report.clock()));
        }
        System.out.println();
        System.out.println("lectura: FCFS deja esperando al proceso corto detras de los dos largos;");
        System.out.println("el turno rotatorio le da CPU antes, a cambio de mas cambios de contexto.");
    }

    /** Valor que sigue a una bandera, o el valor por defecto si no viene. */
    private static String valor(String[] args, String name, String fallback) {
        for (int i = 0; i < args.length; i++) {
            if (!name.equals(args[i])) {
                continue;
            }
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                return args[i + 1];
            }
            return fallback;
        }
        return fallback;
    }

    private static boolean flag(String[] args, String name) {
        for (String arg : args) {
            if (name.equals(arg)) {
                return true;
            }
        }
        return false;
    }
}
