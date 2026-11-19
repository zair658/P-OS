package pos.fs;

import java.util.Arrays;

import pos.base.PosFault;

/**
 * Imagen de disco: el almacenamiento de bloques del sistema de ficheros.
 *
 * Es un arreglo plano de bytes dividido en bloques de tamano fijo. Todo lo que
 * hay por encima (superbloque, mapa de libres, FCB, datos) son bloques de este
 * arreglo; nadie guarda estado fuera de aqui. De ahi que la imagen se pueda
 * volcar a un fichero y volver a montar sin perder nada.
 *
 * La clase lleva dos contadores, lecturas y escrituras de bloque, porque en
 * esta fase medir es parte del trabajo: con el mismo contenido, leer el bloque
 * cien de un fichero cuesta una lectura en contiguo, cien en enlazada y una en
 * indexada. Esos contadores son la prueba de la diferencia.
 */
public final class DiskImage {

    /**
     * Tamano de bloque por defecto, en bytes.
     *
     * Sesenta y cuatro es el menor tamano redondo que deja sitio al FCB
     * serializado (cuarenta y dos bytes con su suma de comprobacion) y a cuatro
     * entradas de directorio. Un bloque de treinta y dos bytes no cabe el FCB.
     */
    public static final int DEFAULT_BLOCK_SIZE = 64;

    /** Numero de bloques por defecto. */
    public static final int DEFAULT_BLOCKS = 256;

    private final int blockSize;
    private final byte[] bytes;

    private long blockReads;
    private long blockWrites;

    /** Crea una imagen vacia, con todos los bloques a cero. */
    public DiskImage(int blockCount, int blockSize) {
        if (blockCount <= 0) {
            throw new IllegalArgumentException("la imagen necesita al menos un bloque: " + blockCount);
        }
        if (blockSize < 8) {
            throw new IllegalArgumentException("un bloque de " + blockSize
                    + " bytes es demasiado pequeno para caber una cabecera");
        }
        this.blockSize = blockSize;
        this.bytes = new byte[blockCount * blockSize];
    }

    /** Crea una imagen con el tamano por defecto. */
    public DiskImage() {
        this(DEFAULT_BLOCKS, DEFAULT_BLOCK_SIZE);
    }

    /** Reconstruye una imagen a partir del contenido de un fichero. */
    static DiskImage ofBytes(byte[] raw, int blockSize) {
        if (raw.length == 0 || raw.length % blockSize != 0) {
            throw new PosFault("la imagen no es multiplo del tamano de bloque " + blockSize
                    + " (" + raw.length + " bytes)");
        }
        DiskImage image = new DiskImage(raw.length / blockSize, blockSize);
        System.arraycopy(raw, 0, image.bytes, 0, raw.length);
        return image;
    }

    // ------------------------------------------------------------------
    // Lectura y escritura de bloques
    // ------------------------------------------------------------------

    public int blockSize() {
        return blockSize;
    }

    public int blockCount() {
        return bytes.length / blockSize;
    }

    /** Contenido de un bloque, como copia. */
    public byte[] read(int block) {
        checkBlock(block);
        blockReads++;
        return Arrays.copyOfRange(bytes, block * blockSize, (block + 1) * blockSize);
    }

    /** Sustituye el contenido de un bloque. */
    public void write(int block, byte[] data) {
        checkBlock(block);
        if (data.length != blockSize) {
            throw new IllegalArgumentException("el bloque " + block + " mide " + blockSize
                    + " bytes y se intentan escribir " + data.length);
        }
        blockWrites++;
        System.arraycopy(data, 0, bytes, block * blockSize, blockSize);
    }

    /** Escribe un bloque entero a cero. */
    public void clear(int block) {
        write(block, new byte[blockSize]);
    }

    /** Lee un entero de 4 bytes dentro de un bloque. */
    public int readInt(int block, int offset) {
        byte[] data = read(block);
        return readInt(data, offset);
    }

    /** Escribe un entero de 4 bytes dentro de un bloque. */
    public void writeInt(int block, int offset, int value) {
        byte[] data = read(block);
        writeInt(data, offset, value);
        write(block, data);
    }

    static int readInt(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) {
            throw new PosFault("lectura de entero fuera del bloque: desplazamiento " + offset);
        }
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    static void writeInt(byte[] data, int offset, int value) {
        if (offset < 0 || offset + 4 > data.length) {
            throw new PosFault("escritura de entero fuera del bloque: desplazamiento " + offset);
        }
        data[offset] = (byte) (value >>> 24);
        data[offset + 1] = (byte) (value >>> 16);
        data[offset + 2] = (byte) (value >>> 8);
        data[offset + 3] = (byte) value;
    }

    /** Comprueba que un numero de bloque existe. */
    public void checkBlock(int block) {
        if (block < 0 || block >= blockCount()) {
            throw new PosFault("bloque " + block + " fuera de la imagen (0.." + (blockCount() - 1) + ")");
        }
    }

    /** Copia todos los bytes de la imagen, para volcarla a un fichero. */
    public byte[] toByteArray() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    // ------------------------------------------------------------------
    // Metricas
    // ------------------------------------------------------------------

    public long blockReads() {
        return blockReads;
    }

    public long blockWrites() {
        return blockWrites;
    }

    public void resetCounters() {
        blockReads = 0;
        blockWrites = 0;
    }

    /** Texto de una linea con el trabajo de disco acumulado. */
    public String stats() {
        return "lecturas " + blockReads + ", escrituras " + blockWrites;
    }

    // ------------------------------------------------------------------
    // Diagnostico
    // ------------------------------------------------------------------

    /** Marca de un bloque segun el mapa de ocupacion, para dibujar el disco. */
    public String render(java.util.function.IntPredicate used) {
        StringBuilder sb = new StringBuilder(bytes.length / blockSize);
        for (int block = 0; block < blockCount(); block++) {
            sb.append(used.test(block) ? '#' : '.');
        }
        return sb.toString();
    }
}
