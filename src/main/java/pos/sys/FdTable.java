package pos.sys;

import java.util.LinkedHashMap;
import java.util.Map;

import pos.base.PosFault;

/**
 * Descriptores abiertos de un proceso.
 *
 * Un descriptor es una entrada de esta tabla: ruta, modo, contenido y posicion.
 * El proceso solo ve un numero; la ruta y el contenido no salen del nucleo, que
 * es lo que impide que un proceso se invente un puntero al disco.
 *
 * Los tres primeros descriptores no estan en la tabla: 0, 1 y 2 son la consola y
 * los lleva la terminal. Por eso el primero que se reparte es el 3.
 *
 * La escritura se acumula en memoria y se guarda al cerrar. Tiene una
 * consecuencia que conviene tener presente: si el proceso muere sin cerrar, lo
 * escrito se pierde. Es el mismo trato que da cualquier sistema real a un buffer
 * sin sincronizar, y aqui se ve en la traza sin necesidad de provocar un apagon.
 */
final class FdTable {

    /** Primer descriptor que se reparte; 0, 1 y 2 son la consola. */
    static final int FIRST_FD = 3;

    /** Una entrada abierta. */
    static final class Entry {

        private final int fd;
        private final String path;
        private final Syscall.Mode mode;
        private final StringBuilder buffer;
        private int offset;

        Entry(int fd, String path, Syscall.Mode mode, String initial) {
            this.fd = fd;
            this.path = path;
            this.mode = mode;
            this.buffer = new StringBuilder(initial == null ? "" : initial);
            this.offset = 0;
        }

        int fd() {
            return fd;
        }

        String path() {
            return path;
        }

        Syscall.Mode mode() {
            return mode;
        }

        String content() {
            return buffer.toString();
        }

        boolean changed() {
            return mode != Syscall.Mode.READ;
        }
    }

    private final Map<Integer, Entry> entries = new LinkedHashMap<>();
    private final int maxFds;

    FdTable(int maxFds) {
        if (maxFds < FIRST_FD) {
            throw new IllegalArgumentException("la tabla necesita espacio para descriptores");
        }
        this.maxFds = maxFds;
    }

    /** Abre una entrada y devuelve su descriptor. */
    int open(String path, Syscall.Mode mode, String initial) {
        if (entries.size() >= maxFds) {
            throw new PosFault("no quedan descriptores libres: la tabla admite " + maxFds);
        }
        // Se reparte el numero mas bajo que este libre, no el siguiente de un
        // contador. Con un contador, un proceso que abre y cierra en un bucle
        // agota la tabla sin tener nada abierto, y el fallo aparece lejos de su
        // causa.
        int fd = FIRST_FD;
        while (entries.containsKey(fd)) {
            fd++;
        }
        entries.put(fd, new Entry(fd, path, mode, initial));
        return fd;
    }

    Entry get(int fd) {
        Entry entry = entries.get(fd);
        if (entry == null) {
            throw new PosFault("el descriptor " + fd + " no esta abierto");
        }
        return entry;
    }

    /** Lee hasta {@code count} caracteres desde la posicion actual. */
    String read(int fd, int count) {
        if (count <= 0) {
            throw new PosFault("hay que leer al menos un caracter");
        }
        Entry entry = get(fd);
        String content = entry.content();
        if (entry.offset >= content.length()) {
            return "";
        }
        int end = Math.min(content.length(), entry.offset + count);
        String chunk = content.substring(entry.offset, end);
        entry.offset = end;
        return chunk;
    }

    /** Anade texto al buffer de la entrada. */
    void write(int fd, String text) {
        Entry entry = get(fd);
        if (entry.mode == Syscall.Mode.READ) {
            throw new PosFault("el descriptor " + fd + " esta abierto en lectura");
        }
        entry.buffer.append(text);
    }

    /** Cierra el descriptor y devuelve la entrada, para que el nucleo la guarde. */
    Entry close(int fd) {
        Entry entry = entries.remove(fd);
        if (entry == null) {
            throw new PosFault("el descriptor " + fd + " no esta abierto");
        }
        return entry;
    }

    int openCount() {
        return entries.size();
    }

    /** Cierra todo sin guardar: lo que hace el nucleo cuando el proceso muere. */
    int abandon() {
        int pending = 0;
        for (Entry entry : entries.values()) {
            if (entry.changed()) {
                pending++;
            }
        }
        entries.clear();
        return pending;
    }
}
