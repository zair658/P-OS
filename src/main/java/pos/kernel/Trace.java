package pos.kernel;

import java.util.ArrayList;
import java.util.List;

/**
 * Registro ordenado de lo que hace el nucleo.
 *
 * Se guarda en memoria con un tope para que una ejecucion larga no se coma la
 * memoria del propio sistema; si se supera el tope se deja de anotar y se marca
 * la traza como truncada, en vez de crecer sin control.
 */
public final class Trace {

    /** Un suceso con su numero de orden y el tick en que ocurrio. */
    public record Event(long seq, long tick, EventKind kind, String who, String detail) {
        public String line() {
            return String.format("[%5d] %-16s %-10s %s",
                    tick, kind.name(), who, detail);
        }
    }

    private static final int CAPACITY = 20_000;

    private final List<Event> events = new ArrayList<>();
    private long seq;
    private boolean truncated;

    public synchronized void add(long tick, EventKind kind, String who, String detail) {
        seq++;
        if (events.size() >= CAPACITY) {
            truncated = true;
            return;
        }
        events.add(new Event(seq, tick, kind, who, detail));
    }

    public synchronized List<Event> events() {
        return List.copyOf(events);
    }

    public synchronized List<String> lines() {
        List<String> lines = new ArrayList<>(events.size());
        for (Event event : events) {
            lines.add(event.line());
        }
        if (truncated) {
            lines.add("[     ] traza truncada: se alcanzo el tope de " + CAPACITY + " sucesos");
        }
        return List.copyOf(lines);
    }

    public synchronized int size() {
        return events.size();
    }

    public synchronized boolean truncated() {
        return truncated;
    }
}
