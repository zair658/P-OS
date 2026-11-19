package pos.fs;

/**
 * Reparto fijo de la imagen en zonas.
 *
 * La imagen empieza por el superbloque, sigue el mapa de bloques libres y la
 * tabla de FCB, y el resto son bloques de datos. Las zonas son fijas a
 * proposito: con un reparto variable habria que escribir un asignador para el
 * propio sistema de ficheros, y eso no aporta nada a lo que esta fase demuestra.
 *
 * El numero de FCB es el numero de bloques de la tabla, uno por bloque. Un
 * limite pequeno y visible es mejor para la defensa que un limite grande que
 * nadie llega a tocar.
 */
public record FsLayout(int blockCount, int blockSize, int fcbBlocks) {

    /** Bloques reservados para la tabla de FCB. */
    public static final int DEFAULT_FCB_BLOCKS = 16;

    public FsLayout {
        if (blockCount <= 0) {
            throw new IllegalArgumentException("la imagen necesita bloques: " + blockCount);
        }
        if (blockSize < Fcb.MIN_BLOCK) {
            throw new IllegalArgumentException("un bloque de " + blockSize
                    + " bytes no cabe el FCB, que necesita " + Fcb.MIN_BLOCK);
        }
        if (fcbBlocks <= 0) {
            throw new IllegalArgumentException("la tabla de FCB necesita al menos un bloque");
        }
        int minimum = 1 + FreeBlockMap.blocksFor(blockCount, blockSize) + fcbBlocks + 1;
        if (blockCount < minimum) {
            throw new IllegalArgumentException("una imagen de " + blockCount
                    + " bloques no tiene sitio para metadatos y datos (minimo " + minimum + ")");
        }
    }

    /** Reparto por defecto para un tamano de bloque dado. */
    public static FsLayout standard(int blockCount, int blockSize) {
        return new FsLayout(blockCount, blockSize, DEFAULT_FCB_BLOCKS);
    }

    public int superblock() {
        return 0;
    }

    public int bitmapFirst() {
        return 1;
    }

    public int bitmapBlocks() {
        return FreeBlockMap.blocksFor(blockCount, blockSize);
    }

    public int fcbFirst() {
        return bitmapFirst() + bitmapBlocks();
    }

    public int dataFirst() {
        return fcbFirst() + fcbBlocks;
    }

    public int dataBlocks() {
        return blockCount - dataFirst();
    }

    /** Ficheros y directorios que caben: uno por bloque de FCB. */
    public int maxFiles() {
        return fcbBlocks;
    }

    /** Comprueba que un bloque pertenece a la zona de datos. */
    public boolean isDataBlock(int block) {
        return block >= dataFirst() && block < blockCount;
    }

    /** Linea de resumen para el autodiagnostico. */
    public String describe() {
        return blockCount + " bloques de " + blockSize + " bytes (" + blockCount * blockSize
                + " bytes): superbloque en 0, mapa en " + bitmapFirst() + ".."
                + (bitmapFirst() + bitmapBlocks() - 1) + ", FCB en " + fcbFirst() + ".."
                + (fcbFirst() + fcbBlocks - 1) + ", datos en " + dataFirst() + ".."
                + (blockCount - 1);
    }
}
