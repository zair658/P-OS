package pos.alloc;

import java.util.List;

import pos.base.FreeSpan;

/**
 * Gestor de huecos: decide en que hueco libre se coloca una peticion.
 *
 * Contrato del sistema: entra la lista de huecos libres y el tamano pedido, y
 * sale el hueco elegido junto con la razon. El gestor no escribe en memoria ni
 * cambia el mapa de propietarios; solo decide. Quien asigna es el nucleo, por
 * medio de la memoria fisica.
 *
 * Las implementaciones deben ser deterministas: con la misma entrada, la misma
 * salida, siempre. Por eso todos desempatan por la direccion menor.
 */
public interface SlotManager {

    /** Nombre corto que aparece en la traza y en la interfaz. */
    String name();

    /**
     * Elige un hueco para la peticion.
     *
     * @param slots          huecos libres, de menor a mayor direccion
     * @param requestedUnits unidades solicitadas
     * @return la decision, con el hueco elegido o con el motivo de no haberlo
     */
    SlotChoice choose(List<FreeSpan> slots, int requestedUnits);
}
