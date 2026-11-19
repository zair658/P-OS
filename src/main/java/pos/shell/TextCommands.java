package pos.shell;

import java.util.ArrayList;
import java.util.List;

import pos.base.PosFault;

/**
 * Ordenes que trabajan con texto.
 *
 * Todas leen de la entrada estandar o de un fichero, nunca de los dos a la vez, y
 * escriben en la salida. esa es la unica regla que cumplen para poder encadenarse
 * con tuberias: si una orden no sabe de donde lee, no puede participar en un
 * tubo.
 *
 * Los recuentos de {@code wc} son de caracteres, no de bytes, porque la lectura
 * devuelve texto ya descodificado. En este sistema todo es ASCII, asi que da
 * igual; se documenta para que nadie lo confunda con un bug el dia que entren
 * acentos.
 */
public final class TextCommands {

    private TextCommands() {
        // clase de utilidad
    }

    static void register(CommandTable table) {
        table.register(Commands.of("echo", "[-n] [texto...]", "escribe lo que le das",
                TextCommands::echo));
        table.register(Commands.of("head", "[-n N] [fichero]", "primeras lineas de la entrada",
                TextCommands::head));
        table.register(Commands.of("tail", "[-n N] [fichero]", "ultimas lineas de la entrada",
                TextCommands::tail));
        table.register(Commands.of("wc", "[-l|-w|-c] [fichero]", "cuenta lineas, palabras y caracteres",
                TextCommands::wc));
        table.register(Commands.of("grep", "[-v] [-i] patron [fichero]", "busca lineas que encajan",
                TextCommands::grep));
    }

    private static void echo(Shell shell, List<String> args, Input in, Output out) {
        boolean newline = true;
        int from = 0;
        if (!args.isEmpty() && args.get(0).equals("-n")) {
            newline = false;
            from = 1;
        }
        String text = String.join(" ", args.subList(from, args.size()));
        if (newline) {
            out.println(text);
        } else {
            out.print(text);
        }
    }

    private static void head(Shell shell, List<String> args, Input in, Output out) {
        int count = countArg(args, 10);
        List<String> lines = lines(shell, withoutCount(args), in);
        for (int i = 0; i < Math.min(count, lines.size()); i++) {
            out.println(lines.get(i));
        }
    }

    private static void tail(Shell shell, List<String> args, Input in, Output out) {
        int count = countArg(args, 10);
        List<String> lines = lines(shell, withoutCount(args), in);
        int from = Math.max(0, lines.size() - count);
        for (int i = from; i < lines.size(); i++) {
            out.println(lines.get(i));
        }
    }

    private static void wc(Shell shell, List<String> args, Input in, Output out) {
        String mode = "todo";
        List<String> rest = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("-l") || arg.equals("-w") || arg.equals("-c")) {
                mode = arg.substring(1);
            } else {
                rest.add(arg);
            }
        }
        String text = text(shell, rest, in);
        String name = rest.isEmpty() ? "" : " " + rest.get(0);
        switch (mode) {
            case "l" -> out.println(lineCount(text) + name);
            case "w" -> out.println(wordCount(text) + name);
            case "c" -> out.println(text.length() + name);
            default -> out.println(lineCount(text) + " " + wordCount(text) + " " + text.length() + name);
        }
    }

    private static void grep(Shell shell, List<String> args, Input in, Output out) {
        boolean invert = false;
        boolean ignoreCase = false;
        List<String> rest = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("-v")) {
                invert = true;
            } else if (arg.equals("-i")) {
                ignoreCase = true;
            } else {
                rest.add(arg);
            }
        }
        if (rest.isEmpty()) {
            throw new PosFault("uso: grep [-v] [-i] patron [fichero]");
        }
        String pattern = rest.get(0);
        String needle = ignoreCase ? pattern.toLowerCase() : pattern;
        for (String line : lines(shell, rest.subList(1, rest.size()), in)) {
            boolean found = (ignoreCase ? line.toLowerCase() : line).contains(needle);
            if (found != invert) {
                out.println(line);
            }
        }
    }

    /** Cuenta de lineas como lo haria wc: una linea es lo que acaba en salto. */
    private static int lineCount(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                count++;
            }
        }
        return text.endsWith("\n") ? count : count + 1;
    }

    private static int wordCount(String text) {
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean space = c == ' ' || c == '\n' || c == '\t' || c == '\r';
            if (!space && !inWord) {
                count++;
            }
            inWord = !space;
        }
        return count;
    }

    /** Texto de entrada: del fichero si lo hay, de la tuberia si no. */
    private static String text(Shell shell, List<String> paths, Input in) {
        if (paths.isEmpty()) {
            if (in.empty()) {
                throw new PosFault("no hay entrada: ni fichero ni tuberia");
            }
            return in.text();
        }
        StringBuilder text = new StringBuilder();
        for (String path : paths) {
            text.append(shell.readFile(path));
        }
        return text.toString();
    }

    private static List<String> lines(Shell shell, List<String> args, Input in) {
        return new ArrayList<>(text(shell, args, in).lines().toList());
    }

    /** Quita el par {@code -n N} para que no se confunda con un fichero. */
    private static List<String> withoutCount(List<String> args) {
        List<String> rest = new ArrayList<>();
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("-n")) {
                i++;
                continue;
            }
            rest.add(args.get(i));
        }
        return rest;
    }

    /** Lee {@code -n N} y devuelve la cuenta. */
    private static int countArg(List<String> args, int fallback) {
        for (int i = 0; i < args.size(); i++) {
            if (args.get(i).equals("-n")) {
                if (i + 1 >= args.size()) {
                    throw new PosFault("falta el numero detras de -n");
                }
                try {
                    return Integer.parseInt(args.get(i + 1));
                } catch (NumberFormatException e) {
                    throw new PosFault("no es un numero: " + args.get(i + 1));
                }
            }
        }
        return fallback;
    }
}
