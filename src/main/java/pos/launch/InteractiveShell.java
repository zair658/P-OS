package pos.launch;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import pos.alloc.BestFit;
import pos.fs.FileSystem;
import pos.kernel.ConsoleSignal;
import pos.kernel.Kernel;
import pos.kernel.KernelObserver;
import pos.mm.MemorySetup;
import pos.proc.Pcb;
import pos.proc.State;
import pos.sched.RoundRobinScheduler;
import pos.shell.Command;
import pos.shell.Keys;
import pos.shell.LiveKeys;
import pos.shell.Output;
import pos.shell.SenalDeTeclado;
import pos.shell.Shell;
import pos.shell.Terminal;
import pos.shell.TtyKeys;

/**
 * Una sesion de terminal con teclado de verdad.
 *
 * El resto de modos del programa son demostraciones: llevan un guion escrito y
 * lo ejecutan hasta el final. Esto es lo mismo pero sin guion: alguien escribe,
 * el sistema contesta, y el estado del sistema de ficheros sobrevive entre
 * sesiones si se le da un fichero de imagen.
 *
 * La dificultad no esta en leer del teclado, esta en no romper la simulacion. Un
 * sistema con reloj simulado no puede quedarse esperando a una persona dentro de
 * un turno de procesador, porque entonces no hay turnos: mientras alguien piensa
 * que escribir, no corre nadie. Por eso la lectura vive en otro hilo
 * ({@link TtyKeys} o {@link LiveKeys}) y la terminal solo mira si ha llegado algo,
 * durmiendo unos tics entre mirada y mirada.
 *
 * Hay dos formas de leer y se eligen solas. Si hay un terminal delante, se lee
 * tecla a tecla ({@link TtyKeys}): eso permite historial con las flechas, cortar
 * con Ctrl-C y tabulador, porque cada tecla llega suelta. Si la entrada es una
 * tuberia o un fichero, no hay terminal que configurar y se leen lineas enteras
 * ({@link LiveKeys}), que es justo lo que esa entrada trae. La condicion no es un
 * capricho: {@code System.console()} devuelve algo solo cuando el proceso tiene
 * un terminal de verdad detras.
 *
 * El ritmo tambien es cosa de quien mira: el nucleo corre tan rapido como puede y
 * es este modo el que se toma unos milisegundos entre turno y turno para que la
 * simulacion no se coma la sesion en un parpadeo. Si se quita el freno, el
 * sistema sigue siendo correcto; simplemente deja de ser legible.
 */
public final class InteractiveShell {

    /** Tics de procesador por turno. */
    private static final int CUANTO = 4;

    /** Unidades de memoria fisica. */
    private static final int FISICAS = 4096;

    /**
     * Unidades de memoria logica por proceso.
     *
     * No es capricho que sea mucho menor que la fisica: cada proceso recibe su
     * espacio logico completo al nacer, asi que un valor del tamano de la
     * memoria fisica dejaria el sistema sin sitio para un segundo proceso y no
     * se podria arrancar nada. Con 256 caben varios.
     */
    private static final int LOGICAS = 256;

    /** Unidades por pagina. */
    private static final int PAGINA = 16;

    /**
     * Bytes por bloque de la imagen guardada.
     *
     * Es el mismo que usa la imagen de ejemplo, y tiene que serlo: una imagen
     * montada con otro tamano de bloque no se puede leer.
     */
    private static final int BLOQUE = ShellDemo.BLOQUE;

    /** Milisegundos entre turno y turno: el freno que hace la sesion legible. */
    private static final long FRENO = 3;

    private InteractiveShell() {
    }

    /**
     * Arranca la sesion.
     *
     * @param imagen fichero donde vive el sistema de ficheros; si no existe se
     *     crea con lo justo, y al salir se guarda. {@code null} para una sesion
     *     en memoria que no deja rastro
     */
    public static void run(Path imagen) {
        PrintStream out = new PrintStream(System.out, true, java.nio.charset.StandardCharsets.UTF_8);

        FileSystem fs = abrirImagen(imagen);
        Kernel kernel = new Kernel(new RoundRobinScheduler(CUANTO),
                MemorySetup.paged(FISICAS, LOGICAS, PAGINA));
        kernel.attachFilesystem(fs);

        Shell shell = new Shell(kernel.syscalls());
        // Con terminal delante se lee tecla a tecla, que es lo unico que permite
        // flechas, Ctrl-C y tabulador. Sin terminal (tuberia, fichero) no hay
        // modo crudo que poner, y sin stty (Windows) tampoco: en los dos casos se
        // leen lineas, que es lo que la entrada sabe entregar.
        Keys keys = System.console() == null ? null
                : TtyKeys.conTerminalCrudo(System.in, out, kernel::clock,
                        shell.history(), nombres(shell), senal(kernel));
        boolean lineaPorLinea = false;
        if (keys == null) {
            keys = new LiveKeys(System.in, kernel::clock);
            lineaPorLinea = true;
        }
        Terminal terminal = new Terminal(shell, keys, kernel::clock, salida(out), false);

        Pcb proceso = kernel.createHostedProcess("terminal", terminal);
        shell.attach(proceso.threads().get(0));

        out.println("P-OS: sesion de terminal. 'ayuda' lista las ordenes, 'exit' termina.");
        if (imagen != null) {
            out.println("imagen: " + imagen.toAbsolutePath());
        }
        if (lineaPorLinea) {
            // Sin modo crudo no se puede leer tecla a tecla: ni flechas, ni
            // tabulador, ni Ctrl-Z. Conviene decirlo, o parecera que la
            // terminal esta rota cuando lo que pasa es que este sistema no deja
            // poner la consola en crudo. El resto de la sesion funciona igual, y
            // el job control tambien, pero por orden escrita (stop, bg, fg).
            out.println("aviso: esta consola no admite modo crudo (hace falta stty);");
            out.println("       se lee linea a linea: sin flechas, sin tabulador y sin Ctrl-Z.");
            out.println("       el control de trabajos se hace por orden: stop, bg, fg, kill.");
        }
        out.println();

        kernel.setObserver(new Ritmo(FRENO, proceso));
        keys.start();
        kernel.run();

        out.println();
        out.println("sesion cerrada: " + kernel.clock() + " tics, "
                + kernel.syscalls().total() + " llamadas al sistema");
        guardarImagen(imagen, fs);
    }

    /**
     * La salida de las ordenes va tal cual a la pantalla.
     *
     * Se vacia en cada escritura porque detras hay una consola que el usuario
     * esta mirando: lo que se escribe es lo que tiene que ver, sin esperar a que
     * la linea siguiente traiga su salto.
     */
    private static Output salida(PrintStream out) {
        return new Output() {
            @Override
            public void print(String text) {
                out.print(text);
            }

            @Override
            public void flush() {
                out.flush();
            }
        };
    }

    /**
     * El puente entre Ctrl-Z y el nucleo.
     *
     * Se construye aqui, y no dentro del teclado, para que la parte que lee
     * teclas no tenga que saber que existe un nucleo: solo ve dos preguntas que
     * puede hacer y una orden que puede dar.
     */
    private static SenalDeTeclado senal(Kernel kernel) {
        return new SenalDeTeclado() {
            @Override
            public boolean hayPrimerPlano() {
                return kernel.hasForeground();
            }

            @Override
            public void parar() {
                kernel.postSignal(ConsoleSignal.PARAR);
            }
        };
    }

    /**
     * Los nombres de orden, para el tabulador.
     *
     * Se copian ahora, en este hilo, y no se consultan desde el hilo que lee: la
     * tabla es estado del shell y el lector no tiene por que tocarla.
     */
    private static List<String> nombres(Shell shell) {
        return shell.table().all().stream().map(Command::name).toList();
    }

    // ------------------------------------------------------------------
    // Imagen en disco
    // ------------------------------------------------------------------

    /**
     * Abre la imagen o crea una nueva.
     *
     * La imagen que se guarda es el disco entero, bloque a bloque, asi que al
     * volver a entrar los ficheros siguen donde estaban. Es la persistencia real
     * del sistema de ficheros, no un truco de la demostracion.
     */
    private static FileSystem abrirImagen(Path ruta) {
        if (ruta != null && Files.exists(ruta)) {
            try {
                byte[] raw = Files.readAllBytes(ruta);
                return FileSystem.mount(raw, BLOQUE, new BestFit());
            } catch (IOException e) {
                System.out.println("no se pudo leer la imagen, se empieza de cero: "
                        + e.getMessage());
            } catch (RuntimeException e) {
                // Una imagen de otro tamano o de otra version no se puede montar;
                // se avisa en vez de reventar con una traza.
                System.out.println("la imagen no encaja, se empieza de cero: " + e.getMessage());
            }
        }
        return ShellDemo.imagen();
    }

    /** Guarda el disco tal y como ha quedado. */
    private static void guardarImagen(Path ruta, FileSystem fs) {
        if (ruta == null) {
            return;
        }
        try {
            Files.write(ruta, fs.image().toByteArray());
            System.out.println("imagen guardada en " + ruta.toAbsolutePath());
        } catch (IOException e) {
            System.out.println("no se pudo guardar la imagen: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // El freno
    // ------------------------------------------------------------------

    /**
     * Frena el sistema para que se pueda leer, y cierra la sesion cuando el
     * teclado desaparece.
     *
     * El freno vive aqui y no en el nucleo por la misma razon que en la ventana:
     * dormir es cosa de quien mira, no de quien ejecuta.
     *
     * El cierre es menos evidente. Si al salir de la terminal queda un proceso de
     * fondo dando vueltas, el nucleo no termina nunca y la sesion se queda
     * colgada sin decir nada. Cuando la terminal muere, se matan los que quedan:
     * es lo que hace una shell de verdad al cerrarse.
     */
    private static final class Ritmo implements KernelObserver {

        private final long millis;
        private final Pcb terminal;
        private boolean cerrando;

        Ritmo(long millis, Pcb terminal) {
            this.millis = millis;
            this.terminal = terminal;
        }

        @Override
        public void afterTurn(Kernel kernel) {
            if (!cerrando && terminal.threads().get(0).state() == State.TERMINATED) {
                cerrando = true;
                for (Pcb otro : kernel.processes()) {
                    if (otro.id() != terminal.id()) {
                        kernel.kill(otro.id());
                    }
                }
            }
            if (millis <= 0) {
                return;
            }
            try {
                Thread.sleep(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
