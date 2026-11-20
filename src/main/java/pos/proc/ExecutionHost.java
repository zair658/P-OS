package pos.proc;

/**
 * Lo que un hilo necesita del nucleo mientras ejecuta.
 *
 * El interprete no conoce al planificador ni al reloj, pero tiene que avisar de
 * cada instruccion retirada (para que el tiempo avance) y pedir la creacion de
 * hilos cuando encuentra un SPAWN. Esta interfaz es ese contrato minimo.
 */
public interface ExecutionHost {

    /** Una instruccion mas ha terminado: avanza el reloj y la cuenta del hilo. */
    void retireInstruction(Tcb tcb);

    /**
     * Crea un hilo nuevo dentro del proceso indicado, que empieza en la
     * instruccion {@code entryPc}.
     */
    void createThread(Pcb process, int entryPc, Tcb parent);

    /**
     * Intenta tomar un cerrojo en nombre del hilo que ejecuta.
     *
     * @return true si el hilo se queda con el cerrojo y puede seguir; false si
     *         hay otro duenyo, en cuyo caso el hilo ya queda apuntado en la cola
     *         del cerrojo y el interprete debe pedir el bloqueo
     */
    boolean acquireLock(Tcb tcb, String name);

    /** Libera un cerrojo y cede el turno a quien estuviera esperando. */
    void releaseLock(Tcb tcb, String name);

    /** Crea un semaforo con su valor inicial, en nombre del hilo que ejecuta. */
    void initSemaphore(Tcb tcb, String name, int initial);

    /**
     * Intenta tomar un permiso de semaforo.
     *
     * @return true si lo toma y sigue; false si el contador estaba a cero, en
     *         cuyo caso el hilo ya queda apuntado en la cola del semaforo y el
     *         interprete debe pedir el bloqueo
     */
    boolean waitSemaphore(Tcb tcb, String name);

    /** Devuelve un permiso al semaforo o despierta al primero que espera. */
    void postSemaphore(Tcb tcb, String name);

    /** Anota el maximo que el proceso promete pedir de un recurso. */
    void claimResource(Tcb tcb, String resource, int maximum);

    /**
     * Intenta tomar instancias de un recurso, con el banquero como arbitro.
     *
     * @return true si se conceden y el hilo sigue; false si la peticion dejaria
     *         el sistema en un estado inseguro, en cuyo caso el hilo ya queda
     *         apuntado para ser atendido cuando alguien devuelva lo suficiente
     *         y el interprete debe pedir el bloqueo
     */
    boolean takeResource(Tcb tcb, String resource, int amount);

    /** Devuelve instancias al monton y despierta a quien pudiera esperarlas. */
    void giveResource(Tcb tcb, String resource, int amount);

    /**
     * Ata un hilo a una condicion y lo deja dormido.
     *
     * El cerrojo se suelta como parte del bloqueo, no en una instruccion aparte:
     * si se soltara antes, entre soltar y dormir el hilo podria perderse la señal
     * que venia a esperar.
     */
    void waitCondition(Tcb tcb, String condition, String lock);

    /**
     * Despierta al primero que espera en la condicion.
     *
     * @return el hilo despertado, o {@code null} si no esperaba nadie
     */
    Tcb signalCondition(Tcb tcb, String condition);

    /** Despierta a todos los que esperan en la condicion; devuelve cuantos eran. */
    int broadcastCondition(Tcb tcb, String condition);
}
