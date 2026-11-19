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
