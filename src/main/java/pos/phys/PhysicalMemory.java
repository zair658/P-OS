package pos.phys;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import pos.base.FreeSpan;

/**
 * Memoria fisica real del sistema, guardada en un arreglo de bytes de la maquina
 * virtual de Java.
 *
 * Este modulo es el unico dueno de dos datos: el contenido de cada unidad y el
 * proceso propietario de cada unidad. Nadie mas los modifica; los demas modulos
 * preguntan. De ahi que la lista de huecos libres no se guarde en ningun sitio:
 * se calcula a partir del mapa de propietarios. Asi no puede desincronizarse
 * nunca y la fusion de huecos al liberar sale gratis.
 *
 * Una unidad es la unidad logica del sistema. No es memoria del proceso
 * anfitrion: es un byte de este arreglo.
 */
public final class PhysicalMemory {

    /** Valor de propietario que indica unidad libre. */
    public static final int FREE = -1;

    private final byte[] data;
    private final int[] owner;

    /**
     * Crea una memoria fisica del tamano indicado, con todas las unidades libres
     * y a cero.
     *
     * @param sizeUnits numero total de unidades
     */
    public PhysicalMemory(int sizeUnits) {
        if (sizeUnits <= 0) {
            throw new IllegalArgumentException("el tamano de la memoria debe ser positivo: " + sizeUnits);
        }
        this.data = new byte[sizeUnits];
        this.owner = new int[sizeUnits];
        Arrays.fill(this.owner, FREE);
    }

    /** Numero total de unidades de la memoria. */
    public int size() {
        return data.length;
    }

    /** Comprueba si todo el tramo indicado esta libre. */
    public boolean isFree(int start, int length) {
        checkRange(start, length);
        for (int i = start; i < start + length; i++) {
            if (owner[i] != FREE) {
                return false;
            }
        }
        return true;
    }

    /** Propietario de una unidad, o {@link #FREE} si esta libre. */
    public int ownerAt(int address) {
        checkAddress(address);
        return owner[address];
    }

    /**
     * Asigna un tramo a un proceso.
     *
     * El contenido se pone a cero: un proceso nunca ve datos del anterior, igual
     * que ocurre en un sistema real cuando el cargador limpia las paginas.
     *
     * @throws IllegalStateException si el tramo no esta libre
     */
    public void allocate(int processId, int start, int length) {
        if (processId < 0) {
            throw new IllegalArgumentException("identificador de proceso invalido: " + processId);
        }
        checkRange(start, length);
        if (!isFree(start, length)) {
            throw new IllegalStateException(
                    "el tramo " + new FreeSpan(start, length).label() + " no esta libre y se intento asignar");
        }
        Arrays.fill(owner, start, start + length, processId);
        Arrays.fill(data, start, start + length, (byte) 0);
    }

    /** Libera todo lo que un proceso tiene asignado. */
    public void releaseAll(int processId) {
        for (int i = 0; i < owner.length; i++) {
            if (owner[i] == processId) {
                owner[i] = FREE;
                data[i] = 0;
            }
        }
    }

    /** Libera un tramo concreto que pertenece a un proceso. */
    public void release(int processId, int start, int length) {
        checkRange(start, length);
        for (int i = start; i < start + length; i++) {
            if (owner[i] != processId) {
                throw new IllegalStateException(
                        "la unidad " + i + " no pertenece al proceso " + processId + " y se intento liberar");
            }
        }
        Arrays.fill(owner, start, start + length, FREE);
        Arrays.fill(data, start, start + length, (byte) 0);
    }

    /**
     * Escribe una unidad.
     *
     * Solo se escribe sobre memoria asignada: escribir sobre memoria libre es un
     * fallo del sistema, no una operacion tolerada.
     */
    public void writeUnit(int address, int value) {
        checkAddress(address);
        if (owner[address] == FREE) {
            throw new IllegalStateException("escritura sobre memoria libre en la unidad " + address);
        }
        data[address] = (byte) (value & 0xFF);
    }

    /** Lee una unidad y la devuelve como entero sin signo, de 0 a 255. */
    public int readUnit(int address) {
        checkAddress(address);
        if (owner[address] == FREE) {
            throw new IllegalStateException("lectura de memoria libre en la unidad " + address);
        }
        return data[address] & 0xFF;
    }

    /**
     * Lee una unidad sin comprobar el propietario.
     *
     * Lo usan las pruebas de integridad y las herramientas de diagnostico, no el
     * camino normal de ejecucion.
     */
    public int peekUnit(int address) {
        checkAddress(address);
        return data[address] & 0xFF;
    }

    /** Numero de unidades ocupadas. */
    public int usedUnits() {
        int n = 0;
        for (int o : owner) {
            if (o != FREE) {
                n++;
            }
        }
        return n;
    }

    /** Numero de unidades libres. */
    public int freeUnits() {
        return data.length - usedUnits();
    }

    /**
     * Huecos libres del sistema, de menor a mayor direccion.
     *
     * Las unidades libres contiguas se agrupan en un solo hueco, de modo que la
     * fusion al liberar no es una operacion aparte: es una consecuencia de
     * calcular la lista desde el mapa de propietarios.
     */
    public List<FreeSpan> freeSpans() {
        List<FreeSpan> spans = new ArrayList<>();
        int i = 0;
        while (i < owner.length) {
            if (owner[i] != FREE) {
                i++;
                continue;
            }
            int start = i;
            while (i < owner.length && owner[i] == FREE) {
                i++;
            }
            spans.add(new FreeSpan(start, i - start));
        }
        return spans;
    }

    /** Hueco libre de mayor tamano, o {@code null} si no hay memoria libre. */
    public FreeSpan largestFreeSpan() {
        FreeSpan best = null;
        for (FreeSpan span : freeSpans()) {
            if (best == null || span.length() > best.length()) {
                best = span;
            }
        }
        return best;
    }

    /**
     * Comprueba los invariantes del modulo.
     *
     * Devuelve la lista de incumplimientos; vacia significa que todo cuadra. Un
     * incumplimiento es un fallo del nucleo, no un aviso.
     */
    public List<String> checkInvariants() {
        List<String> failures = new ArrayList<>();

        if (data.length != owner.length) {
            failures.add("el contenido y el mapa de propietarios tienen distinto tamano");
        }
        if (usedUnits() + freeUnits() != size()) {
            failures.add("ocupado mas libre no suma el tamano total de la memoria");
        }

        int freeCount = 0;
        for (int i = 0; i < owner.length; i++) {
            if (owner[i] == FREE) {
                freeCount++;
            } else if (owner[i] < 0) {
                failures.add("propietario invalido (" + owner[i] + ") en la unidad " + i);
            }
        }
        if (freeCount != freeUnits()) {
            failures.add("el recuento de unidades libres no coincide con el mapa de propietarios");
        }

        int covered = 0;
        for (FreeSpan span : freeSpans()) {
            covered += span.length();
        }
        if (covered != freeCount) {
            failures.add("los huecos libres no cubren todas las unidades libres");
        }

        return failures;
    }

    /**
     * Dibuja el mapa de ocupacion, un caracter por grupo de unidades.
     *
     * El punto es libre y el digito es el ultimo del identificador del proceso
     * que ocupa el grupo. Se usa en pruebas y en la vista de memoria.
     */
    public String render(int unitsPerChar) {
        if (unitsPerChar <= 0) {
            throw new IllegalArgumentException("las unidades por caracter deben ser positivas");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < owner.length; i += unitsPerChar) {
            int o = owner[i];
            if (o == FREE) {
                sb.append('.');
            } else {
                sb.append((char) ('0' + (o % 10)));
            }
        }
        return sb.toString();
    }

    private void checkRange(int start, int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("la longitud debe ser positiva: " + length);
        }
        if (start < 0 || start > data.length - length) {
            throw new IllegalArgumentException(
                    "el tramo [" + start + ", " + (start + length) + ") se sale de una memoria de " + data.length);
        }
    }

    private void checkAddress(int address) {
        if (address < 0 || address >= data.length) {
            throw new IllegalArgumentException("direccion fuera de la memoria fisica: " + address);
        }
    }
}
