package pos.shell;

import java.util.List;

/**
 * Atajo para declarar una orden con una funcion en vez de con una clase.
 *
 * Casi todas las ordenes son diez lineas de codigo sin estado; escribir una
 * clase por cada una llenaria el paquete de ficheros y no aportaria nada. Las que
 * de verdad tienen estructura (la tabla de ordenes, el analizador) son clases.
 */
public final class Commands {

    private Commands() {
        // clase de utilidad
    }

    /** El cuerpo de una orden. */
    @FunctionalInterface
    public interface Body {
        void run(Shell shell, List<String> args, Input in, Output out);
    }

    public static Command of(String name, String usage, String summary, Body body) {
        return new SimpleCommand(name, usage, summary, body);
    }

    private record SimpleCommand(String name, String usage, String summary, Body body)
            implements Command {

        @Override
        public void run(Shell shell, List<String> args, Input in, Output out) {
            body.run(shell, args, in, out);
        }

        @Override
        public String toString() {
            return name + " " + usage;
        }
    }
}
