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
import pos.proc.Tcb;

@DisplayName("El gestor de semaforos reparte permisos de uno en uno y sin colarse")
class SemaphoreManagerTest {

    /** Programa de relleno: al gestor no le importa lo que ejecuten los hilos. */
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
    @DisplayName("nace con el valor que se le dice")
    void naceConSuValor() {
        SemaphoreManager sem = new SemaphoreManager();
        Tcb h1 = hilos(1)[0];

        sem.init(h1, "huecos", 3);

        assertEquals(3, sem.valueOf("huecos"));
        assertEquals(0, sem.waitingCount("huecos"));
        assertEquals(1, sem.count());
    }

    @Test
    @DisplayName("con permisos disponibles el hilo los toma y sigue")
    void tomaPermisoYSigue() {
        SemaphoreManager sem = new SemaphoreManager();
        Tcb h1 = hilos(1)[0];
        sem.init(h1, "huecos", 2);

        assertTrue(sem.waitOn(h1, "huecos"));
        assertEquals(1, sem.valueOf("huecos"));
        assertTrue(sem.waitOn(h1, "huecos"));
        assertEquals(0, sem.valueOf("huecos"));
    }

    @Test
    @DisplayName("agotados los permisos el hilo queda en la cola, no se queda con uno de mas")
    void sinPermisosSeEspera() {
        SemaphoreManager sem = new SemaphoreManager();
        Tcb[] h = hilos(2);
        sem.init(h[0], "lleno", 0);

        assertFalse(sem.waitOn(h[0], "lleno"));
        assertFalse(sem.waitOn(h[1], "lleno"));

        assertEquals(0, sem.valueOf("lleno"), "el contador no baja de cero");
        assertEquals(2, sem.waitingCount("lleno"));
    }

    @Test
    @DisplayName("devolver un permiso con alguien esperando es cederselo, no subir el contador")
    void devolverConEsperaEsCeder() {
        SemaphoreManager sem = new SemaphoreManager();
        Tcb[] h = hilos(2);
        sem.init(h[0], "lleno", 0);
        sem.waitOn(h[0], "lleno");

        Tcb despertado = sem.post(h[1], "lleno");

        assertSame(h[0], despertado);
        assertEquals(0, sem.valueOf("lleno"), "el permiso pasa de mano, no se duplica");
        assertEquals(0, sem.waitingCount("lleno"));
        assertEquals(List.of(h[0]), sem.drainGranted());
        assertTrue(sem.drainGranted().isEmpty(), "la lista de concedidos se entrega entera");
    }

    @Test
    @DisplayName("devolver un permiso sin nadie esperando sube el contador")
    void devolverSinEsperaSube() {
        SemaphoreManager sem = new SemaphoreManager();
        Tcb[] h = hilos(2);
        sem.init(h[0], "lleno", 0);

        assertNull(sem.post(h[1], "lleno"));

        assertEquals(1, sem.valueOf("lleno"));
    }

    @Test
    @DisplayName("la cesion respeta el orden de llegada")
    void laCesionEsFifo() {
        SemaphoreManager sem = new SemaphoreManager();
        Tcb[] h = hilos(3);
        sem.init(h[0], "turno", 0);
        sem.waitOn(h[0], "turno");
        sem.waitOn(h[1], "turno");
        sem.waitOn(h[2], "turno");

        assertSame(h[0], sem.post(h[0], "turno"));
        assertSame(h[1], sem.post(h[0], "turno"));
        assertSame(h[2], sem.post(h[0], "turno"));
        assertEquals(0, sem.valueOf("turno"), "los tres permisos eran los de los tres hilos");
    }

    @Test
    @DisplayName("usar un semaforo que no existe es un fallo, no una espera eterna")
    void usarSemaforoInexistenteEsFallo() {
        SemaphoreManager sem = new SemaphoreManager();
        Tcb h1 = hilos(1)[0];

        PosFault fault = assertThrows(PosFault.class, () -> sem.waitOn(h1, "fantasma"));

        assertTrue(fault.getMessage().contains("fantasma"), fault.getMessage());
        assertTrue(fault.getMessage().contains("SEM_INIT"), fault.getMessage());
    }

    @Test
    @DisplayName("un semaforo no se puede reiniciar: escondería permisos en vuelo")
    void noSePuedeReiniciar() {
        SemaphoreManager sem = new SemaphoreManager();
        Tcb h1 = hilos(1)[0];
        sem.init(h1, "huecos", 1);

        assertThrows(PosFault.class, () -> sem.init(h1, "huecos", 0));
    }

    @Test
    @DisplayName("no se puede crear con permisos negativos")
    void noNaceNegativo() {
        SemaphoreManager sem = new SemaphoreManager();
        Tcb h1 = hilos(1)[0];

        assertThrows(PosFault.class, () -> sem.init(h1, "huecos", -2));
    }

    @Test
    @DisplayName("la tabla dice valor y quienes esperan")
    void describeLaTabla() {
        SemaphoreManager sem = new SemaphoreManager();
        Tcb[] h = hilos(2);
        sem.init(h[0], "huecos", 0);
        sem.waitOn(h[1], "huecos");

        List<String> lines = sem.describe();

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("huecos"), lines.get(0));
        assertTrue(lines.get(0).contains("valor 0"), lines.get(0));
        assertTrue(lines.get(0).contains(h[1].id()), lines.get(0));
        assertEquals(List.of(h[1].id() + " espera el semaforo 'huecos'"), sem.describeBlocked());
    }
}
