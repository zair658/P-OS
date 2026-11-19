package pos.ui.swing;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicScrollBarUI;

/**
 * Barra de desplazamiento plana, sin flechas y sin relieve.
 *
 * La de serie trae botones con flechas que en una consola oscura y densa
 * parecen pegatinas. Aqui solo hay un canal y un pulgar, y el pulgar se aclara
 * al pasar por encima. Las flechas desaparecen porque con rueda y arrastre no
 * hacen falta.
 */
final class FlatScrollBarUI extends BasicScrollBarUI {

    private final Color track = Theme.PANEL;
    private final Color thumb = Theme.BORDER;
    private final Color thumbHot = Theme.MUTED;

    @Override
    protected void configureScrollBarColors() {
        this.trackColor = track;
        this.thumbColor = thumb;
    }

    @Override
    protected JButton createDecreaseButton(int orientation) {
        return invisible();
    }

    @Override
    protected JButton createIncreaseButton(int orientation) {
        return invisible();
    }

    @Override
    protected Dimension getMinimumThumbSize() {
        return new Dimension(6, 24);
    }

    /** Un boton de cero por cero: existe porque la clase base lo exige. */
    private JButton invisible() {
        JButton button = new JButton();
        button.setPreferredSize(new Dimension(0, 0));
        button.setMinimumSize(new Dimension(0, 0));
        button.setMaximumSize(new Dimension(0, 0));
        button.setBorderPainted(false);
        button.setFocusable(false);
        return button;
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.smooth(g2);
        g2.setColor(track);
        g2.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        g2.dispose();
    }

    @Override
    protected void paintThumb(Graphics g, JComponent c, Rectangle bounds) {
        if (bounds.isEmpty() || !scrollbar.isEnabled()) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        Theme.smooth(g2);
        boolean hot = isThumbRollover() || isDragging;
        int inset = 3;
        Theme.fillRound(g2, bounds.x + inset, bounds.y + inset,
                Math.max(4, bounds.width - inset * 2), Math.max(10, bounds.height - inset * 2),
                6, hot ? thumbHot : thumb);
        g2.dispose();
    }
}
