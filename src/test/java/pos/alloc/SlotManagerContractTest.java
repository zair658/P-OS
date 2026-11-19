package pos.alloc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import pos.base.FreeSpan;

/**
 * Contrato comun de los tres gestores de huecos.
 *
 * Estas pruebas se aplican a todos, de modo que un gestor nuevo no puede entrar
 * en el sistema sin cumplir lo mismo que los demas. Lo que se prueba aqui es el
 * contrato, no el criterio de cada uno: eso esta en las pruebas de cada gestor.
 */
class SlotManagerContractTest {

    static Stream<SlotManager> gestores() {
        return Stream.of(new BestFit(), new WorstFit(), new ScoringHybrid());
    }

    @ParameterizedTest
    @MethodSource("gestores")
    @DisplayName("sin huecos libres se informa de que no hay sitio, con motivo")
    void sinHuecosNoHayEleccion(SlotManager manager) {
        SlotChoice choice = manager.choose(List.of(), 32);

        assertFalse(choice.found());
        assertEquals(0, choice.waste());
        assertTrue(choice.reason().contains("32"), "el motivo debe decir cuanto se pedia");
    }

    @ParameterizedTest
    @MethodSource("gestores")
    @DisplayName("si ningun hueco alcanza el tamano pedido no se elige ninguno")
    void ningunHuecoAlcanza(SlotManager manager) {
        List<FreeSpan> slots = List.of(new FreeSpan(0, 10), new FreeSpan(20, 15));

        SlotChoice choice = manager.choose(slots, 32);

        assertFalse(choice.found());
        assertEquals(2, choice.rejected().size(), "los dos huecos deben quedar documentados como descartados");
    }

    @ParameterizedTest
    @MethodSource("gestores")
    @DisplayName("el hueco elegido siempre es lo bastante grande")
    void elElegidoSiempreCabe(SlotManager manager) {
        List<FreeSpan> slots = List.of(new FreeSpan(0, 40), new FreeSpan(100, 200), new FreeSpan(400, 60));

        SlotChoice choice = manager.choose(slots, 50);

        assertNotNull(choice.chosen());
        assertTrue(choice.chosen().length() >= 50);
        assertTrue(choice.waste() >= 0);
    }

    @ParameterizedTest
    @MethodSource("gestores")
    @DisplayName("a igualdad de criterio desempata por la direccion menor")
    void desempatePorDireccionMenor(SlotManager manager) {
        List<FreeSpan> slots = List.of(new FreeSpan(500, 64), new FreeSpan(0, 64), new FreeSpan(200, 64));

        SlotChoice choice = manager.choose(slots, 32);

        assertEquals(new FreeSpan(0, 64), choice.chosen());
    }

    @ParameterizedTest
    @MethodSource("gestores")
    @DisplayName("el resultado no depende del orden en que lleguen los huecos")
    void resultadoIndependienteDelOrden(SlotManager manager) {
        List<FreeSpan> base = List.of(
                new FreeSpan(0, 40), new FreeSpan(48, 120), new FreeSpan(200, 64), new FreeSpan(300, 300));

        SlotChoice referencia = manager.choose(base, 56);
        Random random = new Random(1234L);

        for (int i = 0; i < 25; i++) {
            List<FreeSpan> barajada = new ArrayList<>(base);
            Collections.shuffle(barajada, random);
            assertEquals(referencia.chosen(), manager.choose(barajada, 56).chosen(),
                    "la eleccion debe ser reproducible aunque cambie el orden de la lista");
        }
    }

    @ParameterizedTest
    @MethodSource("gestores")
    @DisplayName("todos los huecos no elegidos quedan documentados con su motivo")
    void todosLosDescartesTienenMotivo(SlotManager manager) {
        List<FreeSpan> slots = List.of(new FreeSpan(0, 40), new FreeSpan(100, 200), new FreeSpan(400, 60));

        SlotChoice choice = manager.choose(slots, 50);

        assertEquals(slots.size() - 1, choice.rejected().size());
        choice.rejected().forEach(r -> assertFalse(r.motive().isBlank(), "un descarte sin motivo no vale"));
        assertTrue(choice.describe().contains(manager.name()), "el resumen debe identificar al gestor");
    }

    @ParameterizedTest
    @MethodSource("gestores")
    @DisplayName("una peticion de cero o menos unidades se rechaza")
    void peticionInvalidaSeRechaza(SlotManager manager) {
        assertThrows(IllegalArgumentException.class, () -> manager.choose(List.of(new FreeSpan(0, 10)), 0));
        assertThrows(IllegalArgumentException.class, () -> manager.choose(List.of(new FreeSpan(0, 10)), -5));
    }

    @ParameterizedTest
    @MethodSource("gestores")
    @DisplayName("dos ejecuciones con la misma entrada dan el mismo resultado")
    void esDeterminista(SlotManager manager) {
        List<FreeSpan> slots = List.of(new FreeSpan(0, 40), new FreeSpan(100, 200), new FreeSpan(400, 60));

        SlotChoice primera = manager.choose(slots, 50);
        SlotChoice segunda = manager.choose(slots, 50);

        assertEquals(primera.chosen(), segunda.chosen());
        assertEquals(primera.reason(), segunda.reason());
    }
}
