package pos.shell;

import java.util.ArrayList;
import java.util.List;

import pos.base.PosFault;

/**
 * Convierte una linea de teclado en algo que la terminal pueda ejecutar.
 *
 * La linea se parte en palabras y operadores. Las comillas dobles agrupan texto
 * con espacios y la barra invertida escapa el caracter siguiente dentro de las
 * comillas; fuera de ellas no hay escapes, para que una ruta de Windows escrita
 * por error no se coma la mitad de la orden.
 *
 * Los operadores se reconocen solo fuera de las comillas: {@code >} y {@code >>}
 * redirigen la salida, {@code <} la entrada y {@code |} encadena ordenes. El
 * resultado son etapas ya resueltas: la terminal no tiene que volver a mirar la
 * linea original para saber a donde va cada cosa.
 *
 * El {@code &} del final marca la ultima etapa como trabajo en segundo plano. Va
 * solo al final de la linea, y no entre dos ordenes como el punto y coma de otras
 * cascaras: aqui no hay separador de ordenes, y aceptar {@code a & b} obligaria a
 * inventarse uno que despues nadie usa.
 *
 * El analizador no sabe que ordenes existen. Si la linea esta bien formada pero
 * la orden no existe, eso se descubre al ejecutarla, no aqui.
 */
public final class LineParser {

    private LineParser() {
        // clase de utilidad
    }

    /** Una orden dentro de una tuberia, con sus redirecciones. */
    public record Stage(List<String> words, String stdinFile, String stdoutFile, boolean append,
            boolean background) {

        public Stage {
            words = List.copyOf(words);
        }

        /** Una etapa en primer plano, que es como llega todo lo que no lleva {@code &}. */
        public Stage(List<String> words, String stdinFile, String stdoutFile, boolean append) {
            this(words, stdinFile, stdoutFile, append, false);
        }

        /** Nombre de la orden: la primera palabra. */
        public String name() {
            return words.isEmpty() ? "" : words.get(0);
        }

        /** Argumentos: todo menos la primera palabra. */
        public List<String> args() {
            return words.isEmpty() ? List.of() : words.subList(1, words.size());
        }
    }

    /** La linea completa: una o mas etapas encadenadas. */
    public record ParsedLine(List<Stage> stages) {

        public ParsedLine {
            stages = List.copyOf(stages);
        }

        public boolean empty() {
            return stages.isEmpty();
        }
    }

    private enum Kind {
        WORD, PIPE, OUT, APPEND, IN, AMP
    }

    private record Token(Kind kind, String text) {
    }

    /**
     * Analiza la linea.
     *
     * @throws PosFault si falta cerrar unas comillas, si un operador se queda sin
     *     fichero o si hay una etapa vacia entre dos tuberias
     */
    public static ParsedLine parse(String raw) {
        if (raw == null) {
            throw new PosFault("linea vacia");
        }
        List<Token> tokens = tokenize(raw.trim());
        if (tokens.isEmpty()) {
            return new ParsedLine(List.of());
        }
        return build(tokens);
    }

    private static List<Token> tokenize(String raw) {
        List<Token> tokens = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        boolean quoted = false;

        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (quoted) {
                if (c == '"') {
                    quoted = false;
                } else if (c == '\\' && i + 1 < raw.length()) {
                    word.append(raw.charAt(++i));
                } else {
                    word.append(c);
                }
                i++;
                continue;
            }
            if (c == '"') {
                quoted = true;
                inWord = true;
                i++;
            } else if (c == ' ' || c == '\t') {
                if (inWord) {
                    tokens.add(new Token(Kind.WORD, word.toString()));
                    word.setLength(0);
                    inWord = false;
                }
                i++;
            } else if (c == '|') {
                flush(tokens, word, inWord);
                inWord = false;
                tokens.add(new Token(Kind.PIPE, "|"));
                i++;
            } else if (c == '>') {
                flush(tokens, word, inWord);
                inWord = false;
                if (i + 1 < raw.length() && raw.charAt(i + 1) == '>') {
                    tokens.add(new Token(Kind.APPEND, ">>"));
                    i += 2;
                } else {
                    tokens.add(new Token(Kind.OUT, ">"));
                    i++;
                }
            } else if (c == '&') {
                flush(tokens, word, inWord);
                inWord = false;
                tokens.add(new Token(Kind.AMP, "&"));
                i++;
            } else if (c == '<') {
                flush(tokens, word, inWord);
                inWord = false;
                tokens.add(new Token(Kind.IN, "<"));
                i++;
            } else {
                word.append(c);
                inWord = true;
                i++;
            }
        }
        if (quoted) {
            throw new PosFault("falta cerrar las comillas");
        }
        flush(tokens, word, inWord);
        return tokens;
    }

    private static void flush(List<Token> tokens, StringBuilder word, boolean inWord) {
        if (inWord) {
            tokens.add(new Token(Kind.WORD, word.toString()));
            word.setLength(0);
        }
    }

    private static ParsedLine build(List<Token> tokens) {
        List<Stage> stages = new ArrayList<>();
        List<String> words = new ArrayList<>();
        String stdinFile = null;
        String stdoutFile = null;
        boolean append = false;

        for (int i = 0; i < tokens.size(); i++) {
            Token token = tokens.get(i);
            if (token.kind() == Kind.WORD) {
                words.add(token.text());
                continue;
            }
            if (token.kind() == Kind.PIPE) {
                if (words.isEmpty()) {
                    throw new PosFault("orden vacia entre tuberias");
                }
                stages.add(new Stage(words, stdinFile, stdoutFile, append));
                words = new ArrayList<>();
                stdinFile = null;
                stdoutFile = null;
                append = false;
                continue;
            }
            if (token.kind() == Kind.AMP) {
                if (words.isEmpty()) {
                    throw new PosFault("'&' sin orden delante");
                }
                if (i != tokens.size() - 1) {
                    throw new PosFault("'&' solo puede ir al final de la linea");
                }
                stages.add(new Stage(words, stdinFile, stdoutFile, append, true));
                return new ParsedLine(stages);
            }
            if (i + 1 >= tokens.size() || tokens.get(i + 1).kind() != Kind.WORD) {
                throw new PosFault("falta el fichero detras de '" + token.text() + "'");
            }
            String file = tokens.get(++i).text();
            if (token.kind() == Kind.IN) {
                if (stdinFile != null) {
                    throw new PosFault("entrada redirigida dos veces");
                }
                stdinFile = file;
            } else {
                if (stdoutFile != null) {
                    throw new PosFault("salida redirigida dos veces");
                }
                stdoutFile = file;
                append = token.kind() == Kind.APPEND;
            }
        }
        if (words.isEmpty()) {
            throw new PosFault("orden vacia entre tuberias");
        }
        stages.add(new Stage(words, stdinFile, stdoutFile, append));
        return new ParsedLine(stages);
    }
}
