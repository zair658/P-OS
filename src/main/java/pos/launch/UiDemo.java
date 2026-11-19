package pos.launch;

import java.util.List;

import pos.fs.AllocationMethod;
import pos.fs.FileSystem;
import pos.fs.FsLayout;
import pos.kernel.Kernel;
import pos.mm.MemorySetup;
import pos.proc.Assembler;
import pos.proc.Pcb;
import pos.sched.RoundRobinScheduler;
import pos.shell.Console;
import pos.shell.Shell;
import pos.shell.Terminal;
import pos.ui.UiFeed;

/**
 * El escenario que se ve en la ventana, y su arranque.
 *
 * Esta montado para que las cinco vistas tengan algo que ensenar a la vez, que
 * es mas dificil de lo que parece: un escenario de planificacion no tiene
 * cerrojos, uno de cerrojos no tiene memoria que mirar y uno de memoria no tiene
 * a nadie esperando. Aqui hay cinco procesos, dos de ellos con dos hilos, uno
 * peleandose por un cerrojo, otro pasandose permisos por semaforos y una
 * terminal pidiendo cosas al nucleo. Todo a la vez y todo corto, porque el
 * sistema tiene que terminar solo.
 *
 * La memoria es paginada y pequena a proposito. Con paginas de cuatro unidades y
 * dieciseis logicas por proceso, el mapa de la ventana ensena marcos sueltos y
 * huecos en medio en vez de un bloque gris de lado a lado.
 */
public final class UiDemo {

    /** Memoria fisica del escenario, en unidades. */
    private static final int FISICAS = 192;

    /** Memoria logica de cada proceso. */
    private static final int LOGICAS = 16;

    /** Tamano de pagina: pequeno, para que el mapa tenga textura. */
    private static final int PAGINA = 4;

    /** Cuanto de Round Robin. Corto, para que los turnos se vean pasar. */
    private static final int CUANTO = 3;

    /** Bloques de la imagen de ficheros de la terminal. */
    private static final int BLOQUES = 128;

    /** Tamano de bloque de la imagen. */
    private static final int BLOQUE = 128;

    /** Proceso que calcula y de vez en cuando espera, como el que lee un fichero. */
    private static final String CON_ESPERAS = """
            SET 0
            STORE 0
            loop: LOAD 0
                  ADD 1
                  STORE 0
                  SLEEP 3
                  LOAD 0
                  CMP 4
                  JZ fin
                  JMP loop
            fin:  EXIT
            """;

    /** Proceso que solo calcula: nunca suelta la CPU si no se la quitan. */
    private static final String SOLO_CALCULO = """
            SET 0
            loop: ADD 1
                  CMP 12
                  JZ fin
                  JMP loop
            fin:  EXIT
            """;

    /** Dos hilos sumando en el mismo contador, protegidos por un cerrojo. */
    private static final String CON_CERROJO = """
            SET 0
            STORE 0
            SET 0
            STORE 1
            SET 0
            STORE 2
            SPAWN hilo
            main:    LOCK cuenta
                     LOAD 0
                     ADD 1
                     STORE 0
                     UNLOCK cuenta
                     LOAD 1
                     ADD 1
                     STORE 1
                     CMP 4
                     JZ finmain
                     JMP main
            finmain: EXIT
            hilo:    LOCK cuenta
                     LOAD 0
                     ADD 1
                     STORE 0
                     UNLOCK cuenta
                     LOAD 2
                     ADD 1
                     STORE 2
                     CMP 4
                     JZ finhilo
                     JMP hilo
            finhilo: EXIT
            """;

    /** Productor y consumidor dandose el turno con dos semaforos. */
    private static final String CON_SEMAFOROS = """
            SEM_INIT hueco 1
            SEM_INIT dato 0
            SET 0
            STORE 0
            SET 0
            STORE 1
            SET 0
            STORE 2
            SPAWN consumidor
            p:       SEM_WAIT hueco
                     LOAD 0
                     ADD 1
                     STORE 0
                     SEM_POST dato
                     LOAD 1
                     ADD 1
                     STORE 1
                     CMP 3
                     JZ finp
                     JMP p
            finp:    EXIT
            consumidor:
            c:       SEM_WAIT dato
                     LOAD 0
                     SUB 1
                     STORE 0
                     SEM_POST hueco
                     LOAD 2
                     ADD 1
                     STORE 2
                     CMP 3
                     JZ finc
                     JMP c
            finc:    EXIT
            """;

    private UiDemo() {
        // Clase de arranque: no se instancia.
    }

    /**
     * Monta el sistema completo y deja el feed enganchado.
     *
     * El feed se engancha antes de crear los procesos para que la primera foto
     * -- la del arranque -- llegue tambien, y no solo las de despues.
     */
    public static Kernel build(UiFeed feed) {
        Kernel kernel = new Kernel(new RoundRobinScheduler(CUANTO),
                MemorySetup.paged(FISICAS, LOGICAS, PAGINA));
        kernel.attachFilesystem(imagen());
        kernel.setObserver(feed);

        kernel.createProcess("carga-a", Assembler.load("carga-a", CON_ESPERAS));
        kernel.createProcess("carga-b", Assembler.load("carga-b", SOLO_CALCULO));
        kernel.createProcess("contienda", Assembler.load("contienda", CON_CERROJO));
        kernel.createProcess("cola", Assembler.load("cola", CON_SEMAFOROS));

        Shell shell = new Shell(kernel.syscalls());
        Terminal terminal = new Terminal(shell, new Console(List.of(
                new Console.Keystroke(1, "ps"),
                new Console.Keystroke(24, "mem"),
                new Console.Keystroke(48, "clock"),
                new Console.Keystroke(72, "exit 0"))), kernel::clock);
        Pcb proceso = kernel.createHostedProcess("terminal", terminal);
        // La sesion tiene que saber en nombre de que hilo llama: sin esto, la
        // primera orden no puede pedir nada al nucleo y el proceso muere.
        terminal.shell().attach(proceso.threads().get(0));
        return kernel;
    }

    /** Imagen de ficheros con algo dentro, para que la terminal tenga que trabajar. */
    private static FileSystem imagen() {
        FileSystem fs = FileSystem.format(FsLayout.standard(BLOQUES, BLOQUE),
                new AllocationMethod.Indexed(), new pos.alloc.BestFit());
        fs.create("/leeme.txt", """
                P-OS: esto es un simulador, no un sistema operativo de verdad
                el nucleo atiende llamadas, no ejecuta ordenes
                la terminal es un proceso mas y pide permiso para todo
                """);
        fs.create("/notas.txt", "unidades fisicas, marcos, paginas\n");
        return fs;
    }

    /** Ejecuta el escenario sin ventana y devuelve el kernel ya cerrado. */
    public static Kernel runHeadless(UiFeed feed) {
        Kernel kernel = build(feed);
        kernel.run();
        return kernel;
    }
}
