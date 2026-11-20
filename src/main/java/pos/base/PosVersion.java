package pos.base;

/**
 * Identidad del sistema.
 *
 * Un solo sitio para el nombre y la version, para que la pantalla de arranque, el
 * prompt de la terminal, el comando de informacion y el pie de la ventana no
 * puedan discrepar.
 */
public final class PosVersion {

    /** Nombre del sistema. */
    public static final String NAME = "P-OS";

    /** Version, en formato mayor.menor. */
    public static final String VERSION = "0.8";

    /** Nombre del sistema con su version, para cabeceras. */
    public static final String FULL = NAME + " " + VERSION;

    /** Fase de construccion en la que esta el sistema. */
    public static final String FASE = "8";

    /** Pie de la ventana: la version sale de aqui, no de un literal suelto. */
    public static final String PIE = FULL + " · fase " + FASE + " · interfaz";

    private PosVersion() {
        // Clase de constantes: no se instancia.
    }
}
