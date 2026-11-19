package pos.launch;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import pos.alloc.BestFit;
import pos.base.FreeSpan;
import pos.base.PosFault;
import pos.mm.AddressSpace;
import pos.mm.MemoryScheme;
import pos.mm.SegmentLayout;
import pos.phys.PhysicalMemory;

/**
 * Demostracion de la fase 3: los tres esquemas de memoria.
 *
 * Primero se hace lo que la fase tiene que garantizar: el mismo proceso, con la
 * misma instruccion, guarda el mismo valor y lo vuelve a leer en los tres
 * esquemas. Si eso no sale, no hay fase 3.
 *
 * Despues se hace lo que de verdad distingue a los esquemas: se fragmenta una
 * memoria y se pide lo mismo a los tres. Unos entran y otros no, y ver quien
 * entra y por que es la defensa entera del capitulo.
 */
public final class MemoryDemo {

    /** Unidades logicas del proceso de prueba. No es multiplo del tamano de pagina a proposito. */
    private static final int LOGICAS = 250;
    private static final int PAGINA = 16;
    private static final int FISICAS = 4096;

    /** Unidades ocupadas antes de colocar al proceso: obliga a que la traduccion no sea la identidad. */
    private static final int PREFIJO = 40;

    private static final int DIRECCION = 200;
    private static final int VALOR = 42;

    private MemoryDemo() {
        // Clase de demostracion: no se instancia.
    }

    public static void main(String[] args) {
        run();
    }

    /** Ejecuta la demostracion completa. */
    public static void run() {
        mismoValorEnLosTresEsquemas();
        System.out.println();
        fragmentacion();
    }

    // ------------------------------------------------------------------
    // 1. La garantia de la fase: mismo valor, mismo resultado
    // ------------------------------------------------------------------

    private static void mismoValorEnLosTresEsquemas() {
        System.out.println("=== los tres esquemas hacen lo mismo ===");
        System.out.println("un proceso de " + LOGICAS + " unidades logicas escribe " + VALOR
                + " en la direccion " + DIRECCION + " y la vuelve a leer");
        System.out.println("la memoria arranca con " + PREFIJO
                + " unidades ocupadas por otro proceso");
        System.out.println();
        System.out.println(String.format("%-15s %8s %8s %12s %13s %8s",
                "esquema", "logico", "fisico", "desperdicio", "fisica(" + DIRECCION + ")", "lectura"));
        System.out.println("-".repeat(70));

        for (MemoryScheme scheme : esquemas()) {
            PhysicalMemory memory = memoriaConPrefijo();
            AddressSpace space = scheme.attach(memory, new BestFit(), 1, "demo", LOGICAS);

            space.store(DIRECCION, VALOR);
            int leido = space.load(DIRECCION);

            System.out.println(String.format("%-15s %8d %8d %12d %13d %8d",
                    scheme.name(), space.logicalUnits(), space.physicalUnits(), space.wastedUnits(),
                    space.physicalAddressOf(DIRECCION), leido));
        }

        System.out.println();
        System.out.println("lectura: los tres devuelven " + VALOR
                + ". El programador no sabe cual esta puesto, y no tiene por que saberlo.");
        System.out.println("Solo cambia el coste: la paginacion reserva la ultima pagina entera,"
                + " asi que 250 unidades ocupan 256.");
        System.out.println();

        System.out.println("--- tabla de traduccion de cada esquema ---");
        for (MemoryScheme scheme : esquemas()) {
            PhysicalMemory memory = memoriaConPrefijo();
            AddressSpace space = scheme.attach(memory, new BestFit(), 1, "demo", LOGICAS);
            space.describe().forEach(line -> System.out.println("  " + line));
            System.out.println();
        }
    }

    private static List<MemoryScheme> esquemas() {
        List<MemoryScheme> schemes = new ArrayList<>();
        schemes.add(new MemoryScheme.Contiguous());
        schemes.add(new MemoryScheme.Paged(PAGINA));
        schemes.add(new MemoryScheme.Segmented(
                SegmentLayout.stacked(new String[] { "codigo", "datos" }, new int[] { 150, 100 })));
        return schemes;
    }

    private static PhysicalMemory memoriaConPrefijo() {
        PhysicalMemory memory = new PhysicalMemory(FISICAS);
        memory.allocate(99, 0, PREFIJO);
        return memory;
    }

    // ------------------------------------------------------------------
    // 2. Donde se rompe cada uno
    // ------------------------------------------------------------------

    private static void fragmentacion() {
        System.out.println("=== fragmentacion: la misma peticion, tres respuestas ===");
        System.out.println("memoria de 256 unidades, cinco procesos de 50 colocados en contiguo");
        System.out.println("despues se liberan el segundo y el quinto");

        PhysicalMemory memory = new PhysicalMemory(256);
        List<AddressSpace> spaces = new ArrayList<>();
        for (int id = 1; id <= 5; id++) {
            spaces.add(new MemoryScheme.Contiguous().attach(memory, new BestFit(), id, "p" + id, 50));
        }
        System.out.println("  mapa tras colocar: " + memory.render(16));
        System.out.println("  ocupadas " + memory.usedUnits() + " de " + memory.size());

        spaces.get(1).release();
        spaces.get(4).release();
        System.out.println("  mapa tras liberar: " + memory.render(16));
        System.out.println("  ocupadas " + memory.usedUnits() + ", huecos " + huecos(memory));
        System.out.println("  el hueco mayor mide " + memory.largestFreeSpan().length() + " unidades");
        System.out.println();

        int pedido = 60;
        System.out.println("ahora se pide un proceso de " + pedido + " unidades logicas");
        System.out.println();
        System.out.println(String.format("%-22s %-26s %s", "esquema", "lo que necesita", "resultado"));
        System.out.println("-".repeat(96));

        fila("contiguo", "un tramo de " + pedido,
                intentar(new MemoryScheme.Contiguous(), memory, 6, pedido));
        fila("paginacion/" + PAGINA, marcos(pedido) + " marcos de " + PAGINA + " (" + marcos(pedido) * PAGINA + ")",
                intentar(new MemoryScheme.Paged(PAGINA), memory, 7, pedido));
        fila("segmentacion 1x" + pedido, "un tramo de " + pedido,
                intentar(new MemoryScheme.Segmented(
                        SegmentLayout.stacked(new String[] { "todo" }, new int[] { pedido })),
                        memory, 8, pedido));
        fila("segmentacion 30+30", "dos tramos de 30",
                intentar(new MemoryScheme.Segmented(
                        SegmentLayout.stacked(new String[] { "codigo", "datos" }, new int[] { 30, 30 })),
                        memory, 9, pedido));

        System.out.println();
        System.out.println("lectura: el contiguo no entra porque no queda ningun tramo de " + pedido
                + " aunque sobren " + memory.freeUnits() + " unidades libres.");
        System.out.println("La paginacion entra porque solo necesita marcos de " + PAGINA
                + " y cualquier hueco de ese tamano sirve.");
        System.out.println("La segmentacion depende del tamano del segmento: con uno de " + pedido
                + " choca igual que el contiguo, con dos de 30 cabe.");
        System.out.println();
        System.out.println("estado final: quedan " + memory.freeUnits()
                + " unidades libres y el invariante dice "
                + (memory.checkInvariants().isEmpty() ? "OK" : "FALLO"));
    }

    private static int marcos(int units) {
        return (units + PAGINA - 1) / PAGINA;
    }

    /**
     * Intenta colocar un proceso y devuelve el resultado en una linea.
     *
     * Si entra, se anota donde y se libera en el acto: cada fila tiene que ver
     * el mismo estado de memoria que la anterior, o la tabla no compara nada.
     */
    private static String intentar(MemoryScheme scheme, PhysicalMemory memory, int id, int units) {
        try {
            AddressSpace space = scheme.attach(memory, new BestFit(), id, "prueba", units);
            String donde = space.reservedSpans().stream()
                    .map(FreeSpan::label)
                    .collect(Collectors.joining(" + "));
            space.release();
            return "entra en " + donde;
        } catch (PosFault fault) {
            FreeSpan largest = memory.largestFreeSpan();
            int mayor = largest == null ? 0 : largest.length();
            return "no entra: no hay " + units + " unidades contiguas (el mayor hueco mide " + mayor + ")";
        }
    }

    private static void fila(String esquema, String necesita, String resultado) {
        System.out.println(String.format("%-22s %-26s %s", esquema, necesita, resultado));
    }

    private static String huecos(PhysicalMemory memory) {
        return memory.freeSpans().stream()
                .map(span -> span.label() + " de " + span.length())
                .collect(Collectors.joining(", "));
    }
}
