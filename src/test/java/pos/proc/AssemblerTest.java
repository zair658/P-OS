package pos.proc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pos.base.PosFault;

@DisplayName("El cargador convierte texto en un programa con los saltos resueltos")
class AssemblerTest {

    @Test
    @DisplayName("resuelve etiquetas a indices absolutos")
    void resuelveEtiquetas() {
        Program program = Assembler.load("bucle", """
                SET 0
                loop: ADD 2
                      CMP 10
                      JZ fin
                      JMP loop
                fin:  EXIT
                """);

        assertEquals(6, program.size());
        assertEquals(new Instruction.Set(0), program.at(0));
        assertEquals(new Instruction.Add(2), program.at(1));
        assertEquals(new Instruction.Jump(1), program.at(4), "JMP loop apunta al indice de ADD");
        assertEquals(new Instruction.Exit(), program.at(5), "fin cae en el EXIT");
    }

    @Test
    @DisplayName("ignora comentarios y lineas vacias")
    void ignoraComentarios() {
        Program program = Assembler.load("comentado", """
                ; esto es un comentario
                SET 1     ; y esto otro

                EXIT      # y este tambien
                """);

        assertEquals(2, program.size());
        assertEquals(new Instruction.Exit(), program.at(1));
    }

    @Test
    @DisplayName("una etiqueta sola no ocupa una instruccion")
    void etiquetaSolaNoOcupa() {
        Program program = Assembler.load("etiquetas", """
                inicio:
                fin:
                SET 4
                JMP inicio
                """);

        assertEquals(2, program.size());
        assertEquals(new Instruction.Jump(0), program.at(1));
    }

    @Test
    @DisplayName("rechaza una etiqueta desconocida diciendo cual")
    void rechazaEtiquetaDesconocida() {
        PosFault fault = assertThrows(PosFault.class, () -> Assembler.load("malo", """
                JMP noexiste
                EXIT
                """));

        assertTrue(fault.getMessage().contains("noexiste"), fault.getMessage());
    }

    @Test
    @DisplayName("rechaza una instruccion desconocida")
    void rechazaInstruccionDesconocida() {
        PosFault fault = assertThrows(PosFault.class, () -> Assembler.load("malo", """
                VOLAR 3
                """));

        assertTrue(fault.getMessage().contains("VOLAR"), fault.getMessage());
    }

    @Test
    @DisplayName("rechaza una etiqueta repetida")
    void rechazaEtiquetaRepetida() {
        PosFault fault = assertThrows(PosFault.class, () -> Assembler.load("malo", """
                a: SET 1
                a: SET 2
                """));

        assertTrue(fault.getMessage().contains("repetida"), fault.getMessage());
    }

    @Test
    @DisplayName("un programa vacio no se puede cargar")
    void rechazaProgramaVacio() {
        assertThrows(PosFault.class, () -> Assembler.load("vacio", "; solo comentarios\n"));
    }

    @Test
    @DisplayName("SLEEP necesita un numero positivo")
    void sleepNecesitaPositivo() {
        assertThrows(PosFault.class, () -> Assembler.load("malo", "SLEEP 0\nEXIT\n"));
    }

    @Test
    @DisplayName("LOCK y UNLOCK se cargan con el nombre del cerrojo")
    void cargaCerrojos() {
        Program program = Assembler.load("critica", """
                LOCK contador
                SET 1
                UNLOCK contador
                EXIT
                """);

        assertEquals(new Instruction.Lock("contador"), program.at(0));
        assertEquals(new Instruction.Unlock("contador"), program.at(2));
    }

    @Test
    @DisplayName("un cerrojo sin nombre no se puede cargar")
    void cerrojoNecesitaNombre() {
        PosFault fault = assertThrows(PosFault.class,
                () -> Assembler.load("malo", "LOCK\nEXIT\n"));

        assertTrue(fault.getMessage().contains("cerrojo"), fault.getMessage());
    }

    @Test
    @DisplayName("el nombre del cerrojo no puede ser un numero suelto")
    void cerrojoNoEsUnaDireccion() {
        // Se distingue a proposito de LOAD y STORE: un cerrojo se identifica por
        // nombre, no por direccion, para que la traza se pueda leer.
        assertThrows(PosFault.class, () -> Assembler.load("malo", "LOCK 3\nEXIT\n"));
    }
}
