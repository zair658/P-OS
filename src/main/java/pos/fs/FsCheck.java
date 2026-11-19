package pos.fs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import pos.base.PosFault;

/**
 * Verificacion de integridad de la imagen.
 *
 * Es la comprobacion que se ejecuta al montar y que se puede pedir a mano con
 * {@code fsck}. No repara nada: informa. Reparar automaticamente es la clase de
 * decision que hay que tomar con el usuario delante, no escondida en un metodo
 * que se ejecuta al arrancar.
 *
 * Lo que se comprueba, en orden de gravedad:
 * <ol>
 *   <li>El superbloque tiene sentido y coincide con la geometria real.</li>
 *   <li>El contador de libres del superbloque cuadra con el mapa de bits.</li>
 *   <li>Los bloques de metadatos estan marcados como ocupados.</li>
 *   <li>Cada fichero ocupa los bloques que su tamano pide, ni uno mas.</li>
 *   <li>Ningun bloque esta en dos ficheros a la vez.</li>
 *   <li>Ni un bloque ocupado queda sin dueno, ni un dueno sin bloque.</li>
 *   <li>Las entradas de directorio apuntan a FCB existentes y sin duplicados.</li>
 * </ol>
 */
public final class FsCheck {

    private final List<String> problems;

    private FsCheck(List<String> problems) {
        this.problems = List.copyOf(problems);
    }

    /** Verdad: la imagen es coherente. */
    public boolean ok() {
        return problems.isEmpty();
    }

    public List<String> problems() {
        return problems;
    }

    public String summary() {
        if (problems.isEmpty()) {
            return "imagen coherente";
        }
        return problems.size() + (problems.size() == 1 ? " problema" : " problemas");
    }

    /** Ejecuta todas las comprobaciones y devuelve el resultado. */
    static FsCheck run(DiskImage image, FsLayout layout, Superblock superblock, FreeBlockMap free,
            Map<Integer, Fcb> fcbs, AllocationMethod method, List<String> previous) {
        List<String> problems = new ArrayList<>(previous);

        if (superblock == null) {
            problems.add("no hay superbloque en la imagen");
            return new FsCheck(problems);
        }
        problems.addAll(superblock.problems(layout));

        if (superblock.freeBlocks() != free.freeCount()) {
            problems.add("el superbloque dice " + superblock.freeBlocks() + " bloques libres y el mapa cuenta "
                    + free.freeCount());
        }

        for (int block = 0; block < layout.dataFirst(); block++) {
            if (free.isFree(block)) {
                problems.add("el bloque de metadatos " + block + " esta marcado como libre");
            }
        }

        // Dueno de cada bloque de datos: -1 libre, -2 metadatos, otro el FCB.
        int[] owner = new int[layout.blockCount()];
        java.util.Arrays.fill(owner, -1);
        for (int block = 0; block < layout.dataFirst(); block++) {
            owner[block] = -2;
        }

        Map<Integer, Integer> entriesPerFcb = new HashMap<>();

        for (Fcb fcb : fcbs.values()) {
            int expected = method.dataBlocksFor(fcb.size(), image.blockSize());
            int actual = fcb.allocation().dataBlocks();
            if (expected != actual) {
                problems.add("'" + fcb.name() + "' mide " + fcb.size() + " bytes ("
                        + expected + " bloques) y tiene " + actual + " bloques asignados");
            }
            if (fcb.size() > 0 && actual == 0) {
                problems.add("'" + fcb.name() + "' tiene " + fcb.size() + " bytes pero ningun bloque");
            }
            for (int logical = 0; logical < actual; logical++) {
                int block;
                try {
                    block = method.physicalBlock(image, fcb.allocation(), logical);
                } catch (PosFault fault) {
                    problems.add("'" + fcb.name() + "': " + fault.getMessage());
                    break;
                }
                if (!layout.isDataBlock(block)) {
                    problems.add("'" + fcb.name() + "' apunta al bloque " + block
                            + ", que no es de datos (" + layout.dataFirst() + ".."
                            + (layout.blockCount() - 1) + ")");
                    continue;
                }
                if (owner[block] >= 0 && owner[block] != fcb.id()) {
                    problems.add("el bloque " + block + " esta en '" + fcb.name() + "' y en el FCB "
                            + owner[block] + " a la vez");
                } else if (owner[block] == -1) {
                    owner[block] = fcb.id();
                }
            }
            // Los metadatos del metodo (el bloque de indices) tambien tienen dueno.
            for (int block : method.metadataBlocks(fcb.allocation())) {
                if (!layout.isDataBlock(block)) {
                    problems.add("'" + fcb.name() + "' tiene metadatos en el bloque " + block
                            + ", que no es de datos");
                    continue;
                }
                if (owner[block] >= 0 && owner[block] != fcb.id()) {
                    problems.add("el bloque de metadatos " + block + " esta en '" + fcb.name()
                            + "' y en el FCB " + owner[block] + " a la vez");
                } else if (owner[block] == -1) {
                    owner[block] = fcb.id();
                }
            }
        }

        // Bloques ocupados sin dueno y bloques libres con dueno.
        for (int block = layout.dataFirst(); block < layout.blockCount(); block++) {
            boolean used = free.isUsed(block);
            boolean referenced = owner[block] >= 0;
            if (used && !referenced) {
                problems.add("el bloque " + block + " esta ocupado en el mapa pero ningun FCB lo reclama");
            }
            if (!used && referenced) {
                problems.add("el bloque " + block + " lo reclama el FCB " + owner[block]
                        + " pero el mapa lo da por libre");
            }
        }

        // Directorios: entradas validas, sin repetir nombre, y todo FCB con su entrada.
        for (Fcb fcb : fcbs.values()) {
            if (!fcb.directory()) {
                continue;
            }
            byte[] content = readContent(image, method, fcb);
            if (content.length % Directory.ENTRY_SIZE != 0) {
                problems.add("el directorio '" + fcb.name() + "' mide " + content.length
                        + " bytes, que no es multiplo de " + Directory.ENTRY_SIZE);
                continue;
            }
            List<Directory.Entry> entries;
            try {
                entries = Directory.parse(content);
            } catch (PosFault fault) {
                problems.add("directorio '" + fcb.name() + "': " + fault.getMessage());
                continue;
            }
            Map<String, Integer> names = new HashMap<>();
            for (Directory.Entry entry : entries) {
                if (names.put(entry.name(), entry.fcbId()) != null) {
                    problems.add("el directorio '" + fcb.name() + "' tiene dos veces '"
                            + entry.name() + "'");
                }
                if (!fcbs.containsKey(entry.fcbId())) {
                    problems.add("el directorio '" + fcb.name() + "' tiene la entrada '" + entry.name()
                            + "' apuntando al FCB " + entry.fcbId() + ", que no existe");
                } else {
                    entriesPerFcb.merge(entry.fcbId(), 1, Integer::sum);
                }
            }
        }

        for (Fcb fcb : fcbs.values()) {
            if (fcb.id() == FileSystem.ROOT_ID) {
                continue;
            }
            int count = entriesPerFcb.getOrDefault(fcb.id(), 0);
            if (count == 0) {
                problems.add("el FCB '" + fcb.name() + "' (" + fcb.id() + ") no esta en ningun directorio");
            } else if (count > 1) {
                problems.add("el FCB '" + fcb.name() + "' (" + fcb.id() + ") aparece en " + count
                        + " directorios");
            }
        }

        return new FsCheck(problems);
    }

    /** Lectura del contenido sin pasar por el sistema de ficheros. */
    private static byte[] readContent(DiskImage image, AllocationMethod method, Fcb fcb) {
        if (fcb.size() == 0) {
            return new byte[0];
        }
        byte[] content = new byte[fcb.size()];
        int capacity = method.capacityPerBlock(image.blockSize());
        int offset = 0;
        for (int logical = 0; logical < fcb.allocation().dataBlocks() && offset < content.length; logical++) {
            byte[] chunk = method.loadBlock(image, fcb.allocation(), logical, capacity);
            int length = Math.min(chunk.length, content.length - offset);
            System.arraycopy(chunk, 0, content, offset, length);
            offset += length;
        }
        return content;
    }
}
