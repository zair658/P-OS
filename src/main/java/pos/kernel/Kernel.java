package pos.kernel;

import java.util.ArrayList;
import java.util.Deque;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pos.base.PosFault;
import pos.fs.FileSystem;
import pos.mm.AddressSpace;
import pos.mm.MemorySetup;
import pos.proc.Assembler;
import pos.proc.BlockKind;
import pos.proc.ExecutionHost;
import pos.proc.HostedTask;
import pos.proc.Instruction;
import pos.proc.Interpreter;
import pos.proc.Pcb;
import pos.proc.ProcessFactory;
import pos.proc.Program;
import pos.proc.State;
import pos.proc.Tcb;
import pos.proc.TurnOutcome;
import pos.sched.Scheduler;
import pos.sched.SchedulingDecision;
import pos.sync.ConditionManager;
import pos.sync.LockManager;
import pos.sync.SemaphoreManager;
import pos.sys.SystemCalls;

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

    /** Tope de procesos vivos: mas alla, la memoria del escenario no da de si. */
    public static final int MAX_PROCESSES = 32;

    /** Programa de mentira que llevan los procesos nativos, como el terminal. */
    private static final Program TRAMPOLINE = new Program("nativa",
            List.of(new Instruction.Nop(), new Instruction.Exit()));

    private final Scheduler scheduler;
    private final MemorySetup memory;
    private final Trace trace;
    private final TurnBaton baton = new TurnBaton();
    private final Interpreter interpreter = new Interpreter();

    private final Map<Integer, Pcb> processes = new LinkedHashMap<>();
    private final List<Tcb> allThreads = new ArrayList<>();
    private final Deque<Tcb> ready = new ArrayDeque<>();
    private final List<Tcb> blocked = new ArrayList<>();

    private final LockManager locks = new LockManager();
    private final SemaphoreManager semaphores = new SemaphoreManager();
    private final ConditionManager conditions = new ConditionManager();
    private final SystemCalls syscalls = new SystemCalls(this);
    private final Map<Integer, List<Integer>> dumps = new LinkedHashMap<>();

    private long clock;
    private int nextProcessId = 1;
    private boolean started;
    private boolean keepFinalMemory;

    /** Quien mira el sistema mientras corre, o {@code null} si a nadie le importa. */
    private KernelObserver observer;

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
        return boot(name, program, arrivalTick, null);
    }

    /**
     * Crea un proceso cuyo primer hilo ejecuta codigo nativo.
     *
     * El programa que se le asigna es un trampolin de dos instrucciones que no
     * llega a ejecutarse nunca: sirve para que el bloque de control tenga un
     * programa como cualquier otro y para que la tabla de resultados muestre
     * algo coherente. Quien ejecuta es la tarea enganchada al hilo.
     */
    public Pcb createHostedProcess(String name, HostedTask task) {
        if (started) {
            throw new IllegalStateException("no se pueden crear procesos con el nucleo en marcha");
        }
        if (task == null) {
            throw new IllegalArgumentException("un proceso nativo necesita su tarea");
        }
        return boot(name, TRAMPOLINE, clock, task);
    }

    private Pcb boot(String name, Program program, long arrivalTick, HostedTask task) {
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
        createThread(process, 0, null, task);
        return process;
    }

    /**
     * Arranca un proceso con el nucleo ya en marcha.
     *
     * Es lo que hace la llamada {@code spawn} de la terminal. Se admite en
     * caliente porque si no, la terminal seria un adorno: un sistema donde no se
     * pueden crear procesos desde dentro no demuestra nada de planificacion. El
     * hilo nuevo entra en la cola de listos en la siguiente vuelta del ciclo,
     * cuando el nucleo mire las llegadas.
     *
     * Hay un tope de procesos y la memoria se comprueba igual que en el arranque:
     * si no cabe, la llamada falla y el proceso no llega a existir.
     *
     * @throws PosFault si el codigo no ensambla, si no cabe en memoria o si se
     *     ha llegado al tope
     */
    public Pcb spawnProcess(String name, String source) {
        if (!started) {
            throw new IllegalStateException("para arrancar en caliente el nucleo tiene que estar en marcha");
        }
        if (processes.size() >= MAX_PROCESSES) {
            throw new PosFault("no se pueden crear mas de " + MAX_PROCESSES + " procesos");
        }
        Program program = Assembler.load(name, source);
        Pcb child = boot(name, program, clock, null);
        trace.add(clock, EventKind.PROCESS_CREATE, "p" + child.id(),
                "arrancado desde otro proceso, entra en listos en la proxima vuelta");
        return child;
    }

    /**
     * Termina un proceso y todos sus hilos.
     *
     * Los hilos se sacan de las colas antes de marcarlos: si uno se quedara en
     * la de listos, el planificador le daria el turno a un muerto y el hilo real
     * que lo transporta ya no estaria esperando, con lo que el nucleo se
     * quedaria esperando un turno que no vuelve.
     *
     * @return cuantos hilos estaban vivos y se han terminado
     */
    public int kill(int pid) {
        Pcb process = processes.get(pid);
        if (process == null) {
            throw new PosFault("no existe el proceso " + pid);
        }
        if (process.state() == State.TERMINATED) {
            return 0;
        }
        int killed = 0;
        for (Tcb thread : process.threads()) {
            if (thread.state() == State.TERMINATED) {
                continue;
            }
            ready.remove(thread);
            blocked.remove(thread);
            thread.clearBlock();
            thread.setState(State.TERMINATED);
            thread.markFinished(clock);
            thread.markFailed("terminado por kill, no por su propio EXIT");
            killed++;
        }
        ProcessFactory.terminate(process);
        captureFinalMemory(process);
        int pending = syscalls.release(pid);
        trace.add(clock, EventKind.EXIT, "p" + pid,
                "kill: " + killed + " hilo(s) terminados, " + pending
                        + " descriptor(es) descartados sin guardar");
        return killed;
    }

    @Override
    public void createThread(Pcb process, int entryPc, Tcb parent) {
        createThread(process, entryPc, parent, null);
    }

    private void createThread(Pcb process, int entryPc, Tcb parent, HostedTask task) {
        long arrival = parent == null ? process.arrivalTick() : clock;
        Tcb thread = ProcessFactory.newThread(process, process.threads().size() + 1, arrival);
        thread.setPc(entryPc);
        thread.setState(State.NEW);
        thread.attachHosted(task);
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
        // Primera foto: el sistema recien arrancado, antes de ejecutar nada.
        notifyTurn();

        try {
            while (true) {
                admitArrivals();
                wakeSleepers();
                wakeWaiters();
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
        // Ultima foto: el estado final, con la memoria de los procesos ya liberada.
        notifyTurn();
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
                thread.setState(State.BLOCKED);
                blocked.add(thread);
                if (thread.waitsForResource()) {
                    // Quien espera un cerrojo, un permiso o una condicion no
                    // tiene hora de despertar: lo despertara otro. Se limpia la
                    // marca de espera por si venia de un SLEEP anterior, o el
                    // barrido de dormidos lo despertaria por error.
                    thread.setWakeTick(-1);
                    trace.add(clock, EventKind.BLOCK, thread.id(), blockText(thread));
                } else {
                    long sleep = Math.max(1L, thread.pendingSleep());
                    thread.setWakeTick(clock + sleep);
                    thread.setBlockedKind(BlockKind.DORMIR);
                    trace.add(clock, EventKind.BLOCK, thread.id(),
                            "duerme " + sleep + " ticks, hasta el " + thread.wakeTick());
                }
            }
            case EXITED -> {
                thread.setState(State.TERMINATED);
                thread.markFinished(clock);
                trace.add(clock, EventKind.EXIT, thread.id(),
                        "termina con cpu " + thread.cpuTicks() + " ticks");
                // Un hilo que se va sin soltar sus cerrojos los deja en poder de
                // un muerto. Se anota, porque el sintoma que vera el usuario es
                // que otros hilos se quedan dormidos sin motivo aparente.
                for (String held : locks.heldBy(thread)) {
                    trace.add(clock, EventKind.LOCK_ORPHAN, thread.id(),
                            "termina sin liberar el cerrojo '" + held + "'");
                }
                Pcb process = thread.process();
                if (ProcessFactory.closeIfEmpty(process)) {
                    captureFinalMemory(process);
                    releaseMemory(process);
                }
            }
        }
        // Se avisa con el turno ya cerrado: no hay nadie dentro del interprete y
        // las colas estan estables. Es el unico momento en que se puede leer el
        // sistema entero sin cerrojos ni estados a medias.
        notifyTurn();
    }

    /**
     * Apunta a alguien que quiera ver el sistema mientras corre.
     *
     * Solo puede haber uno, y se puede quitar pasando {@code null}. El aviso
     * llega al cerrar cada turno y tambien en el arranque y en el cierre, para
     * que quien mire tenga siempre una foto inicial y una final.
     */
    public void setObserver(KernelObserver observer) {
        this.observer = observer;
    }

    /**
     * Da el aviso de turno cerrado.
     *
     * Si el observador revienta se anota una vez y se desconecta: una interfaz
     * rota no puede tumbar el sistema que esta mirando, y repetir el aviso solo
     * llenaria la traza de miles de lineas iguales.
     */
    private void notifyTurn() {
        if (observer == null) {
            return;
        }
        try {
            observer.afterTurn(this);
        } catch (RuntimeException failure) {
            trace.add(clock, EventKind.FAULT, "observador",
                    "la interfaz fallo y se desconecta: " + failure.getMessage());
            observer = null;
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

    /**
     * Despierta a los hilos que ya tienen lo que esperaban.
     *
     * La concesion la decidio el hilo que libero, señalo o devolvio un permiso,
     * pero mover el hilo de la lista de bloqueados a la de listos lo hace el
     * nucleo, aqui, en su propio turno. Asi la cola de listos solo la toca un
     * sitio y el orden de la traza sigue siendo el mismo en cada ejecucion.
     *
     * El unico caso con un paso extra es la condicion. Quien esperaba en una
     * condicion solto el cerrojo al dormirse, asi que al ser señalado no lo tiene:
     * aqui se le vuelve a pedir. Si se lo ha llevado otro mientras tanto, el hilo
     * no vuelve a listos, cambia de motivo de espera y sigue durmiendo en la cola
     * del cerrojo. Esa es toda la diferencia entre una condicion y un semaforo, y
     * es lo que hace que el bucle de comprobacion del monitor sea obligatorio.
     */
    private void wakeWaiters() {
        List<Tcb> woken = new ArrayList<>();
        woken.addAll(locks.drainGranted());
        woken.addAll(semaphores.drainGranted());
        woken.addAll(conditions.drainGranted());

        for (Tcb thread : woken) {
            String name = thread.blockedOn();
            BlockKind kind = thread.blockedKind();
            String lock = thread.reacquireLock();
            if (lock != null) {
                thread.setReacquireLock(null);
                if (!locks.acquire(thread, lock)) {
                    Tcb owner = locks.ownerOf(lock);
                    thread.setBlockedKind(BlockKind.CERROJO);
                    thread.setBlockedOn(lock);
                    trace.add(clock, EventKind.WAKE, thread.id(),
                            "despierta de la condicion '" + name + "' pero el cerrojo '" + lock
                                    + "' esta en poder de "
                                    + (owner == null ? "nadie" : owner.id())
                                    + "; vuelve a dormir, ahora por el cerrojo");
                    continue;
                }
                trace.add(clock, EventKind.LOCK, thread.id(),
                        "readquiere el cerrojo '" + lock + "' antes de seguir");
            }
            blocked.remove(thread);
            thread.clearBlock();
            thread.setState(State.READY);
            ready.addLast(thread);
            trace.add(clock, EventKind.WAKE, thread.id(), wakeText(kind, name));
        }
    }

    /** Que estaba esperando el hilo, para el mensaje de bloqueo. */
    private String blockText(Tcb thread) {
        String name = thread.blockedOn();
        return switch (thread.blockedKind()) {
            case CERROJO -> {
                Tcb owner = locks.ownerOf(name);
                yield "espera el cerrojo '" + name + "', en poder de "
                        + (owner == null ? "nadie" : owner.id());
            }
            case SEMAFORO -> "espera un permiso de '" + name + "', que esta a cero"
                    + " y tiene " + semaphores.waitingCount(name) + " hilo(s) en la cola";
            case CONDICION -> "duerme en la condicion '" + name
                    + "' y su cerrojo quedo libre";
            default -> "espera sin motivo conocido";
        };
    }

    /** Texto del despertar, segun lo que estuviera esperando. */
    private String wakeText(BlockKind kind, String name) {
        return switch (kind) {
            case CERROJO -> "obtiene el cerrojo '" + name + "' y vuelve a listos";
            case SEMAFORO -> "obtiene un permiso de '" + name + "' y vuelve a listos";
            case CONDICION -> "vuelve a listos con el cerrojo de la condicion '" + name + "'";
            default -> "vuelve a listos";
        };
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
            // Nadie tiene hora de despertar. Puede ser un ciclo de cerrojos o
            // un cerrojo que se quedo sin duenyo vivo; el grafo de espera dice
            // cual de las dos cosas es, y con nombres y apellidos.
            LockManager.Deadlock deadlock = locks.findDeadlock();
            if (deadlock != null) {
                trace.add(clock, EventKind.DEADLOCK, "nucleo", deadlock.describe());
                trace.add(clock, EventKind.DEADLOCK, "nucleo", "anillo: " + deadlock.ring());
            } else {
                List<String> orphans = locks.orphanLocks();
                if (!orphans.isEmpty()) {
                    trace.add(clock, EventKind.DEADLOCK, "nucleo",
                            "cerrojos que nadie va a liberar: " + String.join(", ", orphans));
                }
                // Los semaforos y las condiciones no tienen dueño, asi que el
                // grafo no puede cerrar un ciclo con ellos. En vez de inventarse
                // uno, se dice quien espera que: es lo unico que se sabe de
                // verdad.
                List<String> stuck = new ArrayList<>();
                stuck.addAll(semaphores.describeBlocked());
                stuck.addAll(conditions.describeBlocked());
                if (stuck.isEmpty()) {
                    trace.add(clock, EventKind.DEADLOCK, "nucleo",
                            "no queda ningun hilo capaz de despertar");
                } else {
                    trace.add(clock, EventKind.DEADLOCK, "nucleo",
                            "nadie puede despertar a: " + String.join("; ", stuck));
                }
            }
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
                    // En la tabla de resultados interesa distinguir "no llego a
                    // terminar" de "se quedo esperando un cerrojo que nadie iba
                    // a soltar": son dos averias distintas del mismo sintoma. La
                    // nota empieza por "bloqueado para siempre" porque la columna
                    // de estado ya dice "terminado": el sistema lo cerro, no es
                    // que el hilo hubiera acabado su trabajo.
                    thread.markFailed(stuckText(thread));
                }
            }
        }
    }

    /** Nota para la tabla de resultados de un hilo que no llego a terminar. */
    private String stuckText(Tcb thread) {
        String name = thread.blockedOn();
        if (name == null) {
            return "seguia pendiente al cerrar el sistema";
        }
        return switch (thread.blockedKind()) {
            case CERROJO -> "bloqueado para siempre esperando el cerrojo '" + name + "'";
            case SEMAFORO -> "bloqueado para siempre esperando un permiso de '" + name + "'";
            case CONDICION -> "bloqueado para siempre esperando la condicion '" + name + "'";
            default -> "seguia pendiente al cerrar el sistema";
        };
    }

    // ------------------------------------------------------------------
    // Servicios para los hilos
    // ------------------------------------------------------------------

    @Override
    public void retireInstruction(Tcb thread) {
        clock++;
        thread.addCpuTick();
    }

    @Override
    public boolean acquireLock(Tcb thread, String name) {
        boolean acquired = locks.acquire(thread, name);
        if (acquired) {
            trace.add(clock, EventKind.LOCK, thread.id(), "toma el cerrojo '" + name + "'");
            return true;
        }
        // Queda apuntado en la cola del cerrojo y ademas dice por que espera: el
        // bloqueo lo materializa el nucleo al recoger el turno.
        thread.setBlockedOn(name);
        thread.setBlockedKind(BlockKind.CERROJO);
        Tcb owner = locks.ownerOf(name);
        trace.add(clock, EventKind.LOCK, thread.id(),
                "no consigue el cerrojo '" + name + "', en poder de "
                        + (owner == null ? "nadie" : owner.id()));
        return false;
    }

    @Override
    public void releaseLock(Tcb thread, String name) {
        Tcb next = locks.release(thread, name);
        trace.add(clock, EventKind.UNLOCK, thread.id(), next == null
                ? "libera el cerrojo '" + name + "', queda libre"
                : "libera el cerrojo '" + name + "' y se lo cede a " + next.id());
    }

    @Override
    public void initSemaphore(Tcb thread, String name, int initial) {
        semaphores.init(thread, name, initial);
        trace.add(clock, EventKind.SEM_INIT, thread.id(),
                "crea el semaforo '" + name + "' con " + initial + " permiso(s)");
    }

    @Override
    public boolean waitSemaphore(Tcb thread, String name) {
        boolean taken = semaphores.waitOn(thread, name);
        if (taken) {
            trace.add(clock, EventKind.SEM_WAIT, thread.id(),
                    "toma un permiso de '" + name + "', quedan "
                            + semaphores.valueOf(name));
            return true;
        }
        thread.setBlockedOn(name);
        thread.setBlockedKind(BlockKind.SEMAFORO);
        trace.add(clock, EventKind.SEM_WAIT, thread.id(),
                "no quedan permisos en '" + name + "', se pone a esperar");
        return false;
    }

    @Override
    public void postSemaphore(Tcb thread, String name) {
        Tcb next = semaphores.post(thread, name);
        trace.add(clock, EventKind.SEM_POST, thread.id(), next == null
                ? "devuelve un permiso a '" + name + "', queda en "
                        + semaphores.valueOf(name)
                : "devuelve un permiso a '" + name + "' y se lo cede a " + next.id());
    }

    @Override
    public void waitCondition(Tcb thread, String condition, String lock) {
        // El cerrojo tiene que estar en su poder: soltarlo es parte de dormir, y
        // si no lo tuviera, el sistema estaria deshaciendo una exclusion mutua
        // que nunca existio.
        if (locks.ownerOf(lock) != thread) {
            throw new PosFault(thread.id() + ": espera en la condicion '" + condition
                    + "' sin tener el cerrojo '" + lock + "'");
        }
        // Primero se apunta en la cola y solo despues se suelta el cerrojo. Al
        // reves habria un instante en que el hilo ni esta protegido ni va a
        // enterarse de la señal, y una señal en ese hueco se perderia.
        conditions.waitOn(thread, condition, lock);
        thread.setBlockedOn(condition);
        thread.setBlockedKind(BlockKind.CONDICION);
        trace.add(clock, EventKind.COND_WAIT, thread.id(),
                "espera en '" + condition + "' y suelta el cerrojo '" + lock + "'");
        Tcb heir = locks.release(thread, lock);
        trace.add(clock, EventKind.UNLOCK, thread.id(), heir == null
                ? "suelta '" + lock + "' al dormir, queda libre"
                : "suelta '" + lock + "' al dormir y se lo cede a " + heir.id());
    }

    @Override
    public Tcb signalCondition(Tcb thread, String condition) {
        String lock = conditions.lockOf(condition);
        if (lock != null && locks.ownerOf(lock) != thread) {
            throw new PosFault(thread.id() + ": señala la condicion '" + condition
                    + "' sin tener el cerrojo '" + lock + "'");
        }
        Tcb next = conditions.signal(thread, condition);
        trace.add(clock, EventKind.COND_SIGNAL, thread.id(), next == null
                ? "señala '" + condition + "', no esperaba nadie (señal perdida)"
                : "señala '" + condition + "' y despierta a " + next.id());
        return next;
    }

    @Override
    public int broadcastCondition(Tcb thread, String condition) {
        String lock = conditions.lockOf(condition);
        if (lock != null && locks.ownerOf(lock) != thread) {
            throw new PosFault(thread.id() + ": despierta la condicion '" + condition
                    + "' sin tener el cerrojo '" + lock + "'");
        }
        int count = conditions.broadcast(thread, condition);
        trace.add(clock, EventKind.COND_SIGNAL, thread.id(),
                "despierta la condicion '" + condition + "'"
                        + (count == 0
                                ? ", no esperaba nadie"
                                : ", despierta a " + count + " hilo(s)"));
        return count;
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

    /**
     * La cola de listos, en el orden en que el planificador los vera.
     *
     * Es informacion de dentro del sistema, no un detalle de la demostracion:
     * sin ella no se puede ensenar por que Round Robin elige a quien elige, y
     * una tabla de planificacion sin el orden de la cola es media tabla.
     */
    public List<Tcb> readyQueue() {
        return List.copyOf(ready);
    }

    /** Los hilos bloqueados, en el orden en que se bloquearon. */
    public List<Tcb> blockedQueue() {
        return List.copyOf(blocked);
    }

    /** Las llamadas al sistema, vistas desde dentro del nucleo. */
    public SystemCalls syscalls() {
        return syscalls;
    }

    /** Engancha el sistema de ficheros a las llamadas de fichero. */
    public void attachFilesystem(FileSystem fs) {
        syscalls.attachFilesystem(fs);
    }

    public KernelReport report() {
        return KernelReport.of(clock, scheduler.name(), allThreads, trace.truncated());
    }

    // ------------------------------------------------------------------
    // Volcado final de la memoria de un proceso
    // ------------------------------------------------------------------

    /**
     * Pide al nucleo que guarde una copia del contenido logico de cada proceso
     * justo antes de liberar su memoria.
     *
     * Esta apagado por defecto. Existe porque al cerrarse un proceso su memoria
     * fisica se devuelve y se pone a cero, de modo que desde fuera no hay forma
     * de comprobar en que quedo un calculo compartido; con el volcado se puede
     * afirmar "el contador acabo en 5" en vez de deducirlo de la traza. El
     * volcado lee con {@code load}, asi que en un esquema con paginas por
     * demanda provocaria faltas de pagina: no se activa en esas demostraciones.
     */
    public void keepFinalMemory(boolean keep) {
        this.keepFinalMemory = keep;
    }

    /** Copia del contenido logico del proceso, o {@code null} si no hubo volcado. */
    public List<Integer> finalMemory(int processId) {
        return dumps.get(processId);
    }

    private void captureFinalMemory(Pcb process) {
        if (!keepFinalMemory) {
            return;
        }
        AddressSpace space = process.space();
        if (space == null) {
            return;
        }
        int units = space.logicalUnits();
        List<Integer> values = new ArrayList<>(units);
        for (int address = 0; address < units; address++) {
            values.add(space.load(address));
        }
        dumps.put(process.id(), List.copyOf(values));
    }

    /** Tabla de cerrojos del sistema. */
    public LockManager locks() {
        return locks;
    }

    /** Tabla de semaforos del sistema. */
    public SemaphoreManager semaphores() {
        return semaphores;
    }

    /** Tabla de variables de condicion del sistema. */
    public ConditionManager conditions() {
        return conditions;
    }
}
