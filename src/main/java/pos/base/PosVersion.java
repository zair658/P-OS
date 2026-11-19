package pos.base;

/**
 * Identidad del sistema.
 *
 * Un solo sitio para el nombre y la version, para que la pantalla de arranque, el
 * prompt de la terminal y el comando de informacion no puedan discrepar.
 */
public final class PosVersion {

    /** Nombre del sistema. */
    public static final String NAME = "P-OS";

    /** Version, en formato mayor.menor. */
    public static final String VERSION = "0.1";

    /** Nombre del sistema con su version, para cabeceras. */
    public static final String FULL = NAME + " " + VERSION;

    private PosVersion() {
        // Clase de constantes: no se instancia.
    }
}
