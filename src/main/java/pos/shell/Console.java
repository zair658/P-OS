package pos.shell;

import java.util.ArrayList;
import java.util.List;

/**
 * El guion de una sesion de terminal.
 *
 * En este sistema no hay teclado de verdad: los procesos corren contra un reloj
 * simulado y una sesion interactiva de verdad seria un proceso esperando a una
 * persona que no existe. Asi que la entrada viene escrita de antemano, con el
 * tic en el que cada linea "llega".
 *
 * Esa marca de tic es lo que hace interesante la simulacion: si la tercera linea
 * llega en el tic 40 y la terminal va por el 12, el hilo de la terminal se
 * bloquea hasta el 40 y deja correr a los demas. Sin ella, la terminal se comeria
 * la sesion entera en una sola rafaga y no habria nada que planificar.
 */
public final class Console {

    /** Una linea que llega al teclado en un tic concreto. */
    public record Keystroke(long tick, String text) {

        public Keystroke {
            if (tick < 0) {
                throw new IllegalArgumentException("un tic no puede ser negativo: " + tick);
            }
            if (text == null) {
                throw new IllegalArgumentException("la linea no puede ser nula");
            }
        }
    }

    private final List<Keystroke> script;
    private int cursor;

    public Console(List<Keystroke> script) {
        this.script = List.copyOf(script);
    }

    /** Guion en el que cada linea llega un tic despues de la anterior. */
    public static Console of(String... lines) {
        List<Keystroke> typed = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) {
            typed.add(new Keystroke(i + 1L, lines[i]));
        }
        return new Console(typed);
    }

    public boolean hasNext() {
        return cursor < script.size();
    }

    public Keystroke next() {
        if (!hasNext()) {
            throw new IllegalStateException("no queda entrada por leer");
        }
        return script.get(cursor++);
    }

    /** Tic en el que llegara la proxima linea, o -1 si no queda ninguna. */
    public long nextTick() {
        return hasNext() ? script.get(cursor).tick() : -1L;
    }

    /** Cuantas lineas quedan sin leer. */
    public int pending() {
        return script.size() - cursor;
    }

    public int size() {
        return script.size();
    }
}
