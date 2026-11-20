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
    /**
     * Detenido desde fuera: no espera nada, pero tampoco puede ejecutar.
     *
     * Es el estado que introduce el control de trabajos y el unico que no sale
     * de una decision del propio hilo. Un hilo bloqueado se despertara cuando
     * llegue lo que espera; uno detenido no tiene nada que esperar: solo vuelve
     * a la vida si alguien lo reanuda. Por eso no esta en la cola de listos ni
     * en la de bloqueados, y por eso no cuenta como averia cuando el sistema se
     * cierra con el puesto.
     */
    STOPPED("detenido"),
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
