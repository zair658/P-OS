package pos.ui.swing;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;
import javax.swing.JComponent;

import pos.ui.Snapshot;
import pos.ui.UiFeed;
import pos.proc.State;

/**
 * Pintar la interfaz sin pantalla, para dejar constancia de como queda.
 *
 * En un contenedor no hay servidor grafico, asi que no se puede abrir un JFrame
 * ni esperar a que el usuario mire. Si se puede, en cambio, construir el mismo
 * arbol de componentes, darle un tamano y pintarlo sobre una imagen. El
 * resultado es el mismo que se veria en la ventana, porque es el mismo codigo
 * de pintado: no hay una version "de captura" de los paneles.
 *
 * Lo unico que hace falta es saltarse el validador de Swing, que sin ventana no
 * hace nada: en su lugar se recorre el arbol llamando a doLayout.
 */
public final class Capture {

    /** Tamano de las capturas, el mismo de la ventana por defecto. */
    public static final int WIDTH = 1280;

    /** Alto de las capturas. */
    public static final int HEIGHT = 820;

    private Capture() {
        // Solo utilidades.
    }

    /** Pinta una pestana del armazon en un PNG. */
    public static File save(Shell shell, String tab, File file) throws IOException {
        return save(shell, tab, file, WIDTH, HEIGHT);
    }

    /** Pinta una pestana del armazon en un PNG con un tamano concreto. */
    public static File save(Shell shell, String tab, File file, int width, int height)
            throws IOException {
        shell.selectTab(tab);
        JComponent root = shell.rootComponent();
        layout(root, width, height);

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var g = image.createGraphics();
        g.setColor(Theme.BG);
        g.fillRect(0, 0, width, height);
        root.printAll(g);
        g.dispose();

        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("no se pudo crear " + parent);
        }
        ImageIO.write(image, "png", file);
        return file;
    }

    /** Coloca el arbol entero: sin peer, validate() no hace nada. */
    static void layout(JComponent root, int width, int height) {
        root.setSize(width, height);
        arrange(root);
    }

    private static void arrange(java.awt.Component component) {
        if (component instanceof java.awt.Container container) {
            container.doLayout();
            for (java.awt.Component child : container.getComponents()) {
                arrange(child);
            }
        }
    }

    /**
     * Captura el escenario completo en una carpeta: una imagen por pestana.
     *
     * Las seis primeras fotos se toman del mismo instante, no de instantes
     * distintos: se deja correr el sistema con una pausa corta y, al llegar a un
     * reloj con vida, se congela subiendo la pausa. Asi el resumen, las tablas y
     * la traza cuentan la misma historia; si cada captura fuera de un momento
     * distinto, el conjunto no se podria leer como una sola foto del sistema.
     *
     * Las dos ultimas son del cierre, cuando todo ha terminado. Tambien valen:
     * ensenan que el sistema queda sin hilos vivos y con la memoria libre.
     */
    public static List<File> all(File dir, boolean headlessRun) throws IOException {
        UiFeed feed = new UiFeed();
        feed.setPausePerTurn(headlessRun ? 4L : 0L);
        // La ventana de la captura tambien lleva terminal: la sesion la escribe
        // el guion, pero se pinta donde se miraria si hubiera alguien.
        TerminalPanel terminal = new TerminalPanel();
        Shell shell = new Shell(terminal);

        Thread worker = new Thread(
                () -> pos.launch.UiDemo.runHeadless(feed, terminal.screen()), "escenario");
        worker.setDaemon(true);
        worker.start();

        List<File> files = new ArrayList<>();
        // Se espera al instante en que hay un trabajo detenido, no a un reloj
        // fijo: "detenido" solo existe mientras dura el stop, y es justo lo que
        // la pestana de procesos tiene que ensenar. Si el escenario cambiara y
        // ese instante no llegara, se cae al reloj de siempre para no quedarse
        // sin capturas.
        if (!awaitStopped(feed, 20_000)) {
            awaitClock(feed, 55, 20_000);
        }

        // Congelar: la pausa se lee en cada turno, asi que el nucleo se queda
        // quieto en el siguiente y las capturas salen del mismo estado.
        feed.setPausePerTurn(800);
        sleep(40);

        Snapshot live = feed.latest();
        if (live != null) {
            shell.setHistory(feed.history());
            shell.show(live);
            files.add(save(shell, "Resumen", new File(dir, "01-resumen.png")));
            files.add(save(shell, "Procesos", new File(dir, "02-procesos.png")));
            files.add(save(shell, "Reparto", new File(dir, "03-reparto.png")));
            files.add(save(shell, "Memoria", new File(dir, "04-memoria.png")));
            files.add(save(shell, "Sistema", new File(dir, "05-sistema.png")));
            files.add(save(shell, "Traza", new File(dir, "06-traza.png")));
        }

        feed.setPausePerTurn(0);
        awaitFinish(worker, feed, 60_000);

        Snapshot last = feed.latest();
        if (last != null) {
            shell.setHistory(feed.history());
            shell.show(last);
            files.add(save(shell, "Resumen", new File(dir, "07-resumen-final.png")));
            files.add(save(shell, "Memoria", new File(dir, "08-memoria-final.png")));
            files.add(save(shell, "Terminal", new File(dir, "09-terminal.png")));
        }
        return files;
    }

    /** Espera a una foto con algun proceso detenido, o al agotarse el plazo. */
    private static boolean awaitStopped(UiFeed feed, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Snapshot shot = feed.latest();
            if (shot != null && shot.processes().stream()
                    .anyMatch(process -> process.state().equals(State.STOPPED.etiqueta()))) {
                return true;
            }
            if (feed.isFinished()) {
                return false;
            }
            sleep(5);
        }
        return false;
    }

    /** Espera a que el reloj del sistema llegue a un valor, o se agote el plazo. */
    private static void awaitClock(UiFeed feed, long clock, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            Snapshot shot = feed.latest();
            if (shot != null && shot.clock() >= clock) {
                return;
            }
            if (feed.isFinished()) {
                return;
            }
            sleep(5);
        }
    }

    /** Espera a que el hilo del escenario termine. */
    private static void awaitFinish(Thread worker, UiFeed feed, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (worker.isAlive() && System.currentTimeMillis() < deadline) {
            sleep(10);
        }
        // Una foto mas despues del cierre: el ultimo turno tambien cuenta.
        sleep(60);
        if (feed.latest() == null) {
            sleep(100);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Tamano con el que se pintan las capturas, por si alguien lo pregunta. */
    public static Dimension size() {
        return new Dimension(WIDTH, HEIGHT);
    }
}
