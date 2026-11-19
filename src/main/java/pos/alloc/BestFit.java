package pos.alloc;

import pos.base.FreeSpan;

/**
 * BestFit: elige el hueco mas pequeno en el que quepa la peticion.
 *
 * Deja el menor resto posible, lo que aprovecha bien los huecos pequenos, pero
 * tiende a producir muchos restos diminutos e inutilizables. Es el gestor de
 * referencia con el que se compara todo lo demas.
 */
public final class BestFit extends AbstractSlotManager {

    @Override
    public String name() {
        return "best-fit";
    }

    @Override
    protected int rank(FreeSpan slot, int requestedUnits, int largestLength) {
        return slot.length();
    }

    @Override
    protected String rankLabel(FreeSpan slot, int requestedUnits, int largestLength) {
        return "resto " + (slot.length() - requestedUnits);
    }
}
