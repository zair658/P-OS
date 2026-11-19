package pos.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import pos.alloc.BestFit;
import pos.fs.AllocationMethod;
import pos.fs.FileSystem;
import pos.fs.FsLayout;
import pos.kernel.Kernel;
import pos.mm.MemorySetup;
import pos.proc.Assembler;
import pos.proc.Pcb;
import pos.sched.RoundRobinScheduler;

/**
 * La terminal vista desde fuera: se le dan teclas y se mira el texto que sale.
 *
 * Ninguna prueba llama a una orden a mano. Se monta el nucleo, se engancha la
 * terminal como un proceso mas y se deja correr: si algo se cuelga, es que la
 * terminal se quedo esperando un turno que no llega, y eso tambien es un fallo
 * que interesa.
 */
@DisplayName("La terminal")
class ShellTest {

    private static final int FISICAS = 4096;
    private static final int PAGINA = 16;
    private static final int CUANTO = 6;
    private static final int BLOQUES = 256;
    private static final int BLOQUE = 128;

    /** Lo que queda de una sesion: el texto, el codigo de salida y el nucleo. */
    private record Sesion(String texto, int estado, Kernel kernel, FileSystem fs) {

        void debeContener(String esperado) {
            assertTrue(texto.contains(esperado),
                    () -> "no aparece '" + esperado + "' en la sesion:\n" + texto);
        }

        void debeTenerLinea(String linea) {
            assertTrue(List.of(texto.split("\n", -1)).contains(linea),
                    () -> "no hay ninguna linea '" + linea + "' en la sesion:\n" + texto);
        }

        void noDebeContener(String inesperado) {
            assertFalse(texto.contains(inesperado),
                    () -> "no deberia aparecer '" + inesperado + "' en la sesion:\n" + texto);
        }

        /** Si el nombre aparece en el directorio indicado. */
        boolean hay(String directorio, String nombre) {
            return fs.list(directorio).stream().anyMatch(entry -> entry.name().equals(nombre));
        }
    }

    /** Imagen con un fichero de tres lineas y un directorio vacio. */
    private static FileSystem imagen() {
        FileSystem fs = FileSystem.format(FsLayout.standard(BLOQUES, BLOQUE),
                new AllocationMethod.Indexed(), new BestFit());
        fs.create("/datos.txt", "uno\ndos\ntres\n");
        fs.mkdir("/docs");
        // Programas listos para que la terminal los arranque con spawn. Se dejan
        // en la imagen en vez de escribirlos con echo porque el programa tiene
        // varias lineas y aqui lo que se prueba es la terminal, no el parser.
        fs.create("/tarea.pos", "SET 0\nloop: ADD 1\nSTORE 0\nCMP 6\nJZ fin\nJMP loop\nfin: EXIT\n");
        fs.create("/bucle.pos", "SET 0\nloop: ADD 1\nSTORE 0\nJMP loop\n");
        return fs;
    }

    private static Sesion sesion(String... lineas) {
        return sesion(imagen(), lineas);
    }

    private static Sesion sesion(FileSystem fs, String... lineas) {
        Kernel kernel = new Kernel(new RoundRobinScheduler(CUANTO),
                MemorySetup.paged(FISICAS, 256, PAGINA));
        kernel.attachFilesystem(fs);
        Terminal terminal = new Terminal(new Shell(kernel.syscalls()),
                Console.of(lineas), kernel::clock);
        Pcb proceso = kernel.createHostedProcess("terminal", terminal);
        terminal.shell().attach(proceso.threads().get(0));
        kernel.run();
        return new Sesion(terminal.transcript(),
                kernel.syscalls().exitStatus(proceso.id()), kernel, fs);
    }

    @Nested
    @DisplayName("salida")
    class Salida {

        @Test
        @DisplayName("echo escribe lo que le dan")
        void echo() {
            sesion("echo hola mundo").debeContener("hola mundo");
        }

        @Test
        @DisplayName("echo -n no cierra la linea")
        void echoSinSalto() {
            sesion("echo -n pegado", "echo siguiente")
                    .debeContener("pegadopos:/> echo siguiente");
        }

        @Test
        @DisplayName("una orden que no existe se avisa y la sesion sigue")
        void ordenDesconocida() {
            Sesion s = sesion("volar", "echo sigo vivo");
            s.debeContener("volar: orden no encontrada");
            s.debeContener("sigo vivo");
        }

        @Test
        @DisplayName("un fichero que no esta da un error, no una excepcion")
        void ficheroQueNoEsta() {
            sesion("cat /fantasma.txt").debeContener("cat:");
        }
    }

    @Nested
    @DisplayName("ficheros")
    class Ficheros {

        @Test
        @DisplayName("cat vuelca el fichero entero")
        void cat() {
            sesion("cat /datos.txt").debeContener("uno\ndos\ntres");
        }

        @Test
        @DisplayName("la redireccion crea el fichero y se puede leer")
        void redireccion() {
            Sesion s = sesion("echo guardado > /nuevo.txt", "cat /nuevo.txt");
            s.debeContener("guardado");
            assertTrue(s.hay("/", "nuevo.txt"));
        }

        @Test
        @DisplayName(">> acumula en vez de pisar")
        void acumula() {
            Sesion s = sesion("echo uno > /lista.txt", "echo dos >> /lista.txt",
                    "cat /lista.txt");
            s.debeContener("uno\ndos");
        }

        @Test
        @DisplayName("< lee la entrada de un fichero")
        void entradaRedirigida() {
            sesion("wc -l < /datos.txt").debeTenerLinea("3");
        }

        @Test
        @DisplayName("cp, mv y rm dejan la imagen como se espera")
        void copiarMoverBorrar() {
            Sesion s = sesion("cp /datos.txt /copia.txt",
                    "mv /copia.txt /movido.txt",
                    "rm /movido.txt",
                    "ls /");
            s.debeContener("copia.txt");
            s.debeContener("movido.txt");
            assertFalse(s.hay("/", "movido.txt"),
                    "tras rm el fichero no puede seguir ahi");
            assertFalse(s.hay("/", "copia.txt"),
                    "mv se lo llevo: en / ya no esta");
        }

        @Test
        @DisplayName("ls marca los directorios con barra")
        void ls() {
            sesion("ls /").debeContener("docs/");
        }

        @Test
        @DisplayName("mkdir crea y cd entra; pwd lo confirma")
        void directorios() {
            Sesion s = sesion("mkdir /docs/sub", "cd /docs/sub", "pwd");
            s.debeTenerLinea("/docs/sub");
            s.debeContener("/docs/sub> ");
        }

        @Test
        @DisplayName("cd a un directorio que no existe no mueve")
        void cdFallido() {
            Sesion s = sesion("cd /nada", "pwd");
            s.debeContener("cd:");
            s.debeTenerLinea("/");
        }
    }

    @Nested
    @DisplayName("tuberias y filtros")
    class Tuberias {

        @Test
        @DisplayName("la tuberia pasa la salida de una orden a la siguiente")
        void pipe() {
            sesion("cat /datos.txt | wc -l").debeTenerLinea("3");
        }

        @Test
        @DisplayName("head y tail recortan por los dos extremos")
        void cabezaYCola() {
            sesion("cat /datos.txt | head -n 2").debeContener("uno\ndos");
            sesion("cat /datos.txt | tail -n 1").debeTenerLinea("tres");
        }

        @Test
        @DisplayName("grep filtra y grep -v descarta")
        void grep() {
            Sesion s = sesion("cat /datos.txt | grep dos", "cat /datos.txt | grep -v dos");
            s.debeContener("dos");
            s.debeContener("uno\ntres");
        }

        @Test
        @DisplayName("wc -w y wc -c cuentan palabras y caracteres")
        void wc() {
            sesion("cat /datos.txt | wc -w").debeTenerLinea("3");
            sesion("cat /datos.txt | wc -c").debeTenerLinea("13");
        }

        @Test
        @DisplayName("una tuberia de tres etapas encadena")
        void tresEtapas() {
            sesion("cat /datos.txt | grep -v dos | wc -l").debeTenerLinea("2");
        }
    }

    @Nested
    @DisplayName("sistema")
    class Sistema {

        @Test
        @DisplayName("exit con codigo termina la sesion con ese codigo")
        void exit() {
            Sesion s = sesion("exit 3", "echo no deberia salir");
            assertEquals(3, s.estado());
            s.noDebeContener("no deberia salir");
        }

        @Test
        @DisplayName("sin exit la sesion acaba cuando se acaban las teclas")
        void sinExit() {
            assertEquals(0, sesion("echo fin").estado());
        }

        @Test
        @DisplayName("spawn arranca el proceso y ps lo lista")
        void spawn() {
            Sesion s = sesion("spawn tarea /tarea.pos",
                    "sleep 10",
                    "ps");
            s.debeContener("tarea");
        }

        @Test
        @DisplayName("el reloj y la tabla de llamadas existen y cuadran")
        void contabilidad() {
            Sesion s = sesion("clock", "syscalls");
            s.debeContener("tics");
            assertTrue(s.kernel().syscalls().total() > 0,
                    "una sesion entera no puede hacer cero llamadas");
        }

        @Test
        @DisplayName("history recuerda lo escrito y help explica una orden")
        void history() {
            Sesion s = sesion("echo uno", "history", "help cat");
            s.debeContener("echo uno");
            s.debeContener("vuelca ficheros");
        }

        @Test
        @DisplayName("kill termina un proceso y ps lo deja como terminado")
        void kill() {
            // La terminal es el proceso 1 y el primer spawn el 2: los
            // identificadores se reparten por orden de creacion.
            Sesion s = sesion("spawn bucle /bucle.pos",
                    "sleep 5",
                    "ps",
                    "kill 2",
                    "ps");
            s.debeContener("terminado");
        }
    }

    @Nested
    @DisplayName("la linea")
    class Linea {

        @Test
        @DisplayName("las comillas mantienen los espacios en un argumento")
        void comillas() {
            LineParser.ParsedLine parsed = LineParser.parse("echo \"dos palabras\" fin");
            LineParser.Stage stage = parsed.stages().get(0);
            assertEquals(List.of("echo", "dos palabras", "fin"), stage.words());
        }

        @Test
        @DisplayName("la tuberia parte la linea en etapas")
        void etapas() {
            LineParser.ParsedLine parsed = LineParser.parse("cat /datos.txt | wc -l");
            assertEquals(2, parsed.stages().size());
            assertEquals("cat", parsed.stages().get(0).words().get(0));
            assertEquals("wc", parsed.stages().get(1).words().get(0));
        }

        @Test
        @DisplayName("las redirecciones se separan de los argumentos")
        void redirecciones() {
            LineParser.Stage stage = LineParser.parse("echo hola >> /salida.txt")
                    .stages().get(0);
            assertEquals("/salida.txt", stage.stdoutFile());
            assertTrue(stage.append(), ">> tiene que marcar que se anade");
            assertEquals(List.of("echo", "hola"), stage.words());
        }

        @Test
        @DisplayName("la entrada redirigida va a la etapa, no a los argumentos")
        void entrada() {
            LineParser.Stage stage = LineParser.parse("wc -l < /datos.txt").stages().get(0);
            assertEquals("/datos.txt", stage.stdinFile());
            assertEquals(List.of("wc", "-l"), stage.words());
        }

        @Test
        @DisplayName("una comilla sin cerrar se avisa y no se ejecuta nada")
        void comillaSuelta() {
            Sesion s = sesion("echo \"sin cerrar");
            s.debeContener("error:");
        }
    }

    @Nested
    @DisplayName("el interprete no se cae")
    class Entereza {

        @Test
        @DisplayName("una sesion larga con errores sigue funcionando")
        void sesionLarga() {
            Sesion s = sesion(
                    "cat /no.txt",
                    "cd /tampoco",
                    "rm /nada.txt",
                    "head -n x /datos.txt",
                    "grep [ /datos.txt",
                    "echo todavia estoy aqui");
            s.debeContener("todavia estoy aqui");
            assertEquals(0, s.estado());
        }

        @Test
        @DisplayName("los procesos creados por la terminal comparten el reloj")
        void comparteReloj() {
            Sesion s = sesion("spawn tarea /tarea.pos",
                    "sleep 30",
                    "clock");
            assertNotNull(s.texto());
            assertTrue(s.kernel().clock() > 30,
                    "dormir 30 tics no puede dejar el reloj por debajo");
        }
    }

    @Test
    @DisplayName("la tabla trae las ordenes de la distribucion")
    void tablaEstandar() {
        CommandTable table = CommandTable.standard();
        for (String nombre : List.of("ls", "cat", "cd", "pwd", "mkdir", "cp", "mv",
                "rm", "stat", "tree", "df", "echo", "head", "tail", "wc", "grep",
                "ps", "kill", "spawn", "help", "exit")) {
            assertNotNull(table.find(nombre), "falta la orden " + nombre);
        }
        assertTrue(table.size() > 20);
        assertEquals(table.all().size(), table.size());
    }

    @Test
    @DisplayName("registrar dos veces el mismo nombre falla")
    void nombreRepetido() {
        CommandTable table = CommandTable.standard();
        Command repetida = Commands.of("ls", "", "", (shell, args, in, out) -> { });
        try {
            table.register(repetida);
            org.junit.jupiter.api.Assertions.fail("tenia que negarse a tapar 'ls'");
        } catch (IllegalArgumentException esperado) {
            assertTrue(esperado.getMessage().contains("ls"));
        }
    }
}
