package pos.alloc;

import java.util.ArrayList;
import java.util.List;

import pos.base.FreeSpan;

/**
 * Base comun de los gestores de huecos.
 *
 * Todos comparten la misma forma: descartar lo que no cabe, ordenar lo que cabe
 * por un criterio numerico y desempatar por la direccion menor. Cada gestor solo
 * aporta su criterio y como lo describe. Esto evita que los tres gestores
 * divergan en detalles como el desempate, que es justo lo que rompe el
 * determinismo sin que nadie se de cuenta.
 */
public abstract class AbstractSlotManager implements SlotManager {

    @Override
    public final SlotChoice choose(List<FreeSpan> slots, int requestedUnits) {
        if (slots == null) {
            throw new IllegalArgumentException("la lista de huecos no puede ser nula");
        }
        if (requestedUnits <= 0) {
            throw new IllegalArgumentException("las unidades solicitadas deben ser positivas: " + requestedUnits);
        }
        for (FreeSpan slot : slots) {
            if (slot == null) {
                throw new IllegalArgumentException("la lista de huecos contiene un elemento nulo");
            }
        }

        int largest = slots.stream().mapToInt(FreeSpan::length).max().orElse(0);

        FreeSpan chosen = null;
        for (FreeSpan slot : slots) {
            if (slot.length() < requestedUnits) {
                continue;
            }
            if (chosen == null || isBetter(slot, chosen, requestedUnits, largest)) {
                chosen = slot;
            }
        }

        List<SlotRejection> rejected = new ArrayList<>();
        for (FreeSpan slot : slots) {
            if (slot.length() < requestedUnits) {
                rejected.add(new SlotRejection(slot,
                        "no cabe: el hueco tiene " + slot.length() + " unidades y se piden " + requestedUnits));
            } else if (!slot.equals(chosen)) {
                rejected.add(new SlotRejection(slot,
                        "cabe, pero " + rankLabel(slot, requestedUnits, largest)
                                + " frente a " + rankLabel(chosen, requestedUnits, largest)
                                + " del elegido"));
            }
        }

        if (chosen == null) {
            return new SlotChoice(name(), requestedUnits, null,
                    "ningun hueco alcanza " + requestedUnits + " unidades", rejected);
        }
        return new SlotChoice(name(), requestedUnits, chosen,
                "elige el hueco con " + rankLabel(chosen, requestedUnits, largest), rejected);
    }

    /**
     * Criterio numerico del gestor: cuanto menor, mejor.
     *
     * @param slot          hueco candidato, que se sabe que cabe
     * @param requestedUnits unidades solicitadas
     * @param largestLength longitud del hueco mayor disponible
     */
    protected abstract int rank(FreeSpan slot, int requestedUnits, int largestLength);

    /** Descripcion legible del criterio para un hueco concreto. */
    protected abstract String rankLabel(FreeSpan slot, int requestedUnits, int largestLength);

    private boolean isBetter(FreeSpan candidate, FreeSpan current, int requestedUnits, int largest) {
        int candidateRank = rank(candidate, requestedUnits, largest);
        int currentRank = rank(current, requestedUnits, largest);
        if (candidateRank != currentRank) {
            return candidateRank < currentRank;
        }
        // Desempate obligatorio por direccion menor: sin esto el resultado
        // dependeria del orden de la lista y el sistema dejaria de ser
        // reproducible.
        return candidate.start() < current.start();
    }
}
