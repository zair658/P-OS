package pos.proc;

/**
 * Bloque de control de hilo.
 *
 * Un hilo es lo que el planificador mueve: tiene su propio contador de programa,
 * sus registros y su estado, y comparte el proceso (memoria y programa) con sus
 * hermanos. Las metricas que se muestran al final son las clasicas: llegada,
 * primera vez que ocupa la CPU, fin, tiempo de CPU, tiempo de espera y tiempo de
 * retorno.
 *
 * El campo {@code outcome} es la forma en que el hilo le dice al nucleo por que
 * devolvio el turno. Se escribe justo antes de devolverlo y lo lee el nucleo
 * despues, con lo que no hace falta que se pongan de acuerdo por otro canal.
 */
public final class Tcb {

    private final Pcb process;
    private final int localId;
    private final long arrivalTick;
    private final Registers registers = new Registers();

    private State state = State.NEW;
    private int pc;
    private long cpuTicks;
    private long startTick = -1;
    private long finishTick = -1;
    private long wakeTick = -1;
    private long pendingSleep;
    private String failure;
    private String blockedOn;
    private BlockKind blockedKind = BlockKind.NINGUNO;
    private String reacquireLock;
    private TurnOutcome outcome = null;
    private Thread carrier;
    private HostedTask hosted;

    Tcb(Pcb process, int localId, long arrivalTick) {
        this.process = process;
        this.localId = localId;
        this.arrivalTick = arrivalTick;
    }

    /** Identificador estable y legible, por ejemplo {@code p1/h2}. */
    public String id() {
        return "p" + process.id() + "/h" + localId;
    }

    public Pcb process() {
        return process;
    }

    public int localId() {
        return localId;
    }

    public Registers registers() {
        return registers;
    }

    public long arrivalTick() {
        return arrivalTick;
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public int pc() {
        return pc;
    }

    public void setPc(int pc) {
        this.pc = pc;
    }

    public long cpuTicks() {
        return cpuTicks;
    }

    public void addCpuTick() {
        cpuTicks++;
    }

    public long startTick() {
        return startTick;
    }

    public void markStarted(long tick) {
        if (startTick < 0) {
            startTick = tick;
        }
    }

    public long finishTick() {
        return finishTick;
    }

    public void markFinished(long tick) {
        this.finishTick = tick;
    }

    public long wakeTick() {
        return wakeTick;
    }

    public void setWakeTick(long wakeTick) {
        this.wakeTick = wakeTick;
    }

    public long pendingSleep() {
        return pendingSleep;
    }

    public void setPendingSleep(long ticks) {
        this.pendingSleep = ticks;
    }

    public String failure() {
        return failure;
    }

    public void markFailed(String reason) {
        this.failure = reason;
    }

    /**
     * Recurso por el que el hilo esta esperando, o {@code null} si no espera
     * ninguno.
     *
     * Es el dato que convierte la lista de bloqueados en un grafo de espera: el
     * hilo dice a que cerrojo espera y el gestor de cerrojos sabe quien lo
     * tiene. Sin este campo habria que deducir la espera mirando la tabla, y el
     * detector de interbloqueos tendria que adivinar.
     */
    public String blockedOn() {
        return blockedOn;
    }

    public void setBlockedOn(String blockedOn) {
        this.blockedOn = blockedOn;
    }

    public BlockKind blockedKind() {
        return blockedKind;
    }

    public void setBlockedKind(BlockKind blockedKind) {
        this.blockedKind = blockedKind;
    }

    /**
     * Cerrojo que el hilo debe volver a tomar al despertar de una condicion.
     *
     * Es el unico estado que no existia antes de la fase 5b y el que hace que
     * las condiciones no sean gratis. Quien espera en una condicion suelta el
     * cerrojo, y cuando lo señalizan ya no lo tiene: el nucleo se lo vuelve a
     * pedir antes de dejarle correr. Si se lo ha llevado otro, el hilo pasa de
     * estar esperando la condicion a estar esperando el cerrojo, y sigue
     * bloqueado. Con un solo campo booleano no se puede distinguir «vengo de una
     * concesion» de «estoy pidiendo», y esa distincion es justo la que rompe el
     * codigo si se ignora.
     */
    public String reacquireLock() {
        return reacquireLock;
    }

    public void setReacquireLock(String reacquireLock) {
        this.reacquireLock = reacquireLock;
    }

    /** true si el hilo esta bloqueado esperando un cerrojo y no otra cosa. */
    public boolean waitsForLock() {
        return blockedKind == BlockKind.CERROJO;
    }

    /** true si espera un recurso de sincronizacion (no una simple espera). */
    public boolean waitsForResource() {
        return blockedKind == BlockKind.CERROJO
                || blockedKind == BlockKind.SEMAFORO
                || blockedKind == BlockKind.CONDICION
                || blockedKind == BlockKind.RECURSO;
    }

    /** Deja al hilo sin bloqueo, para cuando pasa a listo o termina. */
    public void clearBlock() {
        this.blockedOn = null;
        this.blockedKind = BlockKind.NINGUNO;
        this.reacquireLock = null;
        this.wakeTick = -1;
    }

    public TurnOutcome outcome() {
        return outcome;
    }

    public void setOutcome(TurnOutcome outcome) {
        this.outcome = outcome;
    }

    public Thread carrier() {
        return carrier;
    }

    public void setCarrier(Thread carrier) {
        this.carrier = carrier;
    }

    /**
     * Tarea nativa del hilo, o {@code null} si ejecuta un programa interpretado.
     *
     * Se guarda en el hilo y no en el proceso porque el mismo proceso podria
     * tener un hilo interpretado y otro nativo; hoy no ocurre, pero el dato
     * pertenece al hilo por la misma razon que el contador de programa.
     */
    public HostedTask hosted() {
        return hosted;
    }

    public void attachHosted(HostedTask hosted) {
        this.hosted = hosted;
    }

    /** Tiempo de retorno: desde que llego hasta que termino. */
    public long turnaround(long now) {
        long end = finishTick >= 0 ? finishTick : now;
        return end - arrivalTick;
    }

    /** Tiempo de espera: lo que duro menos lo que estuvo ejecutando. */
    public long waitTicks(long now) {
        long total = turnaround(now);
        return Math.max(0, total - cpuTicks);
    }

    /** Tiempo de respuesta: desde que llego hasta su primer turno. */
    public long responseTicks() {
        return startTick < 0 ? -1 : startTick - arrivalTick;
    }

    @Override
    public String toString() {
        return id() + " " + process.name() + " (" + state.etiqueta() + ")";
    }
}
