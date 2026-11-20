package pos.ui.swing;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.WindowConstants;

import pos.base.PosVersion;
import pos.ui.CallsTable;
import pos.ui.MemoryTable;
import pos.ui.ProcessTable;
import pos.ui.SchedulingTable;
import pos.ui.Snapshot;
import pos.ui.SyncTable;

/**
 * La ventana del sistema.
 *
 * Lo que se ve no es una animacion de lo que pasaria: cada panel lee la foto
 * que el nucleo acaba de tomar, y esa foto es el estado real.
 *
 * Seis vistas de la misma foto, una por pregunta. El resumen contesta "como va
 * esto"; procesos, "que hay vivo"; reparto, "quien usa la CPU y quien espera";
 * memoria, "donde esta cada cosa"; sistema, "a que se dedica el nucleo"; traza,
 * "que paso y en que orden".
 *
 * La ventana no ejecuta nada ni lee el nucleo: recibe fotos del feed y las
 * reparte. Eso la hace segura -- nunca ve un estado a medias -- y la hace
 * barata de refrescar: si la foto es la misma, no se toca nada.
 */
public final class Shell {

    /** Nombres de las pestanas, en orden. */
    /** Las pestanas de siempre: las vistas que se abren siempre. */
    private static final String[] BASE_TABS = {
            "Resumen", "Procesos", "Reparto", "Memoria", "Sistema", "Traza"
    };

    /** Nombre de la pestana de terminal, cuando la hay. */
    private static final String TERMINAL_TAB = "Terminal";

    /** Separacion entre dos vistas apiladas. */
    private static final int GAP = 2;

    /** Columnas de las pestanas, para reajustar su alto con cada foto. */
    private final List<JPanel> columns = new ArrayList<>();

    /** Modelos de tabla, que viven fuera de Swing. */
    private final ProcessTable processModel = new ProcessTable();
    private final SchedulingTable schedulingModel = new SchedulingTable();
    private final MemoryTable memoryModel = new MemoryTable();
    private final CallsTable callsModel = new CallsTable();
    private final SyncTable syncModel = new SyncTable();

    /** Vistas. */
    private final TablePanel processes;
    private final TablePanel threadsProc;
    private final TablePanel threadsSched;
    private final TablePanel memory;
    private final TablePanel calls;
    private final TablePanel sync;
    private final TablePanel queue;
    private final MemoryMapPanel mapSummary = new MemoryMapPanel();
    private final MemoryMapPanel mapMemory = new MemoryMapPanel();
    private final CpuBarsPanel cpu = new CpuBarsPanel();
    private final HistoryPanel history = new HistoryPanel();
    private final TracePanel trace = new TracePanel();

    private final CardLayout cards = new CardLayout();
    private final JPanel pages = new JPanel(cards);
    private final TabBar tabs;

    /**
     * Pestanas que tiene esta ventana.
     *
     * No es una constante porque la de terminal solo existe cuando alguien va a
     * escribir: una captura sin ventana no tiene teclado que ofrecer, y una
     * pestana que no se puede usar es peor que no tenerla.
     */
    private final String[] tabNames;
    private final TopBar top = new TopBar();
    private final StatusBar status = new StatusBar();

    /** Ultima foto mostrada. */
    private Snapshot shown;

    /** Raiz del contenido: todo lo que se ve cuelga de aqui. */
    private final JPanel root = new JPanel(new BorderLayout());

    /**
     * Construye el contenido de la ventana, sin ventana.
     *
     * La separacion es lo que permite capturar la interfaz: en un contenedor sin
     * pantalla no se puede crear un JFrame, pero si se puede construir el arbol
     * de componentes, darle un tamano y pintarlo sobre una imagen. Por eso aqui
     * no hay nada que dependa de tener una ventana abierta.
     */
    public Shell() {
        this(null);
    }

    /**
     * Construye el contenido con una terminal dentro, si se pasa una.
     *
     * @param terminalPage la pestana de terminal, o {@code null} para no tenerla
     */
    public Shell(JComponent terminalPage) {
        this.tabNames = terminalPage == null
                ? BASE_TABS.clone()
                : new String[] { BASE_TABS[0], BASE_TABS[1], BASE_TABS[2], BASE_TABS[3],
                        BASE_TABS[4], BASE_TABS[5], TERMINAL_TAB };
        this.tabs = new TabBar(tabNames);

        processes = new TablePanel(processModel, new int[] { 46, 116, 110, 62, 62, 70, 70, 112 },
                new int[] { 0, 3, 4, 5, 6 }, 2);
        threadsProc = new TablePanel(schedulingModel,
                new int[] { 116, 116, 110, 48, 54, 70, 78, 150 },
                new int[] { 3, 4, 5, 6 }, 2);
        threadsSched = new TablePanel(schedulingModel,
                new int[] { 116, 116, 110, 48, 54, 70, 78, 150 },
                new int[] { 3, 4, 5, 6 }, 2);
        memory = new TablePanel(memoryModel, new int[] { 72, 72, 82, 132 },
                new int[] { 0, 1, 2 }, -1);
        calls = new TablePanel(callsModel, new int[] { 70, 200, 70 },
                new int[] { 0, 2 }, -1);
        sync = new TablePanel(syncModel, new int[] { 92, 120, 282, 78 },
                new int[] { 3 }, -1);
        queue = new TablePanel("Cola de listos", schedulingModel.queueHeaders(),
                new int[] { 64, 136, 140 }, new int[] { 0 }, -1);

        pages.setBackground(Theme.BG);
        pages.add(pageSummary(), tabNames[0]);
        pages.add(pageProcesses(), tabNames[1]);
        pages.add(pageScheduling(), tabNames[2]);
        pages.add(pageMemory(), tabNames[3]);
        pages.add(pageSystem(), tabNames[4]);
        pages.add(pageTrace(), tabNames[5]);
        if (terminalPage != null) {
            JPanel page = new JPanel(new BorderLayout());
            page.setBackground(Theme.BG);
            page.add(terminalPage, BorderLayout.CENTER);
            pages.add(page, TERMINAL_TAB);
        }

        root.setBackground(Theme.BG);
        JPanel chrome = new JPanel(new BorderLayout());
        chrome.setBackground(Theme.BG);
        chrome.add(top, BorderLayout.NORTH);
        chrome.add(tabs, BorderLayout.CENTER);
        root.add(chrome, BorderLayout.NORTH);
        root.add(pages, BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);
    }

    /** El contenido, para colgarlo de una ventana o pintarlo en una imagen. */
    public JComponent rootComponent() {
        return root;
    }

    /**
     * La ventana, ya montada sobre el contenido.
     *
     * Se crea aparte porque crear un JFrame exige pantalla: quien no la tenga
     * -- una captura, un test -- usa {@link #rootComponent()} y se ahorra el
     * problema de que AWT se niegue a arrancar.
     */
    public JFrame frame() {
        JFrame frame = new JFrame("P-OS · sistema operativo sobre la JVM");
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setContentPane(root);
        frame.setMinimumSize(new Dimension(980, 640));
        frame.setSize(1280, 820);
        frame.setLocationRelativeTo(null);
        return frame;
    }

    /** Pestana del resumen: las tres vistas que se miran de reojo. */
    private JPanel pageSummary() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(Theme.BG);
        // Arriba el estado de ahora (CPU y mapa), abajo la evolucion. El mapa se
        // queda con su alto justo -- es una cinta de tres filas -- y el grafico
        // se lleva lo que sobra, que es donde de verdad hay algo que mirar.
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(cpu, BorderLayout.NORTH);
        north.add(mapSummary, BorderLayout.CENTER);
        page.add(north, BorderLayout.NORTH);
        page.add(history, BorderLayout.CENTER);
        return page;
    }

    /** Pestana de procesos: quien existe y quien ejecuta. */
    private JPanel pageProcesses() {
        return page(processes, threadsProc);
    }

    /** Pestana del reparto: los hilos con sus tiempos y la cola en orden. */
    private JPanel pageScheduling() {
        return page(threadsSched, queue);
    }

    /** Pestana de memoria: el mapa y sus tramos. */
    private JPanel pageMemory() {
        return page(mapMemory, memory);
    }

    /** Pestana del sistema: lo que se le pide al nucleo y lo que espera. */
    private JPanel pageSystem() {
        return page(calls, sync);
    }

    /** Pestana de la traza. */
    private JPanel pageTrace() {
        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(Theme.BG);
        page.add(trace, BorderLayout.CENTER);
        return page;
    }

    /**
     * Pestana con las vistas apiladas y el fondo detras.
     *
     * Cada vista se queda con el alto de su contenido y lo que sobra es fondo
     * de la aplicacion, no filas vacias: una tabla de tres filas estirada a
     * cuatrocientos pixeles no dice nada, solo ocupa. La barra aparece unicamente
     * cuando la pila no cabe entera, y entonces se puede recorrer sin perder
     * nada por abajo.
     */
    private JPanel page(JComponent... views) {
        Column column = new Column();
        for (int index = 0; index < views.length; index++) {
            if (index > 0) {
                column.add(Box.createVerticalStrut(GAP));
            }
            JComponent view = views[index];
            view.setAlignmentX(Component.LEFT_ALIGNMENT);
            column.add(view);
        }
        JScrollPane scroll = new JScrollPane(column,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(true);
        scroll.setBackground(Theme.BG);
        scroll.getViewport().setBackground(Theme.BG);
        scroll.getVerticalScrollBar().setUI(new FlatScrollBarUI());
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));

        JPanel page = new JPanel(new BorderLayout());
        page.setBackground(Theme.BG);
        page.add(scroll, BorderLayout.CENTER);
        return page;
    }

    /** Columna de vistas: ancho de la ventana, alto el de su contenido. */
    private final class Column extends JPanel implements Scrollable {

        Column() {
            setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
            setBackground(Theme.BG);
            columns.add(this);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return Theme.ROW_HEIGHT;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(Theme.ROW_HEIGHT, visible.height - Theme.ROW_HEIGHT);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /**
     * Ajusta el techo de cada vista al alto que pide.
     *
     * BoxLayout reparte el espacio que sobra hasta el maximo de cada hijo, y por
     * defecto ese maximo es enorme: sin este paso las tablas volverian a
     * estirarse. Se llama despues de cada foto porque el numero de filas cambia
     * con el tiempo, y el alto de la vista cambia con el.
     */
    private void settle() {
        for (JPanel column : columns) {
            for (Component child : column.getComponents()) {
                int height = child.getPreferredSize().height;
                if (child.getMaximumSize().height != height) {
                    child.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
                }
            }
            column.revalidate();
        }
    }

    /**
     * Muestra una foto en todas las vistas.
     *
     * Se puede llamar desde cualquier hilo: el que llama es el temporizador de
     * Swing, que ya corre en el hilo de la interfaz.
     */
    public void show(Snapshot shot) {
        shown = shot;
        if (shot == null) {
            clear();
            return;
        }
        processes.refresh(shot);
        threadsProc.refresh(shot);
        threadsSched.refresh(shot);
        memory.refresh(shot);
        calls.refresh(shot);
        sync.refresh(shot);
        queue.update(schedulingModel.queueRows(shot), "en orden de salida");
        mapSummary.update(shot);
        mapMemory.update(shot);
        cpu.update(shot);
        history.update(histories, shot);

        fit(processes, shot.processes().size());
        fit(threadsProc, shot.threads().size());
        fit(threadsSched, shot.threads().size());
        fit(memory, memoryModel.rows(shot).size());
        fit(calls, shot.calls().rows().size());
        // La cola y la tabla de sincronizacion se quedan con las filas que lleven
        // ahora, no con el alto de relleno del modelo: si no, la pestana no cabe y
        // saca una barra pegada al borde de la ventana.
        fit(queue, schedulingModel.queueRows(shot).size());
        fit(sync, syncModel.rows(shot).size());

        String traceText = String.join("\n", shot.trace());
        String traceSummary = shot.trace().size() + " lineas"
                + (shot.traceTruncated() ? "  ·  recortada" : "");
        trace.setLines(traceText, traceSummary, true);

        top.update(shot);
        status.update(shot);
        settle();
        pages.revalidate();
        pages.repaint();
    }

    /** Serie temporal que acompana a la foto. */
    private List<long[]> histories = List.of();

    /** Fija la serie temporal que dibuja el panel de evolucion. */
    public void setHistory(List<long[]> samples) {
        this.histories = samples == null ? List.of() : samples;
    }

    /** Deja todas las vistas vacias. */
    public void clear() {
        shown = null;
        processes.clear();
        threadsProc.clear();
        threadsSched.clear();
        memory.clear();
        calls.clear();
        sync.clear();
        queue.clear();
        mapSummary.clear();
        mapMemory.clear();
        cpu.clear();
        history.clear();
        trace.clear();
        top.clear();
        status.clear();
    }

    /** La ultima foto mostrada, o {@code null} si no se ha mostrado ninguna. */
    public Snapshot shown() {
        return shown;
    }

    /** Selecciona una pestana por su nombre, si existe. */
    public void selectTab(String name) {
        for (String tab : tabNames) {
            if (tab.equals(name)) {
                tabs.select(tab);
                cards.show(pages, tab);
                return;
            }
        }
    }

    /** Nombres de las pestanas. */
    public static List<String> tabNames() {
        return List.of(BASE_TABS);
    }

    /**
     * Ajusta el alto de una tabla al numero de filas que lleva.
     *
     * Sin esto, cada tabla se queda con el alto de relleno de Swing y la ventana
     * aparece llena de huecos vacios donde deberia haber filas y scroll. Se
     * calcula: banda de titulo, cabecera, filas y el canal de la barra.
     */
    private void fit(TablePanel panel, int rows) {
        int height = SectionHeader.HEIGHT + Theme.HEADER_HEIGHT + Math.max(1, rows) * Theme.ROW_HEIGHT + 10;
        Dimension size = new Dimension(10, Math.min(height, 420));
        if (!size.equals(panel.getPreferredSize())) {
            panel.setPreferredSize(size);
        }
    }

    /** Tira de pestanas: texto, subrayado en la activa y raton. */
    private final class TabBar extends JPanel {

        private final String[] names;
        private final int[] spans;
        private String active;
        private int hover = -1;

        TabBar(String[] names) {
            this.names = names;
            this.spans = new int[names.length];
            this.active = names[0];
            setOpaque(true);
            setBackground(Theme.BG);
            setPreferredSize(new Dimension(10, 42));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    int index = indexAt(e.getX());
                    if (index >= 0) {
                        select(names[index]);
                    }
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = -1;
                    repaint();
                }
            });
            addMouseMotionListener(new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int index = indexAt(e.getX());
                    if (index != hover) {
                        hover = index;
                        repaint();
                    }
                }
            });
        }

        void select(String name) {
            active = name;
            cards.show(pages, name);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            Theme.smooth(g2);
            int x = 18;
            int baseline = getHeight() - 14;
            for (int index = 0; index < names.length; index++) {
                String name = names[index];
                g2.setFont(Theme.sansBold(12.5f));
                int width = g2.getFontMetrics().stringWidth(name);
                boolean isActive = name.equals(active);
                boolean isHover = index == hover;
                g2.setColor(isActive ? Theme.TEXT : isHover ? Theme.MUTED : Theme.DIM);
                g2.drawString(name, x, baseline);
                if (isActive) {
                    g2.setColor(Theme.ACCENT);
                    g2.fillRoundRect(x, getHeight() - 6, width, 2, 2, 2);
                }
                x += width + 26;
            }
            g2.setColor(Theme.BORDER);
            g2.drawLine(0, getHeight() - 1, getWidth(), getHeight() - 1);
            g2.dispose();
        }

        private int indexAt(int mouseX) {
            int x = 18;
            Graphics2D g2 = (Graphics2D) getGraphics();
            if (g2 == null) {
                return -1;
            }
            Theme.smooth(g2);
            g2.setFont(Theme.sansBold(12.5f));
            for (int index = 0; index < names.length; index++) {
                int width = g2.getFontMetrics().stringWidth(names[index]);
                if (mouseX >= x - 6 && mouseX <= x + width + 6) {
                    g2.dispose();
                    return index;
                }
                x += width + 26;
            }
            g2.dispose();
            return -1;
        }

        /** Anchos de las pestanas, calculados una vez con la fuente ya cargada. */
        int[] spans() {
            return spans;
        }
    }

    /** Barra superior: la marca a la izquierda y el estado del reloj a la derecha. */
    private final class TopBar extends JPanel {

        TopBar() {
            setOpaque(true);
            setBackground(Theme.BG);
            setPreferredSize(new Dimension(10, 46));
        }

        void update(Snapshot shot) {
            repaint();
        }

        void clear() {
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            Theme.smooth(g2);
            int h = getHeight();

            g2.setFont(Theme.sansBold(17f));
            g2.setColor(Theme.TEXT);
            g2.drawString("P-OS", 18, h - 16);

            g2.setFont(Theme.sans(11f));
            g2.setColor(Theme.MUTED);
            g2.drawString("sistema operativo sobre la JVM", 74, h - 16);

            Snapshot shot = shown;
            String right = shot == null
                    ? "esperando el primer turno"
                    : "reloj " + shot.clock() + "  ·  " + shot.phase()
                            + "  ·  " + shot.scheduler();
            g2.setFont(Theme.mono(10.5f));
            g2.setColor(shot == null ? Theme.DIM : Theme.MUTED);
            int width = g2.getFontMetrics().stringWidth(right);
            g2.drawString(right, getWidth() - width - 18, h - 16);

            g2.setColor(Theme.BORDER);
            g2.drawLine(0, h - 1, getWidth(), h - 1);
            g2.dispose();
        }
    }

    /** Pie: que se esta viendo y cuanto hay. */
    private final class StatusBar extends JPanel {

        private String left = "esperando datos";
        // La version sale de PosVersion para que la ventana no pueda
        // discrepar de --version ni del prompt de la terminal.
        private String right = PosVersion.PIE;

        StatusBar() {
            setOpaque(true);
            setBackground(Theme.PANEL_ALT);
            setPreferredSize(new Dimension(10, 26));
        }

        void update(Snapshot shot) {
            int live = shot.liveProcesses();
            int total = shot.processes().size();
            left = total + " procesos (" + live + " vivos)  ·  " + shot.threads().size() + " hilos  ·  "
                    + shot.memory().used() + "/" + shot.memory().size() + " unidades  ·  "
                    + shot.ready().size() + " en cola  ·  " + shot.calls().total() + " llamadas";
            repaint();
        }

        void clear() {
            left = "esperando datos";
            repaint();
        }

        /** Texto de la derecha, para marcar el modo captura o la version. */
        void right(String text) {
            right = text;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            Theme.smooth(g2);
            int h = getHeight();
            g2.setFont(Theme.mono(10f));
            g2.setColor(Theme.MUTED);
            g2.drawString(left, 18, h - 9);
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(Theme.DIM);
            g2.drawString(right, getWidth() - fm.stringWidth(right) - 18, h - 9);
            g2.setColor(Theme.BORDER);
            g2.drawLine(0, 0, getWidth(), 0);
            g2.dispose();
        }
    }
}
