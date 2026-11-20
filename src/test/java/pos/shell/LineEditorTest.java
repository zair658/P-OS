package pos.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pruebas del editor de linea.
 *
 * No hace falta terminal: se le dan teclas y se mira el texto. Es justo la
 * ventaja de haber separado el editor del lector de bytes.
 */
class LineEditorTest {

    private final List<String> history = new ArrayList<>();
    private final LineEditor editor = new LineEditor(history);

    /** Mete una cadena como si se hubiera tecleado. */
    private void type(String text) {
        text.codePoints().forEach(cp -> editor.feed(Key.ch(cp)));
    }

    private void press(Key.Kind kind) {
        editor.feed(Key.of(kind));
    }

    private LineEditor.Result enter() {
        return editor.feed(Key.of(Key.Kind.ENTER));
    }

    @Test
    @DisplayName("lo tecleado se envia tal cual y la linea queda limpia")
    void enviarLoEscrito() {
        type("ls -l");
        assertEquals("ls -l", editor.text());

        LineEditor.Result r = enter();
        assertEquals(LineEditor.Action.SUBMIT, r.action());
        assertEquals("ls -l", r.text());
        assertEquals("", editor.text());
        assertEquals(0, editor.cursor());
    }

    @Test
    @DisplayName("retroceso borra el caracter de antes del cursor")
    void retroceso() {
        type("abc");
        press(Key.Kind.BACKSPACE);
        assertEquals("ab", editor.text());

        press(Key.Kind.BACKSPACE);
        press(Key.Kind.BACKSPACE);
        assertEquals("", editor.text());

        // En linea vacia no rompe ni deja el cursor negativo.
        press(Key.Kind.BACKSPACE);
        assertEquals("", editor.text());
        assertEquals(0, editor.cursor());
    }

    @Test
    @DisplayName("las flechas mueven el cursor y se escribe en medio")
    void escribirEnMedio() {
        type("ac");
        press(Key.Kind.LEFT);
        assertEquals(1, editor.cursor());

        type("b");
        assertEquals("abc", editor.text());
        assertEquals(2, editor.cursor());

        press(Key.Kind.RIGHT);
        press(Key.Kind.RIGHT);
        // El cursor no pasa del final.
        assertEquals(3, editor.cursor());
    }

    @Test
    @DisplayName("inicio y fin llevan el cursor a los extremos")
    void inicioYFin() {
        type("hola");
        press(Key.Kind.HOME);
        assertEquals(0, editor.cursor());
        press(Key.Kind.END);
        assertEquals(4, editor.cursor());
    }

    @Test
    @DisplayName("suprimir borra el caracter bajo el cursor")
    void suprimir() {
        type("abc");
        press(Key.Kind.HOME);
        press(Key.Kind.DELETE);
        assertEquals("bc", editor.text());
        assertEquals(0, editor.cursor());
    }

    @Test
    @DisplayName("Ctrl-C tira la linea y no la envia")
    void controlC() {
        type("esto no va");
        LineEditor.Result r = editor.feed(Key.of(Key.Kind.CANCEL));

        assertEquals(LineEditor.Action.CANCEL, r.action());
        assertEquals("", r.text());
        assertEquals("", editor.text());
        assertEquals(0, editor.cursor());
    }

    @Test
    @DisplayName("Ctrl-D en linea vacia acaba; con texto borra hacia delante")
    void controlD() {
        LineEditor.Result r = editor.feed(Key.of(Key.Kind.EOF));
        assertEquals(LineEditor.Action.EOF, r.action());

        type("abc");
        press(Key.Kind.HOME);
        press(Key.Kind.EOF);
        assertEquals("bc", editor.text());
    }

    @Test
    @DisplayName("subir recupera la anterior y bajar vuelve a lo escrito")
    void historial() {
        history.add("pwd");
        history.add("ls -l");

        type("medi");
        press(Key.Kind.UP);
        assertEquals("ls -l", editor.text());

        press(Key.Kind.UP);
        assertEquals("pwd", editor.text());

        press(Key.Kind.DOWN);
        assertEquals("ls -l", editor.text());

        press(Key.Kind.DOWN);
        // Vuelve al borrador que se estaba escribiendo, no a una linea vacia.
        assertEquals("medi", editor.text());
    }

    @Test
    @DisplayName("subir en la mas vieja no sale del historial")
    void topeDelHistorial() {
        history.add("uno");
        press(Key.Kind.UP);
        assertEquals("uno", editor.text());

        press(Key.Kind.UP);
        assertEquals("uno", editor.text());

        press(Key.Kind.DOWN);
        assertEquals("", editor.text());
    }

    @Test
    @DisplayName("el historial es la lista viva: si crece, se ve lo nuevo")
    void historialVivo() {
        history.add("uno");
        press(Key.Kind.UP);
        assertEquals("uno", editor.text());
        press(Key.Kind.DOWN);

        // El shell anade la linea despues de ejecutarla.
        history.add("dos");
        press(Key.Kind.UP);
        assertEquals("dos", editor.text());
    }

    @Test
    @DisplayName("con historial vacio las flechas verticales no hacen nada")
    void historialVacio() {
        type("algo");
        press(Key.Kind.UP);
        assertEquals("algo", editor.text());
        press(Key.Kind.DOWN);
        assertEquals("algo", editor.text());
    }

    @Test
    @DisplayName("Ctrl-U borra todo y Ctrl-K borra desde el cursor")
    void borrados() {
        type("hola mundo");
        press(Key.Kind.HOME);
        press(Key.Kind.RIGHT);
        press(Key.Kind.RIGHT);
        press(Key.Kind.KILL_END);
        assertEquals("ho", editor.text());

        press(Key.Kind.KILL_LINE);
        assertEquals("", editor.text());
        assertEquals(0, editor.cursor());
    }

    @Test
    @DisplayName("tab completa la palabra del cursor")
    void tabulador() {
        LineEditor conComandos = new LineEditor(history,
                prefix -> "hel".startsWith(prefix) || prefix.isEmpty() ? "help" : null);

        "hel".codePoints().forEach(cp -> conComandos.feed(Key.ch(cp)));
        conComandos.feed(Key.of(Key.Kind.TAB));

        assertEquals("help", conComandos.text());
        assertEquals(4, conComandos.cursor());
    }

    @Test
    @DisplayName("tab sin coincidencias deja la linea como estaba")
    void tabuladorSinCoincidencia() {
        LineEditor sinNada = new LineEditor(history, prefix -> null);
        "zz".codePoints().forEach(cp -> sinNada.feed(Key.ch(cp)));

        sinNada.feed(Key.of(Key.Kind.TAB));
        assertEquals("zz", sinNada.text());
        assertEquals(2, sinNada.cursor());
    }

    @Test
    @DisplayName("tab completa solo la palabra que toca, no la linea entera")
    void tabuladorSoloLaPalabra() {
        LineEditor editor2 = new LineEditor(history,
                prefix -> "/etc".startsWith(prefix) ? "/etc" : null);

        "cat /et".codePoints().forEach(cp -> editor2.feed(Key.ch(cp)));
        editor2.feed(Key.of(Key.Kind.TAB));

        assertEquals("cat /etc", editor2.text());
    }

    @Test
    @DisplayName("enter no arrastra estado del historial a la linea siguiente")
    void enterLimpiaElHistorial() {
        history.add("anterior");
        press(Key.Kind.UP);
        assertEquals("anterior", editor.text());
        enter();

        type("nueva");
        assertEquals("nueva", editor.text());

        // Subir ahora debe traer lo ultimo del historial, no quedar pegado.
        press(Key.Kind.UP);
        assertEquals("anterior", editor.text());
    }

    @Test
    @DisplayName("la cancelacion no deja basura en el historial")
    void cancelarNoTocaElHistorial() {
        history.add("uno");
        type("basura");
        press(Key.Kind.CANCEL);

        assertEquals(1, history.size());
        press(Key.Kind.UP);
        assertEquals("uno", editor.text());
    }
}
