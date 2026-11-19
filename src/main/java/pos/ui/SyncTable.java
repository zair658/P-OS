package pos.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * La tabla de los tres mecanismos de sincronizacion, juntos y en el mismo sitio.
 *
 * Van en una sola tabla con una columna de mecanismo a proposito. En el codigo
 * son tres clases distintas porque resuelven tres problemas distintos, pero
 * mirando el sistema lo que se quiere saber es una cosa sola: quien esta
 * esperando y por que. Tres tablas separadas obligarian a recorrer tres sitios
 * para responder esa pregunta.
 */
public final class SyncTable implements Table {

    @Override
    public String title() {
        return "Cerrojos, semaforos y condiciones";
    }

    @Override
    public String[] headers() {
        return new String[] { "Mecanismo", "Nombre", "Situacion", "Esperan" };
    }

    @Override
    public List<String[]> rows(Snapshot shot) {
        List<String[]> rows = new ArrayList<>();
        for (Snapshot.LockRow lock : shot.sync().locks()) {
            rows.add(new String[] {
                    "cerrojo",
                    lock.name(),
                    lock.owner().equals("-") ? "libre" : "en poder de " + lock.owner(),
                    String.valueOf(lock.waiting())
            });
        }
        for (Snapshot.SemRow semaphore : shot.sync().semaphores()) {
            rows.add(new String[] {
                    "semaforo",
                    semaphore.name(),
                    "valor " + semaphore.value(),
                    String.valueOf(semaphore.waiting())
            });
        }
        for (Snapshot.CondRow condition : shot.sync().conditions()) {
            rows.add(new String[] {
                    "condicion",
                    condition.name(),
                    "cerrojo '" + condition.lock() + "'",
                    String.valueOf(condition.waiting())
            });
        }
        return rows;
    }

    @Override
    public String summary(Snapshot shot) {
        Snapshot.Sync sync = shot.sync();
        if (sync.idle()) {
            return "sin mecanismos de sincronizacion en este escenario";
        }
        int waiting = 0;
        for (Snapshot.LockRow row : sync.locks()) {
            waiting += row.waiting();
        }
        for (Snapshot.SemRow row : sync.semaphores()) {
            waiting += row.waiting();
        }
        for (Snapshot.CondRow row : sync.conditions()) {
            waiting += row.waiting();
        }
        String text = sync.locks().size() + " cerrojos  ·  " + sync.semaphores().size()
                + " semaforos  ·  " + sync.conditions().size() + " condiciones  ·  "
                + waiting + " esperas";
        if (sync.deadlock() != null) {
            text += "  ·  INTERBLOQUEO: " + sync.deadlock();
        }
        return text;
    }
}
