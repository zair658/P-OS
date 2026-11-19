package pos.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * La tabla de procesos: que hay vivo, en que estado y cuanto ocupa.
 *
 * La columna de unidades fisicas no es la misma que la logica, y esa diferencia
 * es justo lo que hay que mirar: en un esquema paginado, un proceso puede pedir
 * dieciseis unidades logicas y ocupar veinte marcos. Verlas juntas convierte un
 * numero en una explicacion.
 */
public final class ProcessTable implements Table {

    @Override
    public String title() {
        return "Procesos";
    }

    @Override
    public String[] headers() {
        return new String[] { "PID", "Proceso", "Estado", "Hilos", "Vivos", "Logico", "Fisico", "Esquema" };
    }

    @Override
    public List<String[]> rows(Snapshot shot) {
        List<String[]> rows = new ArrayList<>(shot.processes().size());
        for (Snapshot.Proc process : shot.processes()) {
            rows.add(new String[] {
                    String.valueOf(process.pid()),
                    process.name(),
                    process.state(),
                    String.valueOf(process.threads()),
                    String.valueOf(process.liveThreads()),
                    String.valueOf(process.units()),
                    String.valueOf(process.physical()),
                    process.scheme()
            });
        }
        return rows;
    }

    @Override
    public String summary(Snapshot shot) {
        int live = shot.liveProcesses();
        int total = shot.processes().size();
        return total + " procesos, " + live + " vivos, " + shot.threads().size() + " hilos";
    }
}
