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

    /**
     * Toma un cerrojo por su nombre.
     *
     * Si esta libre se lo queda y sigue; si lo tiene otro, el hilo queda
     * bloqueado al final de la cola de ese cerrojo. El nombre es sensible a
     * mayusculas: 'contador' y 'Contador' son cerrojos distintos, igual que en
     * un sistema real son objetos distintos.
     */
    record Lock(String name) implements Instruction {
        @Override
        public String text() {
            return "LOCK " + name;
        }
    }

    /** Libera un cerrojo y se lo cede al primero que lo espera. */
    record Unlock(String name) implements Instruction {
        @Override
        public String text() {
            return "UNLOCK " + name;
        }
    }

    /**
     * Crea un semaforo con su valor inicial.
     *
     * Es la unica instruccion que hay que ejecutar antes de usar un semaforo, y
     * solo una vez. Que la creacion sea explicita evita que una errata en un
     * nombre cree un semaforo nuevo con valor cero y deje al hilo durmiendo para
     * siempre sin ningun mensaje.
     */
    record SemInit(String name, int initial) implements Instruction {
        @Override
        public String text() {
            return "SEM_INIT " + name + " " + initial;
        }
    }

    /**
     * Toma un permiso del semaforo (operacion P).
     *
     * Con el contador a cero el hilo queda bloqueado al final de la cola. No hay
     * dueño: cualquier hilo puede devolver el permiso, y por eso un semaforo sirve
     * para pasar de uno a otro, no solo para protegerse de los demas.
     */
    record SemWait(String name) implements Instruction {
        @Override
        public String text() {
            return "SEM_WAIT " + name;
        }
    }

    /** Devuelve un permiso al semaforo (operacion V), o despierta al primero. */
    record SemPost(String name) implements Instruction {
        @Override
        public String text() {
            return "SEM_POST " + name;
        }
    }

    /**
     * Espera sobre una condicion, soltando el cerrojo mientras duerme.
     *
     * Lleva dos nombres porque la condicion no sirve sola: siempre va atada a un
     * cerrojo, y el hilo tiene que tenerlo en ese momento. El hilo duerme hasta
     * que alguien señale la condicion, y entonces vuelve a pedir el cerrojo antes
     * de seguir. Si mientras tanto se lo ha llevado otro, sigue esperando.
     */
    record CondWait(String condition, String lock) implements Instruction {
        @Override
        public String text() {
            return "COND_WAIT " + condition + " " + lock;
        }
    }

    /**
     * Despierta al primero que espera en la condicion.
     *
     * El que señala tiene que tener el cerrojo de la condicion, para que no pueda
     * colarse un cambio de estado entre comprobar la condicion y avisar. Si no
     * espera nadie, la señal se pierde: eso es lo que distingue una condicion de
     * un semaforo y la razon de que el candidato se compruebe en un bucle.
     */
    record CondSignal(String condition) implements Instruction {
        @Override
        public String text() {
            return "COND_SIGNAL " + condition;
        }
    }

    /** Despierta a todos los que esperan en la condicion. */
    record CondBroadcast(String condition) implements Instruction {
        @Override
        public String text() {
            return "COND_BROADCAST " + condition;
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
