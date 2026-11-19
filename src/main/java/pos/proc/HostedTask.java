package pos.proc;

/**
 * Codigo nativo que se ejecuta dentro de un hilo del sistema.
 *
 * Un hilo normal ejecuta las instrucciones de un {@link Program} con el
 * interprete. Un hilo con una tarea enganchada no: cada vez que le toca el
 * turno, el nucleo le pide un paso a esta tarea y listo. Se usa para lo que no
 * tiene sentido escribir en el juego de instrucciones: la terminal, que necesita
 * cadenas, tablas y llamadas al sistema.
 *
 * El contrato es el mismo que el del interprete, y esa es toda la gracia: el
 * nucleo no distingue un hilo interpretado de uno nativo. Sigue habiendo cuota,
 * cambios de contexto, bloqueos y tics; lo unico que cambia es quien ejecuta el
 * paso.
 *
 * Un paso tiene que ser corto. Si una tarea se queda dentro del paso, ningun
 * otro proceso puede ejecutar: aqui no hay expropiacion posible, porque el
 * nucleo solo recupera el control cuando el paso devuelve.
 */
@FunctionalInterface
public interface HostedTask {

    /**
     * Ejecuta un paso en nombre del hilo.
     *
     * @param host el nucleo, para lo que necesite la tarea (reloj, hilos)
     * @param thread el hilo que tiene el turno
     * @return lo mismo que devolveria el interprete tras una instruccion
     */
    StepResult step(ExecutionHost host, Tcb thread);
}
