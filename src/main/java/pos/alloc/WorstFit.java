package pos.alloc;

import pos.base.FreeSpan;

/**
 * WorstFit: elige el hueco mas grande disponible.
 *
 * Deja restos grandes y reutilizables, lo que retrasa la fragmentacion externa
 * con cargas de tamanos parecidos, pero destruye los huecos grandes que podrian
 * necesitar despues peticiones de golpe. Es el contrario exacto de BestFit y
 * sirve para el contraste.
 */
public final class WorstFit extends AbstractSlotManager {

    @Override
    public String name() {
        return "worst-fit";
    }

    @Override
    protected int rank(FreeSpan slot, int requestedUnits, int largestLength) {
        // Cuanto mayor es el hueco, mejor, de ahi el signo negativo.
        return -slot.length();
    }

    @Override
    protected String rankLabel(FreeSpan slot, int requestedUnits, int largestLength) {
        return "hueco de " + slot.length() + " unidades";
    }
}
