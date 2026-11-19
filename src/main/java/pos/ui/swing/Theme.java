package pos.ui.swing;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;
import java.io.File;

/**
 * Colores, tipografias y cuatro cosas de dibujo que usan todos los paneles.
 *
 * Esta todo en un sitio porque una interfaz oscura se rompe por los detalles:
 * un gris que no es el mismo gris, un borde de un pixel de mas, una fuente que
 * cambia de tamano entre dos paneles. Aqui no hay tema configurable: hay un
 * juego de valores y el que pinte usa esos.
 *
 * Las fuentes se cargan de ficheros TTF, no por nombre. En una maquina sin
 * fontconfig instalado, pedir "SansSerif" revienta al construir el objeto Font;
 * leer el fichero y registrarlo no. Si no aparece ninguno de los ficheros
 * buscados, se cae a las fuentes logicas y que pase lo que tenga que pasar: es
 * preferible una ventana con la tipografia de casa que una ventana que no abre.
 */
public final class Theme {

    /** Fondo de la ventana. */
    public static final Color BG = new Color(0x0E1116);

    /** Fondo de los paneles y de las tablas. */
    public static final Color PANEL = new Color(0x151A21);

    /** Fondo alterno, para filas pares y cabeceras. */
    public static final Color PANEL_ALT = new Color(0x1B222B);

    /** Lineas y separadores. */
    public static final Color BORDER = new Color(0x27303C);

    /** Texto principal. */
    public static final Color TEXT = new Color(0xE6EAF2);

    /** Texto secundario: etiquetas, unidades, columnas de relleno. */
    public static final Color MUTED = new Color(0x8D97A8);

    /** Texto apagado: lo que ya no importa, como un hilo terminado. */
    public static final Color DIM = new Color(0x5B6577);

    /** Acento: lo seleccionado, lo que esta pasando ahora. */
    public static final Color ACCENT = new Color(0x5AA9FF);

    /** Ejecutando. */
    public static final Color GREEN = new Color(0x4CC38A);

    /** Listo o esperando turno. */
    public static final Color BLUE = new Color(0x6EA8FE);

    /** Bloqueado o a punto de agotarse. */
    public static final Color AMBER = new Color(0xE3B341);

    /** Fallo o interbloqueo. */
    public static final Color RED = new Color(0xF2555A);

    /** Memoria de un proceso sin color propio asignado. */
    public static final Color PURPLE = new Color(0xB692F6);

    /** Alto de una fila de tabla, en pixeles. */
    public static final int ROW_HEIGHT = 22;

    /** Alto de la cabecera de una tabla. */
    public static final int HEADER_HEIGHT = 26;

    /** Paleta para pintar duennos de memoria: se repite por proceso. */
    private static final Color[] OWNERS = {
            new Color(0x5AA9FF), new Color(0x4CC38A), new Color(0xE3B341),
            new Color(0xB692F6), new Color(0xF2555A), new Color(0x3FB6C8),
            new Color(0xE08BB0), new Color(0x9DBB61)
    };

    /** Familia de texto, ya derivada de un fichero si se pudo. */
    private static final Font SANS = load(new String[] {
            "Inter.ttf", "Manrope.ttf", "Roboto.ttf", "DejaVuSans.ttf"
    }, "SansSerif");

    /** Familia monoespaciada, para trazas y columnas de numeros. */
    private static final Font MONO = load(new String[] {
            "JetBrainsMono-Regular.ttf", "DejaVuSansMono.ttf",
            "LiberationMono-Regular.ttf", "RobotoMono-Regular.ttf"
    }, "Monospaced");

    private Theme() {
        // Solo constantes y ayudas.
    }

    /** Texto normal. */
    public static Font sans(float size) {
        return SANS.deriveFont(Font.PLAIN, size);
    }

    /** Texto normal en negrita. */
    public static Font sansBold(float size) {
        return SANS.deriveFont(Font.BOLD, size);
    }

    /** Texto monoespaciado. */
    public static Font mono(float size) {
        return MONO.deriveFont(Font.PLAIN, size);
    }

    /** Texto monoespaciado en negrita. */
    public static Font monoBold(float size) {
        return MONO.deriveFont(Font.BOLD, size);
    }

    /**
     * Color estable para un duenno de memoria.
     *
     * El color sale del identificador de proceso, no del orden en que aparece:
     * asi el mismo proceso conserva su color entre fotos y el mapa no baile
     * cuando uno termina y deja de estar en la lista.
     */
    public static Color ownerColor(int pid) {
        if (pid <= 0) {
            return PANEL_ALT;
        }
        return OWNERS[(pid - 1) % OWNERS.length];
    }

    /** Color de un estado del nucleo escrito con su etiqueta. */
    public static Color stateColor(String label) {
        if (label == null) {
            return MUTED;
        }
        return switch (label) {
            case "ejecutando" -> GREEN;
            case "listo" -> BLUE;
            case "bloqueado" -> AMBER;
            case "terminado" -> DIM;
            default -> MUTED;
        };
    }

    /** Activa el suavizado de texto y de formas. */
    public static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
    }

    /** Rectangulo redondeado relleno. */
    public static void fillRound(Graphics2D g, int x, int y, int w, int h, int radius, Color color) {
        g.setColor(color);
        g.fill(new RoundRectangle2D.Float(x, y, w, h, radius, radius));
    }

    /** Rectangulo redondeado solo con borde. */
    public static void strokeRound(Graphics2D g, int x, int y, int w, int h, int radius, Color color) {
        g.setColor(color);
        g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, w - 1f, h - 1f, radius, radius));
    }

    /** Etiqueta pequena en mayusculas, del color de lo secundario. */
    public static void caption(Graphics2D g, String text, int x, int y) {
        g.setFont(sansBold(10.5f));
        g.setColor(MUTED);
        g.drawString(text.toUpperCase(), x, y);
    }

    /**
     * Carga la primera fuente que exista de la lista.
     *
     * Se buscan en las carpetas de fuentes del usuario, que es donde estan en
     * este entorno, y en las dos rutas clasicas de Linux. Si ninguna aparece, se
     * devuelve la familia logica pedida.
     */
    private static Font load(String[] names, String fallback) {
        File[] dirs = {
                new File(System.getProperty("user.home", "."), ".local/fonts"),
                new File(System.getProperty("user.home", "."), ".fonts"),
                new File("/usr/share/fonts/truetype/dejavu"),
                new File("/usr/share/fonts/truetype/liberation")
        };
        for (String name : names) {
            for (File dir : dirs) {
                File file = new File(dir, name);
                if (!file.isFile()) {
                    continue;
                }
                try {
                    return Font.createFont(Font.TRUETYPE_FONT, file);
                } catch (Exception e) {
                    // Se prueba la siguiente: una fuente rota no debe tumbar la ventana.
                }
            }
        }
        return new Font(fallback, Font.PLAIN, 12);
    }
}
