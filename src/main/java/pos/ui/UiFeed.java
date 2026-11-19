package pos.ui;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import pos.kernel.Kernel;
import pos.kernel.KernelObserver;

/**
 * El puente entre el nucleo y la ventana: recibe las fotos y las deja listas.
 *
 * El nucleo avisa en su hilo, la ventana pregunta en el suyo. Entre los dos hay
 * una sola referencia atomica: la ultima foto. No hay cerrojos de por medio
 * porque no hacen falta: una foto es inmutable y sustituirla entera es una
 * operacion atomica, asi que la ventana o ve la anterior o ve la nueva, nunca
 * una mezcla de las dos.
 *
 * Ademas de la ultima foto se guarda una serie corta para dibujar la evolucion.
 * Es lo unico que crece con el tiempo, y por eso tiene tope: una ventana abierta
 * toda la tarde no puede acabar con la memoria llena de historia que ya nadie
 * mira.
 */
public final class UiFeed implements KernelObserver {

    /** Muestras que se guardan para el grafico de evolucion. */
    public static final int HISTORY = 900;

    private final AtomicReference<Snapshot> latest = new AtomicReference<>();
    private final Deque<long[]> history = new ArrayDeque<>();
    private final AtomicReference<Boolean> finished = new AtomicReference<>(false);

    private volatile long turns;
    private volatile long version;
    private volatile long pauseMillis;

    /**
     * Frena el sistema para poder mirarlo.
     *
     * El freno vive aqui y no en el nucleo por una razon: dormir es cosa de
     * quien mira, no de quien ejecuta. Un sistema operativo no va despacio
     * porque alguien este mirando su ventana; es la ventana la que se toma su
     * tiempo entre foto y foto. Si se quita la ventana, el freno desaparece con
     * ella y el nucleo sigue igual de rapido.
     */
    public void setPausePerTurn(long millis) {
        this.pauseMillis = Math.max(0L, millis);
    }

    @Override
    public void afterTurn(Kernel kernel) {
        Snapshot shot = Snapshotter.capture(kernel);
        latest.set(shot);
        finished.set(shot.finished());
        turns++;
        version++;
        record(shot);
        pause();
    }

    /** Duerme lo que pida quien mira, si pido algo. */
    private void pause() {
        long millis = pauseMillis;
        if (millis <= 0) {
            return;
        }
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Guarda una muestra de la serie temporal.
     *
     * Se guarda el reloj, el CPU acumulado, los listos y los bloqueados. Con esos
     * cuatro numeros se puede dibujar la curva de ocupacion y la de cola sin
     * guardar el sistema entero en cada tick.
     */
    private void record(Snapshot shot) {
        synchronized (history) {
            history.addLast(new long[] {
                    shot.clock(),
                    shot.cpuTicks(),
                    shot.ready().size(),
                    shot.count("bloqueado")
            });
            while (history.size() > HISTORY) {
                history.removeFirst();
            }
        }
    }

    /** Ultima foto, o {@code null} si el sistema aun no ha dado ninguna. */
    public Snapshot latest() {
        return latest.get();
    }

    /** Serie temporal: cada muestra es {reloj, cpu, listos, bloqueados}. */
    public List<long[]> history() {
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    /** Cuantas fotos se han recibido. Sirve para no repintar dos veces la misma. */
    public long version() {
        return version;
    }

    public long turns() {
        return turns;
    }

    public boolean isFinished() {
        return finished.get();
    }

    /** Se llama al montar un escenario nuevo: la historia anterior ya no vale. */
    public void reset() {
        latest.set(null);
        finished.set(false);
        turns = 0;
        version++;
        synchronized (history) {
            history.clear();
        }
    }
}
