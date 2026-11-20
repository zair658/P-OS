package pos.proc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
 *   LOCK contador
 *   STORE 0
 *   UNLOCK contador
 *   SEM_INIT huecos 1
 *   SEM_WAIT huecos
 *   COND_WAIT hay_datos contador
 *   CMP 10
 *   JZ fin
 *   JMP 0
 *   fin: EXIT
 * </pre>
 * El ';' y el '#' abren comentario en cualquier punto de la linea.
 */
public final class Assembler {

    /**
     * Instrucciones que llevan dos argumentos.
     *
     * El resto solo admite uno, y se comprueba antes del switch para que el
     * mensaje de error no dependa de por donde entre la instruccion.
     */
    private static final Set<String> DOS_ARGUMENTOS = Set.of("SEM_INIT", "COND_WAIT",
            "RES_CLAIM", "RES_TAKE", "RES_GIVE");

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
        String[] parts = line.split("\\s+", 4);
        String mnemonic = parts[0].toUpperCase(Locale.ROOT);
        String argument = parts.length > 1 ? parts[1] : null;
        String second = parts.length > 2 ? parts[2] : null;

        if (parts.length > 3) {
            throw error(program, rawLine, "sobran argumentos en '" + line + "'");
        }
        if (second != null && !DOS_ARGUMENTOS.contains(mnemonic)) {
            throw error(program, rawLine, "'" + mnemonic + "' no lleva dos argumentos");
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
            case "LOCK" -> new Instruction.Lock(nombre(program, rawLine, argument, "cerrojo"));
            case "UNLOCK" -> new Instruction.Unlock(nombre(program, rawLine, argument, "cerrojo"));
            case "SEM_INIT" -> new Instruction.SemInit(
                    nombre(program, rawLine, argument, "semaforo"),
                    noNegativo(program, rawLine, second, "SEM_INIT"));
            case "SEM_WAIT" -> new Instruction.SemWait(nombre(program, rawLine, argument, "semaforo"));
            case "SEM_POST" -> new Instruction.SemPost(nombre(program, rawLine, argument, "semaforo"));
            case "COND_WAIT" -> new Instruction.CondWait(
                    nombre(program, rawLine, argument, "condicion"),
                    nombre(program, rawLine, segundo(program, rawLine, second, "COND_WAIT"),
                            "cerrojo"));
            case "COND_SIGNAL" -> new Instruction.CondSignal(
                    nombre(program, rawLine, argument, "condicion"));
            case "COND_BROADCAST" -> new Instruction.CondBroadcast(
                    nombre(program, rawLine, argument, "condicion"));
            case "RES_CLAIM" -> new Instruction.ResClaim(
                    nombre(program, rawLine, argument, "recurso"),
                    positivo(program, rawLine, second, "RES_CLAIM"));
            case "RES_TAKE" -> new Instruction.ResTake(
                    nombre(program, rawLine, argument, "recurso"),
                    positivo(program, rawLine, second, "RES_TAKE"));
            case "RES_GIVE" -> new Instruction.ResGive(
                    nombre(program, rawLine, argument, "recurso"),
                    positivo(program, rawLine, second, "RES_GIVE"));
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

    /**
     * Nombre de un recurso de sincronizacion (cerrojo, semaforo o condicion).
     *
     * Se acepta un identificador corriente y se respeta el uso de mayusculas,
     * porque el nombre es la clave de la tabla y 'contador' y 'Contador' tienen
     * que ser cosas distintas, igual que en un sistema real.
     *
     * @param que sustantivo para el mensaje de error
     */
    private static String nombre(String program, String line, String argument, String que) {
        if (argument == null) {
            throw error(program, line, "falta el nombre del " + que);
        }
        if (!argument.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw error(program, line, "'" + argument + "' no es un nombre de " + que + " valido");
        }
        return argument;
    }

    /** Segundo argumento obligatorio de una instruccion que lleva dos. */
    private static String segundo(String program, String line, String second, String mnemonic) {
        if (second == null) {
            throw error(program, line, "'" + mnemonic + "' necesita un segundo argumento");
        }
        return second;
    }

    /** Numero que puede ser cero, como el valor inicial de un semaforo. */
    private static int noNegativo(String program, String line, String argument, String mnemonic) {
        String value = segundo(program, line, argument, mnemonic);
        int number = number(program, line, value);
        if (number < 0) {
            throw error(program, line, mnemonic + " no admite un valor negativo");
        }
        return number;
    }

    private static int positive(String program, String line, String argument) {
        int value = number(program, line, argument);
        if (value <= 0) {
            throw error(program, line, "SLEEP necesita un numero mayor que cero");
        }
        return value;
    }

    /** Segundo argumento numerico que tiene que ser mayor que cero. */
    private static int positivo(String program, String line, String argument, String mnemonic) {
        String value = segundo(program, line, argument, mnemonic);
        int number = number(program, line, value);
        if (number <= 0) {
            throw error(program, line, mnemonic + " necesita un numero mayor que cero");
        }
        return number;
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
