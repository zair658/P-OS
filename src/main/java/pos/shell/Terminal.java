package pos.shell;

import java.util.function.LongSupplier;

import pos.proc.ExecutionHost;
import pos.proc.HostedTask;
import pos.proc.StepResult;
import pos.proc.Tcb;

/**
 * El proceso terminal: un hilo nativo que lee del teclado y ejecuta ordenes.
 *
 * No tiene un programa interpretado porque su trabajo no cabe en el juego de
 * instrucciones: maneja cadenas, tablas y llamadas al sistema con argumentos
 * distintos cada vez. Lo que si tiene es el mismo contrato que el interprete: por
 * cada paso devuelve si sigue, si se bloquea o si termina, y el nucleo lo trata
 * igual que a cualquier otro hilo.
 *
 * El paso hace como mucho una linea. Si la terminal ejecutara toda la sesion de
 * golpe no habria concurrencia que ensenar: los demas procesos no correrian hasta
 * que se cerrara la sesion, y el sistema entero seria un unico hilo disfrazado.
 *
 * Cuando la siguiente linea no ha llegado todavia se pide dormir hasta su tic. Se
 * usa el bloqueo por tiempo del nucleo, no una espera activa, para que el proceso
 * suelte el turno y el planificador pueda elegir a otro.
 */
public final class Terminal implements HostedTask {

    private final Shell shell;
    private final Console console;
    private final LongSupplier clock;
    private final TextBuffer transcript = new TextBuffer();

    /**
     * @param shell la cascara que ejecuta las ordenes
     * @param console el guion de lineas que llegan al teclado
     * @param clock reloj del nucleo, para saber si la linea siguiente ya ha llegado
     */
    public Terminal(Shell shell, Console console, LongSupplier clock) {
        if (shell == null || console == null || clock == null) {
            throw new IllegalArgumentException("la terminal necesita cascara, consola y reloj");
        }
        this.shell = shell;
        this.console = console;
        this.clock = clock;
    }

    /** Lo que se ha escrito en la pantalla, en orden. */
    public String transcript() {
        return transcript.text();
    }

    public Shell shell() {
        return shell;
    }

    public Console console() {
        return console;
    }

    @Override
    public StepResult step(ExecutionHost host, Tcb thread) {
        if (!console.hasNext()) {
            // Sin mas entrada la sesion no tiene sentido: se termina como un
            // programa que llega a su ultima instruccion, con codigo 0.
            shell.close();
            return StepResult.EXITED;
        }
        long now = clock.getAsLong();
        long due = console.nextTick();
        if (due > now) {
            thread.setPendingSleep(due - now);
            return StepResult.BLOCKED;
        }

        Console.Keystroke keystroke = console.next();
        transcript.print(shell.prompt() + keystroke.text() + "\n");
        shell.runLine(keystroke.text(), transcript);

        // Ejecutar una linea cuesta lo mismo que una instruccion: si no, la
        // terminal tendria un turno infinito y la cuota no valdria para nadie.
        host.retireInstruction(thread);

        if (shell.drainWait()) {
            // El hilo ya viene apuntado como esperando a ese proceso desde la
            // llamada al sistema; aqui solo se le dice al planificador que lo
            // saque de la cola. Sin hora de despertar: lo despertara el fin del
            // proceso, no el reloj.
            return StepResult.BLOCKED;
        }

        long sleep = shell.drainSleep();
        if (sleep > 0) {
            thread.setPendingSleep(sleep);
            return StepResult.BLOCKED;
        }
        if (shell.exitRequested()) {
            return StepResult.EXITED;
        }
        // Cede el turno aunque le queden lineas: asi los demas procesos avanzan
        // entre orden y orden y la traza ensena el reparto de verdad.
        return StepResult.YIELDED;
    }

    @Override
    public String toString() {
        return "terminal(" + console.pending() + " lineas pendientes)";
    }
}
