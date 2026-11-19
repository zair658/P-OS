package pos.proc;

/**
 * Motivo por el que un hilo esta bloqueado.
 *
 * Antes bastaba con mirar si el hilo tenia {@code blockedOn} puesto, porque la
 * unica espera posible era un cerrojo. Con semaforos y condiciones eso deja de
 * servir: los tres bloques guardan un nombre de recurso y no se pueden
 * confundir, porque el nucleo los despierta de forma distinta y el detector de
 * interbloqueos solo puede seguir las aristas donde hay un dueño identificable.
 *
 * Es un enum y no una cadena para que el compilador obligue a tratar todos los
 * casos en cada sitio donde se decide que hacer con un hilo bloqueado.
 */
public enum BlockKind {

    /** No esta bloqueado. */
    NINGUNO("nada"),

    /** Duerme un numero de tics; despierta solo. */
    DORMIR("una espera"),

    /** Espera un cerrojo que tiene otro hilo. */
    CERROJO("un cerrojo"),

    /** Espera un permiso de semaforo. */
    SEMAFORO("un semaforo"),

    /** Espera una señal sobre una condicion. */
    CONDICION("una condicion");

    private final String descripcion;

    BlockKind(String descripcion) {
        this.descripcion = descripcion;
    }

    /** Texto para las trazas, por ejemplo "esperando un cerrojo". */
    public String descripcion() {
        return descripcion;
    }
}
