package pos.sched;

import java.util.List;
import pos.proc.Tcb;

/**
 * Prioridades, con turno rotatorio entre iguales.
 *
 * Elige siempre el hilo listo de prioridad mas alta, entendiendo por mas alta el
 * numero mas bajo: cero es lo mas urgente. Entre varios de la misma prioridad
 * reparte el procesador por turnos de {@code quantum} ticks, que es lo que evita
 * que dos procesos iguales se queden uno sin ejecutar nunca.
 *
 * La expropiacion sale de la politica sin necesidad de que el nucleo sepa nada de
 * prioridades: cada vez que el hilo en curso devuelve el turno, sea porque
 * termino, porque se bloqueo o porque se le acabo el cuanto, se vuelve a elegir
 * sobre toda la cola de listos. Si mientras tanto ha llegado uno mas urgente, se
 * lleva el procesador. Cuanto mas corto el cuanto, antes se nota la llegada.
 *
 * El peligro clasico de esta politica se puede reproducir aqui: un proceso de
 * prioridad alta que nunca se bloquea deja a los de prioridad baja sin turno, y
 * no hay envejecimiento que lo corrija. Se implemento sin envejecimiento a
 * proposito, porque el envejecimiento esconde el problema y este es el que hay
 * que ensenar.
 */
public final class PriorityScheduler implements Scheduler {

    private final int quantum;

    public PriorityScheduler(int quantum) {
        if (quantum <= 0) {
            throw new IllegalArgumentException("el cuanto de prioridades tiene que ser > 0");
        }
        this.quantum = quantum;
    }

    public int quantum() {
        return quantum;
    }

    @Override
    public String name() {
        return "Prioridad(" + quantum + ")";
    }

    @Override
    public SchedulingDecision pick(List<Tcb> ready, long clock) {
        Tcb chosen = null;
        for (Tcb candidate : ready) {
            if (chosen == null || moreUrgent(candidate, chosen)) {
                chosen = candidate;
            }
        }
        if (chosen == null) {
            return null;
        }
        return new SchedulingDecision(chosen, quantum,
                "prioridad " + chosen.process().priority() + ", la mas alta de la cola");
    }

    private boolean moreUrgent(Tcb a, Tcb b) {
        int priorityA = a.process().priority();
        int priorityB = b.process().priority();
        if (priorityA != priorityB) {
            return priorityA < priorityB;
        }
        return Order.earlier(a, b);
    }
}
