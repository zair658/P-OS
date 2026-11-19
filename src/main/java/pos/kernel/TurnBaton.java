package pos.kernel;

import pos.base.PosFault;
import pos.proc.Tcb;

/**
 * El testigo de turno, el mecanismo que hace que un solo hilo ejecute a la vez.
 *
 * Es un monitor con una regla sencilla: el nucleo concede el turno a un hilo y
 * se queda esperando a que lo devuelva; el hilo espera a que se lo concedan,
 * ejecuta y lo devuelve. Solo hay un portador, asi que no puede haber dos hilos
 * dentro del interprete a la vez (equivale a una unica CPU) y la traza sale
 * determinista.
 *
 * Dos detalles que no son cosmeticos:
 * <ul>
 *   <li>Quien devuelve el turno limpia el portador. Si no lo hiciera, el mismo
 *       hilo podria volver a ver el turno como suyo antes de que el nucleo lo
 *       recogiera, y ejecutaria dos cuantos seguidos.</li>
 *   <li>La concesion tiene un plazo. Si un hilo se cuelga y no devuelve el
 *       turno, el nucleo falla con un mensaje claro en vez de quedarse
 *       esperando para siempre.</li>
 * </ul>
 */
public final class TurnBaton {

    private static final long GRANT_TIMEOUT_MS = 15_000L;

    private Tcb holder;
    private boolean returned;
    private int budget;
    private boolean stopped;

    /**
     * Concede el turno a un hilo y espera a que lo devuelva.
     * Lo llama el nucleo.
     */
    public synchronized void grant(Tcb tcb, int slice) {
        if (holder != null) {
            throw new IllegalStateException("ya hay un hilo con el turno: " + holder.id());
        }
        holder = tcb;
        returned = false;
        budget = Math.max(1, slice);
        notifyAll();

        long deadline = System.currentTimeMillis() + GRANT_TIMEOUT_MS;
        while (!returned) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) {
                holder = null;
                throw new PosFault("el hilo " + tcb.id() + " no devolvio el turno en "
                        + GRANT_TIMEOUT_MS + " ms");
            }
            try {
                wait(left);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                holder = null;
                throw new PosFault("el nucleo fue interrumpido esperando el turno de " + tcb.id(), e);
            }
        }
        holder = null;
    }

    /** Espera a que le concedan el turno. Lo llama el hilo del proceso. */
    public synchronized void awaitTurn(Tcb tcb) {
        while (holder != tcb) {
            if (stopped) {
                return;
            }
            try {
                wait(100L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Consume un tick del cuanto concedido; false si ya no queda. */
    public synchronized boolean consumeBudget() {
        if (holder == null) {
            return false;
        }
        if (budget <= 0) {
            return false;
        }
        budget--;
        return true;
    }

    /**
     * Devuelve el turno. Limpia el portador antes de avisar, para que el hilo
     * no pueda reencontrarse con el turno que acaba de soltar.
     */
    public synchronized void returnTurn() {
        holder = null;
        returned = true;
        notifyAll();
    }

    /** Libera a todos los que esperan turno, para poder cerrar el sistema. */
    public synchronized void stop() {
        stopped = true;
        notifyAll();
    }

    public synchronized boolean isStopped() {
        return stopped;
    }

    public synchronized Tcb holder() {
        return holder;
    }
}
