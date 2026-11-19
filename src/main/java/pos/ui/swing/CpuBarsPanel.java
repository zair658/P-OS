package pos.ui.swing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JPanel;

import pos.ui.Snapshot;

/**
 * El reparto de la CPU: cuantos hilos hay en cada estado y cuanto se aprovecha.
 *
 * Tres barras y una cola. La primera dice como esta repartida la poblacion de
 * hilos, que en un sistema con cerrojos y semaforos es mas informativo que el
 * porcentaje de CPU: cinco hilos bloqueados con la CPU al cien por cien es un
 * sistema atascado, y cinco ejecutando con la CPU al treinta es un sistema que
 * espera entrada.
 *
 * La segunda es la ocupacion real. La tercera es la cola de listos, en orden,
 * porque el orden es justo lo que explica la siguiente eleccion del planificador.
 */
public final class CpuBarsPanel extends JPanel {

    /** Alto de una barra. */
    private static final int BAR = 9;

    /** Separacion entre bloques. */
    private static final int GAP = 12;

    /** Margen lateral. */
    private static final int PAD = 10;

    private final SectionHeader head = new SectionHeader("Reparto de CPU y estados");

    /** Ultima foto pintada. */
    private Snapshot shot;

    public CpuBarsPanel() {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(Theme.PANEL);
        add(head, BorderLayout.NORTH);
    }

    /** Cambia los datos. */
    public void update(Snapshot shot) {
        this.shot = shot;
        if (shot != null) {
            head.setSummary(shot.scheduler() + "  ·  " + shot.threads().size() + " hilos");
        }
        repaint();
    }

    /** Deja el panel vacio. */
    public void clear() {
        shot = null;
        head.setSummary("");
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(10, SectionHeader.HEIGHT + 118);
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

        int width = Math.max(10, getWidth() - PAD * 2);
        int y = SectionHeader.HEIGHT + 18;

        if (shot == null || shot.threads().isEmpty()) {
            g2.setFont(Theme.mono(11.5f));
            g2.setColor(Theme.MUTED);
            g2.drawString("sin hilos todavia", PAD, y + 14);
            g2.dispose();
            return;
        }

        int running = shot.count("ejecutando");
        int ready = shot.count("listo");
        int blocked = shot.count("bloqueado");
        int done = shot.count("terminado");
        int total = Math.max(1, shot.threads().size());

        // Poblacion de hilos por estado, en una barra segmentada.
        g2.setFont(Theme.sansBold(9.5f));
        g2.setColor(Theme.MUTED);
        g2.drawString("HILOS POR ESTADO", PAD, y - 6);

        int x = PAD;
        x = segment(g2, x, y, width, total, running, Theme.GREEN);
        x = segment(g2, x, y, width, total, ready, Theme.BLUE);
        x = segment(g2, x, y, width, total, blocked, Theme.AMBER);
        segment(g2, x, y, width, total, done, Theme.DIM);

        y += BAR + 17;
        g2.setFont(Theme.mono(10.5f));
        int lx = PAD;
        lx = legend(g2, lx, y, Theme.GREEN, "ejecutando " + running);
        lx = legend(g2, lx, y, Theme.BLUE, "listo " + ready);
        lx = legend(g2, lx, y, Theme.AMBER, "bloqueado " + blocked);
        legend(g2, lx, y, Theme.DIM, "terminado " + done);

        // Ocupacion de la CPU.
        y += GAP + 6;
        double use = shot.cpuUse();
        g2.setFont(Theme.sansBold(9.5f));
        g2.setColor(Theme.MUTED);
        g2.drawString("CPU", PAD, y - 6);

        Theme.fillRound(g2, PAD, y, width, BAR, BAR, Theme.PANEL_ALT);
        int filled = (int) Math.round(width * Math.max(0.0, Math.min(1.0, use)));
        if (filled > 2) {
            Theme.fillRound(g2, PAD, y, filled, BAR, BAR, Theme.GREEN);
        }
        g2.setFont(Theme.mono(10.5f));
        g2.setColor(Theme.TEXT);
        String label = Math.round(use * 100) + "%  ·  " + shot.cpuTicks() + " ticks de CPU";
        int lw = g2.getFontMetrics().stringWidth(label);
        g2.drawString(label, getWidth() - PAD - lw, y + BAR + 13);

        // Cola de listos, en orden.
        y += BAR + 26;
        g2.setFont(Theme.sansBold(9.5f));
        g2.setColor(Theme.MUTED);
        g2.drawString("COLA DE LISTOS", PAD, y - 6);

        if (shot.ready().isEmpty()) {
            g2.setFont(Theme.mono(10.5f));
            g2.setColor(Theme.DIM);
            g2.drawString("vacia", PAD, y + 12);
        } else {
            int cx = PAD;
            g2.setFont(Theme.mono(10.5f));
            FontMetrics fm = g2.getFontMetrics();
            for (Snapshot.Ready entry : shot.ready()) {
                String text = entry.position() + " " + entry.thread();
                int w = fm.stringWidth(text) + 14;
                if (cx + w > getWidth() - PAD) {
                    g2.setColor(Theme.DIM);
                    g2.drawString("+" + (shot.ready().size() - entry.position() + 1), cx, y + 12);
                    break;
                }
                Theme.fillRound(g2, cx, y, w, 18, 5, Theme.PANEL_ALT);
                Theme.strokeRound(g2, cx, y, w, 18, 5, Theme.BORDER);
                g2.setColor(Theme.BLUE);
                g2.drawRect(cx + 6, y + 6, 0, 0);
                g2.setColor(Theme.MUTED);
                g2.drawString(text, cx + 7, y + 13);
                cx += w + 6;
            }
        }

        g2.dispose();
    }

    /** Un trozo de la barra de estados, sin bajar del pixel. */
    private int segment(Graphics2D g2, int x, int y, int width, int total, int count, Color color) {
        if (count <= 0) {
            return x;
        }
        int w = Math.max(3, (int) Math.round(width * count / (double) total));
        Theme.fillRound(g2, x, y, Math.max(2, w - 2), BAR, BAR, color);
        return x + w;
    }

    /** Un punto de color y su etiqueta. Devuelve donde sigue la siguiente. */
    private int legend(Graphics2D g2, int x, int y, Color color, String text) {
        g2.setColor(color);
        g2.fillOval(x, y - 7, 7, 7);
        g2.setColor(Theme.MUTED);
        g2.drawString(text, x + 12, y);
        return x + 12 + g2.getFontMetrics().stringWidth(text) + 16;
    }
}
