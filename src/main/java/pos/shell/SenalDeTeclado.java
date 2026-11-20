package pos.shell;

/**
 * Lo que el teclado puede pedirle al sistema sin escribir una linea.
 *
 * Existe por Ctrl-Z. Todas las demas teclas del editor escriben, borran o mueven
 * el cursor, y con eso basta: el resultado es una linea que se ejecuta. Ctrl-Z no
 * produce ninguna linea, produce un hecho sobre un proceso que el usuario no ve,
 * y por eso el editor no lo puede resolver solo.
 *
 * Se deja como interfaz para que el teclado no sepa que hay un nucleo detras.
 * Quien construye el teclado le enchufa el nucleo; en las pruebas no se enchufa
 * nada y Ctrl-Z no hace nada, que es exactamente lo que debe pasar cuando no hay
 * a quien parar.
 */
public interface SenalDeTeclado {

    /**
     * Si hay un trabajo en el primer plano al que valga la pena parar.
     *
     * Se pregunta antes de tocar nada: pulsar Ctrl-Z en el aviso, sin nada
     * corriendo, no deberia borrar lo que se esta escribiendo.
     */
    boolean hayPrimerPlano();

    /** Pide que se detenga el trabajo en primer plano. */
    void parar();
}
