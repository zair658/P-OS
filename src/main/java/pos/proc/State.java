package pos.proc;

/**
 * Estado de un proceso o de un hilo.
 *
 * Es el mismo juego de estados para los dos niveles porque describen lo mismo:
 * alguien que puede ejecutar, que esta ejecutando, que espera algo o que ya
 * termino. Un proceso se considera vivo mientras tenga al menos un hilo vivo,
 * y esa regla vive en {@link Pcb}, no aqui.
 */
public enum State {
    /** Creado pero aun no admitido en la cola de listos. */
    NEW("nuevo"),
    /** Puede ejecutar, espera turno. */
    READY("listo"),
    /** Tiene la CPU en este momento. */
    RUNNING("ejecutando"),
    /** Espera un suceso: fin de una espera o, mas adelante, un cerrojo. */
    BLOCKED("bloqueado"),
    /** Termino o fallo. */
    TERMINATED("terminado");

    private final String etiqueta;

    State(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
    }
}
