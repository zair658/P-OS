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
        private final List<String> tomados = new ArrayList<>();
        private final List<String> soltados = new ArrayList<>();
        private final List<String> negados = new ArrayList<>();
        private final List<String> semaforosCreados = new ArrayList<>();
        private final List<String> condicionesUsadas = new ArrayList<>();
        private final List<String> maximos = new ArrayList<>();
        private int libres = 10;
        private Tcb duenyo;
        private int permisos;
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

        @Override
        public boolean acquireLock(Tcb thread, String name) {
            if (duenyo == null) {
                duenyo = thread;
                tomados.add(name);
                return true;
            }
            negados.add(name);
            return false;
        }

        @Override
        public void releaseLock(Tcb thread, String name) {
            if (duenyo == thread) {
                duenyo = null;
                soltados.add(name);
            }
        }

        @Override
        public void initSemaphore(Tcb thread, String name, int initial) {
            semaforosCreados.add(name + "=" + initial);
            permisos = initial;
        }

        @Override
        public boolean waitSemaphore(Tcb thread, String name) {
            if (permisos > 0) {
                permisos--;
                return true;
            }
            return false;
        }

        @Override
        public void postSemaphore(Tcb thread, String name) {
            permisos++;
        }

        @Override
        public void waitCondition(Tcb thread, String condition, String lock) {
            condicionesUsadas.add(condition + "/" + lock);
        }

        @Override
        public Tcb signalCondition(Tcb thread, String condition) {
            condicionesUsadas.add("signal " + condition);
            return null;
        }

        @Override
        public int broadcastCondition(Tcb thread, String condition) {
            condicionesUsadas.add("broadcast " + condition);
            return 0;
        }

        @Override
        public void claimResource(Tcb thread, String resource, int maximum) {
            maximos.add(resource + "<=" + maximum);
        }

        @Override
        public boolean takeResource(Tcb thread, String resource, int amount) {
            if (libres < amount) {
                negados.add(resource + "x" + amount);
                return false;
            }
            libres -= amount;
            tomados.add(resource + "x" + amount);
            return true;
        }

        @Override
        public void giveResource(Tcb thread, String resource, int amount) {
            libres += amount;
            soltados.add(resource + "x" + amount);
        }
    }

    private static Pcb proceso(Program program) {
        // El interprete toca memoria, asi que el proceso necesita un espacio real.
        return proceso(program, new pos.mm.MemoryScheme.Contiguous());
    }

    /** El mismo programa con el esquema de memoria que se indique. */
    private static Pcb proceso(Program program, pos.mm.MemoryScheme scheme) {
        Pcb pcb = ProcessFactory.newProcess(1, "prueba", program, 256, 0);
        pcb.attachSpace(scheme.attach(new pos.phys.PhysicalMemory(4096),
                new pos.alloc.BestFit(), 1, "prueba", 256));
        return pcb;
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
    @DisplayName("LOCK sobre un cerrojo libre sigue adelante y UNLOCK avisa al nucleo")
    void cerrojoLibreNoBloquea() {
        Program program = Assembler.load("critica", """
                LOCK contador
                SET 1
                STORE 0
                UNLOCK contador
                EXIT
                """);
        Pcb process = proceso(program);
        Host host = new Host();
        Tcb thread = hilo(process);
        Interpreter interpreter = new Interpreter();

        for (int i = 0; i < 4; i++) {
            assertEquals(StepResult.CONTINUE, interpreter.step(host, thread), "paso " + i);
        }

        assertEquals(List.of("contador"), host.tomados);
        assertEquals(List.of("contador"), host.soltados);
        assertEquals(1, process.load(0));
    }

    @Test
    @DisplayName("si el cerrojo esta ocupado el hilo pide bloqueo en vez de colarse")
    void cerrojoOcupadoPideBloqueo() {
        Program program = Assembler.load("critica", """
                LOCK contador
                SET 1
                STORE 0
                EXIT
                """);
        Pcb process = proceso(program);
        Host host = new Host();
        // Otro hilo se ha quedado con el cerrojo antes.
        host.duenyo = hilo(process);

        StepResult result = new Interpreter().step(host, hilo(process));

        assertEquals(StepResult.BLOCKED, result);
        assertEquals(List.of("contador"), host.negados);
        assertTrue(host.tomados.isEmpty(), "no se queda con nada");
        assertEquals(0, process.load(0), "y desde luego no entra en la seccion critica");
    }

    @Test
    @DisplayName("SEM_WAIT cobra el permiso y pide bloqueo cuando ya no quedan")
    void elSemaforoDaUnPermisoYBloqueaAlSegundo() {
        Program program = Assembler.load("turno", """
                SEM_INIT turno 1
                SEM_WAIT turno
                SET 1
                STORE 0
                EXIT
                """);
        Pcb process = proceso(program);
        Host host = new Host();
        Tcb thread = hilo(process);
        Interpreter interpreter = new Interpreter();

        assertEquals(StepResult.CONTINUE, interpreter.step(host, thread));
        assertEquals(List.of("turno=1"), host.semaforosCreados);
        // El unico permiso se lo lleva el primero: sigue sin bloquearse.
        assertEquals(StepResult.CONTINUE, interpreter.step(host, thread));
        assertEquals(0, host.permisos);

        // El mismo programa para otro hilo: ya no queda permiso.
        Tcb segundo = hilo(process);
        segundo.setPc(1);
        assertEquals(StepResult.BLOCKED, interpreter.step(host, segundo));
    }

    @Test
    @DisplayName("SEM_POST devuelve el permiso sin bloquear a nadie")
    void elSemaforoSeDevuelveConSemPost() {
        Program program = Assembler.load("turno", """
                SEM_INIT turno 0
                SEM_POST turno
                EXIT
                """);
        Pcb process = proceso(program);
        Host host = new Host();
        Tcb thread = hilo(process);
        Interpreter interpreter = new Interpreter();

        assertEquals(StepResult.CONTINUE, interpreter.step(host, thread));
        assertEquals(StepResult.CONTINUE, interpreter.step(host, thread));
        assertEquals(1, host.permisos);
    }

    @Test
    @DisplayName("COND_WAIT bloquea siempre y llega con condicion y cerrojo")
    void laCondicionSiempreBloquea() {
        Program program = Assembler.load("avisa", """
                COND_WAIT hay_datos contador
                EXIT
                """);
        Pcb process = proceso(program);
        Host host = new Host();

        assertEquals(StepResult.BLOCKED, new Interpreter().step(host, hilo(process)));
        assertEquals(List.of("hay_datos/contador"), host.condicionesUsadas);
    }

    @Test
    @DisplayName("RES_CLAIM avisa al nucleo y deja el ejecutor donde estaba")
    void declaraMaximoDeRecurso() {
        Program program = Assembler.load("pide", """
                RES_CLAIM impresora 2
                EXIT
                """);
        Pcb process = proceso(program);
        Host host = new Host();

        assertEquals(StepResult.CONTINUE, new Interpreter().step(host, hilo(process)));
        assertEquals(List.of("impresora<=2"), host.maximos);
    }

    @Test
    @DisplayName("RES_TAKE sigue adelante cuando el banquero concede")
    void tomaRecursoConcedido() {
        Program program = Assembler.load("toma", """
                RES_TAKE impresora 1
                EXIT
                """);
        Pcb process = proceso(program);
        Host host = new Host();

        assertEquals(StepResult.CONTINUE, new Interpreter().step(host, hilo(process)));
        assertEquals(List.of("impresorax1"), host.tomados);
        assertEquals(9, host.libres);
    }

    @Test
    @DisplayName("si el banquero no concede, el hilo pide bloqueo como con un cerrojo")
    void tomaRecursoDenegadoPideBloqueo() {
        Program program = Assembler.load("toma", """
                RES_TAKE impresora 1
                EXIT
                """);
        Pcb process = proceso(program);
        Host host = new Host();
        host.libres = 0;

        assertEquals(StepResult.BLOCKED, new Interpreter().step(host, hilo(process)));
        assertEquals(List.of("impresorax1"), host.negados);
        assertTrue(host.tomados.isEmpty());
    }

    @Test
    @DisplayName("RES_GIVE devuelve las instancias sin bloquear a nadie")
    void devuelveRecurso() {
        Program program = Assembler.load("devuelve", """
                RES_CLAIM impresora 2
                RES_TAKE impresora 2
                RES_GIVE impresora 2
                EXIT
                """);
        Pcb process = proceso(program);
        Host host = new Host();
        Interpreter interpreter = new Interpreter();

        Tcb thread = hilo(process);
        assertEquals(StepResult.CONTINUE, interpreter.step(host, thread));
        assertEquals(StepResult.CONTINUE, interpreter.step(host, thread));
        assertEquals(8, host.libres);
        assertEquals(StepResult.CONTINUE, interpreter.step(host, thread));
        assertEquals(10, host.libres);
        assertEquals(List.of("impresorax2"), host.soltados);
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
