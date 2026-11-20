package pos.ui.swing;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

import pos.shell.Keys;
import pos.shell.LiveKeys;

/**
 * La entrada de la terminal cuando se escribe en la propia ventana.
 *
 * Es la misma idea que {@link LiveKeys} y por la misma razon: el teclado no
 * puede bloquear al nucleo. Aqui el teclado es el campo de texto de la ventana,
 * que corre en el hilo de Swing, y el nucleo corre en otro hilo. Si la terminal
 * se quedara esperando a que alguien pulse Intro dentro de su turno, los demas
 * procesos no avanzarian mientras alguien piensa que escribir, y el sistema
 * entero seria un solo hilo disfrazado.
 *
 * Por eso el campo de texto solo deja la linea en una cola y se marcha. La
 * terminal, en su turno, mira si hay algo; si no lo hay pide dormir unos tics y
 * suelta el turno. El precio es el de siempre: la terminal se despierta cada
 * pocos tics para mirar, y ese retardo es el que se nota al escribir.
 *
 * Nunca se cierra sola. Mientras la ventana este abierta la fuente sigue
 * abierta, y por eso la terminal no termina y el sistema no se queda sin nadie
 * a quien servir: si terminara, el nucleo cerraria el escenario y la ventana se
 * quedaria congelada sin poder escribir nada mas.
 */
public final class SwingKeys implements Keys {

    private final Deque<Keystroke> queue = new ArrayDeque<>();
    private final Object lock = new Object();

    /** Reloj del nucleo. Se engancha despues de construirlo: el nucleo usa la fuente. */
    private LongSupplier clock = () -> 0L;

    private volatile boolean open = true;

    /**
     * Engancha el reloj del nucleo.
     *
     * Hay que hacerlo asi por el orden de construccion: el nucleo crea la
     * terminal y la terminal pide esta fuente, asi que el reloj todavia no
     * existe cuando se crea la fuente. Hasta que llegue, el tic de una linea es
     * cero, que solo significa "ya ha llegado" y se ejecuta de inmediato.
     */
    public void attachClock(LongSupplier source) {
        if (source == null) {
            throw new IllegalArgumentException("el reloj no puede ser nulo");
        }
        this.clock = source;
    }

    /** Deja una linea escrita en la ventana para que la terminal la recoja. */
    public void offer(String line) {
        if (line == null) {
            throw new IllegalArgumentException("la linea no puede ser nula");
        }
        synchronized (lock) {
            queue.addLast(new Keystroke(Math.max(0L, clock.getAsLong()), line));
            lock.notifyAll();
        }
    }

    /** Cierra la fuente: la terminal terminara su sesion en el siguiente turno. */
    public void close() {
        open = false;
    }

    @Override
    public boolean hasNext() {
        synchronized (lock) {
            return open || !queue.isEmpty();
        }
    }

    @Override
    public long nextTick() {
        synchronized (lock) {
            Keystroke first = queue.peekFirst();
            // Con algo en la cola la linea ya ha llegado: se ejecuta en este turno.
            // Sin nada, se pide volver a mirar dentro de unos tics.
            return first != null ? first.tick() : clock.getAsLong() + LiveKeys.POLL_TICKS;
        }
    }

    @Override
    public Keystroke next() {
        synchronized (lock) {
            return queue.pollFirst();
        }
    }

    @Override
    public int pending() {
        synchronized (lock) {
            return queue.size();
        }
    }
}
