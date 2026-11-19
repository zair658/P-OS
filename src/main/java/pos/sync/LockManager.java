package pos.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pos.base.PosFault;
import pos.proc.State;
import pos.proc.Tcb;

/**
 * Tabla de cerrojos del sistema.
 *
 * Un cerrojo tiene un duenyo (o ninguno) y una cola de hilos esperando. La
 * politica es FIFO estricta y la cesion es directa: al liberar, el cerrojo pasa
 * al primero de la cola sin pasar por el estado libre. Eso evita el "barging",
 * es decir, que un hilo recien llegado se cuele delante de quien lleva mas
 * tiempo esperando, y ademas hace que el orden de la cola sea una propiedad
 * comprobable con una prueba.
 *
 * Dos reglas que el gestor impone y que conviene tener presentes:
 * <ul>
 *   <li>Un cerrojo no es reentrante. Volver a tomarlo sin soltarlo es un error
 *       de programacion y se denuncia en el acto, en vez de dejar al hilo
 *       esperandose a si mismo para siempre.</li>
 *   <li>Solo lo libera quien lo tiene. Intentar soltar el de otro es un fallo
 *       del proceso, no una operacion tolerada.</li>
 * </ul>
 *
 * El gestor no cambia el estado de los hilos ni los mueve de sitio: eso es del
 * nucleo. Aqui solo se guarda quien posee cada cerrojo, quien espera, y se
 * responde si hay un ciclo de espera o un cerrojo huerfano.
 */
public final class LockManager {

    /** Un cerrojo concreto. */
    private static final class Slot {

        private final String name;
        private Tcb owner;
        private final WaitQueue waiting = new WaitQueue();

        private Slot(String name) {
            this.name = name;
        }
    }

    /**
     * Un ciclo de espera, con los cerrojos que lo cierran.
     *
     * {@code threads.get(i)} espera el cerrojo {@code locks.get(i)}, que tiene
     * {@code threads.get(i + 1)} (el ultimo espera al primero).
     */
    public record Deadlock(List<Tcb> threads, List<String> locks) {

        public Deadlock {
            threads = List.copyOf(threads);
            locks = List.copyOf(locks);
        }

        /** Texto legible del ciclo, para la traza y la demostracion. */
        public String describe() {
            StringBuilder text = new StringBuilder("ciclo de espera: ");
            for (int i = 0; i < threads.size(); i++) {
                if (i > 0) {
                    text.append("; ");
                }
                Tcb next = threads.get((i + 1) % threads.size());
                text.append(threads.get(i).id())
                        .append(" espera '")
                        .append(locks.get(i))
                        .append("' de ")
                        .append(next.id());
            }
            return text.toString();
        }

        /** Identificadores del ciclo, con el primero repetido al final. */
        public String ring() {
            StringBuilder text = new StringBuilder();
            for (Tcb thread : threads) {
                text.append(thread.id()).append(" -> ");
            }
            return text.append(threads.get(0).id()).toString();
        }
    }

    private final Map<String, Slot> slots = new LinkedHashMap<>();

    /**
     * Intenta tomar un cerrojo.
     *
     * @return true si el hilo se queda con el; false si lo tiene otro y hay que
     *         ponerlo a esperar en la cola
     */
    public boolean acquire(Tcb thread, String name) {
        if (thread == null) {
            throw new IllegalArgumentException("no se puede tomar un cerrojo sin hilo");
        }
        Slot slot = slots.computeIfAbsent(name, Slot::new);
        if (slot.owner == null) {
            slot.owner = thread;
            return true;
        }
        if (slot.owner == thread) {
            throw new PosFault(thread.id() + ": ya tiene el cerrojo '" + name
                    + "' y los cerrojos de este sistema no son reentrantes");
        }
        slot.waiting.enqueue(thread);
        return false;
    }

    /**
     * Libera un cerrojo y lo cede al primero de la cola.
     *
     * @return el hilo que lo hereda, o {@code null} si no esperaba nadie
     */
    public Tcb release(Tcb thread, String name) {
        Slot slot = slots.get(name);
        if (slot == null || slot.owner != thread) {
            throw new PosFault(thread.id() + ": intenta liberar el cerrojo '" + name
                    + "', que no tiene");
        }
        Tcb next = slot.waiting.handOff();
        if (next == null) {
            slot.owner = null;
            return null;
        }
        slot.owner = next;
        return next;
    }

    /**
     * Hilos que acaban de heredar un cerrojo, en orden de concesion.
     *
     * Se entregan al nucleo y se vacia la lista. El gestor no los despierta por
     * su cuenta: mover hilos es cosa del bucle del nucleo, y hacerlo desde el
     * hilo que ejecuta romperia la regla de que solo un sitio toca la cola de
     * listos.
     */
    public List<Tcb> drainGranted() {
        List<Tcb> woken = new ArrayList<>();
        for (Slot slot : slots.values()) {
            woken.addAll(slot.waiting.drainGranted());
        }
        return List.copyOf(woken);
    }

    /**
     * Cerrojos que tiene un hilo en este momento.
     *
     * Sirve para avisar cuando un hilo termina sin soltarlos: el cerrojo queda
     * en poder de un muerto y quien lo espere no despertara nunca.
     */
    public List<String> heldBy(Tcb thread) {
        List<String> names = new ArrayList<>();
        for (Slot slot : slots.values()) {
            if (slot.owner == thread) {
                names.add(slot.name);
            }
        }
        return List.copyOf(names);
    }

    /** Duenyo actual de un cerrojo, o {@code null} si esta libre o no existe. */
    public Tcb ownerOf(String name) {
        Slot slot = slots.get(name);
        return slot == null ? null : slot.owner;
    }

    /** Cuantos hilos esperan un cerrojo concreto. */
    public int waitingCount(String name) {
        Slot slot = slots.get(name);
        return slot == null ? 0 : slot.waiting.size();
    }

    /** Hilos bloqueados en algun cerrojo, con el nombre del cerrojo. */
    public List<String> describeBlocked() {
        List<String> lines = new ArrayList<>();
        for (Slot slot : slots.values()) {
            for (Tcb waiter : slot.waiting.threads()) {
                lines.add(waiter.id() + " espera el cerrojo '" + slot.name + "'");
            }
        }
        return List.copyOf(lines);
    }

    /** Cerrojos usados y en uso, para la demostracion. */
    public int count() {
        return slots.size();
    }

    public int heldCount() {
        int held = 0;
        for (Slot slot : slots.values()) {
            if (slot.owner != null) {
                held++;
            }
        }
        return held;
    }

    /**
     * Busca un ciclo en el grafo de espera.
     *
     * El grafo tiene una arista por hilo bloqueado: de quien espera a quien
     * tiene el cerrojo que necesita. Si esa cadena vuelve a su origen hay
     * interbloqueo y nadie de los implicados puede avanzar, porque ninguno
     * soltara el cerrojo que el siguiente necesita. El recorrido es en
     * profundidad y en orden de aparicion, para que el ciclo que se informe sea
     * siempre el mismo ante la misma ejecucion.
     *
     * @return el ciclo encontrado, o {@code null} si no hay ninguno
     */
    public Deadlock findDeadlock() {
        Map<Tcb, Tcb> waitsFor = new LinkedHashMap<>();
        Map<Tcb, String> waitLock = new LinkedHashMap<>();
        for (Slot slot : slots.values()) {
            if (slot.owner == null) {
                continue;
            }
            for (Tcb waiter : slot.waiting.threads()) {
                waitsFor.put(waiter, slot.owner);
                waitLock.put(waiter, slot.name);
            }
        }
        if (waitsFor.isEmpty()) {
            return null;
        }

        Map<Tcb, Integer> mark = new LinkedHashMap<>();
        List<Tcb> path = new ArrayList<>();
        for (Tcb start : new ArrayList<>(waitsFor.keySet())) {
            List<Tcb> cycle = walk(start, waitsFor, mark, path);
            if (cycle != null) {
                List<String> locks = new ArrayList<>(cycle.size());
                for (Tcb thread : cycle) {
                    locks.add(waitLock.getOrDefault(thread, "?"));
                }
                return new Deadlock(cycle, locks);
            }
        }
        return null;
    }

    /**
     * Cerrojos que nadie va a liberar porque su duenyo ya termino.
     *
     * No es un ciclo, pero deja bloqueados a los mismos hilos y merece un
     * mensaje distinto: el fallo aqui es no haber soltado el cerrojo antes de
     * salir, no un orden de adquisicion equivocado.
     */
    public List<String> orphanLocks() {
        List<String> orphans = new ArrayList<>();
        for (Slot slot : slots.values()) {
            if (slot.owner == null || slot.waiting.isEmpty()) {
                continue;
            }
            if (slot.owner.state() == State.TERMINATED) {
                orphans.add("'" + slot.name + "' de " + slot.owner.id() + " (ya termino)");
            }
        }
        return List.copyOf(orphans);
    }

    /**
     * Recorrido en profundidad con marca de tres colores: 0 sin visitar, 1 en
     * la pila actual, 2 ya cerrado. Reencontrar un nodo en la pila es haber
     * cerrado el circulo.
     */
    private List<Tcb> walk(Tcb node, Map<Tcb, Tcb> waitsFor, Map<Tcb, Integer> mark,
            List<Tcb> path) {
        Integer visible = mark.get(node);
        if (visible != null && visible == 1) {
            int start = path.indexOf(node);
            return new ArrayList<>(path.subList(start, path.size()));
        }
        if (visible != null && visible == 2) {
            return null;
        }

        Tcb next = waitsFor.get(node);
        if (next == null) {
            mark.put(node, 2);
            return null;
        }

        mark.put(node, 1);
        path.add(node);
        List<Tcb> found = walk(next, waitsFor, mark, path);
        path.remove(path.size() - 1);
        if (found != null) {
            return found;
        }
        mark.put(node, 2);
        return null;
    }

    /** Estado de la tabla, una linea por cerrojo. Para la demostracion. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (Slot slot : slots.values()) {
            StringBuilder text = new StringBuilder();
            text.append(String.format("%-12s", "'" + slot.name + "'"));
            text.append(slot.owner == null ? "libre" : "en poder de " + slot.owner.id());
            if (!slot.waiting.isEmpty()) {
                List<String> ids = new ArrayList<>();
                for (Tcb waiter : slot.waiting.threads()) {
                    ids.add(waiter.id());
                }
                text.append("; esperan ").append(String.join(", ", ids));
            }
            lines.add(text.toString());
        }
        return List.copyOf(lines);
    }
}
