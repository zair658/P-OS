package pos.kernel;

import pos.proc.Interpreter;
import pos.proc.State;
import pos.proc.StepResult;
import pos.proc.Tcb;
import pos.proc.TurnOutcome;

/**
 * El hilo real de Java que transporta a un hilo del sistema.
 *
 * Su unico trabajo es esperar el turno, ejecutar instrucciones mientras le
 * quede cuanto y devolver el turno. Toda la politica (a quien le toca) esta en
 * el nucleo; aqui solo hay el bucle de ejecucion y la red de seguridad: si algo
 * revienta, se devuelve el turno igualmente, porque dejar al nucleo esperando
 * seria colgar el sistema entero por culpa de un solo proceso.
 */
public final class ProcessThread implements Runnable {

    private final Kernel kernel;
    private final Tcb tcb;
    private final Interpreter interpreter;

    ProcessThread(Kernel kernel, Tcb tcb, Interpreter interpreter) {
        this.kernel = kernel;
        this.tcb = tcb;
        this.interpreter = interpreter;
    }

    @Override
    public void run() {
        try {
            while (tcb.state() != State.TERMINATED) {
                kernel.baton().awaitTurn(tcb);
                if (kernel.baton().isStopped()) {
                    return;
                }
                TurnOutcome outcome = runSlice();
                tcb.setOutcome(outcome);
                kernel.baton().returnTurn();
                if (outcome == TurnOutcome.EXITED) {
                    return;
                }
            }
        } catch (Throwable failure) {
            // Un fallo en un hilo no puede colgar al nucleo: se anota, se marca
            // el hilo como terminado y se devuelve el turno pase lo que pase.
            kernel.onThreadFault(tcb, failure);
            tcb.setOutcome(TurnOutcome.EXITED);
            try {
                kernel.baton().returnTurn();
            } catch (RuntimeException ignored) {
                // el turno ya estaba devuelto
            }
        }
    }

    private TurnOutcome runSlice() {
        while (true) {
            if (!kernel.baton().consumeBudget()) {
                return TurnOutcome.PREEMPTED;
            }
            StepResult result = interpreter.step(kernel, tcb);
            switch (result) {
                case CONTINUE -> {
                    // sigue gastando cuanto
                }
                case YIELDED -> {
                    return TurnOutcome.YIELDED;
                }
                case BLOCKED -> {
                    return TurnOutcome.BLOCKED;
                }
                case EXITED -> {
                    return TurnOutcome.EXITED;
                }
            }
        }
    }
}
