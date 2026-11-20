package pos.shell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Los trabajos de la sesion: lo que la cascara arranco y sigue vivo.
 *
 * Esto no es una copia de la tabla de procesos. El nucleo sabe de procesos; la
 * cascara sabe de <em>trabajos</em>, que es otra cosa: un trabajo tiene numero de
 * la sesion, un nombre puesto por quien lo arranco y un plano (primero o segundo),
 * y esas tres cosas solo existen desde dentro de la sesion. Por eso el estado no
 * se guarda aqui: se pregunta al nucleo cada vez que se pinta la tabla. Guardarlo
 * seria quedarse con la foto de hace un rato y ensenar "ejecutando" un proceso
 * que termino hace veinte tics.
 *
 * La numeracion es creciente y no se reutiliza. Un hueco en la lista es
 * informacion: dice que el trabajo 2 acabo y que el 3 no es el mismo de antes.
 */
public final class JobTable {

    /** Un trabajo de la sesion: numero, proceso, nombre y plano. */
    public static final class Job {

        private final int number;
        private final int pid;
        private final String label;
        private boolean background;

        Job(int number, int pid, String label, boolean background) {
            this.number = number;
            this.pid = pid;
            this.label = label;
            this.background = background;
        }

        /** Numero de la sesion, el que se escribe en {@code fg 1}. */
        public int number() {
            return number;
        }

        public int pid() {
            return pid;
        }

        /** Nombre del trabajo, tal como lo escribio el usuario. */
        public String label() {
            return label;
        }

        public boolean background() {
            return background;
        }

        public void setBackground(boolean background) {
            this.background = background;
        }

        @Override
        public String toString() {
            return "[" + number + "] p" + pid + " " + label
                    + (background ? " (segundo plano)" : " (primer plano)");
        }
    }

    private final Map<Integer, Job> byPid = new LinkedHashMap<>();
    private int nextNumber = 1;

    /** Anota un proceso arrancado desde la sesion. */
    public Job add(int pid, String label, boolean background) {
        if (byPid.containsKey(pid)) {
            throw new IllegalStateException("el proceso " + pid + " ya es un trabajo");
        }
        Job job = new Job(nextNumber++, pid, label, background);
        byPid.put(pid, job);
        return job;
    }

    /** El trabajo con ese numero de sesion, o {@code null}. */
    public Job byNumber(int number) {
        for (Job job : byPid.values()) {
            if (job.number() == number) {
                return job;
            }
        }
        return null;
    }

    public Job byPid(int pid) {
        return byPid.get(pid);
    }

    /** Quita el trabajo y devuelve el que estaba, o {@code null}. */
    public Job remove(int pid) {
        return byPid.remove(pid);
    }

    /** Los trabajos vivos, en orden de numero. */
    public List<Job> all() {
        return List.copyOf(new ArrayList<>(byPid.values()));
    }

    public int size() {
        return byPid.size();
    }

    public boolean isEmpty() {
        return byPid.isEmpty();
    }
}
