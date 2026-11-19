package pos.proc;

/**
 * Registros de un hilo.
 *
 * La maquina que interpreta los programas es deliberadamente minima: un
 * acumulador, una bandera de comparacion y una marca de fallo. Todo lo que no
 * cabe aqui (contador de programa, estado del hilo) vive en el TCB, para que el
 * planificador pueda cambiar de hilo guardando y restaurando un unico objeto.
 */
public final class Registers {

    private int accumulator;
    private boolean equalFlag;

    public int accumulator() {
        return accumulator;
    }

    public void setAccumulator(int value) {
        this.accumulator = value;
    }

    public boolean equalFlag() {
        return equalFlag;
    }

    public void setEqualFlag(boolean value) {
        this.equalFlag = value;
    }

    /** Deja los registros como al principio, para poder reiniciar un hilo. */
    public void clear() {
        this.accumulator = 0;
        this.equalFlag = false;
    }

    @Override
    public String toString() {
        return "acc=" + accumulator + " eq=" + (equalFlag ? 1 : 0);
    }
}
