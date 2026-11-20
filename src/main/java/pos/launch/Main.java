package pos.launch;

import java.io.IOException;

import pos.base.PosVersion;
import pos.kernel.Kernel;
import pos.kernel.KernelReport;
import pos.mm.MemorySetup;
import pos.phys.PhysicalMemory;
import pos.proc.Assembler;
import pos.proc.Pcb;
import pos.sched.FcfsScheduler;
import pos.sched.PriorityScheduler;
import pos.sched.RoundRobinScheduler;
import pos.sched.Scheduler;
import pos.sched.SjfScheduler;

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
        KernelReport sjf = ejecutar(new SjfScheduler());
        KernelReport prioridad = ejecutar(new PriorityScheduler(4));

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
        System.out.println("--- SJF, el mas corto primero ---");
        imprimeInforme(sjf);

        System.out.println();
        System.out.println("--- Prioridades, la mas alta primero ---");
        imprimeInforme(prioridad);

        System.out.println();
        System.out.println("--- resumen ---");
        comparativa(fcfs, rr4, rr1, sjf, prioridad);

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
        Pcb a = kernel.createProcess("contador-a", Assembler.load("contador-a", CONTADOR));
        Pcb b = kernel.createProcess("contador-b", Assembler.load("contador-b", CONTADOR));
        Pcb espera = kernel.createProcess("espera", Assembler.load("espera", ESPERA));

        // Rafagas declaradas, no adivinadas. Las dos de calculo gastan 26 ticks de
        // procesador de verdad (el bucle da cinco vueltas) y la que espera gasta 2.
        // El tamano del programa no lo dice: son siete y dos instrucciones, y el
        // bucle ejecuta las suyas cinco veces. Declararlas aqui es la unica forma
        // de que SJF elija con datos en vez de con una suposicion.
        a.setEstimatedBurst(26);
        b.setEstimatedBurst(26);
        espera.setEstimatedBurst(2);

        // Prioridades: la que espera entrada es la mas urgente, que es la razon por
        // la que en un sistema real una tarea interactiva adelanta a dos de
        // calculo. Las dos de calculo van por detras y en distinto orden, para que
        // la politica tenga algo que decidir entre ellas.
        espera.setPriority(0);
        a.setPriority(1);
        b.setPriority(2);
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
        System.out.println("lectura: FCFS y el turno rotatorio no miran cuanto va a durar cada trabajo,");
        System.out.println("asi que el corto de dos ticks acaba detras de los largos. SJF lo pone primero");
        System.out.println("mientras la cola esta quieta, pero no expropia: si el corto se despierta de un");
        System.out.println("SLEEP con un largo ya corriendo, espera a que termine. Prioridad si lo recupera,");
        System.out.println("porque cada cuanto vuelve a elegir sobre toda la cola y el corto es el urgente.");
        System.out.println("La diferencia entre los dos no es el criterio: es cuando se vuelve a elegir.");
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
