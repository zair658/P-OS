package pos.sys;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import pos.base.PosFault;
import pos.fs.AllocationMethod;
import pos.fs.Fcb;
import pos.fs.FileSystem;
import pos.fs.FsCheck;
import pos.kernel.Kernel;
import pos.phys.PhysicalMemory;
import pos.proc.Pcb;
import pos.proc.State;
import pos.proc.Tcb;

/**
 * El nucleo visto desde fuera: aqui se atienden las llamadas al sistema.
 *
 * Esta clase es el unico camino por el que la terminal toca el mundo. No tiene
 * acceso a las estructuras internas del nucleo mas alla de lo que el propio
 * nucleo le ofrece, y todo lo que devuelve a un proceso es texto o un numero:
 * ninguna referencia a un FCB, a un marco de memoria o a un bloque de disco sale
 * de aqui.
 *
 * Tres decisiones que se notan leyendo el codigo:
 * <ul>
 *   <li>Cada llamada cuesta un tic de nucleo, aunque falle. El reloj lo mueve
 *       {@link Kernel#retireInstruction}, igual que con una instruccion, y asi
 *       el coste de una orden se puede medir en la tabla de la sesion.</li>
 *   <li>El directorio de trabajo es del proceso, no del sistema de ficheros.
 *       Vive en esta clase, indexado por identificador de proceso, y todas las
 *       rutas relativas se resuelven aqui. Un proceso no puede cambiar el
 *       directorio de otro.</li>
 *   <li>Un error esperable no lanza nada hacia fuera: se convierte en
 *       {@link SyscallResult#error}. Solo una llamada mal construida (un fallo de
 *       programacion) revienta con una excepcion.</li>
 * </ul>
 */
public final class SystemCalls {

    /** Descriptores simultaneos por proceso. */
    public static final int MAX_FDS = 16;

    private final Kernel kernel;
    private final Map<Integer, FdTable> tables = new LinkedHashMap<>();
    private final Map<Integer, String> cwd = new LinkedHashMap<>();
    private final Map<Integer, Long> sleeps = new LinkedHashMap<>();
    private final Map<Integer, Integer> exits = new LinkedHashMap<>();
    private final Map<Integer, Long> counts = new LinkedHashMap<>();
    private final Map<Integer, String> names = new LinkedHashMap<>();

    private FileSystem fs;
    private long total;
    private long ticks;

    public SystemCalls(Kernel kernel) {
        if (kernel == null) {
            throw new IllegalArgumentException("las llamadas necesitan un nucleo");
        }
        this.kernel = kernel;
    }

    /** Sistema de ficheros al que se dirigen las llamadas de fichero. */
    public void attachFilesystem(FileSystem fs) {
        this.fs = fs;
    }

    /** El nucleo que atiende las llamadas. */
    public Kernel kernel() {
        return kernel;
    }

    public FileSystem filesystem() {
        return fs;
    }

    // ------------------------------------------------------------------
    // Despacho
    // ------------------------------------------------------------------

    /**
     * Atiende una llamada en nombre del hilo que la hace.
     *
     * El tic se cobra antes de mirar los argumentos: una llamada a una ruta que
     * no existe tambien gasta CPU, que es justo lo que pasa en un sistema real.
     */
    public SyscallResult call(Tcb self, Syscall call) {
        if (self == null) {
            throw new IllegalArgumentException("una llamada necesita un hilo que la haga");
        }
        if (call == null) {
            throw new IllegalArgumentException("llamada vacia");
        }
        counts.merge(call.code(), 1L, Long::sum);
        names.putIfAbsent(call.code(), call.name());
        total++;
        ticks++;
        kernel.retireInstruction(self);
        try {
            return dispatch(self, call);
        } catch (PosFault fault) {
            return SyscallResult.failure(fault.getMessage());
        }
    }

    /**
     * Version comoda: la llamada tiene que salir bien o el proceso se entera.
     *
     * @throws PosFault con el mensaje del nucleo si la llamada fallo
     */
    public String must(Tcb self, Syscall call) {
        SyscallResult result = call(self, call);
        if (!result.ok()) {
            throw new PosFault(result.text());
        }
        return result.text();
    }

    private SyscallResult dispatch(Tcb self, Syscall call) {
        int pid = self.process().id();
        return switch (call) {
            case Syscall.Exit exit -> {
                exits.put(pid, exit.status());
                yield SyscallResult.success();
            }
            case Syscall.Write write -> {
                table(pid).write(write.fd(), write.text());
                yield SyscallResult.success();
            }
            case Syscall.Read read -> SyscallResult.success(table(pid).read(read.fd(), read.count()));
            case Syscall.Open open -> open(pid, open);
            case Syscall.Close close -> close(pid, close.fd());
            case Syscall.Stat stat -> stat(pid, stat.path());
            case Syscall.List list -> list(pid, list.path());
            case Syscall.Mkdir mkdir -> {
                fs().mkdir(resolve(pid, mkdir.path()));
                yield SyscallResult.success();
            }
            case Syscall.Unlink unlink -> {
                fs().remove(resolve(pid, unlink.path()));
                yield SyscallResult.success();
            }
            case Syscall.Chdir chdir -> chdir(pid, chdir.path());
            case Syscall.GetCwd ignored -> SyscallResult.success(cwdOf(pid));
            case Syscall.Ps ignored -> SyscallResult.success(processTable());
            case Syscall.Kill kill -> {
                if (kill.pid() == pid) {
                    throw new PosFault("un proceso no se mata a si mismo: para eso esta exit");
                }
                yield SyscallResult.success(Integer.toString(kernel.kill(kill.pid())));
            }
            case Syscall.Mem ignored -> SyscallResult.success(memoryReport());
            case Syscall.Sleep sleep -> {
                sleeps.merge(pid, Math.max(1L, sleep.ticks()), Long::sum);
                yield SyscallResult.success();
            }
            case Syscall.Clock ignored -> SyscallResult.success(Long.toString(kernel.clock()));
            case Syscall.Spawn spawn -> {
                Pcb child = kernel.spawnProcess(spawn.label(), spawn.source());
                yield SyscallResult.success(Integer.toString(child.id()));
            }
            case Syscall.FsInfo ignored -> SyscallResult.success(fs().usage().describe());
            case Syscall.FsCheck ignored -> checkReport();
            case Syscall.GetMethod ignored ->
                    SyscallResult.success(fs().method().name());
            case Syscall.SetMethod setMethod -> {
                FileSystem.Conversion conversion = fs().switchMethod(methodNamed(setMethod.method()));
                yield SyscallResult.success(conversion.describe());
            }
        };
    }

    // ------------------------------------------------------------------
    // Ficheros
    // ------------------------------------------------------------------

    private SyscallResult open(int pid, Syscall.Open open) {
        String path = resolve(pid, open.path());
        Fcb target = null;
        try {
            target = fs().resolve(path);
        } catch (PosFault missing) {
            if (open.mode() == Syscall.Mode.READ) {
                throw missing;
            }
        }
        String initial = "";
        if (target != null) {
            if (target.directory()) {
                throw new PosFault("'" + path + "' es un directorio y no se puede abrir");
            }
            if (open.mode() != Syscall.Mode.WRITE) {
                initial = fs().readText(path);
            }
        }
        int fd = table(pid).open(path, open.mode(), initial);
        return SyscallResult.success(Integer.toString(fd));
    }

    private SyscallResult close(int pid, int fd) {
        FdTable.Entry entry = table(pid).close(fd);
        if (!entry.changed()) {
            return SyscallResult.success();
        }
        if (exists(entry.path())) {
            fs().write(entry.path(), entry.content());
        } else {
            fs().create(entry.path(), entry.content());
        }
        return SyscallResult.success();
    }

    private SyscallResult stat(int pid, String raw) {
        String path = resolve(pid, raw);
        Fcb fcb = fs().resolve(path);
        return SyscallResult.success((fcb.directory() ? "d" : "f") + "\t" + fcb.size() + "\t"
                + fcb.allocation().dataBlocks() + "\t" + fcb.modified());
    }

    private SyscallResult list(int pid, String raw) {
        String path = resolve(pid, raw);
        List<String> names = new ArrayList<>();
        for (var entry : fs().list(path)) {
            names.add(entry.name());
        }
        return SyscallResult.success(String.join("\n", names));
    }

    private SyscallResult chdir(int pid, String raw) {
        String path = resolve(pid, raw);
        Fcb fcb = fs().resolve(path);
        if (!fcb.directory()) {
            throw new PosFault("'" + path + "' no es un directorio");
        }
        cwd.put(pid, path);
        return SyscallResult.success(path);
    }

    // ------------------------------------------------------------------
    // Procesos y memoria
    // ------------------------------------------------------------------

    private String processTable() {
        StringBuilder text = new StringBuilder();
        for (Pcb process : kernel.processes()) {
            long cpu = 0;
            int live = 0;
            for (Tcb thread : process.threads()) {
                cpu += thread.cpuTicks();
                if (thread.state() != State.TERMINATED) {
                    live++;
                }
            }
            text.append(process.id()).append('\t')
                    .append(process.name()).append('\t')
                    .append(stateText(processState(process))).append('\t')
                    .append(process.threads().size()).append('\t')
                    .append(live).append('\t')
                    .append(cpu).append('\t')
                    .append(process.memoryUnits()).append('\n');
        }
        return text.toString();
    }

    private String memoryReport() {
        PhysicalMemory physical = kernel.memory().physical();
        int size = physical.size();
        int free = physical.freeUnits();
        int used = size - free;
        StringBuilder text = new StringBuilder();
        text.append("esquema\t").append(kernel.memory().scheme().name()).append('\n');
        text.append("fisica\t").append(size).append('\n');
        text.append("ocupada\t").append(used).append('\n');
        text.append("libre\t").append(free).append('\n');
        text.append("huecos\t").append(physical.freeSpans().size()).append('\n');
        text.append("procesos\t").append(kernel.processes().size()).append('\n');
        text.append("hilos\t").append(kernel.threads().size()).append('\n');
        return text.toString();
    }

    private SyscallResult checkReport() {
        FsCheck check = fs().verify();
        StringBuilder text = new StringBuilder(check.summary());
        for (String problem : check.problems()) {
            text.append('\n').append("* ").append(problem);
        }
        return SyscallResult.success(text.toString());
    }

    private static AllocationMethod methodNamed(String name) {
        String key = name == null ? "" : name.trim().toLowerCase();
        return switch (key) {
            case "contiguo", "contiguous" -> new AllocationMethod.Contiguous();
            case "enlazado", "linked" -> new AllocationMethod.Linked();
            case "indexado", "indexed" -> new AllocationMethod.Indexed();
            default -> throw new PosFault("metodo desconocido: '" + name
                    + "'; usa contiguo, enlazado o indexado");
        };
    }

    /**
     * Estado que se muestra para un proceso entero.
     *
     * El PCB no corre: quien corre es el hilo. Si se pregunta al PCB sale siempre
     * "nuevo", porque nadie lo cambia al despachar. Se resume de sus hilos, que es
     * donde esta la verdad: manda el que este mas adelante (ejecutando antes que
     * listo, listo antes que bloqueado), y si no queda ninguno vivo, termino.
     */
    private static State processState(Pcb process) {
        State summary = State.TERMINATED;
        for (Tcb thread : process.threads()) {
            State state = thread.state();
            if (state == State.RUNNING) {
                return State.RUNNING;
            }
            if (state == State.READY) {
                summary = State.READY;
            } else if (state == State.BLOCKED && summary != State.READY) {
                summary = State.BLOCKED;
            } else if (state == State.NEW && summary == State.TERMINATED) {
                summary = State.NEW;
            }
        }
        return summary;
    }

    private static String stateText(State state) {
        return switch (state) {
            case NEW -> "nuevo";
            case READY -> "listo";
            case RUNNING -> "ejecutando";
            case BLOCKED -> "bloqueado";
            case TERMINATED -> "terminado";
        };
    }

    // ------------------------------------------------------------------
    // Utilidades para la terminal
    // ------------------------------------------------------------------

    /** Tics que el proceso tiene pendientes de dormir, y los olvida. */
    public long drainSleep(int pid) {
        Long pending = sleeps.remove(pid);
        return pending == null ? 0L : pending;
    }

    /** Codigo de salida pedido, o {@code null} si no ha pedido salir. */
    public Integer exitStatus(int pid) {
        return exits.get(pid);
    }

    /** Suelta los descriptores de un proceso muerto sin guardar nada. */
    public int release(int pid) {
        sleeps.remove(pid);
        exits.remove(pid);
        cwd.remove(pid);
        FdTable table = tables.remove(pid);
        return table == null ? 0 : table.abandon();
    }

    /** Directorio de trabajo del proceso. */
    public String cwdOf(int pid) {
        return cwd.getOrDefault(pid, FileSystem.ROOT);
    }

    /** Descriptores abiertos del proceso, para las pruebas y la contabilidad. */
    public int openDescriptors(int pid) {
        FdTable table = tables.get(pid);
        return table == null ? 0 : table.openCount();
    }

    // ------------------------------------------------------------------
    // Contabilidad
    // ------------------------------------------------------------------

    /** Una fila de la tabla de llamadas. */
    public record Row(int code, String name, long calls) {
    }

    /** Llamadas hechas, por numero y ordenadas. */
    public List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        counts.keySet().stream().sorted().forEach(code ->
                rows.add(new Row(code, names.getOrDefault(code, "?"), counts.get(code))));
        return List.copyOf(rows);
    }

    public long total() {
        return total;
    }

    /** Tics de nucleo gastados atendiendo llamadas. */
    public long ticks() {
        return ticks;
    }

    public long count(int code) {
        return counts.getOrDefault(code, 0L);
    }

    public void reset() {
        counts.clear();
        names.clear();
        total = 0;
        ticks = 0;
    }

    /** Tabla de contabilidad en texto, para imprimirla al cerrar la sesion. */
    public String report() {
        StringBuilder text = new StringBuilder();
        text.append(String.format("%-5s %-12s %8s%n", "cod", "llamada", "veces"));
        text.append("-".repeat(28)).append('\n');
        for (Row row : rows()) {
            text.append(String.format("%-5d %-12s %8d%n", row.code(), row.name(), row.calls()));
        }
        text.append("-".repeat(28)).append('\n');
        text.append(String.format("%-18s %8d%n", "total llamadas", total));
        text.append(String.format("%-18s %8d%n", "tics de nucleo", ticks));
        return text.toString();
    }

    // ------------------------------------------------------------------
    // Interno
    // ------------------------------------------------------------------

    private FileSystem fs() {
        if (fs == null) {
            throw new PosFault("no hay sistema de ficheros montado");
        }
        return fs;
    }

    private FdTable table(int pid) {
        return tables.computeIfAbsent(pid, key -> new FdTable(MAX_FDS));
    }

    private boolean exists(String path) {
        try {
            fs().resolve(path);
            return true;
        } catch (PosFault missing) {
            return false;
        }
    }

    /**
     * Convierte una ruta del proceso en una ruta absoluta y normalizada.
     *
     * Es el nucleo el que resuelve, no el proceso: asi el proceso no puede
     * escapar de su directorio de trabajo inventando "..", porque la
     * normalizacion se hace una sola vez y contra la raiz.
     */
    private String resolve(int pid, String path) {
        if (path == null || path.isBlank()) {
            throw new PosFault("ruta vacia");
        }
        String base = path.startsWith(FileSystem.ROOT) ? path : cwdOf(pid) + "/" + path;
        String normalized = fs().normalize(base);
        if (!normalized.startsWith(FileSystem.ROOT)) {
            throw new PosFault("la ruta '" + path + "' se sale de la raiz");
        }
        return normalized;
    }
}
