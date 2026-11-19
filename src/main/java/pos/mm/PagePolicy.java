package pos.mm;

/**
 * Politica de reemplazo de paginas: a quien se expulsa cuando no queda marco.
 *
 * La politica no guarda estado. Todo lo que necesita saber esta en la vista que
 * le pasa el espacio de direcciones: que paginas estan presentes, cuando
 * entraron, cuando se usaron por ultima vez y, en el caso de OPT, cuanto falta
 * para que cada una vuelva a usarse. Asi la misma politica sirve para varios
 * procesos sin duplicar estructuras.
 *
 * FIFO y LRU eligen con informacion del pasado. OPT elige con informacion del
 * futuro, y por eso solo es legitimo cuando el programa es nuestro y la traza se
 * conoce de antemano. Aqui es el caso: la demostracion le da la traza completa.
 * Con eso OPT deja de ser una trampa academica y pasa a ser un calculo exacto
 * que sirve de cota: nadie puede fallar menos que OPT sobre esa misma traza.
 *
 * La anomalia de Belady aparece con FIFO: mas marcos pueden dar mas fallos,
 * porque al crecer la cola cambia el orden de salida. LRU y OPT no la sufren,
 * porque son politicas de pila.
 */
public enum PagePolicy {

    /** Expulsa la pagina que lleva mas tiempo cargada. */
    FIFO("FIFO") {
        @Override
        public int victimIndex(Resident resident) {
            int victima = 0;
            for (int i = 1; i < resident.count(); i++) {
                if (resident.loadedAt(i) < resident.loadedAt(victima)) {
                    victima = i;
                }
            }
            return victima;
        }
    },

    /** Expulsa la pagina que lleva mas tiempo sin usarse. */
    LRU("LRU") {
        @Override
        public int victimIndex(Resident resident) {
            int victima = 0;
            for (int i = 1; i < resident.count(); i++) {
                if (resident.usedAt(i) < resident.usedAt(victima)) {
                    victima = i;
                }
            }
            return victima;
        }
    },

    /**
     * Expulsa la pagina que tarda mas en volver a usarse.
     *
     * Si una pagina no vuelve a aparecer en lo que queda de traza, su siguiente
     * uso es infinito y es la primera candidata.
     */
    OPT("OPT") {
        @Override
        public int victimIndex(Resident resident) {
            int victima = 0;
            for (int i = 1; i < resident.count(); i++) {
                if (resident.nextUseOf(resident.pageAt(i)) > resident.nextUseOf(resident.pageAt(victima))) {
                    victima = i;
                }
            }
            return victima;
        }
    };

    private final String label;

    PagePolicy(String label) {
        this.label = label;
    }

    /** Nombre corto para tablas y trazas. */
    public String label() {
        return label;
    }

    /**
     * Indice, dentro de la vista, de la pagina residente que se expulsa.
     *
     * La vista siempre tiene al menos una pagina: si no quedara ninguna, no
     * habria a quien preguntar y el fallo seria de memoria, no de politica.
     */
    public abstract int victimIndex(Resident resident);

    /**
     * Lo que la politica puede mirar antes de decidir.
     *
     * Es una vista y no una copia a proposito: las politicas se llaman en el
     * camino del fallo de pagina y no conviene construir estructuras nuevas ahi.
     */
    public interface Resident {

        /** Paginas presentes ahora mismo, que son las unicas candidatas. */
        int count();

        /** Numero de pagina logica de la entrada {@code index}. */
        int pageAt(int index);

        /** Instante en que la entrada {@code index} entro en memoria. */
        long loadedAt(int index);

        /** Instante del ultimo acceso a la entrada {@code index}. */
        long usedAt(int index);

        /**
         * Accesos que faltan hasta que la pagina vuelva a usarse.
         *
         * Devuelve {@link Integer#MAX_VALUE} si no vuelve a aparecer en la
         * traza conocida.
         */
        int nextUseOf(int page);
    }
}
