package pos.ui.swing;

import java.io.File;
import java.io.IOException;
import java.util.List;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

import pos.kernel.Kernel;
import pos.launch.UiDemo;
import pos.ui.Snapshot;
import pos.ui.UiFeed;

/**
 * Arranque de la ventana: montar el escenario, mirarlo y no bloquear.
 *
 * El nucleo corre en su propio hilo, encerrado en el escenario, y la ventana se
 * limita a leer la ultima foto cada pocos milisegundos. No hay ninguna llamada
 * de la interfaz al nucleo: si la hubiera, la ventana podria pillar el sistema a
 * medio turno y contar una mentira. Aqui solo se mira lo que el nucleo ya cerro.
 *
 * La pausa entre turnos existe para que esto sea visible: sin ella el escenario
 * termina antes de que la ventana llegue a pintar la primera foto, y la ventana
 * se abriria ya con el sistema cerrado. Ponerla es decision de quien mira, no
 * del nucleo, y se puede quitar sin tocar una linea del kernel.
 */
public final class UiLaunch {

    /** Cada cuanto se pregunta si hay foto nueva, en milisegundos. */
    private static final int TICK = 60;

    /** Pausa entre turnos del nucleo, para que se vea algo. */
    private static final long PAUSE = 40L;

    private UiLaunch() {
        // Solo el arranque.
    }

    /** Abre la ventana y echa a andar el escenario. */
    public static void open() {
        UiFeed feed = new UiFeed();
        feed.setPausePerTurn(PAUSE);
        Shell shell = new Shell();

        SwingUtilities.invokeLater(() -> {
            JFrame frame = shell.frame();
            frame.setVisible(true);
            shell.selectTab("Resumen");
        });

        Kernel kernel = UiDemo.build(feed);
        Thread worker = new Thread(() -> {
            try {
                kernel.run();
            } catch (RuntimeException e) {
                System.err.println("el escenario termino con error: " + e.getMessage());
            }
        }, "nucleo");
        worker.setDaemon(true);
        worker.start();

        long[] seen = { -1L };
        Timer timer = new Timer(TICK, event -> {
            long version = feed.version();
            if (version == seen[0]) {
                return;
            }
            seen[0] = version;
            Snapshot shot = feed.latest();
            if (shot != null) {
                shell.setHistory(feed.history());
                shell.show(shot);
            }
        });
        timer.setInitialDelay(0);
        timer.start();
    }

    /**
     * Ejecuta el escenario y deja las capturas en una carpeta.
     *
     * No abre ventana: en un contenedor sin servidor grafico no se puede, y el
     * sentido de esto es justo poder ensenar la interfaz desde ahi.
     */
    public static List<File> capture(String directory) throws IOException {
        File dir = new File(directory);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("no se pudo crear la carpeta " + dir.getAbsolutePath());
        }
        System.out.println("capturando en " + dir.getAbsolutePath() + " ...");
        List<File> files = Capture.all(dir, true);
        for (File file : files) {
            System.out.println("  " + file.getName() + "  " + file.length() + " bytes");
        }
        return files;
    }

    /** Entrada directa, para lanzar la ventana sin pasar por el menu de P-OS. */
    public static void main(String[] args) throws IOException {
        if (args.length > 0 && "--captura".equals(args[0])) {
            capture(args.length > 1 ? args[1] : "capturas");
            return;
        }
        open();
    }
}
