package pos.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pos.base.PosFault;
import pos.proc.Tcb;

/**
 * Tabla de semaforos del sistema.
 *
 * Un semaforo es un contador y una cola. Dos operaciones, las de siempre:
 * <ul>
 *   <li>{@code SEM_WAIT} (esperar, P): si el contador es mayor que cero baja uno
 *       y el hilo sigue; si es cero, el hilo se pone al final de la cola.</li>
 *   <li>{@code SEM_POST} (señalar, V): si hay alguien esperando, el permiso pasa
 *       al primero de la cola y el contador no sube; si no hay nadie, el contador
 *       sube uno.</li>
 * </ul>
 *
 * Que en la rama con espera el contador no suba no es un detalle: es lo que hace
 * que dos hilos no se lleven el mismo permiso. Si se incrementara y luego se
 * despertara, entre las dos cosas alguien podria colarse y quedarse el permiso,
 * el hilo despertado volveria a dormirse y el reparto dejaria de ser justo.
 * Es el mismo criterio que en los cerrojos: cesion directa, sin pasar por libre.
 *
 * Los semaforos se crean con {@code SEM_INIT} y valen cero si no se dice otra
 * cosa. Usar uno que no existe es un error de programacion, no una espera
 * eterna: si {@code SEM_WAIT} creara el semaforo sobre la marcha, una errata en
 * el nombre se convertiria en un hilo dormido para siempre sin ningun mensaje.
 *
 * Igual que el gestor de cerrojos, este no cambia el estado de los hilos: dice
 * quien despierta y el nucleo lo hace al final del turno.
 */
public final class SemaphoreManager {

    /** Un semaforo concreto. */
    private static final class Slot {

        private final String name;
        private int count;
        private final WaitQueue waiting = new WaitQueue();

        private Slot(String name, int count) {
            this.name = name;
            this.count = count;
        }
    }

    private final Map<String, Slot> slots = new LinkedHashMap<>();

    /**
     * Crea un semaforo con su valor inicial.
     *
     * @param initial permiso iniciales; cero deja a los primeros {@code SEM_WAIT}
     *                esperando
     */
    public void init(Tcb thread, String name, int initial) {
        if (initial < 0) {
            throw new PosFault(thread.id() + ": el semaforo '" + name
                    + "' no puede nacer con " + initial + " permisos");
        }
        if (slots.containsKey(name)) {
            throw new PosFault(thread.id() + ": el semaforo '" + name
                    + "' ya existe; reiniciarlo escondería permisos en vuelo");
        }
        slots.put(name, new Slot(name, initial));
    }

    /**
     * Intenta tomar un permiso.
     *
     * @return true si lo toma y sigue; false si no habia y queda en la cola
     */
    public boolean waitOn(Tcb thread, String name) {
        Slot slot = require(name);
        if (slot.count > 0) {
            slot.count--;
            return true;
        }
        slot.waiting.enqueue(thread);
        return false;
    }

    /**
     * Devuelve un permiso, o lo cede al primero que espera.
     *
     * @return el hilo que hereda el permiso, o {@code null} si el contador subio
     */
    public Tcb post(Tcb thread, String name) {
        Slot slot = require(name);
        Tcb next = slot.waiting.handOff();
        if (next == null) {
            slot.count++;
        }
        return next;
    }

    private Slot require(String name) {
        Slot slot = slots.get(name);
        if (slot == null) {
            throw new PosFault("no existe el semaforo '" + name
                    + "'; hay que crearlo con SEM_INIT antes de usarlo");
        }
        return slot;
    }

    /** Valor actual del contador, o -1 si el semaforo no existe. */
    public int valueOf(String name) {
        Slot slot = slots.get(name);
        return slot == null ? -1 : slot.count;
    }

    public int waitingCount(String name) {
        Slot slot = slots.get(name);
        return slot == null ? 0 : slot.waiting.size();
    }

    /** Hilos que acaban de heredar un permiso, en orden de concesion. */
    public List<Tcb> drainGranted() {
        List<Tcb> woken = new ArrayList<>();
        for (Slot slot : slots.values()) {
            woken.addAll(slot.waiting.drainGranted());
        }
        return List.copyOf(woken);
    }

    /**
     * Hilos bloqueados en algun semaforo, con el nombre del semaforo.
     *
     * Hace falta para el informe de bloqueo: un semaforo no tiene dueño, asi que
     * el grafo de espera no puede decir quien lo va a liberar. Lo unico honesto
     * que se puede afirmar es quien espera que.
     */
    public List<String> describeBlocked() {
        List<String> lines = new ArrayList<>();
        for (Slot slot : slots.values()) {
            for (Tcb waiter : slot.waiting.threads()) {
                lines.add(waiter.id() + " espera el semaforo '" + slot.name + "'");
            }
        }
        return List.copyOf(lines);
    }

    public int count() {
        return slots.size();
    }

    /** Estado de la tabla, una linea por semaforo. Para la demostracion. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (Slot slot : slots.values()) {
            StringBuilder text = new StringBuilder();
            text.append(String.format("%-12s", "'" + slot.name + "'"));
            text.append("valor ").append(slot.count);
            List<Tcb> waiters = slot.waiting.threads();
            if (!waiters.isEmpty()) {
                List<String> ids = new ArrayList<>();
                for (Tcb waiter : waiters) {
                    ids.add(waiter.id());
                }
                text.append("; esperan ").append(String.join(", ", ids));
            }
            lines.add(text.toString());
        }
        return List.copyOf(lines);
    }
}
