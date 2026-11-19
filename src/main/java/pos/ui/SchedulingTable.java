package pos.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * La tabla del reparto: quien esta en la cola, quien tiene la CPU y a que precio.
 *
 * Las tres columnas de tiempos -- CPU, espera y retorno -- son las mismas que
 * salen en el informe final del nucleo, pero tomadas en vivo. Eso permite ver la
 * diferencia entre "va lento" y "esta esperando", que es la pregunta que uno se
 * hace mirando una ventana y la que una captura final ya no puede contestar.
 */
public final class SchedulingTable implements Table {

    @Override
    public String title() {
        return "Reparto de CPU";
    }

    @Override
    public String[] headers() {
        return new String[] { "Hilo", "Proceso", "Estado", "PC", "CPU", "Espera", "Retorno", "Bloqueado en" };
    }

    @Override
    public List<String[]> rows(Snapshot shot) {
        List<String[]> rows = new ArrayList<>(shot.threads().size());
        for (Snapshot.ThreadInfo thread : shot.threads()) {
            rows.add(new String[] {
                    thread.id(),
                    thread.process(),
                    thread.state(),
                    String.valueOf(thread.pc()),
                    String.valueOf(thread.cpu()),
                    String.valueOf(thread.waitTicks()),
                    String.valueOf(thread.turnaround()),
                    thread.blockedOn()
            });
        }
        return rows;
    }

    @Override
    public String summary(Snapshot shot) {
        return shot.scheduler()
                + "  ·  reloj " + shot.clock() + " ticks"
                + "  ·  " + shot.ready().size() + " en cola"
                + "  ·  " + shot.count("ejecutando") + " ejecutando"
                + "  ·  " + shot.count("bloqueado") + " bloqueados";
    }

    /** La cola de listos en orden, que es lo que explica la siguiente eleccion. */
    public List<String[]> queueRows(Snapshot shot) {
        List<String[]> rows = new ArrayList<>(shot.ready().size());
        for (Snapshot.Ready entry : shot.ready()) {
            rows.add(new String[] {
                    String.valueOf(entry.position()),
                    entry.thread(),
                    entry.process()
            });
        }
        return rows;
    }

    /** Cabeceras de la cola de listos. */
    public String[] queueHeaders() {
        return new String[] { "Turno", "Hilo", "Proceso" };
    }
}
