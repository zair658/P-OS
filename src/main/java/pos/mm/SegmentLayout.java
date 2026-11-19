package pos.mm;

import java.util.ArrayList;
import java.util.List;

/**
 * Reparto del espacio logico de un proceso en segmentos.
 *
 * Los segmentos van en orden ascendente y no se solapan, pero pueden dejar
 * huecos entre ellos. Eso no es un descuido: un hueco entre segmentos es una
 * zona del espacio logico que no es direccionable, y acceder a ella tiene que
 * fallar. Es justo la clase de error que la segmentacion existe para detectar.
 *
 * El tamano logico del proceso es el final del ultimo segmento, no la suma de
 * las longitudes: si hay huecos, el proceso no puede direccionarlos.
 */
public record SegmentLayout(List<Segment> segments) {

    public SegmentLayout {
        segments = List.copyOf(segments);
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("la segmentacion necesita al menos un segmento");
        }
        int previousEnd = -1;
        for (Segment segment : segments) {
            if (segment.logicalBase() < previousEnd) {
                throw new IllegalArgumentException("los segmentos se solapan o no estan en orden: "
                        + segment.label() + " empieza antes de " + previousEnd);
            }
            previousEnd = segment.logicalEnd();
        }
    }

    /** Tamano del espacio logico: final del ultimo segmento. */
    public int totalLogicalUnits() {
        int total = 0;
        for (Segment segment : segments) {
            total = Math.max(total, segment.logicalEnd());
        }
        return total;
    }

    /**
     * Segmento que contiene una direccion logica.
     *
     * @return el segmento, o {@code null} si la direccion cae en un hueco
     */
    public Segment segmentOf(int address) {
        for (Segment segment : segments) {
            if (segment.contains(address)) {
                return segment;
            }
        }
        return null;
    }

    /**
     * Reparte un espacio logico en segmentos consecutivos del mismo tamano.
     *
     * Sirve para demostraciones y pruebas; en un caso real los segmentos tienen
     * tamanos distintos, que es precisamente la ventaja del esquema.
     */
    public static SegmentLayout even(int totalUnits, int parts) {
        if (parts <= 0) {
            throw new IllegalArgumentException("el numero de segmentos debe ser positivo: " + parts);
        }
        if (totalUnits < parts) {
            throw new IllegalArgumentException("no se pueden repartir " + totalUnits
                    + " unidades entre " + parts + " segmentos");
        }
        List<Segment> segments = new ArrayList<>(parts);
        int base = 0;
        for (int i = 0; i < parts; i++) {
            int length = (i == parts - 1) ? totalUnits - base : totalUnits / parts;
            segments.add(new Segment("s" + i, base, length));
            base += length;
        }
        return new SegmentLayout(segments);
    }

    /** Segmentos apilados desde cero, con nombres y longitudes dadas. */
    public static SegmentLayout stacked(String[] names, int[] lengths) {
        if (names.length != lengths.length) {
            throw new IllegalArgumentException("hay " + names.length + " nombres y "
                    + lengths.length + " longitudes: no cuadran");
        }
        if (names.length == 0) {
            throw new IllegalArgumentException("la segmentacion necesita al menos un segmento");
        }
        List<Segment> segments = new ArrayList<>(names.length);
        int base = 0;
        for (int i = 0; i < names.length; i++) {
            segments.add(new Segment(names[i], base, lengths[i]));
            base += lengths[i];
        }
        return new SegmentLayout(segments);
    }
}
