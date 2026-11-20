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
    public static final long PAUSA_POR_DEFECTO = 160L;

    private UiLaunch() {
        // Solo el arranque.
    }

    /** Abre la ventana con la pausa de siempre. */
    public static void open() {
        open(0L);
    }

    /**
     * Abre la ventana con una pausa entre turnos.
     *
     * La pausa es lo unico que hace visible el reparto: sin ella el escenario se
     * cierra antes de que la ventana pinte la primera foto. Pero tambien es lo
     * que se espera al escribir en la terminal, porque la terminal sondea entre
     * turnos: con pausa cero el sistema corre a toda velocidad y no se ve nada;
     * con pausa larga se ve muy bien y escribir se hace pesado.
     *
     * @param pause milisegundos por turno, o 0 para la de por defecto
     */
    public static void open(long pause) {
        UiFeed feed = new UiFeed();
        feed.setPausePerTurn(pause > 0 ? pause : PAUSA_POR_DEFECTO);
        TerminalPanel terminal = new TerminalPanel();
        Shell shell = new Shell(terminal);

        SwingUtilities.invokeLater(() -> {
            JFrame frame = shell.frame();
            frame.setVisible(true);
            shell.selectTab("Resumen");
        });

        Kernel kernel = UiDemo.build(feed, terminal.source(), terminal.screen());
        // El reloj se engancha cuando existe el nucleo: la fuente se creo antes
        // porque el nucleo la pide al montar la terminal. Mientras no haya
        // llegado ninguna linea no hace falta, y antes de arrancar el nucleo ya
        // esta puesto.
        terminal.source().attachClock(kernel::clock);
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
