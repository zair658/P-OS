package pos.shell;

/**
 * Una pulsacion de tecla, ya decodificada.
 *
 * La diferencia con una linea de texto es justo esta: una tecla no trae salto de
 * linea. Separa dos mundos que estaban mezclados: el editor de linea trabaja con
 * teclas y devuelve lineas, y la terminal solo ve lineas.
 *
 * El secuestro de las secuencias de escape (las flechas mandan tres bytes:
 * ESC, corchete y una letra) pasa en {@link TtyKeys}, que es el unico que mira
 * bytes. Aqui llega ya decidido si lo que se pulso fue "arriba" o la letra A.
 */
public record Key(Kind kind, int codepoint) {

    /** Que se pulso. */
    public enum Kind {
        /** Un caracter imprimible, en {@code codepoint}. */
        CHAR,
        ENTER,
        BACKSPACE,
        DELETE,
        LEFT,
        RIGHT,
        HOME,
        END,
        UP,
        DOWN,
        TAB,
        /** Ctrl-C: se abandona lo escrito. */
        CANCEL,
        /** Ctrl-D: fin de la entrada, si la linea esta vacia. */
        EOF,
        /** Ctrl-K: se borra desde el cursor hasta el final. */
        KILL_END,
        /** Ctrl-U: se borra la linea entera. */
        KILL_LINE,
        /** Ctrl-Z: se detiene el trabajo en primer plano. */
        PARAR,
        /** Una tecla que aqui no significa nada y se descarta. */
        IGNORED
    }

    public static Key of(Kind kind) {
        return new Key(kind, 0);
    }

    public static Key ch(int codepoint) {
        return new Key(Kind.CHAR, codepoint);
    }

    public boolean isChar() {
        return kind == Kind.CHAR;
    }
}
