package pos.mm;

import java.util.ArrayList;
import java.util.List;

import pos.alloc.SlotManager;
import pos.base.PosFault;
import pos.phys.PhysicalMemory;

/**
 * Paginacion por demanda: el proceso empieza sin ninguna pagina en memoria y
 * cada pagina entra cuando se toca por primera vez.
 *
 * Es la diferencia con {@link PagedSpace}, que reserva un marco por pagina al
 * arrancar y no suelta ninguno mientras el proceso vive. Aqui un proceso puede
 * declarar mas paginas que marcos hay: si solo caben tres y necesita cinco,
 * funciona igual, a costa de sacar y meter paginas.
 *
 * El camino de un acceso es siempre el mismo:
 * <ol>
 *   <li>se calcula la pagina y el desplazamiento;</li>
 *   <li>si la pagina esta presente, se traduce y se anota el uso;</li>
 *   <li>si no esta, hay fallo de pagina: se busca marco libre y, si no lo hay,
 *       se elige victima segun la politica, se escribe en el area de
 *       intercambio si estaba sucia y se trae la pagina pedida.</li>
 * </ol>
 *
 * La traduccion sigue siendo una division y un resto. Lo unico nuevo es que la
 * entrada de la tabla puede no estar valida, y que atenderla cuesta acceso a
 * disco. El proceso no se entera: pide una direccion y recibe el valor.
 *
 * Una pagina que nunca se ha escrito no se lee del area de intercambio, porque
 * no esta: se rellena de ceros. Es el equivalente a cargarla de la imagen del
 * programa, que en este sistema no existe como fichero separado.
 */
public final class DemandPagedSpace extends AddressSpace {

    private final int pageSize;
    private final int pageCount;
    private final int frameCap;
    private final PagePolicy policy;
    private final SwapArea swap;

    /** Traza futura, solo para OPT. En FIFO y LRU es nula. */
    private final int[] trace;
    private int traceCursor;
    private int traceMisses;

    /** Marco fisico de cada pagina, o -1 si no esta presente. */
    private final int[] frame;
    private final boolean[] present;
    private final boolean[] dirty;
    private final long[] loadedAt;
    private final long[] usedAt;

    /** Paginas presentes ahora mismo. */
    private int resident;
    /** Reloj de accesos: ordena las entradas para FIFO y LRU. */
    private long clock;

    private long faults;
    private long hits;
    private long swapsOut;
    private long swapsIn;
    private long zeroFills;

    /** Reutilizado en cada expulsion para no reservar memoria en el camino del fallo. */
    private final int[] candidates;
    private final View view = new View();

    private DemandPagedSpace(PhysicalMemory memory, SlotManager slots, int processId,
            String processName, int logicalUnits, int pageSize, int frames,
            PagePolicy policy, SwapArea swap, int[] trace) {
        super(memory, slots, processId, processName, logicalUnits);
        this.pageSize = pageSize;
        this.pageCount = (logicalUnits + pageSize - 1) / pageSize;
        this.frameCap = Math.min(frames, pageCount);
        this.policy = policy;
        this.swap = swap;
        this.trace = trace;
        this.frame = new int[pageCount];
        this.present = new boolean[pageCount];
        this.dirty = new boolean[pageCount];
        this.loadedAt = new long[pageCount];
        this.usedAt = new long[pageCount];
        this.candidates = new int[pageCount];
        for (int page = 0; page < pageCount; page++) {
            frame[page] = -1;
        }
    }

    /**
     * Crea el espacio sin cargar ninguna pagina.
     *
     * Nada de memoria se reserva aqui: el primer fallo de pagina es el que pide
     * el primer marco. Si el area de intercambio no cuadra con el tamano de
     * pagina, el error se ve ahora y no a mitad de una expulsion.
     */
    public static DemandPagedSpace create(PhysicalMemory memory, SlotManager slots, int processId,
            String processName, int logicalUnits, int pageSize, int frames,
            PagePolicy policy, SwapArea swap, int[] trace) {
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
        if (swap.pageSize() != pageSize) {
            throw new IllegalArgumentException("el area de intercambio es de " + swap.pageSize()
                    + " unidades y las paginas son de " + pageSize);
        }
        if (policy == PagePolicy.OPT && (trace == null || trace.length == 0)) {
            throw new IllegalArgumentException("OPT necesita la traza futura del proceso");
        }
        if (trace != null) {
            for (int address : trace) {
                if (address < 0 || address >= logicalUnits) {
                    throw new IllegalArgumentException("la traza sale del espacio logico: " + address);
                }
            }
        }
        return new DemandPagedSpace(memory, slots, processId, processName, logicalUnits,
                pageSize, frames, policy, swap, trace);
    }

    // ------------------------------------------------------------------
    // Traduccion y fallo de pagina
    // ------------------------------------------------------------------

    @Override
    protected void noteAccess(int address) {
        if (address >= 0 && address < logicalUnits()) {
            advanceTrace(address);
        }
    }

    @Override
    protected int translate(int address) {
        checkLogical(address);
        int page = address / pageSize;
        int offset = address % pageSize;
        clock++;
        if (present[page]) {
            hits++;
        } else {
            bring(page);
        }
        usedAt[page] = clock;
        return frame[page] + offset;
    }

    @Override
    protected void noteWrite(int address) {
        dirty[address / pageSize] = true;
    }

    /**
     * Mueve el cursor de la traza hasta la direccion que se esta pidiendo.
     *
     * OPT necesita saber que viene despues. La traza se consume a medida que el
     * proceso la recorre: si la direccion no aparece, se anota el desvio y se
     * sigue adelante con lo que quede, en vez de abortar. El desvio se cuenta
     * porque una traza que no cuadra invalida la cota, y hay que poder verlo.
     */
    private void advanceTrace(int address) {
        if (trace == null) {
            return;
        }
        while (traceCursor < trace.length && trace[traceCursor] != address) {
            traceCursor++;
        }
        if (traceCursor >= trace.length) {
            traceMisses++;
        } else {
            traceCursor++;
        }
    }

    /** Atiende un fallo de pagina: busca marco, expulsa si hace falta y carga. */
    private void bring(int page) {
        faults++;
        int target = -1;
        if (resident < frameCap) {
            target = tryReserveFrame();
        }
        if (target < 0) {
            int victim = chooseVictim();
            target = frame[victim];
            if (dirty[victim]) {
                swap.write(processId(), victim, dump(target));
                swapsOut++;
            }
            present[victim] = false;
            frame[victim] = -1;
            resident--;
        }

        frame[page] = target;
        if (swap.holds(processId(), page)) {
            restore(target, swap.read(processId(), page));
            swap.discard(processId(), page);
            swapsIn++;
        } else {
            clearFrame(target);
            zeroFills++;
        }
        present[page] = true;
        dirty[page] = false;
        loadedAt[page] = clock;
        resident++;
    }

    /**
     * Pide un marco al gestor de huecos mientras queden bajo la cota.
     *
     * Si la memoria fisica se acaba antes que la cota, se expulsa como si no
     * hubiera marcos libres: la cota es una decision de politica, pero la
     * memoria disponible manda.
     */
    private int tryReserveFrame() {
        try {
            return reserve(pageSize, "el marco " + (reservedSpans().size() + 1)
                    + " de '" + processName() + "'");
        } catch (PosFault fault) {
            if (resident == 0) {
                throw fault;
            }
            return -1;
        }
    }

    /** Elige la pagina que sale segun la politica puesta. */
    private int chooseVictim() {
        int count = 0;
        for (int page = 0; page < pageCount; page++) {
            if (present[page]) {
                candidates[count++] = page;
            }
        }
        if (count == 0) {
            throw new PosFault("proceso '" + processName() + "': no hay pagina que expulsar");
        }
        view.count = count;
        return candidates[policy.victimIndex(view)];
    }

    private byte[] dump(int frameStart) {
        byte[] data = new byte[pageSize];
        for (int i = 0; i < pageSize; i++) {
            data[i] = (byte) memory().readUnit(frameStart + i);
        }
        return data;
    }

    private void restore(int frameStart, byte[] data) {
        for (int i = 0; i < pageSize; i++) {
            memory().writeUnit(frameStart + i, data[i] & 0xFF);
        }
    }

    private void clearFrame(int frameStart) {
        for (int i = 0; i < pageSize; i++) {
            memory().writeUnit(frameStart + i, 0);
        }
    }

    // ------------------------------------------------------------------
    // Consulta
    // ------------------------------------------------------------------

    @Override
    public String schemeName() {
        return "demanda/" + pageSize;
    }

    public int pageSize() {
        return pageSize;
    }

    public int pageCount() {
        return pageCount;
    }

    /** Marcos que puede usar el proceso como maximo. */
    public int frameCap() {
        return frameCap;
    }

    /** Paginas en memoria ahora mismo. */
    public int residentPages() {
        return resident;
    }

    public PagePolicy policy() {
        return policy;
    }

    public SwapArea swap() {
        return swap;
    }

    public long faults() {
        return faults;
    }

    public long hits() {
        return hits;
    }

    public long swapsOut() {
        return swapsOut;
    }

    public long swapsIn() {
        return swapsIn;
    }

    /** Paginas que entraron rellenas de cero porque nunca se habian escrito. */
    public long zeroFills() {
        return zeroFills;
    }

    /** Accesos que no cuadraron con la traza futura. Con OPT, deberia ser cero. */
    public int traceMisses() {
        return traceMisses;
    }

    /** Marco fisico de una pagina, o -1 si no esta presente. */
    public int frameOf(int page) {
        if (page < 0 || page >= pageCount) {
            throw new IllegalArgumentException("pagina fuera de la tabla: " + page);
        }
        return frame[page];
    }

    public boolean isPresent(int page) {
        return page >= 0 && page < pageCount && present[page];
    }

    public boolean isDirty(int page) {
        return page >= 0 && page < pageCount && dirty[page];
    }

    @Override
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add("paginacion por demanda: pagina de " + pageSize + ", " + pageCount
                + " paginas, " + frameCap + " marcos, " + policy.label());
        int shown = Math.min(pageCount, 8);
        for (int page = 0; page < shown; page++) {
            int logicalFrom = page * pageSize;
            int logicalTo = Math.min(logicalFrom + pageSize, logicalUnits());
            if (present[page]) {
                lines.add(String.format(
                        "  pagina %2d logica [%d, %d) -> marco %d fisico [%d, %d) %s ultimo uso %d",
                        page, logicalFrom, logicalTo, frame[page], frame[page],
                        frame[page] + pageSize, dirty[page] ? "sucia" : "limpia", usedAt[page]));
            } else {
                lines.add(String.format(
                        "  pagina %2d logica [%d, %d) -> no presente%s",
                        page, logicalFrom, logicalTo,
                        swap.holds(processId(), page) ? " (en intercambio)" : " (sin cargar)"));
            }
        }
        if (pageCount > shown) {
            lines.add("  ... y " + (pageCount - shown) + " paginas mas");
        }
        return lines;
    }

    /**
     * Fallos de los invariantes de la memoria fisica vistos desde este espacio.
     *
     * Lo usan la demostracion y las pruebas: el espacio es quien sabe cuantas
     * paginas deberia haber presentes, y la memoria quien sabe quien es dueno
     * de cada unidad. Los dos tienen que cuadrar.
     */
    public List<String> invariantFailures() {
        return memory().checkInvariants();
    }

    /** Una linea con el recuento de todo lo que ha pasado por este espacio. */
    public String counters() {
        long accesses = faults + hits;
        return String.format(
                "fallos %d de %d accesos (%s), salidas %d, entradas %d, ceros %d, en memoria %d/%d",
                faults, accesses, percent(faults, accesses), swapsOut, swapsIn, zeroFills,
                resident, pageCount);
    }

    private String percent(long part, long total) {
        if (total == 0) {
            return "0%";
        }
        return String.format("%.0f%%", part * 100.0 / total);
    }

    /** Vista de las paginas presentes que se pasa a la politica. */
    private final class View implements PagePolicy.Resident {

        private int count;

        @Override
        public int count() {
            return count;
        }

        @Override
        public int pageAt(int index) {
            return candidates[index];
        }

        @Override
        public long loadedAt(int index) {
            return DemandPagedSpace.this.loadedAt[candidates[index]];
        }

        @Override
        public long usedAt(int index) {
            return DemandPagedSpace.this.usedAt[candidates[index]];
        }

        @Override
        public int nextUseOf(int page) {
            if (trace == null) {
                return Integer.MAX_VALUE;
            }
            for (int i = traceCursor; i < trace.length; i++) {
                if (trace[i] / pageSize == page) {
                    return i - traceCursor;
                }
            }
            return Integer.MAX_VALUE;
        }
    }
}
