package pos.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import pos.alloc.BestFit;
import pos.base.PosFault;
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
        // Programa largo pero finito: sirve para ver que 'fg' espera de verdad a
        // que el proceso acabe, cosa que con 'tarea' no daria tiempo a mirar.
        fs.create("/largo.pos",
                "SET 0\nloop: ADD 1\nSTORE 0\nCMP 60\nJZ fin\nJMP loop\nfin: EXIT\n");
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

        @Test
        @DisplayName("el & final marca la etapa y no llega a los argumentos")
        void ampersandFinal() {
            LineParser.Stage stage = LineParser.parse("spawn eco /tarea.pos &").stages().get(0);
            assertTrue(stage.background(), "el & tiene que marcar segundo plano");
            assertEquals(List.of("spawn", "eco", "/tarea.pos"), stage.words());
        }

        @Test
        @DisplayName("sin & la etapa va en primer plano")
        void sinAmpersand() {
            assertFalse(LineParser.parse("spawn eco /tarea.pos").stages().get(0).background());
        }

        @Test
        @DisplayName("un & en medio de la linea se rechaza")
        void ampersandEnMedio() {
            PosFault fallo = assertThrows(PosFault.class,
                    () -> LineParser.parse("echo a & echo b"));
            assertTrue(fallo.getMessage().contains("al final"), fallo.getMessage());
        }

        @Test
        @DisplayName("un & sin orden delante se rechaza")
        void ampersandSolo() {
            PosFault fallo = assertThrows(PosFault.class, () -> LineParser.parse("&"));
            assertTrue(fallo.getMessage().contains("sin orden"), fallo.getMessage());
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

    @Nested
    @DisplayName("trabajos")
    class Trabajos {

        @Test
        @DisplayName("spawn con & crea un trabajo y jobs lo pinta")
        void jobsLoPinta() {
            Sesion s = sesion("spawn bucle /bucle.pos &", "stop %1", "jobs", "kill 2");
            s.debeContener("[1] p2 bucle (segundo plano)");
            s.debeContener("bucle (segundo plano): detenido (1 hilo(s) fuera de la cola)");
            // La fila de la tabla: numero, pid, estado, plano y nombre.
            s.debeTenerLinea("  [1]   p2   detenido  segundo  bucle");
        }

        @Test
        @DisplayName("bg sin detener solo cambia el plano; detenido, ademas reanuda")
        void bgReanuda() {
            Sesion s = sesion("spawn bucle /bucle.pos &", "stop %1", "bg %1", "jobs", "kill 2");
            s.debeContener("reanudado en segundo plano");
            // Vuelve a la cola de listos: no se salta el turno por haber estado
            // detenido, y la tabla lo llama por su nombre.
            s.debeTenerLinea("  [1]   p2   listo   segundo  bucle");
        }

        @Test
        @DisplayName("fg trae el trabajo al frente y la terminal espera a que acabe")
        void fgEspera() {
            Sesion s = sesion("spawn largo /largo.pos &", "fg %1", "echo fin");
            s.debeContener("al primer plano; la terminal espera como mucho "
                    + Shell.ESPERA_MAXIMA + " ticks");
            String texto = s.texto();
            int espera = texto.indexOf("espera como mucho");
            assertTrue(espera >= 0, () -> "fg tiene que avisar de la espera:\n" + texto);
            int acaba = texto.indexOf("largo (primer plano): terminado");
            int vuelta = texto.lastIndexOf("\nfin");
            assertTrue(acaba > espera,
                    () -> "el trabajo tiene que terminar despues de la espera:\n" + texto);
            assertTrue(vuelta > acaba,
                    () -> "la terminal solo vuelve al teclado cuando el trabajo acaba:\n" + texto);
        }

        @Test
        @DisplayName("fg sobre un trabajo que no termina nunca devuelve el aviso")
        void fgConTope() {
            // 'bucle' no sale nunca de su bucle. Sin tope, la terminal se
            // quedaria esperando para siempre y ni 'kill' ni 'exit' llegarian a
            // ejecutarse: la sesion se colgaria.
            Sesion s = sesion("spawn bucle /bucle.pos &", "fg %1", "jobs", "kill %1", "exit");
            s.debeContener("al primer plano; la terminal espera como mucho "
                    + Shell.ESPERA_MAXIMA + " ticks");
            // El aviso volvio, asi que la linea siguiente se ejecuto y el
            // trabajo seguia vivo: se ve en la tabla.
            s.debeTenerLinea("  [1]   p2   listo   primero  bucle");
            // Y el nucleo dejo constancia de que la espera se agoto, no de que
            // el proceso hubiera terminado.
            String traza = String.join("\n", s.kernel().trace().lines());
            assertTrue(traza.contains("se le acabo la espera de p2"),
                    () -> "la traza tiene que decir que la espera se agoto:\n" + traza);
        }

        @Test
        @DisplayName("si la orden se queja, el & no anade un segundo aviso")
        void unSoloAviso() {
            // 'spawn' sin fichero no arranca nada y ya lo dice: el uso correcto.
            // Anadir encima que el & no vale para esto manda a mirar el sitio
            // equivocado.
            Sesion s = sesion("spawn &");
            String texto = s.texto();
            assertTrue(texto.contains("spawn: uso:"), () -> "falta el uso:\n" + texto);
            assertFalse(texto.contains("no arranca ningun proceso"),
                    () -> "un fallo de uso no necesita el aviso del &:\n" + texto);
        }

        @Test
        @DisplayName("un trabajo que acaba se anuncia en la linea siguiente")
        void avisaDelQueAcabo() {
            // El programa tarda mas que la linea siguiente: se le da tiempo con
            // un sleep, que es la unica forma de que el trabajo muera mientras
            // la terminal no esta mirando.
            Sesion s = sesion("spawn tarea /tarea.pos &", "sleep 120", "jobs");
            s.debeContener("tarea (segundo plano): terminado");
            // Y se va de la tabla: una fila que sigue ahi despues de avisar
            // seria un fantasma que no se puede ni matar ni traer al frente.
            s.debeContener("no hay trabajos en esta sesion");
        }

        @Test
        @DisplayName("un & sobre algo que no arranca procesos se avisa")
        void ampersandSinProceso() {
            sesion("echo hola &").debeContener("el & solo vale para 'spawn'");
        }

        @Test
        @DisplayName("sin trabajos, jobs lo dice y las ordenes avisan de que falta el numero")
        void sinTrabajos() {
            Sesion s = sesion("jobs", "bg %9", "fg %9", "stop");
            s.debeContener("no hay trabajos en esta sesion");
            s.debeContener("no hay ningun trabajo %9");
            s.debeContener("falta el trabajo");
        }

        @Test
        @DisplayName("kill acepta el numero de trabajo y lo saca de la tabla")
        void killPorNumero() {
            Sesion s = sesion("spawn bucle /bucle.pos &", "stop %1", "kill %1", "jobs");
            s.debeContener("p2 terminado (1 hilo(s))");
            s.debeContener("no hay trabajos en esta sesion");
        }

        @Test
        @DisplayName("un pid que no es de la sesion no vale como trabajo")
        void pidAjeno() {
            // p1 es la propia terminal: existe, pero no es un trabajo.
            sesion("spawn bucle /bucle.pos &", "bg 1", "kill 2")
                    .debeContener("p1 no es un trabajo de esta sesion");
        }

        @Test
        @DisplayName("sin trabajos, el pid no se confunde con un trabajo")
        void sinTrabajosPid() {
            sesion("spawn tarea /tarea.pos", "bg 2")
                    .debeContener("no hay trabajos: arranca uno con 'spawn ... &'");
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
