package pos.mm;

import java.util.ArrayList;
import java.util.List;

import pos.alloc.SlotManager;
import pos.phys.PhysicalMemory;

/**
 * Esquema paginado: el espacio logico se parte en paginas del mismo tamano y
 * cada pagina se coloca en cualquier marco libre de la memoria fisica.
 *
 * La traduccion es una division y un resto:
 * {@code pagina = logica / tamano} y {@code offset = logica % tamano}. Con eso
 * se busca el marco en la tabla de paginas y la direccion fisica sale de
 * {@code marco * tamano + offset}.
 *
 * Dos consecuencias que se ven en la demostracion:
 * <ul>
 *   <li>No hay fragmentacion externa: cualquier marco libre sirve para cualquier
 *       pagina, porque todas miden igual.</li>
 *   <li>Hay fragmentacion interna: si el proceso no llena su ultima pagina, el
 *       resto se reserva y se tira. Por eso 250 unidades con paginas de 16
 *       ocupan 256.</li>
 * </ul>
 *
 * Cada pagina se reserva con el gestor de huecos, asi que el esquema no elige
 * marcos por su cuenta: hereda el criterio del gestor y lo deja anotado.
 */
public final class PagedSpace extends AddressSpace {

    private final int pageSize;
    private final int pageCount;
    private int[] frames;

    private PagedSpace(PhysicalMemory memory, SlotManager slots, int processId,
            String processName, int logicalUnits, int pageSize) {
        super(memory, slots, processId, processName, logicalUnits);
        if (pageSize <= 0) {
            throw new IllegalArgumentException("el tamano de pagina debe ser positivo: " + pageSize);
        }
        this.pageSize = pageSize;
        this.pageCount = (logicalUnits + pageSize - 1) / pageSize;
    }

    /**
     * Crea el espacio y reserva un marco por pagina.
     *
     * Si se acaba la memoria a mitad, se devuelven los marcos ya tomados: el
     * proceso no puede quedarse con media tabla de paginas.
     */
    public static PagedSpace create(PhysicalMemory memory, SlotManager slots, int processId,
            String processName, int logicalUnits, int pageSize) {
        PagedSpace space = new PagedSpace(memory, slots, processId, processName, logicalUnits, pageSize);
        try {
            space.buildFrames();
        } catch (RuntimeException failure) {
            space.release();
            throw failure;
        }
        return space;
    }

    private void buildFrames() {
        frames = new int[pageCount];
        for (int page = 0; page < pageCount; page++) {
            frames[page] = reserve(pageSize, "el marco de la pagina " + page);
        }
    }

    @Override
    protected int translate(int address) {
        checkLogical(address);
        int page = address / pageSize;
        int offset = address % pageSize;
        return frames[page] + offset;
    }

    public int pageSize() {
        return pageSize;
    }

    public int pageCount() {
        return pageCount;
    }

    /** Marco fisico donde esta una pagina. */
    public int frameOf(int page) {
        if (page < 0 || page >= pageCount) {
            throw new IllegalArgumentException("pagina fuera de la tabla: " + page);
        }
        return frames[page];
    }

    @Override
    public String schemeName() {
        return "paginacion/" + pageSize;
    }

    @Override
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("paginacion: pagina de " + pageSize + " unidades, " + pageCount
                + " paginas (traduccion: marco * " + pageSize + " + offset)");
        int shown = Math.min(pageCount, 8);
        for (int page = 0; page < shown; page++) {
            int logicalFrom = page * pageSize;
            int logicalTo = Math.min(logicalFrom + pageSize, logicalUnits());
            lines.add(String.format("  pagina %2d logica [%d, %d) -> marco %d fisico [%d, %d)",
                    page, logicalFrom, logicalTo, frames[page], frames[page], frames[page] + pageSize));
        }
        if (pageCount > shown) {
            lines.add("  ... y " + (pageCount - shown) + " paginas mas");
        }
        return lines;
    }
}
