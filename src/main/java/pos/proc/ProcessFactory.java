package pos.proc;

/**
 * Fabricante de procesos e hilos.
 *
 * Existe para que los constructores de {@link Pcb} y {@link Tcb} sigan siendo
 * privados de su paquete: el nucleo no deberia poder montar un bloque de control
 * a mano y saltarse las comprobaciones, pero necesita pedirlos.
 */
public final class ProcessFactory {

    private ProcessFactory() {
        // clase de utilidad
    }

    public static Pcb newProcess(int id, String name, Program program, int memoryUnits,
            long arrivalTick) {
        return new Pcb(id, name, program, memoryUnits, arrivalTick);
    }

    public static Tcb newThread(Pcb process, int localId, long arrivalTick) {
        Tcb thread = new Tcb(process, localId, arrivalTick);
        process.addThread(thread);
        return thread;
    }

    /**
     * Cierra el proceso cuando se entierra el ultimo de sus hilos.
     *
     * @return true si el proceso se quedo sin hilos vivos
     */
    public static boolean closeIfEmpty(Pcb process) {
        if (process.liveThreads() == 0) {
            process.setState(State.TERMINATED);
            return true;
        }
        return false;
    }
}
