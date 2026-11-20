package pos.shell;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * El teclado de verdad, leido tecla a tecla.
 *
 * {@link LiveKeys} lee lineas enteras porque el terminal, por defecto, las
 * entrega asi: el sistema operativo de debajo acumula lo tecleado hasta el salto
 * de linea y no deja ver nada antes. Con esa entrada, las flechas dejan de ser
 * flechas: llegan como basura, y el historial no se puede implementar. No es una
 * limitacion de este programa, es lo que hace el modo cocinado del terminal.
 *
 * La salida es poner el terminal en modo crudo: {@code stty raw -echo}. En crudo,
 * cada tecla llega suelta, el retroceso y Ctrl-C llegan como bytes en vez de
 * actuar sobre el terminal, y por eso hay que pintar el eco a mano. Tambien deja
 * de traducir el salto de linea, asi que aqui todo fin de linea se escribe
 * {@code \r\n} y no {@code \n}.
 *
 * El modo crudo se apaga siempre al terminar, incluso si el programa muere por el
 * camino. Un terminal que se queda en crudo es un terminal roto para el usuario:
 * las teclas dejan de verse y hay que escribir {@code stty sane} a ciegas.
 *
 * Quien decide si se usa esto o {@link LiveKeys} es {@link pos.launch.InteractiveShell}:
 * con un terminal delante se lee tecla a tecla; con una tuberia o un fichero, no
 * hay a quien pedirle modo crudo y se leen lineas, que es lo que la entrada trae.
 *
 * Como {@link LiveKeys}, este hilo no ejecuta nada del sistema: deja lineas en una
 * cola y la terminal las recoge en su turno. El aviso de la orden lo pinta el
 * nucleo a traves de {@link #prompt(String)}, nunca este hilo, porque el aviso
 * depende del directorio actual y eso es estado del sistema.
 */
public final class TtyKeys implements Keys {

    /** Tics que la terminal duerme entre dos miradas a la cola. */
    public static final long POLL_TICKS = 2;

    /** Bytes que se leen como maximo dentro de una secuencia de escape. */
    private static final int MAX_ESCAPE = 8;

    private final InputStream in;
    private final PrintStream out;
    private final LongSupplier clock;
    private final boolean rawMode;
    private final LineEditor editor;
    /** Nombres de orden, copiados al construir: el hilo lector no toca el shell. */
    private final List<String> commandNames;

    private final Deque<Keystroke> queue = new ArrayDeque<>();
    private final Object lock = new Object();
    private final Object screen = new Object();
    private final Thread reader;

    private volatile boolean closed;
    private String prompt = "";
    private boolean promptShown;
    /** Estado del terminal antes del modo crudo, para devolverlo tal cual. */
    private String savedStty;

    /**
     * @param in de donde se leen las teclas, normalmente la entrada estandar
     * @param out donde se pinta el eco y el aviso
     * @param clock reloj del nucleo, para fechar cada linea cuando llega
     * @param history historial vivo de la sesion
     * @param commands nombres de orden para el tabulador
     * @param rawMode si hay que poner el terminal en crudo; false en las pruebas,
     *     que no tienen terminal que configurar
     */
    public TtyKeys(InputStream in, PrintStream out, LongSupplier clock,
            List<String> history, List<String> commands, boolean rawMode) {
        if (in == null || out == null || clock == null) {
            throw new IllegalArgumentException("la entrada con editor necesita flujo, salida y reloj");
        }
        this.in = in;
        this.out = out;
        this.clock = clock;
        this.rawMode = rawMode;
        this.editor = new LineEditor(history, completer(commands));
        this.commandNames = commands == null ? List.of()
                : commands.stream().filter(n -> n != null && !n.isBlank()).sorted().toList();
        this.reader = new Thread(this::readLoop, "teclado");
        // Demonio: si la sesion termina, el hilo no puede sujetar la maquina
        // virtual abierta esperando una tecla que ya no va a llegar.
        this.reader.setDaemon(true);
        if (rawMode) {
            // Red de seguridad: si se sale por donde no se espera, el terminal
            // vuelve a su estado aunque nadie lo pida.
            Runtime.getRuntime().addShutdownHook(new Thread(this::restoreTerminal,
                    "restaurar-terminal"));
        }
    }

    /**
     * Prepara un teclado con editor, o devuelve {@code null} si no puede.
     *
     * Poner el terminal en crudo es cosa de {@code stty}, que existe en los
     * sistemas de Unix y no en Windows. Cuando no esta, no se puede fingir: sin
     * modo crudo el terminal sigue en modo cocinado, las flechas llegan como
     * basura y el eco se pinta dos veces. Antes que dejar la sesion rota, se
     * devuelve {@code null} y quien llama usa la lectura por lineas.
     *
     * @return el teclado listo, o {@code null} si aqui no hay modo crudo
     */
    public static TtyKeys conTerminalCrudo(InputStream in, PrintStream out, LongSupplier clock,
            List<String> history, List<String> commands) {
        TtyKeys keys = new TtyKeys(in, out, clock, history, commands, true);
        return keys.enterRawMode() ? keys : null;
    }

    /** Arranca la lectura. */
    public void start() {
        enterRawMode();
        reader.start();
    }

    @Override
    public boolean drawsPrompt() {
        return true;
    }

    /**
     * El aviso lo pinta el editor, para que quede pegado a lo que se escribe.
     *
     * Lo llama el nucleo en su turno, asi que aqui se puede pedir el aviso al
     * shell sin miedo. Solo se repinta si el aviso ha cambiado: el nucleo pasa
     * por aqui cada dos tics y repintar siempre seria un parpadeo constante.
     */
    @Override
    public void prompt(String text) {
        boolean changed;
        synchronized (lock) {
            changed = !promptShown || !prompt.equals(text);
            prompt = text;
            promptShown = true;
        }
        if (changed) {
            redraw();
        }
    }

    @Override
    public boolean hasNext() {
        synchronized (lock) {
            return !queue.isEmpty() || !closed;
        }
    }

    @Override
    public long nextTick() {
        synchronized (lock) {
            if (!queue.isEmpty()) {
                return clock.getAsLong();
            }
            if (closed) {
                return -1L;
            }
            return clock.getAsLong() + POLL_TICKS;
        }
    }

    @Override
    public Keystroke next() {
        synchronized (lock) {
            if (queue.isEmpty()) {
                throw new IllegalStateException("no hay linea que entregar");
            }
            return queue.removeFirst();
        }
    }

    @Override
    public int pending() {
        synchronized (lock) {
            return queue.size();
        }
    }

    // ------------------------------------------------------------------
    // Lectura de teclas
    // ------------------------------------------------------------------

    private void readLoop() {
        try {
            while (true) {
                Key key = readKey();
                if (key == null) {
                    break;
                }
                String before = editor.text();
                LineEditor.Result result = editor.feed(key);
                switch (result.action()) {
                    case CONTINUE -> {
                        if (key.kind() == Key.Kind.TAB && before.equals(editor.text())) {
                            // El tabulador no pudo alargar nada: si hay varias
                            // ordenes que empiezan igual, se dicen. Callarse deja
                            // al que escribe sin saber si funciono.
                            offerCandidates();
                        }
                        redraw();
                    }
                    case SUBMIT -> submit(result.text());
                    case CANCEL -> cancel();
                    case EOF -> {
                        finish();
                        return;
                    }
                    default -> redraw();
                }
            }
        } catch (IOException e) {
            // Un fallo de lectura se trata como fin de entrada: la sesion acaba
            // por donde empezo, sin mas lineas que ejecutar.
        } finally {
            synchronized (lock) {
                closed = true;
                lock.notifyAll();
            }
            restoreTerminal();
        }
    }

    /** Una tecla, ya decodificada. {@code null} al cerrarse la entrada. */
    private Key readKey() throws IOException {
        int first = in.read();
        if (first < 0) {
            return null;
        }
        if (first == 0x1b) {
            return escape();
        }
        switch (first) {
            case '\r', '\n':
                return Key.of(Key.Kind.ENTER);
            case 0x7f, 0x08:
                return Key.of(Key.Kind.BACKSPACE);
            case 0x03:
                return Key.of(Key.Kind.CANCEL);
            case 0x04:
                return Key.of(Key.Kind.EOF);
            case 0x09:
                return Key.of(Key.Kind.TAB);
            case 0x01:
                return Key.of(Key.Kind.HOME);
            case 0x05:
                return Key.of(Key.Kind.END);
            case 0x0b:
                return Key.of(Key.Kind.KILL_END);
            case 0x15:
                return Key.of(Key.Kind.KILL_LINE);
            case 0x10:
                return Key.of(Key.Kind.UP);
            case 0x0e:
                return Key.of(Key.Kind.DOWN);
            default:
                break;
        }
        // Los bytes altos son UTF-8: hasta que no llega la secuencia entera no
        // se sabe que letra es.
        int codepoint = first < 0x80 ? first : readCodepoint(first);
        if (codepoint < 0x20) {
            // Cualquier otro caracter de control no se escribe.
            return Key.of(Key.Kind.IGNORED);
        }
        return Key.ch(codepoint);
    }

    /**
     * Una secuencia de escape: casi siempre una flecha.
     *
     * Las flechas mandan ESC seguido de '[' y una letra; Inicio, Fin, Suprimir y
     * compania mandan ESC '[' numeros '~'. Todo lo que no se reconoce se ignora,
     * que es mejor que escribir la secuencia en crudo en mitad de la linea.
     */
    private Key escape() throws IOException {
        int second = in.read();
        if (second < 0) {
            return null;
        }
        if (second != '[' && second != 'O') {
            // ESC y otra cosa (Alt+tecla): no se usa para nada aqui.
            return Key.of(Key.Kind.IGNORED);
        }
        StringBuilder params = new StringBuilder();
        for (int i = 0; i < MAX_ESCAPE; i++) {
            int c = in.read();
            if (c < 0) {
                return null;
            }
            if (c >= 0x40 && c <= 0x7e) {
                return escapeKey(params.toString(), (char) c);
            }
            params.append((char) c);
        }
        return Key.of(Key.Kind.IGNORED);
    }

    private Key escapeKey(String params, char last) {
        return switch (last) {
            case 'A' -> Key.of(Key.Kind.UP);
            case 'B' -> Key.of(Key.Kind.DOWN);
            case 'C' -> Key.of(Key.Kind.RIGHT);
            case 'D' -> Key.of(Key.Kind.LEFT);
            case 'H' -> Key.of(Key.Kind.HOME);
            case 'F' -> Key.of(Key.Kind.END);
            case '~' -> switch (params) {
                case "1", "7" -> Key.of(Key.Kind.HOME);
                case "4", "8" -> Key.of(Key.Kind.END);
                case "3" -> Key.of(Key.Kind.DELETE);
                default -> Key.of(Key.Kind.IGNORED);
            };
            default -> Key.of(Key.Kind.IGNORED);
        };
    }

    /** Decodifica una letra UTF-8 cuyo primer byte ya se leyo. */
    private int readCodepoint(int first) throws IOException {
        int extra;
        int codepoint;
        if ((first & 0xE0) == 0xC0) {
            extra = 1;
            codepoint = first & 0x1F;
        } else if ((first & 0xF0) == 0xE0) {
            extra = 2;
            codepoint = first & 0x0F;
        } else if ((first & 0xF8) == 0xF0) {
            extra = 3;
            codepoint = first & 0x07;
        } else {
            return '?';
        }
        for (int i = 0; i < extra; i++) {
            int b = in.read();
            if (b < 0 || (b & 0xC0) != 0x80) {
                return '?';
            }
            codepoint = (codepoint << 6) | (b & 0x3F);
        }
        return codepoint;
    }

    // ------------------------------------------------------------------
    // Pintado
    // ------------------------------------------------------------------

    /**
     * Ensenia las ordenes que empiezan por lo que hay antes del cursor.
     *
     * Solo cuando hay mas de una. Con una sola, el tabulador ya la habria
     * completado y esto no llega a ejecutarse.
     */
    private void offerCandidates() {
        String prefix = editor.wordPrefix();
        if (prefix.isEmpty()) {
            return;
        }
        List<String> hits = commandNames.stream().filter(n -> n.startsWith(prefix)).toList();
        if (hits.size() < 2) {
            return;
        }
        synchronized (screen) {
            out.print("\r\n" + String.join("  ", hits) + "\r\n");
            out.flush();
        }
    }

    /** Repinta el aviso y lo escrito, dejando el cursor donde toca. */
    private void redraw() {
        String shown;
        synchronized (lock) {
            shown = prompt;
            promptShown = true;
        }
        String text = editor.text();
        int cursor = editor.cursor();
        synchronized (screen) {
            out.print("\r" + shown + text + "\u001b[K");
            int back = text.length() - cursor;
            if (back > 0) {
                out.print("\u001b[" + back + "D");
            }
            out.flush();
        }
    }

    private void submit(String line) {
        synchronized (screen) {
            out.print("\r\n");
            out.flush();
        }
        synchronized (lock) {
            promptShown = false;
            queue.addLast(new Keystroke(clock.getAsLong(), line));
            lock.notifyAll();
        }
    }

    private void cancel() {
        synchronized (screen) {
            out.print("^C\r\n");
            out.flush();
        }
        synchronized (lock) {
            promptShown = false;
        }
    }

    private void finish() {
        synchronized (lock) {
            promptShown = false;
            closed = true;
            lock.notifyAll();
        }
    }

    // ------------------------------------------------------------------
    // Modo crudo
    // ------------------------------------------------------------------

    /**
     * Pone el terminal en crudo y guarda como estaba.
     *
     * Se puede llamar mas de una vez: a partir de la segunda no hace nada, que es
     * lo que evita guardar como "estado original" el estado ya cambiado.
     *
     * @return true si el terminal quedo en crudo
     */
    private boolean enterRawMode() {
        if (!rawMode) {
            return false;
        }
        synchronized (lock) {
            if (savedStty != null) {
                return true;
            }
        }
        String saved = stty("stty", "-g");
        if (saved == null || saved.isBlank()) {
            return false;
        }
        synchronized (lock) {
            savedStty = saved;
        }
        return stty("stty", "raw", "-echo") != null;
    }

    /** Devuelve el terminal a como estaba. Se llama mas de una vez sin dano. */
    private void restoreTerminal() {
        String saved;
        synchronized (lock) {
            saved = savedStty;
            savedStty = null;
        }
        if (saved != null && !saved.isBlank()) {
            stty("stty", saved.trim());
        }
    }

    /** Ejecuta stty sobre el terminal de este proceso y devuelve su salida. */
    private String stty(String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            // stty actua sobre su propia entrada, asi que tiene que ser la misma
            // que la de este proceso: si se le da una tuberia, no hay terminal
            // que configurar y falla.
            pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
            Process p = pb.start();
            byte[] raw = p.getInputStream().readAllBytes();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                return null;
            }
            return new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Tabulador
    // ------------------------------------------------------------------

    /**
     * Completa nombres de orden.
     *
     * Solo nombres de orden, y con motivo: completar rutas obligaria a preguntar
     * al sistema de ficheros, que es estado del nucleo y no se puede tocar desde
     * este hilo. Antes de completar mal, no completa.
     */
    private static LineEditor.Completer completer(List<String> commands) {
        List<String> names = commands == null ? List.of()
                : commands.stream().filter(n -> n != null && !n.isBlank()).sorted().toList();
        return prefix -> {
            if (prefix.isEmpty()) {
                return null;
            }
            List<String> hits = names.stream().filter(n -> n.startsWith(prefix)).toList();
            if (hits.isEmpty()) {
                return null;
            }
            if (hits.size() == 1) {
                return hits.get(0) + " ";
            }
            String common = hits.get(0);
            for (String hit : hits) {
                common = commonPrefix(common, hit);
            }
            // Si no alarga nada, no hay nada que ofrecer.
            return common.length() > prefix.length() ? common : null;
        };
    }

    private static String commonPrefix(String a, String b) {
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return a.substring(0, i);
    }
}
