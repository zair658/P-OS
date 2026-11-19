package pos.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * La tabla de llamadas al sistema: que se pidio al nucleo y cuantas veces.
 *
 * Es la vista que separa "el proceso hace cosas" de "el proceso pide cosas". Un
 * programa interpretado no llama al nucleo para calcular; la terminal si, y por
 * eso su fila aqui es la que cuenta el trabajo de verdad.
 */
public final class CallsTable implements Table {

    @Override
    public String title() {
        return "Llamadas al sistema";
    }

    @Override
    public String[] headers() {
        return new String[] { "Codigo", "Llamada", "Veces" };
    }

    @Override
    public List<String[]> rows(Snapshot shot) {
        List<String[]> rows = new ArrayList<>(shot.calls().rows().size());
        for (Snapshot.CallRow call : shot.calls().rows()) {
            rows.add(new String[] {
                    String.valueOf(call.code()),
                    call.name(),
                    String.valueOf(call.count())
            });
        }
        return rows;
    }

    @Override
    public String summary(Snapshot shot) {
        if (shot.calls().empty()) {
            return "ninguna llamada al sistema en este escenario";
        }
        return shot.calls().total() + " llamadas  ·  " + shot.calls().ticks()
                + " ticks de nucleo  ·  " + shot.calls().rows().size() + " clases distintas";
    }
}
