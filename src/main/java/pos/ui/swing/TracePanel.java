package pos.ui.swing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;

import javax.swing.BorderFactory;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;

/**
 * La traza del nucleo, en texto monoespaciado y sin adornos.
 *
 * Es la unica vista que no es una tabla ni un dibujo, y por eso mismo esta: hay
 * cosas que solo se ven leyendo el orden en que pasaron. Las lineas vienen ya
 * escritas por el nucleo; aqui solo se ponen una debajo de otra.
 */
public final class TracePanel extends JPanel {

    private final SectionHeader head = new SectionHeader("Traza");

    private final JTextArea text = new JTextArea();

    public TracePanel() {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(Theme.PANEL);

        text.setEditable(false);
        text.setLineWrap(false);
        text.setFont(Theme.mono(11.5f));
        text.setBackground(Theme.PANEL);
        text.setForeground(Theme.MUTED);
        text.setCaretColor(Theme.PANEL);
        text.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        text.setSelectionColor(new Color(0x24384F));
        text.setSelectedTextColor(Theme.TEXT);

        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.getVerticalScrollBar().setUI(new FlatScrollBarUI());
        scroll.getHorizontalScrollBar().setUI(new FlatScrollBarUI());
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
        scroll.getHorizontalScrollBar().setPreferredSize(new Dimension(9, 0));

        add(head, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    /** Cambia las lineas y, si se pide, salta al final. */
    public void setLines(String lines, String summary, boolean follow) {
        head.setSummary(summary);
        if (!text.getText().equals(lines)) {
            text.setText(lines);
            if (follow) {
                text.setCaretPosition(text.getDocument().getLength());
            }
        }
    }

    /** Deja la traza vacia. */
    public void clear() {
        head.setSummary("");
        text.setText("");
    }
}
