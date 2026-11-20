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

    /** Queda alguna linea por leer, o puede llegar. */
    boolean hasNext();

    /** Tic en el que llegara la proxima linea, o -1 si no queda ninguna. */
    long nextTick();

    /** La siguiente linea. Solo se llama si {@link #hasNext()} dijo que si. */
    Keystroke next();

    /** Cuantas lineas quedan sin leer. */
    int pending();
}
