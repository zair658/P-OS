package pos.kernel;

import java.util.ArrayList;
import java.util.List;
import pos.proc.State;
import pos.proc.Tcb;

/**
 * Tabla de resultados de una ejecucion.
 *
 * Es lo que se ensena en la defensa y lo que se compara entre planificadores:
 * una fila por hilo con las metricas clasicas. Se genera a partir de los bloques
 * de control, no de contadores aparte, para que no pueda decir una cosa distinta
 * de lo que paso.
 */
public record KernelReport(long clock, String scheduler, List<Row> rows, boolean truncatedTrace) {

    /** Metricas de un hilo. Los tiempos son ticks; -1 significa "no ocurrio". */
    public record Row(String thread, String process, long arrival, long start, long finish,
            long cpu, long turnaround, long waitTicks, long response, String state, String failure) {
    }

    public KernelReport {
        rows = List.copyOf(rows);
    }

    static KernelReport of(long clock, String scheduler, List<Tcb> threads, boolean truncated) {
        List<Row> rows = new ArrayList<>(threads.size());
        for (Tcb thread : threads) {
            rows.add(new Row(
                    thread.id(),
                    thread.process().name(),
                    thread.arrivalTick(),
                    thread.startTick(),
                    thread.finishTick(),
                    thread.cpuTicks(),
                    thread.turnaround(clock),
                    thread.waitTicks(clock),
                    thread.responseTicks(),
                    thread.state().etiqueta(),
                    thread.failure()));
        }
        return new KernelReport(clock, scheduler, rows, truncated);
    }

    /** Cabecera y filas ya alineadas para imprimir en consola. */
    public List<String> table() {
        List<String> lines = new ArrayList<>();
        lines.add(String.format("%-9s %-14s %8s %7s %6s %6s %8s %7s %10s  %s",
                "hilo", "proceso", "llegada", "inicio", "fin", "cpu", "retorno", "espera",
                "respuesta", "estado"));
        lines.add("-".repeat(104));
        for (Row row : rows) {
            lines.add(String.format("%-9s %-14s %8d %7s %6s %6d %8d %7d %10s  %s%s",
                    row.thread(),
                    row.process(),
                    row.arrival(),
                    optional(row.start()),
                    optional(row.finish()),
                    row.cpu(),
                    row.turnaround(),
                    row.waitTicks(),
                    row.response() < 0 ? "-" : Long.toString(row.response()),
                    row.state(),
                    row.failure() == null ? "" : "  [" + row.failure() + "]"));
        }
        if (truncatedTrace) {
            lines.add("aviso: la traza se trunco por exceso de sucesos");
        }
        return List.copyOf(lines);
    }

    /** Hilos que llegaron a terminar. */
    public long finishedCount() {
        return rows.stream().filter(row -> row.finish() >= 0).count();
    }

    public double averageTurnaround() {
        return rows.stream().filter(row -> row.finish() >= 0)
                .mapToLong(Row::turnaround).average().orElse(0);
    }

    public double averageWait() {
        return rows.stream().filter(row -> row.finish() >= 0)
                .mapToLong(Row::waitTicks).average().orElse(0);
    }

    public double averageResponse() {
        return rows.stream().filter(row -> row.response() >= 0)
                .mapToLong(Row::response).average().orElse(0);
    }

    public long totalCpu() {
        return rows.stream().mapToLong(Row::cpu).sum();
    }

    /** Promedios sobre los hilos que llegaron a terminar. */
    public List<String> summary() {
        long finished = finishedCount();
        if (finished == 0) {
            return List.of("no termino ningun hilo; no hay promedios que dar");
        }
        List<String> lines = new ArrayList<>();
        lines.add(String.format("reloj final ......... %d ticks", clock));
        lines.add(String.format("planificador ....... %s", scheduler));
        lines.add(String.format("hilos terminados .... %d de %d", finished, rows.size()));
        lines.add(String.format("cpu total ........... %d ticks", totalCpu()));
        lines.add(String.format("retorno medio ....... %.2f ticks", averageTurnaround()));
        lines.add(String.format("espera media ........ %.2f ticks", averageWait()));
        lines.add(String.format("respuesta media ..... %.2f ticks", averageResponse()));
        return List.copyOf(lines);
    }

    private static String optional(long value) {
        return value < 0 ? "-" : Long.toString(value);
    }

    /**
     * Linea de una sola fila, para pruebas y para el comando de estado.
     */
    public Row row(String threadId) {
        for (Row row : rows) {
            if (row.thread().equals(threadId)) {
                return row;
            }
        }
        return null;
    }

    /** Numero de hilos que acabaron sin fallo. */
    public long healthyThreads() {
        return rows.stream()
                .filter(row -> row.failure() == null && State.TERMINATED.etiqueta().equals(row.state()))
                .count();
    }
}
