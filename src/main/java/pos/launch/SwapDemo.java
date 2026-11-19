package pos.launch;

import java.util.ArrayList;
import java.util.List;

import pos.alloc.BestFit;
import pos.fs.AllocationMethod;
import pos.fs.FileSystem;
import pos.fs.FsLayout;
import pos.mm.DemandPagedSpace;
import pos.mm.MemoryScheme;
import pos.mm.PagePolicy;
import pos.mm.SwapArea;
import pos.phys.PhysicalMemory;

/**
 * Demostracion de la fase 3 completa: paginas por demanda, area de intercambio
 * y politicas de reemplazo.
 *
 * Tres partes, en el mismo orden que las fases anteriores. Primero lo que el
 * capitulo tiene que garantizar: un proceso con mas paginas que marcos funciona
 * y sus datos vuelven igual despues de haber estado fuera. Si eso no sale, lo
 * demas da igual.
 *
 * Despues lo que distingue a las politicas: la misma traza, el mismo numero de
 * marcos y tres formas de elegir victima. Y la anomalia de Belady, que es el
 * unico sitio donde mas memoria empeora el resultado: FIFO con cuatro marcos
 * falla mas que FIFO con tres.
 *
 * Se cierra mirando donde vive de verdad el area de intercambio: un fichero de
 * P-OS, con su FCB y sus bloques, no un arreglo escondido en el nucleo.
 */
public final class SwapDemo {

    /** Unidades por pagina. Pequenas a proposito: una traza corta mueve muchas paginas. */
    private static final int PAGINA = 16;

    /** Paginas que referencia la traza de Belady: van de la 1 a la 5. */
    private static final int PAGINAS_TRAZA = 6;

    /** Traza clasica de la anomalia: doce accesos, cinco paginas distintas. */
    private static final int[] TRAZA = { 1, 2, 3, 4, 1, 2, 5, 1, 2, 3, 4, 5 };

    /** Paginas del proceso de la segunda parte. */
    private static final int PAGINAS_DATOS = 6;

    /** Unidades de la memoria fisica de la segunda parte: tres marcos y ni uno mas. */
    private static final int MARCOS_DATOS = 3;

    /** Medidas del disco donde vive el intercambio. */
    private static final int BLOQUE = 64;
    private static final int BLOQUES = 256;

    /** Ranuras del area de intercambio: mas de las que la demostracion necesita. */
    private static final int RANURAS = 24;

    private SwapDemo() {
        // Clase de demostracion: no se instancia.
    }

    public static void main(String[] args) {
        run();
    }

    /** Ejecuta la demostracion completa. */
    public static void run() {
        tresPoliticas();
        System.out.println();
        sacarYVolverAMeter();
        System.out.println();
        dondeViveElIntercambio();
    }

    // ------------------------------------------------------------------
    // 1. La misma traza, tres politicas
    // ------------------------------------------------------------------

    private static void tresPoliticas() {
        System.out.println("=== la misma traza, tres politicas ===");
        System.out.println("traza de paginas: " + traza());
        System.out.println("cada acceso escribe en su pagina, asi que casi todas salen sucias a disco");
        System.out.println("memoria de 3 y de 4 marcos de " + PAGINA
                + " unidades; area de intercambio de " + RANURAS + " ranuras");
        System.out.println();
        System.out.println(String.format("%-6s %7s %7s %8s %9s %7s %10s",
                "politica", "marcos", "fallos", "salidas", "entradas", "ceros", "en memoria"));
        System.out.println("-".repeat(60));

        List<Resultado> resultados = new ArrayList<>();
        for (PagePolicy policy : PagePolicy.values()) {
            resultados.add(correrTraza(policy, 3));
        }
        resultados.add(correrTraza(PagePolicy.FIFO, 4));

        for (Resultado resultado : resultados) {
            System.out.println(String.format("%-6s %7d %7d %8d %9d %7d %10d",
                    resultado.policy().label(), resultado.marcos(), resultado.fallos(),
                    resultado.salidas(), resultado.entradas(), resultado.ceros(),
                    resultado.enMemoria()));
        }

        System.out.println();
        System.out.println("lectura: con los mismos 3 marcos, OPT falla "
                + fallos(resultados, PagePolicy.OPT, 3) + " veces y LRU "
                + fallos(resultados, PagePolicy.LRU, 3) + ".");
        System.out.println("OPT acierta porque conoce la traza futura: si una pagina no vuelve a"
                + " usarse, es la que sale.");
        System.out.println("FIFO con 4 marcos falla " + fallos(resultados, PagePolicy.FIFO, 4)
                + " y con 3 fallaba " + fallos(resultados, PagePolicy.FIFO, 3)
                + ": mas memoria, mas fallos. Es la anomalia de Belady.");
        System.out.println("LRU y OPT no la sufren: son politicas de pila, una memoria mayor"
                + " contiene siempre las paginas de la memoria menor.");
        System.out.println();
        Resultado opt = buscar(resultados, PagePolicy.OPT, 3);
        System.out.println("desvios de traza con OPT: " + opt.desvios()
                + " (si no es cero, la cota no vale)");
        System.out.println("ceros cargados: " + opt.ceros()
                + " de " + opt.fallos() + " fallos (lo que nunca se habia escrito no esta en disco)");
    }

    /**
     * Pasa la traza por un espacio paginado por demanda y devuelve el recuento.
     *
     * Cada ejecucion estrena disco y memoria: si compartieran, la segunda
     * politica veria el area de intercambio de la primera y las cifras no
     * compararian nada.
     */
    private static Resultado correrTraza(PagePolicy policy, int marcos) {
        FileSystem fs = FileSystem.format(FsLayout.standard(BLOQUES, BLOQUE),
                new AllocationMethod.Indexed(), new BestFit());
        SwapArea swap = SwapArea.format(fs, PAGINA, RANURAS);
        PhysicalMemory memory = new PhysicalMemory(marcos * PAGINA);
        int logico = PAGINAS_TRAZA * PAGINA;

        List<Integer> futuro = new ArrayList<>();
        for (int paginas : TRAZA) {
            futuro.add(paginas * PAGINA);
        }
        MemoryScheme scheme = new MemoryScheme.DemandPaged(PAGINA, marcos, policy, swap, futuro);
        DemandPagedSpace space = (DemandPagedSpace) scheme.attach(memory, new BestFit(), 1,
                "traza", logico);

        for (int paginas : TRAZA) {
            space.store(paginas * PAGINA, paginas);
        }

        comprobar(space, marcos);
        return new Resultado(policy, marcos, space.faults(), space.swapsOut(), space.swapsIn(),
                space.zeroFills(), space.residentPages(), space.traceMisses());
    }

    private static long fallos(List<Resultado> resultados, PagePolicy policy, int marcos) {
        return buscar(resultados, policy, marcos).fallos();
    }

    private static Resultado buscar(List<Resultado> resultados, PagePolicy policy, int marcos) {
        return resultados.stream()
                .filter(r -> r.policy() == policy && r.marcos() == marcos)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "falta el resultado de " + policy + " con " + marcos + " marcos"));
    }

    private static String traza() {
        StringBuilder sb = new StringBuilder();
        for (int paginas : TRAZA) {
            sb.append(sb.length() == 0 ? "" : " ").append(paginas);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 2. Sacar y volver a meter
    // ------------------------------------------------------------------

    private static void sacarYVolverAMeter() {
        System.out.println("=== sacar y volver a meter ===");
        System.out.println("un proceso de " + PAGINAS_DATOS + " paginas con " + MARCOS_DATOS
                + " marcos: escribe su numero en cada pagina y luego las lee al reves");
        System.out.println();

        FileSystem fs = FileSystem.format(FsLayout.standard(BLOQUES, BLOQUE),
                new AllocationMethod.Indexed(), new BestFit());
        SwapArea swap = SwapArea.format(fs, PAGINA, RANURAS);
        PhysicalMemory memory = new PhysicalMemory(MARCOS_DATOS * PAGINA);
        DemandPagedSpace space = (DemandPagedSpace) new MemoryScheme.DemandPaged(
                PAGINA, MARCOS_DATOS, PagePolicy.LRU, swap, null)
                .attach(memory, new BestFit(), 7, "datos", PAGINAS_DATOS * PAGINA);

        int desplazamiento = 5;
        for (int page = 0; page < PAGINAS_DATOS; page++) {
            space.store(page * PAGINA + desplazamiento, 10 + page);
        }
        System.out.println("tras escribir las " + PAGINAS_DATOS + " paginas: " + space.counters());
        System.out.println("quedan fuera " + swap.usedSlots() + " paginas; las que estan en memoria:");
        space.describe().forEach(line -> System.out.println("  " + line));
        System.out.println();

        boolean bien = true;
        for (int page = PAGINAS_DATOS - 1; page >= 0; page--) {
            int leido = space.load(page * PAGINA + desplazamiento);
            if (leido != 10 + page) {
                bien = false;
                System.out.println("  pagina " + page + ": se esperaba " + (10 + page)
                        + " y se leyo " + leido);
            }
        }
        System.out.println("tras leer al reves: " + space.counters());
        System.out.println("valores recuperados: " + (bien ? "los " + PAGINAS_DATOS + " correctos"
                : "FALLO") + " (ninguno se invento al volver del disco)");
        System.out.println("memoria fisica ocupada por el proceso: " + space.physicalUnits()
                + " unidades de " + PAGINAS_DATOS * PAGINA + " logicas");
        comprobar(space, MARCOS_DATOS);
        System.out.println();
        System.out.println("lectura: el proceso declara " + PAGINAS_DATOS
                + " paginas y solo caben " + MARCOS_DATOS + ".");
        System.out.println("Las otras pasan por disco y vuelven con su contenido intacto.");
    }

    // ------------------------------------------------------------------
    // 3. Donde vive el area de intercambio
    // ------------------------------------------------------------------

    private static void dondeViveElIntercambio() {
        System.out.println("=== el area de intercambio es un fichero ===");
        FileSystem fs = FileSystem.format(FsLayout.standard(BLOQUES, BLOQUE),
                new AllocationMethod.Indexed(), new BestFit());
        SwapArea swap = SwapArea.format(fs, PAGINA, RANURAS);
        PhysicalMemory memory = new PhysicalMemory(MARCOS_DATOS * PAGINA);
        DemandPagedSpace space = (DemandPagedSpace) new MemoryScheme.DemandPaged(
                PAGINA, MARCOS_DATOS, PagePolicy.FIFO, swap, null)
                .attach(memory, new BestFit(), 3, "forzado", PAGINAS_DATOS * PAGINA);

        for (int page = 0; page < PAGINAS_DATOS; page++) {
            space.store(page * PAGINA, page + 1);
        }

        System.out.println("directorio raiz: " + nombres(fs));
        System.out.println("fichero " + SwapArea.PATH + ": " + swap.sizeBytes()
                + " bytes, " + swap.usedSlots() + " de " + swap.slots() + " ranuras ocupadas");
        System.out.println("disco: " + fs.usage().describe());
        System.out.println("trabajo de disco del intercambio: " + fs.image().stats());
        System.out.println();
        System.out.println("los primeros bytes del fichero, en hexadecimal:");
        byte[] raw = fs.read(SwapArea.PATH);
        StringBuilder sb = new StringBuilder();
        int limite = Math.min(raw.length, 24);
        for (int i = 0; i < limite; i++) {
            sb.append(String.format("%02x ", raw[i] & 0xFF));
        }
        System.out.println("  " + sb.toString().trim() + (raw.length > limite ? " ..." : ""));
        System.out.println();
        System.out.println("lectura: los cuatro primeros bytes son la magia PSW1, luego el tamano"
                + " de pagina y el numero de ranuras.");
        System.out.println("El resto son las paginas expulsadas, cada una con su proceso y su"
                + " numero de pagina delante.");
        System.out.println("Nada de esto esta en memoria del nucleo: se puede montar la imagen y"
                + " leer el fichero como cualquier otro.");
    }

    /** Nombres del directorio raiz, separados por espacios. */
    private static String nombres(FileSystem fs) {
        List<String> nombres = new ArrayList<>();
        fs.list(FileSystem.ROOT).forEach(entry -> nombres.add(entry.name()));
        return String.join(" ", nombres);
    }

    // ------------------------------------------------------------------
    // Comprobaciones
    // ------------------------------------------------------------------

    /**
     * Comprueba las dos cosas que no pueden fallar: los invariantes de la
     * memoria fisica y que no haya mas paginas presentes que marcos.
     */
    private static void comprobar(DemandPagedSpace space, int marcos) {
        List<String> fallos = new ArrayList<>(space.invariantFailures());
        if (space.residentPages() > marcos) {
            fallos.add("hay " + space.residentPages() + " paginas presentes con " + marcos + " marcos");
        }
        if (space.residentPages() > space.frameCap()) {
            fallos.add("se paso de la cota de marcos: " + space.residentPages()
                    + " > " + space.frameCap());
        }
        if (!fallos.isEmpty()) {
            throw new IllegalStateException("comprobacion fallida: " + String.join("; ", fallos));
        }
    }

    /** Lo que se mide de una pasada de la traza. */
    private record Resultado(PagePolicy policy, int marcos, long fallos, long salidas,
            long entradas, long ceros, int enMemoria, int desvios) {
    }
}
