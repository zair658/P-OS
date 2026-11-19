package pos.sched;

import java.util.List;
import pos.proc.Tcb;

/**
 * Turno rotatorio con cuanto configurable.
 *
 * Expropiativa: cada hilo ejecuta como mucho {@code quantum} ticks y vuelve al
 * final de la cola. Es la que da tiempos de respuesta acotados a cambio de mas
 * cambios de contexto.
 *
 * El tamano del cuanto no es un detalle: muy grande se comporta como FCFS, muy
 * pequeno se pasa el tiempo cambiando de contexto. El escenario de comparacion
 * lo ensena con numeros en vez de con opiniones.
 */
public final class RoundRobinScheduler implements Scheduler {

    private final int quantum;

    public RoundRobinScheduler(int quantum) {
        if (quantum <= 0) {
            throw new IllegalArgumentException("el cuanto de Round Robin tiene que ser > 0");
        }
        this.quantum = quantum;
    }

    public int quantum() {
        return quantum;
    }

    @Override
    public String name() {
        return "RoundRobin(" + quantum + ")";
    }

    @Override
    public SchedulingDecision pick(List<Tcb> ready, long clock) {
        if (ready.isEmpty()) {
            return null;
        }
        return new SchedulingDecision(ready.get(0), quantum,
                "primero de la cola");
    }
}
