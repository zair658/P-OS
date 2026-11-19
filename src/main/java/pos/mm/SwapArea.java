package pos.mm;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import pos.base.PosFault;
import pos.fs.FileSystem;

/**
 * Area de intercambio: las paginas expulsadas, guardadas en un fichero del
 * sistema de ficheros de P-OS.
 *
 * No es memoria del anfitrion ni un arreglo escondido en el nucleo: es el
 * fichero {@code /intercambio}, con su FCB, sus bloques y su entrada en el
 * directorio. Si el sistema se queda sin sitio en disco, el intercambio falla
 * como fallaria cualquier otro fichero, y el montaje lo delata si la imagen no
 * cuadra. Eso es lo que se queria de la fase 3: que el area de intercambio no
 * fuese una trampa, sino parte del sistema.
 *
 * Formato, todo en orden de bytes de red para que la imagen sea portable:
 * <pre>
 *   cabecera  12 bytes: magia "PSW1", tamano de pagina, numero de ranuras
 *   ranura    (8 + tamano de pagina) bytes:
 *             identificador de proceso (-1 si libre), numero de pagina, datos
 * </pre>
 *
 * El fichero se reescribe entero en cada movimiento. Con estas medidas son unos
 * cientos de bytes y el coste es despreciable; a cambio, la imagen que queda en
 * disco siempre esta completa y se puede montar y leer desde fuera. Se paga en
 * bloques escritos, y eso se ve en los contadores del disco.
 */
public final class SwapArea {

    /** Nombre del fichero dentro del sistema de P-OS. */
    public static final String PATH = "/intercambio";

    /** "PSW1" en hexadecimal: identifica el fichero como area de intercambio. */
    private static final int MAGIC = 0x50535731;

    private static final int HEADER_BYTES = 12;
    private static final int SLOT_HEADER = 8;
    private static final int FREE = -1;

    private final FileSystem fs;
    private final byte[] raw;
    private final int pageSize;
    private final int slots;

    private long outs;
    private long ins;

    private SwapArea(FileSystem fs, byte[] raw, int pageSize, int slots) {
        this.fs = fs;
        this.raw = raw;
        this.pageSize = pageSize;
        this.slots = slots;
    }

    // ------------------------------------------------------------------
    // Creacion y apertura
    // ------------------------------------------------------------------

    /** Bytes que ocupa un area con esas medidas. */
    public static int bytesFor(int pageSize, int slots) {
        return HEADER_BYTES + slots * (SLOT_HEADER + pageSize);
    }

    /**
     * Crea el area de intercambio como fichero nuevo.
     *
     * Si el fichero ya existe se sobrescribe: formatear el area es tirar lo que
     * hubiera dentro, igual que formatear el disco.
     */
    public static SwapArea format(FileSystem fs, int pageSize, int slots) {
        if (fs == null) {
            throw new IllegalArgumentException("el area de intercambio necesita un sistema de ficheros");
        }
        if (pageSize <= 0) {
            throw new IllegalArgumentException("el tamano de pagina debe ser positivo: " + pageSize);
        }
        if (slots <= 0) {
            throw new IllegalArgumentException("el area de intercambio necesita ranuras: " + slots);
        }
        byte[] raw = new byte[bytesFor(pageSize, slots)];
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        buffer.putInt(MAGIC);
        buffer.putInt(pageSize);
        buffer.putInt(slots);
        for (int slot = 0; slot < slots; slot++) {
            int offset = slotOffset(slot, pageSize);
            buffer.putInt(offset, FREE);
            buffer.putInt(offset + 4, FREE);
        }
        fs.create(PATH, raw);
        return new SwapArea(fs, raw, pageSize, slots);
    }

    /** Abre el area de intercambio que ya esta en el sistema de ficheros. */
    public static SwapArea open(FileSystem fs) {
        if (fs == null) {
            throw new IllegalArgumentException("el area de intercambio necesita un sistema de ficheros");
        }
        byte[] raw = fs.read(PATH);
        if (raw.length < HEADER_BYTES) {
            throw new PosFault("el area de intercambio esta truncada: " + raw.length + " bytes");
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        int magic = buffer.getInt();
        if (magic != MAGIC) {
            throw new PosFault(String.format("'%s' no es un area de intercambio (magia 0x%08X)", PATH, magic));
        }
        int pageSize = buffer.getInt();
        int slots = buffer.getInt();
        int esperado = bytesFor(pageSize, slots);
        if (raw.length != esperado) {
            throw new PosFault("el area de intercambio declara " + esperado
                    + " bytes y ocupa " + raw.length);
        }
        return new SwapArea(fs, raw, pageSize, slots);
    }

    /** Desplazamiento del encabezado de una ranura. */
    private static int slotOffset(int slot, int pageSize) {
        return HEADER_BYTES + slot * (SLOT_HEADER + pageSize);
    }

    // ------------------------------------------------------------------
    // Contenido
    // ------------------------------------------------------------------

    public int pageSize() {
        return pageSize;
    }

    public int slots() {
        return slots;
    }

    /** Bytes que ocupa el fichero. */
    public int sizeBytes() {
        return raw.length;
    }

    /** Paginas expulsadas que guarda ahora mismo. */
    public int usedSlots() {
        int usadas = 0;
        for (int slot = 0; slot < slots; slot++) {
            if (processOf(slot) != FREE) {
                usadas++;
            }
        }
        return usadas;
    }

    /** Paginas que aun caben sin tocar nada. */
    public int freeSlots() {
        return slots - usedSlots();
    }

    /** Paginas escritas a disco desde que se creo el area. */
    public long outs() {
        return outs;
    }

    /** Paginas leidas de disco desde que se creo el area. */
    public long ins() {
        return ins;
    }

    public boolean holds(int processId, int page) {
        return slotOf(processId, page) >= 0;
    }

    /**
     * Guarda una pagina expulsada.
     *
     * Si esa pagina ya estaba guardada se reutiliza su ranura. Si no hay
     * ranuras libres no se improvisa: el intercambio se ha quedado sin sitio y
     * eso es un fallo del sistema, con el numero de ranuras en el mensaje.
     */
    public void write(int processId, int page, byte[] data) {
        if (data.length != pageSize) {
            throw new IllegalArgumentException("una pagina de " + pageSize
                    + " unidades no puede escribirse con " + data.length);
        }
        int slot = slotOf(processId, page);
        if (slot < 0) {
            slot = freeSlot();
        }
        if (slot < 0) {
            throw new PosFault("el area de intercambio esta llena: " + slots
                    + " ranuras de " + pageSize + " unidades ocupadas");
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        int offset = slotOffset(slot, pageSize);
        buffer.putInt(offset, processId);
        buffer.putInt(offset + 4, page);
        System.arraycopy(data, 0, raw, offset + SLOT_HEADER, pageSize);
        fs.write(PATH, raw);
        outs++;
    }

    /** Devuelve el contenido de una pagina expulsada. */
    public byte[] read(int processId, int page) {
        int slot = slotOf(processId, page);
        if (slot < 0) {
            throw new PosFault("la pagina " + page + " del proceso " + processId
                    + " no esta en el area de intercambio");
        }
        byte[] data = new byte[pageSize];
        System.arraycopy(raw, slotOffset(slot, pageSize) + SLOT_HEADER, data, 0, pageSize);
        ins++;
        return data;
    }

    /**
     * Suelta la ranura de una pagina que vuelve a memoria.
     *
     * Se escribe el fichero para que lo que queda en disco no mencione una
     * pagina que ya no esta fuera.
     */
    public void discard(int processId, int page) {
        int slot = slotOf(processId, page);
        if (slot < 0) {
            return;
        }
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        int offset = slotOffset(slot, pageSize);
        buffer.putInt(offset, FREE);
        buffer.putInt(offset + 4, FREE);
        java.util.Arrays.fill(raw, offset + SLOT_HEADER, offset + SLOT_HEADER + pageSize, (byte) 0);
        fs.write(PATH, raw);
    }

    private int slotOf(int processId, int page) {
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        for (int slot = 0; slot < slots; slot++) {
            int offset = slotOffset(slot, pageSize);
            if (buffer.getInt(offset) == processId && buffer.getInt(offset + 4) == page) {
                return slot;
            }
        }
        return -1;
    }

    private int processOf(int slot) {
        ByteBuffer buffer = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        return buffer.getInt(slotOffset(slot, pageSize));
    }

    private int freeSlot() {
        for (int slot = 0; slot < slots; slot++) {
            if (processOf(slot) == FREE) {
                return slot;
            }
        }
        return -1;
    }

    @Override
    public String toString() {
        return "intercambio " + PATH + ": " + usedSlots() + " de " + slots + " ranuras de "
                + pageSize + " unidades, " + raw.length + " bytes";
    }
}
