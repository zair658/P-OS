package pos.ui.swing;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.geom.Path2D;
import java.util.List;

import javax.swing.JPanel;

import pos.ui.Snapshot;
import pos.ui.UiFeed;

/**
 * La evolucion del sistema: cola de listos, hilos bloqueados y CPU.
 *
 * Una foto dice donde esta el sistema; esta tira dice hacia donde va. Las tres
 * series son las mismas que guarda el feed -- reloj, CPU acumulada, listos y
 * bloqueados -- y se dibujan contra el reloj, no contra el numero de muestra,
 * para que una pausa larga entre fotos no se convierta en una recta.
 *
 * Los bloqueados se dibujan hacia abajo porque semanticamente son lo contrario
 * de estar listo: cuando las dos curvas se cruzan, el sistema tiene mas gente
 * esperando que gente preparada, y eso es un atasco, no una racha.
 */
public final class HistoryPanel extends JPanel {

    /** Margen del grafico. */
    private static final int PAD = 12;

    /** Alto comodo del area de dibujo, y minimo cuando el panel es bajo. */
    private static final int PLOT = 76;

    /** Muestras minimas para que la linea tenga sentido. */
    private static final int MIN_SAMPLES = 2;

    private final SectionHeader head = new SectionHeader("Evolucion");

    /** Muestras: {reloj, cpu, listos, bloqueados}. */
    private List<long[]> samples = List.of();

    /** Ultima foto, para el resumen. */
    private Snapshot shot;

    public HistoryPanel() {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(Theme.PANEL);
        add(head, BorderLayout.NORTH);
    }

    /** Cambia la serie y la foto de referencia. */
    public void update(List<long[]> samples, Snapshot shot) {
        this.samples = samples == null ? List.of() : samples;
        this.shot = shot;
        if (shot != null) {
            head.setSummary(samples.size() + " muestras  ·  reloj " + shot.clock() + " ticks");
        }
        repaint();
    }

    /** Deja el grafico vacio. */
    public void clear() {
        samples = List.of();
        shot = null;
        head.setSummary("");
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(10, SectionHeader.HEIGHT + PLOT + 42);
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

        int top = SectionHeader.HEIGHT + 16;
        int left = PAD + 26;
        int width = Math.max(10, getWidth() - left - PAD - 46);
        // El grafico ocupa el alto que le den: una tira de setenta y seis pixeles
        // en un panel de cuatrocientos es un grafico escondido en una esquina.
        int plot = Math.max(PLOT, getHeight() - top - 30);
        int bottom = top + plot;

        if (samples.size() < MIN_SAMPLES) {
            g2.setFont(Theme.mono(11.5f));
            g2.setColor(Theme.MUTED);
            g2.drawString("recogiendo muestras", left, top + 24);
            g2.dispose();
            return;
        }

        long first = samples.get(0)[0];
        long last = samples.get(samples.size() - 1)[0];
        long span = Math.max(1, last - first);

        int maxReady = 1;
        int maxBlocked = 1;
        for (long[] sample : samples) {
            maxReady = Math.max(maxReady, (int) sample[2]);
            maxBlocked = Math.max(maxBlocked, (int) sample[3]);
        }
        maxReady = nice(maxReady);
        maxBlocked = nice(maxBlocked);

        // La linea del cero no va en medio: se coloca segun lo que ocupa cada
        // serie. Con la mitad fija para cada una, un escenario sin bloqueados
        // -- que es lo normal -- dejaba media pantalla en blanco.
        int mid = top + (int) Math.round(plot * (double) maxReady / (maxReady + maxBlocked));

        // Rejilla: tres lineas horizontales, la del cero en medio.
        g2.setColor(Theme.BORDER);
        g2.drawLine(left, mid, left + width, mid);
        g2.setColor(new Color(0x1E262F));
        g2.drawLine(left, top + 10, left + width, top + 10);
        g2.drawLine(left, bottom - 10, left + width, bottom - 10);

        // Listos, hacia arriba desde el centro.
        Path2D.Double ready = new Path2D.Double();
        Path2D.Double readyArea = new Path2D.Double();
        readyArea.moveTo(left, mid);
        boolean firstPoint = true;
        for (long[] sample : samples) {
            double x = left + width * (sample[0] - first) / (double) span;
            double y = mid - (plot / 2.0 - 6) * Math.min(1.0, sample[2] / (double) maxReady);
            if (firstPoint) {
                ready.moveTo(x, y);
                firstPoint = false;
            } else {
                ready.lineTo(x, y);
            }
            readyArea.lineTo(x, y);
        }
        readyArea.lineTo(left + width, mid);
        readyArea.closePath();
        g2.setColor(new Color(90, 169, 255, 34));
        g2.fill(readyArea);
        g2.setColor(Theme.BLUE);
        g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(ready);

        // Bloqueados, hacia abajo.
        Path2D.Double blocked = new Path2D.Double();
        firstPoint = true;
        for (long[] sample : samples) {
            double x = left + width * (sample[0] - first) / (double) span;
            double y = mid + (plot / 2.0 - 6) * Math.min(1.0, sample[3] / (double) maxBlocked);
            if (firstPoint) {
                blocked.moveTo(x, y);
                firstPoint = false;
            } else {
                blocked.lineTo(x, y);
            }
        }
        g2.setColor(Theme.AMBER);
        g2.draw(blocked);

        // Etiquetas de las escalas.
        g2.setStroke(new BasicStroke(1f));
        g2.setFont(Theme.mono(9.5f));
        g2.setColor(Theme.MUTED);
        g2.drawString("+" + maxReady, PAD, top + 12);
        g2.drawString("0", PAD, mid + 4);
        g2.drawString("-" + maxBlocked, PAD, bottom - 8);

        FontMetrics fm = g2.getFontMetrics();
        int lastReady = (int) samples.get(samples.size() - 1)[2];
        int lastBlocked = (int) samples.get(samples.size() - 1)[3];
        g2.setColor(Theme.BLUE);
        String r = "listos " + lastReady;
        g2.drawString(r, left + width + 8, top + 22);
        g2.setColor(Theme.AMBER);
        String b = "bloq " + lastBlocked;
        g2.drawString(b, left + width + 8, top + 36);

        if (shot != null) {
            g2.setColor(Theme.GREEN);
            g2.drawString(Math.round(shot.cpuUse() * 100) + "% cpu", left + width + 8, top + 50);
        }

        // Reloj inicial y final bajo el eje.
        g2.setColor(Theme.DIM);
        g2.setFont(Theme.mono(9.5f));
        g2.drawString(String.valueOf(first), left, bottom + 14);
        String lastText = String.valueOf(last);
        g2.drawString(lastText, left + width - fm.stringWidth(lastText), bottom + 14);

        g2.dispose();
    }

    /** Redondea al alza a un numero que se lea bien en un eje. */
    private static int nice(int value) {
        if (value <= 1) {
            return 1;
        }
        if (value <= 5) {
            return 5;
        }
        if (value <= 10) {
            return 10;
        }
        return (int) (Math.ceil(value / 10.0) * 10);
    }
}
