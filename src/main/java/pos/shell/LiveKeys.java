package pos.shell;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.LongSupplier;

/**
 * La entrada de verdad: lo que alguien escribe en el teclado.
 *
 * El problema de mezclar teclado con planificacion es que el teclado es lento y
 * el nucleo no puede esperarlo. Si la terminal se quedara parada en un
 * {@code readLine} dentro de su turno, el planificador no volveria a elegir a
 * nadie: los demas procesos no correrian mientras alguien piensa que escribir, y
 * el sistema entero seria un unico hilo disfrazado. Justo lo contrario de lo que
 * este sistema quiere ensenar.
 *
 * Por eso el teclado se lee en otro hilo y las lineas se dejan en una cola. La
 * terminal, en su turno, mira si hay algo: si lo hay lo ejecuta y si no pide
 * dormir unos tics y suelta el turno. Asi el teclado no bloquea a nadie y el
 * sistema sigue repartiendo el procesador mientras espera.
 *
 * Ese "pedir dormir unos tics" es el precio de hacerlo bien: la terminal se
 * despierta cada pocos tics para mirar si ha llegado algo. Con un tic de verdad
 * detras seria un sondeo razonable; aqui, como el reloj corre a ritmo de
 * simulacion, el retardo se nota poco.
 *
 * Aqui no se escribe nada: ni avisos ni ecos. El aviso lo pinta la terminal
 * cuando se queda esperando, y la linea la pinta el propio teclado del sistema
 * al leerla. Si este hilo tambien pintara, saldrian dos avisos y en mal orden.
 */
public final class LiveKeys implements Keys {

    /** Tics que la terminal duerme entre dos miradas a la cola. */
    public static final long POLL_TICKS = 2;

    private final Deque<Keystroke> queue = new ArrayDeque<>();
    private final LongSupplier clock;
    private final Object lock = new Object();
    private volatile boolean closed;
    private final Thread reader;

    /**
     * @param in de donde se lee: normalmente la entrada estandar
     * @param clock reloj del nucleo, para fechar cada linea cuando llega
     */
    public LiveKeys(InputStream in, LongSupplier clock) {
        if (in == null || clock == null) {
            throw new IllegalArgumentException("la entrada viva necesita flujo y reloj");
        }
        this.clock = clock;
        this.reader = new Thread(() -> readLoop(in), "teclado");
        // Demonio: si la simulacion termina, el hilo que espera al teclado no
        // puede quedarse sujetando la maquina virtual abierta.
        this.reader.setDaemon(true);
    }

    /** Arranca la lectura del teclado. */
    public void start() {
        reader.start();
    }

    private void readLoop(InputStream in) {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            while (true) {
                String line = br.readLine();
                if (line == null) {
                    // Fin de la entrada: no es un error, es que se cerro el flujo.
                    break;
                }
                synchronized (lock) {
                    queue.addLast(new Keystroke(clock.getAsLong(), line));
                    lock.notifyAll();
                }
            }
        } catch (IOException e) {
            // Un fallo de lectura se trata como fin de entrada: la sesion acaba
            // por donde empezo, sin mas lineas que ejecutar.
        } finally {
            synchronized (lock) {
                closed = true;
                lock.notifyAll();
            }
        }
    }

    @Override
    public boolean hasNext() {
        synchronized (lock) {
            return !queue.isEmpty() || !closed;
        }
    }

    /**
     * Si el lector ya cerro, es decir, si la entrada se agoto.
     *
     * Sirve para distinguir "todavia puede llegar algo" de "esto era todo". Las
     * pruebas lo usan para arrancar el nucleo con el guion ya entero en la cola;
     * si arrancara antes, la terminal se encontraria la cola vacia a media
     * lectura y no habria forma de saber que no estaba esperando a nadie.
     */
    boolean terminado() {
        return closed;
    }

    /**
     * Cuando llega la proxima linea.
     *
     * Si ya hay una en la cola, llego ahora: se devuelve el tic actual para que
     * la terminal no duerma. Si no hay ninguna pero el teclado sigue abierto, se
     * devuelve un tic futuro y la terminal duerme ese rato y vuelve a mirar: eso
     * es el sondeo, y es lo que evita que la terminal se quede con el turno.
     */
    @Override
    public long nextTick() {
        synchronized (lock) {
            if (!queue.isEmpty()) {
                return clock.getAsLong();
            }
            if (closed) {
                return -1L;
            }
            return clock.getAsLong() + POLL_TICKS;
        }
    }

    @Override
    public Keystroke next() {
        synchronized (lock) {
            if (queue.isEmpty()) {
                throw new IllegalStateException("no hay linea que entregar");
            }
            return queue.removeFirst();
        }
    }

    @Override
    public int pending() {
        synchronized (lock) {
            return queue.size();
        }
    }
}
