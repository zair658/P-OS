package pos.shell;

import java.util.ArrayList;
import java.util.List;

/**
 * El guion de una sesion de terminal.
 *
 * En las pruebas y en las demostraciones no hay teclado de verdad: los procesos
 * corren contra un reloj simulado y una sesion interactiva seria un proceso
 * esperando a una persona que no existe. Asi que la entrada viene escrita de
 * antemano, con el tic en el que cada linea "llega".
 *
 * Esa marca de tic es lo que hace interesante la simulacion: si la tercera linea
 * llega en el tic 40 y la terminal va por el 12, el hilo de la terminal se
 * bloquea hasta el 40 y deja correr a los demas. Sin ella, la terminal se comeria
 * la sesion entera en una sola rafaga y no habria nada que planificar.
 *
 * Para una sesion con teclado de verdad esta {@link LiveKeys}: mismo contrato,
 * fuente distinta.
 */
public final class Console implements Keys {

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

    @Override
    public boolean hasNext() {
        return cursor < script.size();
    }

    @Override
    public Keystroke next() {
        if (!hasNext()) {
            throw new IllegalStateException("no queda entrada por leer");
        }
        return script.get(cursor++);
    }

    @Override
    public long nextTick() {
        return hasNext() ? script.get(cursor).tick() : -1L;
    }

    @Override
    public int pending() {
        return script.size() - cursor;
    }

    public int size() {
        return script.size();
    }
}
