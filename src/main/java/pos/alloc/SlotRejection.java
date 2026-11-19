package pos.alloc;

import pos.base.FreeSpan;

/** Hueco que se descarto al elegir, con el motivo en texto legible. */
public record SlotRejection(FreeSpan slot, String motive) {

    public SlotRejection {
        if (slot == null) {
            throw new IllegalArgumentException("el hueco descartado no puede ser nulo");
        }
        if (motive == null || motive.isBlank()) {
            throw new IllegalArgumentException("todo descarte necesita un motivo");
        }
    }
}
