package pos.base;

/**
 * Tramo contiguo de unidades dentro de un espacio de direcciones o de memoria
 * fisica. Se usa tanto para representar un hueco libre como una region ocupada.
 *
 * Es inmutable. El intervalo es semiabierto: empieza en {@code start} y termina
 * en {@code end()} sin incluir esta ultima.
 */
public record FreeSpan(int start, int length) {

    public FreeSpan {
        if (start < 0) {
            throw new IllegalArgumentException("el inicio del tramo no puede ser negativo: " + start);
        }
        if (length <= 0) {
            throw new IllegalArgumentException("la longitud del tramo debe ser positiva: " + length);
        }
    }

    /** Fin exclusivo del tramo. */
    public int end() {
        return start + length;
    }

    /** Comprueba si una direccion cae dentro del tramo. */
    public boolean contains(int address) {
        return address >= start && address < end();
    }

    /** Comprueba si otro tramo se solapa con este. */
    public boolean overlaps(FreeSpan other) {
        return start < other.end() && other.start < end();
    }

    /** Tramo resultante al ocupar las primeras {@code units} unidades de este. */
    public FreeSpan takeFront(int units) {
        if (units <= 0 || units > length) {
            throw new IllegalArgumentException("no se pueden tomar " + units + " unidades de un tramo de " + length);
        }
        return new FreeSpan(start, units);
    }

    /** Resto que queda al ocupar las primeras {@code units} unidades de este. */
    public FreeSpan remainingAfter(int units) {
        if (units <= 0 || units > length) {
            throw new IllegalArgumentException("no se pueden ocupar " + units + " unidades de un tramo de " + length);
        }
        if (units == length) {
            return null;
        }
        return new FreeSpan(start + units, length - units);
    }

    /** Representacion compacta para trazas: {@code [inicio, fin)}. */
    public String label() {
        return "[" + start + ", " + end() + ")";
    }
}
