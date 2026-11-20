package pos.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pruebas del teclado con editor de linea.
 *
 * Se le dan bytes, que es justo lo que llega de un terminal, y se miran las
 * lineas que salen. El modo crudo va apagado porque aqui no hay terminal que
 * configurar; lo que se prueba es lo que hace con lo que recibe, que es donde
 * estan las trampas: las flechas llegan como tres bytes y las letras acentuadas
 * como dos o tres.
 */
class TtyKeysTest {

    private final ByteArrayOutputStream salida = new ByteArrayOutputStream();
    private final PrintStream out = new PrintStream(salida, true, StandardCharsets.UTF_8);

    private TtyKeys teclado(String entrada, List<String> historial, List<String> ordenes) {
        byte[] bytes = entrada.getBytes(StandardCharsets.UTF_8);
        TtyKeys keys = new TtyKeys(new ByteArrayInputStream(bytes), out, () -> 0L,
                historial, ordenes, false);
        keys.start();
        return keys;
    }

    /** Igual, con el ancho del terminal fijado: sin terminal no hay a quien preguntar. */
    private TtyKeys teclado(String entrada, List<String> historial, List<String> ordenes,
            int ancho) {
        byte[] bytes = entrada.getBytes(StandardCharsets.UTF_8);
        TtyKeys keys = new TtyKeys(new ByteArrayInputStream(bytes), out, () -> 0L,
                historial, ordenes, false, ancho);
        keys.start();
        return keys;
    }

    /**
     * Espera a que el lector termine y devuelve las lineas recogidas.
     *
     * Se recoge dentro del bucle a proposito: {@code hasNext()} significa "hay
     * linea o puede llegar", asi que con lineas ya en la cola seguiria diciendo
     * que si y la espera no acabaria hasta el limite.
     */
    private List<String> lineas(TtyKeys keys) {
        List<String> recogidas = new ArrayList<>();
        long limite = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < limite) {
            while (keys.pending() > 0) {
                recogidas.add(keys.next().text());
            }
            if (!keys.hasNext()) {
                return recogidas;
            }
            try {
                Thread.sleep(2);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                fail("interrumpido esperando al teclado");
            }
        }
        fail("el teclado no termino a tiempo");
        return recogidas;
    }

    @Test
    @DisplayName("lo tecleado sale como linea al pulsar enter")
    void lineaSimple() {
        assertEquals(List.of("pwd"), lineas(teclado("pwd\r", List.of(), List.of())));
    }

    @Test
    @DisplayName("el salto de linea del teclado tambien vale como enter")
    void enterConSalto() {
        assertEquals(List.of("pwd"), lineas(teclado("pwd\n", List.of(), List.of())));
    }

    @Test
    @DisplayName("la flecha arriba recupera el historial")
    void flechaArriba() {
        // ESC [ A es la flecha arriba: tres bytes que hay que juntar en una tecla.
        assertEquals(List.of("echo hola"),
                lineas(teclado("\u001b[A\r", List.of("echo hola"), List.of())));
    }

    @Test
    @DisplayName("arriba y abajo recorren el historial y vuelven al borrador")
    void historialCompleto() {
        List<String> historial = List.of("uno", "dos");
        // arriba, arriba (uno), abajo (dos), abajo (borrador vacio), enter
        assertEquals(List.of(""), lineas(teclado("\u001b[A\u001b[A\u001b[B\u001b[B\r",
                historial, List.of())));
    }

    @Test
    @DisplayName("la flecha izquierda deja escribir en medio de la linea")
    void editarEnMedio() {
        // "ac", izquierda, "b" -> "abc"
        assertEquals(List.of("abc"),
                lineas(teclado("ac\u001b[Db\r", List.of(), List.of())));
    }

    @Test
    @DisplayName("inicio y fin llevan el cursor a los extremos")
    void inicioYFin() {
        // "bc", inicio, "a" -> "abc"
        assertEquals(List.of("abc"),
                lineas(teclado("bc\u001b[Ha\r", List.of(), List.of())));
        // "ab", fin, "c" -> "abc"
        assertEquals(List.of("abc"),
                lineas(teclado("ab\u001b[Fc\r", List.of(), List.of())));
    }

    @Test
    @DisplayName("suprimir borra el caracter bajo el cursor")
    void suprimir() {
        // "abc", inicio, suprimir -> "bc"
        assertEquals(List.of("bc"),
                lineas(teclado("abc\u001b[H\u001b[3~\r", List.of(), List.of())));
    }

    @Test
    @DisplayName("retroceso borra el ultimo caracter")
    void retroceso() {
        assertEquals(List.of("ab"), lineas(teclado("abc\u007f\r", List.of(), List.of())));
    }

    @Test
    @DisplayName("Ctrl-C tira la linea y no la entrega")
    void controlC() {
        // Se escribe algo, se corta y se escribe de nuevo: solo llega lo segundo.
        assertEquals(List.of("bueno"),
                lineas(teclado("malo\u0003bueno\r", List.of(), List.of())));
    }

    @Test
    @DisplayName("Ctrl-D cierra la entrada y no entrega la linea a medias")
    void controlD() {
        TtyKeys keys = teclado("a medias\u0004", List.of(), List.of());
        assertTrue(lineas(keys).isEmpty(), "una linea sin enter no llega");
        assertFalse(keys.hasNext(), "tras Ctrl-D la entrada se cierra");
    }

    @Test
    @DisplayName("Ctrl-U borra la linea entera")
    void controlU() {
        assertEquals(List.of("otra"),
                lineas(teclado("borrame\u0015otra\r", List.of(), List.of())));
    }

    @Test
    @DisplayName("las letras acentuadas llegan enteras")
    void utf8() {
        // La eñe ocupa dos bytes: si se descodificara byte a byte saldria basura.
        assertEquals(List.of("mañana"), lineas(teclado("mañana\r", List.of(), List.of())));
        assertEquals(List.of("canción"), lineas(teclado("canción\r", List.of(), List.of())));
    }

    @Test
    @DisplayName("el tabulador completa cuando solo hay una orden posible")
    void tabulador() {
        assertEquals(List.of("help "),
                lineas(teclado("hel\t\r", List.of(), List.of("help", "head", "ls"))));
    }

    @Test
    @DisplayName("el tabulador alarga hasta donde coinciden las ordenes")
    void tabuladorComun() {
        assertEquals(List.of("he"),
                lineas(teclado("h\t\r", List.of(), List.of("help", "head"))));
    }

    @Test
    @DisplayName("el tabulador ambiguo ensena las ordenes que caben")
    void tabuladorAmbiguo() {
        TtyKeys keys = teclado("he\t\r", List.of(), List.of("help", "head", "ls"));
        assertEquals(List.of("he"), lineas(keys));

        String pintado = salida.toString(StandardCharsets.UTF_8);
        assertTrue(pintado.contains("head  help"),
                "cuando no puede decidir tiene que decir por donde seguir: " + pintado);
    }

    @Test
    @DisplayName("el tabulador sin coincidencias no toca la linea")
    void tabuladorSinNada() {
        assertEquals(List.of("zz"), lineas(teclado("zz\t\r", List.of(), List.of("help"))));
    }

    @Test
    @DisplayName("el aviso se pinta cuando lo pide el nucleo y no antes")
    void aviso() {
        TtyKeys keys = teclado("", List.of(), List.of());
        assertFalse(salida.toString(StandardCharsets.UTF_8).contains("pos:"),
                "el lector no inventa el aviso: lo da el nucleo");

        keys.prompt("pos:/> ");
        assertTrue(salida.toString(StandardCharsets.UTF_8).contains("pos:/>"),
                "y cuando lo da, se pinta");

        // Repetir el mismo aviso no lo vuelve a pintar: el nucleo pasa por aqui
        // cada dos tics y seria un parpadeo.
        int antes = salida.toString(StandardCharsets.UTF_8).length();
        keys.prompt("pos:/> ");
        assertEquals(antes, salida.toString(StandardCharsets.UTF_8).length(),
                "el mismo aviso no se repinta");

        lineas(keys);
    }

    @Test
    @DisplayName("cambiar de directorio repinta el aviso nuevo")
    void avisoNuevo() {
        TtyKeys keys = teclado("", List.of(), List.of());
        keys.prompt("pos:/> ");
        keys.prompt("pos:/home> ");
        assertTrue(salida.toString(StandardCharsets.UTF_8).contains("pos:/home> "),
                "el aviso nuevo tiene que salir");
        lineas(keys);
    }

    @Test
    @DisplayName("las secuencias que no se usan se descartan sin ensuciar la linea")
    void secuenciasRaras() {
        // AvPag (ESC [ 6 ~) y Alt+x no significan nada aqui: no deben aparecer.
        assertEquals(List.of("ok"),
                lineas(teclado("ok\u001b[6~\u001bx\r", List.of(), List.of())));
    }

    @Test
    @DisplayName("el teclado dice que pinta su propio aviso")
    void pintaSuAviso() {
        TtyKeys keys = teclado("", List.of(), List.of());
        assertTrue(keys.drawsPrompt(), "sin esto la terminal pintaria dos avisos");
        lineas(keys);
    }

    @Test
    @DisplayName("varias lineas seguidas salen en orden")
    void variasLineas() {
        assertEquals(List.of("uno", "dos", "tres"),
                lineas(teclado("uno\rdos\rtres\r", List.of(), List.of())));
    }

    @Test
    @DisplayName("el historial crece solo cuando el shell apunta la linea")
    void historialVivoEnElTeclado() {
        List<String> historial = new ArrayList<>();
        historial.add("primera");
        TtyKeys keys = teclado("\u001b[A\r", historial, List.of());
        assertEquals(List.of("primera"), lineas(keys));
    }

    // ------------------------------------------------------------------
    // Lineas mas largas que el terminal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("una linea corta cabe en una fila y el cursor queda tras lo escrito")
    void colocacionCorta() {
        // Aviso de 7 y cinco letras: 12 caracteres en 80 columnas.
        assertEquals(new TtyKeys.Colocacion(false, 0, 12, 0, 1, 0, 12),
                TtyKeys.colocar(7, 5, 5, 80));
    }

    @Test
    @DisplayName("una linea que acaba justo en el borde pide un salto explicito")
    void colocacionEnElBorde() {
        // 7 + 73 = 80. El terminal no baja solo: se queda esperando el caracter
        // siguiente, y sin bajarlo a mano las cuentas de filas salen mal.
        assertEquals(new TtyKeys.Colocacion(true, 0, 0, 1, 1, 1, 0),
                TtyKeys.colocar(7, 73, 73, 80));
    }

    @Test
    @DisplayName("una linea que pasa del borde ocupa dos filas")
    void colocacionPartida() {
        // 95 caracteres en 80 columnas: 80 en la primera fila y 15 en la segunda.
        assertEquals(new TtyKeys.Colocacion(false, 0, 15, 1, 2, 1, 15),
                TtyKeys.colocar(7, 88, 88, 80));
    }

    @Test
    @DisplayName("con el cursor a media linea partida hay que subir una fila")
    void colocacionCursorEnMedio() {
        // El texto acaba en la fila 1 y el cursor esta en la 0: una fila arriba.
        assertEquals(new TtyKeys.Colocacion(false, 1, 12, 0, 2, 1, 15),
                TtyKeys.colocar(7, 88, 5, 80));
    }

    @Test
    @DisplayName("la linea vacia deja el cursor detras del aviso")
    void colocacionVacia() {
        assertEquals(new TtyKeys.Colocacion(false, 0, 7, 0, 1, 0, 7),
                TtyKeys.colocar(7, 0, 0, 80));
    }

    @Test
    @DisplayName("al repintar una linea partida se sube antes de borrar")
    void repintadoSubeDeFila() {
        // Diez columnas y once letras: la segunda fila ya tiene una letra, asi que
        // el repintado tiene que subir, borrar y volver a escribir.
        TtyKeys keys = teclado("abcdefghijkl", List.of(), List.of(), 10);
        // Se espera a que el lector acabe: lo pintado se mira despues, no antes.
        assertEquals(List.of(), lineas(keys));
        String pintado = salida.toString(StandardCharsets.UTF_8);
        assertTrue(pintado.contains("\u001b[1A\u001b[Jabcdefghijk"),
                "sube una fila, borra y repinta la linea entera: " + pintado);
    }

    @Test
    @DisplayName("pulsar enter con una linea partida la cierra por el final")
    void enterEnLineaPartida() {
        // Con el cursor en la fila 1, columna 2, cerrar la linea es irse a esa
        // columna y bajar: no vale un salto desde donde este el cursor.
        assertEquals(List.of("abcdefghijkl"),
                lineas(teclado("abcdefghijkl\r", List.of(), List.of(), 10)));
        String pintado = salida.toString(StandardCharsets.UTF_8);
        assertTrue(pintado.contains("\r\u001b[2C\r\n"),
                "el enter se pinta al final de lo escrito: " + pintado);
    }

    // ------------------------------------------------------------------
    // Ctrl-Z
    // ------------------------------------------------------------------

    /** Un enganche de mentira: apunta si le pidieron parar y que contestaba. */
    private static final class SenalFalsa implements SenalDeTeclado {
        private final boolean hay;
        private int paradas;

        SenalFalsa(boolean hay) {
            this.hay = hay;
        }

        @Override
        public boolean hayPrimerPlano() {
            return hay;
        }

        @Override
        public void parar() {
            paradas++;
        }
    }

    private TtyKeys tecladoConSenal(String entrada, SenalDeTeclado senal) {
        byte[] bytes = entrada.getBytes(StandardCharsets.UTF_8);
        TtyKeys keys = new TtyKeys(new ByteArrayInputStream(bytes), out, () -> 0L,
                List.of(), List.of(), false, 80, senal);
        keys.start();
        return keys;
    }

    @Test
    @DisplayName("Ctrl-Z avisa al sistema y no ensucia la linea")
    void ctrlZAvisa() {
        SenalFalsa senal = new SenalFalsa(true);
        // Se escribe una orden, se pulsa Ctrl-Z y se sigue escribiendo: la tecla
        // no escribe nada, y lo que venga detras sigue su curso.
        assertEquals(List.of("echo fin"),
                lineas(tecladoConSenal("echo fin\u001a\r", senal)));
        assertEquals(1, senal.paradas, "tiene que pedir la parada una vez");
        assertTrue(salida.toString(StandardCharsets.UTF_8).contains("^Z"),
                "el eco de Ctrl-Z tiene que verse: " + salida.toString(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("Ctrl-Z sin primer plano no toca lo que se esta escribiendo")
    void ctrlZSinPrimerPlano() {
        SenalFalsa senal = new SenalFalsa(false);
        // La tecla cae entre "echo" y " fin": si se colara como caracter o
        // borrara la linea, saldria otra cosa.
        assertEquals(List.of("echo fin"),
                lineas(tecladoConSenal("echo\u001a fin\r", senal)));
        assertEquals(0, senal.paradas, "no hay a quien parar: no se pide nada");
        assertFalse(salida.toString(StandardCharsets.UTF_8).contains("^Z"),
                "tampoco se pinta el eco de una tecla que no hace nada");
    }

    @Test
    @DisplayName("sin enganche, Ctrl-Z no existe")
    void ctrlZSinEnganche() {
        // Un teclado montado sin sistema detras (guiones y pruebas): la tecla se
        // descarta sin romper la linea.
        assertEquals(List.of("ab"), lineas(teclado("a\u001ab\r", List.of(), List.of())));
    }

    @Test
    @DisplayName("un ancho que no existe no se acepta")
    void anchoImposible() {
        assertThrows(IllegalArgumentException.class, () -> TtyKeys.colocar(0, 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> TtyKeys.colocar(5, -1, 0, 80));
        assertThrows(IllegalArgumentException.class, () -> TtyKeys.colocar(5, 3, 9, 80));
    }
}
