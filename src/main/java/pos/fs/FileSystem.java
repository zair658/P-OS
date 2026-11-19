package pos.fs;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import pos.alloc.BestFit;
import pos.alloc.SlotManager;
import pos.base.PosFault;

/**
 * Sistema de ficheros sobre una imagen de bloques.
 *
 * Es el unico dueno de la imagen: el superbloque, el mapa de libres, la tabla de
 * FCB y los bloques de datos. Todo lo que se ve desde fuera (crear, leer,
 * escribir, borrar, listar) pasa por aqui, y todo lo que hay dentro de la imagen
 * se puede volver a montar desde cero.
 *
 * Tres decisiones que conviene tener claras al leerlo:
 * <ul>
 *   <li>El invariante de la fase: los bloques de datos de un fichero son
 *       exactamente los que necesita su tamano. Un fichero nunca guarda bloques
 *       de mas, y por eso el verificador puede comprobarlo.</li>
 *   <li>Escribir un contenido que no cabe donde esta obliga a recolocarlo. Es
 *       justo lo que le pasa al metodo contiguo cuando el bloque de al lado
 *       esta ocupado, y la razon de que su crecimiento sea malo.</li>
 *   <li>Cambiar de metodo en caliente es leer todo, liberar todo y volver a
 *       escribir todo con el metodo nuevo. No hay conversion parcial: o la
 *       imagen entera queda en el metodo nuevo, o no se toca.</li>
 * </ul>
 */
public final class FileSystem {

    /** Ruta del directorio raiz. */
    public static final String ROOT = "/";

    /** Identificador del FCB raiz. */
    public static final int ROOT_ID = 0;

    private final DiskImage image;
    private final FsLayout layout;
    private final SlotManager slots;
    private final Map<Integer, Fcb> fcbs = new LinkedHashMap<>();

    private AllocationMethod method;
    private FreeBlockMap free;
    private Superblock superblock;
    private List<String> loadProblems = new ArrayList<>();
    private int tick;
    private String cwd = ROOT;

    private FileSystem(DiskImage image, FsLayout layout, SlotManager slots) {
        this.image = image;
        this.layout = layout;
        this.slots = slots;
    }

    // ------------------------------------------------------------------
    // Creacion y montaje
    // ------------------------------------------------------------------

    /** Formatea una imagen nueva con el reparto y el metodo indicados. */
    public static FileSystem format(FsLayout layout, AllocationMethod method, SlotManager slots) {
        if (method == null) {
            throw new IllegalArgumentException("hay que elegir metodo de asignacion");
        }
        FileSystem fs = new FileSystem(new DiskImage(layout.blockCount(), layout.blockSize()),
                layout, slots == null ? new BestFit() : slots);
        fs.method = method;
        fs.free = new FreeBlockMap(layout.blockCount());
        // Metadatos: superbloque, mapa de bits y tabla de FCB.
        fs.free.markUsed(0, layout.dataFirst());
        fs.superblock = Superblock.fresh(layout, fs.free.freeCount(), method, ROOT_ID);

        Fcb root = new Fcb(ROOT_ID, "raiz", true, 0, new Allocation.Empty(), 0, 0);
        fs.fcbs.put(ROOT_ID, root);
        fs.persist(root);
        fs.persistMetadata();
        return fs;
    }

    /** Formatea una imagen con el reparto por defecto. */
    public static FileSystem format(AllocationMethod method) {
        return format(FsLayout.standard(DiskImage.DEFAULT_BLOCKS, DiskImage.DEFAULT_BLOCK_SIZE),
                method, new BestFit());
    }

    /**
     * Monta una imagen ya existente.
     *
     * Lee el superbloque, carga el mapa de libres y la tabla de FCB, y verifica
     * el conjunto. Si algo no cuadra, no monta: se lanza un fallo con la lista
     * de problemas, y el arranque decide quedarse en modo degradado.
     */
    public static FileSystem mount(byte[] raw, int blockSize, SlotManager slots) {
        DiskImage image = DiskImage.ofBytes(raw, blockSize);
        FsLayout layout = FsLayout.standard(image.blockCount(), image.blockSize());
        Superblock superblock = Superblock.readFrom(image);

        FileSystem fs = new FileSystem(image, layout, slots == null ? new BestFit() : slots);
        fs.superblock = superblock;
        fs.method = superblock.method();

        List<String> problems = new ArrayList<>();
        try {
            fs.free = fs.readFreeMap();
        } catch (PosFault fault) {
            problems.add(fault.getMessage());
            fs.free = new FreeBlockMap(layout.blockCount());
            fs.free.markUsed(0, layout.dataFirst());
        }
        problems.addAll(fs.loadFcbs());
        fs.loadProblems = problems;

        FsCheck check = fs.verify();
        if (!check.ok()) {
            throw new PosFault("la imagen no monta: " + check.summary() + "\n  "
                    + String.join("\n  ", check.problems()));
        }
        return fs;
    }

    /** Monta una imagen con el reparto por defecto. */
    public static FileSystem mount(byte[] raw) {
        return mount(raw, DiskImage.DEFAULT_BLOCK_SIZE, new BestFit());
    }

    private FreeBlockMap readFreeMap() {
        byte[] bytes = new byte[FreeBlockMap.bytesFor(layout.blockCount())];
        for (int i = 0; i < layout.bitmapBlocks(); i++) {
            byte[] block = image.read(layout.bitmapFirst() + i);
            int offset = i * image.blockSize();
            int length = Math.min(block.length, bytes.length - offset);
            System.arraycopy(block, 0, bytes, offset, length);
        }
        return FreeBlockMap.fromBytes(bytes, layout.blockCount());
    }

    private List<String> loadFcbs() {
        List<String> problems = new ArrayList<>();
        fcbs.clear();
        for (int id = 0; id < layout.maxFiles(); id++) {
            try {
                byte[] block = image.read(layout.fcbFirst() + id);
                Fcb fcb = Fcb.fromBytes(block, id, method);
                if (fcb != null) {
                    fcbs.put(id, fcb);
                }
            } catch (PosFault fault) {
                problems.add(fault.getMessage());
            }
        }
        if (!fcbs.containsKey(ROOT_ID)) {
            problems.add("no hay FCB raiz en la imagen");
        }
        return problems;
    }

    // ------------------------------------------------------------------
    // Persistencia de metadatos
    // ------------------------------------------------------------------

    private void persist(Fcb fcb) {
        image.write(layout.fcbFirst() + fcb.id(), fcb.toBytes(image.blockSize()));
    }

    private void eraseFcb(int id) {
        image.clear(layout.fcbFirst() + id);
    }

    private void persistMetadata() {
        byte[] map = free.toBytes();
        for (int i = 0; i < layout.bitmapBlocks(); i++) {
            byte[] block = new byte[image.blockSize()];
            int offset = i * image.blockSize();
            int length = Math.min(block.length, map.length - offset);
            if (length > 0) {
                System.arraycopy(map, offset, block, 0, length);
            }
            image.write(layout.bitmapFirst() + i, block);
        }
        superblock = Superblock.fresh(layout, free.freeCount(), method, ROOT_ID);
        superblock.writeTo(image);
    }

    // ------------------------------------------------------------------
    // Contenido de un fichero
    // ------------------------------------------------------------------

    private int capacity() {
        return method.capacityPerBlock(image.blockSize());
    }

    private void writeBlocks(Allocation allocation, byte[] content) {
        int capacity = capacity();
        for (int logical = 0; logical < allocation.dataBlocks(); logical++) {
            int from = logical * capacity;
            int to = Math.min(from + capacity, content.length);
            byte[] chunk = Arrays.copyOfRange(content, from, to);
            method.storeBlock(image, allocation, logical, chunk);
        }
    }

    private byte[] readContent(Fcb fcb) {
        if (fcb.size() == 0) {
            return new byte[0];
        }
        byte[] content = new byte[fcb.size()];
        int capacity = capacity();
        int offset = 0;
        for (int logical = 0; logical < fcb.allocation().dataBlocks() && offset < content.length; logical++) {
            byte[] chunk = method.loadBlock(image, fcb.allocation(), logical, capacity);
            int length = Math.min(chunk.length, content.length - offset);
            System.arraycopy(chunk, 0, content, offset, length);
            offset += length;
        }
        return content;
    }

    /**
     * Deja el contenido indicado en un fichero existente.
     *
     * Si el tamano nuevo necesita los mismos bloques, se reescriben y no cambia
     * la colocacion. Si necesita mas, se intenta crecer en el sitio y, cuando el
     * metodo no puede, se libera y se vuelve a colocar. Si necesita menos, se
     * recoloca: esta version no devuelve bloques sobrantes a un fichero vivo.
     */
    private Fcb updateContent(Fcb fcb, byte[] content) {
        int needed = method.dataBlocksFor(content.length, image.blockSize());
        int current = fcb.allocation().dataBlocks();
        Allocation allocation;

        if (needed == current) {
            allocation = fcb.allocation();
        } else if (needed > current) {
            Allocation grown = method.extend(image, free, slots, fcb.allocation(), needed - current);
            if (grown != null) {
                allocation = grown;
            } else {
                method.release(image, free, fcb.allocation());
                allocation = method.allocate(image, free, slots, needed);
            }
        } else {
            method.release(image, free, fcb.allocation());
            allocation = method.allocate(image, free, slots, needed);
        }

        writeBlocks(allocation, content);
        Fcb updated = fcb.withContent(content.length, allocation, nextTick());
        fcbs.put(updated.id(), updated);
        persist(updated);
        persistMetadata();
        return updated;
    }

    /** Crea la asignacion de un contenido nuevo y lo escribe. */
    private Allocation storeNew(byte[] content) {
        int blocks = method.dataBlocksFor(content.length, image.blockSize());
        Allocation allocation = method.allocate(image, free, slots, blocks);
        writeBlocks(allocation, content);
        return allocation;
    }

    // ------------------------------------------------------------------
    // Rutas
    // ------------------------------------------------------------------

    /** Directorio de trabajo actual. */
    public String cwd() {
        return cwd;
    }

    /** Normaliza una ruta contra el directorio de trabajo. */
    public String normalize(String path) {
        if (path == null || path.isBlank()) {
            throw new PosFault("ruta vacia");
        }
        String base = path.startsWith("/") ? path : cwd + "/" + path;
        Deque<String> parts = new ArrayDeque<>();
        for (String part : base.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!parts.isEmpty()) {
                    parts.removeLast();
                }
            } else {
                parts.addLast(part);
            }
        }
        return ROOT + String.join("/", parts);
    }

    /** Ruta absoluta de un nombre dentro del directorio actual. */
    public String absolute(String name) {
        return normalize(name);
    }

    public Fcb root() {
        Fcb root = fcbs.get(ROOT_ID);
        if (root == null) {
            throw new PosFault("la imagen no tiene directorio raiz");
        }
        return root;
    }

    /** Busca una entrada de un directorio por nombre. */
    private Fcb childOf(Fcb directory, String name) {
        for (Directory.Entry entry : Directory.parse(readContent(directory))) {
            if (entry.name().equals(name)) {
                Fcb child = fcbs.get(entry.fcbId());
                if (child == null) {
                    throw new PosFault("la entrada '" + name + "' apunta al FCB " + entry.fcbId()
                            + ", que no existe");
                }
                return child;
            }
        }
        return null;
    }

    /** Resuelve una ruta a su FCB. */
    public Fcb resolve(String path) {
        String normalized = normalize(path);
        if (ROOT.equals(normalized)) {
            return root();
        }
        Fcb current = root();
        for (String part : normalized.substring(1).split("/")) {
            if (!current.directory()) {
                throw new PosFault("'" + current.name() + "' no es un directorio");
            }
            Fcb child = childOf(current, part);
            if (child == null) {
                throw new PosFault("no existe '" + path + "': falta '" + part + "' en "
                        + describePathOf(current));
            }
            current = child;
        }
        return current;
    }

    private String describePathOf(Fcb fcb) {
        return fcb.id() == ROOT_ID ? ROOT : fcb.name();
    }

    /** Padre y nombre de una ruta que se va a crear. */
    private record Target(Fcb parent, String name) {
    }

    private Target target(String path) {
        String normalized = normalize(path);
        if (ROOT.equals(normalized)) {
            throw new PosFault("la raiz ya existe y no tiene padre");
        }
        int cut = normalized.lastIndexOf('/');
        String parentPath = cut == 0 ? ROOT : normalized.substring(0, cut);
        String name = normalized.substring(cut + 1);
        if (name.length() > Fcb.MAX_NAME) {
            throw new PosFault("el nombre '" + name + "' pasa de " + Fcb.MAX_NAME + " caracteres");
        }
        Fcb parent = resolve(parentPath);
        if (!parent.directory()) {
            throw new PosFault("'" + parentPath + "' no es un directorio");
        }
        return new Target(parent, name);
    }

    // ------------------------------------------------------------------
    // Operaciones de fichero
    // ------------------------------------------------------------------

    /** Crea un fichero con el contenido indicado. */
    public Fcb create(String path, byte[] content) {
        if (content == null) {
            throw new IllegalArgumentException("el contenido no puede ser nulo");
        }
        Target target = target(path);
        if (childOf(target.parent(), target.name()) != null) {
            throw new PosFault("ya existe '" + normalize(path) + "'");
        }
        int id = firstFreeId();
        Allocation allocation = storeNew(content);
        int now = nextTick();
        Fcb fcb = new Fcb(id, target.name(), false, content.length, allocation, now, now);
        fcbs.put(id, fcb);
        persist(fcb);
        addEntry(target.parent(), target.name(), id);
        return fcb;
    }

    /** Crea un fichero de texto, en UTF-8, por comodidad en las demos. */
    public Fcb create(String path, String text) {
        return create(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Crea un directorio vacio. */
    public Fcb mkdir(String path) {
        Target target = target(path);
        if (childOf(target.parent(), target.name()) != null) {
            throw new PosFault("ya existe '" + normalize(path) + "'");
        }
        int id = firstFreeId();
        int now = nextTick();
        Fcb fcb = new Fcb(id, target.name(), true, 0, new Allocation.Empty(), now, now);
        fcbs.put(id, fcb);
        persist(fcb);
        addEntry(target.parent(), target.name(), id);
        return fcb;
    }

    /** Contenido de un fichero. */
    public byte[] read(String path) {
        Fcb fcb = resolve(path);
        if (fcb.directory()) {
            throw new PosFault("'" + normalize(path) + "' es un directorio");
        }
        return readContent(fcb);
    }

    /** Contenido de un fichero como texto UTF-8. */
    public String readText(String path) {
        return new String(read(path), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** Sustituye el contenido de un fichero existente. */
    public Fcb write(String path, byte[] content) {
        Fcb fcb = resolve(path);
        if (fcb.directory()) {
            throw new PosFault("'" + normalize(path) + "' es un directorio");
        }
        return updateContent(fcb, content);
    }

    /** Sustituye el contenido de un fichero existente por texto UTF-8. */
    public Fcb write(String path, String text) {
        return write(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Anade contenido al final de un fichero. */
    public Fcb append(String path, byte[] extra) {
        Fcb fcb = resolve(path);
        if (fcb.directory()) {
            throw new PosFault("'" + normalize(path) + "' es un directorio");
        }
        byte[] current = readContent(fcb);
        byte[] combined = Arrays.copyOf(current, current.length + extra.length);
        System.arraycopy(extra, 0, combined, current.length, extra.length);
        return updateContent(fcb, combined);
    }

    /** Anade texto UTF-8 al final de un fichero. */
    public Fcb append(String path, String text) {
        return append(path, text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** Borra un fichero o un directorio vacio. */
    public void remove(String path) {
        Fcb fcb = resolve(path);
        if (fcb.id() == ROOT_ID) {
            throw new PosFault("no se puede borrar la raiz");
        }
        if (fcb.directory() && !Directory.parse(readContent(fcb)).isEmpty()) {
            throw new PosFault("el directorio '" + normalize(path) + "' no esta vacio");
        }
        Target target = target(path);
        method.release(image, free, fcb.allocation());
        fcbs.remove(fcb.id());
        eraseFcb(fcb.id());
        removeEntry(target.parent(), target.name());
        persistMetadata();
    }

    /** Entradas de un directorio, ordenadas por nombre. */
    public List<Directory.Entry> list(String path) {
        Fcb fcb = resolve(path);
        if (!fcb.directory()) {
            throw new PosFault("'" + normalize(path) + "' no es un directorio");
        }
        List<Directory.Entry> entries = new ArrayList<>(Directory.parse(readContent(fcb)));
        entries.sort(Comparator.comparing(Directory.Entry::name));
        return List.copyOf(entries);
    }

    /** FCB de cada entrada de un directorio, ordenados por nombre. */
    public List<Fcb> children(String path) {
        List<Fcb> children = new ArrayList<>();
        for (Directory.Entry entry : list(path)) {
            Fcb child = fcbs.get(entry.fcbId());
            if (child != null) {
                children.add(child);
            }
        }
        return List.copyOf(children);
    }

    /** Cambia el directorio de trabajo. */
    public String chdir(String path) {
        Fcb fcb = resolve(path);
        if (!fcb.directory()) {
            throw new PosFault("'" + normalize(path) + "' no es un directorio");
        }
        cwd = normalize(path);
        return cwd;
    }

    /** Todos los FCB cargados, incluida la raiz. */
    public List<Fcb> fcbs() {
        return List.copyOf(fcbs.values());
    }

    private int firstFreeId() {
        for (int id = 0; id < layout.maxFiles(); id++) {
            if (!fcbs.containsKey(id)) {
                return id;
            }
        }
        throw new PosFault("la tabla de FCB esta llena (" + layout.maxFiles()
                + " ficheros); hay que borrar alguno");
    }

    private void addEntry(Fcb directory, String name, int id) {
        List<Directory.Entry> entries = new ArrayList<>(Directory.parse(readContent(directory)));
        entries.add(new Directory.Entry(name, id));
        updateContent(directory, Directory.build(entries));
    }

    private void removeEntry(Fcb directory, String name) {
        List<Directory.Entry> entries = new ArrayList<>(Directory.parse(readContent(directory)));
        entries.removeIf(entry -> entry.name().equals(name));
        updateContent(directory, Directory.build(entries));
    }

    private int nextTick() {
        tick++;
        return tick;
    }

    // ------------------------------------------------------------------
    // Cambio de metodo en caliente
    // ------------------------------------------------------------------

    /**
     * Convierte la imagen entera al metodo indicado.
     *
     * Lee todo el contenido antes de tocar nada, para que un fallo a mitad no
     * deje la imagen a medias. Despues libera la zona de datos entera y vuelve a
     * colocar cada fichero con el metodo nuevo, reutilizando la tabla de FCB.
     */
    public Conversion switchMethod(AllocationMethod newMethod) {
        if (newMethod == null) {
            throw new IllegalArgumentException("hay que elegir metodo de asignacion");
        }
        AllocationMethod oldMethod = method;
        if (oldMethod.code() == newMethod.code()) {
            return new Conversion(oldMethod.name(), newMethod.name(), 0, 0, 0);
        }

        Map<Integer, byte[]> contents = new LinkedHashMap<>();
        for (Fcb fcb : fcbs.values()) {
            contents.put(fcb.id(), readContent(fcb));
        }

        image.resetCounters();
        free.markFree(layout.dataFirst(), layout.dataBlocks());
        method = newMethod;

        int written = 0;
        for (Fcb fcb : new ArrayList<>(fcbs.values())) {
            Allocation allocation = storeNew(contents.get(fcb.id()));
            Fcb converted = fcb.withContent(contents.get(fcb.id()).length, allocation, fcb.modified());
            fcbs.put(converted.id(), converted);
            persist(converted);
            written++;
        }
        persistMetadata();
        return new Conversion(oldMethod.name(), newMethod.name(), written,
                image.blockReads(), image.blockWrites());
    }

    /** Resultado de una conversion de metodo. */
    public record Conversion(String from, String to, int files, long reads, long writes) {

        public String describe() {
            return "convertidos " + files + " ficheros de " + from + " a " + to + ": "
                    + reads + " lecturas y " + writes + " escrituras de bloque";
        }
    }

    // ------------------------------------------------------------------
    // Estado y verificacion
    // ------------------------------------------------------------------

    public DiskImage image() {
        return image;
    }

    public FsLayout layout() {
        return layout;
    }

    public AllocationMethod method() {
        return method;
    }

    public Superblock superblock() {
        return superblock;
    }

    public FreeBlockMap freeMap() {
        return free;
    }

    /** Uso del disco en una linea. */
    public Usage usage() {
        int files = 0;
        int directories = 0;
        int dataUsed = 0;
        for (Fcb fcb : fcbs.values()) {
            if (fcb.directory()) {
                directories++;
            } else {
                files++;
            }
            dataUsed += fcb.allocation().dataBlocks();
        }
        return new Usage(layout.blockCount(), layout.blockSize(), layout.dataBlocks(),
                free.freeCount(), free.usedCount(), dataUsed, layout.maxFiles(),
                files, directories, method.name(),
                free.freeSpans().stream().filter(span -> layout.isDataBlock(span.start())).count());
    }

    /** Resumen del uso del disco. */
    public record Usage(int blocks, int blockSize, int dataRegion, int freeBlocks, int usedBlocks,
            int dataBlocksInFiles, int maxFiles, int files, int directories, String method,
            long freeSpans) {

        public String describe() {
            return blocks + " bloques de " + blockSize + " bytes, " + freeBlocks + " libres ("
                    + (100 * freeBlocks / blocks) + "%), " + files + " ficheros y "
                    + directories + " directorios, metodo " + method + ", "
                    + freeSpans + " huecos libres";
        }
    }

    /** Ejecuta la verificacion completa de la imagen. */
    public FsCheck verify() {
        return FsCheck.run(image, layout, superblock, free, fcbs, method, loadProblems);
    }

    /** Vuelca la imagen a bytes para guardarla en un fichero. */
    public byte[] save() {
        persistMetadata();
        return image.toByteArray();
    }
}
