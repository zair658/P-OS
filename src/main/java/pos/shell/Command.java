package pos.shell;

import java.util.List;

/**
 * Una orden de la terminal.
 *
 * Las ordenes no hablan con el nucleo directamente: solo ven el {@link Shell},
 * que es quien traduce a llamadas al sistema. Asi una orden se puede escribir y
 * probar sin montar un nucleo, y el dia que cambie la interfaz de llamadas solo
 * cambia la cascara.
 *
 * Una orden no devuelve nada. Lo que tenga que decir lo escribe en la salida; lo
 * que tenga que pedir, lo pide al shell; y si no puede hacer su trabajo, lanza
 * {@link pos.base.PosFault} y la terminal lo convierte en una linea de error sin
 * tumbar la sesion.
 */
public interface Command {

    /** Nombre con el que se invoca. */
    String name();

    /** Como se usa, para la ayuda. */
    String usage();

    /** Una linea explicando para que sirve. */
    String summary();

    void run(Shell shell, List<String> args, Input in, Output out);
}
