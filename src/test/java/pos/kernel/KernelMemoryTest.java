package pos.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pos.base.PosFault;
import pos.mm.MemoryScheme;
import pos.mm.MemorySetup;
import pos.mm.SegmentLayout;
import pos.proc.Assembler;
import pos.proc.Pcb;
import pos.proc.Program;
import pos.sched.FcfsScheduler;

@DisplayName("El nucleo da memoria a los procesos, la protege y la recupera")
class KernelMemoryTest {

    /** Guarda un valor en memoria y termina: prueba que la traduccion funciona de punta a punta. */
    private static final String GUARDA = """
            SET 7
            STORE 20
            LOAD 20
            EXIT
            """;

    private static final String FUERA_DE_RANGO = """
            SET 7
            STORE 40
            EXIT
            """;

    private static Program programa(String nombre, String fuente) {
        return Assembler.load(nombre, fuente);
    }

    @Test
    @DisplayName("al crear el proceso ya tiene espacio de direcciones")
    void elProcesoNaceConMemoria() {
        Kernel kernel = new Kernel(new FcfsScheduler(), MemorySetup.paged(1024, 32, 8));
        Pcb process = kernel.createProcess("uno", programa("uno", GUARDA));

        assertTrue(process.hasMemory());
        assertNotNull(process.space());
        assertEquals(32, process.memoryUnits());
        assertEquals(32, process.physicalUnits(), "4 paginas de 8 unidades");
        assertEquals(32, kernel.memory().physical().usedUnits());
    }

    @Test
    @DisplayName("el proceso ejecuta STORE y LOAD sin salirse de su espacio")
    void elProgramaUsaSuMemoria() {
        Kernel kernel = new Kernel(new FcfsScheduler(), MemorySetup.paged(1024, 32, 8));
        kernel.createProcess("uno", programa("uno", GUARDA));
        KernelReport report = kernel.run();

        KernelReport.Row row = report.rows().get(0);
        assertNull(row.failure(), "no deberia fallar: " + row.failure());
        assertEquals(4, row.cpu());
        assertEquals(1, report.healthyThreads());
    }

    @Test
    @DisplayName("escribir fuera del espacio logico mata al hilo, no al sistema")
    void escribirFueraDelEspacioEsUnFallo() {
        Kernel kernel = new Kernel(new FcfsScheduler(), MemorySetup.paged(1024, 32, 8));
        kernel.createProcess("uno", programa("uno", FUERA_DE_RANGO));
        KernelReport report = kernel.run();

        KernelReport.Row row = report.rows().get(0);
        assertNotNull(row.failure());
        assertTrue(row.failure().contains("40"), row.failure());
        assertEquals(0, report.healthyThreads());
    }

    @Test
    @DisplayName("al terminar el proceso se devuelve toda su memoria fisica")
    void laMemoriaSeRecuperaAlTerminar() {
        Kernel kernel = new Kernel(new FcfsScheduler(), MemorySetup.paged(1024, 32, 8));
        kernel.createProcess("uno", programa("uno", GUARDA));

        assertEquals(32, kernel.memory().physical().usedUnits());
        kernel.run();

        assertEquals(0, kernel.memory().physical().usedUnits());
        assertTrue(kernel.memory().physical().checkInvariants().isEmpty(),
                kernel.memory().physical().checkInvariants().toString());
    }

    @Test
    @DisplayName("un proceso que no cabe no se crea y no deja memoria tomada")
    void siNoCabeNoHayProceso() {
        // Cien unidades fisicas para procesos de sesenta: solo cabe uno.
        Kernel kernel = new Kernel(new FcfsScheduler(), MemorySetup.contiguous(100, 60));
        Pcb primero = kernel.createProcess("uno", programa("uno", GUARDA));
        assertEquals(1, primero.id());

        assertThrows(PosFault.class, () -> kernel.createProcess("dos", programa("dos", GUARDA)));

        assertEquals(1, kernel.processes().size());
        assertEquals(60, kernel.memory().physical().usedUnits(),
                "el intento fallido no puede dejar memoria tomada");
        assertTrue(kernel.memory().physical().checkInvariants().isEmpty(),
                kernel.memory().physical().checkInvariants().toString());
    }

    @Test
    @DisplayName("el mismo escenario da el mismo resultado con los tres esquemas")
    void losTresEsquemasDanLaMismaEjecucion() {
        long relojContiguo = -1;
        for (MemorySetup setup : ListaDeMemorias.todas()) {
            Kernel kernel = new Kernel(new FcfsScheduler(), setup);
            kernel.createProcess("uno", programa("uno", GUARDA));
            kernel.createProcess("dos", programa("dos", GUARDA));

            KernelReport report = kernel.run();
            assertEquals(2, report.healthyThreads(), "esquema " + setup.scheme().name());
            for (KernelReport.Row row : report.rows()) {
                assertNull(row.failure(), "esquema " + setup.scheme().name() + ": " + row.failure());
                assertEquals(4, row.cpu());
            }
            if (relojContiguo < 0) {
                relojContiguo = report.clock();
            }
            assertEquals(relojContiguo, report.clock(),
                    "el esquema " + setup.scheme().name() + " no puede cambiar el reloj");
            assertEquals(0, setup.physical().usedUnits(),
                    "esquema " + setup.scheme().name() + " dejo memoria sin devolver");
        }
    }

    /** Configuraciones equivalentes: 32 unidades logicas por proceso en cada esquema. */
    private static final class ListaDeMemorias {
        static java.util.List<MemorySetup> todas() {
            return java.util.List.of(
                    MemorySetup.contiguous(1024, 32),
                    MemorySetup.paged(1024, 32, 8),
                    MemorySetup.segmented(1024, SegmentLayout.even(32, 4)));
        }
    }

    @Test
    @DisplayName("el esquema por defecto reparte memoria y no la consume toda con un proceso")
    void elEsquemaPorDefectoDaParaVarios() {
        Kernel kernel = new Kernel(new FcfsScheduler(), 256);
        kernel.createProcess("uno", programa("uno", GUARDA));
        kernel.createProcess("dos", programa("dos", GUARDA));

        assertEquals(MemoryScheme.Contiguous.class, kernel.memory().scheme().getClass());
        assertEquals(0, kernel.memory().physical().freeUnits() % 256,
                "la memoria por defecto debe ser multiplo del tamano de un proceso");
        assertTrue(kernel.memory().physical().freeUnits() > 0);
    }
}
