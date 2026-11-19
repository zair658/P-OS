package pos.mm;

import java.util.List;

import pos.alloc.SlotManager;
import pos.phys.PhysicalMemory;

/**
 * Esquema de gestion de memoria: la politica que decide de que forma un proceso
 * ocupa memoria fisica y como se traducen sus direcciones.
 *
 * Es un objeto pequeno a proposito. El esquema no hace nada por si mismo: cuando
 * el nucleo admite un proceso, le pide un espacio de direcciones y el esquema se
 * lo construye. Toda la inteligencia de traduccion vive en el espacio.
 *
 * Los tres esquemas son excluyentes, de ahi que la interfaz sea sellada: si
 * manana se anade uno, el compilador obliga a tratarlo en todos los sitios que
 * deciden por tipo.
 */
public sealed interface MemoryScheme
        permits MemoryScheme.Contiguous, MemoryScheme.Paged, MemoryScheme.Segmented,
                MemoryScheme.DemandPaged {

    /** Nombre corto para tablas y trazas. */
    String name();

    /**
     * Construye el espacio de direcciones de un proceso.
     *
     * @param memory        memoria fisica compartida por todos los procesos
     * @param slots         gestor de huecos que decide donde se coloca cada tramo
     * @param processId     identificador del proceso, para marcar la propiedad
     * @param processName   nombre legible, que aparece en los mensajes de fallo
     * @param logicalUnits  unidades logicas que necesita el proceso
     */
    AddressSpace attach(PhysicalMemory memory, SlotManager slots, int processId,
            String processName, int logicalUnits);

    /** Un solo tramo contiguo: rapido de traducir, propenso a fragmentacion externa. */
    record Contiguous() implements MemoryScheme {

        @Override
        public String name() {
            return "contiguo";
        }

        @Override
        public AddressSpace attach(PhysicalMemory memory, SlotManager slots, int processId,
                String processName, int logicalUnits) {
            return ContiguousSpace.create(memory, slots, processId, processName, logicalUnits);
        }
    }

    /**
     * Paginas de tamano fijo en marcos libres cualesquiera.
     *
     * @param pageSize unidades por pagina; debe ser positivo
     */
    record Paged(int pageSize) implements MemoryScheme {

        public Paged {
            if (pageSize <= 0) {
                throw new IllegalArgumentException("el tamano de pagina debe ser positivo: " + pageSize);
            }
        }

        @Override
        public String name() {
            return "paginacion/" + pageSize;
        }

        @Override
        public AddressSpace attach(PhysicalMemory memory, SlotManager slots, int processId,
                String processName, int logicalUnits) {
            return PagedSpace.create(memory, slots, processId, processName, logicalUnits, pageSize);
        }
    }

    /**
     * Paginacion por demanda: menos marcos que paginas y area de intercambio.
     *
     * El espacio arranca vacio y las paginas entran al tocarlas. Cuando no cabe
     * una mas, la politica decide cual sale y, si estaba sucia, se guarda en el
     * area de intercambio.
     *
     * @param pageSize unidades por pagina; debe ser positivo
     * @param frames   marcos que el proceso puede usar a la vez
     * @param policy   politica de reemplazo
     * @param swap     area de intercambio, que vive en el sistema de ficheros
     * @param trace    direcciones que el proceso va a pedir, en orden; OPT la
     *                 necesita, FIFO y LRU la ignoran
     */
    record DemandPaged(int pageSize, int frames, PagePolicy policy, SwapArea swap,
            List<Integer> trace) implements MemoryScheme {

        public DemandPaged {
            if (pageSize <= 0) {
                throw new IllegalArgumentException("el tamano de pagina debe ser positivo: " + pageSize);
            }
            if (frames <= 0) {
                throw new IllegalArgumentException("hacen falta marcos: " + frames);
            }
            if (policy == null) {
                throw new IllegalArgumentException("la paginacion por demanda necesita una politica");
            }
            if (swap == null) {
                throw new IllegalArgumentException("la paginacion por demanda necesita area de intercambio");
            }
            if (policy == PagePolicy.OPT && (trace == null || trace.isEmpty())) {
                throw new IllegalArgumentException("OPT necesita la traza futura del proceso");
            }
            trace = trace == null ? null : List.copyOf(trace);
        }

        @Override
        public String name() {
            return "demanda/" + pageSize + "/" + policy.label();
        }

        @Override
        public AddressSpace attach(PhysicalMemory memory, SlotManager slots, int processId,
                String processName, int logicalUnits) {
            int[] future = trace == null ? null : trace.stream().mapToInt(Integer::intValue).toArray();
            return DemandPagedSpace.create(memory, slots, processId, processName, logicalUnits,
                    pageSize, frames, policy, swap, future);
        }
    }

    /**
     * Segmentos de tamano libre, cada uno en su propio tramo.
     *
     * El reparto manda sobre el tamano logico: el espacio del proceso es el que
     * declara el reparto, no una cifra aparte que podria no cuadrar con el.
     *
     * @param layout reparto del espacio logico en segmentos
     */
    record Segmented(SegmentLayout layout) implements MemoryScheme {

        public Segmented {
            if (layout == null) {
                throw new IllegalArgumentException("la segmentacion necesita un reparto de segmentos");
            }
        }

        @Override
        public String name() {
            return "segmentacion";
        }

        @Override
        public AddressSpace attach(PhysicalMemory memory, SlotManager slots, int processId,
                String processName, int logicalUnits) {
            return SegmentedSpace.create(memory, slots, processId, processName, layout, logicalUnits);
        }
    }
}
