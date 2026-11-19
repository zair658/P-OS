package pos.sync;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import pos.proc.Tcb;

/**
 * Cola de hilos que esperan un recurso.
 *
 * Cerrojos, semaforos y condiciones esperan de la misma manera: alguien se pone
 * al final, alguien sale por delante y el orden de entrada es el orden de salida.
 * Lo unico que cambia es que significa "salir". Por eso la cola es una sola
 * pieza y cada gestor le pone encima su semantica, en vez de tres copias del
 * mismo deque con los mismos dos metodos.
 *
 * Hay dos listas y la distincion importa:
 * <ul>
 *   <li>{@code waiting} es quien espera ahora mismo. Es la cola de verdad.</li>
 *   <li>{@code granted} es quien ya tiene el recurso pero todavia no se ha
 *       enterado, porque mover hilos es cosa del nucleo y no del gestor. Se
 *       llena al conceder y la vacia el nucleo al final de cada turno.</li>
 * </ul>
 * Sin la segunda lista, quien concede tendria que despertar al hilo desde el
 * turno de otro hilo, y entonces dos sitios distintos tocarian la cola de
 * listos. La regla del sistema es que la toca uno solo.
 *
 * La cesion es directa: cuando se concede, el hilo sale de {@code waiting} y
 * entra en {@code granted} en el acto. No vuelve a competir, asi que un hilo
 * recien llegado no puede adelantar a quien lleva mas tiempo esperando.
 */
public final class WaitQueue {

    private final Deque<Tcb> waiting = new ArrayDeque<>();
    private final Deque<Tcb> granted = new ArrayDeque<>();

    /** Pone un hilo al final de la cola. */
    public void enqueue(Tcb thread) {
        if (thread == null) {
            throw new IllegalArgumentException("no se puede encolar un hilo nulo");
        }
        waiting.addLast(thread);
    }

    /**
     * Saca al primero de la cola y lo apunta como concedido.
     *
     * @return el hilo que hereda el recurso, o {@code null} si no esperaba nadie
     */
    public Tcb handOff() {
        Tcb next = waiting.pollFirst();
        if (next != null) {
            granted.addLast(next);
        }
        return next;
    }

    /** Saca a todos los que esperan, en orden, y los apunta como concedidos. */
    public List<Tcb> handOffAll() {
        List<Tcb> handed = new ArrayList<>(waiting);
        waiting.clear();
        granted.addAll(handed);
        return List.copyOf(handed);
    }

    public boolean isEmpty() {
        return waiting.isEmpty();
    }

    public int size() {
        return waiting.size();
    }

    /** Quien espera ahora mismo, en orden de llegada. */
    public List<Tcb> threads() {
        return List.copyOf(waiting);
    }

    /**
     * Hilos que ya tienen el recurso y esperan a que el nucleo los atienda.
     *
     * Se entregan y se vacia la lista: el nucleo la consume entera en cada
     * vuelta de su bucle.
     */
    public List<Tcb> drainGranted() {
        List<Tcb> woken = new ArrayList<>(granted);
        granted.clear();
        return woken;
    }
}
