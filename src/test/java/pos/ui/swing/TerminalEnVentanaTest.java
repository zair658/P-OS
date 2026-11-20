package pos.ui.swing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import javax.swing.JTextArea;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import pos.kernel.Kernel;
import pos.mm.MemorySetup;
import pos.proc.Pcb;
import pos.sched.RoundRobinScheduler;
import pos.shell.Output;
import pos.shell.Terminal;

/**
 * La terminal de la ventana: escribir arriba y que lo ejecute el nucleo.
 *
 * Lo que se prueba aqui es la cadena entera, y cada eslabon tiene su trampa:
 *
 *   - la caja de texto corre en el hilo de Swing y el nucleo en otro, asi que la
 *     linea tiene que pasar por una cola y no ejecutarse al escribirla;
 *   - la salida la produce el nucleo y la tiene que pintar Swing, asi que hay
 *     que comprobar que llega y no solo que se escribio;
 *   - y el nucleo tiene que seguir vivo mientras nadie escribe: si la fuente se
 *     cerrara al quedarse sin lineas, la ventana se quedaria congelada y no se
 *     podria escribir nada mas.
 */
@DisplayName("La terminal de la ventana")
class TerminalEnVentanaTest {

    private static final int FISICAS = 4096;
    private static final int LOGICAS = 256;
    private static final int PAGINA = 16;
    private static final int CUANTO = 6;

    /** Lo que tarda como mucho una orden en dar la vuelta, en milisegundos. */
    private static final long ESPERA = 5000L;

    @Nested
    @DisplayName("La entrada de la ventana")
    class Entrada {

        @Test
        @DisplayName("guarda la linea y la suelta con el tic en que llego")
        void guardaLaLinea() {
            AtomicLong reloj = new AtomicLong(7L);
            SwingKeys keys = new SwingKeys();
            keys.attachClock(reloj::get);

            keys.offer("jobs");

            assertTrue(keys.hasNext());
            assertEquals(1, keys.pending());
            assertEquals(7L, keys.nextTick());
            assertEquals("jobs", keys.next().text());
            assertEquals(0, keys.pending());
        }

        @Test
        @DisplayName("sin nada escrito pide volver a mirar dentro de unos tics")
        void esperaYMiraOtraVez() {
            AtomicLong reloj = new AtomicLong(10L);
            SwingKeys keys = new SwingKeys();
            keys.attachClock(reloj::get);

            assertTrue(keys.hasNext());
            long due = keys.nextTick();

            assertTrue(due > 10L, "con la cola vacia el tic debe quedar por delante");
            assertTrue(due <= 10L + 10L, "y no tan lejos como para parecer colgada");
        }

        @Test
        @DisplayName("cerrada deja de tener entrada cuando ya no queda nada")
        void seCierra() {
            SwingKeys keys = new SwingKeys();
            keys.offer("ps");
            keys.close();

            // Lo pendiente se entrega antes de terminar: cerrar no es tirar.
            assertTrue(keys.hasNext());
            assertEquals("ps", keys.next().text());
            assertFalse(keys.hasNext());
        }

        @Test
        @DisplayName("la ventana no deja pasar lineas en blanco")
        void descartaLineasVacias() {
            TerminalPanel panel = new TerminalPanel();

            panel.submit("   ");
            panel.submit("");

            assertEquals(0, panel.source().pending());
            panel.submit("spawn bucle /bucle.pos &");
            assertEquals(1, panel.source().pending());
        }

        @Test
        @DisplayName("Intro en la caja de texto deja la linea para la terminal")
        void introEnvia() {
            TerminalPanel panel = new TerminalPanel();

            panel.input().setText("ps");
            // Lo que hace Intro: dispara la accion de la caja. Si el oyente no
            // estuviera puesto, la ventana tendria una caja que no hace nada.
            panel.input().postActionEvent();

            assertEquals(1, panel.source().pending());
            assertEquals("ps", panel.source().next().text());
            assertEquals("", panel.input().getText(), "la caja se vacia al enviar");
        }
    }

    @Nested
    @DisplayName("La pantalla de la ventana")
    class Pantalla {

        @Test
        @DisplayName("lo que escribe el nucleo acaba en el area de texto")
        void llegaAlArea() throws Exception {
            JTextArea area = new JTextArea();
            SwingScreen screen = new SwingScreen(area);

            screen.print("pos> ps");
            screen.print("\n");
            screen.flush();
            // El volcado se hace en el hilo de Swing: se espera a que la cola de
            // eventos llegue a este punto para no mirar antes de que pinte.
            SwingUtilities.invokeAndWait(() -> {
                // Nada: solo hacer cola detras del volcado pedido.
            });

            assertEquals("pos> ps\n", area.getText());
        }
    }

    @Nested
    @DisplayName("De la ventana al nucleo")
    class DeLaVentanaAlNucleo {

        @Test
        @DisplayName("lo escrito en la ventana lo ejecuta el nucleo")
        void ejecutaLoEscrito() throws Exception {
            Kernel kernel = new Kernel(new RoundRobinScheduler(CUANTO),
                    MemorySetup.paged(FISICAS, LOGICAS, PAGINA));
            // El nombre Shell esta aqui dos veces: el de la cascara, que es este,
            // y el de la ventana, que es el de este mismo paquete.
            pos.shell.Shell cascara = new pos.shell.Shell(kernel.syscalls());
            SwingKeys keys = new SwingKeys();
            StringBuilder texto = new StringBuilder();
            Output pantalla = texto::append;
            Terminal terminal = new Terminal(cascara, keys, kernel::clock, pantalla, true);
            Pcb proceso = kernel.createHostedProcess("terminal", terminal);
            cascara.attach(proceso.threads().get(0));
            keys.attachClock(kernel::clock);

            Thread nucleo = new Thread(kernel::run, "nucleo");
            nucleo.setDaemon(true);
            nucleo.start();
            try {
                keys.offer("help");

                assertTrue(esperar(() -> texto.indexOf("ordenes (") >= 0),
                        "el nucleo tenia que ejecutar el help escrito en la ventana: " + texto);
                // El aviso va delante de lo ejecutado: se ve de donde salio.
                assertTrue(texto.indexOf("help") < texto.indexOf("ordenes ("),
                        "la linea escrita tiene que salir antes que su respuesta");
            } finally {
                // Sin cerrar la fuente el nucleo no terminaria nunca: la ventana
                // esta abierta por definicion. Aqui se cierra para no dejar un
                // hilo dando vueltas despues de la prueba.
                keys.close();
                nucleo.join(ESPERA);
            }
        }

        /** Espera a que se cumpla algo, con un tope de tiempo. */
        private boolean esperar(BooleanSupplier condicion) throws InterruptedException {
            long limite = System.currentTimeMillis() + ESPERA;
            while (System.currentTimeMillis() < limite) {
                if (condicion.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(10L);
            }
            return condicion.getAsBoolean();
        }
    }
}
