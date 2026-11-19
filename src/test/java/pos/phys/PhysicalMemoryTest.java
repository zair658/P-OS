package pos.phys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import pos.base.FreeSpan;

/** Pruebas de la memoria fisica real por bloques. */
class PhysicalMemoryTest {

    @Test
    @DisplayName("una memoria recien creada esta entera libre y cumple sus invariantes")
    void memoriaNuevaEstaVacia() {
        PhysicalMemory memory = new PhysicalMemory(256);

        assertEquals(256, memory.size());
        assertEquals(0, memory.usedUnits());
        assertEquals(256, memory.freeUnits());
        assertEquals(List.of(new FreeSpan(0, 256)), memory.freeSpans());
        assertTrue(memory.checkInvariants().isEmpty(), "los invariantes deben cumplirse al crear");
    }

    @Test
    @DisplayName("asignar marca el propietario y permite escribir y recuperar el mismo valor")
    void escrituraYLecturaDevuelvenElMismoValor() {
        PhysicalMemory memory = new PhysicalMemory(256);
        memory.allocate(7, 64, 32);

        memory.writeUnit(70, 0xAB);

        assertEquals(7, memory.ownerAt(70));
        assertEquals(0xAB, memory.readUnit(70));
    }

    @Test
    @DisplayName("una asignacion nueva no deja ver los datos del proceso anterior")
    void laAsignacionLimpiaElContenidoAnterior() {
        PhysicalMemory memory = new PhysicalMemory(128);
        memory.allocate(1, 0, 16);
        memory.writeUnit(0, 0xFF);
        memory.writeUnit(15, 0xEE);

        memory.releaseAll(1);
        memory.allocate(2, 0, 16);

        // Se comprueba con peekUnit porque readUnit exige propietario y aqui lo
        // que interesa es el contenido fisico, no el control de acceso.
        assertEquals(0, memory.peekUnit(0), "el primer byte debe estar a cero");
        assertEquals(0, memory.peekUnit(15), "el ultimo byte debe estar a cero");
    }

    @Test
    @DisplayName("no se puede asignar sobre un tramo ya ocupado")
    void asignacionSolapadaSeRechaza() {
        PhysicalMemory memory = new PhysicalMemory(256);
        memory.allocate(1, 0, 64);

        assertThrows(IllegalStateException.class, () -> memory.allocate(2, 32, 64),
                "un solapamiento debe ser un fallo, no un aviso");
        assertEquals(64, memory.usedUnits(), "el intento fallido no debe cambiar la ocupacion");
    }

    @Test
    @DisplayName("un tramo fuera de la memoria se rechaza antes de tocar nada")
    void tramoFueraDeRangoSeRechaza() {
        PhysicalMemory memory = new PhysicalMemory(256);

        assertThrows(IllegalArgumentException.class, () -> memory.allocate(1, 200, 100));
        assertThrows(IllegalArgumentException.class, () -> memory.allocate(1, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> memory.allocate(1, 0, 0));
        assertEquals(0, memory.usedUnits());
    }

    @Test
    @DisplayName("escribir o leer memoria libre es un fallo del sistema")
    void accesoAMemoriaLibreSeRechaza() {
        PhysicalMemory memory = new PhysicalMemory(64);

        assertThrows(IllegalStateException.class, () -> memory.writeUnit(10, 1));
        assertThrows(IllegalStateException.class, () -> memory.readUnit(10));
    }

    @Test
    @DisplayName("liberar unidades contiguas fusiona los huecos sin operacion aparte")
    void liberarFusionaLosHuecos() {
        PhysicalMemory memory = new PhysicalMemory(256);
        memory.allocate(1, 0, 64);
        memory.allocate(2, 64, 64);

        assertEquals(List.of(new FreeSpan(128, 128)), memory.freeSpans());

        memory.releaseAll(1);
        assertEquals(List.of(new FreeSpan(0, 64), new FreeSpan(128, 128)), memory.freeSpans());

        memory.releaseAll(2);
        assertEquals(List.of(new FreeSpan(0, 256)), memory.freeSpans(),
                "al liberar el tramo intermedio los tres huecos deben quedar en uno");
    }

    @Test
    @DisplayName("liberar un tramo que no es del proceso se rechaza")
    void liberarTramoAjenoSeRechaza() {
        PhysicalMemory memory = new PhysicalMemory(128);
        memory.allocate(1, 0, 64);

        assertThrows(IllegalStateException.class, () -> memory.release(2, 0, 64));
    }

    @Test
    @DisplayName("el mayor hueco libre es el que se informa")
    void mayorHuecoLibre() {
        PhysicalMemory memory = new PhysicalMemory(256);
        memory.allocate(1, 0, 32);
        memory.allocate(2, 64, 32);

        assertEquals(new FreeSpan(96, 160), memory.largestFreeSpan());
    }

    @Test
    @DisplayName("los invariantes se cumplen tras una secuencia de asignaciones y liberaciones")
    void invariantesTrasOperacionesMixtas() {
        PhysicalMemory memory = new PhysicalMemory(512);
        memory.allocate(1, 0, 64);
        memory.allocate(2, 128, 32);
        memory.allocate(3, 400, 112);
        memory.releaseAll(2);
        memory.allocate(4, 128, 16);

        List<String> failures = memory.checkInvariants();

        assertTrue(failures.isEmpty(), "invariantes incumplidos: " + failures);
        assertEquals(64 + 16 + 112, memory.usedUnits());
        assertEquals(512 - (64 + 16 + 112), memory.freeUnits());
    }

    @Test
    @DisplayName("el mapa de ocupacion dibuja una marca por grupo de unidades")
    void mapaDeOcupacion() {
        PhysicalMemory memory = new PhysicalMemory(256);
        memory.allocate(1, 0, 64);
        memory.allocate(2, 128, 64);

        assertEquals("1.2.", memory.render(64), "el punto es libre y el digito es el proceso");
    }
}
