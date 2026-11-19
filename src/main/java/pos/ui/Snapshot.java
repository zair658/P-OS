package pos.ui;

import java.util.List;

/**
 * Una foto del sistema en un instante, ya copiada y ya cerrada.
 *
 * Esta es la pieza que permite que la ventana no toque el nucleo. En vez de que
 * la interfaz pregunte "como va esto" mientras el sistema muta sus colas -- que
 * seria una carrera garantizada -- el nucleo construye una de estas al cerrar
 * cada turno, cuando nadie ejecuta, y la deja hecha. La ventana solo lee copias.
 *
 * Todo lo que hay aqui son valores: listas inmutables, textos ya formateados y
 * el mapa de memoria en un array propio. Nada apunta a un objeto vivo del
 * nucleo, asi que una foto vieja sigue siendo valida y comparable con la nueva.
 */
public record Snapshot(
        long clock,
        String scheduler,
        String memoryScheme,
        String phase,
        List<Proc> processes,
        List<ThreadInfo> threads,
        MemoryMap memory,
        List<Ready> ready,
        long cpuTicks,
        Sync sync,
        Calls calls,
        List<String> trace,
        boolean traceTruncated) {

    public Snapshot {
        processes = List.copyOf(processes);
        threads = List.copyOf(threads);
        ready = List.copyOf(ready);
        trace = List.copyOf(trace);
    }

    /** Un proceso, con lo que la tabla necesita y no mas. */
    public record Proc(int pid, String name, String state, int threads, int liveThreads,
            int units, int physical, String scheme) {
    }

    /** Un hilo, con sus tiempos en ticks y su estado tal cual lo dice el nucleo. */
    public record ThreadInfo(String id, String process, String state, int pc, long cpu,
            long waitTicks, long turnaround, String blockedOn, String failure) {
    }

    /**
     * El mapa de memoria fisica, unidad a unidad.
     *
     * {@code owners[i]} es el identificador del proceso duenno de la unidad
     * {@code i}, o {@link pos.phys.PhysicalMemory#FREE} si esta libre. Se guarda
     * como array porque el mapa se pinta celda a celda y convertirlo a lista en
     * cada refresco seria trabajo tirado.
     */
    public record MemoryMap(int size, int used, int free, String scheme, int[] owners) {

        public MemoryMap {
            owners = owners.clone();
        }

        /** Copia defensiva: el array no sale de aqui. */
        @Override
        public int[] owners() {
            return owners.clone();
        }

        /** Duenno de una unidad, o {@code FREE} si esta libre. */
        public int ownerAt(int unit) {
            return owners[unit];
        }

        public double usedRatio() {
            return size == 0 ? 0.0 : (double) used / size;
        }
    }

    /** Un tramo de memoria con duenno, para la tabla del mapa. */
    public record Span(int start, int length, int owner) {
    }

    /** Un hilo en la cola de listos, con su sitio en la cola. */
    public record Ready(int position, String thread, String process) {
    }

    /** El estado de los tres mecanismos de sincronizacion. */
    public record Sync(List<LockRow> locks, List<SemRow> semaphores, List<CondRow> conditions,
            String deadlock) {

        public Sync {
            locks = List.copyOf(locks);
            semaphores = List.copyOf(semaphores);
            conditions = List.copyOf(conditions);
        }

        public boolean idle() {
            return locks.isEmpty() && semaphores.isEmpty() && conditions.isEmpty();
        }
    }

    /** Un cerrojo: quien lo tiene y cuantos esperan. */
    public record LockRow(String name, String owner, int waiting) {
    }

    /** Un semaforo: su valor y cuantos esperan permiso. */
    public record SemRow(String name, int value, int waiting) {
    }

    /** Una condicion: el cerrojo al que va atada y cuantos esperan. */
    public record CondRow(String name, String lock, int waiting) {
    }

    /** La contabilidad de llamadas al sistema. */
    public record Calls(long total, long ticks, List<CallRow> rows) {

        public Calls {
            rows = List.copyOf(rows);
        }

        public boolean empty() {
            return total == 0;
        }
    }

    /** Una llamada y cuantas veces se hizo. */
    public record CallRow(int code, String name, long count) {
    }

    // ------------------------------------------------------------------
    // Consultas derivadas
    // ------------------------------------------------------------------

    /**
     * Cuantos hilos hay en cada estado.
     *
     * Se calcula al construir la foto y no en cada pintada: el estado de la
     * cabecera y el de la tabla tienen que decir lo mismo, y calcularlo dos veces
     * en sitios distintos es la forma mas facil de que un dia no lo digan.
     */
    public int count(String state) {
        int total = 0;
        for (ThreadInfo thread : threads) {
            if (thread.state().equals(state)) {
                total++;
            }
        }
        return total;
    }

    public boolean finished() {
        return "terminado".equals(phase);
    }

    /** Procesos vivos: los que no han terminado. */
    public int liveProcesses() {
        int total = 0;
        for (Proc process : processes) {
            if (!"terminado".equals(process.state())) {
                total++;
            }
        }
        return total;
    }

    /**
     * Ocupacion de la CPU en tanto por uno, medida como ticks de CPU repartidos
     * entre los ticks de reloj transcurridos y el numero de hilos que habia.
     *
     * Es una medida honesta pero tosca: con un solo procesador, lo que sobra es
     * tiempo en que no habia nada que ejecutar. Se acota a 1 porque los hilos
     * que duermen tambien consumen reloj y la cuenta podria pasarse.
     */
    public double cpuUse() {
        if (clock <= 0 || threads.isEmpty()) {
            return 0.0;
        }
        double raw = (double) cpuTicks / ((double) clock * threads.size());
        return Math.min(1.0, raw);
    }

    /** El proceso duenno de una unidad, o {@code null} si esta libre. */
    public String ownerName(int unit) {
        int owner = memory.ownerAt(unit);
        for (Proc process : processes) {
            if (process.pid() == owner) {
                return process.name();
            }
        }
        return null;
    }
}
