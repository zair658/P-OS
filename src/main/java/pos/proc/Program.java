package pos.proc;

import java.util.List;
import pos.base.PosFault;

/**
 * Programa cargado en memoria, listo para ejecutarse.
 *
 * Es inmutable: una vez resueltas las etiquetas a indices absolutos, el
 * programa es solo una lista de instrucciones. Los hilos comparten el programa
 * y llevan su propio contador de programa, que es lo que permite que dos hilos
 * del mismo proceso ejecuten el mismo codigo en puntos distintos.
 */
public final class Program {

    private final String name;
    private final List<Instruction> code;

    public Program(String name, List<Instruction> code) {
        if (code.isEmpty()) {
            throw new PosFault("el programa '" + name + "' no tiene instrucciones");
        }
        this.name = name;
        this.code = List.copyOf(code);
    }

    public String name() {
        return name;
    }

    public int size() {
        return code.size();
    }

    /**
     * Instruccion en una posicion concreta.
     *
     * @throws PosFault si el indice se sale del programa; el mensaje incluye el
     *     nombre del programa y el indice, que es lo que hace falta para
     *     encontrar el fallo en la traza.
     */
    public Instruction at(int index) {
        if (index < 0 || index >= code.size()) {
            throw new PosFault("'" + name + "': instruccion " + index
                    + " fuera de rango (0.." + (code.size() - 1) + ")");
        }
        return code.get(index);
    }

    /** Listado con indices, para poder ensenar el programa cargado. */
    public List<String> listing() {
        var lines = new java.util.ArrayList<String>(code.size());
        for (int i = 0; i < code.size(); i++) {
            lines.add(String.format("%3d  %s", i, code.get(i).text()));
        }
        return List.copyOf(lines);
    }
}
