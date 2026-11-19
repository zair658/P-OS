package pos.alloc;

import java.util.List;

import pos.base.FreeSpan;

/**
 * Resultado de pedirle un hueco a un gestor.
 *
 * No basta con el hueco elegido: el sistema necesita poder explicar la
 * decision, porque esa explicacion alimenta la traza, la tabla de resultados y
 * la vista de memoria. Un gestor que no sabe explicarse esta mal implementado.
 *
 * @param manager        nombre del gestor que decidio
 * @param requestedUnits unidades solicitadas
 * @param chosen         hueco elegido, o {@code null} si no habia ninguno valido
 * @param reason         resumen legible de la decision
 * @param rejected       huecos descartados y por que
 */
public record SlotChoice(
        String manager,
        int requestedUnits,
        FreeSpan chosen,
        String reason,
        List<SlotRejection> rejected) {

    public SlotChoice {
        if (manager == null || manager.isBlank()) {
            throw new IllegalArgumentException("el nombre del gestor es obligatorio");
        }
        if (requestedUnits <= 0) {
            throw new IllegalArgumentException("las unidades solicitadas deben ser positivas: " + requestedUnits);
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("toda decision necesita un motivo");
        }
        rejected = List.copyOf(rejected);
    }

    /** Indica si se encontro un hueco valido. */
    public boolean found() {
        return chosen != null;
    }

    /** Unidades que quedarian sin usar en el hueco elegido. */
    public int waste() {
        return chosen == null ? 0 : chosen.length() - requestedUnits;
    }

    /** Resumen de una linea, apto para la traza. */
    public String describe() {
        if (chosen == null) {
            return manager + ": sin hueco para " + requestedUnits + " unidades (" + reason + ")";
        }
        return manager + ": elige " + chosen.label() + " de " + chosen.length()
                + " unidades para " + requestedUnits + " pedidas, resto " + waste()
                + ", descartados " + rejected.size();
    }
}
