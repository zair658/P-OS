package pos.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import pos.base.PosFault;
import pos.proc.Assembler;
import pos.proc.Pcb;
import pos.proc.ProcessFactory;
import pos.proc.Tcb;

@DisplayName("El banquero no concede lo que dejaria el sistema sin salida")
class BankerTest {

    /** Programa de relleno: al banquero no le importa lo que ejecuten los hilos. */
    private static Pcb proceso(int id, String nombre) {
        return ProcessFactory.newProcess(id, nombre, Assembler.load(nombre, "SET 1\nEXIT"), 16, 0);
    }

    /** Un hilo de un proceso nuevo, listo para pedir recursos. */
    private static Tcb hilo(int id, String nombre) {
        return ProcessFactory.newThread(proceso(id, nombre), 1, 0);
    }

    // ------------------------------------------------------------------
    // Declaracion
    // ------------------------------------------------------------------

    @Test
    @DisplayName("un recurso se declara con sus instancias y no se redeclara")
    void declaraUnaVez() {
        Banker banquero = new Banker();
        banquero.declareResource("impresora", 3);

        assertEquals(3, banquero.capacityOf("impresora"));
        assertEquals(List.of("impresora"), banquero.resources());
        assertThrows(PosFault.class, () -> banquero.declareResource("impresora", 5));
    }

    @Test
    @DisplayName("un recurso sin instancias no tiene sentido")
    void noAdmiteCeroInstancias() {
        Banker banquero = new Banker();

        assertThrows(PosFault.class, () -> banquero.declareResource("impresora", 0));
        assertEquals(-1, banquero.capacityOf("impresora"));
    }

    @Test
    @DisplayName("el maximo declarado no puede superar lo que hay en el sistema")
    void maximoNoSuperaLaCapacidad() {
        Banker banquero = new Banker();
        banquero.declareResource("impresora", 3);
        Tcb h1 = hilo(1, "editor");

        assertThrows(PosFault.class, () -> banquero.claim(h1, "impresora", 4));
        banquero.claim(h1, "impresora", 3);
    }

    @Test
    @DisplayName("declarar dos veces el maximo del mismo recurso es un error del programa")
    void noSeDeclaraDosVecesElMismoMaximo() {
        Banker banquero = new Banker();
        banquero.declareResource("impresora", 3);
        Tcb h1 = hilo(1, "editor");
        banquero.claim(h1, "impresora", 2);

        assertThrows(PosFault.class, () -> banquero.claim(h1, "impresora", 1));
    }

    @Test
    @DisplayName("pedir sin declarar maximo se denuncia en vez de concederse")
    void noSePuedePedirSinDeclarar() {
        Banker banquero = new Banker();
        banquero.declareResource("impresora", 3);
        Tcb h1 = hilo(1, "editor");

        // El banquero no puede decidir si no sabe cuanto va a pedir como mucho.
        assertThrows(PosFault.class, () -> banquero.request(h1, "impresora", 1));
        assertEquals(3, banquero.availableOf("impresora"));
    }

    @Test
    @DisplayName("pedir mas de lo declarado no es esperar: es incumplir la promesa")
    void pedirMasDeLoDeclaradoSeDenuncia() {
        Banker banquero = new Banker();
        banquero.declareResource("impresora", 3);
        Tcb h1 = hilo(1, "editor");
        banquero.claim(h1, "impresora", 2);

        assertEquals(Banker.Verdict.GRANTED, banquero.request(h1, "impresora", 2).verdict());
        assertEquals(Banker.Verdict.EXCEEDS_MAX, banquero.request(h1, "impresora", 1).verdict());
    }

    @Test
    @DisplayName("usar un recurso que nadie declaro se denuncia")
    void recursoInexistente() {
        Banker banquero = new Banker();
        Tcb h1 = hilo(1, "editor");

        assertThrows(PosFault.class, () -> banquero.claim(h1, "impresora", 1));
        banquero.declareResource("impresora", 1);
        assertThrows(PosFault.class, () -> banquero.request(h1, "escaner", 1));
    }

    // ------------------------------------------------------------------
    // Reparto normal
    // ------------------------------------------------------------------

    @Test
    @DisplayName("con instancias de sobra se concede en el acto")
    void concedeCuandoCabeYEsSeguro() {
        Banker banquero = new Banker();
        banquero.declareResource("impresora", 3);
        Tcb h1 = hilo(1, "editor");
        banquero.claim(h1, "impresora", 2);

        Banker.Answer respuesta = banquero.request(h1, "impresora", 2);

        assertEquals(Banker.Verdict.GRANTED, respuesta.verdict());
        assertEquals(1, banquero.availableOf("impresora"));
        assertEquals(0, banquero.pendingCount());
    }

    @Test
    @DisplayName("sin instancias libres el proceso espera")
    void esperaCuandoNoQuedan() {
        Banker banquero = new Banker();
        banquero.declareResource("impresora", 2);
        Tcb h1 = hilo(1, "uno");
        Tcb h2 = hilo(2, "dos");
        Tcb h3 = hilo(3, "tres");
        banquero.claim(h1, "impresora", 1);
        banquero.claim(h2, "impresora", 1);
        banquero.claim(h3, "impresora", 1);

        assertEquals(Banker.Verdict.GRANTED, banquero.request(h1, "impresora", 1).verdict());
        assertEquals(Banker.Verdict.GRANTED, banquero.request(h2, "impresora", 1).verdict());
        Banker.Answer tercera = banquero.request(h3, "impresora", 1);

        assertEquals(Banker.Verdict.WAIT, tercera.verdict());
        assertEquals(0, banquero.availableOf("impresora"));
        assertEquals(1, banquero.pendingCount());
        assertEquals(1, banquero.pendingFor(h3));
        // El motivo dice que no queda nada, no que sea inseguro.
        assertTrue(tercera.reason().contains("no quedan"), tercera.reason());
    }

    // ------------------------------------------------------------------
    // El caso de manual: hay instancias libres y aun asi se niega
    // ------------------------------------------------------------------

    /**
     * Tres instancias, dos procesos. Uno tiene una y puede acabar con una mas;
     * el otro tiene una y necesita dos. Queda una libre.
     *
     * El estado de partida es seguro: si el primero acaba, suelta su instancia y
     * el segundo puede terminar. Pero si la libre se le da al segundo, los dos
     * quedan con lo mismo que necesitan y nadie suelta: abrazo mortal. El
     * banquero lo ve, no concede y deja al segundo esperando.
     */
    private static final class Escenario {

        private final Banker banquero = new Banker();
        private final Tcb primero = hilo(1, "primero");
        private final Tcb segundo = hilo(2, "segundo");

        private Escenario() {
            banquero.declareResource("impresora", 3);
            banquero.claim(primero, "impresora", 2);
            banquero.claim(segundo, "impresora", 3);
            banquero.request(primero, "impresora", 1);
            banquero.request(segundo, "impresora", 1);
        }
    }

    @Test
    @DisplayName("el estado de partida es seguro y hay una instancia libre")
    void elPuntoDePartidaEsSeguro() {
        Escenario escenario = new Escenario();

        assertTrue(escenario.banquero.isSafe());
        assertEquals(1, escenario.banquero.availableOf("impresora"));
    }

    @Test
    @DisplayName("se niega una peticion aunque queden instancias, si dejaria el reparto sin salida")
    void niegaAunqueQuedenInstancias() {
        Escenario escenario = new Escenario();

        Banker.Answer respuesta = escenario.banquero.request(escenario.segundo, "impresora", 1);

        assertEquals(Banker.Verdict.WAIT, respuesta.verdict());
        // La instancia sigue libre: no se ha concedido nada.
        assertEquals(1, escenario.banquero.availableOf("impresora"));
        assertEquals(1, escenario.banquero.pendingCount());
        assertEquals(1, escenario.banquero.pendingFor(escenario.segundo));
        assertTrue(respuesta.reason().contains("sin ningun orden seguro"), respuesta.reason());
    }

    /**
     * Que cada concesion se compruebe antes de hacerla tiene una consecuencia que
     * conviene dejar escrita: por muchas peticiones que se concedan, el reparto
     * nunca se queda sin orden de salida. No hay forma de llegar a un estado
     * inseguro pidiendo; habria que repartir sin preguntar.
     */
    @Test
    @DisplayName("conceder nunca deja el reparto sin orden seguro")
    void concederMantieneElEstadoSeguro() {
        Banker banquero = new Banker();
        banquero.declareResource("impresora", 4);
        Tcb uno = hilo(1, "uno");
        Tcb dos = hilo(2, "dos");
        Tcb tres = hilo(3, "tres");
        banquero.claim(uno, "impresora", 2);
        banquero.claim(dos, "impresora", 2);
        banquero.claim(tres, "impresora", 3);

        // Aunque el monton se quede vacio, el estado es seguro: el primero tiene
        // todo lo que pidio y puede terminar, y al hacerlo suelta lo suyo.
        assertEquals(Banker.Verdict.GRANTED, banquero.request(uno, "impresora", 2).verdict());
        assertEquals(Banker.Verdict.GRANTED, banquero.request(dos, "impresora", 2).verdict());
        assertTrue(banquero.isSafe());
        assertEquals(0, banquero.availableOf("impresora"));

        // La siguiente se niega por falta de instancias, no por inseguridad.
        Banker.Answer siguiente = banquero.request(tres, "impresora", 1);
        assertEquals(Banker.Verdict.WAIT, siguiente.verdict());
        assertTrue(siguiente.reason().contains("no quedan"), siguiente.reason());
    }

    @Test
    @DisplayName("al devolver se reintenta lo que esperaba y se concede")
    void devolverConcedeLoPendiente() {
        Escenario escenario = new Escenario();
        escenario.banquero.request(escenario.segundo, "impresora", 1);

        List<Tcb> despertados = escenario.banquero.release(escenario.primero, "impresora", 1);

        assertEquals(1, despertados.size());
        assertEquals(escenario.segundo, despertados.get(0));
        assertEquals(0, escenario.banquero.pendingCount());
        // De las dos libres, una pasa al segundo; la otra se queda en el monton.
        assertEquals(1, escenario.banquero.availableOf("impresora"));
        assertTrue(escenario.banquero.isSafe());
    }

    @Test
    @DisplayName("devolver mas de lo que se tiene es un error del programa")
    void noSeDevuelveLoQueNoSeTiene() {
        Banker banquero = new Banker();
        banquero.declareResource("impresora", 3);
        Tcb h1 = hilo(1, "editor");
        banquero.claim(h1, "impresora", 2);
        banquero.request(h1, "impresora", 1);

        assertThrows(PosFault.class, () -> banquero.release(h1, "impresora", 2));
        assertEquals(2, banquero.availableOf("impresora"));
    }

    // ------------------------------------------------------------------
    // Fin del proceso
    // ------------------------------------------------------------------

    @Test
    @DisplayName("al terminar un proceso sus recursos vuelven y se atiende a quien esperaba")
    void alTerminarSeLibera() {
        Escenario escenario = new Escenario();
        escenario.banquero.request(escenario.segundo, "impresora", 1);

        List<Tcb> despertados = escenario.banquero.releaseAll(1);

        assertEquals(1, despertados.size());
        // El proceso muerto sale de la tabla: no puede seguir contando.
        assertFalse(escenario.banquero.describe().stream()
                .anyMatch(linea -> linea.startsWith("primero")));
        assertTrue(escenario.banquero.isSafe());
    }

    @Test
    @DisplayName("un proceso que termina esperando no ocupa sitio en la cola")
    void peticionDeUnMuertoSeDescarta() {
        Escenario escenario = new Escenario();
        escenario.banquero.request(escenario.segundo, "impresora", 1);
        escenario.segundo.setState(pos.proc.State.TERMINATED);

        escenario.banquero.release(escenario.primero, "impresora", 1);

        assertEquals(0, escenario.banquero.pendingCount());
    }

    // ------------------------------------------------------------------
    // Tabla y secuencia segura
    // ------------------------------------------------------------------

    @Test
    @DisplayName("la secuencia segura nombra a todos los procesos que podrian terminar")
    void secuenciaSeguraConNombres() {
        Escenario escenario = new Escenario();

        List<String> secuencia = escenario.banquero.safeSequence();

        assertEquals(2, secuencia.size());
        assertTrue(secuencia.contains("primero"));
        assertTrue(secuencia.contains("segundo"));
    }

    @Test
    @DisplayName("la tabla dice maximo, tiene y le falta de cada proceso")
    void laTablaDiceLoQueHay() {
        Escenario escenario = new Escenario();

        List<Banker.Row> filas = escenario.banquero.table();

        assertEquals(2, filas.size());
        Banker.Row primera = filas.get(0);
        assertEquals("primero", primera.process());
        assertEquals("impresora", primera.resource());
        assertEquals(2, primera.maximum());
        assertEquals(1, primera.allocated());
        assertEquals(1, primera.need());
    }

    @Test
    @DisplayName("sin procesos con maximos el estado es seguro y no hay nada que repartir")
    void sinProcesosEsSeguro() {
        Banker banquero = new Banker();
        banquero.declareResource("impresora", 3);

        assertTrue(banquero.isSafe());
        assertTrue(banquero.safeSequence().isEmpty());
        assertEquals(3, banquero.availableOf("impresora"));
        assertTrue(banquero.table().isEmpty());
    }
}
