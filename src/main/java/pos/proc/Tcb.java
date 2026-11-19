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
    private TurnOutcome outcome = null;
    private Thread carrier;

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
