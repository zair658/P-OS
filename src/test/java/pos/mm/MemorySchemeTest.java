package pos.mm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pos.alloc.BestFit;
import pos.alloc.SlotManager;
import pos.base.PosFault;
import pos.phys.PhysicalMemory;

@DisplayName("Los tres esquemas de memoria traducen, protegen y liberan igual")
class MemorySchemeTest {

    private static final int LOGICAS = 250;
    private static final int PAGINA = 16;

    private static final SlotManager SLOTS = new BestFit();

    /** Memoria con las primeras unidades ocupadas: la traduccion no puede ser la identidad. */
    private static PhysicalMemory memoriaConPrefijo(int prefijo) {
        PhysicalMemory memory = new PhysicalMemory(4096);
        memory.allocate(99, 0, prefijo);
        return memory;
    }

    private static List<MemoryScheme> losTresEsquemas() {
        return List.of(
                new MemoryScheme.Contiguous(),
                new MemoryScheme.Paged(PAGINA),
                new MemoryScheme.Segmented(
                        SegmentLayout.stacked(new String[] { "codigo", "datos" }, new int[] { 150, 100 })));
    }

    // ------------------------------------------------------------------
    // La garantia de la fase
    // ------------------------------------------------------------------

    @Test
    @DisplayName("el mismo programa guarda y recupera el mismo valor en los tres esquemas")
    void mismoValorEnLosTresEsquemas() {
        for (MemoryScheme scheme : losTresEsquemas()) {
            PhysicalMemory memory = memoriaConPrefijo(40);
            AddressSpace space = scheme.attach(memory, SLOTS, 1, "prueba", LOGICAS);

            space.store(200, 42);
            assertEquals(42, space.load(200), "esquema " + scheme.name());
            assertEquals(LOGICAS, space.logicalUnits(), "esquema " + scheme.name());
        }
    }

    @Test
    @DisplayName("los tres esquemas colocan al proceso fuera del prefijo ya ocupado")
    void ningunoPisaLaMemoriaAjena() {
        for (MemoryScheme scheme : losTresEsquemas()) {
            PhysicalMemory memory = memoriaConPrefijo(40);
            AddressSpace space = scheme.attach(memory, SLOTS, 1, "prueba", LOGICAS);

            space.store(0, 7);
            assertTrue(space.physicalAddressOf(0) >= 40, "esquema " + scheme.name());
            assertEquals(99, memory.ownerAt(39), "el prefijo cambio de dueno con " + scheme.name());
        }
    }

    // ------------------------------------------------------------------
    // Contiguo
    // ------------------------------------------------------------------

    @Test
    @DisplayName("el contiguo traduce con una suma desde el registro base")
    void contiguoTraduceConBase() {
        PhysicalMemory memory = memoriaConPrefijo(40);
        ContiguousSpace space = (ContiguousSpace) new MemoryScheme.Contiguous()
                .attach(memory, SLOTS, 1, "prueba", LOGICAS);

        assertEquals(40, space.base());
        assertEquals(40 + LOGICAS, space.limit());
        assertEquals(240, space.physicalAddressOf(200));
        assertEquals(LOGICAS, space.physicalUnits());
        assertEquals(0, space.wastedUnits());
    }

    @Test
    @DisplayName("el contiguo no entra si ningun hueco alcanza el tamano pedido")
    void contiguoFallaSinHuecoSuficiente() {
        PhysicalMemory memory = new PhysicalMemory(128);
        AddressSpace primero = new MemoryScheme.Contiguous().attach(memory, SLOTS, 1, "a", 50);
        new MemoryScheme.Contiguous().attach(memory, SLOTS, 2, "b", 60);
        primero.release();

        int libresAntes = memory.freeUnits();
        PosFault fault = assertThrows(PosFault.class,
                () -> new MemoryScheme.Contiguous().attach(memory, SLOTS, 3, "c", 60));

        assertTrue(fault.getMessage().contains("60"), fault.getMessage());
        assertEquals(libresAntes, memory.freeUnits(),
                "una reserva que falla no puede dejar memoria tomada");
    }

    // ------------------------------------------------------------------
    // Paginacion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("la paginacion desperdicia solo el resto de la ultima pagina")
    void paginacionDesperdiciaElRestoDeLaUltimaPagina() {
        PhysicalMemory memory = memoriaConPrefijo(40);
        PagedSpace space = (PagedSpace) new MemoryScheme.Paged(PAGINA)
                .attach(memory, SLOTS, 1, "prueba", LOGICAS);

        assertEquals(16, space.pageCount());
        assertEquals(256, space.physicalUnits());
        assertEquals(6, space.wastedUnits());
    }

    @Test
    @DisplayName("cada pagina va a un marco distinto y la traduccion usa pagina y desplazamiento")
    void paginacionTraduceConPaginaYDesplazamiento() {
        PhysicalMemory memory = memoriaConPrefijo(40);
        PagedSpace space = (PagedSpace) new MemoryScheme.Paged(PAGINA)
                .attach(memory, SLOTS, 1, "prueba", LOGICAS);

        // La direccion 200 esta en la pagina 12, desplazamiento 8.
        int marco = space.frameOf(12);
        assertEquals(marco + 8, space.physicalAddressOf(200));
        assertEquals(marco + 8, space.physicalAddressOf(200));

        // Dos paginas distintas no pueden compartir marco.
        for (int page = 0; page < space.pageCount(); page++) {
            for (int other = page + 1; other < space.pageCount(); other++) {
                assertNotEquals(space.frameOf(page), space.frameOf(other),
                        "las paginas " + page + " y " + other + " comparten marco");
            }
        }
    }

    @Test
    @DisplayName("la paginacion entra donde el contiguo no cabe")
    void paginacionEntraDondeElContiguoNo() {
        PhysicalMemory memory = new PhysicalMemory(128);
        AddressSpace primero = new MemoryScheme.Contiguous().attach(memory, SLOTS, 1, "a", 50);
        new MemoryScheme.Contiguous().attach(memory, SLOTS, 2, "b", 60);
        primero.release();

        // Quedan dos huecos: [0, 50) y [110, 128). Ninguno sirve para 60 de golpe.
        assertThrows(PosFault.class,
                () -> new MemoryScheme.Contiguous().attach(memory, SLOTS, 3, "c", 60));

        AddressSpace paginado = new MemoryScheme.Paged(PAGINA).attach(memory, SLOTS, 4, "c", 60);
        assertEquals(4, paginado.reservedSpans().size());
        assertEquals(64, paginado.physicalUnits());

        paginado.store(59, 9);
        assertEquals(9, paginado.load(59));
    }

    // ------------------------------------------------------------------
    // Segmentacion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("cada segmento tiene su base fisica y se traduce por desplazamiento")
    void segmentacionTraducePorSegmento() {
        PhysicalMemory memory = memoriaConPrefijo(40);
        SegmentedSpace space = (SegmentedSpace) new MemoryScheme.Segmented(
                SegmentLayout.stacked(new String[] { "codigo", "datos" }, new int[] { 150, 100 }))
                .attach(memory, SLOTS, 1, "prueba", LOGICAS);

        assertEquals(40, space.baseOf(0));
        assertEquals(190, space.baseOf(1));
        // La direccion 200 esta en 'datos', desplazamiento 50.
        assertEquals(240, space.physicalAddressOf(200));
        assertEquals(250, space.physicalUnits());
        assertEquals(0, space.wastedUnits());
    }

    @Test
    @DisplayName("un hueco entre segmentos no es direccionable")
    void huecoEntreSegmentosFalla() {
        SegmentLayout layout = new SegmentLayout(List.of(
                new Segment("codigo", 0, 10),
                new Segment("datos", 20, 10)));
        PhysicalMemory memory = new PhysicalMemory(256);
        AddressSpace space = new MemoryScheme.Segmented(layout)
                .attach(memory, SLOTS, 1, "prueba", 30);

        space.store(5, 3);
        assertEquals(3, space.load(5));

        PosFault fault = assertThrows(PosFault.class, () -> space.load(15));
        assertTrue(fault.getMessage().contains("no pertenece a ningun segmento"), fault.getMessage());
    }

    @Test
    @DisplayName("el reparto en segmentos manda sobre el tamano logico declarado")
    void segmentacionExigeQueElRepartoCuadre() {
        SegmentLayout layout = SegmentLayout.even(64, 4);
        PhysicalMemory memory = new PhysicalMemory(256);

        assertThrows(IllegalArgumentException.class,
                () -> new MemoryScheme.Segmented(layout).attach(memory, SLOTS, 1, "prueba", 60));
        assertEquals(256, memory.freeUnits(), "la comprobacion tiene que ser antes de reservar");
    }

    // ------------------------------------------------------------------
    // Proteccion y liberacion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ningun esquema deja leer ni escribir fuera del espacio logico")
    void direccionFueraDelEspacioFalla() {
        for (MemoryScheme scheme : losTresEsquemas()) {
            PhysicalMemory memory = memoriaConPrefijo(40);
            AddressSpace space = scheme.attach(memory, SLOTS, 1, "prueba", LOGICAS);

            PosFault lectura = assertThrows(PosFault.class, () -> space.load(LOGICAS),
                    "esquema " + scheme.name());
            assertTrue(lectura.getMessage().contains(String.valueOf(LOGICAS)), lectura.getMessage());
            assertThrows(PosFault.class, () -> space.store(-1, 1), "esquema " + scheme.name());
        }
    }

    @Test
    @DisplayName("liberar devuelve exactamente lo reservado y deja la memoria como estaba")
    void liberarDevuelveTodo() {
        for (MemoryScheme scheme : losTresEsquemas()) {
            PhysicalMemory memory = memoriaConPrefijo(40);
            AddressSpace space = scheme.attach(memory, SLOTS, 1, "prueba", LOGICAS);

            space.store(10, 5);
            space.release();

            assertEquals(0, space.physicalUnits());
            assertEquals(40, memory.usedUnits(), "tras liberar solo queda el prefijo con " + scheme.name());
            assertEquals(4096 - 40, memory.freeUnits());
            assertTrue(memory.checkInvariants().isEmpty(), memory.checkInvariants().toString());
        }
    }

    /**
     * Un caso de prueba: el esquema y el tamano logico que le corresponde.
     *
     * Hace falta emparejarlos porque en la segmentacion el tamano no se elige:
     * sale del reparto, y declarar otro distinto es un error.
     */
    private record Caso(MemoryScheme scheme, int units) {
    }

    private static List<Caso> casos(int units) {
        return List.of(
                new Caso(new MemoryScheme.Contiguous(), units),
                new Caso(new MemoryScheme.Paged(PAGINA), units),
                new Caso(new MemoryScheme.Segmented(SegmentLayout.even(units, 4)), units));
    }

    @Test
    @DisplayName("dos procesos no se ven la memoria aunque usen el mismo esquema")
    void dosProcesosNoCompartenMemoria() {
        for (Caso caso : casos(100)) {
            PhysicalMemory memory = new PhysicalMemory(4096);
            AddressSpace uno = caso.scheme().attach(memory, SLOTS, 1, "uno", caso.units());
            AddressSpace dos = caso.scheme().attach(memory, SLOTS, 2, "dos", caso.units());

            uno.store(0, 255);
            assertEquals(0, dos.load(0), "esquema " + caso.scheme().name());
            assertNotEquals(uno.physicalAddressOf(0), dos.physicalAddressOf(0),
                    "esquema " + caso.scheme().name());
        }
    }

    @Test
    @DisplayName("liberar un proceso no toca al de al lado")
    void liberarUnoNoTocaAlOtro() {
        PhysicalMemory memory = new PhysicalMemory(4096);
        AddressSpace uno = new MemoryScheme.Paged(PAGINA).attach(memory, SLOTS, 1, "uno", 64);
        AddressSpace dos = new MemoryScheme.Paged(PAGINA).attach(memory, SLOTS, 2, "dos", 64);

        uno.store(3, 111);
        dos.store(3, 222);

        uno.release();
        assertEquals(222, dos.load(3));
        assertTrue(memory.checkInvariants().isEmpty(), memory.checkInvariants().toString());
    }

    @Test
    @DisplayName("un espacio sin liberar bloquea la memoria y se nota en el mapa")
    void sinLiberarLaMemoriaSigueOcupada() {
        PhysicalMemory memory = new PhysicalMemory(256);
        AddressSpace space = new MemoryScheme.Contiguous().attach(memory, SLOTS, 1, "uno", 64);

        assertEquals(64, memory.usedUnits());
        assertFalse(memory.isFree(0, 1));

        space.release();
        assertEquals(0, memory.usedUnits());
        assertTrue(memory.isFree(0, 256));
    }
}
