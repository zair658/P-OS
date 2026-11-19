package pos.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pos.base.PosFault;
import pos.proc.Assembler;
import pos.proc.Pcb;
import pos.proc.ProcessFactory;
import pos.proc.State;
import pos.proc.Tcb;

@DisplayName("El gestor de cerrojos da exclusion, respeta el orden de espera y ve los ciclos")
class LockManagerTest {

    /** Programa de relleno: al gestor de cerrojos no le importa lo que ejecuten. */
    private static Pcb proceso() {
        return ProcessFactory.newProcess(1, "demo", Assembler.load("demo", "SET 1\nEXIT"), 16, 0);
    }

    private static Tcb[] hilos(int cuantos) {
        Pcb process = proceso();
        Tcb[] threads = new Tcb[cuantos];
        for (int i = 0; i < cuantos; i++) {
            threads[i] = ProcessFactory.newThread(process, i + 1, 0);
        }
        return threads;
    }

    @Test
    @DisplayName("un cerrojo libre se toma a la primera y queda con duenyo")
    void cerrojoLibreSeToma() {
        LockManager locks = new LockManager();
        Tcb h1 = hilos(1)[0];

        assertTrue(locks.acquire(h1, "contador"));
        assertSame(h1, locks.ownerOf("contador"));
        assertEquals(0, locks.waitingCount("contador"));
        assertTrue(locks.heldBy(h1).contains("contador"));
    }

    @Test
    @DisplayName("el segundo que lo pide no lo obtiene y queda en la cola")
    void elSegundoEspera() {
        LockManager locks = new LockManager();
        Tcb[] h = hilos(2);

        assertTrue(locks.acquire(h[0], "contador"));
        assertFalse(locks.acquire(h[1], "contador"));
        assertSame(h[0], locks.ownerOf("contador"));
        assertEquals(1, locks.waitingCount("contador"));
        assertEquals(1, locks.heldCount());
    }

    @Test
    @DisplayName("al liberar se lo cede al primero que llego, no al ultimo")
    void liberarCedeAlPrimeroDeLaCola() {
        LockManager locks = new LockManager();
        Tcb[] h = hilos(3);

        assertTrue(locks.acquire(h[0], "contador"));
        locks.acquire(h[1], "contador");
        locks.acquire(h[2], "contador");

        Tcb heredero = locks.release(h[0], "contador");

        assertSame(h[1], heredero, "el primero de la cola hereda el cerrojo");
        assertSame(h[1], locks.ownerOf("contador"));
        assertEquals(1, locks.waitingCount("contador"));
        assertEquals(List.of(h[1]), locks.drainGranted());
    }

    @Test
    @DisplayName("el que hereda no se da por despierto dos veces")
    void laHerenciaSeSirveUnaSolaVez() {
        LockManager locks = new LockManager();
        Tcb[] h = hilos(2);

        locks.acquire(h[0], "contador");
        locks.acquire(h[1], "contador");
        locks.release(h[0], "contador");

        assertEquals(List.of(h[1]), locks.drainGranted());
        assertTrue(locks.drainGranted().isEmpty(), "la segunda vez ya no hay nadie que despertar");
    }

    @Test
    @DisplayName("liberar un cerrojo ajeno es un error, no un favor")
    void liberarSinSerDuenyoFalla() {
        LockManager locks = new LockManager();
        Tcb[] h = hilos(2);

        locks.acquire(h[0], "contador");

        assertThrows(PosFault.class, () -> locks.release(h[1], "contador"));
        assertSame(h[0], locks.ownerOf("contador"));
    }

    @Test
    @DisplayName("dos hilos que se esperan mutuamente son un ciclo, y el ciclo se nombra")
    void dosCerrojosCruzadosSonUnCiclo() {
        LockManager locks = new LockManager();
        Tcb[] h = hilos(2);

        locks.acquire(h[0], "a");
        locks.acquire(h[1], "b");
        // Cada uno pide lo que tiene el otro.
        locks.acquire(h[0], "b");
        locks.acquire(h[1], "a");

        LockManager.Deadlock deadlock = locks.findDeadlock();

        assertTrue(deadlock != null, "tiene que ver el ciclo");
        assertEquals(2, deadlock.threads().size());
        assertTrue(deadlock.describe().contains("p1/h1"));
        assertTrue(deadlock.describe().contains("p1/h2"));
        assertTrue(deadlock.ring().contains("->"));
    }

    @Test
    @DisplayName("si nadie espera en circulo, no hay interbloqueo")
    void sinCicloNoHayInterbloqueo() {
        LockManager locks = new LockManager();
        Tcb[] h = hilos(2);

        locks.acquire(h[0], "a");
        locks.acquire(h[1], "a");

        assertNull(locks.findDeadlock(), "una espera en cadena no es un ciclo");
        assertTrue(locks.orphanLocks().isEmpty());
    }

    @Test
    @DisplayName("un cerrojo que se queda un hilo ya terminado se denuncia aparte")
    void cerrojoDeUnMuertoSeDenuncia() {
        LockManager locks = new LockManager();
        Tcb[] h = hilos(2);

        locks.acquire(h[0], "a");
        h[0].setState(State.TERMINATED);
        locks.acquire(h[1], "a");

        assertNull(locks.findDeadlock(), "no hay ciclo: el duenyo simplemente no va a volver");
        List<String> huerfanos = locks.orphanLocks();
        assertEquals(1, huerfanos.size());
        assertTrue(huerfanos.get(0).contains("'a'"), huerfanos.get(0));
        assertTrue(huerfanos.get(0).contains("termino"), huerfanos.get(0));
    }

    @Test
    @DisplayName("la tabla de cerrojos dice quien lo tiene y quien espera")
    void laTablaCuentaLaVerdad() {
        LockManager locks = new LockManager();
        Tcb[] h = hilos(2);

        locks.acquire(h[0], "contador");
        locks.acquire(h[1], "contador");

        String linea = locks.describe().get(0);
        assertTrue(linea.contains("'contador'"));
        assertTrue(linea.contains("p1/h1"));
        assertTrue(linea.contains("p1/h2"));
    }
}
