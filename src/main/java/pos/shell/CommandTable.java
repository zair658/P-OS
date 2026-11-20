package pos.shell;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Las ordenes que entiende la terminal, por nombre.
 *
 * La tabla se llena al arrancar y despues solo se lee. No es inmutable porque
 * obligar a construirla con una lista de todas las ordenes haria que cada
 * fichero de ordenes tuviera que devolver objetos en vez de registrarse donde le
 * toca; el efecto practico es el mismo: nadie registra nada con la sesion ya
 * empezada, y repetir un nombre falla en vez de tapar la anterior en silencio.
 */
public final class CommandTable {

    private final Map<String, Command> commands = new LinkedHashMap<>();

    /** La tabla con todas las ordenes de la distribucion. */
    public static CommandTable standard() {
        CommandTable table = new CommandTable();
        FileCommands.register(table);
        TextCommands.register(table);
        SystemCommands.register(table);
        JobCommands.register(table);
        return table;
    }

    /**
     * Añade una orden.
     *
     * @throws IllegalArgumentException si el nombre ya esta cogido
     */
    public void register(Command command) {
        if (commands.containsKey(command.name())) {
            throw new IllegalArgumentException("orden repetida: " + command.name());
        }
        commands.put(command.name(), command);
    }

    /** La orden con ese nombre, o {@code null} si no existe. */
    public Command find(String name) {
        return commands.get(name);
    }

    public boolean contains(String name) {
        return commands.containsKey(name);
    }

    /** Todas las ordenes, por orden alfabetico. */
    public List<Command> all() {
        List<Command> list = new ArrayList<>(commands.values());
        list.sort((a, b) -> a.name().compareTo(b.name()));
        return List.copyOf(list);
    }

    public int size() {
        return commands.size();
    }
}
