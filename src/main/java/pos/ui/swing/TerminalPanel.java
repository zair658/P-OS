package pos.ui.swing;

import java.awt.BorderLayout;
import java.awt.Dimension;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;

import pos.shell.Keys;
import pos.shell.Output;

/**
 * La terminal, dentro de la propia ventana.
 *
 * El escenario de la ventana ya lleva una terminal como proceso, pero con un
 * guion: las ordenes estan escritas de antemano y nadie puede cambiarlas. Esta
 * pestana es la misma terminal con otra fuente de entrada -- el campo de texto
 * de abajo --, y por eso lo que se escribe aqui lo ejecuta el nucleo de verdad,
 * con sus llamadas al sistema y su planificacion, no un interprete aparte.
 *
 * La linea que se escribe se engancha como una mas en la cola de la fuente, en
 * vez de ejecutarse aqui mismo. Esa diferencia es la que hace que escribir no
 * pare al sistema: entre que se pulsa Intro y sale la respuesta, los demas
 * procesos siguen corriendo, y se les ve correr en las otras pestanas.
 *
 * No hay ningun comando fuera de la cascara: si aqui pone que no existe, es que
 * no existe en P-OS. La ventana no completa ni perdona nada.
 */
public final class TerminalPanel extends JPanel {

    /** Margen interior de la pantalla. */
    private static final int PAD = 10;

    private final SwingKeys source = new SwingKeys();
    private final SwingScreen screen;
    private final JTextArea text = new JTextArea();
    private final JTextField input = new JTextField();

    public TerminalPanel() {
        super(new BorderLayout());
        setBackground(Theme.BG);

        text.setEditable(false);
        text.setFont(Theme.mono(13f));
        text.setBackground(Theme.PANEL);
        text.setForeground(Theme.TEXT);
        text.setCaretColor(Theme.TEXT);
        text.setBorder(BorderFactory.createEmptyBorder(PAD, PAD, PAD, PAD));
        // Sin ajuste de linea: una tabla de ps se lee en columnas, y si se parte
        // deja de leerse. Para eso esta la barra horizontal.
        text.setLineWrap(false);

        // La cabecera va fuera del area de texto y no dentro: dentro se iria
        // hacia arriba con la sesion, y a la tercera orden ya no se sabria como
        // se escribe aqui.
        JLabel head = new JLabel("P-OS · terminal del sistema · escribe abajo y pulsa Intro · help para ver las ordenes");
        head.setFont(Theme.mono(12f));
        head.setForeground(Theme.MUTED);
        head.setBorder(BorderFactory.createEmptyBorder(PAD, PAD, 0, PAD));

        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.PANEL);
        scroll.getVerticalScrollBar().setUI(new FlatScrollBarUI());
        scroll.getHorizontalScrollBar().setUI(new FlatScrollBarUI());
        scroll.getVerticalScrollBar().setPreferredSize(new Dimension(9, 0));
        scroll.getHorizontalScrollBar().setPreferredSize(new Dimension(0, 9));

        JLabel prompt = new JLabel("pos>");
        prompt.setFont(Theme.mono(13f));
        prompt.setForeground(Theme.ACCENT);
        prompt.setBorder(BorderFactory.createEmptyBorder(0, PAD, 0, 8));

        input.setFont(Theme.mono(13f));
        input.setBackground(Theme.PANEL_ALT);
        input.setForeground(Theme.TEXT);
        input.setCaretColor(Theme.TEXT);
        input.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        // Intro en el campo es la unica tecla que hace falta: no hay flechas ni
        // historial que ofrecer, porque el editor de linea es el propio campo.
        input.addActionListener(event -> send());

        JPanel row = new JPanel(new BorderLayout());
        row.setBackground(Theme.BG);
        row.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
        row.add(prompt, BorderLayout.WEST);
        row.add(input, BorderLayout.CENTER);

        add(head, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(row, BorderLayout.SOUTH);

        screen = new SwingScreen(text);
    }

    /** La fuente de entrada, para que el nucleo la use como teclado. */
    public Keys keys() {
        return source;
    }

    /** La fuente de entrada tal cual, que es la que admite lineas y reloj. */
    public SwingKeys source() {
        return source;
    }

    /** La pantalla, para que el nucleo escriba en ella. */
    public Output screen() {
        return screen;
    }

    /** Pone el cursor donde se escribe, al entrar en la pestana. */
    public void focusInput() {
        input.requestFocusInWindow();
    }

    /** Lo que se ha escrito en la pantalla. Sirve para comprobar sin ventana. */
    public String transcript() {
        return text.getText();
    }

    /** Recoge la linea escrita y la deja para la terminal. No ejecuta nada. */
    private void send() {
        String line = input.getText();
        input.setText("");
        submit(line);
    }

    /** La caja donde se escribe. Solo para las pruebas: nadie mas la toca. */
    JTextField input() {
        return input;
    }

    /**
     * Deja una linea para la terminal, si dice algo.
     *
     * Una linea vacia no le dice nada al sistema: se descarta aqui y no se gasta
     * un turno del nucleo en ella.
     */
    void submit(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        source.offer(line);
    }
}
