package pos.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import pos.alloc.BestFit;
import pos.fs.AllocationMethod;
import pos.fs.FileSystem;
import pos.fs.FsLayout;
import pos.kernel.Kernel;
import pos.mm.MemorySetup;
import pos.proc.Pcb;
import pos.sched.RoundRobinScheduler;

/**
 * La terminal con teclado de verdad.
 *
 * Lo que se prueba aqui es lo que no existia cuando la entrada era un guion: que
 * las lineas lleguen de una tuberia de verdad, que la sesion se vea mientras
 * ocurre y no solo al final, y que el aviso de linea salga una vez y en su sitio.
 */
@DisplayName("La terminal con teclado")
class TerminalVivaTest {

    private static final int FISICAS = 4096;
    private static final int LOGICAS = 256;
    private static final int PAGINA = 16;
    private static final int CUANTO = 6;
    private static final int BLOQUES = 256;
    private static final int BLOQUE = 128;

    /** Entrada que ya trae todo escrito, como una tuberia. */
    private static InputStream guion(String... lineas) {
        return new ByteArrayInputStream(
                String.join("\n", lineas).concat("\n").getBytes(StandardCharsets.UTF_8));
    }

    /** Una pantalla de mentira que solo apunta lo que le escriben. */
    private static final class Pantalla implements Output {
        private final StringBuilder texto = new StringBuilder();

        @Override
        public void print(String chunk) {
            texto.append(chunk);
        }

        @Override
        public String toString() {
            return texto.toString();
        }
    }

    private static FileSystem imagen() {
        FileSystem fs = FileSystem.format(FsLayout.standard(BLOQUES, BLOQUE),
                new AllocationMethod.Indexed(), new BestFit());
        fs.create("/datos.txt", "uno\ndos\ntres\n");
        fs.mkdir("/docs");
        return fs;
    }

    /**
     * Una sesion completa montando el nucleo y esperando a que la terminal lea.
     *
     * El hilo del teclado es un demonio y la entrada ya trae el fin de fichero,
     * asi que la sesion no puede quedarse colgada: en cuanto se lee la ultima
     * linea, hasNext se vuelve falso y la terminal termina sola.
     */
    /**
     * Un teclado que tarda: la primera mirada no encuentra nada.
     *
     * Con una tuberia ya escrita la terminal nunca llega a esperar, y entonces el
     * aviso no tiene ocasion de salir. Este teclado finge la espera para poder
     * probar que el aviso se pinta al esperar y una sola vez.
     */
    private static final class Tarda implements Keys {
        private final LongSupplier clock;
        private final List<String> lineas;
        private int siguiente;
        private boolean primeraMirada = true;

        Tarda(LongSupplier clock, String... lineas) {
            this.clock = clock;
            this.lineas = List.of(lineas);
        }

        @Override
        public boolean hasNext() {
            return siguiente < lineas.size();
        }

        @Override
        public long nextTick() {
            if (primeraMirada) {
                primeraMirada = false;
                return clock.getAsLong() + 5;
            }
            return clock.getAsLong();
        }

        @Override
        public Keystroke next() {
            return new Keystroke(clock.getAsLong(), lineas.get(siguiente++));
        }

        @Override
        public int pending() {
            return lineas.size() - siguiente;
        }
    }

    private static String sesion(Pantalla pantalla, boolean echo, String... lineas)
            throws InterruptedException {
        return sesionCon(new LiveKeys(guion(lineas), () -> 0L), pantalla, echo);
    }

    /**
     * Monta el nucleo con un teclado cualquiera y espera a que la sesion cierre.
     *
     * Se espera a que el hilo del nucleo muera, que es la senal de que la terminal
     * vio el fin de la entrada. Si no muere, la terminal se quedo esperando un
     * turno que no llega, y eso es un fallo que interesa ver, no un cuelgue.
     */
    private static String sesionCon(Keys keys, Pantalla pantalla, boolean echo)
            throws InterruptedException {
        Kernel kernel = new Kernel(new RoundRobinScheduler(CUANTO),
                MemorySetup.paged(FISICAS, LOGICAS, PAGINA));
        kernel.attachFilesystem(imagen());
        Terminal terminal = new Terminal(new Shell(kernel.syscalls()), keys,
                kernel::clock, pantalla, echo);
        Pcb proceso = kernel.createHostedProcess("terminal", terminal);
        terminal.shell().attach(proceso.threads().get(0));
        if (keys instanceof LiveKeys vivas) {
            vivas.start();
        }
        Thread hilo = new Thread(kernel::run, "nucleo");
        hilo.setDaemon(true);
        hilo.start();
        for (int i = 0; i < 600 && hilo.isAlive(); i++) {
            Thread.sleep(10);
        }
        assertFalse(hilo.isAlive(), "la sesion no termino: la terminal se quedo esperando");
        return terminal.transcript();
    }

    @Nested
    @DisplayName("el teclado")
    class Teclado {

        @Test
        @DisplayName("entrega las lineas en el orden en que se escribieron")
        void orden() throws InterruptedException {
            LiveKeys keys = new LiveKeys(guion("ls", "pwd", "exit"), () -> 0L);
            keys.start();
            List<String> leidas = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                while (!keys.hasNext() || keys.pending() == 0) {
                    Thread.sleep(2);
                }
                leidas.add(keys.next().text());
            }
            assertEquals(List.of("ls", "pwd", "exit"), leidas);
        }

        @Test
        @DisplayName("al cerrarse la entrada deja de haber lineas")
        void finDeEntrada() throws InterruptedException {
            LiveKeys keys = new LiveKeys(guion("ls"), () -> 0L);
            keys.start();
            while (keys.pending() == 0) {
                Thread.sleep(2);
            }
            keys.next();
            for (int i = 0; i < 200 && keys.hasNext(); i++) {
                Thread.sleep(5);
            }
            assertFalse(keys.hasNext(), "con la entrada cerrada ya no deberia haber lineas");
            assertEquals(-1L, keys.nextTick());
        }

        @Test
        @DisplayName("mientras espera pide dormir unos tics en vez de no soltar el turno")
        void sondeo() {
            AtomicLong reloj = new AtomicLong(100);
            LiveKeys keys = new LiveKeys(new ByteArrayInputStream(new byte[0]), reloj::get);
            // Sin arrancar el hilo no hay lineas ni fin: es el estado "esperando".
            assertTrue(keys.nextTick() > reloj.get(), "esperando debe pedir dormir, no 0");
            assertEquals(reloj.get() + LiveKeys.POLL_TICKS, keys.nextTick());
        }

        @Test
        @DisplayName("pedir una linea cuando no hay es un error, no una linea vacia")
        void sinLineas() {
            LiveKeys keys = new LiveKeys(new ByteArrayInputStream(new byte[0]), () -> 0L);
            assertThrows(IllegalStateException.class, keys::next);
        }

        @Test
        @DisplayName("acepta acentos y no los parte")
        void acentos() throws InterruptedException {
            LiveKeys keys = new LiveKeys(guion("echo año"), () -> 0L);
            keys.start();
            while (keys.pending() == 0) {
                Thread.sleep(2);
            }
            assertEquals("echo año", keys.next().text());
        }
    }

    @Nested
    @DisplayName("lo que se ve")
    class EnPantalla {

        @Test
        @DisplayName("la salida llega a la pantalla y al acta a la vez")
        void lasDosPartes() throws InterruptedException {
            Pantalla pantalla = new Pantalla();
            String acta = sesion(pantalla, false, "echo hola", "exit 0");
            assertTrue(acta.contains("hola"), "el acta deberia guardar la salida");
            assertTrue(pantalla.toString().contains("hola"), "la pantalla deberia ensenarla");
        }

        @Test
        @DisplayName("sin repetir la linea escrita no se ve el comando, solo lo que responde")
        void sinEco() throws InterruptedException {
            Pantalla pantalla = new Pantalla();
            sesion(pantalla, false, "echo hola", "exit 0");
            assertFalse(pantalla.toString().contains("echo hola"),
                    "con el eco apagado la linea no se repite");
        }

        @Test
        @DisplayName("repitiendo la linea escrita se ve el comando")
        void conEco() throws InterruptedException {
            Pantalla pantalla = new Pantalla();
            String acta = sesion(pantalla, true, "echo hola", "exit 0");
            assertTrue(acta.contains("echo hola"), "con el eco encendido la linea se repite");
        }

        @Test
        @DisplayName("el aviso de linea se ensena al esperar y no una vez por vuelta")
        void aviso() throws InterruptedException {
            Pantalla pantalla = new Pantalla();
            // Este teclado tarda a proposito: es la unica forma de que la
            // terminal llegue a quedarse esperando y pinte el aviso.
            sesionCon(new Tarda(new AtomicLong(0)::get, "echo hola", "exit 0"),
                    pantalla, false);
            String texto = pantalla.toString();
            int avisos = texto.split("/>", -1).length - 1;
            assertTrue(avisos >= 1, "al esperar tiene que haber un aviso: " + texto);
            // Uno por linea como mucho: si se pintara en cada vuelta del sondeo
            // habria decenas y la pantalla seria ilegible.
            assertTrue(avisos <= 4, "el aviso se repite demasiado: " + avisos + " en " + texto);
        }

        @Test
        @DisplayName("con guion ya escrito no se pinta aviso: nadie esta esperando")
        void sinAvisoConGuion() throws InterruptedException {
            Pantalla pantalla = new Pantalla();
            sesion(pantalla, false, "echo hola", "exit 0");
            assertFalse(pantalla.toString().contains("/>"),
                    "sin espera no hay aviso: " + pantalla);
        }
    }
}
