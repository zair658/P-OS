package pos.sync;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import pos.base.PosFault;
import pos.proc.Tcb;

/**
 * Tabla de variables de condicion.
 *
 * Una condicion no guarda un valor: guarda una lista de hilos dormidos y el
 * nombre del cerrojo con el que se usa. Su protocolo es el que cuesta:
 * <ol>
 *   <li>Quien llama a {@code COND_WAIT} suelta el cerrojo y se duerme. Suelta y
 *       duerme tienen que ser el mismo paso; si fueran dos, entre uno y otro el
 *       hilo podria perder la señal que venia a esperar.</li>
 *   <li>Quien llama a {@code COND_SIGNAL} despierta al primero de la cola. Aqui
 *       solo se apunta quien despierta: el nucleo es quien lo pone en la cola de
 *       listos.</li>
 *   <li>El despertado <b>no</b> tiene el cerrojo: lo solto al dormirse. Antes de
 *       correr tiene que volver a tomarlo, y mientras lo toma puede ser que se lo
 *       haya llevado otro, en cuyo caso sigue bloqueado. Ese tercer paso es el
 *       que este sistema guarda en {@link Tcb#reacquireLock()} y hace el
 *       nucleo.</li>
 * </ol>
 *
 * Una condicion se asocia a su cerrojo en el primer {@code COND_WAIT} y no lo
 * cambia. Si se usara con dos cerrojos distintos, la exclusion mutua dejaria de
 * significar algo y el error seria invisible hasta que algo se colase.
 *
 * Una señal sin nadie esperando no se guarda: se pierde. Es lo que hace un
 * semaforo y no una condicion, y es la razon de que en el codigo de un monitor
 * el candidato se compruebe en un bucle y no en un {@code if}.
 */
public final class ConditionManager {

    /** Una condicion concreta. */
    private static final class Slot {

        private final String name;
        private final String lockName;
        private final WaitQueue waiting = new WaitQueue();

        private Slot(String name, String lockName) {
            this.name = name;
            this.lockName = lockName;
        }
    }

    private final Map<String, Slot> slots = new LinkedHashMap<>();

    /**
     * Devuelve el cerrojo al que esta atada una condicion, o {@code null} si la
     * condicion todavia no existe.
     */
    public String lockOf(String name) {
        Slot slot = slots.get(name);
        return slot == null ? null : slot.lockName;
    }

    /**
     * Apunta al hilo como durmiente de la condicion.
     *
     * El hilo ya tiene que estar bloqueado con el cerrojo en su poder: soltarlo
     * es otro paso, que da el nucleo al atender el bloqueo, para que no haya un
     * instante en que el hilo ni duerme ni esta protegido.
     *
     * @param lock cerrojo con el que se usa la condicion; el primero que se pase
     *             la ata para siempre
     */
    public void waitOn(Tcb thread, String name, String lock) {
        Slot slot = slots.get(name);
        if (slot == null) {
            slot = new Slot(name, lock);
            slots.put(name, slot);
        } else if (!slot.lockName.equals(lock)) {
            throw new PosFault(thread.id() + ": la condicion '" + name + "' esta atada al cerrojo '"
                    + slot.lockName + "' y no se puede esperar con '" + lock + "'");
        }
        slot.waiting.enqueue(thread);
    }

    /**
     * Despierta al primero que espera.
     *
     * @return el hilo señalado, o {@code null} si no esperaba nadie (señal
     *         perdida)
     */
    public Tcb signal(Tcb thread, String name) {
        Slot slot = slots.get(name);
        if (slot == null) {
            return null;
        }
        Tcb next = slot.waiting.handOff();
        if (next != null) {
            next.setReacquireLock(slot.lockName);
        }
        return next;
    }

    /** Despierta a todos los que esperan; devuelve cuantos eran. */
    public int broadcast(Tcb thread, String name) {
        Slot slot = slots.get(name);
        if (slot == null) {
            return 0;
        }
        List<Tcb> woken = slot.waiting.handOffAll();
        for (Tcb waiter : woken) {
            waiter.setReacquireLock(slot.lockName);
        }
        return woken.size();
    }

    public int waitingCount(String name) {
        Slot slot = slots.get(name);
        return slot == null ? 0 : slot.waiting.size();
    }

    /** Hilos que acaban de ser señalados, en orden de concesion. */
    public List<Tcb> drainGranted() {
        List<Tcb> woken = new ArrayList<>();
        for (Slot slot : slots.values()) {
            woken.addAll(slot.waiting.drainGranted());
        }
        return List.copyOf(woken);
    }

    /** Hilos bloqueados en alguna condicion, con el nombre de la condicion. */
    public List<String> describeBlocked() {
        List<String> lines = new ArrayList<>();
        for (Slot slot : slots.values()) {
            for (Tcb waiter : slot.waiting.threads()) {
                lines.add(waiter.id() + " espera la condicion '" + slot.name
                        + "' (cerrojo '" + slot.lockName + "')");
            }
        }
        return List.copyOf(lines);
    }

    public int count() {
        return slots.size();
    }

    /** Estado de la tabla, una linea por condicion. Para la demostracion. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        for (Slot slot : slots.values()) {
            StringBuilder text = new StringBuilder();
            text.append(String.format("%-14s", "'" + slot.name + "'"));
            text.append("cerrojo '").append(slot.lockName).append("'");
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
