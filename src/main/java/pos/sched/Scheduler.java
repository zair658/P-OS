package pos.sched;

import java.util.List;
import pos.proc.Tcb;

/**
 * Politica de planificacion.
 *
 * Un planificador solo decide dos cosas: a quien le toca y por cuanto tiempo.
 * No ejecuta, no bloquea, no despierta: eso es del nucleo. Esta separacion es
 * la que permite comparar politicas sobre la misma traza de sucesos y sacar una
 * tabla honesta en vez de una impresion.
 *
 * El contrato de determinismo: ante la misma lista de listos y el mismo reloj,
 * la misma eleccion. Si hay empate se desempata por llegada y luego por
 * identificador, nunca por orden de iteracion de una coleccion.
 */
public interface Scheduler {

    /** Nombre corto para la tabla y la traza. */
    String name();

    /**
     * Elige el siguiente hilo.
     *
     * @param ready cola de listos en orden de admision (el primero entro antes)
     * @param clock tick actual del sistema
     * @return la decision, o {@code null} si no hay nada que ejecutar
     */
    SchedulingDecision pick(List<Tcb> ready, long clock);
}
