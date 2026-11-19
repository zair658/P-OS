package pos.shell;

import java.util.ArrayList;
import java.util.List;

import pos.base.PosFault;
import pos.sys.Syscall;

/**
 * Ordenes que tocan el sistema de ficheros.
 *
 * Todas pasan por llamadas al sistema, incluidas las que podrian hacerlo mejor
 * por dentro: {@code ls} pide un {@code stat} por entrada para saber si es un
 * directorio, {@code cp} lee y escribe a trozos con descriptores y {@code tree}
 * recorre el arbol a base de {@code list}. Esa torpeza es el punto: cada paso que
 * da la terminal queda contado en la tabla de llamadas, y eso es lo que se quiere
 * ensenar.
 *
 * Las ordenes no comprueban el sistema de ficheros por su cuenta mas de lo
 * imprescindible. Quien decide si un nombre esta cogido, si un directorio esta
 * vacio o si un fichero cabe es el nucleo; aqui solo se traduce la respuesta en
 * un mensaje.
 */
public final class FileCommands {

    private FileCommands() {
        // clase de utilidad
    }

    static void register(CommandTable table) {
        table.register(Commands.of("ls", "[-l] [ruta]", "lista el contenido de un directorio",
                FileCommands::ls));
        table.register(Commands.of("cat", "[fichero...]", "vuelca ficheros o la entrada",
                FileCommands::cat));
        table.register(Commands.of("pwd", "", "dice en que directorio estas", FileCommands::pwd));
        table.register(Commands.of("cd", "[ruta]", "cambia de directorio", FileCommands::cd));
        table.register(Commands.of("mkdir", "<ruta>", "crea un directorio", FileCommands::mkdir));
        table.register(Commands.of("touch", "<ruta>", "crea un fichero vacio si no existe",
                FileCommands::touch));
        table.register(Commands.of("rm", "[-r] <ruta...>", "borra ficheros o directorios",
                FileCommands::rm));
        table.register(Commands.of("cp", "<origen> <destino>", "copia un fichero",
                FileCommands::cp));
        table.register(Commands.of("mv", "<origen> <destino>", "mueve o renombra un fichero",
                FileCommands::mv));
        table.register(Commands.of("stat", "<ruta>", "muestra los datos de una entrada",
                FileCommands::stat));
        table.register(Commands.of("tree", "[ruta]", "recorre el arbol de directorios",
                FileCommands::tree));
        table.register(Commands.of("df", "", "resume el espacio de la imagen", FileCommands::df));
        table.register(Commands.of("fsck", "", "verifica la imagen y las estructuras",
                FileCommands::fsck));
    }

    private static void ls(Shell shell, List<String> args, Input in, Output out) {
        boolean longFormat = false;
        String path = ".";
        for (String arg : args) {
            if (arg.equals("-l")) {
                longFormat = true;
            } else if (arg.startsWith("-")) {
                throw new PosFault("opcion desconocida: " + arg);
            } else {
                path = arg;
            }
        }
        List<String> names = shell.listDir(path);
        if (names.isEmpty()) {
            return;
        }
        if (!longFormat) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < names.size(); i++) {
                String name = names.get(i);
                // Un stat por entrada solo para poner la barra final. Cuesta una
                // llamada y ahorra al usuario tener que adivinar que es que.
                if (shell.stat(Shell.join(path, name)).directory()) {
                    name = name + "/";
                }
                line.append(name);
                if (i < names.size() - 1) {
                    line.append("  ");
                }
            }
            out.println(line.toString());
            return;
        }
        for (String name : names) {
            Shell.Entry entry = shell.stat(Shell.join(path, name));
            out.println(entry.kind() + " " + String.format("%4d", entry.size()) + " "
                    + String.format("%3d", entry.blocks()) + "b  " + name);
        }
    }

    private static void cat(Shell shell, List<String> args, Input in, Output out) {
        if (args.isEmpty()) {
            if (in.empty()) {
                throw new PosFault("no hay nada que volcar: ni ficheros ni entrada");
            }
            out.print(in.text());
            return;
        }
        for (String path : args) {
            out.print(shell.readFile(path));
        }
    }

    private static void pwd(Shell shell, List<String> args, Input in, Output out) {
        if (!args.isEmpty()) {
            throw new PosFault("pwd no lleva argumentos");
        }
        out.println(shell.cwd());
    }

    private static void cd(Shell shell, List<String> args, Input in, Output out) {
        String path = args.isEmpty() ? "/" : args.get(0);
        if (args.size() > 1) {
            throw new PosFault("cd solo admite una ruta");
        }
        shell.must(new Syscall.Chdir(path));
    }

    private static void mkdir(Shell shell, List<String> args, Input in, Output out) {
        if (args.isEmpty()) {
            throw new PosFault("falta la ruta del directorio");
        }
        for (String path : args) {
            shell.must(new Syscall.Mkdir(path));
        }
    }

    private static void touch(Shell shell, List<String> args, Input in, Output out) {
        if (args.isEmpty()) {
            throw new PosFault("falta la ruta del fichero");
        }
        for (String path : args) {
            if (shell.exists(path)) {
                continue;
            }
            int fd = Integer.parseInt(shell.must(new Syscall.Open(path, Syscall.Mode.WRITE)));
            shell.call(new Syscall.Close(fd));
        }
    }

    private static void rm(Shell shell, List<String> args, Input in, Output out) {
        boolean recursive = false;
        List<String> paths = new ArrayList<>();
        for (String arg : args) {
            if (arg.equals("-r")) {
                recursive = true;
            } else if (arg.startsWith("-")) {
                throw new PosFault("opcion desconocida: " + arg);
            } else {
                paths.add(arg);
            }
        }
        if (paths.isEmpty()) {
            throw new PosFault("falta la ruta que borrar");
        }
        for (String path : paths) {
            remove(shell, path, recursive);
        }
    }

    private static void remove(Shell shell, String path, boolean recursive) {
        Shell.Entry entry = shell.stat(path);
        if (entry.directory()) {
            if (!recursive) {
                throw new PosFault(path + " es un directorio; usa rm -r");
            }
            // Se vacia a mano y luego se borra la entrada: el nucleo no borra
            // arboles, y hacerlo en la cascara deja el recorrido a la vista.
            for (String child : shell.listDir(path)) {
                remove(shell, Shell.join(path, child), true);
            }
        }
        shell.must(new Syscall.Unlink(path));
    }

    private static void cp(Shell shell, List<String> args, Input in, Output out) {
        if (args.size() != 2) {
            throw new PosFault("uso: cp <origen> <destino>");
        }
        String from = args.get(0);
        String to = args.get(1);
        if (shell.stat(from).directory()) {
            throw new PosFault("cp todavia no copia directorios: " + from);
        }
        String text = shell.readFile(from);
        shell.writeFile(to, text, false);
        out.println(from + " -> " + to + " (" + text.length() + " caracteres)");
    }

    private static void mv(Shell shell, List<String> args, Input in, Output out) {
        if (args.size() != 2) {
            throw new PosFault("uso: mv <origen> <destino>");
        }
        // No hay llamada de renombrar: se copia y se borra, que es lo que hace un
        // sistema sin enlaces duros que compartan bloques.
        String text = shell.readFile(args.get(0));
        shell.writeFile(args.get(1), text, false);
        shell.must(new Syscall.Unlink(args.get(0)));
        out.println(args.get(0) + " -> " + args.get(1));
    }

    private static void stat(Shell shell, List<String> args, Input in, Output out) {
        if (args.size() != 1) {
            throw new PosFault("uso: stat <ruta>");
        }
        Shell.Entry entry = shell.stat(args.get(0));
        out.println("tipo      : " + (entry.directory() ? "directorio" : "fichero"));
        out.println("tamano    : " + entry.size() + " bytes");
        out.println("bloques   : " + entry.blocks());
        out.println("modificado: tic " + entry.tick());
    }

    private static void tree(Shell shell, List<String> args, Input in, Output out) {
        String path = args.isEmpty() ? "." : args.get(0);
        out.println(path);
        int[] counter = {0, 0};
        walk(shell, path, "", counter, out);
        out.println(counter[0] + " directorio(s), " + counter[1] + " fichero(s)");
    }

    private static void walk(Shell shell, String path, String prefix, int[] counter, Output out) {
        List<String> names = shell.listDir(path);
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            boolean last = i == names.size() - 1;
            Shell.Entry entry = shell.stat(Shell.join(path, name));
            out.println(prefix + (last ? "\\-- " : "|-- ") + name
                    + (entry.directory() ? "/" : " (" + entry.size() + "b)"));
            if (entry.directory()) {
                counter[0]++;
                walk(shell, Shell.join(path, name), prefix + (last ? "    " : "|   "), counter, out);
            } else {
                counter[1]++;
            }
        }
    }

    private static void df(Shell shell, List<String> args, Input in, Output out) {
        printLines(shell.must(new Syscall.FsInfo()), out);
    }

    private static void fsck(Shell shell, List<String> args, Input in, Output out) {
        printLines(shell.must(new Syscall.FsCheck()), out);
    }

    private static void printLines(String text, Output out) {
        for (String line : text.split("\n")) {
            if (!line.isEmpty()) {
                out.println("  " + line);
            }
        }
    }
}
