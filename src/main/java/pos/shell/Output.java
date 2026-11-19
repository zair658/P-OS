package pos.shell;

/**
 * Donde escribe una orden.
 *
 * Es una interfaz y no un {@code StringBuilder} porque hay tres destinos
 * distintos: la pantalla de la terminal, un buffer intermedio de una tuberia y
 * el fichero de una redireccion. Las ordenes escriben siempre aqui y no saben
 * cual de los tres les ha tocado.
 */
@FunctionalInterface
public interface Output {

    /** Escribe el texto tal cual, sin anadir salto de linea. */
    void print(String text);

    /** Escribe el texto y un salto de linea. */
    default void println(String text) {
        print(text + "\n");
    }

    /** Escribe solo un salto de linea. */
    default void newLine() {
        print("\n");
    }

    /** Un destino en memoria, para tuberias y redirecciones. */
    static Output buffer() {
        return new TextBuffer();
    }
}
