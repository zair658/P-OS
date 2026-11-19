package pos.proc;

/**
 * Por que un hilo devolvio el turno.
 *
 * Lo escribe el hilo que ejecuta y lo lee el nucleo: es la manera de que el
 * nucleo sepa que hacer con el hilo (volver a la cola, bloquearlo, enterrarlo)
 * sin tener que inspeccionar su estado interno.
 */
public enum TurnOutcome {
    /** Se acabo el cuanto de tiempo que le dio el planificador. */
    PREEMPTED("desalojado por fin de cuanto"),
    /** Cedio el turno a proposito con YIELD. */
    YIELDED("cedio el turno"),
    /** Se bloqueo esperando un suceso. */
    BLOCKED("bloqueado"),
    /** Termino o fallo. */
    EXITED("terminado");

    private final String descripcion;

    TurnOutcome(String descripcion) {
        this.descripcion = descripcion;
    }

    public String descripcion() {
        return descripcion;
    }
}
