package pos.fs;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import pos.alloc.SlotChoice;
import pos.alloc.SlotManager;
import pos.base.PosFault;

/**
 * Metodo de asignacion de bloques: la politica que decide donde va el contenido
 * de un fichero.
 *
 * Los tres metodos hacen lo mismo desde fuera (crear, leer, escribir, borrar) y
 * se pueden cambiar en caliente. Lo que cambia es el reparto y, sobre todo, el
 * coste de acceso: cuantas lecturas de bloque cuesta llegar al bloque logico
 * numero cien. El contiguo lo calcula, el enlazado recorre la cadena y el
 * indexado consulta una tabla.
 *
 * La interfaz es la misma para los tres porque el resto del sistema no debe
 * saber cual esta puesto. Es la misma regla que en memoria con los esquemas.
 */
public sealed interface AllocationMethod
        permits AllocationMethod.Contiguous, AllocationMethod.Linked, AllocationMethod.Indexed {

    /** Nombre corto para tablas y trazas. */
    String name();

    /** Codigo que se guarda en el superbloque. */
    int code();

    /** Bytes utiles de un bloque de datos con este metodo. */
    int capacityPerBlock(int blockSize);

    /**
     * Bloques de datos necesarios para un contenido de {@code size} bytes.
     *
     * El enlazado necesita mas porque cada bloque gasta cuatro bytes en el
     * puntero al siguiente. Esa diferencia es real y se ve en la tabla.
     */
    default int dataBlocksFor(int size, int blockSize) {
        if (size < 0) {
            throw new IllegalArgumentException("el tamano no puede ser negativo: " + size);
        }
        if (size == 0) {
            return 0;
        }
        int capacity = capacityPerBlock(blockSize);
        return (size + capacity - 1) / capacity;
    }

    /** Bloques que ocupa el fichero contando los metadatos del metodo. */
    default int totalBlocksFor(int size, int blockSize) {
        int data = dataBlocksFor(size, blockSize);
        return data + overheadBlocks(data, blockSize);
    }

    /** Bloques de metadatos que anade el metodo para esos datos. */
    int overheadBlocks(int dataBlocks, int blockSize);

    /**
     * Reserva los bloques de un fichero y deja escritos los metadatos del
     * metodo (punteros de la cadena o tabla de indices).
     */
    Allocation allocate(DiskImage image, FreeBlockMap map, SlotManager slots, int dataBlocks);

    /** Devuelve al mapa todos los bloques de la asignacion. */
    void release(DiskImage image, FreeBlockMap map, Allocation allocation);

    /** Traduce un bloque logico del fichero a un bloque fisico de la imagen. */
    int physicalBlock(DiskImage image, Allocation allocation, int logicalBlock);

    /**
     * Bloques fisicos que la asignacion ocupa y no son contenido del fichero.
     *
     * Solo el metodo indexado gasta metadatos propios: su bloque de indices.
     * El verificador necesita conocerlos para no confundirlos con bloques
     * ocupados sin dueno.
     */
    default List<Integer> metadataBlocks(Allocation allocation) {
        return List.of();
    }

    /** Bloques fisicos totales de la asignacion, datos y metadatos. */
    default int totalBlocks(Allocation allocation) {
        return allocation.dataBlocks() + metadataBlocks(allocation).size();
    }

    /** Escribe un trozo de contenido en un bloque logico. */
    void storeBlock(DiskImage image, Allocation allocation, int logicalBlock, byte[] chunk);

    /** Lee un trozo de contenido de un bloque logico, sin el relleno. */
    byte[] loadBlock(DiskImage image, Allocation allocation, int logicalBlock, int capacity);

    /**
     * Intenta crecer sin mover el contenido.
     *
     * @return la asignacion ampliada, o {@code null} si este metodo no puede
     *         crecer en el sitio y hay que reasignar todo
     */
    Allocation extend(DiskImage image, FreeBlockMap map, SlotManager slots, Allocation allocation,
            int extraBlocks);

    /** Lineas de diagnostico de la asignacion, para la tabla de la vista. */
    List<String> describe(DiskImage image, Allocation allocation);

    /** Metodo a partir del codigo guardado en el superbloque. */
    static AllocationMethod of(int code) {
        return switch (code) {
            case 0 -> new Contiguous();
            case 1 -> new Linked();
            case 2 -> new Indexed();
            default -> throw new PosFault("metodo de asignacion desconocido en el superbloque: " + code);
        };
    }

    /** Relleno de un bloque hasta el tamano completo, con ceros. */
    private static byte[] pad(byte[] chunk, int blockSize) {
        if (chunk.length > blockSize) {
            throw new PosFault("el trozo mide " + chunk.length + " bytes y el bloque " + blockSize);
        }
        if (chunk.length == blockSize) {
            return chunk.clone();
        }
        byte[] data = new byte[blockSize];
        System.arraycopy(chunk, 0, data, 0, chunk.length);
        return data;
    }

    /**
     * Contiguo: primer bloque y longitud.
     *
     * Acceso directo con una suma. El precio es la fragmentacion externa: al
     * crecer necesita el bloque de al lado y, si esta ocupado, hay que mover el
     * fichero entero. Es exactamente el mismo problema que un proceso contiguo
     * en memoria, y por eso la colocacion se le pide al mismo gestor de huecos.
     */
    record Contiguous() implements AllocationMethod {

        @Override
        public String name() {
            return "contiguo";
        }

        @Override
        public int code() {
            return 0;
        }

        @Override
        public int capacityPerBlock(int blockSize) {
            return blockSize;
        }

        @Override
        public int overheadBlocks(int dataBlocks, int blockSize) {
            return 0;
        }

        @Override
        public Allocation allocate(DiskImage image, FreeBlockMap map, SlotManager slots, int dataBlocks) {
            if (dataBlocks == 0) {
                return new Allocation.Empty();
            }
            SlotChoice choice = slots.choose(map.freeSpans(), dataBlocks);
            if (!choice.found()) {
                throw new PosFault("no hay un tramo contiguo de " + dataBlocks + " bloques ("
                        + choice.reason() + "; el hueco mayor mide "
                        + largest(map) + " bloques)");
            }
            int first = choice.chosen().start();
            map.markUsed(first, dataBlocks);
            return new Allocation.Contiguous(first, dataBlocks);
        }

        private static int largest(FreeBlockMap map) {
            var span = map.largestSpan();
            return span == null ? 0 : span.length();
        }

        @Override
        public void release(DiskImage image, FreeBlockMap map, Allocation allocation) {
            if (allocation instanceof Allocation.Contiguous contiguous) {
                map.markFree(contiguous.first(), contiguous.length());
            }
        }

        @Override
        public int physicalBlock(DiskImage image, Allocation allocation, int logicalBlock) {
            Allocation.Contiguous contiguous = (Allocation.Contiguous) allocation;
            if (logicalBlock < 0 || logicalBlock >= contiguous.length()) {
                throw new PosFault("bloque logico " + logicalBlock + " fuera del fichero (0.."
                        + (contiguous.length() - 1) + ")");
            }
            return contiguous.first() + logicalBlock;
        }

        @Override
        public void storeBlock(DiskImage image, Allocation allocation, int logicalBlock, byte[] chunk) {
            image.write(physicalBlock(image, allocation, logicalBlock),
                    pad(chunk, image.blockSize()));
        }

        @Override
        public byte[] loadBlock(DiskImage image, Allocation allocation, int logicalBlock, int capacity) {
            byte[] data = image.read(physicalBlock(image, allocation, logicalBlock));
            return java.util.Arrays.copyOf(data, capacity);
        }

        @Override
        public Allocation extend(DiskImage image, FreeBlockMap map, SlotManager slots,
                Allocation allocation, int extraBlocks) {
            if (!(allocation instanceof Allocation.Contiguous contiguous)) {
                return null;
            }
            int start = contiguous.first() + contiguous.length();
            if (start + extraBlocks > map.blockCount()) {
                return null;
            }
            for (int block = start; block < start + extraBlocks; block++) {
                if (map.isUsed(block)) {
                    return null;
                }
            }
            map.markUsed(start, extraBlocks);
            return new Allocation.Contiguous(contiguous.first(), contiguous.length() + extraBlocks);
        }

        @Override
        public List<String> describe(DiskImage image, Allocation allocation) {
            Allocation.Contiguous contiguous = (Allocation.Contiguous) allocation;
            List<String> lines = new ArrayList<>();
            lines.add(String.format("bloques %d..%d (%d contiguos)",
                    contiguous.first(), contiguous.first() + contiguous.length() - 1,
                    contiguous.length()));
            lines.add("acceso directo: bloque fisico = " + contiguous.first() + " + logico, sin lecturas");
            return lines;
        }
    }

    /**
     * Enlazada: cada bloque apunta al siguiente.
     *
     * No hay fragmentacion externa porque cualquier bloque libre sirve. El
     * precio se paga al leer: para llegar al bloque cien hay que pasar por los
     * noventa y nueve anteriores. Ademas cada bloque pierde cuatro bytes en el
     * puntero.
     */
    record Linked() implements AllocationMethod {

        /** Bytes del puntero al siguiente bloque. */
        static final int POINTER = 4;

        @Override
        public String name() {
            return "enlazada";
        }

        @Override
        public int code() {
            return 1;
        }

        @Override
        public int capacityPerBlock(int blockSize) {
            return blockSize - POINTER;
        }

        @Override
        public int overheadBlocks(int dataBlocks, int blockSize) {
            return 0;
        }

        @Override
        public Allocation allocate(DiskImage image, FreeBlockMap map, SlotManager slots, int dataBlocks) {
            if (dataBlocks == 0) {
                return new Allocation.Empty();
            }
            List<Integer> blocks = takeFreeBlocks(map, dataBlocks);
            if (blocks.size() < dataBlocks) {
                // Deshacer lo tomado: una asignacion que falla no puede dejar
                // bloques marcados como ocupados.
                for (int block : blocks) {
                    map.markFree(block);
                }
                throw new PosFault("no quedan " + dataBlocks + " bloques libres para la cadena (hay "
                        + blocks.size() + ")");
            }
            for (int i = 0; i < blocks.size(); i++) {
                int next = i + 1 < blocks.size() ? blocks.get(i + 1) : -1;
                image.write(blocks.get(i), blockWithPointer(image.blockSize(), next));
            }
            return new Allocation.Chained(blocks.get(0), dataBlocks);
        }

        /** Toma bloques libres sueltos, empezando por el primero libre. */
        static List<Integer> takeFreeBlocks(FreeBlockMap map, int count) {
            List<Integer> blocks = new ArrayList<>();
            for (int block = 0; block < map.blockCount() && blocks.size() < count; block++) {
                if (map.isFree(block)) {
                    map.markUsed(block);
                    blocks.add(block);
                }
            }
            return blocks;
        }

        private static byte[] blockWithPointer(int blockSize, int next) {
            byte[] data = new byte[blockSize];
            DiskImage.writeInt(data, blockSize - POINTER, next);
            return data;
        }

        private static int pointerOf(byte[] data) {
            return DiskImage.readInt(data, data.length - POINTER);
        }

        @Override
        public void release(DiskImage image, FreeBlockMap map, Allocation allocation) {
            if (!(allocation instanceof Allocation.Chained chained)) {
                return;
            }
            Set<Integer> visited = new HashSet<>();
            int block = chained.first();
            for (int step = 0; step < chained.dataBlocks(); step++) {
                if (block < 0 || block >= map.blockCount()) {
                    throw new PosFault("la cadena sale de la imagen en el bloque " + block);
                }
                if (!visited.add(block)) {
                    throw new PosFault("la cadena se muerde la cola en el bloque " + block);
                }
                int next = pointerOf(image.read(block));
                map.markFree(block);
                block = next;
            }
        }

        @Override
        public int physicalBlock(DiskImage image, Allocation allocation, int logicalBlock) {
            Allocation.Chained chained = (Allocation.Chained) allocation;
            if (logicalBlock < 0 || logicalBlock >= chained.dataBlocks()) {
                throw new PosFault("bloque logico " + logicalBlock + " fuera del fichero (0.."
                        + (chained.dataBlocks() - 1) + ")");
            }
            int block = chained.first();
            for (int step = 0; step < logicalBlock; step++) {
                if (block < 0 || block >= image.blockCount()) {
                    throw new PosFault("la cadena sale de la imagen en el bloque " + block);
                }
                block = pointerOf(image.read(block));
            }
            if (block < 0 || block >= image.blockCount()) {
                throw new PosFault("la cadena sale de la imagen en el bloque " + block);
            }
            return block;
        }

        @Override
        public void storeBlock(DiskImage image, Allocation allocation, int logicalBlock, byte[] chunk) {
            int block = physicalBlock(image, allocation, logicalBlock);
            byte[] data = image.read(block);
            byte[] payload = pad(chunk, image.blockSize());
            System.arraycopy(payload, 0, data, 0, image.blockSize() - POINTER);
            image.write(block, data);
        }

        @Override
        public byte[] loadBlock(DiskImage image, Allocation allocation, int logicalBlock, int capacity) {
            byte[] data = image.read(physicalBlock(image, allocation, logicalBlock));
            if (capacity > data.length - POINTER) {
                throw new PosFault("capacidad " + capacity + " mayor que el bloque util "
                        + (data.length - POINTER));
            }
            return java.util.Arrays.copyOf(data, capacity);
        }

        @Override
        public Allocation extend(DiskImage image, FreeBlockMap map, SlotManager slots,
                Allocation allocation, int extraBlocks) {
            if (!(allocation instanceof Allocation.Chained chained)) {
                return null;
            }
            List<Integer> extra = new ArrayList<>();
            for (int block = 0; block < map.blockCount() && extra.size() < extraBlocks; block++) {
                if (map.isFree(block)) {
                    map.markUsed(block);
                    extra.add(block);
                }
            }
            if (extra.size() < extraBlocks) {
                for (int block : extra) {
                    map.markFree(block);
                }
                return null;
            }
            // Recorrer hasta el ultimo eslabon original.
            int block = chained.first();
            for (int step = 0; step < chained.dataBlocks() - 1; step++) {
                block = pointerOf(image.read(block));
            }
            // El ultimo eslabon conserva sus datos: solo cambia el puntero.
            byte[] tail = image.read(block);
            DiskImage.writeInt(tail, tail.length - POINTER, extra.get(0));
            image.write(block, tail);
            for (int i = 0; i < extra.size(); i++) {
                int next = i + 1 < extra.size() ? extra.get(i + 1) : -1;
                image.write(extra.get(i), blockWithPointer(image.blockSize(), next));
            }
            return new Allocation.Chained(chained.first(), chained.dataBlocks() + extraBlocks);
        }

        @Override
        public List<String> describe(DiskImage image, Allocation allocation) {
            Allocation.Chained chained = (Allocation.Chained) allocation;
            List<String> lines = new ArrayList<>();
            StringBuilder chain = new StringBuilder();
            int block = chained.first();
            Set<Integer> visited = new HashSet<>();
            while (block >= 0 && visited.add(block)) {
                chain.append(block);
                int next = pointerOf(image.read(block));
                if (next >= 0) {
                    chain.append(" -> ");
                }
                block = next;
            }
            lines.add("cadena " + chain);
            lines.add("acceso al bloque logico k: k+1 lecturas; util " + capacityPerBlock(image.blockSize())
                    + " de " + image.blockSize() + " bytes por bloque");
            return lines;
        }
    }

    /**
     * Indexada: un bloque de indices por fichero.
     *
     * Acceso directo en dos lecturas, sin fragmentacion externa. El precio es un
     * bloque entero de indices por fichero, que en un fichero de un solo bloque
     * es el doble de espacio. Es el metodo por defecto.
     */
    record Indexed() implements AllocationMethod {

        @Override
        public String name() {
            return "indexada";
        }

        @Override
        public int code() {
            return 2;
        }

        @Override
        public int capacityPerBlock(int blockSize) {
            return blockSize;
        }

        @Override
        public int overheadBlocks(int dataBlocks, int blockSize) {
            return dataBlocks == 0 ? 0 : 1;
        }

        /** Punteros que caben en un bloque de indices. */
        public static int pointersPerIndex(int blockSize) {
            return blockSize / 4;
        }

        @Override
        public Allocation allocate(DiskImage image, FreeBlockMap map, SlotManager slots, int dataBlocks) {
            if (dataBlocks == 0) {
                return new Allocation.Empty();
            }
            int limit = pointersPerIndex(image.blockSize());
            if (dataBlocks > limit) {
                throw new PosFault("el indice de un nivel solo apunta " + limit
                        + " bloques y el fichero necesita " + dataBlocks
                        + "; el metodo indexado de esta version no tiene doble nivel");
            }
            int indexBlock = map.firstFree();
            if (indexBlock < 0) {
                throw new PosFault("no queda ningun bloque libre para el indice");
            }
            map.markUsed(indexBlock);
            List<Integer> data = Linked.takeFreeBlocks(map, dataBlocks);
            if (data.size() < dataBlocks) {
                for (int block : data) {
                    map.markFree(block);
                }
                map.markFree(indexBlock);
                throw new PosFault("no quedan " + dataBlocks + " bloques libres para el contenido del fichero");
            }
            byte[] index = new byte[image.blockSize()];
            for (int i = 0; i < limit; i++) {
                DiskImage.writeInt(index, i * 4, i < data.size() ? data.get(i) : -1);
            }
            image.write(indexBlock, index);
            return new Allocation.Indexed(indexBlock, dataBlocks);
        }

        @Override
        public void release(DiskImage image, FreeBlockMap map, Allocation allocation) {
            if (!(allocation instanceof Allocation.Indexed indexed)) {
                return;
            }
            byte[] index = image.read(indexed.indexBlock());
            for (int i = 0; i < indexed.dataBlocks(); i++) {
                int block = DiskImage.readInt(index, i * 4);
                if (block >= 0 && block < map.blockCount()) {
                    map.markFree(block);
                }
            }
            map.markFree(indexed.indexBlock());
        }

        @Override
        public int physicalBlock(DiskImage image, Allocation allocation, int logicalBlock) {
            Allocation.Indexed indexed = (Allocation.Indexed) allocation;
            if (logicalBlock < 0 || logicalBlock >= indexed.dataBlocks()) {
                throw new PosFault("bloque logico " + logicalBlock + " fuera del fichero (0.."
                        + (indexed.dataBlocks() - 1) + ")");
            }
            byte[] index = image.read(indexed.indexBlock());
            return DiskImage.readInt(index, logicalBlock * 4);
        }

        @Override
        public void storeBlock(DiskImage image, Allocation allocation, int logicalBlock, byte[] chunk) {
            image.write(physicalBlock(image, allocation, logicalBlock),
                    pad(chunk, image.blockSize()));
        }

        @Override
        public byte[] loadBlock(DiskImage image, Allocation allocation, int logicalBlock, int capacity) {
            byte[] data = image.read(physicalBlock(image, allocation, logicalBlock));
            return java.util.Arrays.copyOf(data, capacity);
        }

        @Override
        public Allocation extend(DiskImage image, FreeBlockMap map, SlotManager slots,
                Allocation allocation, int extraBlocks) {
            if (!(allocation instanceof Allocation.Indexed indexed)) {
                return null;
            }
            int limit = pointersPerIndex(image.blockSize());
            if (indexed.dataBlocks() + extraBlocks > limit) {
                return null;
            }
            List<Integer> extra = Linked.takeFreeBlocks(map, extraBlocks);
            if (extra.size() < extraBlocks) {
                for (int block : extra) {
                    map.markFree(block);
                }
                return null;
            }
            byte[] index = image.read(indexed.indexBlock());
            for (int i = 0; i < extra.size(); i++) {
                DiskImage.writeInt(index, (indexed.dataBlocks() + i) * 4, extra.get(i));
            }
            image.write(indexed.indexBlock(), index);
            return new Allocation.Indexed(indexed.indexBlock(), indexed.dataBlocks() + extraBlocks);
        }

        @Override
        public List<Integer> metadataBlocks(Allocation allocation) {
            return allocation instanceof Allocation.Indexed indexed
                    ? List.of(indexed.indexBlock())
                    : List.of();
        }

        @Override
        public List<String> describe(DiskImage image, Allocation allocation) {
            Allocation.Indexed indexed = (Allocation.Indexed) allocation;
            byte[] index = image.read(indexed.indexBlock());
            List<String> lines = new ArrayList<>();
            StringBuilder pointers = new StringBuilder();
            for (int i = 0; i < indexed.dataBlocks(); i++) {
                if (i > 0) {
                    pointers.append(", ");
                }
                pointers.append(DiskImage.readInt(index, i * 4));
            }
            lines.add("indice en el bloque " + indexed.indexBlock() + " -> [" + pointers + "]");
            lines.add("acceso al bloque logico k: 2 lecturas (indice y dato), "
                    + (image.blockSize() / 4) + " punteros por bloque de indices");
            return lines;
        }
    }
}
