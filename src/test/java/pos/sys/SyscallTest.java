package pos.sys;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
import pos.proc.ExecutionHost;
import pos.proc.HostedTask;
import pos.proc.StepResult;
import pos.proc.Tcb;
import pos.sched.RoundRobinScheduler;

@DisplayName("La frontera entre el proceso y el nucleo")
class SyscallTest {

    private static final int BLOQUE = 128;
    private static final int BLOQUES = 64;
    private static final int CUANTO = 4;

    /**
     * Sonda: un proceso nativo que hace llamadas en nombre de su propio hilo.
     *
     * Es la forma mas directa de probar la frontera sin pasar por la terminal. Lo
     * que se comprueba aqui es el contrato del nucleo: que responde, que cobra y
     * que se niega cuando toca.
     */
    private static final class Sonda implements HostedTask {

        private final Kernel kernel;
        private final List<Syscall> plan;
        private final List<SyscallResult> respuestas = new ArrayList<>();
        private int next;

        Sonda(Kernel kernel, Syscall... plan) {
            this.kernel = kernel;
            this.plan = List.of(plan);
        }

        @Override
        public StepResult step(ExecutionHost host, Tcb thread) {
            if (next == plan.size()) {
                return StepResult.EXITED;
            }
            respuestas.add(kernel.syscalls().call(thread, plan.get(next++)));
            host.retireInstruction(thread);
            return StepResult.CONTINUE;
        }

        List<SyscallResult> respuestas() {
            return respuestas;
        }
    }

    private static Kernel nucleo() {
        Kernel kernel = new Kernel(new RoundRobinScheduler(CUANTO),
                MemorySetup.paged(4096, 256, 16));
        FileSystem fs = FileSystem.format(FsLayout.standard(BLOQUES, BLOQUE),
                new AllocationMethod.Indexed(), new BestFit());
        fs.create("/datos.txt", "uno\ndos\ntres\n");
        kernel.attachFilesystem(fs);
        return kernel;
    }

    private static Sonda correr(Kernel kernel, Syscall... plan) {
        Sonda sonda = new Sonda(kernel, plan);
        kernel.createHostedProcess("sonda", sonda);
        kernel.run();
        return sonda;
    }

    @Nested
    @DisplayName("descriptores")
    class Descriptores {

        @Test
        @DisplayName("abrir, leer y cerrar devuelve lo que se escribio")
        void abrirLeerCerrar() {
            Sonda sonda = correr(nucleo(),
                    new Syscall.Open("/datos.txt", Syscall.Mode.READ),
                    new Syscall.Read(3, 64),
                    new Syscall.Read(3, 64),
                    new Syscall.Close(3));

            List<SyscallResult> r = sonda.respuestas();
            assertTrue(r.get(0).ok(), "abrir un fichero que existe tiene que salir bien");
            assertEquals("3", r.get(0).text(), "los tres primeros descriptores son la consola");
            assertEquals("uno\ndos\ntres\n", r.get(1).text());
            assertEquals("", r.get(2).text(), "leer mas alla del final devuelve vacio, no falla");
            assertTrue(r.get(3).ok());
        }

        @Test
        @DisplayName("leer un descriptor que no se abrio falla")
        void leerSinAbrir() {
            Sonda sonda = correr(nucleo(), new Syscall.Read(9, 10));
            assertFalse(sonda.respuestas().get(0).ok());
            assertTrue(sonda.respuestas().get(0).text().contains("9"));
        }

        @Test
        @DisplayName("la tabla de descriptores tiene tope y lo dice")
        void topeDeDescriptores() {
            List<Syscall> plan = new ArrayList<>();
            for (int i = 0; i <= SystemCalls.MAX_FDS; i++) {
                plan.add(new Syscall.Open("/datos.txt", Syscall.Mode.READ));
            }
            Sonda sonda = correr(nucleo(), plan.toArray(new Syscall[0]));

            List<SyscallResult> r = sonda.respuestas();
            for (int i = 0; i < SystemCalls.MAX_FDS; i++) {
                assertTrue(r.get(i).ok(), "el descriptor " + (i + 3) + " tenia que caber");
            }
            assertFalse(r.get(SystemCalls.MAX_FDS).ok());
            assertTrue(r.get(SystemCalls.MAX_FDS).text().contains("descriptor"));
        }

        @Test
        @DisplayName("el descriptor que se cierra se vuelve a repartir")
        void reutilizaDescriptores() {
            Sonda sonda = correr(nucleo(),
                    new Syscall.Open("/datos.txt", Syscall.Mode.READ),
                    new Syscall.Close(3),
                    new Syscall.Open("/datos.txt", Syscall.Mode.READ));

            assertEquals("3", sonda.respuestas().get(0).text());
            assertEquals("3", sonda.respuestas().get(2).text(),
                    "si no se reutiliza, un bucle de abrir y cerrar agota la tabla");
        }

        @Test
        @DisplayName("escribir deja el contenido al cerrar, no antes")
        void escrituraDiferida() {
            Kernel kernel = nucleo();
            Sonda sonda = correr(kernel,
                    new Syscall.Open("/nuevo.txt", Syscall.Mode.WRITE),
                    new Syscall.Write(3, "primera"),
                    new Syscall.Close(3),
                    new Syscall.Open("/nuevo.txt", Syscall.Mode.READ),
                    new Syscall.Read(3, 64));

            List<SyscallResult> r = sonda.respuestas();
            assertTrue(r.get(2).ok());
            assertEquals("primera", r.get(4).text(),
                    "al cerrar se guarda: si no, el fichero no serviria para nada");
        }
    }

    @Nested
    @DisplayName("procesos")
    class Procesos {

        /** Proceso que no termina nunca: sirve de victima. */
        private static final String BUCLE = """
                SET 0
                loop: ADD 1
                      STORE 0
                      JMP loop
                """;

        @Test
        @DisplayName("matar a otro proceso saca sus hilos de las colas")
        void matarAOtro() {
            Kernel kernel = nucleo();
            kernel.createProcess("bucle", Assembler.load("bucle", BUCLE));
            // La victima se crea antes que la sonda, asi que es la 1: los
            // identificadores se reparten por orden de creacion.
            Sonda sonda = correr(kernel, new Syscall.Kill(1), new Syscall.Ps());

            assertTrue(sonda.respuestas().get(0).ok());
            assertEquals(1, sonda.respuestas().get(0).number(),
                    "el proceso tenia un solo hilo vivo");
            assertTrue(sonda.respuestas().get(1).text().contains("terminado"),
                    "el proceso muerto tiene que aparecer como terminado");
        }

        @Test
        @DisplayName("un proceso no se mata a si mismo")
        void noSeMataAsiMismo() {
            Sonda sonda = correr(nucleo(), new Syscall.Kill(1));
            assertFalse(sonda.respuestas().get(0).ok());
            assertTrue(sonda.respuestas().get(0).text().contains("exit"),
                    "se le dice que use exit, que es la salida ordenada");
        }

        @Test
        @DisplayName("matar un proceso que no existe se avisa, no se ignora")
        void matarFantasma() {
            Sonda sonda = correr(nucleo(), new Syscall.Kill(40));
            assertFalse(sonda.respuestas().get(0).ok(),
                    "escribir mal un identificador no puede parecer que funciono");
            assertTrue(sonda.respuestas().get(0).text().contains("40"));
        }

        @Test
        @DisplayName("spawn crea el proceso y aparece en la lista")
        void spawn() {
            Sonda sonda = correr(nucleo(),
                    new Syscall.Spawn("hijo", "SET 0\nEXIT\n"),
                    new Syscall.Ps());

            assertTrue(sonda.respuestas().get(0).ok());
            assertEquals(2, sonda.respuestas().get(0).number(),
                    "spawn devuelve el identificador del hijo");
            assertTrue(sonda.respuestas().get(1).text().contains("hijo"));
        }

        @Test
        @DisplayName("spawn con codigo que no existe falla y no deja proceso a medias")
        void spawnInvalido() {
            Sonda sonda = correr(nucleo(),
                    new Syscall.Spawn("malo", "VOLAR 3\n"),
                    new Syscall.Ps());

            assertFalse(sonda.respuestas().get(0).ok());
            assertFalse(sonda.respuestas().get(1).text().contains("malo"),
                    "un programa que no se puede cargar no puede llegar a existir");
        }

        @Test
        @DisplayName("el estado del proceso se resume de sus hilos")
        void estadoDelProceso() {
            Kernel kernel = nucleo();
            kernel.createProcess("bucle", Assembler.load("bucle", BUCLE));
            Sonda sonda = correr(kernel, new Syscall.Kill(1), new Syscall.Ps());
            String tabla = sonda.respuestas().get(1).text();
            assertTrue(tabla.contains("ejecutando"),
                    "la sonda esta corriendo mientras pide la tabla");
            assertFalse(tabla.contains("nuevo"),
                    "ningun proceso puede quedarse en 'nuevo' toda la vida");
        }
    }

    @Nested
    @DisplayName("contabilidad")
    class Contabilidad {

        @Test
        @DisplayName("cada llamada cuesta un tic y se apunta")
        void coste() {
            Kernel kernel = nucleo();
            correr(kernel, new Syscall.Clock(), new Syscall.GetCwd(), new Syscall.Clock());

            assertEquals(3, kernel.syscalls().ticks(),
                    "atender tres llamadas son tres tics de nucleo");
            assertEquals(3, kernel.syscalls().total());
            assertEquals(2, kernel.syscalls().count(new Syscall.Clock().code()),
                    "el reloj se pidio dos veces");
        }

        @Test
        @DisplayName("el informe no repite codigos ni nombres")
        void codigos() {
            List<Syscall> todas = List.of(
                    new Syscall.Exit(0),
                    new Syscall.Write(1, "x"),
                    new Syscall.Read(0, 1),
                    new Syscall.Open("/a", Syscall.Mode.READ),
                    new Syscall.Close(3),
                    new Syscall.Stat("/a"),
                    new Syscall.List("/"),
                    new Syscall.Mkdir("/a"),
                    new Syscall.Unlink("/a"),
                    new Syscall.Chdir("/"),
                    new Syscall.GetCwd(),
                    new Syscall.Ps(),
                    new Syscall.Kill(2),
                    new Syscall.Mem(),
                    new Syscall.Sleep(1),
                    new Syscall.Clock(),
                    new Syscall.Spawn("x", "EXIT"),
                    new Syscall.FsInfo(),
                    new Syscall.FsCheck(),
                    new Syscall.GetMethod(),
                    new Syscall.SetMethod("indexado"));

            Set<Integer> codes = new HashSet<>();
            Set<String> names = new HashSet<>();
            for (Syscall call : todas) {
                assertTrue(codes.add(call.code()), "codigo repetido: " + call.code());
                assertTrue(names.add(call.name()), "nombre repetido: " + call.name());
            }
            for (int code : codes) {
                assertTrue(code > 0, "los codigos empiezan en uno");
            }
        }
    }

    @Nested
    @DisplayName("lo que no existe todavia en el sistema")
    class SinPiezas {

        @Test
        @DisplayName("sin sistema de ficheros la llamada falla en vez de reventar")
        void sinFicheros() {
            Kernel kernel = new Kernel(new RoundRobinScheduler(CUANTO),
                    MemorySetup.paged(4096, 256, 16));
            Sonda sonda = correr(kernel, new Syscall.Open("/leeme.txt", Syscall.Mode.READ));

            assertFalse(sonda.respuestas().get(0).ok());
            assertTrue(sonda.respuestas().get(0).text().contains("montado"),
                    "el error tiene que decir que falta montar, no un null");
        }

        @Test
        @DisplayName("un proceso que termina devuelve su codigo")
        void codigoDeSalida() {
            Kernel kernel = nucleo();
            correr(kernel, new Syscall.Exit(7));
            assertEquals(7, kernel.syscalls().exitStatus(1));
            assertFalse(kernel.syscalls().report().isEmpty());
        }
    }
}
