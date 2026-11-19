package pos.fs;

import java.nio.charset.StandardCharsets;

import pos.base.PosFault;

/**
 * Bloque de control de fichero: lo que el sistema sabe de un fichero.
 *
 * Guarda el identificador, el nombre, si es directorio, el tamano en bytes, la
 * asignacion de bloques y dos marcas de tiempo logicas. No guarda el contenido:
 * eso esta en los bloques de datos.
 *
 * Se serializa en un bloque entero con una suma de comprobacion en los dos
 * ultimos bytes, que cubre todo lo anterior. La suma no es criptografia, es
 * deteccion de corrupcion: si alguien cambia un byte del FCB a mano, al montar
 * sale el aviso en vez de un comportamiento raro.
 */
public record Fcb(int id, String name, boolean directory, int size, Allocation allocation,
        int created, int modified) {

    /** Longitud maxima del nombre en bytes. */
    public static final int MAX_NAME = 12;

    /** Tamano minimo de bloque que admite un FCB serializado. */
    public static final int MIN_BLOCK = 42;

    /** Desplazamiento del tamano en el bloque. */
    private static final int OFF_SIZE = 18;

    /** Desplazamiento de la marca de creacion en el bloque. */
    private static final int OFF_CREATED = 30;

    /** Desplazamiento de la marca de escritura en el bloque. */
    private static final int OFF_MODIFIED = 34;

    public Fcb {
        if (id < 0) {
            throw new IllegalArgumentException("el FCB necesita identificador: " + id);
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("el FCB necesita nombre");
        }
        if (name.length() > MAX_NAME) {
            throw new IllegalArgumentException("el nombre '" + name + "' pasa de " + MAX_NAME + " caracteres");
        }
        if (name.indexOf('/') >= 0) {
            throw new IllegalArgumentException("el nombre '" + name + "' no puede llevar barra");
        }
        if (size < 0) {
            throw new IllegalArgumentException("tamano negativo en '" + name + "': " + size);
        }
        if (allocation == null) {
            throw new IllegalArgumentException("el FCB necesita una asignacion (aunque sea vacia)");
        }
    }

    public boolean isFile() {
        return !directory;
    }

    /** Bloque fisico del bloque logico indicado. */
    public int blockAt(AllocationMethod method, DiskImage image, int logicalBlock) {
        return method.physicalBlock(image, allocation, logicalBlock);
    }

    /** Copia con contenido nuevo: cambia tamano y asignacion, y la marca de escritura. */
    public Fcb withContent(int newSize, Allocation newAllocation, int tick) {
        return new Fcb(id, name, directory, newSize, newAllocation, created, tick);
    }

    /** Copia con la marca de escritura al dia. */
    public Fcb touched(int tick) {
        return new Fcb(id, name, directory, size, allocation, created, tick);
    }

    // ------------------------------------------------------------------
    // Serializacion
    // ------------------------------------------------------------------

    /**
     * Serializa el FCB en un bloque.
     *
     * Colocacion: identificador, banderas, longitud y nombre, tamano, los dos
     * campos de la asignacion, las marcas de tiempo y la suma de comprobacion.
     */
    public byte[] toBytes(int blockSize) {
        if (blockSize < MIN_BLOCK) {
            throw new PosFault("el bloque de " + blockSize + " bytes no cabe un FCB");
        }
        byte[] data = new byte[blockSize];
        DiskImage.writeInt(data, 0, id);
        data[4] = (byte) ((directory ? 1 : 0) | 2);
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        data[5] = (byte) bytes.length;
        System.arraycopy(bytes, 0, data, 6, bytes.length);
        DiskImage.writeInt(data, OFF_SIZE, size);
        DiskImage.writeInt(data, OFF_SIZE + 4, firstField());
        DiskImage.writeInt(data, OFF_SIZE + 8, secondField());
        DiskImage.writeInt(data, OFF_CREATED, created);
        DiskImage.writeInt(data, OFF_MODIFIED, modified);
        int sum = checksum(data);
        data[blockSize - 2] = (byte) (sum >>> 8);
        data[blockSize - 1] = (byte) sum;
        return data;
    }

    /** Primer campo de la asignacion, tal como se guarda. */
    private int firstField() {
        return switch (allocation) {
            case Allocation.Empty empty -> 0;
            case Allocation.Contiguous contiguous -> contiguous.first();
            case Allocation.Chained chained -> chained.first();
            case Allocation.Indexed indexed -> indexed.indexBlock();
        };
    }

    /** Segundo campo de la asignacion, tal como se guarda. */
    private int secondField() {
        return switch (allocation) {
            case Allocation.Empty empty -> 0;
            case Allocation.Contiguous contiguous -> contiguous.length();
            case Allocation.Chained chained -> chained.dataBlocks();
            case Allocation.Indexed indexed -> indexed.dataBlocks();
        };
    }

    /**
     * Reconstruye un FCB de un bloque.
     *
     * @return el FCB, o {@code null} si el bloque no esta en uso
     */
    public static Fcb fromBytes(byte[] data, int expectedId, AllocationMethod method) {
        if (data.length < MIN_BLOCK) {
            throw new PosFault("bloque demasiado pequeno para un FCB");
        }
        int flags = data[4] & 0xFF;
        if ((flags & 2) == 0) {
            return null;
        }
        int stored = ((data[data.length - 2] & 0xFF) << 8) | (data[data.length - 1] & 0xFF);
        int computed = checksum(data);
        if (stored != computed) {
            throw new PosFault("el FCB " + expectedId + " esta corrupto: suma 0x"
                    + Integer.toHexString(stored) + ", calculada 0x" + Integer.toHexString(computed));
        }
        int nameLen = data[5] & 0xFF;
        if (nameLen <= 0 || nameLen > MAX_NAME) {
            throw new PosFault("el FCB " + expectedId + " tiene un nombre de " + nameLen + " bytes");
        }
        String name = new String(data, 6, nameLen, StandardCharsets.US_ASCII);
        int size = DiskImage.readInt(data, OFF_SIZE);
        int first = DiskImage.readInt(data, OFF_SIZE + 4);
        int second = DiskImage.readInt(data, OFF_SIZE + 8);
        int created = DiskImage.readInt(data, OFF_CREATED);
        int modified = DiskImage.readInt(data, OFF_MODIFIED);
        Allocation allocation = switch (method) {
            case AllocationMethod.Contiguous contiguous ->
                    second == 0 ? new Allocation.Empty() : new Allocation.Contiguous(first, second);
            case AllocationMethod.Linked linked ->
                    second == 0 ? new Allocation.Empty() : new Allocation.Chained(first, second);
            case AllocationMethod.Indexed indexed ->
                    second == 0 ? new Allocation.Empty() : new Allocation.Indexed(first, second);
        };
        return new Fcb(expectedId, name, (flags & 1) != 0, size, allocation, created, modified);
    }

    /** Suma de los bytes hasta la marca de escritura, sin contar la propia suma. */
    private static int checksum(byte[] data) {
        int sum = 0;
        for (int i = 0; i < OFF_MODIFIED + 4; i++) {
            sum = (sum + (data[i] & 0xFF)) & 0xFFFF;
        }
        return sum;
    }

    /** Linea de la tabla de ficheros. */
    public String describe() {
        return String.format("%-2d %-13s %-4s %6d  %s", id, name, directory ? "dir" : "fich",
                size, allocation);
    }
}
