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
    private final Keys keys;
    private final LongSupplier clock;
    private final TextBuffer transcript = new TextBuffer();
    private final Output out;
    private final boolean echoLine;

    /**
     * Si el aviso lo pinta la terminal mientras espera, en vez de darlo por
     * escrito junto a la linea. Es lo propio del teclado: con guion, el aviso y
     * la linea se pintan juntos porque la linea ya esta; en vivo hay que ensenar
     * el aviso antes de que alguien escriba.
     */
    private final boolean livePrompt;

    /** Ya hay un aviso en pantalla esperando linea: no se repite en cada vuelta. */
    private boolean promptShown;

    /**
     * @param shell la cascara que ejecuta las ordenes
     * @param keys de donde llegan las lineas: un guion o el teclado
     * @param clock reloj del nucleo, para saber si la linea siguiente ya ha llegado
     */
    public Terminal(Shell shell, Keys keys, LongSupplier clock) {
        this(shell, keys, clock, null, true);
    }

    /**
     * Una terminal que ademas de acumular la sesion la va ensenyando.
     *
     * Con guion no hace falta: la sesion entera se imprime al final y queda
     * igual de legible. Con teclado de verdad, no: quien escribe espera ver la
     * respuesta de lo que acaba de escribir, no al cabo de veinte ordenes.
     *
     * @param screen donde va saliendo la sesion mientras ocurre, o null
     * @param echoLine si la terminal repite la linea escrita. Se apaga cuando el
     *     teclado ya la pinto al leerla, para no verla dos veces
     */
    public Terminal(Shell shell, Keys keys, LongSupplier clock, Output screen, boolean echoLine) {
        if (shell == null || keys == null || clock == null) {
            throw new IllegalArgumentException("la terminal necesita cascara, entrada y reloj");
        }
        this.shell = shell;
        this.keys = keys;
        this.clock = clock;
        this.echoLine = echoLine;
        // El aviso se pinta solo cuando hay pantalla y nadie va a repetir la
        // linea: si la linea no se repite, alguien tiene que ensenar donde se
        // escribe, y ese alguien es la terminal, que sabe cuando espera.
        this.livePrompt = screen != null && !echoLine;
        // Sin pantalla, la salida es el propio transcript: lo de siempre.
        this.out = screen == null ? transcript : tee(transcript, screen);
    }

    /** Escribe en dos destinos a la vez: la memoria de la sesion y la pantalla. */
    private static Output tee(Output first, Output second) {
        return chunk -> {
            first.print(chunk);
            second.print(chunk);
        };
    }

    /** Lo que se ha escrito en la pantalla, en orden. */
    public String transcript() {
        return transcript.text();
    }

    public Shell shell() {
        return shell;
    }

    public Keys keys() {
        return keys;
    }

    @Override
    public StepResult step(ExecutionHost host, Tcb thread) {
        if (!keys.hasNext()) {
            // Sin mas entrada la sesion no tiene sentido: se termina como un
            // programa que llega a su ultima instruccion, con codigo 0.
            shell.close();
            return StepResult.EXITED;
        }
        long now = clock.getAsLong();
        long due = keys.nextTick();
        if (due > now) {
            // Con teclado de verdad no hay nada que ejecutar todavia, asi que
            // este es el momento de pintar el aviso: se hace aqui, y no en el
            // hilo que lee, para que el aviso salga despues de la respuesta del
            // comando anterior y no antes.
            if (livePrompt && !promptShown) {
                out.print(shell.prompt());
                promptShown = true;
            }
            thread.setPendingSleep(due - now);
            return StepResult.BLOCKED;
        }

        Keys.Keystroke keystroke = keys.next();
        // La linea ya esta dentro: el aviso que hubiera en pantalla ha cumplido.
        promptShown = false;
        if (echoLine) {
            out.print(shell.prompt() + keystroke.text() + "\n");
        }
        shell.runLine(keystroke.text(), out);

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
        return "terminal(" + keys.pending() + " lineas pendientes)";
    }
}
