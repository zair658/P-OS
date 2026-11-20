package pos.shell;

import java.util.List;

/**
 * El editor de linea: convierte pulsaciones en una linea, sin mirar la pantalla.
 *
 * Esta clase no lee del teclado ni escribe nada. Recibe {@link Key} y devuelve
 * que ha pasado, que es todo lo que hace falta para probarla sin terminal: los
 * tests le meten flechas y teclas y comprueban el texto resultante.
 *
 * El estado es el de cualquier editor de consola: un buffer, un cursor que puede
 * estar en medio, y un puntero al historial. El historial es la lista viva de la
 * sesion, asi que crece por detras mientras se edita; por eso el puntero usa -1
 * como "estoy escribiendo una linea nueva" en vez de guardar el tamano, que
 * cambiaria bajo los pies.
 */
public final class LineEditor {

    /** Que ha pasado al dar una tecla. */
    public enum Action {
        /** Nada que ejecutar todavia. */
        CONTINUE,
        /** Hay una linea completa, en {@link Result#text()}. */
        SUBMIT,
        /** Ctrl-C: se tira lo escrito. */
        CANCEL,
        /** Ctrl-D en linea vacia: se acaba la entrada. */
        EOF
    }

    /** Resultado de una pulsacion. El texto solo viene con {@code SUBMIT}. */
    public record Result(Action action, String text) {

        static final Result CONTINUE = new Result(Action.CONTINUE, "");
        static final Result CANCEL = new Result(Action.CANCEL, "");
        static final Result EOF = new Result(Action.EOF, "");
    }

    /** Completa una palabra a medias. Devuelve el reemplazo, o null si no hay. */
    @FunctionalInterface
    public interface Completer {
        String complete(String prefix);
    }

    private final StringBuilder buffer = new StringBuilder();
    private final List<String> history;
    private final Completer completer;

    private int cursor;
    /** -1 mientras se escribe una linea nueva; si no, indice en el historial. */
    private int historyIndex = -1;
    /** Lo que se estaba escribiendo antes de subir al historial. */
    private String draft = "";

    public LineEditor(List<String> history) {
        this(history, prefix -> null);
    }

    public LineEditor(List<String> history, Completer completer) {
        this.history = history;
        this.completer = completer;
    }

    /** Texto que hay ahora mismo en la linea. */
    public String text() {
        return buffer.toString();
    }

    /** Posicion del cursor dentro de {@link #text()}. */
    public int cursor() {
        return cursor;
    }

    /** Deja la linea vacia, como tras enviarla o cancelarla. */
    public void reset() {
        buffer.setLength(0);
        cursor = 0;
        historyIndex = -1;
        draft = "";
    }

    /** Aplica una pulsacion. */
    public Result feed(Key key) {
        switch (key.kind()) {
            case CHAR -> {
                buffer.insert(cursor, (char) key.codepoint());
                cursor++;
                return Result.CONTINUE;
            }
            case ENTER -> {
                String line = buffer.toString();
                reset();
                return new Result(Action.SUBMIT, line);
            }
            case CANCEL -> {
                reset();
                return Result.CANCEL;
            }
            case EOF -> {
                if (buffer.length() == 0) {
                    reset();
                    return Result.EOF;
                }
                deleteForward();
                return Result.CONTINUE;
            }
            case BACKSPACE -> {
                if (cursor > 0) {
                    buffer.deleteCharAt(cursor - 1);
                    cursor--;
                }
                return Result.CONTINUE;
            }
            case DELETE -> {
                deleteForward();
                return Result.CONTINUE;
            }
            case LEFT -> {
                cursor = Math.max(0, cursor - 1);
                return Result.CONTINUE;
            }
            case RIGHT -> {
                cursor = Math.min(buffer.length(), cursor + 1);
                return Result.CONTINUE;
            }
            case HOME -> {
                cursor = 0;
                return Result.CONTINUE;
            }
            case END -> {
                cursor = buffer.length();
                return Result.CONTINUE;
            }
            case UP -> {
                older();
                return Result.CONTINUE;
            }
            case DOWN -> {
                newer();
                return Result.CONTINUE;
            }
            case TAB -> {
                complete();
                return Result.CONTINUE;
            }
            case KILL_END -> {
                buffer.setLength(cursor);
                return Result.CONTINUE;
            }
            case KILL_LINE -> {
                reset();
                return Result.CONTINUE;
            }
            default -> {
                return Result.CONTINUE;
            }
        }
    }

    private void deleteForward() {
        if (cursor < buffer.length()) {
            buffer.deleteCharAt(cursor);
        }
    }

    /** Sube a la entrada anterior del historial. */
    private void older() {
        if (history.isEmpty()) {
            return;
        }
        if (historyIndex == -1) {
            // Se guarda lo que habia escrito para poder volver con abajo.
            draft = buffer.toString();
            historyIndex = history.size() - 1;
        } else if (historyIndex > 0) {
            historyIndex--;
        } else {
            // Ya esta en la mas vieja: no hace nada, como en cualquier shell.
            return;
        }
        load(history.get(historyIndex));
    }

    /** Baja a la siguiente, o vuelve al borrador si ya no hay mas. */
    private void newer() {
        if (historyIndex == -1) {
            return;
        }
        if (historyIndex < history.size() - 1) {
            historyIndex++;
            load(history.get(historyIndex));
        } else {
            historyIndex = -1;
            load(draft);
        }
    }

    private void load(String text) {
        buffer.setLength(0);
        buffer.append(text);
        cursor = buffer.length();
    }

    /** Completa la palabra que hay antes del cursor. */
    private void complete() {
        int start = wordStart();
        String prefix = buffer.substring(start, cursor);
        String replacement = completer.complete(prefix);
        if (replacement == null || replacement.isEmpty()) {
            return;
        }
        buffer.replace(start, cursor, replacement);
        cursor = start + replacement.length();
    }

    /**
     * La palabra que hay antes del cursor.
     *
     * La necesita quien pinta: para ensenar que ordenes empiezan igual cuando el
     * tabulador no puede decidir, hay que saber que se estaba escribiendo.
     */
    public String wordPrefix() {
        return buffer.substring(wordStart(), cursor);
    }

    /** Donde empieza la palabra que toca el cursor. */
    private int wordStart() {
        int i = cursor;
        while (i > 0 && !Character.isWhitespace(buffer.charAt(i - 1))) {
            i--;
        }
        return i;
    }
}
