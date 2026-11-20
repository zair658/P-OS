package pos.sched;

import pos.proc.Tcb;

/**
 * Regla de desempate comun a todos los planificadores.
 *
 * El contrato de determinismo del planificador dice que dos ejecuciones del mismo
 * escenario eligen siempre lo mismo. Para que eso se cumpla, el empate no puede
 * resolverse por el orden en que aparezcan los hilos en una coleccion: hay que
 * resolverlo por datos del sistema.
 *
 * La regla es la misma para todas las politicas, y en este orden: quien llego
 * antes al sistema, luego quien tiene el identificador de proceso menor y por
 * ultimo el identificador local del hilo. Los tres son estables y no dependen de
 * la iteracion.
 */
final class Order {

    private Order() {
        // clase de utilidad
    }

    /** true si {@code a} debe elegirse antes que {@code b} cuando empatan. */
    static boolean earlier(Tcb a, Tcb b) {
        if (a.arrivalTick() != b.arrivalTick()) {
            return a.arrivalTick() < b.arrivalTick();
        }
        if (a.process().id() != b.process().id()) {
            return a.process().id() < b.process().id();
        }
        return a.localId() < b.localId();
    }
}
