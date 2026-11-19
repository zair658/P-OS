package pos.sched;

import pos.proc.Tcb;

/**
 * Resultado de una eleccion: el hilo y el cuanto que se le concede.
 *
 * El cuanto viaja con la decision, no con el planificador, porque quien lo
 * aplica es el testigo de turno. Un cuanto de {@link Integer#MAX_VALUE} significa
 * "hasta que termine o se bloquee", que es lo que necesita FCFS para no ser
 * expropiativo.
 */
public record SchedulingDecision(Tcb thread, int slice, String reason) {

    /** Cuanto practicamente infinito, para politicas no expropiativas. */
    public static final int NO_LIMIT = Integer.MAX_VALUE;

    public SchedulingDecision {
        if (thread == null) {
            throw new IllegalArgumentException("la decision necesita un hilo");
        }
        if (slice <= 0) {
            throw new IllegalArgumentException("el cuanto tiene que ser mayor que cero");
        }
    }

    public String sliceText() {
        return slice == NO_LIMIT ? "sin limite" : slice + " ticks";
    }
}
