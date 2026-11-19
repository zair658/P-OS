package pos.proc;

import pos.base.PosFault;

/**
 * Interprete de una instruccion.
 *
 * Ejecuta exactamente una instruccion por llamada: ese es el grano con el que
 * se mide el tiempo y con el que se puede desalojar a un hilo a mitad de un
 * bucle. El contador de programa se incrementa antes de ejecutar, y las
 * instrucciones de salto lo sobrescriben, de modo que cada caso decide a donde
 * va despues.
 *
 * Toda condicion imposible (salto fuera del programa, direccion invalida) lanza
 * {@link PosFault}; el hilo que la ejecuta lo captura y devuelve el turno para
 * que el resto del sistema siga funcionando.
 *
 * Sobre LOCK: el contador de programa avanza antes de ejecutar, asi que un LOCK
 * que no consigue el cerrojo deja el pc en la instruccion siguiente. Eso es
 * correcto porque el interprete no es quien concede: cuando el duenyo libera,
 * el gestor de cerrojos transfiere la propiedad y solo entonces el nucleo
 * devuelve el hilo a la cola de listos. Es decir, la adquisicion se completa
 * mientras el hilo duerme, igual que un SLEEP se completa durmiendo, y al volver
 * a la CPU el hilo ya es duenyo y sigue por detras del LOCK. La alternativa,
 * retroceder el pc y reintentar, obligaria a distinguir "vengo de una concesion"
 * de "pido el cerrojo dos veces", y esa distincion no aporta nada al capitulo.
 *
 * SEM_WAIT funciona igual que LOCK: el permiso se cobra mientras el hilo duerme.
 * COND_WAIT no: aqui el hilo si se detiene en la instruccion y al despertar tiene
 * que volver a pedir el cerrojo, porque lo solto al dormirse. Esa readquisicion
 * la hace el nucleo antes de devolverle la CPU, no este bucle, y por eso el pc
 * puede quedarse detras del COND_WAIT sin ningun reintento visible.
 */
public final class Interpreter {

    public StepResult step(ExecutionHost host, Tcb tcb) {
        Pcb process = tcb.process();
        Program program = process.program();

        Instruction instruction = program.at(tcb.pc());
        tcb.setPc(tcb.pc() + 1);

        StepResult result = switch (instruction) {
            case Instruction.Set ins -> {
                tcb.registers().setAccumulator(ins.value());
                yield StepResult.CONTINUE;
            }
            case Instruction.Load ins -> {
                tcb.registers().setAccumulator(process.load(ins.address()));
                yield StepResult.CONTINUE;
            }
            case Instruction.Store ins -> {
                process.store(ins.address(), tcb.registers().accumulator());
                yield StepResult.CONTINUE;
            }
            case Instruction.Add ins -> {
                tcb.registers().setAccumulator(tcb.registers().accumulator() + ins.value());
                yield StepResult.CONTINUE;
            }
            case Instruction.Sub ins -> {
                tcb.registers().setAccumulator(tcb.registers().accumulator() - ins.value());
                yield StepResult.CONTINUE;
            }
            case Instruction.Cmp ins -> {
                tcb.registers().setEqualFlag(tcb.registers().accumulator() == ins.value());
                yield StepResult.CONTINUE;
            }
            case Instruction.Jump ins -> {
                jumpTo(tcb, ins.target(), "JMP");
                yield StepResult.CONTINUE;
            }
            case Instruction.JumpIfEqual ins -> {
                if (tcb.registers().equalFlag()) {
                    jumpTo(tcb, ins.target(), "JZ");
                }
                yield StepResult.CONTINUE;
            }
            case Instruction.JumpIfNotEqual ins -> {
                if (!tcb.registers().equalFlag()) {
                    jumpTo(tcb, ins.target(), "JNZ");
                }
                yield StepResult.CONTINUE;
            }
            case Instruction.Spawn ins -> {
                host.createThread(process, ins.target(), tcb);
                yield StepResult.CONTINUE;
            }
            case Instruction.Lock ins ->
                // Si el cerrojo tiene duenyo, el hilo queda en su cola y se pide
                // el bloqueo: el nucleo lo sacara de la cola de listos.
                host.acquireLock(tcb, ins.name()) ? StepResult.CONTINUE : StepResult.BLOCKED;
            case Instruction.Unlock ins -> {
                host.releaseLock(tcb, ins.name());
                yield StepResult.CONTINUE;
            }
            case Instruction.SemInit ins -> {
                host.initSemaphore(tcb, ins.name(), ins.initial());
                yield StepResult.CONTINUE;
            }
            case Instruction.SemWait ins ->
                // Mismo criterio que LOCK: el pc ya apunto a la instruccion
                // siguiente, y el permiso se completa mientras el hilo duerme.
                host.waitSemaphore(tcb, ins.name()) ? StepResult.CONTINUE : StepResult.BLOCKED;
            case Instruction.SemPost ins -> {
                host.postSemaphore(tcb, ins.name());
                yield StepResult.CONTINUE;
            }
            case Instruction.CondWait ins -> {
                // Soltar el cerrojo y dormir es un solo paso: lo hace el nucleo
                // al atender el bloqueo, no una instruccion UNLOCK aparte.
                host.waitCondition(tcb, ins.condition(), ins.lock());
                yield StepResult.BLOCKED;
            }
            case Instruction.CondSignal ins -> {
                host.signalCondition(tcb, ins.condition());
                yield StepResult.CONTINUE;
            }
            case Instruction.CondBroadcast ins -> {
                host.broadcastCondition(tcb, ins.condition());
                yield StepResult.CONTINUE;
            }
            case Instruction.Yield ignored -> StepResult.YIELDED;
            case Instruction.Sleep ins -> {
                tcb.setPendingSleep(ins.ticks());
                yield StepResult.BLOCKED;
            }
            case Instruction.Exit ignored -> StepResult.EXITED;
            case Instruction.Nop ignored -> StepResult.CONTINUE;
        };

        host.retireInstruction(tcb);
        return result;
    }

    private void jumpTo(Tcb tcb, int target, String mnemonic) {
        if (target < 0 || target >= tcb.process().program().size()) {
            throw new PosFault(tcb.id() + ": " + mnemonic + " a " + target
                    + " fuera del programa '" + tcb.process().program().name() + "'");
        }
        tcb.setPc(target);
    }
}
