package pos.mm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pos.alloc.BestFit;
import pos.base.PosFault;
import pos.fs.AllocationMethod;
import pos.fs.FileSystem;
import pos.fs.FsLayout;
import pos.phys.PhysicalMemory;

@DisplayName("Las paginas por demanda fallan, se expulsan y vuelven con su contenido")
class DemandPagingTest {

    private static final int PAGINA = 16;
    private static final int BLOQUE = 64;
    private static final int BLOQUES = 64;
    private static final int RANURAS = 16;

    /** Traza clasica de cinco paginas: doce accesos, la que demuestra la anomalia. */
    private static final int[] TRAZA = { 1, 2, 3, 4, 1, 2, 5, 1, 2, 3, 4, 5 };

    /** Paginas que direcciona la traza, contando la cero. */
    private static final int PAGINAS = 6;

    private static FileSystem disco() {
        return FileSystem.format(FsLayout.standard(BLOQUES, BLOQUE),
                new AllocationMethod.Indexed(), new BestFit());
    }

    /** Espacio con area de intercambio nueva sobre un disco nuevo. */
    private static DemandPagedSpace espacio(PagePolicy policy, int marcos, int paginas) {
        FileSystem fs = disco();
        SwapArea swap = SwapArea.format(fs, PAGINA, RANURAS);
        return (DemandPagedSpace) new MemoryScheme.DemandPaged(PAGINA, marcos, policy, swap, null)
                .attach(new PhysicalMemory(marcos * PAGINA), new BestFit(), 1, "prueba",
                        paginas * PAGINA);
    }

    private static List<Integer> futuro() {
        List<Integer> direcciones = new ArrayList<>();
        for (int pagina : TRAZA) {
            direcciones.add(pagina * PAGINA);
        }
        return direcciones;
    }

    /** Pasa la traza escribiendo en cada pagina y devuelve cuantos fallos cuesta. */
    private static long fallosDeLaTraza(PagePolicy policy, int marcos) {
        FileSystem fs = disco();
        SwapArea swap = SwapArea.format(fs, PAGINA, RANURAS);
        DemandPagedSpace space = (DemandPagedSpace) new MemoryScheme.DemandPaged(
                PAGINA, marcos, policy, swap, futuro())
                .attach(new PhysicalMemory(marcos * PAGINA), new BestFit(), 1, "traza",
                        PAGINAS * PAGINA);
        for (int pagina : TRAZA) {
            space.store(pagina * PAGINA, pagina);
        }
        assertTrue(space.invariantFailures().isEmpty(), "los invariantes tienen que cuadrar");
        return space.faults();
    }

    // ------------------------------------------------------------------
    // Fallos y politicas
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Politicas de reemplazo")
    class Politicas {

        @Test
        @DisplayName("FIFO, LRU y OPT dan los fallos que dice la teoria sobre la traza clasica")
        void fallosEsperados() {
            assertEquals(9, fallosDeLaTraza(PagePolicy.FIFO, 3));
            assertEquals(10, fallosDeLaTraza(PagePolicy.LRU, 3));
            assertEquals(7, fallosDeLaTraza(PagePolicy.OPT, 3));
        }

        @Test
        @DisplayName("FIFO sufre la anomalia de Belady: cuatro marcos fallan mas que tres")
        void anomaliaDeBelady() {
            long conTres = fallosDeLaTraza(PagePolicy.FIFO, 3);
            long conCuatro = fallosDeLaTraza(PagePolicy.FIFO, 4);
            assertEquals(9, conTres);
            assertEquals(10, conCuatro);
            assertTrue(conCuatro > conTres, "mas marcos tienen que empeorar a FIFO en esta traza");
        }

        @Test
        @DisplayName("LRU y OPT no empeoran al crecer la memoria: son politicas de pila")
        void sinAnomalia() {
            assertTrue(fallosDeLaTraza(PagePolicy.LRU, 4) <= fallosDeLaTraza(PagePolicy.LRU, 3));
            assertTrue(fallosDeLaTraza(PagePolicy.OPT, 4) <= fallosDeLaTraza(PagePolicy.OPT, 3));
        }

        @Test
        @DisplayName("OPT acierta con el futuro de la traza y no registra desvios")
        void optSinDesvios() {
            FileSystem fs = disco();
            SwapArea swap = SwapArea.format(fs, PAGINA, RANURAS);
            DemandPagedSpace space = (DemandPagedSpace) new MemoryScheme.DemandPaged(
                    PAGINA, 3, PagePolicy.OPT, swap, futuro())
                    .attach(new PhysicalMemory(3 * PAGINA), new BestFit(), 1, "traza",
                            PAGINAS * PAGINA);
            for (int pagina : TRAZA) {
                space.store(pagina * PAGINA, pagina);
            }
            assertEquals(0, space.traceMisses());
        }

        @Test
        @DisplayName("OPT exige la traza futura y sin ella no se construye")
        void optNecesitaTraza() {
            SwapArea swap = SwapArea.format(disco(), PAGINA, RANURAS);
            assertThrows(IllegalArgumentException.class,
                    () -> new MemoryScheme.DemandPaged(PAGINA, 3, PagePolicy.OPT, swap, null));
            assertThrows(IllegalArgumentException.class,
                    () -> new MemoryScheme.DemandPaged(PAGINA, 3, PagePolicy.OPT, swap, List.of()));
            assertThrows(IllegalArgumentException.class,
                    () -> new MemoryScheme.DemandPaged(PAGINA, 0, PagePolicy.FIFO, swap, null));
        }

        @Test
        @DisplayName("un desvio en la traza se cuenta en vez de romper la ejecucion")
        void desvioSeCuenta() {
            SwapArea swap = SwapArea.format(disco(), PAGINA, RANURAS);
            DemandPagedSpace space = (DemandPagedSpace) new MemoryScheme.DemandPaged(
                    PAGINA, 2, PagePolicy.OPT, swap, List.of(0, PAGINA))
                    .attach(new PhysicalMemory(2 * PAGINA), new BestFit(), 1, "traza",
                            PAGINAS * PAGINA);
            space.load(3 * PAGINA);
            assertTrue(space.traceMisses() > 0, "la direccion no estaba en la traza");
        }
    }

    // ------------------------------------------------------------------
    // Intercambio
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Area de intercambio")
    class Intercambio {

        @Test
        @DisplayName("una pagina sucia que sale vuelve con su contenido")
        void vuelveIntacta() {
            DemandPagedSpace space = espacio(PagePolicy.LRU, 2, 5);
            for (int pagina = 0; pagina < 5; pagina++) {
                space.store(pagina * PAGINA + 7, 100 + pagina);
            }
            for (int pagina = 0; pagina < 5; pagina++) {
                assertEquals(100 + pagina, space.load(pagina * PAGINA + 7),
                        "la pagina " + pagina + " tenia que volver con su valor");
            }
            assertTrue(space.swapsOut() > 0, "tenia que haber salidas a disco");
            assertTrue(space.swapsIn() > 0, "tenia que haber entradas desde disco");
            assertTrue(space.invariantFailures().isEmpty());
        }

        @Test
        @DisplayName("una pagina que solo se leyo no se escribe en el intercambio")
        void paginaLimpiaNoSaleADisco() {
            DemandPagedSpace space = espacio(PagePolicy.FIFO, 2, 5);
            for (int pagina = 0; pagina < 5; pagina++) {
                space.load(pagina * PAGINA);
            }
            assertTrue(space.faults() >= 5, "cada pagina nueva es un fallo");
            assertEquals(0, space.swapsOut(), "nada sucio que escribir");
            assertEquals(0, space.swapsIn(), "nada guardado que leer");
            assertEquals(5, space.zeroFills(), "las cinco entraron rellenas de cero");
        }

        @Test
        @DisplayName("el area llena es un fallo del sistema, no una expulsion silenciosa")
        void areaLlena() {
            FileSystem fs = disco();
            SwapArea swap = SwapArea.format(fs, PAGINA, 1);
            DemandPagedSpace space = (DemandPagedSpace) new MemoryScheme.DemandPaged(
                    PAGINA, 1, PagePolicy.FIFO, swap, null)
                    .attach(new PhysicalMemory(PAGINA), new BestFit(), 1, "lleno", 4 * PAGINA);
            space.store(0, 1);
            space.store(PAGINA, 2);
            assertThrows(PosFault.class, () -> space.store(2 * PAGINA, 3),
                    "con una sola ranura, la segunda pagina sucia no cabe fuera");
        }

        @Test
        @DisplayName("el tamano de pagina del area tiene que ser el del esquema")
        void paginaQueNoCuadra() {
            SwapArea swap = SwapArea.format(disco(), 8, RANURAS);
            assertThrows(IllegalArgumentException.class,
                    () -> new MemoryScheme.DemandPaged(PAGINA, 2, PagePolicy.FIFO, swap, null)
                            .attach(new PhysicalMemory(4 * PAGINA), new BestFit(), 1, "x", 4 * PAGINA));
        }
    }

    // ------------------------------------------------------------------
    // El fichero de intercambio es un fichero
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("El intercambio vive en el sistema de ficheros")
    class Fichero {

        @Test
        @DisplayName("formatear el area crea un fichero con su magia y sus medidas")
        void cabecera() {
            FileSystem fs = disco();
            SwapArea swap = SwapArea.format(fs, PAGINA, RANURAS);
            byte[] raw = fs.read(SwapArea.PATH);
            assertArrayEquals(new byte[] { 'P', 'S', 'W', '1' }, Arrays.copyOf(raw, 4));
            assertEquals(SwapArea.bytesFor(PAGINA, RANURAS), raw.length);
            assertEquals(PAGINA, swap.pageSize());
            assertEquals(RANURAS, swap.slots());
            assertEquals(0, swap.usedSlots());
        }

        @Test
        @DisplayName("las paginas guardadas sobreviven a guardar y volver a montar la imagen")
        void sobreviveAlMontaje() {
            FileSystem fs = disco();
            SwapArea swap = SwapArea.format(fs, PAGINA, RANURAS);
            byte[] datos = new byte[PAGINA];
            for (int i = 0; i < PAGINA; i++) {
                datos[i] = (byte) (i + 1);
            }
            swap.write(7, 3, datos);
            byte[] imagen = fs.save();

            FileSystem montado = FileSystem.mount(imagen, BLOQUE, new BestFit());
            SwapArea reabierto = SwapArea.open(montado);
            assertTrue(reabierto.holds(7, 3));
            assertArrayEquals(datos, reabierto.read(7, 3));
            assertEquals(1, reabierto.usedSlots());
        }

        @Test
        @DisplayName("reabrir el area no se inventa ranuras ocupadas")
        void reapertura() {
            FileSystem fs = disco();
            SwapArea swap = SwapArea.format(fs, PAGINA, RANURAS);
            swap.write(1, 0, new byte[PAGINA]);
            SwapArea reabierto = SwapArea.open(fs);
            assertEquals(1, reabierto.usedSlots());
            assertEquals(RANURAS - 1, reabierto.freeSlots());
            assertFalse(reabierto.holds(1, 1));
            reabierto.discard(1, 0);
            assertEquals(0, reabierto.usedSlots());
            assertFalse(SwapArea.open(fs).holds(1, 0));
        }

        @Test
        @DisplayName("un fichero que no es area de intercambio no se abre como tal")
        void noEsArea() {
            FileSystem fs = disco();
            fs.create("/otra-cosa", "texto cualquiera que no es un area de intercambio");
            assertThrows(PosFault.class, () -> SwapArea.open(fs));
        }
    }

    // ------------------------------------------------------------------
    // Limites y liberacion
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("Limites del proceso")
    class Limites {

        @Test
        @DisplayName("sin marcos libres se expulsa, no se falla")
        void expulsaEnVezDeFallar() {
            DemandPagedSpace space = espacio(PagePolicy.LRU, 2, 6);
            for (int pagina = 0; pagina < 6; pagina++) {
                space.store(pagina * PAGINA, pagina);
            }
            assertEquals(2, space.residentPages());
            assertEquals(2 * PAGINA, space.physicalUnits());
        }

        @Test
        @DisplayName("fuera del espacio logico se falla antes de tocar la politica")
        void proteccion() {
            DemandPagedSpace space = espacio(PagePolicy.FIFO, 2, 4);
            assertThrows(PosFault.class, () -> space.load(4 * PAGINA));
            assertThrows(PosFault.class, () -> space.load(-1));
        }

        @Test
        @DisplayName("liberar devuelve los marcos y deja la memoria como estaba")
        void liberar() {
            FileSystem fs = disco();
            SwapArea swap = SwapArea.format(fs, PAGINA, RANURAS);
            PhysicalMemory memory = new PhysicalMemory(8 * PAGINA);
            DemandPagedSpace space = (DemandPagedSpace) new MemoryScheme.DemandPaged(
                    PAGINA, 3, PagePolicy.FIFO, swap, null)
                    .attach(memory, new BestFit(), 5, "suelta", 6 * PAGINA);
            for (int pagina = 0; pagina < 6; pagina++) {
                space.store(pagina * PAGINA, pagina);
            }
            assertEquals(3 * PAGINA, memory.usedUnits());
            space.release();
            assertEquals(0, memory.usedUnits());
            assertEquals(8 * PAGINA, memory.freeUnits());
            assertTrue(memory.checkInvariants().isEmpty());
        }

        @Test
        @DisplayName("con cap de marcos mayor que paginas no se reserva de mas")
        void cotaMayorQuePaginas() {
            DemandPagedSpace space = espacio(PagePolicy.FIFO, 10, 3);
            for (int pagina = 0; pagina < 3; pagina++) {
                space.load(pagina * PAGINA);
            }
            assertEquals(3, space.frameCap(), "la cota se queda en el numero de paginas");
            assertEquals(3 * PAGINA, space.physicalUnits());
        }
    }
}
