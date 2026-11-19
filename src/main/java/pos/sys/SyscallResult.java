package pos.sys;

/**
 * Resultado de una llamada al sistema.
 *
 * Dos cosas y nada mas: si salio bien y que devuelve, en texto. No se usa una
 * excepcion para el error porque un error de llamada es un dato que el proceso
 * tiene que poder mirar y decidir; una excepcion que cruza la frontera del
 * nucleo es un fallo de programacion, no un "no existe el fichero".
 */
public record SyscallResult(boolean ok, String text) {

    public SyscallResult {
        if (text == null) {
            throw new IllegalArgumentException("el resultado necesita texto");
        }
    }

    public static SyscallResult success(String text) {
        return new SyscallResult(true, text);
    }

    public static SyscallResult success() {
        return new SyscallResult(true, "");
    }

    public static SyscallResult failure(String text) {
        return new SyscallResult(false, text);
    }

    /** Valor numerico del texto devuelto; 0 si no es un numero. */
    public long number() {
        try {
            return Long.parseLong(text.trim());
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }
}
