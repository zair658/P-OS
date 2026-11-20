package pos.sched;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pos.base.PosFault;
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

    /** Hilo con rafaga y prioridad declaradas, para las politicas que las miran. */
    private static Tcb hilo(int processId, long arrival, int burst, int priority) {
        Tcb thread = hilo(processId, arrival);
        thread.process().setEstimatedBurst(burst);
        thread.process().setPriority(priority);
        return thread;
    }

    @Test
    @DisplayName("SJF elige la rafaga estimada mas corta aunque este al final")
    void sjfEligeLaRafagaMasCorta() {
        Tcb largo = hilo(1, 0, 40, 0);
        Tcb medio = hilo(2, 0, 12, 0);
        Tcb corto = hilo(3, 0, 3, 0);
        SjfScheduler scheduler = new SjfScheduler();

        SchedulingDecision decision = scheduler.pick(List.of(largo, medio, corto), 0);

        assertEquals(corto, decision.thread());
        assertEquals(SchedulingDecision.NO_LIMIT, decision.slice(),
                "SJF no expropia: cuanto sin limite");
        assertEquals("SJF", scheduler.name());
    }

    @Test
    @DisplayName("SJF desempata por llegada y luego por proceso cuando la rafaga coincide")
    void sjfDesempata() {
        SjfScheduler scheduler = new SjfScheduler();

        // Misma rafaga: decide haber llegado antes, no el identificador.
        Tcb llegoAntes = hilo(9, 0, 8, 0);
        Tcb llegoDespues = hilo(1, 3, 8, 0);
        assertEquals(llegoAntes, scheduler.pick(List.of(llegoDespues, llegoAntes), 3).thread());

        // Misma llegada y misma rafaga: decide el identificador de proceso.
        Tcb segundoProceso = hilo(2, 0, 8, 0);
        Tcb primeroProceso = hilo(1, 0, 8, 0);
        assertEquals(primeroProceso, scheduler.pick(List.of(segundoProceso, primeroProceso),
                0).thread());
    }

    @Test
    @DisplayName("prioridades: gana el numero mas bajo, no el primero de la cola")
    void prioridadGanaLaMasAlta() {
        Tcb flojo = hilo(1, 0, 8, 5);
        Tcb urgente = hilo(2, 0, 8, 0);
        Tcb medio = hilo(3, 0, 8, 2);
        PriorityScheduler scheduler = new PriorityScheduler(4);

        SchedulingDecision decision = scheduler.pick(List.of(flojo, medio, urgente), 0);

        assertEquals(urgente, decision.thread());
        assertEquals(4, decision.slice(), "entre iguales se reparte por turnos");
        assertEquals("Prioridad(4)", scheduler.name());
    }

    @Test
    @DisplayName("prioridades: la urgencia puede mas que haber llegado antes")
    void prioridadVenceALaLlegada() {
        Tcb viejo = hilo(1, 0, 8, 7);
        Tcb nuevo = hilo(2, 5, 8, 1);

        assertEquals(nuevo, new PriorityScheduler(2).pick(List.of(viejo, nuevo), 5).thread());
    }

    @Test
    @DisplayName("prioridades: a igual prioridad atiende al que llego antes")
    void prioridadDesempataPorLlegada() {
        Tcb tarde = hilo(1, 9, 8, 3);
        Tcb pronto = hilo(2, 1, 8, 3);

        assertEquals(pronto, new PriorityScheduler(3).pick(List.of(tarde, pronto), 10).thread());
    }

    @Test
    @DisplayName("prioridades rechaza un cuanto no positivo")
    void prioridadCuantoInvalido() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PriorityScheduler(0));
        assertTrue(error.getMessage().contains("cuanto"), error.getMessage());
    }

    @Test
    @DisplayName("el proceso nace con la rafaga de su programa y prioridad cero")
    void procesoDeclaraRafagaYPrioridad() {
        Pcb process = hilo(1, 0).process();

        assertEquals(2, process.estimatedBurst(),
                "sin bucles, el tamano del programa es la rafaga real");
        assertEquals(0, process.priority(), "quien no dice nada no adelanta a nadie");
    }

    @Test
    @DisplayName("rafaga y prioridad fuera de rango se rechazan al declararlas")
    void valoresDePlanificacionInvalidos() {
        Pcb process = hilo(1, 0).process();

        assertThrows(PosFault.class, () -> process.setEstimatedBurst(0));
        assertThrows(PosFault.class, () -> process.setPriority(-1));
    }

    @Test
    @DisplayName("sin listos, SJF y prioridades tampoco deciden")
    void politicasNuevasSinListos() {
        assertNull(new SjfScheduler().pick(List.of(), 0));
        assertNull(new PriorityScheduler(4).pick(List.of(), 0));
    }
}
