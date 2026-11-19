package pos.launch;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import pos.base.PosFault;
import pos.fs.Allocation;
import pos.fs.AllocationMethod;
import pos.fs.DiskImage;
import pos.fs.Fcb;
import pos.fs.FileSystem;
import pos.fs.FsLayout;

/**
 * Demostracion de la fase 4: el sistema de ficheros.
 *
 * Como en las fases anteriores, primero se comprueba lo que el capitulo tiene
 * que garantizar: lo que se escribe se lee igual, con cualquiera de los tres
 * metodos y despues de cambiar de metodo. Si eso no sale, el resto da igual.
 *
 * Despues se hace lo que de verdad los distingue: se fragmenta el disco y se
 * pide el mismo fichero a los tres. Unos entran, otros no, y el porque es la
 * defensa entera del capitulo.
 *
 * Se cierra comprobando que una imagen estropeada no monta: el sistema lo dice
 * y se niega, en vez de arrancar con datos que no cuadran.
 */
public final class FileSystemDemo {

    /** Bloques de 64 bytes: el puntero de cuatro ocupa una parte apreciable y se nota. */
    private static final int BLOQUE = 128;
    private static final int BLOQUES = 256;

    /** Tamano de los ficheros que llenan el disco, en bloques logicos. */
    private static final int HUECO = 16;

    /** Fichero pedido al final: mas grande que cualquier hueco que quede. */
    private static final int GRANDE = 24;

    /** Ficheros del escenario: la tabla de FCB admite dieciseis con la raiz, y se borran los pares. */
    private static final int FICHEROS = 13;

    private FileSystemDemo() {
        // Clase de demostracion: no se instancia.
    }

    public static void main(String[] args) {
        run();
    }

    /** Ejecuta la demostracion completa. */
    public static void run() {
        loQueSeEscribeSeLee();
        System.out.println();
        losTresMetodos();
        System.out.println();
        fragmentacion();
        System.out.println();
        integridad();
    }

    // ------------------------------------------------------------------
    // 1. La garantia: lo escrito se lee, tambien tras remontar
    // ------------------------------------------------------------------

    private static void loQueSeEscribeSeLee() {
        System.out.println("=== lo que se escribe se lee ===");

        FileSystem fs = nuevo(new AllocationMethod.Contiguous());
        fs.mkdir("/notas");
        fs.mkdir("/notas/cifras");
        fs.create("/notas/leeme.txt", "el sistema guarda y devuelve lo mismo");
        fs.create("/notas/cifras/pi.txt", "3,141592");
        fs.create("/notas/vacio.txt", "");
        fs.write("/notas/leeme.txt", relleno(300, 3));

        byte[] guardado = fs.save();
        // El tamano de bloque no viaja en los bytes: quien monta tiene que saberlo,
        // o el superbloque se lee en la posicion equivocada.
        FileSystem montado = FileSystem.mount(guardado, BLOQUE, new pos.alloc.BestFit());

        System.out.println("  ficheros .......... " + montado.usage().files());
        System.out.println("  directorios ....... " + montado.usage().directories());
        System.out.println("  leeme.txt ......... " + bytes(montado.read("/notas/leeme.txt")));
        System.out.println("  pi.txt ............ " + montado.readText("/notas/cifras/pi.txt"));
        System.out.println("  vacio.txt ......... " + montado.read("/notas/vacio.txt").length
                + " bytes");
        System.out.println("  listado /notas .... " + nombres(montado, "/notas"));
        System.out.println("  integridad ........ " + montado.verify().summary());
        coinciden(montado.read("/notas/leeme.txt"), relleno(300, 3), "leeme.txt tras remontar");
    }

    // ------------------------------------------------------------------
    // 2. Los tres metodos hacen lo mismo, con distinto coste
    // ------------------------------------------------------------------

    private static void losTresMetodos() {
        System.out.println("=== los tres metodos guardan lo mismo ===");

        byte[] datos = relleno(380, 7);
        List<String> lineas = new ArrayList<>();
        lineas.add(String.format("%-10s %8s %8s %8s %10s %10s",
                "metodo", "bloques", "indices", "total", "escrituras", "lecturas"));

        for (AllocationMethod method : metodos()) {
            FileSystem fs = nuevo(new AllocationMethod.Contiguous());
            fs.create("/datos.bin", new byte[0]);
            fs.switchMethod(method);
            fs.image().resetCounters();
            fs.write("/datos.bin", datos);

            coinciden(fs.read("/datos.bin"), datos, "datos.bin en " + method.name());

            Fcb fcb = fs.resolve("/datos.bin");
            int bloques = fcb.allocation().dataBlocks();
            int indices = method.overheadBlocks(bloques, BLOQUE);
            lineas.add(String.format("%-10s %8d %8d %8d %10d %10d",
                    method.name(), bloques, indices, bloques + indices,
                    fs.image().blockWrites(), fs.image().blockReads()));
        }

        lineas.forEach(System.out::println);
        System.out.println();
        System.out.println("lectura: los mismos 380 bytes no ocupan lo mismo. El contiguo los mete en");
        System.out.println("tres bloques y no gasta indices; el enlazado pierde cuatro bytes por bloque");
        System.out.println("en el puntero y necesita uno mas; el indexado paga un bloque entero solo");
        System.out.println("para la tabla, pero accede a cualquier bloque en dos lecturas en vez de");
        System.out.println("recorrer la cadena.");
    }

    // ------------------------------------------------------------------
    // 3. Lo que los distingue: un disco fragmentado
    // ------------------------------------------------------------------

    private static void fragmentacion() {
        System.out.println("=== el mismo hueco para los tres metodos ===");
        System.out.println("se llena el disco con " + FICHEROS + " ficheros de " + HUECO + " bloques");
        System.out.println("y se borran los pares: quedan huecos de " + HUECO + " bloques, uno si y otro no");
        System.out.println("se pide un fichero de " + GRANDE + " bloques");
        System.out.println();

        System.out.println(String.format("%-10s %-10s %s", "metodo", "resultado", "detalle"));

        for (AllocationMethod method : metodos()) {
            FileSystem fs = nuevo(method);
            sembrarHuecos(fs, method);

            String resultado;
            String detalle;
            try {
                fs.create("/grande.bin", relleno(GRANDE * method.capacityPerBlock(BLOQUE), 11));
                Fcb fcb = fs.resolve("/grande.bin");
                resultado = "entra";
                detalle = fcb.allocation().dataBlocks() + " bloques: "
                        + method.describe(fs.image(), fcb.allocation()).get(0);
            } catch (PosFault fault) {
                resultado = "no entra";
                detalle = primeraLinea(fault.getMessage());
            }
            System.out.println(String.format("%-10s %-10s %s", method.name(), resultado, detalle));
        }

        System.out.println();
        System.out.println("lectura: el contiguo necesita " + GRANDE + " bloques seguidos y ningun hueco");
        System.out.println("llega ahi, aunque en total sobre espacio de sobra; el enlazado toma bloques");
        System.out.println("sueltos y los encadena, asi que no le importa donde esten; el indexado");
        System.out.println("hace lo mismo y ademas reserva un bloque para la tabla de punteros.");
    }

    /**
     * Llena el disco con ficheros del mismo tamano y borra los pares.
     *
     * El tamano se calcula con la capacidad del metodo activo para que los tres
     * escenarios sean comparables: el mismo numero de bloques ocupados, no los
     * mismos bytes.
     */
    private static void sembrarHuecos(FileSystem fs, AllocationMethod method) {
        int bytes = HUECO * method.capacityPerBlock(BLOQUE);
        for (int i = 0; i < FICHEROS; i++) {
            fs.create("/hueco-" + i + ".bin", relleno(bytes, i));
        }
        // Se cierra el disco con lo que sobre: si quedara un tramo grande al final,
        // el contiguo entraria por ahi y el escenario no probaria nada. Se toma el
        // mayor de los huecos libres, no la suma, porque lo que el contiguo mira es
        // el tramo, no el total.
        int sobrantes = 0;
        for (var span : fs.freeMap().freeSpans()) {
            if (fs.layout().isDataBlock(span.start())) {
                sobrantes = Math.max(sobrantes, span.length());
            }
        }
        sobrantes -= 2;
        if (sobrantes > 0) {
            fs.create("/relleno.bin",
                    relleno(sobrantes * method.capacityPerBlock(BLOQUE), 99));
        }
        for (int i = 0; i < FICHEROS; i += 2) {
            fs.remove("/hueco-" + i + ".bin");
        }
    }

    // ------------------------------------------------------------------
    // 4. Una imagen estropeada no monta
    // ------------------------------------------------------------------

    private static void integridad() {
        System.out.println("=== una imagen incoherente no monta ===");

        FileSystem fs = nuevo(new AllocationMethod.Contiguous());
        fs.create("/cifras.bin", relleno(200, 5));
        byte[] buenos = fs.save();

        // Se marca como libre el primer bloque de datos del fichero: el mapa dice
        // que esta suelto y el FCB dice que es suyo.
        byte[] rotos = buenos.clone();
        int bloque = switch (fs.resolve("/cifras.bin").allocation()) {
            case Allocation.Contiguous c -> c.first();
            case Allocation.Chained c -> c.first();
            case Allocation.Indexed i -> i.indexBlock();
            case Allocation.Empty e -> -1;
        };

        System.out.println("  montaje correcto .. " + FileSystem.mount(buenos, BLOQUE, new pos.alloc.BestFit()).usage().describe());

        try {
            liberarEnMapa(rotos, bloque);
            FileSystem.mount(rotos, BLOQUE, new pos.alloc.BestFit());
            System.out.println("  montaje roto ...... MONTA (mal)");
        } catch (PosFault fault) {
            System.out.println("  montaje roto ...... rechazado");
            for (String linea : fault.getMessage().split("\n")) {
                System.out.println("    " + linea);
            }
        }

        System.out.println();
        System.out.println("lectura: al montar se contrasta el mapa de libres con los FCB. Un bloque");
        System.out.println("libre que un fichero reclama es una fuga o una corrupcion, y el sistema");
        System.out.println("prefiere no arrancar a escribir encima de datos que cree suyos.");
    }

    /**
     * Marca el bloque indicado como libre en el mapa de la imagen.
     *
     * En este mapa un bit a uno significa libre, asi que liberar es poner el bit,
     * no quitarlo. El bit vive en el bloque del mapa: se calcula su posicion con
     * el reparto, no con una constante que pueda quedarse vieja.
     */
    private static void liberarEnMapa(byte[] raw, int bloque) {
        if (bloque < 0) {
            throw new PosFault("el fichero no tiene bloques que liberar");
        }
        FsLayout layout = FsLayout.standard(BLOQUES, BLOQUE);
        int offset = layout.bitmapFirst() * BLOQUE + bloque / 8;
        raw[offset] |= (byte) (1 << (bloque % 8));
    }

    // ------------------------------------------------------------------
    // Utilidades
    // ------------------------------------------------------------------

    private static FileSystem nuevo(AllocationMethod method) {
        return FileSystem.format(FsLayout.standard(BLOQUES, BLOQUE), method, new pos.alloc.BestFit());
    }

    private static List<AllocationMethod> metodos() {
        return List.of(new AllocationMethod.Contiguous(), new AllocationMethod.Linked(),
                new AllocationMethod.Indexed());
    }

    private static byte[] relleno(int length, int semilla) {
        byte[] data = new byte[length];
        new Random(semilla).nextBytes(data);
        return data;
    }

    private static String bytes(byte[] data) {
        String texto = new String(data, java.nio.charset.StandardCharsets.ISO_8859_1);
        boolean imprimible = texto.chars().allMatch(c -> c >= 32 && c < 127);
        return imprimible ? "\"" + texto + "\"" : data.length + " bytes binarios";
    }

    private static String nombres(FileSystem fs, String path) {
        List<String> nombres = new ArrayList<>();
        for (var entry : fs.list(path)) {
            boolean directorio = fs.resolve(path + "/" + entry.name()).directory();
            nombres.add(entry.name() + (directorio ? "/" : ""));
        }
        return String.join(" ", nombres);
    }

    private static String primeraLinea(String texto) {
        int corte = texto.indexOf('\n');
        return corte < 0 ? texto : texto.substring(0, corte);
    }

    private static void coinciden(byte[] obtenido, byte[] esperado, String que) {
        if (!java.util.Arrays.equals(obtenido, esperado)) {
            throw new IllegalStateException("no coincide: " + que);
        }
    }

}
