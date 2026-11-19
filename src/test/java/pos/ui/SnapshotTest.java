package pos.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import pos.kernel.Kernel;
import pos.launch.UiDemo;
import pos.phys.PhysicalMemory;
import pos.proc.State;

/**
 * Pruebas de la foto que alimenta la ventana.
 *
 * La interfaz no se prueba mirandola. Se prueba aqui: el escenario termina, la
 * foto final cuenta lo mismo que el informe del nucleo y el mapa de memoria no
 * inventa duennos. Si esto esta bien, lo que queda por depurar es pintura.
 */
class SnapshotTest {

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("el escenario de la ventana termina y deja fotos en el feed")
    void elEscenarioTerminaDejaFotos() {
        UiFeed feed = new UiFeed();
        Kernel kernel = UiDemo.runHeadless(feed);

        Snapshot last = feed.latest();
        assertNotNull(last, "el feed tiene que tener al menos la ultima foto");
        assertTrue(last.finished(), "el escenario tiene que acabar solo");
        assertTrue(feed.turns() > 1, "tiene que haber mas de una foto: " + feed.turns());

        // Los cinco procesos y sus seis hilos, todos cerrados.
        assertEquals(5, last.processes().size());
        // Dos hilos en 'contienda', dos en 'cola' y uno en cada uno de los otros tres.
        assertEquals(7, last.threads().size());
        assertEquals(7, last.count(State.TERMINATED.etiqueta()));

        // Ningun hilo se quedo a medias ni fallo.
        for (Snapshot.ThreadInfo thread : last.threads()) {
            assertTrue(thread.failure().isEmpty(),
                    "el hilo " + thread.id() + " acabo mal: " + thread.failure());
            assertTrue(thread.cpu() > 0, "el hilo " + thread.id() + " no ejecuto nada");
        }
        assertTrue(last.cpuTicks() > 0);
        assertEquals(kernel.clock(), last.clock());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("la foto final coincide con el informe del nucleo")
    void laFotoCoincideConElInforme() {
        UiFeed feed = new UiFeed();
        Kernel kernel = UiDemo.runHeadless(feed);
        Snapshot last = feed.latest();

        for (int i = 0; i < last.threads().size(); i++) {
            Snapshot.ThreadInfo foto = last.threads().get(i);
            var fila = kernel.report().rows().get(i);
            assertEquals(fila.thread(), foto.id());
            assertEquals(fila.cpu(), foto.cpu());
            assertEquals(fila.waitTicks(), foto.waitTicks());
            assertEquals(fila.turnaround(), foto.turnaround());
            assertEquals(fila.state(), foto.state());
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("el mapa de memoria no se inventa duennos ni deja unidades sin decidir")
    void elMapaEsCoherente() {
        UiFeed feed = new UiFeed();
        Kernel kernel = UiDemo.runHeadless(feed);
        Snapshot last = feed.latest();
        Snapshot.MemoryMap memory = last.memory();

        assertEquals(192, memory.size());
        assertEquals(0, memory.used(), "al cerrarse el sistema no queda memoria ocupada");
        assertEquals(192, memory.free());
        for (int unit = 0; unit < memory.size(); unit++) {
            assertEquals(PhysicalMemory.FREE, memory.ownerAt(unit));
        }

        // Una copia no puede alterar la foto: el array es del que la pide.
        int[] copia = memory.owners();
        copia[0] = 99;
        assertEquals(PhysicalMemory.FREE, memory.ownerAt(0));
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("los mecanismos de sincronizacion llegan a la foto por su nombre")
    void losMecanismosLleganANominados() {
        UiFeed feed = new UiFeed();
        UiDemo.runHeadless(feed);
        Snapshot last = feed.latest();

        assertEquals(1, last.sync().locks().size());
        assertEquals("cuenta", last.sync().locks().get(0).name());
        assertNull(last.sync().deadlock(), "el escenario no debe acabar en interbloqueo");

        assertEquals(2, last.sync().semaphores().size());
        assertEquals("hueco", last.sync().semaphores().get(0).name());
        assertEquals("dato", last.sync().semaphores().get(1).name());
        assertEquals(1, last.sync().semaphores().get(0).value(),
                "el productor devuelve el permiso al consumir: el hueco acaba libre");
        assertEquals(0, last.sync().semaphores().get(1).value(),
                "no puede quedar dato sin consumir");
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("la terminal deja llamadas contadas y una traza acotada")
    void laTerminalDejaLlamadasYTraza() {
        UiFeed feed = new UiFeed();
        UiDemo.runHeadless(feed);
        Snapshot last = feed.latest();

        assertFalse(last.calls().empty(), "la terminal tiene que llamar al nucleo");
        assertTrue(last.calls().total() > 0);
        assertTrue(last.calls().ticks() > 0);

        assertFalse(last.trace().isEmpty());
        assertTrue(last.trace().size() <= Snapshotter.TRACE_LINES,
                "la foto no puede arrastrar la traza entera: " + last.trace().size());
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("el feed guarda una serie temporal acotada")
    void laSerieEsAcotada() {
        UiFeed feed = new UiFeed();
        UiDemo.runHeadless(feed);

        assertFalse(feed.history().isEmpty());
        assertTrue(feed.history().size() <= UiFeed.HISTORY);
        long[] ultima = feed.history().get(feed.history().size() - 1);
        assertEquals(feed.latest().clock(), ultima[0]);
        assertEquals(feed.latest().cpuTicks(), ultima[1]);

        feed.reset();
        assertNull(feed.latest());
        assertTrue(feed.history().isEmpty());
    }
}
