package pos.proc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import pos.base.PosFault;

/**
 * Cargador de programas en texto.
 *
 * Dos pasadas: la primera cuenta las instrucciones y anota en que indice cae
 * cada etiqueta; la segunda construye las instrucciones resolviendo los saltos a
 * indices absolutos. Asi el interprete nunca busca etiquetas en tiempo de
 * ejecucion: un salto es un entero.
 *
 * Sintaxis, una instruccion por linea:
 * <pre>
 *   ; comentario
 *   etiqueta:
 *   SET 1
 *   ADD 2
 *   CMP 10
 *   JZ fin
 *   JMP 0
 *   fin: EXIT
 * </pre>
 * El ';' y el '#' abren comentario en cualquier punto de la linea.
 */
public final class Assembler {

    private Assembler() {
        // clase de utilidad
    }

    public static Program load(String name, String source) {
        List<String> lines = source.lines().toList();

        // Primera pasada: indice de cada etiqueta.
        Map<String, Integer> labels = new HashMap<>();
        int index = 0;
        for (int number = 0; number < lines.size(); number++) {
            String line = strip(lines.get(number));
            if (line.isEmpty()) {
                continue;
            }
            String rest = line;
            int colon = line.indexOf(':');
            if (colon >= 0) {
                String label = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
                if (label.isEmpty()) {
                    throw new PosFault("'" + name + "' linea " + (number + 1)
                            + ": etiqueta vacia");
                }
                if (labels.put(label, index) != null) {
                    throw new PosFault("'" + name + "' linea " + (number + 1)
                            + ": etiqueta repetida '" + label + "'");
                }
                rest = line.substring(colon + 1).trim();
            }
            if (!rest.isEmpty()) {
                index++;
            }
        }

        // Segunda pasada: instrucciones con los saltos ya resueltos.
        List<Instruction> code = new ArrayList<>();
        for (int number = 0; number < lines.size(); number++) {
            String line = strip(lines.get(number));
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.indexOf(':');
            String rest = colon >= 0 ? line.substring(colon + 1).trim() : line;
            if (rest.isEmpty()) {
                continue;
            }
            code.add(parse(name, lines.get(number), rest, labels, code.size()));
        }

        if (code.isEmpty()) {
            throw new PosFault("'" + name + "' no contiene instrucciones utiles");
        }
        return new Program(name, code);
    }

    private static Instruction parse(String program, String rawLine, String line,
            Map<String, Integer> labels, int index) {
        String[] parts = line.split("\\s+");
        String mnemonic = parts[0].toUpperCase(Locale.ROOT);
        String argument = parts.length > 1 ? parts[1] : null;

        if (parts.length > 2) {
            throw error(program, rawLine, "sobran argumentos en '" + line + "'");
        }

        return switch (mnemonic) {
            case "NOP", "YIELD", "EXIT" -> withoutArgument(program, rawLine, mnemonic, argument);
            case "SET" -> new Instruction.Set(number(program, rawLine, argument));
            case "LOAD" -> new Instruction.Load(number(program, rawLine, argument));
            case "STORE" -> new Instruction.Store(number(program, rawLine, argument));
            case "ADD" -> new Instruction.Add(number(program, rawLine, argument));
            case "SUB" -> new Instruction.Sub(number(program, rawLine, argument));
            case "CMP" -> new Instruction.Cmp(number(program, rawLine, argument));
            case "SLEEP" -> new Instruction.Sleep(positive(program, rawLine, argument));
            case "JMP" -> new Instruction.Jump(target(program, rawLine, argument, labels));
            case "JZ" -> new Instruction.JumpIfEqual(target(program, rawLine, argument, labels));
            case "JNZ" -> new Instruction.JumpIfNotEqual(target(program, rawLine, argument, labels));
            case "SPAWN" -> new Instruction.Spawn(target(program, rawLine, argument, labels));
            default -> throw error(program, rawLine, "instruccion desconocida '" + mnemonic + "'");
        };
    }

    private static Instruction withoutArgument(String program, String line, String mnemonic,
            String argument) {
        if (argument != null) {
            throw error(program, line, "'" + mnemonic + "' no lleva argumento");
        }
        return switch (mnemonic) {
            case "NOP" -> new Instruction.Nop();
            case "YIELD" -> new Instruction.Yield();
            case "EXIT" -> new Instruction.Exit();
            default -> throw new PosFault("caso no previsto: " + mnemonic);
        };
    }

    private static int number(String program, String line, String argument) {
        if (argument == null) {
            throw error(program, line, "falta el numero");
        }
        try {
            return Integer.parseInt(argument);
        } catch (NumberFormatException e) {
            throw error(program, line, "'" + argument + "' no es un numero entero");
        }
    }

    private static int positive(String program, String line, String argument) {
        int value = number(program, line, argument);
        if (value <= 0) {
            throw error(program, line, "SLEEP necesita un numero mayor que cero");
        }
        return value;
    }

    private static int target(String program, String line, String argument,
            Map<String, Integer> labels) {
        if (argument == null) {
            throw error(program, line, "falta la etiqueta de destino");
        }
        Integer destination = labels.get(argument.toLowerCase(Locale.ROOT));
        if (destination == null) {
            throw error(program, line, "etiqueta desconocida '" + argument + "'");
        }
        return destination;
    }

    private static PosFault error(String program, String line, String message) {
        return new PosFault("'" + program + "': " + message + " (en '" + line.trim() + "')");
    }

    private static String strip(String line) {
        String result = line;
        int semicolon = result.indexOf(';');
        if (semicolon >= 0) {
            result = result.substring(0, semicolon);
        }
        int hash = result.indexOf('#');
        if (hash >= 0) {
            result = result.substring(0, hash);
        }
        return result.trim();
    }
}
