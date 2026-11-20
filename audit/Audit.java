package pos.ui.swing;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Point;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.TableColumn;

import pos.proc.State;
import pos.ui.Snapshot;
import pos.ui.UiFeed;

/**
 * Auditoria de maquetacion: mide donde cae cada columna y si el texto cabe.
 * No forma parte del proyecto; se compila aparte contra target/classes.
 */
public final class Audit {

    public static void main(String[] args) throws Exception {
        UiFeed feed = new UiFeed();
        feed.setPausePerTurn(4L);
        Shell shell = new Shell();
        Thread worker = new Thread(() -> pos.launch.UiDemo.runHeadless(feed), "escenario");
        worker.setDaemon(true);
        worker.start();

        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            Snapshot shot = feed.latest();
            if (shot != null && shot.processes().stream()
                    .anyMatch(p -> p.state().equals(State.STOPPED.etiqueta()))) {
                break;
            }
            if (feed.isFinished()) {
                break;
            }
            Thread.sleep(5);
        }
        feed.setPausePerTurn(800);
        Thread.sleep(40);

        Snapshot live = feed.latest();
        System.out.println("reloj " + live.clock() + "  fase " + live.phase()
                + "  ejecutando=" + live.count("ejecutando")
                + " listos=" + live.ready().size()
                + " bloqueados=" + live.count("bloqueado")
                + " cpu=" + live.cpuTicks());

        shell.setHistory(feed.history());
        shell.show(live);

        for (String tab : new String[] { "Resumen", "Procesos", "Reparto", "Memoria", "Sistema", "Traza" }) {
            shell.selectTab(tab);
            Capture.layout(shell.rootComponent(), 1280, 820);
            System.out.println("\n===== pestana " + tab);
            report(shell.rootComponent());
            scrolls(shell.rootComponent());
        }
    }

    private static void report(Component c) {
        if (c instanceof TablePanel panel) {
            measure(panel);
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                report(child);
            }
        }
    }

    /** Cada barra de desplazamiento que haya: cuanto mide el contenido contra su hueco. */
    private static void scrolls(Component c) {
        if (c instanceof JScrollPane pane && pane.getWidth() > 0) {
            Dimension view = pane.getViewport().getView() == null
                    ? new Dimension(0, 0) : pane.getViewport().getView().getPreferredSize();
            System.out.printf("  [ScrollPane] hueco %dx%d  contenido prefiere %dx%d  barra-V=%s"
                            + "  barra-H=%s%n",
                    pane.getViewport().getWidth(), pane.getViewport().getHeight(),
                    view.width, view.height,
                    pane.getVerticalScrollBar().isVisible(),
                    pane.getHorizontalScrollBar().isVisible());
        }
        if (c instanceof Container container) {
            for (Component child : container.getComponents()) {
                scrolls(child);
            }
        }
    }

    private static void measure(TablePanel panel) {
        JTable table = panel.table();
        Point origin = new Point(0, 0);
        java.awt.Component walk = table;
        while (walk != null && walk != panel) {
            origin.translate(walk.getX(), walk.getY());
            walk = walk.getParent();
        }
        origin.translate(panel.getX(), panel.getY());

        int total = 0;
        int viewport = table.getParent().getWidth();
        StringBuilder widths = new StringBuilder();
        for (int i = 0; i < table.getColumnCount(); i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            widths.append(column.getHeaderValue()).append('=')
                    .append(column.getWidth()).append(' ');
            total += column.getWidth();
        }
        System.out.printf("  [%s] panel %dx%d prefiere %s max %s  viewport=%d  suma columnas=%d  modo=%d%n",
                panel.getClass().getSimpleName(), panel.getWidth(), panel.getHeight(),
                panel.getPreferredSize(), panel.getMaximumSize(),
                viewport, total, table.getAutoResizeMode());
        System.out.println("    " + widths);

        FontMetrics metrics = table.getFontMetrics(table.getFont());
        StringBuilder needs = new StringBuilder();
        for (int i = 0; i < table.getColumnCount(); i++) {
            TableColumn column = table.getColumnModel().getColumn(i);
            int need = metrics.stringWidth(String.valueOf(column.getHeaderValue())) + 24;
            for (int r = 0; r < table.getRowCount(); r++) {
                Object value = table.getValueAt(r, table.convertColumnIndexToModel(i));
                int width = metrics.stringWidth(value == null ? "" : value.toString()) + 24;
                need = Math.max(need, width);
            }
            needs.append(column.getHeaderValue()).append(">=").append(need).append(' ');
            if (need > column.getWidth()) {
                System.out.printf("    ! columna '%s' necesita %d px y tiene %d%n",
                        column.getHeaderValue(), need, column.getWidth());
            }
        }
        System.out.println("    minimos: " + needs);

        for (int r = 0; r < table.getRowCount(); r++) {
            StringBuilder row = new StringBuilder("    |");
            for (int i = 0; i < table.getColumnCount(); i++) {
                Object value = table.getValueAt(r, table.convertColumnIndexToModel(i));
                row.append(' ').append(value).append(" |");
            }
            System.out.println(row);
        }
    }
}
