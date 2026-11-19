package pos.ui.swing;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.plaf.basic.BasicTableUI;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;

import pos.ui.Snapshot;
import pos.ui.Table;

/**
 * Una tabla de la consola, pintada a partir de un modelo de {@code pos.ui}.
 *
 * El panel no decide que columnas hay ni que dice cada fila: eso lo hace el
 * modelo, que se prueba con JUnit sin abrir ventana. Aqui solo se pinta. Y como
 * el modelo tambien trae el titulo y el resumen, refrescar la tabla es una linea:
 * pedir las filas de la foto y ponerselas.
 *
 * Hay dos formas de usarlo. Con un modelo, para las tablas que ya existen en
 * {@code pos.ui}; o suelto, para las listas que solo viven en la ventana -- la
 * cola de listos, la traza -- que se rellenan con {@link #update(List, String)}.
 *
 * Alineacion: las columnas marcadas como numericas van a la derecha. Una columna
 * de numeros alineada a la izquierda no se puede comparar de un vistazo, y
 * comparar de un vistazo es de lo que va esto. La columna de estado, si se
 * indica, lleva un punto del color del estado antes del texto.
 */
public final class TablePanel extends JPanel {

    /** Columnas que se alinean a la derecha. */
    private final int[] numeric;

    /** Columna que lleva punto de color, o -1. */
    private final int dotColumn;

    /** Modelo de filas de Swing. */
    private final RowsModel model;

    /** Fuente de las filas, o {@code null} si se rellena a mano. */
    private final Table source;

    /** Banda de titulo y resumen. */
    private final SectionHeader head;

    /** La tabla, por si alguien necesita la seleccion. */
    private final JTable table;

    /**
     * Construye el panel de un modelo de tabla.
     *
     * @param source    modelo que sabe las columnas y las filas
     * @param widths    ancho preferido de cada columna, en pixeles
     * @param numeric   indices de columna que se alinean a la derecha
     * @param dotColumn columna de estado con punto de color, o -1
     */
    public TablePanel(Table source, int[] widths, int[] numeric, int dotColumn) {
        this(source, source.title(), source.headers(), widths, numeric, dotColumn);
    }

    /**
     * Construye una tabla suelta, sin modelo detras.
     *
     * @param title   titulo de la seccion
     * @param headers cabeceras de columna
     * @param widths  ancho preferido de cada columna
     * @param numeric indices de columna alineados a la derecha
     * @param dotColumn columna de estado con punto, o -1
     */
    public TablePanel(String title, String[] headers, int[] widths, int[] numeric, int dotColumn) {
        this(null, title, headers, widths, numeric, dotColumn);
    }

    private TablePanel(Table source, String title, String[] headers, int[] widths,
            int[] numeric, int dotColumn) {
        super(new BorderLayout());
        this.source = source;
        this.numeric = numeric == null ? new int[0] : numeric.clone();
        this.dotColumn = dotColumn;

        setOpaque(true);
        setBackground(Theme.PANEL);

        model = new RowsModel(headers);
        table = new JTable(model);
        table.setUI(new BasicTableUI());
        table.setBackground(Theme.PANEL);
        table.setForeground(Theme.TEXT);
        table.setFont(Theme.mono(12f));
        table.setRowHeight(Theme.ROW_HEIGHT);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setSelectionBackground(new Color(0x24384F));
        table.setSelectionForeground(Theme.TEXT);
        table.setFocusable(false);
        table.setDefaultRenderer(Object.class, new CellRenderer());

        styleHeader(table.getTableHeader());
        for (int i = 0; i < widths.length && i < table.getColumnCount(); i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }

        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.setBackground(Theme.PANEL);
        scroll.getVerticalScrollBar().setUI(new FlatScrollBarUI());
        scroll.getHorizontalScrollBar().setUI(new FlatScrollBarUI());
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
        scroll.getHorizontalScrollBar().setPreferredSize(new Dimension(9, 0));

        head = new SectionHeader(title);
        add(head, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    /**
     * Refresca la tabla con una foto.
     *
     * Si el panel se construyo con modelo, las filas, el titulo y el resumen
     * salen de el. Si no, se ignora: una tabla suelta se rellena por su cuenta.
     */
    public void refresh(Snapshot shot) {
        if (source == null || shot == null) {
            return;
        }
        head.setTitle(source.title());
        head.setSummary(source.summary(shot));
        model.setRows(source.rows(shot));
    }

    /** Rellena la tabla a mano: filas ya en texto y resumen de la cabecera. */
    public void update(List<String[]> rows, String summary) {
        head.setSummary(summary);
        model.setRows(rows);
    }

    /** Deja la tabla vacia. */
    public void clear() {
        update(List.of(), "");
    }

    /** Ancho preferido de una columna. */
    public void columnWidth(int column, int width) {
        if (column >= 0 && column < table.getColumnCount()) {
            table.getColumnModel().getColumn(column).setPreferredWidth(width);
        }
    }

    /** La tabla de Swing. */
    public JTable table() {
        return table;
    }

    /** Cabecera de columnas: fondo plano y texto pequeno en mayusculas. */
    private void styleHeader(JTableHeader header) {
        header.setDefaultRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                    boolean focused, int row, int col) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(
                        t, value, false, false, row, col);
                label.setFont(Theme.sansBold(10.5f));
                label.setForeground(Theme.MUTED);
                label.setBackground(Theme.PANEL_ALT);
                label.setOpaque(true);
                label.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
                label.setText(value == null ? "" : value.toString().toUpperCase());
                label.setHorizontalAlignment(isNumeric(t.convertColumnIndexToModel(col))
                        ? SwingConstants.RIGHT : SwingConstants.LEFT);
                return label;
            }
        });
        header.setPreferredSize(new Dimension(10, Theme.HEADER_HEIGHT));
        header.setReorderingAllowed(false);
        header.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
    }

    private boolean isNumeric(int column) {
        for (int index : numeric) {
            if (index == column) {
                return true;
            }
        }
        return false;
    }

    /** Pinta una celda: fondo alterno, alineacion y punto de estado. */
    private final class CellRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                boolean focused, int row, int col) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    t, value, selected, false, row, col);
            label.setFont(Theme.mono(12f));
            label.setOpaque(true);
            label.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
            label.setForeground(Theme.TEXT);
            if (!selected) {
                label.setBackground(row % 2 == 0 ? Theme.PANEL : Theme.PANEL_ALT);
            }
            int index = t.convertColumnIndexToModel(col);
            label.setHorizontalAlignment(isNumeric(index)
                    ? SwingConstants.RIGHT : SwingConstants.LEFT);

            String text = value == null ? "" : value.toString();
            if (index == dotColumn) {
                Color color = Theme.stateColor(text);
                label.setIcon(new Dot(color));
                label.setForeground(color);
            } else {
                label.setIcon(null);
                if ("terminado".equals(text)) {
                    label.setForeground(Theme.DIM);
                }
            }
            label.setText(text);
            return label;
        }
    }

    /** Un circulo de siete pixeles del color de un estado. */
    private static final class Dot implements Icon {

        private final Color color;

        Dot(Color color) {
            this.color = color;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            Theme.smooth(g2);
            g2.setColor(color);
            g2.fillOval(x, y + 2, 7, 7);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 11;
        }

        @Override
        public int getIconHeight() {
            return 11;
        }
    }

    /** Modelo de filas ya convertidas a texto. */
    private static final class RowsModel extends AbstractTableModel {

        private final String[] headers;
        private List<String[]> rows = List.of();

        RowsModel(String[] headers) {
            this.headers = headers.clone();
        }

        void setRows(List<String[]> newRows) {
            rows = newRows == null ? List.of() : new ArrayList<>(newRows);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return headers.length;
        }

        @Override
        public String getColumnName(int column) {
            return headers[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            String[] values = rows.get(row);
            return column < values.length ? values[column] : "";
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    }
}
