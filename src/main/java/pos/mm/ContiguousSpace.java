package pos.mm;

import java.util.ArrayList;
import java.util.List;

import pos.alloc.SlotManager;
import pos.phys.PhysicalMemory;

/**
 * Esquema contiguo: el proceso ocupa un solo tramo de memoria fisica.
 *
 * Es el mas simple y el mas rapido de traducir: una suma. La direccion fisica
 * es {@code base + logica}, sin tablas ni divisiones. A cambio, el proceso
 * entero tiene que caber de una pieza, y ahi aparece la fragmentacion externa:
 * puede haber memoria libre de sobra repartida en trozos que no sirven.
 *
 * Es el unico esquema en el que las unidades reservadas y las usables coinciden
 * exactamente, asi que nunca desperdicia memoria dentro del tramo reservado.
 */
public final class ContiguousSpace extends AddressSpace {

    private final int base;

    private ContiguousSpace(PhysicalMemory memory, SlotManager slots, int processId,
            String processName, int logicalUnits) {
        super(memory, slots, processId, processName, logicalUnits);
        this.base = reserve(logicalUnits, "el tramo del proceso");
    }

    /**
     * Crea el espacio y reserva el tramo completo.
     *
     * No hace falta deshacer nada si falla: la reserva es un solo tramo, o se
     * toma entero o no se toma. Los esquemas que reservan varios tramos
     * (paginacion y segmentacion) si tienen que devolver los anteriores, y lo
     * hacen.
     */
    public static ContiguousSpace create(PhysicalMemory memory, SlotManager slots, int processId,
            String processName, int logicalUnits) {
        return new ContiguousSpace(memory, slots, processId, processName, logicalUnits);
    }

    @Override
    protected int translate(int address) {
        checkLogical(address);
        return base + address;
    }

    /** Direccion fisica donde empieza el proceso. */
    public int base() {
        return base;
    }

    /** Primera direccion fisica que no es del proceso. */
    public int limit() {
        return base + logicalUnits();
    }

    @Override
    public String schemeName() {
        return "contiguo";
    }

    @Override
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("contiguo: el proceso vive en un solo tramo");
        lines.add("  registro base " + base + ", limite " + limit()
                + " (traduccion: fisica = " + base + " + logica)");
        if (!placements().isEmpty()) {
            lines.add("  " + placements().get(0).describe());
        }
        return lines;
    }
}
