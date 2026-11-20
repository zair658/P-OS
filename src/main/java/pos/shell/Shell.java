package pos.shell;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import pos.base.PosFault;
import pos.proc.State;
import pos.proc.Tcb;
import pos.sys.Syscall;
import pos.sys.SyscallResult;
import pos.sys.SystemCalls;

/**
 * La cascara: lo que queda cuando le quitas la pantalla y el teclado.
 *
 * Aqui vive el estado de la sesion (quien soy, donde estoy, que he escrito) y
 * aqui se traduce cada necesidad de una orden a una llamada al sistema. Las
 * ordenes no ven el nucleo: ven este objeto. Es la misma separacion que hay en
 * un sistema real entre la biblioteca del programador y la interfaz de llamadas,
 * solo que aqui caben las dos en un fichero legible.
 *
 * La sesion tambien lleva la cuenta de tres cosas que no son llamadas: las
 * peticiones de dormir, de esperar a un proceso y de terminar. Las tres se
 * apuntan y las ejecuta la terminal al final del paso, porque son cosas que solo
 * puede hacer el hilo que tiene el turno: dormir o morir desde dentro de una
 * orden dejaria la orden a medias.
 *
 * Y lleva la tabla de trabajos, que no es lo mismo que la tabla de procesos: un
 * trabajo es un proceso visto desde esta sesion, con su numero y su plano.
 */
public final class Shell {

    private final SystemCalls system;
    private final CommandTable table;
    private final List<String> history = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final JobTable jobs = new JobTable();
    private Tcb self;
    private long sleepTicks;
    private boolean background;
    private boolean waitPending;

    public Shell(SystemCalls system) {
        this(system, CommandTable.standard());
    }

    public Shell(SystemCalls system, CommandTable table) {
        if (system == null) {
            throw new IllegalArgumentException("la cascara necesita las llamadas del nucleo");
        }
        this.system = system;
        this.table = table;
    }

    /** Ata la sesion al hilo que la ejecuta. Se hace una vez, al crearlo. */
    public void attach(Tcb thread) {
        if (thread == null) {
            throw new IllegalArgumentException("la sesion necesita un hilo");
        }
        this.self = thread;
    }

    public Tcb self() {
        return self;
    }

    public SystemCalls system() {
        return system;
    }

    public CommandTable table() {
        return table;
    }

    /**
     * Lo escrito en la sesion, como vista viva de la lista.
     *
     * Es una vista y no una copia a proposito: el editor de linea la consulta
     * desde su propio hilo en cuanto se pulsa una flecha, y con una copia veria
     * el historial congelado en el momento en que se construyo. La lista de
     * debajo admite lectura y escritura a la vez, que es lo que pasa aqui: la
     * terminal anade lineas mientras el teclado las recorre.
     */
    public List<String> history() {
        return Collections.unmodifiableList(history);
    }

    /** Tic actual del reloj simulado. */
    public long clock() {
        return system.kernel().clock();
    }

    /** Ruta de trabajo de la sesion. */
    public String cwd() {
        return system.must(self, new Syscall.GetCwd());
    }

    /** Lo que se muestra antes de cada linea. */
    public String prompt() {
        String dir = cwd();
        return "pos:" + (dir.isEmpty() ? "/" : dir) + "> ";
    }

    /**
     * Ejecuta una linea completa: la parte en etapas, resuelve tuberias y
     * redirecciones y llama a cada orden.
     */
    public void runLine(String raw, Output out) {
        String line = raw == null ? "" : raw.trim();
        if (line.isEmpty()) {
            return;
        }
        history.add(line);
        reap(out);
        LineParser.ParsedLine parsed;
        try {
            parsed = LineParser.parse(line);
        } catch (PosFault fault) {
            out.println("error: " + fault.getMessage());
            return;
        }
        List<LineParser.Stage> stages = parsed.stages();
        Input stdin = Input.EMPTY;
        for (int i = 0; i < stages.size(); i++) {
            LineParser.Stage stage = stages.get(i);
            boolean last = i == stages.size() - 1;
            Input in = stage.stdinFile() != null ? new Input(readFile(stage.stdinFile())) : stdin;
            TextBuffer buffer = last && stage.stdoutFile() == null ? null : new TextBuffer();
            Output sink = buffer == null ? out : buffer;
            runStage(stage, in, sink);
            if (buffer == null) {
                stdin = Input.EMPTY;
            } else if (stage.stdoutFile() != null && !last) {
                // Redireccion en medio de una tuberia: el texto va al fichero y
                // ademas sigue por el tubo, que es lo que hace una shell de
                // verdad con 'foo > f | bar'.
                writeFile(stage.stdoutFile(), buffer.text(), stage.append());
                stdin = new Input(buffer.text());
            } else if (stage.stdoutFile() != null) {
                writeFile(stage.stdoutFile(), buffer.text(), stage.append());
                stdin = Input.EMPTY;
            } else {
                stdin = new Input(buffer.text());
            }
            if (exitRequested()) {
                return;
            }
        }
    }

    private void runStage(LineParser.Stage stage, Input in, Output out) {
        Command command = table.find(stage.name());
        if (command == null) {
            out.println(stage.name() + ": orden no encontrada; escribe 'help' para ver la lista");
            return;
        }
        int tracked = jobs.size();
        background = stage.background();
        try {
            command.run(this, stage.args(), in, out);
        } catch (PosFault fault) {
            out.println(stage.name() + ": " + fault.getMessage());
        } finally {
            background = false;
        }
        if (stage.background() && jobs.size() == tracked) {
            // Un & sobre algo que no arranca un proceso no tiene a quien
            // aplicarse. Decirlo es mejor que fingir que se hizo algo.
            out.println("&: '" + stage.name()
                    + "' no arranca ningun proceso; el & solo vale para 'spawn'");
        }
    }

    /** Los trabajos vivos de la sesion. */
    public JobTable jobs() {
        return jobs;
    }

    /** Si la orden que se esta ejecutando llego con {@code &}. */
    public boolean inBackground() {
        return background;
    }

    /**
     * Apunta que la terminal debe esperar a un proceso al final del paso.
     *
     * Lo pide {@code fg}: quien devuelve el primer plano a un trabajo se queda
     * esperando a que acabe, como en cualquier terminal. La espera en si la
     * arranca la terminal, no la orden, porque bloquearse desde dentro de la
     * orden dejaria la orden a medias.
     */
    public void requestWait() {
        waitPending = true;
    }

    /**
     * Tope de espera del primer plano, en ticks.
     *
     * Existe porque un trabajo puede no terminar nunca: un bucle sin salida
     * puesto en primer plano dejaria la terminal muda para siempre, y sin
     * Ctrl-Z (que es cosa de Unix) no habria forma de recuperarla. Con un tope,
     * lo peor que pasa es que vuelva el aviso y el trabajo siga a lo suyo, que
     * es justo lo que se puede arreglar con {@code stop} o {@code kill}.
     *
     * Cuatrocientos ticks son de sobra para un programa corto y lo bastante
     * pocos para que nadie se quede mirando una pantalla quieta.
     */
    public static final long ESPERA_MAXIMA = 400L;

    /** Si habia una espera pedida y la consume. */
    public boolean drainWait() {
        boolean pending = waitPending;
        waitPending = false;
        return pending;
    }

    /**
     * Retira de la tabla los trabajos que ya terminaron, avisando de cada uno.
     *
     * Se llama al principio de cada linea: el trabajo murio mientras nadie
     * miraba, y el momento de contarlo es cuando el usuario vuelve a escribir.
     * Cuesta una llamada por trabajo vivo, y solo si hay alguno; una sesion sin
     * trabajos no paga nada por esta funcion.
     */
    private void reap(Output out) {
        if (jobs.isEmpty()) {
            return;
        }
        for (JobTable.Job job : jobs.all()) {
            String state = jobState(job.pid());
            if (state.isEmpty() || state.equals(State.TERMINATED.etiqueta())) {
                jobs.remove(job.pid());
                out.println(job + ": terminado");
            }
        }
    }

    /**
     * El estado de un proceso segun el nucleo, o vacio si ya no existe.
     *
     * Vuelve a preguntar cada vez en vez de guardarlo: un trabajo que dice
     * "ejecutando" y lleva diez tics muerto es peor que no tener tabla.
     */
    public String jobState(int pid) {
        SyscallResult result = call(new Syscall.Job(pid));
        if (!result.ok()) {
            return "";
        }
        String[] parts = result.text().split("\t");
        return parts.length < 3 ? "" : parts[2];
    }

    /** Una llamada que puede fallar: devuelve el resultado en vez de lanzar. */
    public SyscallResult call(Syscall call) {
        return system.call(self, call);
    }

    /** Una llamada que tiene que salir bien; si falla, lanza. */
    public String must(Syscall call) {
        return system.must(self, call);
    }

    /** Lee un fichero entero a traves de un descriptor. */
    public String readFile(String path) {
        int fd = Integer.parseInt(must(new Syscall.Open(path, Syscall.Mode.READ)));
        StringBuilder text = new StringBuilder();
        try {
            while (true) {
                String chunk = must(new Syscall.Read(fd, 4096));
                if (chunk.isEmpty()) {
                    break;
                }
                text.append(chunk);
            }
        } finally {
            // Cerrar siempre, aunque la lectura haya fallado a medias: si no, el
            // descriptor se queda ocupado y la sesion se queda sin tabla.
            call(new Syscall.Close(fd));
        }
        return text.toString();
    }

    /** Escribe un fichero, entero o anadiendo al final. */
    public void writeFile(String path, String text, boolean append) {
        int fd = Integer.parseInt(must(new Syscall.Open(path,
                append ? Syscall.Mode.APPEND : Syscall.Mode.WRITE)));
        try {
            if (!text.isEmpty()) {
                must(new Syscall.Write(fd, text));
            }
        } finally {
            call(new Syscall.Close(fd));
        }
    }

    /** Nombres de las entradas de un directorio, sin la marca final. */
    public List<String> listDir(String path) {
        String text = must(new Syscall.List(path));
        List<String> names = new ArrayList<>();
        for (String line : text.split("\n")) {
            if (!line.isEmpty()) {
                names.add(line);
            }
        }
        return names;
    }

    /** Datos de una entrada: el texto de la llamada stat ya interpretado. */
    public record Entry(boolean directory, int size, int blocks, int tick) {

        public char kind() {
            return directory ? 'd' : 'f';
        }
    }

    public Entry stat(String path) {
        String text = must(new Syscall.Stat(path));
        String[] parts = text.split("\t");
        if (parts.length != 4) {
            throw new PosFault("respuesta de stat ilegible: " + text);
        }
        return new Entry(parts[0].startsWith("d"), Integer.parseInt(parts[1]),
                Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
    }

    public boolean exists(String path) {
        return call(new Syscall.Stat(path)).ok();
    }

    /**
     * Cierra la sesion como si se hubiera escrito exit, si no se cerro ya.
     *
     * Se usa cuando se acaban las teclas: la sesion termina igual que un programa
     * que llega a su ultima instruccion, con codigo 0. Dejar el codigo sin
     * apuntar haria que un guion normal pareciera un fallo.
     */
    public void close() {
        if (!exitRequested()) {
            requestExit(0);
        }
    }

    /** Pide terminar la sesion con ese codigo. Lo ejecuta la terminal. */
    public void requestExit(int status) {
        must(new Syscall.Exit(status));
    }

    public Integer exitStatus() {
        return system.exitStatus(self.process().id());
    }

    public boolean exitRequested() {
        return exitStatus() != null;
    }

    /** Pide dormir tantos tics. Lo ejecuta la terminal. */
    public void requestSleep(long ticks) {
        if (ticks < 0) {
            throw new PosFault("no se puede dormir un tiempo negativo");
        }
        sleepTicks += ticks;
    }

    /** Tics pedidos y no consumidos todavia. */
    public long drainSleep() {
        long total = sleepTicks;
        sleepTicks = 0;
        return total;
    }

    /** Tics pedidos sin consumir. */
    public long pendingSleep() {
        return sleepTicks;
    }

    /** El nombre de una entrada sin su ruta. */
    public static String baseName(String path) {
        String clean = path == null ? "" : path.trim();
        while (clean.length() > 1 && clean.endsWith("/")) {
            clean = clean.substring(0, clean.length() - 1);
        }
        int slash = clean.lastIndexOf('/');
        return slash < 0 ? clean : clean.substring(slash + 1);
    }

    /** Cuelga un nombre de una ruta, sin duplicar la barra. */
    public static String join(String dir, String name) {
        if (dir.isEmpty() || dir.equals("/")) {
            return "/" + name;
        }
        return dir.endsWith("/") ? dir + name : dir + "/" + name;
    }
}
