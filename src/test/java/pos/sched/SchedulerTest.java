package pos.sched;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pos.proc.Pcb;
import pos.proc.ProcessFactory;
import pos.proc.Program;
import pos.proc.Instruction;
import pos.proc.Tcb;

@DisplayName("Los planificadores eligen de forma determinista")
class SchedulerTest {

    private static Tcb hilo(int processId, long arrival) {
        Program program = new Program("p" + processId, List.of(new Instruction.Nop(),
                new Instruction.Exit()));
        Pcb process = ProcessFactory.newProcess(processId, "proc" + processId, program, 64, arrival);
        return ProcessFactory.newThread(process, 1, arrival);
    }

    @Test
    @DisplayName("sin listos no hay decision")
    void sinListosNoHayDecision() {
        assertNull(new FcfsScheduler().pick(List.of(), 0));
        assertNull(new RoundRobinScheduler(3).pick(List.of(), 0));
    }

    @Test
    @DisplayName("FCFS elige al que llego antes aunque este al final de la lista")
    void fcfsEligeAlPrimeroEnLlegar() {
        Tcb tarde = hilo(1, 10);
        Tcb pronto = hilo(2, 2);
        Tcb medio = hilo(3, 5);

        SchedulingDecision decision = new FcfsScheduler().pick(List.of(tarde, pronto, medio), 12);

        assertNotNull(decision);
        assertEquals(pronto, decision.thread());
        assertEquals(SchedulingDecision.NO_LIMIT, decision.slice(),
                "FCFS no expropia: cuanto sin limite");
    }

    @Test
    @DisplayName("FCFS desempata por proceso y luego por hilo cuando la llegada coincide")
    void fcfsDesempata() {
        Tcb segundoProceso = hilo(2, 4);
        Tcb primeroProceso = hilo(1, 4);

        SchedulingDecision decision = new FcfsScheduler()
                .pick(List.of(segundoProceso, primeroProceso), 4);

        assertEquals(primeroProceso, decision.thread());
    }

    @Test
    @DisplayName("Round Robin toma el primero de la cola y aplica su cuanto")
    void roundRobinTomaElPrimero() {
        Tcb primero = hilo(1, 0);
        Tcb segundo = hilo(2, 0);
        RoundRobinScheduler scheduler = new RoundRobinScheduler(4);

        SchedulingDecision decision = scheduler.pick(List.of(primero, segundo), 0);

        assertEquals(primero, decision.thread());
        assertEquals(4, decision.slice());
        assertEquals("RoundRobin(4)", scheduler.name());
    }

    @Test
    @DisplayName("un cuanto no positivo se rechaza al construir el planificador")
    void cuantoInvalido() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new RoundRobinScheduler(0));
        assertTrue(error.getMessage().contains("cuanto"), error.getMessage());
    }

    @Test
    @DisplayName("la decision no acepta un cuanto de cero")
    void decisionInvalida() {
        Tcb thread = hilo(1, 0);
        assertThrows(IllegalArgumentException.class,
                () -> new SchedulingDecision(thread, 0, "malo"));
    }
}
