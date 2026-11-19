package pos.kernel;

/**
 * Quien quiera mirar el sistema mientras corre, se apunta aqui.
 *
 * El nucleo no sabe que hay una ventana al otro lado, y no debe saberlo: si
 * tuviera que pintar, arrastraria consigo toda la interfaz. Lo unico que hace es
 * avisar al final de cada turno de que el estado ha cambiado, desde su propio
 * hilo y en un punto en que nada esta a medias: entre dos turnos no hay ningun
 * hilo dentro del interprete, asi que quien reciba el aviso puede leer el
 * sistema entero sin cerrojos y sin ver un estado incoherente.
 *
 * Esa es la regla que hace segura la ventana: la interfaz nunca lee el nucleo
 * por su cuenta, solo lee la copia que el nucleo le deja hecha.
 *
 * Un observador no puede frenar al nucleo: si tarda, el sistema tarda, pero no
 * se corrompe. Si revienta, se anota en la traza y se deja de avisar, porque una
 * ventana rota no puede tumbar el sistema operativo que esta mirando.
 */
@FunctionalInterface
public interface KernelObserver {

    /**
     * El sistema acaba de cerrar un turno. Se llama en el hilo del nucleo.
     *
     * @param kernel el sistema, en un estado consistente y sin nadie ejecutando
     */
    void afterTurn(Kernel kernel);
}
