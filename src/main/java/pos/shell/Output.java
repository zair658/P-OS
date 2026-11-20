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

    /**
     * Fuerza que lo escrito llegue de verdad al destino.
     *
     * Hace falta porque un {@code PrintStream} con autovaciado solo descarga al
     * ver un salto de linea. El aviso de la terminal es justo lo contrario: un
     * texto sin salto que hay que ensenar ya, antes de que llegue la orden
     * siguiente. Sin esto, el aviso se queda en el bufer y aparece pegado a la
     * linea que se escriba despues.
     *
     * Los destinos en memoria y los ficheros no lo necesitan: escribir ya es
     * todo lo que tienen que hacer. Por eso viene vacio y solo lo redefine quien
     * escribe en una pantalla.
     */
    default void flush() {
        // Un buffer o un fichero no guardan nada pendiente.
    }

    /** Un destino en memoria, para tuberias y redirecciones. */
    static Output buffer() {
        return new TextBuffer();
    }
}
