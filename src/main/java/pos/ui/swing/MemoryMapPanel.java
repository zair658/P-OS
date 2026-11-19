package pos.ui.swing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.swing.JPanel;

import pos.ui.Snapshot;

/**
 * El mapa de la memoria fisica: una casilla por unidad.
 *
 * Es la vista que justifica la memoria paginada. Una tabla de tramos dice
 * "contienda ocupa 16 unidades en 4 tramos", que es correcto y no se entiende.
 * Aqui se ve que esas unidades no estan juntas, que entre medias hay huecos de
 * otros y que la memoria libre queda repartida en trozos. Eso es exactamente lo
 * que hace la paginacion, y no hay texto que lo explique mejor que el dibujo.
 *
 * Cada casilla lleva el color de su duenno, sacado del identificador de proceso:
 * el mismo proceso conserva su color entre fotos, de modo que uno que termina no
 * recolorea a los demas.
 */
public final class MemoryMapPanel extends JPanel {

    /** Columnas del mapa. Con 192 unidades salen tres filas exactas. */
    private static final int COLUMNS = 64;

    /** Alto minimo de una casilla. */
    private static final int CELL_MIN = 20;

    /** Alto maximo: mas que esto y el mapa parece un tablero de ajedrez. */
    private static final int CELL_MAX = 52;

    /** Alto comodo de una casilla cuando el panel manda su tamano preferido. */
    private static final int CELL_HEIGHT = 27;

    /** Ancho reservado a las etiquetas de direccion. */
    private static final int GUTTER = 34;

    /** Margen del mapa. */
    private static final int PAD = 12;

    /** Alto de la leyenda. */
    private static final int LEGEND = 27;

    /** Banda de titulo y resumen. */
    private final SectionHeader head = new SectionHeader("Memoria fisica");

    /** Duennos presentes en la ultima foto, en orden de aparicion. */
    private final Map<Integer, String> owners = new LinkedHashMap<>();

    /** Relleno de una casilla libre: mas oscuro que el panel. */
    private static final Color FREE = new Color(0x0D1219);

    /** Filo de una casilla libre: apenas un trazo, para que se cuente la rejilla. */
    private static final Color FREE_EDGE = new Color(0x1F2833);

    /** Unidades de la ultima foto: pid duenno, o el valor de libre. */
    private int[] units = new int[0];

    /** Resumen de ocupacion, ya formateado. */
    private String occupancy = "";

    public MemoryMapPanel() {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(Theme.PANEL);
        add(head, BorderLayout.NORTH);
    }

    /** Cambia el mapa y la leyenda con una foto. */
    public void update(Snapshot shot) {
        if (shot == null) {
            clear();
            return;
        }
        Snapshot.MemoryMap memory = shot.memory();
        units = memory.owners();
        owners.clear();
        for (int owner : units) {
            if (owner > 0) {
                String name = nameOf(shot, owner);
                if (name != null) {
                    owners.putIfAbsent(owner, name);
                }
            }
        }
        occupancy = memory.used() + " / " + memory.size() + " unidades  ·  "
                + percent(memory.used(), memory.size()) + " ocupada";
        head.setSummary(occupancy);
        revalidate();
        repaint();
    }

    /** Deja el mapa vacio. */
    public void clear() {
        units = new int[0];
        owners.clear();
        occupancy = "";
        head.setSummary("");
        revalidate();
        repaint();
    }

    /** Nombre del proceso duenno de un identificador, o {@code null} si ya no vive. */
    private static String nameOf(Snapshot shot, int pid) {
        for (Snapshot.Proc process : shot.processes()) {
            if (process.pid() == pid) {
                return process.name();
            }
        }
        return null;
    }

    private static String percent(int part, int total) {
        if (total <= 0) {
            return "0%";
        }
        return Math.round(part * 100.0 / total) + "%";
    }

    /** Alto que necesita el mapa para el numero de unidades que lleva. */
    @Override
    public Dimension getPreferredSize() {
        int rows = Math.max(1, (units.length + COLUMNS - 1) / COLUMNS);
        int height = SectionHeader.HEIGHT + LEGEND + rows * CELL_HEIGHT + PAD;
        return new Dimension(10, height);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.smooth(g2);
        int w = getWidth();
        int top = SectionHeader.HEIGHT;

        paintLegend(g2, w, top);

        int mapTop = top + LEGEND;
        if (units.length == 0) {
            g2.setFont(Theme.mono(11.5f));
            g2.setColor(Theme.MUTED);
            g2.drawString("sin memoria que mostrar todavia", PAD, mapTop + 18);
            g2.dispose();
            return;
        }

        int usable = Math.max(1, w - GUTTER - PAD * 2);
        double cellW = usable / (double) COLUMNS;
        int rows = (units.length + COLUMNS - 1) / COLUMNS;
        int cellPx = Math.max(2, (int) Math.round(cellW) - 1);

        // Las casillas crecen con el panel. Un mapa de tres filas metido en
        // cuatrocientos pixeles deja doscientos de vacio si se deja el alto fijo;
        // estirarlas es lo que hace que el dibujo ocupe el sitio que se le da y
        // siga leyendose igual.
        int room = Math.max(CELL_MIN, getHeight() - mapTop - PAD);
        int cellHeight = Math.max(CELL_MIN, Math.min(CELL_MAX, room / Math.max(1, rows)));
        int block = rows * cellHeight;
        mapTop += Math.max(0, (room - block) / 2);

        for (int index = 0; index < units.length; index++) {
            int row = index / COLUMNS;
            int col = index % COLUMNS;
            int x = (int) Math.round(PAD + GUTTER + col * cellW);
            int y = mapTop + row * cellHeight + 4;
            int height = cellHeight - 8;
            int owner = units[index];
            if (owner > 0) {
                Theme.fillRound(g2, x, y, cellPx, height, 2, Theme.ownerColor(owner));
            } else {
                // Un hueco se dibuja mas oscuro que el panel, no mas claro: la
                // memoria libre es ausencia de algo, y asi lo ocupado resalta.
                Theme.fillRound(g2, x, y, cellPx, height, 2, FREE);
                g2.setColor(FREE_EDGE);
                g2.drawRect(x, y, cellPx, height);
            }
        }

        // Direccion de la primera unidad de cada fila y una marca que las separa.
        g2.setFont(Theme.mono(10f));
        FontMetrics fm = g2.getFontMetrics();
        for (int row = 0; row < rows; row++) {
            int y = mapTop + row * cellHeight + cellHeight / 2 + fm.getAscent() / 2 - 2;
            g2.setColor(Theme.MUTED);
            g2.drawString(String.valueOf(row * COLUMNS), PAD, y);
            g2.setColor(Theme.BORDER);
            g2.drawLine(PAD + GUTTER - 5, mapTop + row * cellHeight + 4,
                    PAD + GUTTER - 5, mapTop + row * cellHeight + cellHeight - 4);
        }

        g2.dispose();
    }

    /** Leyenda: un punto por proceso con memoria, y la casilla de libre. */
    private void paintLegend(Graphics2D g2, int w, int top) {
        int x = PAD;
        int y = top + 16;
        g2.setFont(Theme.mono(10.5f));
        FontMetrics fm = g2.getFontMetrics();

        for (Map.Entry<Integer, String> entry : owners.entrySet()) {
            Color color = Theme.ownerColor(entry.getKey());
            g2.setColor(color);
            g2.fillRoundRect(x, y - 8, 8, 8, 2, 2);
            g2.setColor(Theme.MUTED);
            String label = "p" + entry.getKey() + " " + entry.getValue();
            g2.drawString(label, x + 12, y);
            x += 12 + fm.stringWidth(label) + 18;
            if (x > w - 170) {
                break;
            }
        }

        g2.setColor(FREE);
        g2.fillRoundRect(x, y - 8, 8, 8, 2, 2);
        g2.setColor(FREE_EDGE);
        g2.drawRect(x, y - 8, 8, 8);
        g2.setColor(Theme.MUTED);
        g2.drawString("libre", x + 12, y);

        if (!occupancy.isEmpty()) {
            g2.setColor(Theme.MUTED);
            int sw = fm.stringWidth(occupancy);
            g2.drawString(occupancy, Math.max(10, w - sw - PAD), y);
        }
    }
}
