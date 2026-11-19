# P-OS

Sistema operativo didáctico escrito en Java, para la asignatura de Sistemas
Operativos. No simula desde fuera: mantiene memoria real por bloques, procesos e
hilos reales sobre hilos de la máquina virtual, un sistema de ficheros propio y
una terminal que es un proceso más del sistema.

## Estado

| Fase | Contenido | Estado |
|---|---|---|
| 0 | Entorno de compilación y esqueleto del proyecto | terminada |
| 1 | Memoria física por bloques y gestores de huecos | terminada |
| 2 | Procesos, hilos, intérprete y planificación FCFS / Round Robin | terminada |
| 3 | Memoria virtual: contiguo, paginado y segmentado | terminada |
| 3b | Páginas por demanda, área de intercambio y políticas FIFO / LRU / OPT | terminada |
| 4 | Sistema de ficheros, imagen por bloques y tres métodos de asignación | terminada |
| 5 | Cerrojos, exclusión mutua y detección de interbloqueo | terminada |
| 6 | Semáforos, variables de condición y monitores | terminada |
| 7 | Llamadas al sistema, descriptores y terminal | terminada |

Las fases restantes están descritas en `docs/arquitectura.md`.

## Cómo compilar y ejecutar

Requiere Java 21 y Maven. En este equipo ya están instalados y se cargan con:

```bash
. ~/.local/env-pos.sh
```

Después:

```bash
mvn test                     # pruebas; imprime las tablas comparativas
mvn package                  # genera el ejecutable
java -jar target/p-os-0.1.0-SNAPSHOT.jar
java -cp target/p-os-0.1.0-SNAPSHOT.jar pos.launch.Main --memoria
java -cp target/p-os-0.1.0-SNAPSHOT.jar pos.launch.Main --swap
java -cp target/p-os-0.1.0-SNAPSHOT.jar pos.launch.Main --disco
java -cp target/p-os-0.1.0-SNAPSHOT.jar pos.launch.Main --concurrencia
java -cp target/p-os-0.1.0-SNAPSHOT.jar pos.launch.Main --sincronizacion
java -cp target/p-os-0.1.0-SNAPSHOT.jar pos.launch.Main --medicion
java -cp target/p-os-0.1.0-SNAPSHOT.jar pos.launch.Main --medicion --csv
java -cp target/p-os-0.1.0-SNAPSHOT.jar pos.launch.Main --shell
```

`--memoria` ejecuta la demostración de los tres esquemas de memoria: el mismo
programa con los tres, la tabla de traducción de cada uno y la prueba de
fragmentación. `--disco` hace lo mismo con el sistema de ficheros: los tres
métodos de asignación sobre el mismo contenido, el mismo hueco pedido por los
tres y una imagen estropeada que se niega a montar. `--swap` hace lo mismo con
la paginación por demanda: la misma traza con FIFO, LRU y OPT, la anomalía de
Belady reproducida, un proceso con más páginas que marcos que recupera sus datos
del área de intercambio, y el fichero `/intercambio` mirado por dentro.
`--concurrencia` hace lo mismo con la sincronización: la carrera entre dos hilos
que pierde actualizaciones bajo Round Robin, la traza del cerrojo que la arregla
y un abrazo mortal entre dos hilos que el sistema detecta en vez de colgarse.
`--sincronizacion` hace lo mismo con los mecanismos de espera: el mismo contador
protegido con cerrojo y con semáforo, un productor y un consumidor que se dan el
turno con dos semáforos sin gastar CPU, y un monitor con variable de condición
donde se ve que el despertado tiene que volver a pedir el cerrojo. `--medicion`
no enseña mecanismos: cronometra los tres con el mismo problema y saca la tabla
de costes en ticks, con la columna de resultado al lado para que no se confunda
«más barato» con «correcto»; `--medicion --csv` la da en formato de hoja de
cálculo. `--shell` monta una imagen de ficheros y abre una sesión de terminal
sobre ella: ordenes con tuberías y redirecciones, dos procesos de cálculo
compartiendo procesador con la sesión, y la tabla de llamadas que el núcleo
atendió, con su coste en tics. El arranque sin argumentos compara los
planificadores.

## Documentación

- `docs/arquitectura.md`: documento de arquitectura, con las decisiones y su
  motivo, el modelo de ejecución y las fases.
- `docs/fase-01-memoria.md`: qué se implementó en la primera fase, con las
  mediciones de los gestores de huecos.
- `docs/fase-02-procesos.md`: qué se implementó en la segunda, el modelo de
  ejecución y la comparación de planificadores.
- `docs/fase-03-memoria.md`: qué se implementó en la tercera, los tres esquemas
  de memoria con sus tablas de traducción y la medición de la fragmentación.
- `docs/fase-03b-intercambio.md`: qué añadió la fase 3b, la paginación por
  demanda, el área de intercambio como fichero y la comparación de políticas.
- `docs/fase-04-disco.md`: qué se implementó en la cuarta, la imagen por bloques,
  los tres métodos de asignación y la verificación al montar.
- `docs/fase-05-concurrencia.md`: qué añadió la quinta, los cerrojos con cola de
  espera, la cesión directa, la detección de ciclos y la demostración de la
  carrera.
- `docs/fase-06-sincronizacion.md`: qué añadió la sexta, los semáforos, las
  variables de condición atadas a un cerrojo, la readquisición al despertar, la
  comparación entre ambos mecanismos y la tabla de costes medida con `--medicion`.
- `docs/fase-07-llamadas.md`: qué añadió la séptima, la frontera de las llamadas
  al sistema, la tabla de descriptores, la terminal como proceso, el intérprete
  de líneas con tuberías y la contabilidad por código.

## Convenciones

Comentarios en español y sin emojis. Nombres de clases y métodos en inglés, con
los términos de la asignatura. Los textos que ve el usuario, en español, salvo
los nombres de comando.
