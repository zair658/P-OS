package pos.alloc;

import pos.base.FreeSpan;

/**
 * Gestor hibrido propio, basado en puntuacion.
 *
 * Es el algoritmo que distingue a P-OS de una implementacion de libro. Parte de
 * BestFit, que es un buen punto de partida, y le corrige el defecto que lo hace
 * fallar en la practica: BestFit elige siempre el encaje mas ajustado y, cuando
 * ese encaje deja un resto diminuto, el resto es demasiado pequeno para volver a
 * usarse. El hueco no se ha aprovechado: se ha perdido. Este gestor llama miga a
 * ese resto inutilizable y lo cobra.
 *
 * <pre>
 *   resto(s) = longitud(s) - pedido
 *
 *   puntuacion(s) = 0                          si resto(s) == 0   (encaje exacto)
 *                 = pesoMiga * longitud(s)     si resto(s) &lt; minimoUtil
 *                 = resto(s)                   en cualquier otro caso
 * </pre>
 *
 * La consecuencia practica es que el gestor prefiere dejar veinte unidades en un
 * hueco que dejar seis en otro, aunque el segundo encaje mejor, porque seis
 * unidades no sirven para nada y veinte si. Eso es exactamente lo contrario de
 * lo que hace BestFit, y es lo que se puede demostrar con la misma carga sobre
 * los dos gestores.
 *
 * Un hallazgo que conviene dejar escrito, porque en la defensa se va a preguntar
 * y porque explica una decision de diseno: la primera version de este algoritmo
 * sumaba un castigo por usar el hueco mayor disponible. Se descarto porque es
 * aritmeticamente inerte. Como el resto de un hueco crece con su tamano, un
 * castigo proporcional al tamano conserva el orden de BestFit y no cambia jamas
 * la eleccion; y un castigo fijo solo puede alterar el resultado cuando el hueco
 * mayor es tambien el unico que cabe, en cuyo caso no hay nada que elegir. Un
 * parametro que no cambia nunca el resultado no es un criterio, es decoracion, y
 * se quito.
 *
 * Los dos parametros que si quedan son reales y se pueden cambiar desde la
 * terminal para ver el efecto: el minimo utilizable y el peso de la miga.
 */
public final class ScoringHybrid extends AbstractSlotManager {

    /** Resto minimo, en unidades, que se considera aprovechable. */
    public static final int DEFAULT_MIN_USEFUL_UNITS = 16;

    /** Peso por defecto del hueco desperdiciado por dejar una miga. */
    public static final int DEFAULT_SCRAP_WEIGHT = 1;

    private final int minUsefulUnits;
    private final int scrapWeight;

    /** Crea el gestor con los valores por defecto. */
    public ScoringHybrid() {
        this(DEFAULT_MIN_USEFUL_UNITS, DEFAULT_SCRAP_WEIGHT);
    }

    /**
     * Crea el gestor con parametros concretos.
     *
     * @param minUsefulUnits resto por debajo del cual se considera miga
     * @param scrapWeight    cuanto se cobra un hueco que solo sirve para dejar miga
     */
    public ScoringHybrid(int minUsefulUnits, int scrapWeight) {
        if (minUsefulUnits < 0) {
            throw new IllegalArgumentException("el minimo utilizable no puede ser negativo: " + minUsefulUnits);
        }
        if (scrapWeight <= 0) {
            throw new IllegalArgumentException("el peso de la miga debe ser positivo: " + scrapWeight);
        }
        this.minUsefulUnits = minUsefulUnits;
        this.scrapWeight = scrapWeight;
    }

    /** Resto minimo que se considera aprovechable. */
    public int minUsefulUnits() {
        return minUsefulUnits;
    }

    /** Peso aplicado al hueco que solo deja miga. */
    public int scrapWeight() {
        return scrapWeight;
    }

    @Override
    public String name() {
        return "hibrido-puntuacion";
    }

    /**
     * Puntuacion de un hueco. Menor es mejor.
     *
     * Es publica a proposito: la vista de memoria muestra la puntuacion de cada
     * candidato para que la decision se pueda auditar en pantalla y no haya que
     * creersela.
     */
    public int score(FreeSpan slot, int requestedUnits) {
        int waste = slot.length() - requestedUnits;
        if (waste <= 0) {
            return 0;
        }
        if (waste < minUsefulUnits) {
            return scrapWeight * slot.length();
        }
        return waste;
    }

    @Override
    protected int rank(FreeSpan slot, int requestedUnits, int largestLength) {
        return score(slot, requestedUnits);
    }

    @Override
    protected String rankLabel(FreeSpan slot, int requestedUnits, int largestLength) {
        int waste = slot.length() - requestedUnits;
        String tipo = waste <= 0 ? "encaje exacto" : (waste < minUsefulUnits ? "miga" : "resto utilizable");
        return "puntuacion " + score(slot, requestedUnits) + " (" + tipo + " de " + waste + ")";
    }
}
