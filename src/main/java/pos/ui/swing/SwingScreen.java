package pos.ui.swing;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import pos.shell.Output;

/**
 * La pantalla de la terminal, sobre el area de texto de la ventana.
 *
 * Dos hilos escriben aqui: el del nucleo, que produce la salida, y el de Swing,
 * que es el unico que puede tocar un componente. Por eso lo que llega no se
 * pinta en el acto: se acumula y se pide un volcado al hilo de Swing. Si cada
 * letra fuera un {@code invokeLater} la cola de eventos se llenaria de miles de
 * tareas diminutas y la ventana iria a tirones.
 *
 * Los volcados se agrupan: mientras hay uno pedido, los demas solo dejan su
 * texto en el bufer. Asi una rafaga de salida se pinta de una vez y no una vez
 * por llamada.
 */
public final class SwingScreen implements Output {

    private final JTextArea area;
    private final StringBuilder buffer = new StringBuilder();
    private boolean drainPosted;

    public SwingScreen(JTextArea area) {
        if (area == null) {
            throw new IllegalArgumentException("la pantalla necesita un area de texto");
        }
        this.area = area;
    }

    @Override
    public void print(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        synchronized (buffer) {
            buffer.append(text);
        }
        post();
    }

    /**
     * Pide el volcado de lo pendiente.
     *
     * El vaciado importa de verdad: el aviso de la terminal se escribe sin salto
     * de linea y sin este empujon se quedaria en el bufer, invisible, hasta que
     * llegara la orden siguiente.
     */
    @Override
    public void flush() {
        post();
    }

    private void post() {
        synchronized (buffer) {
            if (drainPosted) {
                return;
            }
            drainPosted = true;
        }
        SwingUtilities.invokeLater(this::drain);
    }

    private void drain() {
        String chunk;
        synchronized (buffer) {
            chunk = buffer.toString();
            buffer.setLength(0);
            drainPosted = false;
        }
        if (chunk.isEmpty()) {
            return;
        }
        area.append(chunk);
        // Pegado al final: en una terminal lo ultimo es lo que se esta mirando.
        area.setCaretPosition(area.getDocument().getLength());
    }
}
