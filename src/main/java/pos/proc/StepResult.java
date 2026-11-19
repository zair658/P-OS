package pos.proc;

/**
 * Que paso al ejecutar una instruccion.
 *
 * El interprete no decide nada de planificacion: solo dice si el hilo puede
 * seguir, si ha cedido el turno, si se ha bloqueado o si ha terminado. Quien
 * decide es el nucleo.
 */
public enum StepResult {
    /** El hilo puede seguir ejecutando la siguiente instruccion. */
    CONTINUE,
    /** Cedio el turno con YIELD. */
    YIELDED,
    /** Quedo bloqueado esperando un suceso. */
    BLOCKED,
    /** Termino. */
    EXITED
}
