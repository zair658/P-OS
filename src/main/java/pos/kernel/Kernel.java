package pos.kernel;

import java.util.ArrayList;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pos.base.PosFault;
import pos.mm.AddressSpace;
import pos.mm.MemorySetup;
import pos.proc.ExecutionHost;
import pos.proc.Interpreter;
import pos.proc.Pcb;
import pos.proc.ProcessFactory;
import pos.proc.Program;
import pos.proc.State;
import pos.proc.Tcb;
import pos.proc.TurnOutcome;
import pos.sched.Scheduler;
import pos.sched.SchedulingDecision;

/**
 * El nucleo: el unico que decide quien ejecuta.
 *
 * Mantiene el reloj, la cola de listos, los bloqueados y la lista completa de
 * hilos, y hace el ciclo de siempre: admitir llegadas, despertar a quien toca,
 * preguntar al planificador, conceder el turno y recoger el resultado.
 *
 * Dos decisiones que conviene tener claras al leer el codigo:
 * <ul>
 *   <li>El reloj avanza por instruccion retirada, no por tiempo real. Asi el
 *       mismo escenario da siempre la misma traza y las tablas de comparacion
 *       significan algo.</li>
 *   <li>El nucleo no ejecuta instrucciones: solo cede el turno y espera. Quien
 *       ejecuta es el hilo real de cada proceso, y por eso un fallo dentro de un
 *       proceso no tumba al sistema.</li>
 * </ul>
 */
public final class Kernel implements ExecutionHost {

    private final Scheduler scheduler;
    private final MemorySetup memory;
    private final Trace trace;
    private final TurnBaton baton = new TurnBaton();
    private final Interpreter interpreter = new Interpreter();

    private final Map<Integer, Pcb> processes = new LinkedHashMap<>();
    private final List<Tcb> allThreads = new ArrayList<>();
    private final Deque<Tcb> ready = new ArrayDeque<>();
    private final List<Tcb> blocked = new ArrayList<>();

    private long clock;
    private int nextProcessId = 1;
    private boolean started;

    /** Nucleo con esquema contiguo y memoria sobrada para varios procesos. */
    public Kernel(Scheduler scheduler, int memoryUnits) {
        this(scheduler, memoryUnits, new Trace());
    }

    public Kernel(Scheduler scheduler, int memoryUnits, Trace trace) {
        this(scheduler, defaultMemory(memoryUnits), trace);
    }

    public Kernel(Scheduler scheduler, MemorySetup memory) {
        this(scheduler, memory, new Trace());
    }

    public Kernel(Scheduler scheduler, MemorySetup memory, Trace trace) {
        if (scheduler == null) {
            throw new IllegalArgumentException("el nucleo necesita un planificador");
        }
        if (memory == null) {
            throw new IllegalArgumentException("el nucleo necesita memoria");
        }
        this.scheduler = scheduler;
        this.memory = memory;
        this.trace = trace;
    }

    /**
     * Configuracion por defecto: contiguo y con sitio para ocho procesos.
     *
     * Se deja margen a proposito. Un nucleo que solo cupiera un proceso no
     * demostraria nada sobre planificacion, y la memoria de la maquina virtual
     * es barata.
     */
    private static MemorySetup defaultMemory(int logicalUnits) {
        if (logicalUnits <= 0) {
            throw new IllegalArgumentException("cada proceso necesita memoria");
        }
        return MemorySetup.contiguous(Math.max(4096, logicalUnits * 8), logicalUnits);
    }

    // ------------------------------------------------------------------
    // Construccion del escenario
    // ------------------------------------------------------------------

    public Pcb createProcess(String name, Program program) {
        return createProcess(name, program, clock);
    }

    public Pcb createProcess(String name, Program program, long arrivalTick) {
        if (started) {
            throw new IllegalStateException("no se pueden crear procesos con el nucleo en marcha");
        }
        int id = nextProcessId;
        // La memoria se pide antes de consumir el identificador: si no cabe, el
        // proceso no llega a existir y no queda un hueco en la numeracion.
        AddressSpace space = memory.newSpace(id, name);
        nextProcessId++;

        Pcb process = ProcessFactory.newProcess(id, name, program, space.logicalUnits(), arrivalTick);
        process.attachSpace(space);
        processes.put(id, process);
        trace.add(clock, EventKind.PROCESS_CREATE, "p" + id,
                name + " '" + program.name() + "' (" + program.size() + " instrucciones, "
                        + space.logicalUnits() + " unidades logicas)");
        trace.add(clock, EventKind.MEMORY_ALLOC, "p" + id,
                memory.scheme().name() + ": " + space.physicalUnits() + " unidades fisicas en "
                        + space.reservedSpans().size() + " tramo(s), " + wasteText(space));
        createThread(process, 0, null);
        return process;
    }

    @Override
    public void createThread(Pcb process, int entryPc, Tcb parent) {
        long arrival = parent == null ? process.arrivalTick() : clock;
        Tcb thread = ProcessFactory.newThread(process, process.threads().size() + 1, arrival);
        thread.setPc(entryPc);
        thread.setState(State.NEW);
        allThreads.add(thread);

        Thread carrier = new Thread(new ProcessThread(this, thread, interpreter), thread.id());
        carrier.setDaemon(true);
        thread.setCarrier(carrier);

        trace.add(clock, EventKind.THREAD_CREATE, thread.id(),
                "arranca en la instruccion " + entryPc + " de " + process.name());
        carrier.start();
    }

    // ------------------------------------------------------------------
    // Ciclo del nucleo
    // ------------------------------------------------------------------

    public KernelReport run() {
        if (started) {
            throw new IllegalStateException("el nucleo ya se ejecuto");
        }
        started = true;
        trace.add(clock, EventKind.BOOT, "nucleo",
                "planificador " + scheduler.name() + ", " + memory.describe());

        try {
            while (true) {
                admitArrivals();
                wakeSleepers();
                if (finished()) {
                    break;
                }
                SchedulingDecision decision = scheduler.pick(new ArrayList<>(ready), clock);
                if (decision == null) {
                    if (!advanceIdle()) {
                        break;
                    }
                    continue;
                }
                dispatch(decision);
            }
        } finally {
            shutdown();
        }

        trace.add(clock, EventKind.DONE, "nucleo", "reloj final " + clock + " ticks");
        return report();
    }

    private void dispatch(SchedulingDecision decision) {
        Tcb thread = decision.thread();
        ready.remove(thread);
        thread.setState(State.RUNNING);
        thread.markStarted(clock);

        trace.add(clock, EventKind.SWITCH, thread.id(),
                decision.reason() + ", cuanto " + decision.sliceText());
        baton.grant(thread, decision.slice());

        TurnOutcome outcome = thread.outcome();
        if (outcome == null) {
            throw new PosFault("el hilo " + thread.id() + " devolvio el turno sin decir por que");
        }

        switch (outcome) {
            case PREEMPTED -> {
                thread.setState(State.READY);
                ready.addLast(thread);
                trace.add(clock, EventKind.PREEMPT, thread.id(), "se le acabo el cuanto");
            }
            case YIELDED -> {
                thread.setState(State.READY);
                ready.addLast(thread);
                trace.add(clock, EventKind.YIELD, thread.id(), "cedio el turno");
            }
            case BLOCKED -> {
                long sleep = Math.max(1L, thread.pendingSleep());
                thread.setWakeTick(clock + sleep);
                thread.setState(State.BLOCKED);
                blocked.add(thread);
                trace.add(clock, EventKind.BLOCK, thread.id(),
                        "duerme " + sleep + " ticks, hasta el " + thread.wakeTick());
            }
            case EXITED -> {
                thread.setState(State.TERMINATED);
                thread.markFinished(clock);
                trace.add(clock, EventKind.EXIT, thread.id(),
                        "termina con cpu " + thread.cpuTicks() + " ticks");
                if (ProcessFactory.closeIfEmpty(thread.process())) {
                    releaseMemory(thread.process());
                }
            }
        }
    }

    /**
     * Devuelve a memoria fisica todo lo que ocupaba un proceso terminado.
     *
     * Se hace aqui y no al cerrar el sistema porque liberar tarde falsea
     * cualquier medida de fragmentacion tomada a mitad de ejecucion.
     */
    private void releaseMemory(Pcb process) {
        AddressSpace space = process.space();
        if (space == null) {
            return;
        }
        int units = space.physicalUnits();
        space.release();
        trace.add(clock, EventKind.MEMORY_FREE, "p" + process.id(),
                "devuelve " + units + " unidades fisicas");
    }

    private static String wasteText(AddressSpace space) {
        if (space.wastedUnits() == 0) {
            return "sin desperdicio";
        }
        return "desperdicia " + space.wastedUnits() + " unidades";
    }

    private void admitArrivals() {
        for (Tcb thread : allThreads) {
            if (thread.state() == State.NEW && thread.arrivalTick() <= clock) {
                thread.setState(State.READY);
                ready.addLast(thread);
                trace.add(clock, EventKind.ADMIT, thread.id(), "entra en la cola de listos");
            }
        }
    }

    private void wakeSleepers() {
        Iterator<Tcb> iterator = blocked.iterator();
        while (iterator.hasNext()) {
            Tcb thread = iterator.next();
            if (thread.wakeTick() >= 0 && thread.wakeTick() <= clock) {
                iterator.remove();
                thread.setState(State.READY);
                ready.addLast(thread);
                trace.add(clock, EventKind.WAKE, thread.id(), "vuelve a listos");
            }
        }
    }

    /**
     * Cuando no hay nadie listo pero queda algo pendiente, el reloj salta al
     * proximo suceso en vez de girar en vacio. Ese salto no es un atajo: es lo
     * que haria un sistema que solo gasta CPU cuando hay algo que ejecutar.
     *
     * @return false si nadie puede despertar ya (bloqueo mutuo o fin)
     */
    private boolean advanceIdle() {
        long next = Long.MAX_VALUE;
        for (Tcb thread : allThreads) {
            if (thread.state() == State.NEW && thread.arrivalTick() > clock) {
                next = Math.min(next, thread.arrivalTick());
            }
            if (thread.state() == State.BLOCKED && thread.wakeTick() > clock) {
                next = Math.min(next, thread.wakeTick());
            }
        }
        if (next == Long.MAX_VALUE) {
            trace.add(clock, EventKind.DEADLOCK, "nucleo",
                    "no queda ningun hilo capaz de despertar");
            return false;
        }
        trace.add(clock, EventKind.IDLE, "nucleo", "sin listos; el reloj salta a " + next);
        clock = next;
        return true;
    }

    private boolean finished() {
        if (allThreads.isEmpty()) {
            return true;
        }
        for (Tcb thread : allThreads) {
            if (thread.state() != State.TERMINATED) {
                return false;
            }
        }
        return true;
    }

    private void shutdown() {
        baton.stop();
        for (Tcb thread : allThreads) {
            Thread carrier = thread.carrier();
            if (carrier == null) {
                continue;
            }
            try {
                carrier.join(2_000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (carrier.isAlive()) {
                trace.add(clock, EventKind.FAULT, thread.id(),
                        "el hilo no termino al cerrar el sistema");
            }
        }
        for (Tcb thread : allThreads) {
            if (thread.state() != State.TERMINATED) {
                thread.setState(State.TERMINATED);
                if (thread.failure() == null) {
                    thread.markFailed("seguia pendiente al cerrar el sistema");
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Servicios para los hilos
    // ------------------------------------------------------------------

    @Override
    public void retireInstruction(Tcb thread) {
        clock++;
        thread.addCpuTick();
    }

    /** Un hilo ha reventado: se anota y se deja constancia en su bloque. */
    void onThreadFault(Tcb thread, Throwable failure) {
        String message = failure.getMessage() != null
                ? failure.getMessage()
                : failure.getClass().getSimpleName();
        thread.markFailed(message);
        trace.add(clock, EventKind.FAULT, thread.id(), message);
    }

    // ------------------------------------------------------------------
    // Consulta
    // ------------------------------------------------------------------

    TurnBaton baton() {
        return baton;
    }

    public long clock() {
        return clock;
    }

    public Scheduler scheduler() {
        return scheduler;
    }

    /** Configuracion de memoria con la que corre este nucleo. */
    public MemorySetup memory() {
        return memory;
    }

    public Trace trace() {
        return trace;
    }

    public List<Pcb> processes() {
        return List.copyOf(processes.values());
    }

    public List<Tcb> threads() {
        return List.copyOf(allThreads);
    }

    public KernelReport report() {
        return KernelReport.of(clock, scheduler.name(), allThreads, trace.truncated());
    }
}
