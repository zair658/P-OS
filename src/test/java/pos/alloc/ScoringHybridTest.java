package pos.alloc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import pos.base.FreeSpan;

/** Pruebas del gestor hibrido propio y de su diferencia con BestFit. */
class ScoringHybridTest {

    @Test
    @DisplayName("el encaje exacto gana siempre, aunque haya huecos mayores")
    void encajeExactoGanaSiempre() {
        ScoringHybrid hibrido = new ScoringHybrid();
        List<FreeSpan> slots = List.of(new FreeSpan(0, 1000), new FreeSpan(2000, 64), new FreeSpan(3000, 200));

        SlotChoice choice = hibrido.choose(slots, 64);

        assertEquals(new FreeSpan(2000, 64), choice.chosen());
        assertEquals(0, choice.waste());
    }

    @Test
    @DisplayName("prefiere dejar un resto utilizable antes que dejar una miga")
    void prefiereRestoUtilizableAntesQueMiga() {
        // Pedido de 40 unidades.
        //   [0, 50)   deja 10, que es miga: no sirve para nada y se cobra el hueco entero.
        //   [100, 170) es un hueco de 70 y deja 30, que si sirve.
        //   [300, 400) es un hueco de 100 y deja 60, pero se desperdicia mas.
        List<FreeSpan> slots = List.of(new FreeSpan(0, 50), new FreeSpan(100, 70), new FreeSpan(300, 100));

        SlotChoice hibrido = new ScoringHybrid().choose(slots, 40);
        SlotChoice bestFit = new BestFit().choose(slots, 40);

        assertEquals(new FreeSpan(100, 70), hibrido.chosen(),
                "el hibrido debe rechazar el encaje que solo deja miga");
        assertEquals(new FreeSpan(0, 50), bestFit.chosen(),
                "BestFit debe caer en la miga, que es justo el defecto que el hibrido corrige");
    }

    @Test
    @DisplayName("si todas las opciones dejan miga, elige la que menos desperdicia")
    void siTodoEsMigaEligeLaMenor() {
        List<FreeSpan> slots = List.of(new FreeSpan(0, 45), new FreeSpan(100, 52));

        SlotChoice choice = new ScoringHybrid().choose(slots, 40);

        // Restos de 5 y de 12: los dos son miga con el minimo por defecto de 16,
        // asi que se cobra el hueco entero y gana el mas pequeno.
        assertEquals(new FreeSpan(0, 45), choice.chosen());
    }

    @Test
    @DisplayName("con el minimo utilizable a cero se comporta exactamente como BestFit")
    void conMinimoCeroEquivaleABestFit() {
        ScoringHybrid sinMigas = new ScoringHybrid(0, 1);
        BestFit bestFit = new BestFit();
        List<FreeSpan> slots = List.of(
                new FreeSpan(0, 50), new FreeSpan(64, 170), new FreeSpan(300, 400), new FreeSpan(900, 65));

        for (int pedido = 1; pedido <= 65; pedido++) {
            assertEquals(bestFit.choose(slots, pedido).chosen(), sinMigas.choose(slots, pedido).chosen(),
                    "con minimo cero la correccion desaparece y debe coincidir con BestFit para " + pedido);
        }
    }

    @Test
    @DisplayName("el minimo utilizable es un parametro real y cambia la decision")
    void elMinimoEsUnParametroReal() {
        List<FreeSpan> slots = List.of(new FreeSpan(0, 50), new FreeSpan(100, 70));

        // Resto de 10 en el primero. Con minimo 16 es miga y se descarta; con
        // minimo 8 ya cuenta como aprovechable y gana por dejar menos resto.
        assertEquals(new FreeSpan(100, 70), new ScoringHybrid(16, 1).choose(slots, 40).chosen());
        assertEquals(new FreeSpan(0, 50), new ScoringHybrid(8, 1).choose(slots, 40).chosen());
    }

    @Test
    @DisplayName("la puntuacion de cada candidato es explicable numero a numero")
    void laPuntuacionSePuedeAuditar() {
        ScoringHybrid hibrido = new ScoringHybrid(16, 1);
        FreeSpan miga = new FreeSpan(0, 50);
        FreeSpan util = new FreeSpan(100, 70);

        assertEquals(50, hibrido.score(miga, 40), "una miga cobra el hueco entero");
        assertEquals(30, hibrido.score(util, 40), "un resto utilizable cobra solo el resto");
    }

    @Test
    @DisplayName("los parametros invalidos se rechazan al construir el gestor")
    void parametrosInvalidosSeRechazan() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new ScoringHybrid(-1, 1));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new ScoringHybrid(16, 0));
    }

    @Test
    @DisplayName("el nombre del gestor lo identifica en la traza")
    void nombreDelGestor() {
        assertEquals("hibrido-puntuacion", new ScoringHybrid().name());
        assertTrue(new ScoringHybrid().choose(List.of(new FreeSpan(0, 64)), 64).describe().contains("hibrido-puntuacion"));
    }
}
