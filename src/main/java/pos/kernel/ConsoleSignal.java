package pos.kernel;

/**
 * Lo que el teclado le pide al nucleo, sin pasar por una llamada al sistema.
 *
 * Una llamada al sistema la pide un proceso y la atiende el nucleo en nombre de
 * ese proceso. Esto no: lo pide alguien que esta escribiendo, y no hay proceso
 * detras. En un sistema de verdad seria una interrupcion del teclado; aqui es un
 * aviso que se deja en un buzon y que el nucleo mira al principio de cada vuelta
 * de su ciclo, cuando ya no esta ejecutando a nadie.
 *
 * Por eso mismo esta en {@code pos.kernel} y no en {@code pos.shell}: quien
 * escribe no manda sobre el sistema, solo avisa. Quien decide es el nucleo.
 */
public enum ConsoleSignal {

    /** Ctrl-Z: se detiene el trabajo que la terminal tiene en el primer plano. */
    PARAR
}
