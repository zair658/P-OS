package pos.ui;

import java.util.List;

/**
 * Una tabla de la ventana, en abstracto.
 *
 * La separacion es deliberada: aqui no hay ni una clase de Swing. Un modelo sabe
 * que columnas tiene y como se rellena a partir de una {@link Snapshot}; quien
 * lo pinta es otra capa. Asi las tablas se pueden probar con un test normal
 * -- sin ventana, sin pantalla y sin esperar a que se repinte nada -- que es lo
 * unico que permite afirmar que una columna ensena lo que dice ensenar.
 */
public interface Table {

    /** Titulo de la tabla, el que se ve encima de ella. */
    String title();

    /** Cabeceras de las columnas, en orden. */
    String[] headers();

    /** Filas de la tabla para una foto concreta. */
    List<String[]> rows(Snapshot shot);

    /** Una linea de contexto: que se esta viendo y cuanto. */
    String summary(Snapshot shot);
}
