package pos.launch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * La medicion tambien se comprueba.
 *
 * Una tabla de tiempos que no se pueda repetir no vale como prueba de nada: si
 * los numeros cambiaran en cada ejecucion, la comparacion seria una anecdota.
 * Por eso la mitad de estas pruebas no miran el reloj, miran que el reloj de
 * hoy sea el de ayer.
 */
@DisplayName("La tabla de medicion da siempre las mismas cifras y dice la verdad")
@Timeout(value = 30, unit = java.util.concurrent.TimeUnit.SECONDS)
class MetricsDemoTest {

    @Test
    @DisplayName("sin proteccion se pierden actualizaciones, y el resultado lo dice")
    void sinProteccionSePierdenActualizaciones() {
        MetricsDemo.Medida sinNada = MetricsDemo.contador().get(0);

        assertNotEquals(10, sinNada.valor(), "con cuanto 1 el desalojo tiene que caer dentro");
        assertTrue(sinNada.resultado().startsWith("incorrecto"),
                "el resultado tiene que delatarlo: " + sinNada.resultado());
    }

    @Test
    @DisplayName("cerrojo y semaforo de valor uno salvan el contador")
    void cerrojoYSemaforoSalvanElContador() {
        List<MetricsDemo.Medida> filas = MetricsDemo.contador();

        assertEquals(10, filas.get(1).valor(), "el cerrojo tiene que dar 10");
        assertEquals(10, filas.get(2).valor(), "el semaforo de valor uno tambien");
        assertEquals("correcto", filas.get(1).resultado());
        assertEquals("correcto", filas.get(2).resultado());
    }

    @Test
    @DisplayName("un semaforo de valor uno cuesta lo mismo que un cerrojo")
    void elSemaforoDeValorUnoNoEsMasBarato() {
        List<MetricsDemo.Medida> filas = MetricsDemo.contador();

        long cerrojo = filas.get(1).reloj();
        long semaforo = filas.get(2).reloj();
        long diferencia = Math.abs(semaforo - cerrojo);

        assertTrue(diferencia <= 2, "no deberia haber mas de un par de ticks: " + diferencia);
        assertEquals(filas.get(1).sincronizacion(), filas.get(2).sincronizacion() - 1,
                "el semaforo solo añade el SEM_INIT que crea la tabla");
    }

    @Test
    @DisplayName("el giro, los semaforos y la condicion entregan las tres piezas")
    void losTresMecanismosEntreganLasPiezas() {
        List<MetricsDemo.Medida> filas = MetricsDemo.consumidor();

        assertEquals(3, filas.get(0).valor(), "giro");
        assertEquals(3, filas.get(1).valor(), "dos semaforos");
        assertEquals(3, filas.get(3).valor(), "cerrojo y condicion");
        assertTrue(filas.stream().filter(f -> f.valor() != null).allMatch(MetricsDemo.Medida::correcto));
    }

    @Test
    @DisplayName("un permiso para tres piezas acaba en interbloqueo, no en dato raro")
    void unPermisoPorTresAcabaEnInterbloqueo() {
        MetricsDemo.Medida permiso = MetricsDemo.consumidor().get(2);

        assertNull(permiso.valor(), "sin final no hay volcado de memoria");
        assertEquals("interbloqueo", permiso.resultado());
        assertTrue(Double.isNaN(permiso.esperaMedia()),
                "si no termino ningun hilo no hay espera media que dar");
    }

    @Test
    @DisplayName("el que gira gasta mas ticks que el que se duerme")
    void girarCuestaMasQueDormir() {
        List<MetricsDemo.Medida> filas = MetricsDemo.consumidor();

        long giro = filas.get(0).reloj();
        long semaforos = filas.get(1).reloj();

        assertTrue(giro > semaforos, "girar tiene que salir mas caro: " + giro + " vs " + semaforos);
        assertTrue(filas.get(0).despertares() >= filas.get(1).despertares(),
                "el que gira tambien se bloquea, pero por el cerrojo, no por el dato");
    }

    @Test
    @DisplayName("la condicion da un paso mas que el semaforo")
    void laCondicionCuestaMasQueElSemaforo() {
        List<MetricsDemo.Medida> filas = MetricsDemo.consumidor();

        assertTrue(filas.get(3).reloj() > filas.get(1).reloj(),
                "readquirir el cerrojo se paga en ticks");
        assertTrue(filas.get(3).sincronizacion() > filas.get(1).sincronizacion(),
                "y se paga en instrucciones de sincronizacion");
    }

    @Test
    @DisplayName("las cifras se repiten: la tabla es reproducible")
    void lasCifrasSeRepiten() {
        for (int vuelta = 0; vuelta < 2; vuelta++) {
            List<MetricsDemo.Medida> primera = MetricsDemo.consumidor();
            List<MetricsDemo.Medida> segunda = MetricsDemo.consumidor();

            for (int i = 0; i < primera.size(); i++) {
                assertEquals(primera.get(i).reloj(), segunda.get(i).reloj(),
                        "reloj distinto en " + primera.get(i).nombre());
                assertEquals(primera.get(i).bloqueos(), segunda.get(i).bloqueos(),
                        "bloqueos distintos en " + primera.get(i).nombre());
                assertEquals(primera.get(i).despertares(), segunda.get(i).despertares(),
                        "despertares distintos en " + primera.get(i).nombre());
                assertEquals(primera.get(i).valor(), segunda.get(i).valor(),
                        "dato distinto en " + primera.get(i).nombre());
            }
        }
    }

    @Test
    @DisplayName("el coste por vuelta baja al crecer el trabajo, no sube")
    void elCostePorVueltaNoCrece() {
        MetricsDemo.Medida corta = MetricsDemo.contadorMedido(2);
        MetricsDemo.Medida larga = MetricsDemo.contadorMedido(20);

        assertEquals(4, corta.valor());
        assertEquals(40, larga.valor());

        double porVueltaCorta = corta.reloj() / 2.0;
        double porVueltaLarga = larga.reloj() / 20.0;
        assertTrue(porVueltaLarga <= porVueltaCorta,
                "el arranque se diluye: " + porVueltaLarga + " deberia ser <= " + porVueltaCorta);
        assertEquals(4L * larga.sincronizacion(), corta.sincronizacion() * 40L,
                "las operaciones de sincronizacion crecen en proporcion al trabajo");
    }
}
