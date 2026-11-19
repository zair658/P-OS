package pos.base;

/**
 * Fallo controlado del sistema.
 *
 * Se usa para todo lo que el nucleo debe reportar en vez de dejar escapar una
 * excepcion generica: una direccion fuera de rango, un programa mal formado, un
 * contador de programa invalido. El mensaje siempre dice que paso y con que dato,
 * porque ese texto acaba en la traza y en la consola.
 */
public class PosFault extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PosFault(String message) {
        super(message);
    }

    public PosFault(String message, Throwable cause) {
        super(message, cause);
    }
}
