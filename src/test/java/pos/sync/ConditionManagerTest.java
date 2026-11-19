package pos.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

@DisplayName("El gestor de condiciones despierta de uno en uno y obliga a readquirir el cerrojo")
class ConditionManagerTest {

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
    @DisplayName("la condicion nace en el primer wait y queda atada a su cerrojo")
    void naceAtadaASuCerrojo() {
        ConditionManager cond = new ConditionManager();
        Tcb h1 = hilos(1)[0];

        assertNull(cond.lockOf("hay_datos"));
        cond.waitOn(h1, "hay_datos", "contador");

        assertEquals("contador", cond.lockOf("hay_datos"));
        assertEquals(1, cond.waitingCount("hay_datos"));
        assertEquals(1, cond.count());
    }

    @Test
    @DisplayName("usarla con otro cerrojo es un fallo: la exclusion dejaria de significar algo")
    void otroCerrojoEsFallo() {
        ConditionManager cond = new ConditionManager();
        Tcb[] h = hilos(2);
        cond.waitOn(h[0], "hay_datos", "contador");

        PosFault fault = assertThrows(PosFault.class,
                () -> cond.waitOn(h[1], "hay_datos", "otro"));

        assertTrue(fault.getMessage().contains("contador"), fault.getMessage());
        assertTrue(fault.getMessage().contains("otro"), fault.getMessage());
        assertEquals(1, cond.waitingCount("hay_datos"), "el que no cuadra no entra en la cola");
    }

    @Test
    @DisplayName("signal despierta al primero y le apunta el cerrojo a readquirir")
    void senalDespiertaAlPrimero() {
        ConditionManager cond = new ConditionManager();
        Tcb[] h = hilos(2);
        cond.waitOn(h[0], "hay_datos", "contador");
        cond.waitOn(h[1], "hay_datos", "contador");

        Tcb despertado = cond.signal(h[0], "hay_datos");

        assertSame(h[0], despertado);
        assertEquals("contador", h[0].reacquireLock(), "vuelve a pedir el cerrojo que solto");
        assertNull(h[1].reacquireLock(), "el segundo no se toca");
        assertEquals(1, cond.waitingCount("hay_datos"));
    }

    @Test
    @DisplayName("una señal sin nadie esperando se pierde, no se guarda para luego")
    void senalSinEsperaSePierde() {
        ConditionManager cond = new ConditionManager();
        Tcb h1 = hilos(1)[0];

        assertNull(cond.signal(h1, "nadie_espera"));
        assertEquals(0, cond.count(), "señalar no crea la condicion");
    }

    @Test
    @DisplayName("broadcast despierta a todos y los devuelve contados")
    void broadcastDespiertaATodos() {
        ConditionManager cond = new ConditionManager();
        Tcb[] h = hilos(3);
        cond.waitOn(h[0], "turno", "contador");
        cond.waitOn(h[1], "turno", "contador");
        cond.waitOn(h[2], "turno", "contador");

        int cuantos = cond.broadcast(h[0], "turno");

        assertEquals(3, cuantos);
        assertEquals(0, cond.waitingCount("turno"));
        for (Tcb thread : h) {
            assertEquals("contador", thread.reacquireLock(), thread.id());
        }
        assertEquals(List.of(h[0], h[1], h[2]), cond.drainGranted(), "y en orden de llegada");
    }

    @Test
    @DisplayName("el que se queda esperando no se ha despertado todavia")
    void elQueEsperaNoEstaDespierto() {
        ConditionManager cond = new ConditionManager();
        Tcb h1 = hilos(1)[0];

        cond.waitOn(h1, "hay_datos", "contador");

        assertNull(h1.reacquireLock(), "no ha habido señal");
        assertTrue(cond.drainGranted().isEmpty());
    }

    @Test
    @DisplayName("la tabla dice la condicion, su cerrojo y quien espera")
    void describeLaTabla() {
        ConditionManager cond = new ConditionManager();
        Tcb h1 = hilos(1)[0];
        cond.waitOn(h1, "hay_datos", "contador");

        List<String> lines = cond.describe();

        assertEquals(1, lines.size());
        assertTrue(lines.get(0).contains("hay_datos"), lines.get(0));
        assertTrue(lines.get(0).contains("contador"), lines.get(0));
        assertTrue(lines.get(0).contains(h1.id()), lines.get(0));
        assertEquals(List.of(h1.id() + " espera la condicion 'hay_datos' (cerrojo 'contador')"),
                cond.describeBlocked());
    }
}
