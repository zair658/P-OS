package pos.ui;

import java.util.ArrayList;
import java.util.List;

import pos.kernel.Kernel;
import pos.mm.MemorySetup;
import pos.phys.PhysicalMemory;
import pos.proc.Pcb;
import pos.proc.State;
import pos.proc.Tcb;
import pos.sync.Banker;
import pos.sync.LockManager;
import pos.sys.SystemCalls;

/**
 * Traduce el estado vivo del nucleo a una {@link Snapshot}.
 *
 * Este es el unico sitio del proyecto donde se lee el sistema entero de golpe, y
 * por eso se le llama solo desde el aviso de turno cerrado: alli no hay nadie
 * dentro del interprete y las colas no se estan moviendo. Leer desde el hilo de
 * la ventana seria pedirle al sistema que se quedara quieto mientras le miran, y
 * eso ni es cierto ni hace falta.
 *
 * La traduccion no opina: copia lo que hay, con los textos en castellano que ya
 * usa el resto del sistema para no inventar un vocabulario paralelo.
 */
public final class Snapshotter {

    /** Lineas de traza que viajan en cada foto. La ventana no necesita mas. */
    public static final int TRACE_LINES = 80;

    private Snapshotter() {
        // Clase de utilidad: no se instancia.
    }

    /** Foto del sistema en este instante. Debe llamarse con el turno cerrado. */
    public static Snapshot capture(Kernel kernel) {
        long clock = kernel.clock();
        List<Pcb> processes = kernel.processes();
        List<Tcb> threads = kernel.threads();

        return new Snapshot(
                clock,
                kernel.scheduler().name(),
                kernel.memory().describe(),
                phaseOf(threads, clock),
                processes(processes),
                threads(threads, clock),
                memory(kernel.memory()),
                ready(kernel),
                cpuTicks(threads),
                sync(kernel),
                calls(kernel),
                trace(kernel),
                kernel.trace().truncated());
    }

    /**
     * En que momento esta el sistema: recien montado, ejecutando o cerrado.
     *
     * Se mira hilo a hilo en vez de usar una bandera del nucleo a proposito: la
     * foto tiene que poder decir "terminado" aunque se tome desde fuera y nadie
     * haya avisado de que se acabo.
     */
    private static String phaseOf(List<Tcb> threads, long clock) {
        if (threads.isEmpty()) {
            return "listo";
        }
        boolean soloDetenidos = false;
        for (Tcb thread : threads) {
            State state = thread.state();
            if (state == State.STOPPED) {
                // Ni ejecuta ni espera: el sistema no esta trabajando, pero
                // tampoco ha acabado. Decir "ejecutando" con todo el mundo en
                // pausa seria mentir en la barra de estado.
                soloDetenidos = true;
                continue;
            }
            if (state != State.TERMINATED) {
                return clock == 0 ? "listo" : "ejecutando";
            }
        }
        return soloDetenidos ? "en pausa" : "terminado";
    }

    private static List<Snapshot.Proc> processes(List<Pcb> processes) {
        List<Snapshot.Proc> rows = new ArrayList<>(processes.size());
        for (Pcb process : processes) {
            rows.add(new Snapshot.Proc(
                    process.id(),
                    process.name(),
                    process.state().etiqueta(),
                    process.threads().size(),
                    (int) process.liveThreads(),
                    process.memoryUnits(),
                    process.physicalUnits(),
                    process.space() == null ? "-" : process.space().schemeName()));
        }
        return rows;
    }

    private static List<Snapshot.ThreadInfo> threads(List<Tcb> threads, long clock) {
        List<Snapshot.ThreadInfo> rows = new ArrayList<>(threads.size());
        for (Tcb thread : threads) {
            rows.add(new Snapshot.ThreadInfo(
                    thread.id(),
                    thread.process().name(),
                    thread.state().etiqueta(),
                    thread.pc(),
                    thread.cpuTicks(),
                    thread.waitTicks(clock),
                    thread.turnaround(clock),
                    thread.blockedOn() == null ? "-" : thread.blockedOn(),
                    thread.failure() == null ? "" : thread.failure()));
        }
        return rows;
    }

    private static Snapshot.MemoryMap memory(MemorySetup setup) {
        PhysicalMemory physical = setup.physical();
        int size = physical.size();
        int[] owners = new int[size];
        for (int unit = 0; unit < size; unit++) {
            owners[unit] = physical.ownerAt(unit);
        }
        return new Snapshot.MemoryMap(size, physical.usedUnits(), physical.freeUnits(),
                setup.describe(), owners);
    }

    private static List<Snapshot.Ready> ready(Kernel kernel) {
        List<Snapshot.Ready> rows = new ArrayList<>();
        List<Tcb> queue = kernel.readyQueue();
        for (int i = 0; i < queue.size(); i++) {
            Tcb thread = queue.get(i);
            rows.add(new Snapshot.Ready(i + 1, thread.id(), thread.process().name()));
        }
        return rows;
    }

    private static long cpuTicks(List<Tcb> threads) {
        long total = 0;
        for (Tcb thread : threads) {
            total += thread.cpuTicks();
        }
        return total;
    }

    private static Snapshot.Sync sync(Kernel kernel) {
        LockManager locks = kernel.locks();
        List<Snapshot.LockRow> lockRows = new ArrayList<>();
        for (String name : locks.names()) {
            Tcb owner = locks.ownerOf(name);
            lockRows.add(new Snapshot.LockRow(name, owner == null ? "-" : owner.id(),
                    locks.waitingCount(name)));
        }

        List<Snapshot.SemRow> semRows = new ArrayList<>();
        for (String name : kernel.semaphores().names()) {
            semRows.add(new Snapshot.SemRow(name, kernel.semaphores().valueOf(name),
                    kernel.semaphores().waitingCount(name)));
        }

        List<Snapshot.CondRow> condRows = new ArrayList<>();
        for (String name : kernel.conditions().names()) {
            condRows.add(new Snapshot.CondRow(name, kernel.conditions().lockOf(name),
                    kernel.conditions().waitingCount(name)));
        }

        Banker banquero = kernel.banker();
        List<Snapshot.ResRow> resRows = new ArrayList<>();
        for (String name : banquero.resources()) {
            StringBuilder holders = new StringBuilder();
            for (Banker.Row row : banquero.table()) {
                if (row.resource().equals(name) && row.allocated() > 0) {
                    holders.append(holders.length() == 0 ? "" : ", ")
                            .append(row.process()).append(' ').append(row.allocated());
                }
            }
            resRows.add(new Snapshot.ResRow(name, banquero.capacityOf(name),
                    banquero.availableOf(name), holders.toString(),
                    banquero.waitingCount(name)));
        }

        LockManager.Deadlock deadlock = locks.findDeadlock();
        return new Snapshot.Sync(lockRows, semRows, condRows, resRows,
                deadlock == null ? null : deadlock.describe());
    }

    private static Snapshot.Calls calls(Kernel kernel) {
        SystemCalls syscalls = kernel.syscalls();
        List<Snapshot.CallRow> rows = new ArrayList<>();
        for (SystemCalls.Row row : syscalls.rows()) {
            rows.add(new Snapshot.CallRow(row.code(), row.name(), row.calls()));
        }
        return new Snapshot.Calls(syscalls.total(), syscalls.ticks(), rows);
    }

    private static List<String> trace(Kernel kernel) {
        List<String> lines = kernel.trace().lines();
        int from = Math.max(0, lines.size() - TRACE_LINES);
        return lines.subList(from, lines.size());
    }
}
