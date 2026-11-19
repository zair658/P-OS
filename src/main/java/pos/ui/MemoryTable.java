package pos.ui;

import java.util.ArrayList;
import java.util.List;

import pos.phys.PhysicalMemory;

/**
 * La tabla del mapa de memoria: tramos, no unidades.
 *
 * Cuatro mil unidades una por una no se leen, se miran; para eso esta el mapa
 * grafico. Lo que si se lee es la lista de tramos: donde empieza la memoria de
 * cada proceso, cuanto ocupa y donde quedaron los huecos. Los tramos se sacan
 * agrupando el array de duennos, de modo que la tabla y el dibujo no puedan
 * contar cosas distintas: son la misma fuente.
 */
public final class MemoryTable implements Table {

    @Override
    public String title() {
        return "Tramos de memoria fisica";
    }

    @Override
    public String[] headers() {
        return new String[] { "Inicio", "Fin", "Unidades", "Dueno" };
    }

    @Override
    public List<String[]> rows(Snapshot shot) {
        List<String[]> rows = new ArrayList<>();
        for (Snapshot.Span span : spans(shot)) {
            rows.add(new String[] {
                    String.valueOf(span.start()),
                    String.valueOf(span.start() + span.length()),
                    String.valueOf(span.length()),
                    ownerText(shot, span.owner())
            });
        }
        return rows;
    }

    @Override
    public String summary(Snapshot shot) {
        Snapshot.MemoryMap memory = shot.memory();
        return memory.size() + " unidades  ·  " + memory.used() + " ocupadas  ·  "
                + memory.free() + " libres  ·  " + spans(shot).size() + " tramos";
    }

    /**
     * Agrupa el mapa de unidades en tramos contiguos del mismo duenno.
     *
     * Un tramo libre de cuatro unidades y otro libre de cuatro pegadas son el
     * mismo tramo de ocho: si no se agruparan, la tabla diria que hay cuatro
     * huecos donde hay uno, que es exactamente el error que la fragmentacion
     * externa hace visible.
     */
    public static List<Snapshot.Span> spans(Snapshot shot) {
        Snapshot.MemoryMap memory = shot.memory();
        List<Snapshot.Span> spans = new ArrayList<>();
        int size = memory.size();
        if (size == 0) {
            return spans;
        }
        int owner = memory.ownerAt(0);
        int start = 0;
        for (int unit = 1; unit <= size; unit++) {
            int current = unit < size ? memory.ownerAt(unit) : Integer.MIN_VALUE;
            if (current != owner) {
                spans.add(new Snapshot.Span(start, unit - start, owner));
                start = unit;
                owner = current;
            }
        }
        return spans;
    }

    /** Texto del duenno de un tramo: nombre del proceso, o "libre". */
    public static String ownerText(Snapshot shot, int owner) {
        if (owner == PhysicalMemory.FREE) {
            return "libre";
        }
        for (Snapshot.Proc process : shot.processes()) {
            if (process.pid() == owner) {
                return process.name() + " (" + process.pid() + ")";
            }
        }
        return "pid " + owner;
    }
}
