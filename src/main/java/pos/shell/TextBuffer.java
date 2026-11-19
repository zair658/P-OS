package pos.shell;

/** Buffer de salida: lo que se acumula en una tuberia o en una redireccion. */
public final class TextBuffer implements Output {

    private final StringBuilder text = new StringBuilder();

    @Override
    public void print(String chunk) {
        if (chunk == null) {
            throw new IllegalArgumentException("no se escribe nulo");
        }
        text.append(chunk);
    }

    /** Todo lo escrito hasta ahora. */
    public String text() {
        return text.toString();
    }

    public boolean empty() {
        return text.length() == 0;
    }

    public int size() {
        return text.length();
    }

    public void clear() {
        text.setLength(0);
    }

    @Override
    public String toString() {
        return text.toString();
    }
}
