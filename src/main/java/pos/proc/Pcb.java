package pos.proc;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

import pos.base.PosFault;
import pos.mm.AddressSpace;

/**
 * Bloque de control de proceso: la fuente de verdad de todo lo que un proceso
 * tiene y de como esta.
 *
 * Guarda el programa, el espacio de direcciones y la lista de hilos. No guarda
 * el estado de ejecucion de cada hilo (eso es del TCB): el proceso es el
 * contenedor, los hilos son los que corren.
 *
 * El espacio de direcciones se declara al nacer, pero se asigna despues: un
 * proceso recien creado sabe cuanto necesita y todavia no tiene nada. Mientras
 * no tenga espacio no puede tocar memoria, y decirlo con un fallo es mejor que
 * devolver ceros como si todo fuera bien.
 *
 * El proceso habla de direcciones logicas y nada mas. Que detras haya un tramo
 * contiguo, paginas en marcos sueltos o segmentos repartidos no es asunto suyo:
 * lo resuelve el traductor, que es el objeto {@link AddressSpace}.
 */
public final class Pcb {

    private final int id;
    private final String name;
    private final Program program;
    private final int memoryUnits;
    private final List<Tcb> threads;
    private final long arrivalTick;

    private AddressSpace space;
    private State state = State.NEW;

    Pcb(int id, String name, Program program, int memoryUnits, long arrivalTick) {
        if (memoryUnits <= 0) {
            throw new PosFault("el proceso '" + name + "' necesita al menos una unidad de memoria");
        }
        this.id = id;
        this.name = name;
        this.program = program;
        this.memoryUnits = memoryUnits;
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

    /** Unidades logicas que el proceso declaro necesitar. */
    public int memoryUnits() {
        return memoryUnits;
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

    // ------------------------------------------------------------------
    // Espacio de direcciones
    // ------------------------------------------------------------------

    /**
     * Asigna el espacio de direcciones del proceso.
     *
     * Solo se asigna una vez: si ya tenia, es un error del nucleo y hay que
     * decirlo, porque dos espacios para el mismo proceso significan memoria
     * reservada dos veces y liberada una.
     */
    public void attachSpace(AddressSpace space) {
        if (space == null) {
            throw new IllegalArgumentException("el espacio de direcciones no puede ser nulo");
        }
        if (this.space != null) {
            throw new IllegalStateException("el proceso '" + name + "' ya tenia espacio de direcciones");
        }
        if (space.logicalUnits() != memoryUnits) {
            throw new IllegalStateException("el proceso '" + name + "' declaro " + memoryUnits
                    + " unidades logicas y se le asigno un espacio de " + space.logicalUnits());
        }
        this.space = space;
    }

    /** Espacio de direcciones asignado, o {@code null} si aun no tiene. */
    public AddressSpace space() {
        return space;
    }

    /** Indica si el proceso ya puede ejecutar instrucciones que toquen memoria. */
    public boolean hasMemory() {
        return space != null;
    }

    /** Lee una unidad del espacio de direcciones del proceso. */
    public int load(int address) {
        return spaceOrFail().load(address);
    }

    /** Escribe una unidad del espacio de direcciones del proceso. */
    public void store(int address, int value) {
        spaceOrFail().store(address, value);
    }

    /** Unidades fisicas que ocupa el proceso de verdad. */
    public int physicalUnits() {
        return space == null ? 0 : space.physicalUnits();
    }

    private AddressSpace spaceOrFail() {
        if (space == null) {
            throw new PosFault("el proceso '" + name
                    + "' no tiene memoria asignada y no puede acceder a direccion alguna");
        }
        return space;
    }

    @Override
    public String toString() {
        return "p" + id + " " + name;
    }
}
