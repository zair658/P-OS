package pos.mm;

import java.util.ArrayList;
import java.util.List;

import pos.alloc.SlotManager;
import pos.base.PosFault;
import pos.phys.PhysicalMemory;

/**
 * Esquema segmentado: el espacio logico se divide en segmentos de tamano libre
 * y cada uno se coloca donde quepa en memoria fisica.
 *
 * La tabla de segmentos guarda, por segmento, su base fisica y su longitud. La
 * traduccion busca el segmento que contiene la direccion logica, comprueba el
 * limite y suma el desplazamiento: {@code fisica = base + (logica - inicio
 * logico)}.
 *
 * Diferencias con paginacion que esta clase hace visibles:
 * <ul>
 *   <li>No hay fragmentacion interna, porque se reserva justo la longitud del
 *       segmento.</li>
 *   <li>Si hay fragmentacion externa, porque cada segmento necesita un tramo
 *       contiguo y los tramos tienen tamanos distintos.</li>
 *   <li>Los huecos entre segmentos no son direccionables: acceder a uno es un
 *       fallo, no una lectura cualquiera.</li>
 * </ul>
 *
 * Nota de honestidad sobre el modelo: el repertorio de P-OS maneja direcciones
 * logicas planas, asi que el numero de segmento se deduce del rango en el que
 * cae la direccion, en vez de venir empaquetado en la propia direccion. Lo que
 * se conserva de segmentacion es lo que importa: tabla de base y longitud por
 * segmento, comprobacion de limite independiente por segmento y colocacion
 * separada de cada uno.
 */
public final class SegmentedSpace extends AddressSpace {

    private final List<Segment> segments;
    private int[] bases;

    private SegmentedSpace(PhysicalMemory memory, SlotManager slots, int processId,
            String processName, SegmentLayout layout) {
        super(memory, slots, processId, processName, layout.totalLogicalUnits());
        this.segments = layout.segments();
    }

    /**
     * Crea el espacio y reserva un tramo por segmento.
     *
     * Si un segmento no cabe, se liberan los anteriores. La colocacion va en
     * orden de tabla, que es lo que hace el resultado reproducible.
     */
    public static SegmentedSpace create(PhysicalMemory memory, SlotManager slots, int processId,
            String processName, SegmentLayout layout, int logicalUnits) {
        if (layout.totalLogicalUnits() != logicalUnits) {
            throw new IllegalArgumentException("el reparto en segmentos cubre "
                    + layout.totalLogicalUnits() + " unidades logicas pero se declararon " + logicalUnits);
        }
        SegmentedSpace space = new SegmentedSpace(memory, slots, processId, processName, layout);
        try {
            space.buildBases();
        } catch (RuntimeException failure) {
            space.release();
            throw failure;
        }
        return space;
    }

    private void buildBases() {
        bases = new int[segments.size()];
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            bases[i] = reserve(segment.length(), "el segmento '" + segment.name() + "'");
        }
    }

    @Override
    protected int translate(int address) {
        checkLogical(address);
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segment.contains(address)) {
                return bases[i] + (address - segment.logicalBase());
            }
        }
        throw new PosFault("proceso '" + processName() + "': la direccion " + address
                + " no pertenece a ningun segmento (hueco no direccionable)");
    }

    /** Segmentos declarados, en orden de tabla. */
    public List<Segment> segments() {
        return segments;
    }

    /** Base fisica de un segmento de la tabla. */
    public int baseOf(int index) {
        if (index < 0 || index >= segments.size()) {
            throw new IllegalArgumentException("indice de segmento fuera de la tabla: " + index);
        }
        return bases[index];
    }

    @Override
    public String schemeName() {
        return "segmentacion";
    }

    @Override
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("segmentacion: " + segments.size() + " segmentos (traduccion: base + desplazamiento)");
        int lastEnd = 0;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            if (segment.logicalBase() > lastEnd) {
                lines.add("  hueco logico [" + lastEnd + ", " + segment.logicalBase() + ") no direccionable");
            }
            lines.add(String.format("  %-8s logico [%d, %d) (%d) -> fisico [%d, %d)",
                    segment.name(), segment.logicalBase(), segment.logicalEnd(), segment.length(),
                    bases[i], bases[i] + segment.length()));
            lastEnd = segment.logicalEnd();
        }
        return lines;
    }
}
