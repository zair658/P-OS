package pos.shell;

import java.util.List;

/**
 * Lo que una orden recibe por la entrada estandar.
 *
 * Se entrega ya leido, en texto. Es una simplificacion consciente: en un sistema
 * real la entrada es un flujo y la orden decide cuanto lee; aqui la tuberia se
 * resuelve copiando la salida de una orden a la entrada de la siguiente, lo que
 * permite encadenar ordenes sin escribir un planificador de flujos y sin
 * bloquear procesos por un tubo lleno.
 */
public record Input(String text) {

    public static final Input EMPTY = new Input("");

    public Input {
        if (text == null) {
            throw new IllegalArgumentException("la entrada no puede ser nula");
        }
    }

    public boolean empty() {
        return text.isEmpty();
    }

    /** Lineas de la entrada, sin la marca final. */
    public List<String> lines() {
        if (text.isEmpty()) {
            return List.of();
        }
        return text.lines().toList();
    }
}
