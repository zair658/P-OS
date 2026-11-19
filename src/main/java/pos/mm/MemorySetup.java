package pos.mm;

import pos.alloc.BestFit;
import pos.alloc.SlotManager;
import pos.phys.PhysicalMemory;

/**
 * Todo lo que hace falta para dar memoria a un proceso, en un solo objeto.
 *
 * El nucleo necesita cuatro cosas relacionadas para crear un espacio de
 * direcciones: la memoria fisica, el esquema, el gestor de huecos y el tamano
 * logico. Pasarlas por separado obliga a cuatro parametros del mismo tipo
 * conceptual y hace facil equivocarse de orden; juntas se leen como una
 * configuracion del sistema, que es lo que son.
 *
 * El tamano logico es comun a todos los procesos de este sistema: los procesos
 * de P-OS se declaran con el mismo espacio, y lo que cambia es como se coloca.
 */
public record MemorySetup(PhysicalMemory physical, MemoryScheme scheme, SlotManager slots, int logicalUnits) {

    public MemorySetup {
        if (physical == null) {
            throw new IllegalArgumentException("la configuracion necesita memoria fisica");
        }
        if (scheme == null) {
            throw new IllegalArgumentException("la configuracion necesita un esquema de memoria");
        }
        if (slots == null) {
            throw new IllegalArgumentException("la configuracion necesita un gestor de huecos");
        }
        if (logicalUnits <= 0) {
            throw new IllegalArgumentException("el tamano logico debe ser positivo: " + logicalUnits);
        }
    }

    /** Esquema contiguo sobre una memoria nueva, con best-fit como gestor. */
    public static MemorySetup contiguous(int physicalUnits, int logicalUnits) {
        return new MemorySetup(new PhysicalMemory(physicalUnits), new MemoryScheme.Contiguous(),
                new BestFit(), logicalUnits);
    }

    /** Esquema paginado sobre una memoria nueva, con best-fit como gestor. */
    public static MemorySetup paged(int physicalUnits, int logicalUnits, int pageSize) {
        return new MemorySetup(new PhysicalMemory(physicalUnits), new MemoryScheme.Paged(pageSize),
                new BestFit(), logicalUnits);
    }

    /** Esquema segmentado; el tamano logico sale del reparto de segmentos. */
    public static MemorySetup segmented(int physicalUnits, SegmentLayout layout) {
        return new MemorySetup(new PhysicalMemory(physicalUnits), new MemoryScheme.Segmented(layout),
                new BestFit(), layout.totalLogicalUnits());
    }

    /** Crea el espacio de direcciones de un proceso con esta configuracion. */
    public AddressSpace newSpace(int processId, String processName) {
        return scheme.attach(physical, slots, processId, processName, logicalUnits);
    }

    /** Descripcion de una linea, para la cabecera de la traza y de la vista. */
    public String describe() {
        return scheme.name() + " con " + slots.name() + ", " + physical.size()
                + " unidades fisicas, " + logicalUnits + " logicas por proceso";
    }
}
