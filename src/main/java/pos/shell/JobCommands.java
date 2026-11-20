package pos.shell;

import java.util.ArrayList;
import java.util.List;

import pos.base.PosFault;
import pos.proc.State;
import pos.sys.Syscall;

/**
 * Las ordenes de control de trabajos: {@code jobs}, {@code bg}, {@code fg} y
 * {@code stop}.
 *
 * Una terminal normal no solo arranca programas: los manda al fondo, los trae de
 * vuelta y los para. Sin eso, {@code spawn} seria lanzar y esperar, y el
 * planificador no tendria nada que repartir. Estas cuatro ordenes son la mitad
 * visible de eso; la otra mitad esta en el nucleo, que sabe detener y reanudar
 * procesos sin matarlos.
 *
 * Todas aceptan el numero de trabajo con {@code %n}, como en las cascaras de
 * verdad. Un numero suelto se interpreta como pid, porque el resto de ordenes del
 * sistema ({@code kill}, {@code ps}) hablan en pids y no conviene que el mismo
 * numero signifique dos cosas segun la orden.
 */
final class JobCommands {

    private JobCommands() {
        // clase de utilidad
    }

    static void register(CommandTable table) {
        table.register(Commands.of("jobs", "", "los trabajos de esta sesion",
                JobCommands::jobs));
        table.register(Commands.of("bg", "%n | pid", "sigue en segundo plano",
                JobCommands::bg));
        table.register(Commands.of("fg", "%n | pid", "lo trae al primer plano y espera",
                JobCommands::fg));
        table.register(Commands.of("stop", "%n | pid", "lo detiene sin matarlo",
                JobCommands::stop));
    }

    // ------------------------------------------------------------------
    // jobs
    // ------------------------------------------------------------------

    private static void jobs(Shell shell, List<String> args, Input in, Output out) {
        JobTable jobs = shell.jobs();
        if (jobs.isEmpty()) {
            out.println("no hay trabajos en esta sesion");
            return;
        }

        // Las filas se reunen antes de pintar porque el ancho de cada columna
        // depende de lo que haya dentro: con "listo" y "detenido" en la misma
        // tabla, un ancho fijo deja la columna del plano temblando.
        List<String[]> filas = new ArrayList<>();
        List<String> fantasmas = new ArrayList<>();
        for (JobTable.Job job : jobs.all()) {
            String state = shell.jobState(job.pid());
            if (state.isEmpty()) {
                // El proceso desaparecio del nucleo sin que la sesion se
                // enterara. Se dice y se quita: una fila fantasma en la tabla
                // es peor que una tabla corta.
                jobs.remove(job.pid());
                fantasmas.add("  [" + job.number() + "] p" + job.pid()
                        + ": ya no existe, sale de la tabla");
                continue;
            }
            filas.add(new String[] {
                    "[" + job.number() + "]", "p" + job.pid(), state,
                    job.background() ? "segundo" : "primero", job.label()});
        }
        if (filas.isEmpty()) {
            out.println("no hay trabajos en esta sesion");
        } else {
            out.println(fila(new String[] {"trab", "pid", "estado", "plano", "nombre"},
                    anchos(filas)));
            for (String[] row : filas) {
                out.println(fila(row, anchos(filas)));
            }
        }
        for (String aviso : fantasmas) {
            out.println(aviso);
        }
    }

    /** El ancho de cada columna: lo que ocupe la cabecera o el valor mas largo. */
    private static int[] anchos(List<String[]> filas) {
        int[] ancho = {"trab".length(), "pid".length(), "estado".length(),
                "plano".length(), "nombre".length()};
        for (String[] row : filas) {
            for (int i = 0; i < row.length; i++) {
                ancho[i] = Math.max(ancho[i], row[i].length());
            }
        }
        return ancho;
    }

    /** Una linea de la tabla: columnas alineadas y la ultima sin relleno. */
    private static String fila(String[] campos, int[] ancho) {
        StringBuilder linea = new StringBuilder("  ");
        for (int i = 0; i < campos.length; i++) {
            if (i == campos.length - 1) {
                linea.append(campos[i]);
            } else {
                linea.append(pad(campos[i], ancho[i] + 2));
            }
        }
        return linea.toString();
    }

    // ------------------------------------------------------------------
    // bg y fg
    // ------------------------------------------------------------------

    private static void bg(Shell shell, List<String> args, Input in, Output out) {
        JobTable.Job job = resolve(shell, args);
        String state = shell.jobState(job.pid());
        if (state.isEmpty() || state.equals(State.TERMINATED.etiqueta())) {
            shell.jobs().remove(job.pid());
            throw new PosFault("el trabajo " + job.number() + " ya termino");
        }
        job.setBackground(true);
        if (state.equals(State.STOPPED.etiqueta())) {
            shell.must(new Syscall.Resume(job.pid()));
            out.println(job + ": reanudado en segundo plano");
        } else {
            out.println(job + ": ya corria; queda en segundo plano");
        }
    }

    private static void fg(Shell shell, List<String> args, Input in, Output out) {
        JobTable.Job job = resolve(shell, args);
        String state = shell.jobState(job.pid());
        if (state.isEmpty() || state.equals(State.TERMINATED.etiqueta())) {
            shell.jobs().remove(job.pid());
            throw new PosFault("el trabajo " + job.number() + " ya termino");
        }
        job.setBackground(false);
        if (state.equals(State.STOPPED.etiqueta())) {
            shell.must(new Syscall.Resume(job.pid()));
        }
        // La llamada devuelve "1" si de verdad hay algo que esperar. Con un
        // proceso ya terminado devuelve "0", y entonces no se espera: dormirse
        // por un aviso que ya se dio seria no despertar nunca.
        if ("1".equals(shell.must(new Syscall.WaitProcess(job.pid())))) {
            // Se dice el tope al usuario: cuando llegue, el aviso vuelve y el
            // trabajo sigue vivo, y conviene que no parezca que se ha perdido.
            out.println(job + ": al primer plano; la terminal espera como mucho "
                    + Shell.ESPERA_MAXIMA + " ticks");
            shell.requestWait();
        } else {
            out.println(job + ": ya habia terminado");
            shell.jobs().remove(job.pid());
        }
    }

    // ------------------------------------------------------------------
    // stop
    // ------------------------------------------------------------------

    private static void stop(Shell shell, List<String> args, Input in, Output out) {
        JobTable.Job job = resolve(shell, args);
        String state = shell.jobState(job.pid());
        if (state.isEmpty() || state.equals(State.TERMINATED.etiqueta())) {
            shell.jobs().remove(job.pid());
            throw new PosFault("el trabajo " + job.number() + " ya termino");
        }
        if (state.equals(State.STOPPED.etiqueta())) {
            out.println(job + ": ya estaba detenido");
            return;
        }
        int stopped = Integer.parseInt(shell.must(new Syscall.Suspend(job.pid())));
        out.println(job + ": detenido (" + stopped + " hilo(s) fuera de la cola)");
    }

    // ------------------------------------------------------------------
    // Apoyo
    // ------------------------------------------------------------------

    /**
     * Traduce el argumento a un trabajo: {@code %n} por numero, o un pid que
     * tiene que ser de un trabajo de esta sesion.
     *
     * Se comparte con {@code kill}, que tambien acepta {@code %n}: en una
     * terminal de verdad las ordenes que hablan de trabajos hablan todas igual,
     * y obligar a buscar el pid con {@code jobs} antes de matar seria una
     * molestia sin ninguna ventaja.
     */
    static JobTable.Job resolve(Shell shell, List<String> args) {
        if (args.isEmpty()) {
            throw new PosFault("falta el trabajo: escribe %1 o el pid");
        }
        String arg = args.get(0).trim();
        JobTable jobs = shell.jobs();
        if (arg.startsWith("%")) {
            int number;
            try {
                number = Integer.parseInt(arg.substring(1));
            } catch (NumberFormatException e) {
                throw new PosFault("'" + arg + "' no es un numero de trabajo");
            }
            JobTable.Job job = jobs.byNumber(number);
            if (job == null) {
                throw new PosFault("no hay ningun trabajo " + arg + " en esta sesion");
            }
            return job;
        }
        int pid;
        try {
            pid = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            throw new PosFault("'" + arg + "' no es un trabajo; usa %n o un pid");
        }
        JobTable.Job job = jobs.byPid(pid);
        if (job == null) {
            if (jobs.isEmpty()) {
                throw new PosFault("no hay trabajos: arranca uno con 'spawn ... &'");
            }
            throw new PosFault("p" + pid + " no es un trabajo de esta sesion;"
                    + " escribe 'jobs' para ver cuales hay");
        }
        return job;
    }

    /** Rellena a la derecha para que las columnas caigan donde toca. */
    private static String pad(String text, int width) {
        StringBuilder padded = new StringBuilder(text);
        while (padded.length() < width) {
            padded.append(' ');
        }
        return padded.toString();
    }
}
