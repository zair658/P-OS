package pos.shell;

/**
 * De donde le llegan las lineas a la terminal.
 *
 * Hay dos fuentes y las dos son legitimas:
 *
 *   - el guion de {@link Console}, que entrega lineas ya escritas con el tic en
 *     que "llegan". Es lo que usan las pruebas y las demostraciones, y es lo que
 *     hace la sesion reproducible: la misma entrada da siempre la misma traza.
 *   - el teclado de verdad, cuando alguien escribe en un terminal. Esa entrada no
 *     se puede repetir, y por eso no la usan las pruebas.
 *
 * La terminal no necesita saber cual de las dos tiene delante. Todo lo que pide
 * cabe aqui: si queda algo, cuando llega y cual es la linea.
 */
public interface Keys {

    /** Una linea que llega al teclado en un tic concreto. */
    record Keystroke(long tick, String text) {

        public Keystroke {
            if (tick < 0) {
                throw new IllegalArgumentException("un tic no puede ser negativo: " + tick);
            }
            if (text == null) {
                throw new IllegalArgumentException("la linea no puede ser nula");
            }
        }
    }

    /**
     * Empieza a leer, si esta fuente necesita un hilo para ello.
     *
     * El guion de {@link Console} ya tiene todas sus lineas y no hace nada; el
     * teclado, en cambio, arranca aqui su hilo de lectura.
     */
    default void start() {
        // Sin hilo propio no hay nada que arrancar.
    }

    /** Queda alguna linea por leer, o puede llegar. */
    boolean hasNext();

    /** Tic en el que llegara la proxima linea, o -1 si no queda ninguna. */
    long nextTick();

    /** La siguiente linea. Solo se llama si {@link #hasNext()} dijo que si. */
    Keystroke next();

    /** Cuantas lineas quedan sin leer. */
    int pending();

    /**
     * Si esta fuente pinta su propio aviso.
     *
     * Con un guion o con lineas leidas del flujo, el aviso lo pinta la terminal:
     * la linea llega entera y el aviso va delante, como una cabecera. Con un
     * editor de linea no puede ser asi, porque el aviso tiene que estar pegado al
     * texto que se esta escribiendo y moverse con el. Cuando esto devuelve true,
     * la terminal deja de pintarlo y llama a {@link #prompt(String)}.
     */
    default boolean drawsPrompt() {
        return false;
    }

    /**
     * El aviso que toca ahora, para las fuentes que lo pintan.
     *
     * Lo llama el nucleo en su turno, nunca el hilo lector: el aviso depende del
     * directorio actual, que es estado del sistema.
     */
    default void prompt(String text) {
        // Las fuentes que no pintan no tienen nada que hacer con esto.
    }
}
