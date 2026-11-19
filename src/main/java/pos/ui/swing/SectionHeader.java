package pos.ui.swing;

import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

import javax.swing.JComponent;

/**
 * La banda que corona cada seccion: titulo a la izquierda y resumen a la derecha.
 *
 * Es un componente y no dos etiquetas metidas en un panel porque asi se controla
 * el alto exacto -- treinta y un pixeles, ni uno mas -- y el borde inferior, que
 * es lo que separa la seccion de la tabla sin dibujar una caja alrededor.
 */
final class SectionHeader extends JComponent {

    /** Alto por defecto de la banda. */
    static final int HEIGHT = 31;

    private String title;
    private String summary = "";

    SectionHeader(String title) {
        this.title = title;
        setOpaque(false);
        setPreferredSize(new Dimension(10, HEIGHT));
    }

    void setTitle(String title) {
        this.title = title;
        repaint();
    }

    void setSummary(String summary) {
        this.summary = summary == null ? "" : summary;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.smooth(g2);
        int w = getWidth();
        int h = getHeight();
        g2.setColor(Theme.PANEL_ALT);
        g2.fillRect(0, 0, w, h);

        g2.setFont(Theme.sansBold(12f));
        g2.setColor(Theme.TEXT);
        g2.drawString(title, 10, h - 10);

        if (!summary.isEmpty()) {
            g2.setFont(Theme.mono(10.5f));
            g2.setColor(Theme.MUTED);
            int sw = g2.getFontMetrics().stringWidth(summary);
            g2.drawString(summary, Math.max(10, w - sw - 10), h - 10);
        }

        g2.setColor(Theme.BORDER);
        g2.drawLine(0, h - 1, w, h - 1);
        g2.dispose();
    }
}
