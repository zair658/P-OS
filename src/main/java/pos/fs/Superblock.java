package pos.fs;

import pos.base.PosFault;

/**
 * Superbloque: la cabecera de la imagen.
 *
 * Dice que la imagen es nuestra, con que version se creo, de que tamano son sus
 * bloques, cuantos hay, cuantos quedan libres, que metodo de asignacion esta
 * puesto y cual es el FCB del directorio raiz.
 *
 * El campo de bloques libres esta duplicado a proposito: tambien se puede contar
 * en el mapa de bits. Tenerlo dos veces es un invariante que se comprueba al
 * montar; si alguien toca el mapa y no el superbloque, el sistema lo detecta en
 * vez de seguir creyendo un numero falso. Un campo redundante que se verifica
 * vale mas que un campo que nadie contrasta.
 */
public record Superblock(int magic, int version, int blockSize, int blockCount, int freeBlocks,
        int methodCode, int rootFcb) {

    /** Marca de la imagen: las letras POSF en ASCII. */
    public static final int MAGIC = 0x504F5346;

    /** Version del formato. Cambia si cambia la colocacion de los campos. */
    public static final int VERSION = 1;

    public Superblock {
        if (blockSize <= 0 || blockCount <= 0) {
            throw new IllegalArgumentException("superbloque con geometria invalida");
        }
        if (freeBlocks < 0 || freeBlocks > blockCount) {
            throw new IllegalArgumentException("bloques libres fuera de rango: " + freeBlocks);
        }
    }

    /** Superbloque de una imagen recien formateada. */
    public static Superblock fresh(FsLayout layout, int freeBlocks, AllocationMethod method,
            int rootFcb) {
        return new Superblock(MAGIC, VERSION, layout.blockSize(), layout.blockCount(), freeBlocks,
                method.code(), rootFcb);
    }

    /** Metodo de asignacion que declara el superbloque. */
    public AllocationMethod method() {
        return AllocationMethod.of(methodCode);
    }

    /**
     * Comprueba que el superbloque tiene sentido por si mismo.
     *
     * @return lista de problemas; vacia si es coherente
     */
    public java.util.List<String> problems(FsLayout layout) {
        java.util.List<String> problems = new java.util.ArrayList<>();
        if (magic != MAGIC) {
            problems.add("la imagen no es de este sistema: marca 0x"
                    + Integer.toHexString(magic).toUpperCase() + " en vez de 0x"
                    + Integer.toHexString(MAGIC).toUpperCase());
            return problems;
        }
        if (version != VERSION) {
            problems.add("version del formato " + version + ", se esperaba " + VERSION
                    + "; esta version no sabe montarla");
        }
        if (blockSize != layout.blockSize()) {
            problems.add("el superbloque dice bloques de " + blockSize
                    + " bytes y la imagen tiene " + layout.blockSize());
        }
        if (blockCount != layout.blockCount()) {
            problems.add("el superbloque dice " + blockCount + " bloques y la imagen tiene "
                    + layout.blockCount());
        }
        try {
            AllocationMethod.of(methodCode);
        } catch (PosFault fault) {
            problems.add(fault.getMessage());
        }
        if (rootFcb < 0 || rootFcb >= layout.maxFiles()) {
            problems.add("el FCB raiz " + rootFcb + " no existe (hay " + layout.maxFiles() + ")");
        }
        return problems;
    }

    // ------------------------------------------------------------------
    // Serializacion
    // ------------------------------------------------------------------

    /** Escribe el superbloque en un bloque de la imagen. */
    public void writeTo(DiskImage image) {
        byte[] data = new byte[image.blockSize()];
        DiskImage.writeInt(data, 0, magic);
        DiskImage.writeInt(data, 4, version);
        DiskImage.writeInt(data, 8, blockSize);
        DiskImage.writeInt(data, 12, blockCount);
        DiskImage.writeInt(data, 16, freeBlocks);
        DiskImage.writeInt(data, 20, methodCode);
        DiskImage.writeInt(data, 24, rootFcb);
        image.write(0, data);
    }

    /**
     * Lee el superbloque del bloque 0.
     *
     * Comprueba la marca antes de construir el registro: una imagen ajena, o
     * vacia, no tiene por que traer numeros con sentido, y leerla a ciegas daria
     * un error de geometria en vez del mensaje que el usuario necesita.
     */
    public static Superblock readFrom(DiskImage image) {
        byte[] data = image.read(0);
        if (data.length < 28) {
            throw new PosFault("el bloque 0 es demasiado pequeno para el superbloque");
        }
        int magic = DiskImage.readInt(data, 0);
        if (magic != MAGIC) {
            throw new PosFault("la imagen no es de este sistema: marca 0x"
                    + Integer.toHexString(magic).toUpperCase() + " en vez de 0x"
                    + Integer.toHexString(MAGIC).toUpperCase());
        }
        int version = DiskImage.readInt(data, 4);
        int blockSize = DiskImage.readInt(data, 8);
        int blockCount = DiskImage.readInt(data, 12);
        if (blockSize <= 0 || blockCount <= 0) {
            throw new PosFault("el superbloque declara una geometria imposible: "
                    + blockCount + " bloques de " + blockSize + " bytes");
        }
        return new Superblock(magic, version, blockSize, blockCount,
                DiskImage.readInt(data, 16),
                DiskImage.readInt(data, 20),
                DiskImage.readInt(data, 24));
    }

    /** Resumen de una linea para la consola. */
    public String describe() {
        return "formato v" + version + ", " + blockCount + " bloques de " + blockSize
                + " bytes, metodo " + method().name() + ", " + freeBlocks + " libres";
    }
}
