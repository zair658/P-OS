package pos.shell;

import java.util.List;

import pos.base.PosFault;
import pos.base.PosVersion;
import pos.sys.Syscall;

/**
 * Ordenes que hablan del sistema, no de ficheros.
 *
 * Son las que dejan ver el nucleo desde dentro de un proceso: tablas de procesos,
 * memoria, contador de llamadas. Este es el detalle que hace que la terminal no
 * sea un adorno: {@code ps} no consulta una copia de las estructuras del nucleo,
 * pide una llamada al sistema y el nucleo le contesta con lo que hay en ese
 * instante, incluida ella misma como un proceso mas.
 *
 * Salvo {@code help}, {@code history} y {@code clear}, que son de la cascara y no
 * del sistema, todas las ordenes de aqui gastan al menos una llamada.
 */
public final class SystemCommands {

    private SystemCommands() {
        // clase de utilidad
    }

    static void register(CommandTable table) {
        table.register(Commands.of("ps", "", "lista los procesos vivos", SystemCommands::ps));
        table.register(Commands.of("kill", "pid | %n", "termina un proceso", SystemCommands::kill));
        table.register(Commands.of("mem", "", "resume la memoria fisica", SystemCommands::mem));
        table.register(Commands.of("sleep", "<tics>", "duerme la terminal unos tics",
                SystemCommands::sleep));
        table.register(Commands.of("clock", "", "dice el tic actual", SystemCommands::clock));
        table.register(Commands.of("uptime", "", "tiempo desde el arranque", SystemCommands::uptime));
        table.register(Commands.of("syscalls", "", "tabla de llamadas atendidas",
                SystemCommands::syscalls));
        table.register(Commands.of("method", "[nombre]", "consulta o cambia el metodo de asignacion",
                SystemCommands::method));
        table.register(Commands.of("spawn", "<nombre> <fichero>", "arranca un proceso con ese codigo",
                SystemCommands::spawn));
        table.register(Commands.of("history", "", "lineas escritas en la sesion",
                SystemCommands::history));
        table.register(Commands.of("help", "[orden]", "lista las ordenes o explica una",
                SystemCommands::help));
        table.register(Commands.of("clear", "", "limpia la pantalla", SystemCommands::clear));
        table.register(Commands.of("version", "", "version del sistema", SystemCommands::version));
        table.register(Commands.of("uname", "", "nombre del sistema", SystemCommands::uname));
        table.register(Commands.of("exit", "[codigo]", "termina la sesion",
                SystemCommands::exit));
    }

    private static void ps(Shell shell, List<String> args, Input in, Output out) {
        out.println(String.format("%-4s %-12s %-11s %5s %6s %6s %5s",
                "PID", "NOMBRE", "ESTADO", "HILOS", "VIVOS", "CPU", "MEM"));
        for (String line : shell.must(new Syscall.Ps()).split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] row = line.split("\t");
            if (row.length != 7) {
                throw new PosFault("fila de ps ilegible: " + line);
            }
            out.println(String.format("%-4s %-12s %-11s %5s %6s %6s %5s",
                    row[0], row[1], row[2], row[3], row[4], row[5], row[6]));
        }
    }

    private static void kill(Shell shell, List<String> args, Input in, Output out) {
        if (args.size() != 1) {
            throw new PosFault("uso: kill <pid> | %n");
        }
        // Con %n se mata por numero de trabajo; el pid que se manda al nucleo
        // sale de la tabla de la sesion, que es la unica que sabe emparejarlos.
        JobTable.Job job = args.get(0).trim().startsWith("%")
                ? JobCommands.resolve(shell, args) : null;
        int pid = job == null ? number(args.get(0), "pid") : job.pid();
        int killed = number(shell.must(new Syscall.Kill(pid)), "hilos terminados");
        if (job != null) {
            shell.jobs().remove(pid);
        }
        out.println("p" + pid + " terminado (" + killed + " hilo(s))");
    }

    private static void mem(Shell shell, List<String> args, Input in, Output out) {
        for (String line : shell.must(new Syscall.Mem()).split("\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String[] pair = line.split("\t");
            if (pair.length == 2) {
                out.println(String.format("%-10s %s", pair[0], pair[1]));
            } else {
                out.println(line);
            }
        }
    }

    private static void sleep(Shell shell, List<String> args, Input in, Output out) {
        if (args.size() != 1) {
            throw new PosFault("uso: sleep <tics>");
        }
        long ticks = number(args.get(0), "tics");
        if (ticks <= 0) {
            throw new PosFault("hay que dormir al menos un tic");
        }
        shell.must(new Syscall.Sleep(ticks));
        shell.requestSleep(ticks);
        out.println("la terminal se duerme " + ticks + " tic(s), hasta el "
                + (shell.clock() + ticks));
    }

    private static void clock(Shell shell, List<String> args, Input in, Output out) {
        out.println("tic " + shell.must(new Syscall.Clock()));
    }

    private static void uptime(Shell shell, List<String> args, Input in, Output out) {
        long now = number(shell.must(new Syscall.Clock()), "tic");
        out.println("arriba " + now + " tic(s), " + (now * 10) + " ms simulados");
    }

    private static void syscalls(Shell shell, List<String> args, Input in, Output out) {
        for (String line : shell.system().report().split("\n")) {
            out.println(line);
        }
    }

    private static void method(Shell shell, List<String> args, Input in, Output out) {
        if (args.isEmpty()) {
            out.println("metodo de asignacion: " + shell.must(new Syscall.GetMethod()));
            return;
        }
        if (args.size() > 1) {
            throw new PosFault("uso: method [contiguo|enlazado|indexado]");
        }
        out.println(shell.must(new Syscall.SetMethod(args.get(0))));
    }

    private static void spawn(Shell shell, List<String> args, Input in, Output out) {
        if (args.size() != 2) {
            throw new PosFault("uso: spawn <nombre> <fichero>");
        }
        String source = shell.readFile(args.get(1));
        int pid = number(shell.must(new Syscall.Spawn(args.get(0), source)), "pid");
        if (shell.inBackground()) {
            // Solo los arranques con & son trabajos. Un spawn normal es un
            // proceso que la sesion lanzo y no reclama: meterlo en la tabla de
            // trabajos haria que 'fg' ofreciera traer al frente cosas que nunca
            // estuvieron detras.
            JobTable.Job job = shell.jobs().add(pid, args.get(0), true);
            out.println(job + ": " + source.lines().count() + " linea(s) de codigo");
            return;
        }
        out.println("p" + pid + " creado con " + source.lines().count() + " linea(s) de codigo");
    }

    private static void history(Shell shell, List<String> args, Input in, Output out) {
        List<String> lines = shell.history();
        for (int i = 0; i < lines.size(); i++) {
            out.println(String.format("%3d  %s", i + 1, lines.get(i)));
        }
    }

    private static void help(Shell shell, List<String> args, Input in, Output out) {
        if (!args.isEmpty()) {
            Command command = shell.table().find(args.get(0));
            if (command == null) {
                throw new PosFault("no existe la orden '" + args.get(0) + "'");
            }
            out.println(command.name() + " " + command.usage());
            out.println("  " + command.summary());
            return;
        }
        out.println("ordenes (" + shell.table().size() + "):");
        for (Command command : shell.table().all()) {
            out.println(String.format("  %-10s %-24s %s", command.name(), command.usage(),
                    command.summary()));
        }
    }

    private static void clear(Shell shell, List<String> args, Input in, Output out) {
        // En una terminal de verdad esto serian codigos de escape; aqui la salida
        // es un registro que se lee despues, y borrar de verdad dejaria el
        // registro mintiendo sobre lo que paso.
        out.println("[pantalla limpia: en este sistema la salida se guarda como registro]");
    }

    private static void version(Shell shell, List<String> args, Input in, Output out) {
        out.println(PosVersion.FULL);
    }

    private static void uname(Shell shell, List<String> args, Input in, Output out) {
        out.println(PosVersion.NAME + " " + PosVersion.VERSION + " maquina-virtual-java");
    }

    private static void exit(Shell shell, List<String> args, Input in, Output out) {
        int status = args.isEmpty() ? 0 : number(args.get(0), "codigo de salida");
        shell.requestExit(status);
        out.println("fin de la sesion, codigo " + status);
    }

    private static int number(String text, String what) {
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException e) {
            throw new PosFault("el " + what + " tiene que ser un numero: " + text);
        }
    }
}
