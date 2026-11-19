package pos.proc;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import pos.base.PosFault;

/**
 * Bloque de control de proceso: la fuente de verdad de todo lo que un proceso
 * tiene y de como esta.
 *
 * Guarda el programa, el espacio de direcciones y la lista de hilos. No guarda
 * el estado de ejecucion de cada hilo (eso es del TCB): el proceso es el
 * contenedor, los hilos son los que corren.
 *
 * El espacio de direcciones es, en esta fase, un vector propio del proceso con
 * acceso comprobado. La fase 3 lo sustituye por el traductor a memoria fisica;
 * hasta entonces la unica garantia que se da es que nadie puede escribir fuera
 * de su propio espacio.
 */
public final class Pcb {

    private final int id;
    private final String name;
    private final Program program;
    private final byte[] addressSpace;
    private final List<Tcb> threads;
    private final long arrivalTick;

    private State state = State.NEW;

    Pcb(int id, String name, Program program, int memoryUnits, long arrivalTick) {
        if (memoryUnits <= 0) {
            throw new PosFault("el proceso '" + name + "' necesita al menos una unidad de memoria");
        }
        this.id = id;
        this.name = name;
        this.program = program;
        this.addressSpace = new byte[memoryUnits];
        this.threads = new ArrayList<>();
        this.arrivalTick = arrivalTick;
    }

    public int id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Program program() {
        return program;
    }

    public long arrivalTick() {
        return arrivalTick;
    }

    public int memoryUnits() {
        return addressSpace.length;
    }

    public State state() {
        return state;
    }

    void setState(State state) {
        this.state = state;
    }

    public List<Tcb> threads() {
        return Collections.unmodifiableList(threads);
    }

    void addThread(Tcb thread) {
        threads.add(thread);
    }

    /** Hilos que aun pueden ejecutar o volver a ejecutar. */
    public long liveThreads() {
        return threads.stream().filter(t -> t.state() != State.TERMINATED).count();
    }

    /** Lee una unidad del espacio de direcciones del proceso. */
    public int load(int address) {
        checkAddress(address);
        return addressSpace[address] & 0xFF;
    }

    /** Escribe una unidad del espacio de direcciones del proceso. */
    public void store(int address, int value) {
        checkAddress(address);
        addressSpace[address] = (byte) (value & 0xFF);
    }

    private void checkAddress(int address) {
        if (address < 0 || address >= addressSpace.length) {
            throw new PosFault("proceso '" + name + "': direccion " + address
                    + " fuera de su espacio (0.." + (addressSpace.length - 1) + ")");
        }
    }

    @Override
    public String toString() {
        return "p" + id + " " + name;
    }
}
