package pos.sync;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import pos.base.PosFault;
import pos.proc.State;
import pos.proc.Tcb;

/**
 * Banquero: reparto de recursos con varias instancias y prevencion del
 * interbloqueo.
 *
 * El gestor de cerrojos del sistema detecta el ciclo cuando ya ha ocurrido. El
 * banquero hace lo contrario: no deja que ocurra. Para eso hay que saber antes
 * de empezar cuanto va a pedir cada proceso, y esa declaracion es lo que lo
 * hace posible. Sin declarar el maximo no hay forma de distinguir "este proceso
 * quiere dos impresoras" de "este proceso quiere todas las que haya", y con esa
 * duda el unico reparto prudente es no conceder nada.
 *
 * El protocolo, en el orden en que lo usa un proceso:
 * <ol>
 *   <li>El sistema declara los recursos que hay y cuantas instancias tiene cada
 *       uno ({@link #declareResource}). Un recurso es un tipo, no un objeto: hay
 *       tres impresoras, no la impresora uno y la dos.</li>
 *   <li>El proceso declara su maximo por recurso ({@link #claim}). Es una
 *       promesa: no volvera a pedir mas de eso. Pedir mas es un error del
 *       programa y se denuncia, en vez de concederse.</li>
 *   <li>El proceso pide ({@link #request}). El banquero concede si quedan
 *       instancias <em>y</em> el reparto resultante es seguro.</li>
 *   <li>El proceso devuelve ({@link #release}). Entonces se reintentan las
 *       peticiones que quedaron esperando, en orden de llegada.</li>
 * </ol>
 *
 * Lo que distingue al banquero de un simple contador es el tercer paso. Un
 * contador mira cuantas instancias quedan libres y las da. Eso conduce al
 * abrazo mortal, porque cada concesion por separado parece razonable y el
 * conjunto no lo es. El banquero, antes de conceder, ensaya el reparto y
 * comprueba que existe al menos un orden en el que todos los procesos podrian
 * terminar. Si no lo hay, no concede, y el proceso espera aunque queden
 * instancias libres. Esa negativa con recursos disponibles es el resultado que
 * hay que saber explicar: no es un fallo, es la prevencion funcionando.
 *
 * Ni el ensayo ni la concesion se hacen a medias: el ensayo trabaja sobre
 * copias de los vectores y solo si sale seguro se toca el estado real.
 *
 * Este objeto no cambia el estado de los hilos ni los mueve de sitio. Dice
 * quien puede seguir y quien tiene que esperar; el nucleo es el que bloquea y
 * el que despierta.
 */
public final class Banker {

    /** Resultado de una peticion. */
    public enum Verdict {

        /** Se concede en el acto. */
        GRANTED,

        /**
         * No se concede. El hilo se bloquea y la peticion queda apuntada para
         * reintentarla cuando alguien devuelva instancias.
         */
        WAIT,

        /**
         * Pide mas de lo que declaro al nacer. No es que no haya recursos: es
         * que el proceso ha roto su promesa, y cumplirla hacia atras no se
         * puede.
         */
        EXCEEDS_MAX
    }

    /**
     * Respuesta a una peticion, con el motivo escrito.
     *
     * El motivo viaja con el veredicto a proposito: la frase que explica por que
     * no se concede es la que acaba en la traza y en la demostracion, y
     * reconstruirla despues, cuando el estado ya ha cambiado, no siempre da lo
     * mismo.
     */
    public record Answer(Verdict verdict, String reason) {
    }

    /** Una fila de la tabla: un proceso y su relacion con un recurso. */
    public record Row(String process, String resource, int maximum, int allocated, int need) {

        /** Texto de la fila, alineado como el resto de tablas del sistema. */
        public String line() {
            return String.format("%-12s %-12s %9d %9d %9d",
                    process, resource, maximum, allocated, need);
        }
    }

    /**
     * Peticion que no se pudo atender y espera a que alguien devuelva.
     *
     * Guarda el motivo ademas de la peticion porque hay dos motivos muy
     * distintos para esperar -no quedan instancias, o quedan pero no se pueden
     * dar- y quien lee la traza necesita saber cual de los dos es.
     */
    private record Pending(Tcb thread, String resource, int amount, String reason) {

        int pid() {
            return thread.process().id();
        }
    }

    /** Instancias totales de cada recurso. */
    private final Map<String, Integer> capacity = new LinkedHashMap<>();

    /** Nombre legible de cada proceso, para las tablas. */
    private final Map<Integer, String> names = new LinkedHashMap<>();

    /** Maximo declarado: proceso a recurso a instancias. */
    private final Map<Integer, Map<String, Integer>> maximum = new LinkedHashMap<>();

    /** Instancias en poder de cada proceso: proceso a recurso a instancias. */
    private final Map<Integer, Map<String, Integer>> allocated = new LinkedHashMap<>();

    /** Peticiones sin atender, en orden de llegada. */
    private final List<Pending> pending = new ArrayList<>();

    /** Hilos que acaban de recibir lo que pidieron. */
    private final List<Tcb> granted = new ArrayList<>();

    // ------------------------------------------------------------------
    // Declaracion
    // ------------------------------------------------------------------

    /**
     * Declara un recurso con su numero de instancias.
     *
     * Se declara una sola vez. Volver a declararlo con otro numero escondería
     * instancias ya repartidas o prometeria unas que no existen, asi que se
     * denuncia.
     */
    public void declareResource(String name, int instances) {
        if (instances <= 0) {
            throw new PosFault("el recurso '" + name + "' no puede tener " + instances
                    + " instancias; un recurso que no existe no se declara");
        }
        if (capacity.containsKey(name)) {
            throw new PosFault("el recurso '" + name + "' ya esta declarado con "
                    + capacity.get(name) + " instancias");
        }
        capacity.put(name, instances);
    }

    /**
     * Anota el maximo que un proceso promete pedir de un recurso.
     *
     * La promesa se guarda por proceso, no por hilo: los recursos son del
     * proceso, igual que la memoria. Que el maximo supere las instancias del
     * sistema se rechaza aqui, porque un proceso que promete pedir mas de lo que
     * hay nunca podria terminar con sus pedidos satisfechos y estaria pidiendo
     * un imposible por escrito.
     */
    public void claim(Tcb thread, String resource, int declared) {
        requireResource(resource);
        int pid = thread.process().id();
        if (declared <= 0) {
            throw new PosFault(thread.id() + ": el maximo declarado de '" + resource
                    + "' tiene que ser al menos 1, no " + declared);
        }
        Map<String, Integer> row = maximum.computeIfAbsent(pid, key -> new LinkedHashMap<>());
        if (row.containsKey(resource)) {
            throw new PosFault(thread.id() + ": el proceso '" + nameOf(pid)
                    + "' ya declaro un maximo de " + row.get(resource) + " para '" + resource
                    + "'; declararlo otra vez dejaria dos promesas incompatibles");
        }
        if (declared > capacity.get(resource)) {
            throw new PosFault(thread.id() + ": el proceso '" + nameOf(pid) + "' declara un maximo de "
                    + declared + " para '" + resource + "', pero el sistema solo tiene "
                    + capacity.get(resource));
        }
        row.put(resource, declared);
        names.putIfAbsent(pid, thread.process().name());
    }

    // ------------------------------------------------------------------
    // Peticion y devolucion
    // ------------------------------------------------------------------

    /**
     * Atiende una peticion de {@code amount} instancias de un recurso.
     *
     * Concede solo si el reparto que quedaria sigue siendo seguro. Si hay que
     * esperar, la peticion se apunta y el hilo se bloquea: el recurso no se le
     * da mas tarde por su cuenta, hace falta que alguien devuelva instancias.
     */
    public Answer request(Tcb thread, String resource, int amount) {
        requireResource(resource);
        int pid = thread.process().id();
        if (amount <= 0) {
            throw new PosFault(thread.id() + ": peticion de " + amount
                    + " instancias de '" + resource + "'");
        }
        int declared = maxOf(pid, resource);
        if (declared <= 0) {
            throw new PosFault(thread.id() + ": pide '" + resource
                    + "' sin haber declarado un maximo; el banquero no puede decidir sin saber"
                    + " cuanto va a pedir como mucho");
        }
        int held = heldOf(pid, resource);
        if (held + amount > declared) {
            return new Answer(Verdict.EXCEEDS_MAX, "el proceso '" + nameOf(pid) + "' pide "
                    + amount + " de '" + resource + "' y ya tiene " + held
                    + ", pero declaro como maximo " + declared);
        }
        int free = availableOf(resource);
        if (free < amount) {
            String motivo = "no quedan instancias libres de '" + resource + "': "
                    + (amount == 1 ? "pide 1" : "pide " + amount) + " y hay " + free
                    + " de " + capacity.get(resource);
            pending.add(new Pending(thread, resource, amount, motivo));
            return new Answer(Verdict.WAIT, motivo);
        }
        if (!safeAfter(pid, resource, amount)) {
            // El caso que hay que saber explicar: quedan instancias y no se dan.
            String motivo = libres(free) + " de '" + resource + "' y "
                    + (amount == 1 ? "se pide 1" : "se piden " + amount)
                    + ", pero concederlas dejaria el reparto sin ningun orden seguro";
            pending.add(new Pending(thread, resource, amount, motivo));
            return new Answer(Verdict.WAIT, motivo);
        }
        grant(pid, resource, amount);
        return new Answer(Verdict.GRANTED, "concede " + amount + " de '" + resource
                + "' al proceso '" + nameOf(pid) + "', quedan " + availableOf(resource));
    }

    /** "queda 1 instancia" / "quedan 2 instancias", para que la traza se lea. */
    private static String libres(int free) {
        return free == 1 ? "queda 1 instancia" : "quedan " + free + " instancias";
    }

    /**
     * Devuelve instancias al monton y reintenta lo que estaba esperando.
     *
     * @return hilos que quedan atendidos por efecto de esta devolucion, en orden
     *         de concesion; el nucleo es quien los devuelve a la cola de listos
     */
    public List<Tcb> release(Tcb thread, String resource, int amount) {
        requireResource(resource);
        int pid = thread.process().id();
        if (amount <= 0) {
            throw new PosFault(thread.id() + ": devolucion de " + amount
                    + " instancias de '" + resource + "'");
        }
        int held = heldOf(pid, resource);
        if (amount > held) {
            throw new PosFault(thread.id() + ": devuelve " + amount + " de '" + resource
                    + "' pero el proceso '" + nameOf(pid) + "' solo tiene " + held);
        }
        Map<String, Integer> row = allocated.get(pid);
        row.merge(resource, -amount, Integer::sum);
        if (row.get(resource) == 0) {
            row.remove(resource);
        }
        return retryPending();
    }

    /**
     * Devuelve todo lo que tiene un proceso y lo saca de la tabla.
     *
     * Se llama cuando el proceso termina. No es un detalle de limpieza: si los
     * recursos de un muerto se quedaran asignados, el banquero los contaria como
     * ocupados para siempre y acabaria negando peticiones por un proceso que ya
     * no existe.
     */
    public List<Tcb> releaseAll(int pid) {
        maximum.remove(pid);
        allocated.remove(pid);
        pending.removeIf(request -> request.pid() == pid);
        return retryPending();
    }

    /**
     * Reintenta las peticiones pendientes en orden de llegada.
     *
     * Una concesion puede hacer posible la siguiente, asi que se repite mientras
     * algo avance. El orden es FIFO y no se altera: quien esperaba antes se
     * atiende antes, aunque un recien llegado tambien cupiera.
     */
    private List<Tcb> retryPending() {
        List<Tcb> woken = new ArrayList<>();
        boolean progress = true;
        while (progress && !pending.isEmpty()) {
            progress = false;
            Iterator<Pending> iterator = pending.iterator();
            while (iterator.hasNext()) {
                Pending request = iterator.next();
                if (isGone(request)) {
                    iterator.remove();
                    continue;
                }
                int pid = request.pid();
                if (availableOf(request.resource()) < request.amount()) {
                    continue;
                }
                if (!safeAfter(pid, request.resource(), request.amount())) {
                    continue;
                }
                grant(pid, request.resource(), request.amount());
                iterator.remove();
                woken.add(request.thread());
                granted.add(request.thread());
                progress = true;
            }
        }
        return List.copyOf(woken);
    }

    /** Un hilo que ya no existe no debe seguir ocupando sitio en la cola. */
    private boolean isGone(Pending request) {
        return request.thread().state() == State.TERMINATED
                || !maximum.containsKey(request.pid());
    }

    // ------------------------------------------------------------------
    // Estado seguro
    // ------------------------------------------------------------------

    /**
     * Orden en el que podrian terminar todos los procesos si cada uno devuelve
     * lo que tiene al acabar.
     *
     * Se conoce como secuencia segura. Si la lista no incluye a todos los
     * procesos con maximos declarados, el estado no es seguro: hay al menos un
     * conjunto de procesos que se quedaria esperando para siempre.
     */
    public List<String> safeSequence() {
        return sequence(-1, null, 0);
    }

    /** El estado actual, sin concesiones de por medio, es seguro. */
    public boolean isSafe() {
        return maximum.isEmpty() || safeSequence().size() == maximum.size();
    }

    /**
     * Si el estado no es seguro, los procesos que se quedan fuera del orden.
     *
     * Es diagnostico, no decision: nada de lo que hace el banquero depende de
     * esta lista. Con el reparto siempre comprobado, un estado inseguro no se
     * alcanza pidiendo; solo se veria si alguien repartiera sin preguntar.
     */
    private List<String> unsafeLeftover() {
        List<String> order = safeSequence();
        List<String> leftover = new ArrayList<>();
        for (Integer pid : maximum.keySet()) {
            String name = nameOf(pid);
            if (!order.contains(name)) {
                leftover.add(name);
            }
        }
        return List.copyOf(leftover);
    }

    /** Ensaya la concesion sobre copias y dice si el resultado seguiria siendo seguro. */
    private boolean safeAfter(int pid, String resource, int amount) {
        return maximum.isEmpty() || sequence(pid, resource, amount).size() == maximum.size();
    }

    /**
     * Algoritmo de comprobacion, con una concesion hipotetica opcional.
     *
     * Se parte de las instancias libres y se pregunta una y otra vez si algun
     * proceso sin terminar podria acabar con lo que hay libre. Cuando uno puede,
     * se supone que termina y devuelve todo lo suyo, y el monton crece. Si al
     * final todos han podido, el estado es seguro.
     *
     * No hay optimizacion ni heuristica: la eleccion de cual termina primero es
     * la del primero que cabe, y el resultado -seguro o no- no depende de ese
     * orden. Esa es justo la propiedad que hace util el algoritmo.
     *
     * @param pid        proceso al que se le supone la concesion, o -1 para
     *                   comprobar el estado tal cual esta
     * @param resource   recurso que se le concederia
     * @param amount     instancias que se le concederian
     */
    private List<String> sequence(int pid, String resource, int amount) {
        Map<String, Integer> work = available();
        if (pid >= 0) {
            work.merge(resource, -amount, Integer::sum);
        }
        List<Integer> unfinished = new ArrayList<>(maximum.keySet());
        List<String> order = new ArrayList<>();
        boolean progress = true;
        while (progress && !unfinished.isEmpty()) {
            progress = false;
            Iterator<Integer> iterator = unfinished.iterator();
            while (iterator.hasNext()) {
                int candidate = iterator.next();
                if (!fits(candidate, pid, resource, amount, work)) {
                    continue;
                }
                heldBy(candidate, pid, resource, amount)
                        .forEach((name, count) -> work.merge(name, count, Integer::sum));
                order.add(nameOf(candidate));
                iterator.remove();
                progress = true;
            }
        }
        return List.copyOf(order);
    }

    /** Si todo lo que le falta al proceso cabe en lo que queda libre. */
    private boolean fits(int pid, int target, String resource, int amount,
            Map<String, Integer> work) {
        for (String name : capacity.keySet()) {
            int need = Math.max(0, maxOf(pid, name) - heldHeld(pid, name, target, resource, amount));
            if (need > work.getOrDefault(name, 0)) {
                return false;
            }
        }
        return true;
    }

    /** Lo que el proceso tiene, contando la concesion hipotetica si es el objetivo. */
    private Map<String, Integer> heldBy(int pid, int target, String resource, int amount) {
        Map<String, Integer> holds = new LinkedHashMap<>();
        for (String name : capacity.keySet()) {
            int held = heldHeld(pid, name, target, resource, amount);
            if (held > 0) {
                holds.put(name, held);
            }
        }
        return holds;
    }

    private int heldHeld(int pid, String name, int target, String resource, int amount) {
        int held = heldOf(pid, name);
        if (pid == target && name.equals(resource)) {
            held += amount;
        }
        return held;
    }

    // ------------------------------------------------------------------
    // Consultas
    // ------------------------------------------------------------------

    /** Instancias libres de cada recurso, en el orden en que se declararon. */
    public Map<String, Integer> available() {
        Map<String, Integer> free = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : capacity.entrySet()) {
            int taken = 0;
            for (Map<String, Integer> row : allocated.values()) {
                taken += row.getOrDefault(entry.getKey(), 0);
            }
            free.put(entry.getKey(), entry.getValue() - taken);
        }
        return free;
    }

    /** Instancias libres de un recurso, o -1 si el recurso no existe. */
    public int availableOf(String resource) {
        Integer total = capacity.get(resource);
        if (total == null) {
            return -1;
        }
        int taken = 0;
        for (Map<String, Integer> row : allocated.values()) {
            taken += row.getOrDefault(resource, 0);
        }
        return total - taken;
    }

    /** Instancias totales de un recurso, o -1 si no existe. */
    public int capacityOf(String resource) {
        return capacity.getOrDefault(resource, -1);
    }

    /** Peticiones que siguen esperando. */
    public int pendingCount() {
        return pending.size();
    }

    /** Hilos que esperan instancias de un recurso concreto. */
    public int waitingCount(String resource) {
        int count = 0;
        for (Pending request : pending) {
            if (request.resource().equals(resource)) {
                count++;
            }
        }
        return count;
    }

    /** Cuantas instancias espera un hilo, o 0 si no espera ninguna. */
    public int pendingFor(Tcb thread) {
        for (Pending request : pending) {
            if (request.thread() == thread) {
                return request.amount();
            }
        }
        return 0;
    }

    /** Por que espera un hilo, en texto, o cadena vacia si no espera. */
    public String pendingReason(Tcb thread) {
        for (Pending request : pending) {
            if (request.thread() == thread) {
                return request.reason();
            }
        }
        return "";
    }

    /** Lo que tiene un proceso, en texto, para las trazas. Vacio si no tiene nada. */
    public String heldSummary(int pid) {
        Map<String, Integer> row = allocated.get(pid);
        if (row == null || row.isEmpty()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        row.forEach((resource, count) -> text.append(text.length() == 0 ? "" : ", ")
                .append(count).append(" de '").append(resource).append("'"));
        return text.toString();
    }

    /** Nombres de los recursos declarados, en orden. */
    public List<String> resources() {
        return List.copyOf(capacity.keySet());
    }

    /** Hilos que acaban de recibir lo que pidieron, y se vacia la lista. */
    public List<Tcb> drainGranted() {
        List<Tcb> woken = List.copyOf(granted);
        granted.clear();
        return woken;
    }

    /** Filas de la tabla, para trazas, terminal y pantalla. */
    public List<Row> table() {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Integer, Map<String, Integer>> entry : maximum.entrySet()) {
            int pid = entry.getKey();
            for (String resource : capacity.keySet()) {
                int declared = maxOf(pid, resource);
                int held = heldOf(pid, resource);
                if (declared == 0 && held == 0) {
                    continue;
                }
                rows.add(new Row(nameOf(pid), resource, declared, held, Math.max(0, declared - held)));
            }
        }
        return List.copyOf(rows);
    }

    /** Texto de la tabla completa, con la cabecera y las instancias libres. */
    public List<String> describe() {
        List<String> lines = new ArrayList<>();
        lines.add(String.format("%-12s %-12s %9s %9s %9s",
                "proceso", "recurso", "maximo", "tiene", "le falta"));
        for (Row row : table()) {
            lines.add(row.line());
        }
        StringBuilder free = new StringBuilder();
        available().forEach((name, count) -> free.append(free.length() == 0 ? "" : ", ")
                .append(name).append(" ").append(count).append(" de ").append(capacity.get(name)));
        lines.add("libres: " + (free.length() == 0 ? "ninguno declarado" : free));
        List<String> order = safeSequence();
        if (!maximum.isEmpty()) {
            lines.add(order.size() == maximum.size()
                    ? "estado seguro, un orden posible: " + String.join(" > ", order)
                    : "estado inseguro: no caben " + String.join(", ", unsafeLeftover()));
        }
        if (!pending.isEmpty()) {
            lines.add("esperando: " + String.join("; ", describeBlocked()));
        }
        return List.copyOf(lines);
    }

    /** Quien espera que, para el informe de bloqueo del nucleo. */
    public List<String> describeBlocked() {
        List<String> lines = new ArrayList<>();
        for (Pending request : pending) {
            lines.add(request.thread().id() + " espera " + request.amount() + " de '"
                    + request.resource() + "'");
        }
        return List.copyOf(lines);
    }

    // ------------------------------------------------------------------
    // Apoyo
    // ------------------------------------------------------------------

    private void grant(int pid, String resource, int amount) {
        allocated.computeIfAbsent(pid, key -> new LinkedHashMap<>())
                .merge(resource, amount, Integer::sum);
    }

    private int maxOf(int pid, String resource) {
        Map<String, Integer> row = maximum.get(pid);
        return row == null ? 0 : row.getOrDefault(resource, 0);
    }

    private int heldOf(int pid, String resource) {
        Map<String, Integer> row = allocated.get(pid);
        return row == null ? 0 : row.getOrDefault(resource, 0);
    }

    private String nameOf(int pid) {
        String name = names.get(pid);
        return name == null ? "p" + pid : name;
    }

    private void requireResource(String resource) {
        if (!capacity.containsKey(resource)) {
            throw new PosFault("no existe el recurso '" + resource
                    + "'; los recursos se declaran antes de usarlos");
        }
    }
}
