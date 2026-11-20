package pos.base;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pruebas de la identidad del sistema.
 *
 * Existen por un fallo concreto: durante un tiempo {@code --version} respondia
 * 0.1 mientras el pie de la ventana pintaba 0.8, porque el pie era un literal
 * suelto. Estas pruebas atan los dos sitios al mismo dato.
 */
class PosVersionTest {

    @Test
    @DisplayName("el nombre completo lleva nombre y version")
    void nombreCompleto() {
        assertEquals("P-OS 0.8", PosVersion.FULL);
        assertEquals(PosVersion.NAME + " " + PosVersion.VERSION, PosVersion.FULL);
    }

    @Test
    @DisplayName("el pie de la ventana no repite la version a mano")
    void pieSaleDeLaVersion() {
        assertTrue(PosVersion.PIE.startsWith(PosVersion.FULL),
                "el pie debe empezar por la misma cadena que --version: " + PosVersion.PIE);
        assertEquals(PosVersion.FULL + " · fase " + PosVersion.FASE + " · interfaz",
                PosVersion.PIE);
    }

    @Test
    @DisplayName("la version tiene forma mayor.menor")
    void formatoDeVersion() {
        assertTrue(PosVersion.VERSION.matches("\\d+\\.\\d+"),
                "la version debe ser mayor.menor: " + PosVersion.VERSION);
    }

    @Test
    @DisplayName("la fase es un numero, no una palabra")
    void faseNumerica() {
        assertTrue(PosVersion.FASE.matches("\\d+"),
                "la fase debe ser un numero: " + PosVersion.FASE);
    }
}
