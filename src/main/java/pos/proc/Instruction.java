package pos.proc;

/**
 * Repertorio de instrucciones de P-OS.
 *
 * Es un conjunto cerrado (una interfaz sellada) para que el interprete pueda
 * recorrerlo con un switch exhaustivo: si algun dia se anade una instruccion y
 * se olvida tratarla, el compilador falla en vez de dejar un caso mudo en
 * tiempo de ejecucion.
 *
 * Las direcciones son indices dentro del espacio de direcciones del proceso, y
 * los saltos son indices absolutos dentro del programa, resueltos por
 * {@link Assembler} en el momento de cargar el texto.
 */
public sealed interface Instruction {

    /** Texto legible, el que se muestra al listar un programa cargado. */
    String text();

    /** Carga un valor inmediato en el acumulador. */
    record Set(int value) implements Instruction {
        @Override
        public String text() {
            return "SET " + value;
        }
    }

    /** Carga en el acumulador la unidad de memoria indicada. */
    record Load(int address) implements Instruction {
        @Override
        public String text() {
            return "LOAD " + address;
        }
    }

    /** Guarda el acumulador en la unidad de memoria indicada. */
    record Store(int address) implements Instruction {
        @Override
        public String text() {
            return "STORE " + address;
        }
    }

    /** Suma un inmediato al acumulador. */
    record Add(int value) implements Instruction {
        @Override
        public String text() {
            return "ADD " + value;
        }
    }

    /** Resta un inmediato al acumulador. */
    record Sub(int value) implements Instruction {
        @Override
        public String text() {
            return "SUB " + value;
        }
    }

    /** Compara el acumulador con un inmediato y deja la bandera en el registro. */
    record Cmp(int value) implements Instruction {
        @Override
        public String text() {
            return "CMP " + value;
        }
    }

    /** Salto incondicional. */
    record Jump(int target) implements Instruction {
        @Override
        public String text() {
            return "JMP " + target;
        }
    }

    /** Salta si la ultima comparacion dio igual. */
    record JumpIfEqual(int target) implements Instruction {
        @Override
        public String text() {
            return "JZ " + target;
        }
    }

    /** Salta si la ultima comparacion dio distinto. */
    record JumpIfNotEqual(int target) implements Instruction {
        @Override
        public String text() {
            return "JNZ " + target;
        }
    }

    /** Crea un hilo nuevo dentro del mismo proceso, que empieza en el destino. */
    record Spawn(int target) implements Instruction {
        @Override
        public String text() {
            return "SPAWN " + target;
        }
    }

    /** Entrega el turno voluntariamente, sin bloquearse. */
    record Yield() implements Instruction {
        @Override
        public String text() {
            return "YIELD";
        }
    }

    /** Se bloquea durante un numero de ticks. */
    record Sleep(int ticks) implements Instruction {
        @Override
        public String text() {
            return "SLEEP " + ticks;
        }
    }

    /** Termina el hilo. */
    record Exit() implements Instruction {
        @Override
        public String text() {
            return "EXIT";
        }
    }

    /** No hace nada; sirve para etiquetar posiciones. */
    record Nop() implements Instruction {
        @Override
        public String text() {
            return "NOP";
        }
    }
}
