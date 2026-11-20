package pos.shell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import pos.alloc.BestFit;
import pos.fs.AllocationMethod;
import pos.fs.FileSystem;
import pos.fs.FsLayout;
import pos.kernel.ConsoleSignal;
import pos.kernel.Kernel;
import pos.kernel.KernelObserver;
import pos.mm.MemorySetup;
import pos.proc.Pcb;
import pos.sched.RoundRobinScheduler;

/**
 * Ctrl-Z: detener lo que hay en el primer plano y recuperar el aviso.
 *
 * La tecla no la puede atender quien la lee. Cuando la terminal espera a un
 * proceso esta dormida en el nucleo, asi que nadie mira el teclado mientras el
 * programa corre; el aviso se deja en un buzon y lo mira el nucleo al principio
 * de su vuelta. Estas pruebas comprueban justo eso: que el aviso llega, que el
 * trabajo queda detenido y que la terminal vuelve a pedir linea.
 *
 * Para disparar el aviso en el momento justo se usa el observador del nucleo, que
 * se llama al cerrar cada turno con el sistema quieto. Es lo mismo que hace una
 * tecla de verdad: llega de fuera y no cuando el programa quiere.
 */
@DisplayName("Ctrl-Z")
class CtrlZTest {

    private static final int FISICAS = 4096;
    private static final int LOGICAS = 256;
    private static final int PAGINA = 16;
    private static final int CUANTO = 6;
    private static final int BLOQUES = 256;
    private static final int BLOQUE = 128;

    /** Lo que queda de la sesion. */
    private record Sesion(String texto, Kernel kernel) {

        void debeContener(String esperado) {
            assertTrue(texto.contains(esperado),
                    () -> "no aparece '" + esperado + "' en la sesion:\n" + texto);
        }

        void debeTenerLinea(String linea) {
            assertTrue(List.of(texto.split("\n", -1)).contains(linea),
                    () -> "no hay ninguna linea '" + linea + "' en la sesion:\n" + texto);
        }

        /** Lo que la traza cuenta, que es donde se ve lo que hizo el nucleo. */
        List<String> traza() {
            return kernel.trace().lines();
        }
    }

    private static FileSystem imagen() {
        FileSystem fs = FileSystem.format(FsLayout.standard(BLOQUES, BLOQUE),
                new AllocationMethod.Indexed(), new BestFit());
        // Largo pero finito: da tiempo a que el trabajo este en el primer plano
        // sin que se acabe antes de que llegue la tecla.
        fs.create("/largo.pos",
                "SET 0\nloop: ADD 1\nSTORE 0\nCMP 60\nJZ fin\nJMP loop\nfin: EXIT\n");
        return fs;
    }

    /**
     * Una sesion con un observador que pulsa Ctrl-Z una sola vez.
     *
     * @param cuando dice en que momento se pulsa: con un trabajo en el primer
     *     plano o en la primera vuelta, haya o no trabajo
     */
    private static Sesion sesion(KernelObserver cuando, String... lineas) {
        Kernel kernel = new Kernel(new RoundRobinScheduler(CUANTO),
                MemorySetup.paged(FISICAS, LOGICAS, PAGINA));
        kernel.attachFilesystem(imagen());
        Terminal terminal = new Terminal(new Shell(kernel.syscalls()),
                Console.of(lineas), kernel::clock);
        Pcb proceso = kernel.createHostedProcess("terminal", terminal);
        terminal.shell().attach(proceso.threads().get(0));
        kernel.setObserver(cuando);
        kernel.run();
        return new Sesion(terminal.transcript(), kernel);
    }

    /** Un Ctrl-Z que llega en cuanto hay algo en el primer plano. */
    private static KernelObserver ctrlZConPrimerPlano() {
        AtomicBoolean pulsado = new AtomicBoolean();
        return kernel -> {
            if (!pulsado.get() && kernel.hasForeground()) {
                pulsado.set(true);
                kernel.postSignal(ConsoleSignal.PARAR);
            }
        };
    }

    @Nested
    @DisplayName("con un trabajo en el primer plano")
    class ConTrabajo {

        private Sesion conFg() {
            return sesion(ctrlZConPrimerPlano(),
                    "spawn largo /largo.pos &", "fg %1", "jobs", "fg %1", "echo fin");
        }

        @Test
        @DisplayName("el trabajo queda detenido y se ve en la tabla")
        void quedaDetenido() {
            Sesion s = conFg();
            s.debeTenerLinea("  [1]   p2   detenido  primero  largo");
        }

        @Test
        @DisplayName("la terminal recupera el aviso y sigue leyendo ordenes")
        void vuelveElAviso() {
            Sesion s = conFg();
            String texto = s.texto();
            int detenido = texto.indexOf("detenido  primero");
            int segundaEspera = texto.indexOf("al primer plano", texto.indexOf("al primer plano") + 1);
            int acaba = texto.indexOf("largo (primer plano): terminado");
            int fin = texto.lastIndexOf("\nfin");
            assertTrue(segundaEspera > detenido,
                    () -> "tras Ctrl-Z la sesion tiene que seguir leyendo ordenes:\n" + texto);
            assertTrue(acaba > segundaEspera,
                    () -> "el segundo fg espera de verdad a que el trabajo acabe:\n" + texto);
            assertTrue(fin > acaba,
                    () -> "la terminal solo vuelve al teclado cuando el trabajo acaba:\n" + texto);
        }

        @Test
        @DisplayName("el nucleo lo cuenta en la traza")
        void loCuentaLaTraza() {
            String traza = String.join("\n", conFg().traza());
            assertTrue(traza.contains("Ctrl-Z detiene p2"),
                    () -> "la traza tiene que decir que lo detuvo el teclado:\n" + traza);
            assertTrue(traza.contains("el primer plano queda libre"),
                    () -> "y que la terminal vuelve a su aviso:\n" + traza);
        }

        @Test
        @DisplayName("tras Ctrl-Z el trabajo se puede matar como cualquier otro")
        void sePuedeMatar() {
            Sesion s = sesion(ctrlZConPrimerPlano(),
                    "spawn largo /largo.pos &", "fg %1", "kill %1", "echo fin");
            s.debeContener("p2 terminado");
            s.debeContener("\nfin");
        }
    }

    @Nested
    @DisplayName("sin nada en el primer plano")
    class SinTrabajo {

        @Test
        @DisplayName("el aviso se ignora y la sesion sigue entera")
        void seIgnora() {
            AtomicInteger pulsaciones = new AtomicInteger();
            Sesion s = sesion(kernel -> {
                if (pulsaciones.getAndIncrement() == 0) {
                    kernel.postSignal(ConsoleSignal.PARAR);
                }
            }, "echo uno", "echo dos");
            assertTrue(pulsaciones.get() > 0, "el observador tiene que haberse llamado");
            s.debeContener("uno");
            s.debeContener("dos");
            String traza = String.join("\n", s.traza());
            assertTrue(traza.contains("Ctrl-Z sin trabajo en primer plano"),
                    () -> "la traza tiene que decir que no habia a quien parar:\n" + traza);
        }
    }
}
