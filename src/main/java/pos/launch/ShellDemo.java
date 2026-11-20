package pos.launch;

import java.util.List;

import pos.fs.AllocationMethod;
import pos.fs.FileSystem;
import pos.fs.FsLayout;
import pos.kernel.Kernel;
import pos.kernel.KernelReport;
import pos.kernel.Trace;
import pos.mm.MemorySetup;
import pos.proc.Assembler;
import pos.proc.Pcb;
import pos.sched.RoundRobinScheduler;
import pos.shell.Console;
import pos.shell.Keys;
import pos.shell.Shell;
import pos.shell.Terminal;

/**
 * Demostracion de la fase 7: la frontera entre el proceso y el nucleo.
 *
 * La demostracion no se limita a ensenar que las ordenes funcionan. Eso seria una
 * demo de una shell, no de un sistema operativo. Lo que hay que ver es que la
 * terminal es un proceso como cualquier otro: compite por el turno, se bloquea
 * cuando no tiene entrada, y sus ordenes son llamadas al sistema que el nucleo
 * cuenta, atiende y a veces deniega.
 *
 * Por eso hay tres partes:
 *
 *   1. Una sesion de terminal sobre una imagen de ficheros, para ver que hace.
 *   2. Una terminal compartiendo procesador con dos procesos de calculo, para ver
 *      que el reparto es real y no una funcion de la terminal.
 *   3. La contabilidad que queda en el nucleo: que llamadas se hicieron y cuantas
 *      veces.
 */
public final class ShellDemo {

    /** Memoria fisica del escenario, en unidades. */
    private static final int FISICAS = 4096;

    /** Pagina del escenario: pequena, para que la tabla de marcos se note. */
    private static final int PAGINA = 16;

    /** Cuanto de planificacion: corto, para que se vean los cambios de turno. */
    private static final int CUANTO = 6;

    /** Imagen de ficheros: bloques de 128 bytes, suficientes para la sesion. */
    static final int BLOQUES = 256;
    static final int BLOQUE = 128;

    /**
     * Programa que se arranca desde la terminal con spawn.
     *
     * Termina: si no, el sistema no acabaria nunca y la demostracion no
     * imprimiria nada. Lo que se quiere ver es que el proceso lo crea la
     * terminal, no que se pueda dejar corriendo para siempre.
     */
    private static final String TAREA = """
            SET 0
            loop: ADD 1
                  STORE 0
                  CMP 6
                  JZ fin
                  JMP loop
            fin:  EXIT
            """;

    /** Proceso de calculo que termina solo. */
    private static final String CONTADOR = """
            SET 0
            loop: ADD 1
                  STORE 0
                  CMP 9
                  JZ fin
                  JMP loop
            fin:  EXIT
            """;

    /**
     * Proceso de calculo que no termina nunca.
     *
     * Se usa para que la terminal tenga algo que matar: si el proceso acabara
     * solo, se perderia el unico caso en que el nucleo termina a otro por orden
     * de un tercero, que es donde hay que sacar los hilos de las colas a mano.
     */
    private static final String BUCLE = """
            SET 0
            loop: ADD 1
                  STORE 0
                  JMP loop
            """;

    private ShellDemo() {
        // Clase de demostracion: no se instancia.
    }

    public static void main(String[] args) {
        run();
    }

    public static void run() {
        System.out.println("=== fase 7: llamadas al sistema y terminal ===");
        System.out.println();
        unaSesion();
        System.out.println();
        elControlDeTrabajos();
        System.out.println();
        laTerminalCompiteConOtros();
        System.out.println();
        loQueQuedaEnElNucleo();
    }

    // ------------------------------------------------------------------
    // 1. Una sesion: lo que ve el usuario
    // ------------------------------------------------------------------

    private static void unaSesion() {
        System.out.println("=== una sesion de terminal ===");

        Kernel kernel = montar(imagen());
        Terminal terminal = terminal(kernel, Console.of(
                "pwd",
                "ls",
                "cat /leeme.txt",
                "echo hola desde la terminal > /saludo.txt",
                "cat /saludo.txt",
                "cat /leeme.txt | wc -l",
                "grep sistema /leeme.txt",
                "cp /leeme.txt /copia.txt",
                "stat /copia.txt",
                "mkdir /tmp",
                "cat /tarea.pos | head -n 3",
                "spawn tarea /tarea.pos",
                "sleep 20",
                "ps",
                "exit 0"));

        Pcb proceso = kernel.createHostedProcess("terminal", terminal);
        terminal.shell().attach(proceso.threads().get(0));
        kernel.run();

        System.out.println(terminal.transcript());
        System.out.println("estado final: el proceso de la terminal acabo con codigo "
                + kernel.syscalls().exitStatus(proceso.id()));
    }


    // ------------------------------------------------------------------
    // 1b. Control de trabajos
    // ------------------------------------------------------------------

    /**
     * Una sesion que para, manda al fondo y mata un trabajo.
     *
     * Lo interesante no es que las ordenes contesten, sino que el proceso
     * detenido desaparece de la cola de listos sin morir: durante todo el rato
     * que esta parado sigue existiendo, conserva su memoria y sus descriptores,
     * y no consume turno. Por eso se ensena tambien la traza del nucleo.
     */
    private static void elControlDeTrabajos() {
        System.out.println("=== control de trabajos: parar, mandar al fondo y matar ===");

        Kernel kernel = montar(imagen());
        Terminal terminal = terminal(kernel, Console.of(
                "spawn bucle /bucle.pos &",
                "jobs",
                "stop %1",
                "jobs",
                "ps",
                "bg %1",
                "jobs",
                "kill %1",
                "jobs",
                "exit 0"));
        Pcb proceso = kernel.createHostedProcess("terminal", terminal);
        terminal.shell().attach(proceso.threads().get(0));
        kernel.run();

        System.out.println(terminal.transcript());

        System.out.println("lo que apunto el nucleo:");
        for (Trace.Event event : kernel.trace().events()) {
            if (event.kind() == pos.kernel.EventKind.STOP
                    || event.kind() == pos.kernel.EventKind.CONT) {
                System.out.println("  " + event.line());
            }
        }
        System.out.println("reloj final: " + kernel.clock() + " tics");
    }

    // ------------------------------------------------------------------
    // 2. La terminal no tiene privilegios de planificacion
    // ------------------------------------------------------------------

    private static void laTerminalCompiteConOtros() {
        System.out.println("=== la terminal compite con dos procesos de calculo ===");
        System.out.println("cuanto " + CUANTO + " tics, tres procesos, dos de ellos calculando");

        Kernel kernel = montar(imagen());
        // Las lineas llegan espaciadas a proposito: en los huecos la terminal se
        // bloquea y los contadores avanzan. Si la terminal tuviera un turno
        // preferente, el hueco no existiria.
        Terminal terminal = terminal(kernel, new Console(List.of(
                new Keys.Keystroke(1, "ps"),
                new Keys.Keystroke(30, "kill 3"),
                new Keys.Keystroke(50, "clock"),
                new Keys.Keystroke(70, "ps"),
                new Keys.Keystroke(90, "exit 0"))));
        Pcb proceso = kernel.createHostedProcess("terminal", terminal);
        terminal.shell().attach(proceso.threads().get(0));

        kernel.createProcess("suma-a", Assembler.load("suma-a", CONTADOR), 0);
        kernel.createProcess("bucle-b", Assembler.load("bucle-b", BUCLE), 0);
        kernel.run();

        System.out.println(terminal.transcript());

        System.out.println("reparto del procesador:");
        for (String line : kernel.report().table()) {
            System.out.println("  " + line);
        }
        System.out.println();
        System.out.println("turnos (solo los cambios de hilo, no las llamadas):");
        int shown = 0;
        for (Trace.Event event : kernel.trace().events()) {
            if (event.kind() == pos.kernel.EventKind.SWITCH && shown < 18) {
                System.out.println("  " + event.line());
                shown++;
            }
        }
    }

    // ------------------------------------------------------------------
    // 3. Lo que el nucleo apunta
    // ------------------------------------------------------------------

    private static void loQueQuedaEnElNucleo() {
        System.out.println("=== contabilidad de llamadas ===");
        Kernel kernel = montar(imagen());
        Terminal terminal = terminal(kernel, Console.of(
                "cat /leeme.txt",
                "ls /notas",
                "echo una linea > /a.txt",
                "cat /a.txt | wc -c",
                "stat /a.txt",
                "exit 0"));
        Pcb proceso = kernel.createHostedProcess("terminal", terminal);
        terminal.shell().attach(proceso.threads().get(0));
        kernel.run();

        System.out.println(kernel.syscalls().report());
        System.out.println();
        System.out.println("la sesion hizo " + kernel.syscalls().total()
                + " llamadas y consumio " + kernel.syscalls().ticks() + " tics en atenderlas");
        System.out.println("la terminal gasto " + proceso.threads().get(0).cpuTicks()
                + " tics de procesador en total");
        System.out.println("reloj final: " + kernel.clock() + " tics");
    }

    // ------------------------------------------------------------------
    // Piezas comunes
    // ------------------------------------------------------------------

    /**
     * Imagen de ficheros con lo justo para que la sesion tenga algo que leer.
     *
     * Se usa asignacion indexada porque el escenario crea y borra ficheros de
     * tamanos distintos y no interesa que la fragmentacion decida el resultado de
     * la demostracion: eso ya se demostro en la fase 4.
     */
    static FileSystem imagen() {
        FileSystem fs = FileSystem.format(FsLayout.standard(BLOQUES, BLOQUE),
                new AllocationMethod.Indexed(), new pos.alloc.BestFit());
        fs.create("/leeme.txt", """
                este sistema es un simulador, no un sistema operativo de verdad
                el nucleo atiende llamadas, no ejecuta ordenes
                la terminal es un proceso mas y pide permiso para todo
                """);
        fs.create("/tarea.pos", TAREA);
        fs.create("/bucle.pos", BUCLE);
        fs.mkdir("/notas");
        fs.create("/notas/pi.txt", "3,141592\n");
        fs.create("/notas/e.txt", "2,718281\n");
        return fs;
    }

    private static Kernel montar(FileSystem fs) {
        Kernel kernel = new Kernel(new RoundRobinScheduler(CUANTO),
                MemorySetup.paged(FISICAS, 256, PAGINA));
        kernel.attachFilesystem(fs);
        return kernel;
    }

    private static Terminal terminal(Kernel kernel, Console console) {
        Shell shell = new Shell(kernel.syscalls());
        return new Terminal(shell, console, kernel::clock);
    }
}
