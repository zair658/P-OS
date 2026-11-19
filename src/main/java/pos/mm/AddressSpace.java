package pos.mm;

import java.util.ArrayList;
import java.util.List;

import pos.alloc.SlotChoice;
import pos.alloc.SlotManager;
import pos.base.FreeSpan;
import pos.base.PosFault;
import pos.phys.PhysicalMemory;

/**
 * Espacio de direcciones de un proceso: el traductor.
 *
 * Un proceso nunca ve una direccion fisica. Ve direcciones logicas, de 0 a
 * {@code logicalUnits - 1}, y este objeto las convierte. Cada esquema guarda su
 * propia tabla (registro base, tabla de paginas, tabla de segmentos) y cada uno
 * reserva memoria fisica a su manera.
 *
 * Hay dos reglas que todos los esquemas respetan y que el nucleo puede dar por
 * ciertas sin saber cual esta puesto:
 * <ul>
 *   <li>Ningun proceso puede leer ni escribir fuera de su espacio logico.</li>
 *   <li>Al liberar, devuelve exactamente los tramos que reservo, ni uno mas.</li>
 * </ul>
 *
 * Lo que si cambia entre esquemas es como se reparte la memoria fisica y cuanto
 * se desperdicia. Esa diferencia es la que se mide en la demostracion.
 */
public abstract class AddressSpace {

    private final PhysicalMemory memory;
    private final SlotManager slots;
    private final int processId;
    private final String processName;
    private final int logicalUnits;
    private final List<FreeSpan> reserved = new ArrayList<>();
    private final List<SlotChoice> placements = new ArrayList<>();

    protected AddressSpace(PhysicalMemory memory, SlotManager slots, int processId,
            String processName, int logicalUnits) {
        if (memory == null) {
            throw new IllegalArgumentException("el espacio de direcciones necesita memoria fisica");
        }
        if (slots == null) {
            throw new IllegalArgumentException("el espacio de direcciones necesita un gestor de huecos");
        }
        if (logicalUnits <= 0) {
            throw new PosFault("el proceso '" + processName
                    + "' necesita al menos una unidad logica de memoria");
        }
        this.memory = memory;
        this.slots = slots;
        this.processId = processId;
        this.processName = processName;
        this.logicalUnits = logicalUnits;
    }

    // ------------------------------------------------------------------
    // Acceso del proceso: direcciones logicas
    // ------------------------------------------------------------------

    /** Lee una unidad logica del proceso. */
    public final int load(int address) {
        return memory.readUnit(translate(address));
    }

    /** Escribe una unidad logica del proceso. */
    public final void store(int address, int value) {
        memory.writeUnit(translate(address), value);
    }

    /**
     * Convierte una direccion logica en fisica.
     *
     * Es donde esta la esencia de cada esquema. Si la traduccion no es posible
     * (fuera de limite, pagina no presente, direccion que no cae en ningun
     * segmento) se lanza un fallo con el motivo concreto.
     */
    protected abstract int translate(int address);

    /**
     * Direccion fisica que corresponde a una logica.
     *
     * Es la misma traduccion que usa {@link #load} y {@link #store}, expuesta
     * solo para diagnostico y para las tablas de la demostracion: un proceso no
     * tiene forma de llamarla.
     */
    public final int physicalAddressOf(int address) {
        return translate(address);
    }

    /**
     * Comprueba que la direccion cae en el espacio logico del proceso.
     *
     * Es la primera barrera de proteccion y la unica que comparten los tres
     * esquemas. Cada traductor puede anadir comprobaciones propias despues.
     */
    protected final void checkLogical(int address) {
        if (address < 0 || address >= logicalUnits) {
            throw new PosFault("proceso '" + processName + "': direccion " + address
                    + " fuera de su espacio logico (0.." + (logicalUnits - 1) + ")");
        }
    }

    // ------------------------------------------------------------------
    // Reserva y liberacion de memoria fisica
    // ------------------------------------------------------------------

    /**
     * Reserva un tramo contiguo de memoria fisica para este proceso.
     *
     * Pregunta al gestor de huecos, que es quien decide donde; aqui solo se
     * ejecuta la decision y se anota. La razon del gestor se guarda, porque el
     * sistema tiene que poder explicar por que la memoria quedo como quedo.
     *
     * @param units para que se reserva, en singular y con articulo
     * @return direccion fisica inicial del tramo reservado
     */
    protected final int reserve(int units, String what) {
        SlotChoice choice = slots.choose(memory.freeSpans(), units);
        if (!choice.found()) {
            throw new PosFault("proceso '" + processName + "': no hay " + units
                    + " unidades contiguas para " + what + " (" + choice.reason()
                    + "; el hueco mayor es de "
                    + (memory.largestFreeSpan() == null ? 0 : memory.largestFreeSpan().length())
                    + " unidades)");
        }
        int start = choice.chosen().start();
        memory.allocate(processId, start, units);
        reserved.add(new FreeSpan(start, units));
        placements.add(choice);
        return start;
    }

    /** Devuelve a memoria fisica todo lo que este espacio tenia reservado. */
    public final void release() {
        for (FreeSpan span : reserved) {
            memory.release(processId, span.start(), span.length());
        }
        reserved.clear();
        placements.clear();
    }

    // ------------------------------------------------------------------
    // Consulta
    // ------------------------------------------------------------------

    public final int processId() {
        return processId;
    }

    public final String processName() {
        return processName;
    }

    /** Unidades logicas que el proceso puede direccionar. */
    public final int logicalUnits() {
        return logicalUnits;
    }

    /** Unidades fisicas realmente ocupadas: lo que cuesta el proceso de verdad. */
    public final int physicalUnits() {
        int total = 0;
        for (FreeSpan span : reserved) {
            total += span.length();
        }
        return total;
    }

    /**
     * Unidades fisicas reservadas que el proceso no puede usar.
     *
     * En paginacion es el resto de la ultima pagina; en contiguo y segmentado
     * suele ser cero, porque se reserva justo lo pedido.
     */
    public final int wastedUnits() {
        return physicalUnits() - logicalUnits;
    }

    /** Tramos fisicos reservados, de menor a mayor direccion. */
    public final List<FreeSpan> reservedSpans() {
        List<FreeSpan> spans = new ArrayList<>(reserved);
        spans.sort((a, b) -> Integer.compare(a.start(), b.start()));
        return List.copyOf(spans);
    }

    /** Decisiones del gestor de huecos, para poder explicar la colocacion. */
    public final List<SlotChoice> placements() {
        return List.copyOf(placements);
    }

    /** Nombre corto del esquema, para tablas y trazas. */
    public abstract String schemeName();

    /** Volcado de la tabla de traduccion: una linea por entrada. */
    public abstract List<String> describe();

    /** Resumen de una linea con el coste real del proceso. */
    public final String summary() {
        int waste = wastedUnits();
        String extra = waste == 0
                ? "sin desperdicio"
                : "desperdicia " + waste + " unidades (" + percent(waste) + ")";
        return String.format("%-14s logico %4d  fisico %4d  %s",
                schemeName(), logicalUnits, physicalUnits(), extra);
    }

    private String percent(int waste) {
        if (physicalUnits() == 0) {
            return "0%";
        }
        return String.format("%.1f%%", waste * 100.0 / physicalUnits());
    }

    @Override
    public String toString() {
        return schemeName() + " de '" + processName + "' (" + logicalUnits + " unidades logicas)";
    }
}
