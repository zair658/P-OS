package pos.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pos.proc.Assembler;
import pos.proc.Program;
import pos.sched.FcfsScheduler;
import pos.sched.RoundRobinScheduler;
import pos.sched.Scheduler;

@DisplayName("El nucleo reparte la CPU, bloquea, despierta y sobrevive a los fallos")
class KernelSchedulingTest {

    private static final String CONTADOR = """
            SET 0
            loop: ADD 1
                  STORE 0
                  CMP 5
                  JZ fin
                  JMP loop
            fin:  EXIT
            """;

    private static final String LANZA_HILO = """
            SET 7
            SPAWN hijo
            ADD 1
            EXIT
            hijo: SET 3
                  EXIT
            """;

    private static Program contador(String nombre) {
        return Assembler.load(nombre, CONTADOR);
    }

    private static List<String> cambiosDeContexto(Kernel kernel) {
        List<String> ids = new ArrayList<>();
        for (Trace.Event event : kernel.trace().events()) {
            if (event.kind() == EventKind.SWITCH) {
                ids.add(event.who());
            }
        }
        return List.copyOf(ids);
    }

    @Test
    @DisplayName("con FCFS el primero que llega termina antes de que empiece el segundo")
    void fcfsTerminaEnOrdenDeLlegada() {
        Kernel kernel = new Kernel(new FcfsScheduler(), 256);
        kernel.createProcess("alfa", contador("contador-a"));
        kernel.createProcess("beta", contador("contador-b"));

        KernelReport report = kernel.run();

        assertEquals(List.of("p1/h1", "p2/h1"), cambiosDeContexto(kernel),
                "FCFS no expropia: un solo turno por hilo");
        assertEquals(2, report.rows().size());
        assertTrue(report.rows().stream().allMatch(row -> row.finish() >= 0), "los dos terminan");
        assertNull(report.row("p1/h1").failure());
        assertTrue(report.row("p1/h1").finish() <= report.row("p2/h1").start(),
                "el segundo no empieza hasta que el primero acaba");
    }

    @Test
    @DisplayName("con Round Robin los turnos se alternan en vez de esperar al final")
    void roundRobinAlternaTurnos() {
        Kernel kernel = new Kernel(new RoundRobinScheduler(4), 256);
        kernel.createProcess("alfa", contador("contador-a"));
        kernel.createProcess("beta", contador("contador-b"));

        KernelReport report = kernel.run();
        List<String> cambios = cambiosDeContexto(kernel);

        assertEquals(List.of("p1/h1", "p2/h1", "p1/h1", "p2/h1"),
                cambios.subList(0, 4), "los cuatro primeros turnos se reparten");
        assertTrue(cambios.stream().filter("p1/h1"::equals).count() > 1,
                "el primer hilo recupera la CPU: hubo desalojo");
        assertTrue(kernel.trace().events().stream()
                .anyMatch(event -> event.kind() == EventKind.PREEMPT), "se registro el desalojo");
        assertTrue(report.rows().stream().allMatch(row -> row.finish() >= 0));
    }

    @Test
    @DisplayName("el mismo escenario produce siempre la misma traza")
    void laTrazaEsReproducible() {
        List<String> primera = escenario().trace().lines();
        List<String> segunda = escenario().trace().lines();

        assertEquals(primera, segunda, "el reloj es por instruccion, no por tiempo real");
        assertTrue(primera.size() > 10, "la traza no esta vacia");
    }

    private static Kernel escenario() {
        Kernel kernel = new Kernel(new RoundRobinScheduler(3), 256);
        kernel.createProcess("alfa", contador("contador-a"));
        kernel.createProcess("beta", contador("contador-b"));
        kernel.run();
        return kernel;
    }

    @Test
    @DisplayName("SPAWN crea un hilo hermano que comparte el proceso")
    void spawnCreaHiloHermano() {
        Kernel kernel = new Kernel(new RoundRobinScheduler(2), 256);
        kernel.createProcess("lanzador", Assembler.load("lanza", LANZA_HILO));

        KernelReport report = kernel.run();

        assertEquals(2, report.rows().size(), "el proceso acaba con dos hilos");
        assertNotNull(report.row("p1/h2"), "el hilo creado se llama p1/h2");
        assertTrue(report.rows().stream().allMatch(row -> row.finish() >= 0));
        assertEquals(1, kernel.processes().size(), "los dos hilos son del mismo proceso");
    }

    @Test
    @DisplayName("SLEEP bloquea al hilo y el reloj salta hasta su despertar")
    void sleepBloqueaYDespierta() {
        Kernel kernel = new Kernel(new FcfsScheduler(), 256);
        kernel.createProcess("dormilon", Assembler.load("duerme", "SLEEP 10\nEXIT\n"));

        KernelReport report = kernel.run();

        Trace.Event bloqueo = kernel.trace().events().stream()
                .filter(event -> event.kind() == EventKind.BLOCK)
                .findFirst().orElseThrow(() -> new AssertionError("no hubo bloqueo"));
        Trace.Event despertar = kernel.trace().events().stream()
                .filter(event -> event.kind() == EventKind.WAKE)
                .findFirst().orElseThrow(() -> new AssertionError("no hubo despertar"));

        assertTrue(despertar.tick() > bloqueo.tick(), "despierta despues de bloquearse");
        assertTrue(despertar.tick() >= 10, "duerme al menos los 10 ticks pedidos");
        assertTrue(kernel.trace().events().stream()
                .anyMatch(event -> event.kind() == EventKind.IDLE), "el reloj salto sin girar en vacio");
        assertTrue(report.row("p1/h1").finish() >= 10);
    }

    @Test
    @DisplayName("un proceso que revienta no se lleva por delante a los demas")
    void unFalloNoTumbaElSistema() {
        Kernel kernel = new Kernel(new FcfsScheduler(), 256);
        kernel.createProcess("roto", Assembler.load("roto", "STORE 900\nEXIT\n"));
        kernel.createProcess("sano", contador("contador-b"));

        KernelReport report = kernel.run();

        assertNotNull(report.row("p1/h1").failure(), "el fallo queda anotado en su hilo");
        assertNull(report.row("p2/h1").failure(), "el otro proceso no se entera");
        assertTrue(report.row("p2/h1").finish() >= 0, "el sano termina igualmente");
        assertTrue(kernel.trace().events().stream()
                .anyMatch(event -> event.kind() == EventKind.FAULT));
    }

    @Test
    @DisplayName("las metricas cuadran con lo que dice la traza")
    void metricasCoherentes() {
        Kernel kernel = new Kernel(new RoundRobinScheduler(4), 256);
        kernel.createProcess("alfa", contador("contador-a"));
        kernel.createProcess("beta", contador("contador-b"));

        KernelReport report = kernel.run();

        for (KernelReport.Row row : report.rows()) {
            assertEquals(row.turnaround(), row.finish() - row.arrival(),
                    "retorno = fin - llegada en " + row.thread());
            assertEquals(row.turnaround() - row.cpu(), row.waitTicks(),
                    "espera = retorno - cpu en " + row.thread());
            assertTrue(row.response() >= 0, "todo hilo llego a ejecutar");
            assertTrue(row.cpu() > 0, "todo hilo gasto cpu");
        }
        assertTrue(report.summary().stream().anyMatch(line -> line.contains("retorno medio")));
    }

    @Test
    @DisplayName("un proceso que llega tarde espera a su tick de llegada")
    void respetaLaLlegadaTardia() {
        Kernel kernel = new Kernel(new FcfsScheduler(), 256);
        Program program = Assembler.load("corto", "SET 1\nEXIT\n");
        kernel.createProcess("pronto", program, 0);
        kernel.createProcess("tarde", program, 100);

        KernelReport report = kernel.run();

        assertEquals(100, report.row("p2/h1").arrival());
        assertTrue(report.row("p2/h1").start() >= 100, "no se adelanta a su llegada");
        assertTrue(kernel.trace().events().stream()
                .anyMatch(event -> event.kind() == EventKind.ADMIT && "p2/h1".equals(event.who())));
    }

    @Test
    @DisplayName("el nucleo no se puede ejecutar dos veces")
    void noSeEjecutaDosVeces() {
        Kernel kernel = new Kernel(new FcfsScheduler(), 256);
        kernel.createProcess("uno", Assembler.load("corto", "EXIT\n"));
        kernel.run();

        assertThrows(IllegalStateException.class, kernel::run);
    }

    @Test
    @DisplayName("no se admiten procesos nuevos con el nucleo en marcha")
    void noAdmiteProcesosEnMarcha() {
        Kernel kernel = new Kernel(new FcfsScheduler(), 256);
        kernel.createProcess("uno", Assembler.load("corto", "EXIT\n"));
        kernel.run();

        assertThrows(IllegalStateException.class,
                () -> kernel.createProcess("dos", Assembler.load("otro", "EXIT\n")));
    }

    @Test
    @DisplayName("un hilo que solo cede el turno no se queda sin ejecutar")
    void cedeElTurnoYVuelve() {
        Kernel kernel = new Kernel(new RoundRobinScheduler(1), 256);
        kernel.createProcess("cedon", Assembler.load("cede", "YIELD\nSET 5\nEXIT\n"));
        kernel.createProcess("otro", Assembler.load("otro", "SET 9\nEXIT\n"));

        KernelReport report = kernel.run();

        assertTrue(kernel.trace().events().stream()
                .anyMatch(event -> event.kind() == EventKind.YIELD));
        assertTrue(report.rows().stream().allMatch(row -> row.finish() >= 0));
    }

    @Test
    @DisplayName("un scheduler nulo o una memoria no positiva se rechazan al construir")
    void parametrosInvalidos() {
        Scheduler nulo = null;
        assertThrows(IllegalArgumentException.class, () -> new Kernel(nulo, 256));
        assertThrows(IllegalArgumentException.class, () -> new Kernel(new FcfsScheduler(), 0));
    }

    @Test
    @DisplayName("el mensaje del fallo dice que direccion se salio del espacio")
    void elFalloDiceQueDireccionFallo() {
        Kernel kernel = new Kernel(new FcfsScheduler(), 256);
        kernel.createProcess("roto", Assembler.load("roto", "STORE 700\nEXIT\n"));

        kernel.run();

        String failure = kernel.report().rows().get(0).failure();
        assertNotNull(failure);
        assertTrue(failure.contains("700"), failure);
    }
}
