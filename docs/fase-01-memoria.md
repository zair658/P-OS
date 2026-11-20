# Fase 1. Memoria física y gestores de huecos

Estado: terminada y verificada. 47 pruebas en verde.

## Qué hay implementado

| Clase | Paquete | Responsabilidad |
|---|---|---|
| `PhysicalMemory` | `pos.phys` | Memoria real por bloques: contenido, propietario de cada unidad, huecos libres, invariantes |
| `FreeSpan` | `pos.base` | Tramo contiguo inmutable, sirve para huecos y para regiones ocupadas |
| `SlotManager` | `pos.alloc` | Contrato de los gestores de huecos |
| `AbstractSlotManager` | `pos.alloc` | Forma común: descartar lo que no cabe, ordenar por rango, desempatar por dirección menor |
| `BestFit`, `WorstFit` | `pos.alloc` | Los dos gestores de referencia |
| `ScoringHybrid` | `pos.alloc` | Gestor propio con dos parámetros |
| `SlotChoice`, `SlotRejection` | `pos.alloc` | Decisión con su motivo, incluidos los huecos descartados |

La memoria se crea con 4096 unidades en el arranque, en bloques de 64 para
dibujar el mapa.

## Dos decisiones que se sostienen solas

**Un solo dueño del dato.** `PhysicalMemory` es la única clase que guarda el
propietario de cada unidad. La lista de huecos libres no se almacena en ningún
sitio: se calcula a partir del mapa de propietarios. De ahí salen dos ventajas
que se notan en cuanto el sistema crece. No existe la posibilidad de que la lista
de huecos y el mapa de ocupación se desincronicen, porque la lista no es un dato,
es una vista. Y la fusión de huecos al liberar no es una operación que haya que
programar: es lo que ocurre cuando dos unidades libres contiguas se leen juntas.
La prueba `liberarFusionaLosHuecos` lo verifica liberando el bloque intermedio de
tres.

**Toda escritura se comprueba.** Escribir o leer sobre memoria libre lanza una
excepción, no devuelve ceros en silencio. Un sistema que tolera eso esconde el
fallo que más caro sale en la defensa: el de un puntero que se fue a donde no
debía.

## El gestor propio

BestFit elige siempre el encaje más ajustado. Cuando ese encaje deja un resto
diminuto, el resto no se puede volver a usar: el hueco no se ha aprovechado, se
ha perdido. El gestor llama miga a ese resto y lo cobra.

```
resto(s) = longitud(s) - pedido

puntuación(s) = 0                       si resto(s) == 0        encaje exacto
              = pesoMiga * longitud(s)  si resto(s) < mínimoÚtil
              = resto(s)                en otro caso
```

Parámetros por defecto: mínimo útil 16 unidades, peso de la miga 1. Los dos se
pueden cambiar y hay pruebas de que el cambio altera la decisión
(`elMinimoEsUnParametroReal`).

Hay un caso límite que conviene tener probado: con el mínimo útil a cero, la
corrección desaparece y el gestor coincide con BestFit en todos los tamaños del
1 al 65. Eso demuestra que el híbrido es una corrección de BestFit y no otra cosa
disfrazada (`conMinimoCeroEquivaleABestFit`).

## Medición, sin maquillar

Tres cargas sobre las tres estrategias. Las tablas las imprime la propia suite
(`mvn test`), así que se pueden reproducir.

**Huecos de 50 y 70, después una petición de 60.**

| gestor | atendidas | fallidas | huecos muertos | mayor hueco |
|---|---|---|---|---|
| best-fit | 6 | 0 | 2 / 20 u | 10 |
| worst-fit | 5 | 1 | 0 / 0 u | 50 |
| híbrido | 5 | 1 | 0 / 0 u | 50 |

Gana BestFit. El híbrido rechaza la petición de 40 en el hueco de 50 porque solo
deja miga, se come el de 70 y cuando llega la de 60 no tiene dónde ponerla.
BestFit usa el de 50, conserva el de 70 entero y la atiende. Esta carga está en
la suite a propósito: un gestor propio que solo se compara en la tabla donde gana
no demuestra nada.

**Huecos de 70, 50 y 50, dos peticiones de 40.**

| gestor | atendidas | fallidas | huecos muertos | mayor hueco |
|---|---|---|---|---|
| best-fit | 8 | 0 | 3 / 30 u | 70 |
| worst-fit | 8 | 0 | 2 / 20 u | 50 |
| híbrido | 8 | 0 | 2 / 20 u | 50 |

Aquí el híbrido gana en su métrica: misma cantidad de peticiones atendidas, una
miga menos. BestFit consume los dos huecos de 50 y deja tres trozos de 10; el
híbrido consume el de 70 y uno de 50, deja dos trozos de 10 y conserva un hueco
de 50 entero, que sí sirve.

**Carga variada con liberaciones intercaladas.**

| gestor | atendidas | fallidas | huecos muertos | mayor hueco |
|---|---|---|---|---|
| best-fit | 16 | 2 | 2 / 16 u | 32 |
| worst-fit | 15 | 3 | 1 / 8 u | 32 |
| híbrido | 16 | 2 | 2 / 16 u | 32 |

Empate técnico con BestFit.

## Conclusión que se lleva a la defensa

BestFit es difícil de batir en peticiones atendidas, y se puede explicar en una
frase: al dejar siempre el resto mínimo, casi nunca rechaza una petición que otro
gestor aceptaría. El híbrido no lo supera en esa métrica y hay una carga que lo
demuestra. Lo que aporta es control sobre la forma del hueco que queda: permite
fijar explícitamente qué resto merece la pena conservar, y con ello deja menos
huecos inservibles. Es una variante con parámetros medibles, no una mejora
universal, y decirlo así es más defendible que afirmar lo contrario.

## Comandos

```bash
. ~/.local/env-pos.sh      # Java 21 y Maven, sin tocar el sistema
cd ~/p-os
mvn test                   # pruebas y tablas comparativas
mvn package                # genera target/p-os-0.8.0-SNAPSHOT.jar
java -jar target/p-os-0.8.0-SNAPSHOT.jar
```

Salida del arranque en esta fase:

```
P-OS 0.8

memoria fisica ....... 4096 unidades
unidades libres ...... 3968
huecos libres ........ 2
mapa ................. 1.2.....................................................
invariantes .......... OK

estado: nucleo de memoria operativo, sin terminal ni interfaz todavia
```

## Lo que no hay todavía

- Traducción de direcciones y los tres esquemas de memoria: fase 3.
- La interfaz no existe; el mapa se ve en texto.
- El compilador está en modo estricto: cualquier aviso lo trata como error
  (`-Xlint:all -Werror`). La suite entera tarda menos de dos segundos, así que se
  puede correr completa en cada cambio.
