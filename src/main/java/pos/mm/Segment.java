package pos.mm;

/**
 * Un segmento declarado por el proceso: nombre, direccion logica inicial y
 * longitud.
 *
 * El nombre no es decorativo: en la tabla de traduccion y en la traza aparece
 * "codigo", "datos" o "pila", que es como se razona sobre segmentacion. Un
 * segmento sin nombre obliga a hablar de "segmento 2", y entonces la traza deja
 * de explicar nada.
 */
public record Segment(String name, int logicalBase, int length) {

    public Segment {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("todo segmento necesita un nombre");
        }
        if (logicalBase < 0) {
            throw new IllegalArgumentException("el inicio logico del segmento no puede ser negativo: " + logicalBase);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("la longitud del segmento debe ser positiva: " + length);
        }
    }

    /** Fin exclusivo del segmento en el espacio logico. */
    public int logicalEnd() {
        return logicalBase + length;
    }

    /** Comprueba si una direccion logica cae dentro del segmento. */
    public boolean contains(int address) {
        return address >= logicalBase && address < logicalEnd();
    }

    /** Desplazamiento de una direccion dentro del segmento; -1 si no cae dentro. */
    public int offsetOf(int address) {
        return contains(address) ? address - logicalBase : -1;
    }

    /** Etiqueta compacta: {@code codigo [0, 64)}. */
    public String label() {
        return name + " [" + logicalBase + ", " + logicalEnd() + ")";
    }
}
