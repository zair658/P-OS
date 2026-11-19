package pos.sched;

import java.util.List;
import pos.proc.Tcb;

/**
 * Primero en llegar, primero en ser atendido.
 *
 * No expropiativa: el hilo que empieza sigue hasta terminar o bloquearse. Es la
 * politica de referencia, la que ensena el problema del convoy: un trabajo largo
 * delante retrasa a todos los cortos que llegaron despues.
 *
 * Desempate: llegada al sistema y, si coincide, orden de creacion del hilo. Con
 * eso dos ejecuciones del mismo escenario eligen siempre lo mismo.
 */
public final class FcfsScheduler implements Scheduler {

    @Override
    public String name() {
        return "FCFS";
    }

    @Override
    public SchedulingDecision pick(List<Tcb> ready, long clock) {
        Tcb chosen = null;
        for (Tcb candidate : ready) {
            if (chosen == null || earlier(candidate, chosen)) {
                chosen = candidate;
            }
        }
        if (chosen == null) {
            return null;
        }
        return new SchedulingDecision(chosen, SchedulingDecision.NO_LIMIT,
                "primero en llegar, primero en ser atendido");
    }

    private boolean earlier(Tcb a, Tcb b) {
        if (a.arrivalTick() != b.arrivalTick()) {
            return a.arrivalTick() < b.arrivalTick();
        }
        if (a.process().id() != b.process().id()) {
            return a.process().id() < b.process().id();
        }
        return a.localId() < b.localId();
    }
}
