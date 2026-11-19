package pos.fs;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import pos.base.PosFault;

/**
 * Entradas de un directorio.
 *
 * El contenido de un directorio son bytes como los de cualquier fichero, pero
 * con una estructura fija: por cada entrada, el nombre en doce bytes y el
 * identificador del FCB en cuatro. Dieciseis bytes por entrada, sin longitud
 * variable ni separadores. Un formato fijo se parsea sin ambiguedad y se puede
 * explicar en una linea.
 *
 * El directorio no guarda los datos de sus ficheros, solo sus nombres y a que
 * FCB apuntan. Cambiar el nombre de un fichero es cambiar la entrada, no el FCB.
 */
public final class Directory {

    /** Bytes de una entrada: doce del nombre y cuatro del identificador. */
    public static final int ENTRY_SIZE = Fcb.MAX_NAME + 4;

    /** Una entrada: nombre y FCB al que apunta. */
    public record Entry(String name, int fcbId) {

        public Entry {
            if (name == null || name.isEmpty()) {
                throw new IllegalArgumentException("entrada sin nombre");
            }
            if (name.length() > Fcb.MAX_NAME) {
                throw new IllegalArgumentException("nombre de entrada demasiado largo: " + name);
            }
            if (fcbId < 0) {
                throw new IllegalArgumentException("entrada " + name + " sin FCB");
            }
        }
    }

    private Directory() {
        // Utilidades: no se instancia.
    }

    /** Convierte el contenido de un directorio en entradas. */
    public static List<Entry> parse(byte[] content) {
        if (content.length % ENTRY_SIZE != 0) {
            throw new PosFault("el contenido de un directorio mide " + content.length
                    + " bytes, que no es multiplo de " + ENTRY_SIZE);
        }
        List<Entry> entries = new ArrayList<>();
        for (int offset = 0; offset < content.length; offset += ENTRY_SIZE) {
            int length = 0;
            while (length < Fcb.MAX_NAME && content[offset + length] != 0) {
                length++;
            }
            if (length == 0) {
                continue;
            }
            String name = new String(content, offset, length, StandardCharsets.US_ASCII);
            int id = DiskImage.readInt(content, offset + Fcb.MAX_NAME);
            entries.add(new Entry(name, id));
        }
        return entries;
    }

    /** Serializa las entradas al contenido de un directorio. */
    public static byte[] build(List<Entry> entries) {
        byte[] content = new byte[entries.size() * ENTRY_SIZE];
        int offset = 0;
        for (Entry entry : entries) {
            byte[] name = entry.name().getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, content, offset, name.length);
            DiskImage.writeInt(content, offset + Fcb.MAX_NAME, entry.fcbId());
            offset += ENTRY_SIZE;
        }
        return content;
    }
}
