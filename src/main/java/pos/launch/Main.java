package pos.launch;

import pos.base.PosVersion;
import pos.phys.PhysicalMemory;

/**
 * Punto de entrada del sistema.
 *
 * En esta fase solo comprueba que el nucleo arranca y que la memoria fisica
 * responde. La terminal de comandos llega en su fase y la interfaz grafica
 * despues; este arranque no adelanta ninguna de las dos, solo verifica.
 *
 * Uso:
 * <pre>
 *   java -jar p-os.jar             saludo y comprobacion del nucleo
 *   java -jar p-os.jar --version   version y salida
 * </pre>
 */
public final class Main {

    private Main() {
        // Clase de arranque: no se instancia.
    }

    public static void main(String[] args) {
        if (args.length > 0 && ("--version".equals(args[0]) || "-v".equals(args[0]))) {
            System.out.println(PosVersion.FULL);
            return;
        }
        if (args.length > 0 && ("--help".equals(args[0]) || "-h".equals(args[0]))) {
            System.out.println("uso: p-os [--version] [--help]");
            System.out.println("  sin argumentos  comprueba el nucleo y muestra el estado de la memoria");
            return;
        }

        System.out.println(PosVersion.FULL);
        System.out.println();

        // Comprobacion de arranque: si la memoria fisica no responde, el resto
        // del sistema no tiene sentido.
        PhysicalMemory memory = new PhysicalMemory(4096);
        memory.allocate(1, 0, 64);
        memory.allocate(2, 128, 64);

        System.out.println("memoria fisica ....... " + memory.size() + " unidades");
        System.out.println("unidades libres ...... " + memory.freeUnits());
        System.out.println("huecos libres ........ " + memory.freeSpans().size());
        System.out.println("mapa ................. " + memory.render(64));

        var failures = memory.checkInvariants();
        if (failures.isEmpty()) {
            System.out.println("invariantes .......... OK");
        } else {
            System.out.println("invariantes .......... FALLO");
            failures.forEach(f -> System.out.println("  " + f));
            System.exit(1);
        }

        System.out.println();
        System.out.println("estado: nucleo de memoria operativo, sin terminal ni interfaz todavia");
    }
}
