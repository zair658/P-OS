package pos.fs;

import java.util.ArrayList;
import java.util.List;

import pos.base.FreeSpan;

/**
 * Mapa de bloques libres: un bit por bloque.
 *
 * Es el mismo concepto que el mapa de propietarios de la memoria fisica, pero
 * en pequeno y por bloques. Un bit a uno significa libre. La lista de huecos no
 * se guarda: se calcula recorriendo los bits, igual que en memoria, para que no
 * pueda desincronizarse del mapa.
 *
 * El mapa se guarda en la imagen en bloques enteros, de modo que al montar se
 * lee de disco y al desmontar se escribe. Los bits que sobran del ultimo bloque
 * se quedan a cero: no son bloques de nadie.
 */
public final class FreeBlockMap {

    private final int blockCount;
    private final boolean[] free;

    /** Mapa con todos los bloques libres. */
    public FreeBlockMap(int blockCount) {
        if (blockCount <= 0) {
            throw new IllegalArgumentException("el mapa necesita al menos un bloque");
        }
        this.blockCount = blockCount;
        this.free = new boolean[blockCount];
        for (int i = 0; i < blockCount; i++) {
            free[i] = true;
        }
    }

    /** Composicion defensiva, para reconstruir un mapa ya cargado. */
    private FreeBlockMap(boolean[] state) {
        this.blockCount = state.length;
        this.free = state.clone();
    }

    /** Bytes que ocupa el mapa al guardarse en disco. */
    public static int bytesFor(int blockCount) {
        return (blockCount + 7) / 8;
    }

    /** Bloques de imagen que ocupa el mapa con un tamano de bloque dado. */
    public static int blocksFor(int blockCount, int blockSize) {
        return (bytesFor(blockCount) + blockSize - 1) / blockSize;
    }

    // ------------------------------------------------------------------
    // Consulta
    // ------------------------------------------------------------------

    public int blockCount() {
        return blockCount;
    }

    public boolean isFree(int block) {
        check(block);
        return free[block];
    }

    public boolean isUsed(int block) {
        return !isFree(block);
    }

    public int freeCount() {
        int count = 0;
        for (boolean bit : free) {
            if (bit) {
                count++;
            }
        }
        return count;
    }

    public int usedCount() {
        return blockCount - freeCount();
    }

    /** Primer bloque libre, o -1 si no queda ninguno. */
    public int firstFree() {
        for (int block = 0; block < blockCount; block++) {
            if (free[block]) {
                return block;
            }
        }
        return -1;
    }

    /**
     * Huecos libres, como tramos contiguos.
     *
     * Es lo que se le pasa al gestor de huecos cuando el metodo contiguo
     * necesita un tramo entero. Reutilizar el gestor de la fase 1 no es
     * capricho: el problema de colocar un fichero contiguo es exactamente el de
     * colocar un proceso contiguo, con la misma fragmentacion externa.
     */
    public List<FreeSpan> freeSpans() {
        List<FreeSpan> spans = new ArrayList<>();
        int start = -1;
        for (int block = 0; block < blockCount; block++) {
            if (free[block] && start < 0) {
                start = block;
            } else if (!free[block] && start >= 0) {
                spans.add(new FreeSpan(start, block - start));
                start = -1;
            }
        }
        if (start >= 0) {
            spans.add(new FreeSpan(start, blockCount - start));
        }
        return spans;
    }

    public FreeSpan largestSpan() {
        FreeSpan best = null;
        for (FreeSpan span : freeSpans()) {
            if (best == null || span.length() > best.length()) {
                best = span;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Cambios
    // ------------------------------------------------------------------

    public void markUsed(int block) {
        check(block);
        free[block] = false;
    }

    public void markFree(int block) {
        check(block);
        free[block] = true;
    }

    public void markUsed(int start, int length) {
        for (int block = start; block < start + length; block++) {
            markUsed(block);
        }
    }

    public void markFree(int start, int length) {
        for (int block = start; block < start + length; block++) {
            markFree(block);
        }
    }

    private void check(int block) {
        if (block < 0 || block >= blockCount) {
            throw new IndexOutOfBoundsException("bloque " + block + " fuera del mapa (0.."
                    + (blockCount - 1) + ")");
        }
    }

    // ------------------------------------------------------------------
    // Serializacion
    // ------------------------------------------------------------------

    /** Vuelca el mapa a los bytes que se guardan en disco. */
    public byte[] toBytes() {
        byte[] data = new byte[bytesFor(blockCount)];
        for (int block = 0; block < blockCount; block++) {
            if (free[block]) {
                data[block / 8] |= (byte) (1 << (block % 8));
            }
        }
        return data;
    }

    /** Reconstruye el mapa desde los bytes de la imagen. */
    public static FreeBlockMap fromBytes(byte[] data, int blockCount) {
        if (data.length * 8 < blockCount) {
            throw new IllegalArgumentException("el mapa guardado no cubre " + blockCount + " bloques");
        }
        boolean[] state = new boolean[blockCount];
        for (int block = 0; block < blockCount; block++) {
            state[block] = (data[block / 8] & (1 << (block % 8))) != 0;
        }
        return new FreeBlockMap(state);
    }

    /** Dibujo del mapa: {@code .} libre, {@code #} ocupado. */
    public String render() {
        StringBuilder sb = new StringBuilder(blockCount);
        for (boolean bit : free) {
            sb.append(bit ? '.' : '#');
        }
        return sb.toString();
    }
}
