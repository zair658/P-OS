package pos.sys;

/**
 * Una llamada al sistema.
 *
 * Es lo unico que un proceso puede pedirle al nucleo. El proceso de usuario no
 * toca el disco ni la tabla de procesos: pide, y el nucleo decide si se hace.
 * Esa frontera es la razon de existir de este paquete, y por eso cada llamada es
 * un tipo con sus argumentos y no una cadena de texto: si el compilador no puede
 * comprobar los argumentos, el nucleo acaba validando cadenas a mano.
 *
 * La interfaz es sellada porque el despacho del nucleo es un {@code switch} sin
 * rama por defecto: cuando se anade una llamada nueva, el compilador obliga a
 * tratarla en vez de dejarla caer en un "no implementado" en tiempo de ejecucion.
 */
public sealed interface Syscall {

    /** Numero de la llamada, el que sale en la tabla de la sesion. */
    int code();

    /** Nombre corto, en minusculas, con el que se documenta la llamada. */
    String name();

    /** Argumentos en texto, para la traza y la tabla de contabilidad. */
    String arguments();

    /** Modo de apertura de un fichero. */
    enum Mode {

        /** Solo lectura: lo que se lea sale del contenido que habia al abrir. */
        READ("lectura"),

        /** Escritura desde cero: el contenido anterior se descarta al cerrar. */
        WRITE("escritura"),

        /** Escritura al final: se conserva lo que habia y se anade detras. */
        APPEND("adicion");

        private final String descripcion;

        Mode(String descripcion) {
            this.descripcion = descripcion;
        }

        public String descripcion() {
            return descripcion;
        }
    }

    /** Termina el proceso que llama. */
    record Exit(int status) implements Syscall {
        @Override
        public int code() {
            return 1;
        }

        @Override
        public String name() {
            return "exit";
        }

        @Override
        public String arguments() {
            return Integer.toString(status);
        }
    }

    /** Escribe en un descriptor abierto. */
    record Write(int fd, String text) implements Syscall {
        @Override
        public int code() {
            return 2;
        }

        @Override
        public String name() {
            return "write";
        }

        @Override
        public String arguments() {
            return fd + ", " + text.length() + " caracteres";
        }
    }

    /** Lee del descriptor, como mucho {@code count} caracteres. */
    record Read(int fd, int count) implements Syscall {
        @Override
        public int code() {
            return 3;
        }

        @Override
        public String name() {
            return "read";
        }

        @Override
        public String arguments() {
            return fd + ", " + count + " caracteres";
        }
    }

    /** Abre un fichero y devuelve su descriptor. */
    record Open(String path, Mode mode) implements Syscall {
        @Override
        public int code() {
            return 4;
        }

        @Override
        public String name() {
            return "open";
        }

        @Override
        public String arguments() {
            return path + ", " + mode.descripcion();
        }
    }

    /** Cierra el descriptor; si era de escritura, es cuando se guarda. */
    record Close(int fd) implements Syscall {
        @Override
        public int code() {
            return 5;
        }

        @Override
        public String name() {
            return "close";
        }

        @Override
        public String arguments() {
            return Integer.toString(fd);
        }
    }

    /** Tipo, tamano y bloques de una entrada. */
    record Stat(String path) implements Syscall {
        @Override
        public int code() {
            return 6;
        }

        @Override
        public String name() {
            return "stat";
        }

        @Override
        public String arguments() {
            return path;
        }
    }

    /** Nombres de las entradas de un directorio, uno por linea. */
    record List(String path) implements Syscall {
        @Override
        public int code() {
            return 7;
        }

        @Override
        public String name() {
            return "list";
        }

        @Override
        public String arguments() {
            return path;
        }
    }

    /** Crea un directorio. */
    record Mkdir(String path) implements Syscall {
        @Override
        public int code() {
            return 8;
        }

        @Override
        public String name() {
            return "mkdir";
        }

        @Override
        public String arguments() {
            return path;
        }
    }

    /** Borra un fichero o un directorio vacio. */
    record Unlink(String path) implements Syscall {
        @Override
        public int code() {
            return 9;
        }

        @Override
        public String name() {
            return "unlink";
        }

        @Override
        public String arguments() {
            return path;
        }
    }

    /** Cambia el directorio de trabajo del proceso. */
    record Chdir(String path) implements Syscall {
        @Override
        public int code() {
            return 10;
        }

        @Override
        public String name() {
            return "chdir";
        }

        @Override
        public String arguments() {
            return path;
        }
    }

    /** Directorio de trabajo actual del proceso. */
    record GetCwd() implements Syscall {
        @Override
        public int code() {
            return 11;
        }

        @Override
        public String name() {
            return "getcwd";
        }

        @Override
        public String arguments() {
            return "";
        }
    }

    /** Estado de todos los procesos, una fila por proceso. */
    record Ps() implements Syscall {
        @Override
        public int code() {
            return 12;
        }

        @Override
        public String name() {
            return "ps";
        }

        @Override
        public String arguments() {
            return "";
        }
    }

    /** Termina otro proceso y todos sus hilos. */
    record Kill(int pid) implements Syscall {
        @Override
        public int code() {
            return 13;
        }

        @Override
        public String name() {
            return "kill";
        }

        @Override
        public String arguments() {
            return Integer.toString(pid);
        }
    }

    /** Ocupacion de la memoria fisica. */
    record Mem() implements Syscall {
        @Override
        public int code() {
            return 14;
        }

        @Override
        public String name() {
            return "mem";
        }

        @Override
        public String arguments() {
            return "";
        }
    }

    /**
     * Duerme al proceso que llama.
     *
     * La llamada no duerme por si misma: anota los tics y vuelve. Quien decide
     * bloquear el hilo es el planificador, y esa decision se toma al terminar la
     * orden, no dentro del nucleo. Es la diferencia entre "pedir una espera" y
     * "esperar aqui mismo con la CPU tomada".
     */
    record Sleep(long ticks) implements Syscall {
        @Override
        public int code() {
            return 15;
        }

        @Override
        public String name() {
            return "sleep";
        }

        @Override
        public String arguments() {
            return ticks + " tics";
        }
    }

    /** Tic actual del reloj logico. */
    record Clock() implements Syscall {
        @Override
        public int code() {
            return 16;
        }

        @Override
        public String name() {
            return "clock";
        }

        @Override
        public String arguments() {
            return "";
        }
    }

    /**
     * Crea un proceso a partir de codigo fuente del ensamblador de P-OS.
     *
     * El componente se llama {@code label} y no {@code name} porque {@code name()}
     * ya significa otra cosa en esta jerarquia: el nombre de la llamada. Cuando
     * ambos coincidian, el proceso nuevo heredaba el nombre "spawn" y en la tabla
     * de procesos todos los hijos aparecian igual.
     */
    record Spawn(String label, String source) implements Syscall {
        @Override
        public int code() {
            return 17;
        }

        @Override
        public String name() {
            return "spawn";
        }

        @Override
        public String arguments() {
            return label + ", " + source.lines().count() + " lineas";
        }
    }

    /** Ocupacion del sistema de ficheros. */
    record FsInfo() implements Syscall {
        @Override
        public int code() {
            return 18;
        }

        @Override
        public String name() {
            return "fsinfo";
        }

        @Override
        public String arguments() {
            return "";
        }
    }

    /** Verificacion de la imagen del sistema de ficheros. */
    record FsCheck() implements Syscall {
        @Override
        public int code() {
            return 19;
        }

        @Override
        public String name() {
            return "fscheck";
        }

        @Override
        public String arguments() {
            return "";
        }
    }

    /** Cambia el metodo de asignacion de la imagen. */
    record SetMethod(String method) implements Syscall {
        @Override
        public int code() {
            return 20;
        }

        @Override
        public String name() {
            return "setmethod";
        }

        @Override
        public String arguments() {
            return method;
        }
    }
    /** Consulta el metodo de asignacion de la imagen. */
    record GetMethod() implements Syscall {
        @Override
        public int code() {
            return 21;
        }

        @Override
        public String name() {
            return "getmethod";
        }

        @Override
        public String arguments() {
            return "";
        }
    }
}
