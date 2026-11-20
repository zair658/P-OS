package pos.sched;

import java.util.List;
import pos.proc.Tcb;

/**
 * El trabajo mas corto primero.
 *
 * No expropiativa: elige, entre los listos, el que declara la rafaga estimada
 * menor y le da el procesador hasta que termine o se bloquee. Es la politica que
 * arregla el problema del convoy de FCFS y la que da el retorno medio minimo,
 * siempre que la rafaga se sepa de antemano.
 *
 * Que no se sepa es justo su punto debil, y aqui se ve sin adornos: el sistema no
 * adivina, lee {@link pos.proc.Pcb#estimatedBurst()}, que nace con el tamano del
 * programa y que el escenario puede declarar. Si esa estimacion se equivoca, SJF
 * elige mal y puede dejar sin procesador a un trabajo corto de verdad. Por eso la
 * tabla comparativa se lee con la estimacion a la vista y no como una promesa.
 *
 * Ese mismo defecto tiene arreglo expropiativo (el tiempo restante mas corto
 * primero, SRTF), que aqui no se implementa: con expropiacion, un trabajo recien
 * llegado puede adelantar al que esta corriendo, y entonces el orden depende de
 * los instantes de llegada de una forma que la demostracion tendria que explicar
 * aparte.
 */
public final class SjfScheduler implements Scheduler {

    @Override
    public String name() {
        return "SJF";
    }

    @Override
    public SchedulingDecision pick(List<Tcb> ready, long clock) {
        Tcb chosen = null;
        for (Tcb candidate : ready) {
            if (chosen == null || shorter(candidate, chosen)) {
                chosen = candidate;
            }
        }
        if (chosen == null) {
            return null;
        }
        return new SchedulingDecision(chosen, SchedulingDecision.NO_LIMIT,
                "rafaga estimada " + chosen.process().estimatedBurst() + ", la mas corta de la cola");
    }

    private boolean shorter(Tcb a, Tcb b) {
        int burstA = a.process().estimatedBurst();
        int burstB = b.process().estimatedBurst();
        if (burstA != burstB) {
            return burstA < burstB;
        }
        return Order.earlier(a, b);
    }
}
