package pos.proc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pos.base.PosFault;

@DisplayName("El interprete ejecuta el repertorio y respeta la memoria del proceso")
class InterpreterTest {

    /** Nucleo minimo para probar el interprete sin planificador. */
    private static final class Host implements ExecutionHost {
        private final List<Integer> spawns = new ArrayList<>();
        private long clock;

        @Override
        public void retireInstruction(Tcb thread) {
            clock++;
            thread.addCpuTick();
        }

        @Override
        public void createThread(Pcb process, int entryPc, Tcb parent) {
            spawns.add(entryPc);
        }
    }

    private static Pcb proceso(Program program) {
        return ProcessFactory.newProcess(1, "prueba", program, 256, 0);
    }

    private static Tcb hilo(Pcb process) {
        return ProcessFactory.newThread(process, 1, 0);
    }

    private static long ejecutar(Interpreter interpreter, Host host, Tcb thread) {
        long steps = 0;
        while (steps < 10_000) {
            StepResult result = interpreter.step(host, thread);
            steps++;
            if (result != StepResult.CONTINUE) {
                return steps;
            }
        }
        throw new AssertionError("el programa no termino en 10000 pasos");
    }

    @Test
    @DisplayName("suma inmediatos y los deja en memoria")
    void sumaYGuarda() {
        Program program = Assembler.load("suma", """
                SET 0
                ADD 1
                ADD 2
                ADD 3
                ADD 4
                ADD 5
                STORE 0
                EXIT
                """);
        Pcb process = proceso(program);
        Host host = new Host();

        long steps = ejecutar(new Interpreter(), host, hilo(process));

        assertEquals(8, steps);
        assertEquals(15, process.load(0));
    }

    @Test
    @DisplayName("recorre un bucle con CMP y JZ hasta la condicion de salida")
    void recorreBucle() {
        Program program = Assembler.load("bucle", """
                SET 0
                loop: ADD 2
                      STORE 1
                      CMP 10
                      JZ fin
                      JMP loop
                fin:  EXIT
                """);
        Pcb process = proceso(program);
        Host host = new Host();

        ejecutar(new Interpreter(), host, hilo(process));

        assertEquals(10, process.load(1));
    }

    @Test
    @DisplayName("YIELD y SLEEP no siguen ejecutando en el mismo paso")
    void cedeYDuerme() {
        Program cedido = Assembler.load("cede", "YIELD\nEXIT\n");
        Pcb process = proceso(cedido);
        Tcb thread = hilo(process);
        Interpreter interpreter = new Interpreter();
        Host host = new Host();

        assertEquals(StepResult.YIELDED, interpreter.step(host, thread));
        assertEquals(StepResult.EXITED, interpreter.step(host, thread));

        Program dormido = Assembler.load("duerme", "SLEEP 7\nEXIT\n");
        Pcb otro = proceso(dormido);
        Tcb otroHilo = hilo(otro);
        Interpreter segundo = new Interpreter();

        assertEquals(StepResult.BLOCKED, segundo.step(host, otroHilo));
        assertEquals(7, otroHilo.pendingSleep());
    }

    @Test
    @DisplayName("SPAWN pide un hilo nuevo en el destino del salto")
    void spawnPideHilo() {
        Program program = Assembler.load("lanza", """
                SPAWN trabajo
                EXIT
                trabajo: SET 1
                         EXIT
                """);
        Pcb process = proceso(program);
        Host host = new Host();

        new Interpreter().step(host, hilo(process));

        assertEquals(List.of(2), host.spawns);
    }

    @Test
    @DisplayName("escribir fuera del espacio de direcciones es un fallo, no un valor")
    void rechazaDireccionFueraDeRango() {
        Program program = Assembler.load("malo", "STORE 300\nEXIT\n");
        Pcb process = proceso(program);

        PosFault fault = assertThrows(PosFault.class,
                () -> new Interpreter().step(new Host(), hilo(process)));

        assertTrue(fault.getMessage().contains("300"), fault.getMessage());
    }

    @Test
    @DisplayName("un salto fuera del programa se detecta al ejecutarlo")
    void rechazaSaltoFueraDelPrograma() {
        // Se construye a mano: el cargador no deja resolver una etiqueta inexistente.
        Program program = new Program("roto", List.of(new Instruction.Jump(999)));
        Pcb process = proceso(program);

        PosFault fault = assertThrows(PosFault.class,
                () -> new Interpreter().step(new Host(), hilo(process)));

        assertTrue(fault.getMessage().contains("999"), fault.getMessage());
    }
}
