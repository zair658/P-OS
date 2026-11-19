package pos.kernel;

/**
 * Tipos de suceso que se anotan en la traza.
 *
 * La traza es la prueba de que el sistema hizo lo que dice: cada vez que el
 * nucleo cambia de hilo, bloquea, despierta o entierra a uno, queda una linea
 * con el tick. Dos ejecuciones del mismo escenario tienen que producir la misma
 * traza, y hay una prueba que lo comprueba.
 */
public enum EventKind {
    BOOT("arranque"),
    PROCESS_CREATE("proceso creado"),
    MEMORY_ALLOC("memoria asignada"),
    MEMORY_FREE("memoria liberada"),
    THREAD_CREATE("hilo creado"),
    ADMIT("admitido"),
    SWITCH("cambio de contexto"),
    PREEMPT("desalojo"),
    YIELD("cesion"),
    BLOCK("bloqueo"),
    WAKE("despertar"),
    EXIT("fin de hilo"),
    FAULT("fallo"),
    IDLE("sin trabajo"),
    DEADLOCK("bloqueo mutuo"),
    DONE("fin");

    private final String etiqueta;

    EventKind(String etiqueta) {
        this.etiqueta = etiqueta;
    }

    public String etiqueta() {
        return etiqueta;
    }
}
