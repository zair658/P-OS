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
}
