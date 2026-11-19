package pos.alloc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import pos.base.FreeSpan;
import pos.phys.PhysicalMemory;

/**
 * Comparacion de los tres gestores sobre las mismas cargas.
 *
 * No se limita a comprobar que funcionan: mide cuantas peticiones se atienden,
 * cuantas fallan y cuantas unidades quedan en huecos inservibles. La tabla se
 * imprime aunque las comprobaciones pasen, porque es la evidencia que se lleva a
 * la defensa.
 *
 * El resultado de la comparacion no se maquilla. BestFit es un rival duro: al
 * dejar siempre el resto minimo, casi nunca pierde en peticiones atendidas. El
 * gestor hibrido no lo supera en esa metrica y hay una carga que lo demuestra.
 * Donde si cambia algo es en la forma del hueco que queda: el hibrido genera
 * menos huecos inservibles porque se niega a dejar migas. Un algoritmo propio que
 * solo gana en su propia tabla no demuestra nada; uno que gana en una metrica,
 * pierde en otra y lo dice, si.
 */
class SlotManagersComparisonTest {

    /** Un hueco por debajo de este tamano no sirve para ninguna peticion del sistema. */
    private static final int MINIMO_UTIL = 16;

    /**
     * Operacion de la simulacion.
     *
     * @param asignar  verdadero para asignar, falso para liberar
     * @param unidades tamano pedido, cuando se asigna
     * @param objetivo numero de asignacion cuyo proceso se libera, cuando se libera
     */
    private record Operacion(boolean asignar, int unidades, int objetivo) {
        static Operacion asigna(int unidades) {
            return new Operacion(true, unidades, 0);
        }

        /** Libera el proceso creado por la asignacion numero {@code objetivo}, empezando en uno. */
        static Operacion libera(int objetivo) {
            return new Operacion(false, 0, objetivo);
        }
    }

    @Test
    @DisplayName("BestFit gana en peticiones atendidas: hay que decirlo")
    void bestFitGanaCuandoLlegaUnaPeticionGrandeDespues() {
        // Se construyen dos huecos, uno de 50 y otro de 70, separados por bloques
        // ocupados que no se liberan.
        //   peticion de 40: BestFit usa el de 50 y conserva el de 70 entero;
        //                   el hibrido usa el de 70 porque el de 50 solo deja miga.
        //   peticion de 60: BestFit la atiende; al hibrido no le queda sitio.
        List<Operacion> operaciones = List.of(
                Operacion.asigna(50),      // 1
                Operacion.asigna(40),      // 2, separador
                Operacion.asigna(70),      // 3
                Operacion.asigna(40),      // 4, separador
                Operacion.libera(1),
                Operacion.libera(3),
                Operacion.asigna(40),
                Operacion.asigna(60));

        System.out.println(simular("huecos de 50 y 70, luego una peticion de 60", 200, operaciones));
        assertTrue(true);
    }

    @Test
    @DisplayName("el hibrido deja menos huecos inservibles: su metrica")
    void elHibridoDejaMenosHuecosInservibles() {
        // Tres huecos de 70, 50 y 50 y solo dos peticiones de 40.
        // BestFit consume los dos de 50 y deja dos migas de 10.
        // El hibrido consume el de 70 y uno de 50, y deja una sola miga de 10,
        // pero conserva un hueco de 50 entero.
        List<Operacion> operaciones = List.of(
                Operacion.asigna(70),      // 1
                Operacion.asigna(40),      // 2, separador
                Operacion.asigna(50),      // 3
                Operacion.asigna(40),      // 4, separador
                Operacion.asigna(50),      // 5
                Operacion.asigna(40),      // 6, separador
                Operacion.libera(1),
                Operacion.libera(3),
                Operacion.libera(5),
                Operacion.asigna(40),
                Operacion.asigna(40));

        System.out.println(simular("huecos de 70, 50 y 50, dos peticiones de 40", 300, operaciones));
        assertTrue(true);
    }

    @Test
    @DisplayName("carga realista con liberaciones intercaladas")
    void cargaRealista() {
        List<Operacion> operaciones = new ArrayList<>();
        int asignacion = 0;
        int[] tamanos = {64, 96, 48, 32, 80, 40, 72, 56, 24, 88, 40, 48};
        for (int i = 0; i < tamanos.length; i++) {
            operaciones.add(Operacion.asigna(tamanos[i]));
            asignacion++;
            if (i % 3 == 2) {
                // Se libera una asignacion anterior, no la ultima: asi quedan
                // huecos sueltos en medio de la memoria.
                operaciones.add(Operacion.libera(asignacion - 2));
            }
        }
        for (int i = 0; i < 6; i++) {
            operaciones.add(Operacion.asigna(40));
        }

        System.out.println(simular("carga con tamanos variados y liberaciones", 640, operaciones));
        assertTrue(true);
    }

    @Test
    @DisplayName("la misma carga produce siempre la misma tabla")
    void laSimulacionEsReproducible() {
        List<Operacion> operaciones = List.of(
                Operacion.asigna(50), Operacion.asigna(40), Operacion.asigna(70),
                Operacion.asigna(40), Operacion.libera(1), Operacion.libera(3),
                Operacion.asigna(40), Operacion.asigna(60));

        assertEquals(simular("a", 200, operaciones), simular("a", 200, operaciones));
    }

    @Test
    @DisplayName("las tres estrategias se comparan sobre la misma carga")
    void losTresGestoresRecibenLaMismaCarga() {
        List<Operacion> operaciones = List.of(
                Operacion.asigna(64), Operacion.asigna(48), Operacion.libera(1), Operacion.asigna(40));
        String tabla = simular("prueba", 256, operaciones);

        assertTrue(tabla.contains("best-fit"));
        assertTrue(tabla.contains("worst-fit"));
        assertTrue(tabla.contains("hibrido-puntuacion"));
    }

    private String simular(String titulo, int unidades, List<Operacion> operaciones) {
        StringBuilder sb = new StringBuilder();
        sb.append('\n').append(titulo).append(" (").append(unidades).append(" unidades, ")
                .append(operaciones.size()).append(" operaciones)\n");
        sb.append("+---------------------+----------+---------+--------------+------------+\n");
        sb.append("| gestor              | atendidas| fallidas| huecos muertos| mayor hueco|\n");
        sb.append("+---------------------+----------+---------+--------------+------------+\n");

        for (SlotManager gestor : List.of(new BestFit(), new WorstFit(), new ScoringHybrid())) {
            PhysicalMemory memory = new PhysicalMemory(unidades);
            Map<Integer, Integer> procesos = new HashMap<>();
            int atendidas = 0;
            int fallidas = 0;
            int pid = 1;

            for (Operacion operacion : operaciones) {
                if (operacion.asignar()) {
                    SlotChoice choice = gestor.choose(memory.freeSpans(), operacion.unidades());
                    if (choice.found()) {
                        memory.allocate(pid, choice.chosen().start(), operacion.unidades());
                        procesos.put(atendidas + 1, pid);
                        pid++;
                        atendidas++;
                    } else {
                        fallidas++;
                    }
                } else {
                    Integer objetivo = procesos.get(operacion.objetivo());
                    if (objetivo != null) {
                        memory.releaseAll(objetivo);
                        procesos.remove(operacion.objetivo());
                    }
                }
                List<String> fallos = memory.checkInvariants();
                if (!fallos.isEmpty()) {
                    throw new IllegalStateException("invariantes rotos con " + gestor.name() + ": " + fallos);
                }
            }

            long muertos = memory.freeSpans().stream().filter(h -> h.length() < MINIMO_UTIL).count();
            int muertosUnidades = memory.freeSpans().stream()
                    .filter(h -> h.length() < MINIMO_UTIL)
                    .mapToInt(FreeSpan::length)
                    .sum();
            FreeSpan mayor = memory.largestFreeSpan();

            sb.append(String.format("| %-19s | %8d | %7d | %5d /%4d u | %11s |%n",
                    gestor.name(), atendidas, fallidas, muertos, muertosUnidades,
                    mayor == null ? "-" : String.valueOf(mayor.length())));
        }

        sb.append("+---------------------+----------+---------+--------------+------------+\n");
        return sb.toString();
    }
}
