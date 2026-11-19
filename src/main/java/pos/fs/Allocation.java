package pos.fs;

/**
 * Reparto de bloques de un fichero concreto, segun el metodo activo.
 *
 * Es lo que el FCB guarda de la asignacion: dos numeros que solo significan algo
 * junto al metodo que los escribio. El contiguo guarda donde empieza y cuanto
 * mide; el enlazado, donde empieza y cuantos bloques tiene la cadena; el
 * indexado, donde esta el bloque de indices y cuantos datos apunta.
 *
 * Mantenerlo como jerarquia sellada evita el error clasico de leer el campo de
 * un metodo con las reglas de otro: el compilador obliga a tratar los tres casos
 * en cada sitio que interpreta una asignacion.
 */
public sealed interface Allocation
        permits Allocation.Empty, Allocation.Contiguous, Allocation.Chained, Allocation.Indexed {

    /** Bloques de datos que ocupa el fichero. */
    int dataBlocks();

    /**
     * Fichero sin bloques: existe en la tabla de FCB pero no ocupa datos.
     *
     * Es el estado de un fichero recien creado y el de un directorio vacio. Se
     * representa aparte en vez de con una asignacion de longitud cero para que
     * ningun metodo tenga que tratar el cero como caso raro.
     */
    record Empty() implements Allocation {

        @Override
        public int dataBlocks() {
            return 0;
        }

        @Override
        public String toString() {
            return "sin bloques";
        }
    }

    /** Bloque contiguo: primer bloque y longitud. */
    record Contiguous(int first, int length) implements Allocation {

        public Contiguous {
            if (first < 0 || length <= 0) {
                throw new IllegalArgumentException("asignacion contigua invalida: inicio " + first
                        + ", longitud " + length);
            }
        }

        @Override
        public int dataBlocks() {
            return length;
        }

        @Override
        public String toString() {
            return "contiguo " + first + "+" + length;
        }
    }

    /** Cadena enlazada: primer bloque y numero de eslabones. */
    record Chained(int first, int dataBlocks) implements Allocation {

        public Chained {
            if (first < 0 || dataBlocks <= 0) {
                throw new IllegalArgumentException("asignacion enlazada invalida: inicio " + first
                        + ", bloques " + dataBlocks);
            }
        }

        @Override
        public String toString() {
            return "enlazada desde " + first + " (" + dataBlocks + " bloques)";
        }
    }

    /** Indice de un nivel: bloque de indices y numero de datos que apunta. */
    record Indexed(int indexBlock, int dataBlocks) implements Allocation {

        public Indexed {
            if (indexBlock < 0 || dataBlocks <= 0) {
                throw new IllegalArgumentException("asignacion indexada invalida: indice " + indexBlock
                        + ", bloques " + dataBlocks);
            }
        }

        @Override
        public String toString() {
            return "indexada indice " + indexBlock + " (" + dataBlocks + " bloques)";
        }
    }
}
