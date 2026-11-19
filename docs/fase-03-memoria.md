# Fase 3: memoria virtual

Esta fase cierra el hueco que quedaba abierto desde la fase 1: había memoria
física real y gestores de huecos, pero los procesos no tenían un espacio de
direcciones propio. Ahora cada proceso ve su propia memoria, el sistema traduce
cada acceso y un proceso no puede tocar la memoria de otro.

Lo que se ha construido, cómo se demuestra y qué queda pendiente.

---

## 1. Qué hay ahora

| Pieza | Clase | Papel |
|---|---|---|
| Espacio de direcciones | `pos.mm.AddressSpace` | Contrato: traducir, leer, escribir, liberar |
| Esquema contiguo | `pos.mm.ContiguousSpace` | Base y límite; traducción por suma |
| Esquema paginado | `pos.mm.PagedSpace` | Tabla de páginas; traducción por marco y desplazamiento |
| Esquema segmentado | `pos.mm.SegmentedSpace` | Un tramo por segmento; traducción por segmento |
| Segmento | `pos.mm.Segment` | Nombre, inicio lógico y longitud |
| Reparto | `pos.mm.SegmentLayout` | Lista de segmentos, con hueco o seguidos |
| Esquema | `pos.mm.MemoryScheme` | Interfaz sellada con los tres, elige el que se pone |
| Configuración | `pos.mm.MemorySetup` | Memoria, esquema, gestor de huecos y tamaño lógico |
| Demostración | `pos.launch.MemoryDemo` | Las tablas de este documento, reproducibles |

El bloque de control de proceso (`pos.proc.Pcb`) ya no guarda un vector de
unidades: guarda su traductor.

---

## 2. La decisión que ordena todo lo demás

**El traductor es el espacio de direcciones del proceso.** No está dentro de él:
la dirección de una unidad es el traductor. Un proceso no tiene forma de leer
memoria física porque no conoce ninguna dirección física; solo puede pedir
«unidad N de mi espacio», y el traductor decide dónde cae eso.

Eso resuelve tres cosas de una vez:

- **Protección.** Una dirección fuera del espacio lógico no se traduce: se
  levanta `PosFault` con el número que se intentó. `LOAD 40` en un proceso de 32
  unidades mata al hilo, no al sistema.
- **Transparencia.** El programa es el mismo con los tres esquemas. El intérprete
  no sabe cuál está puesto y no tiene por qué saberlo.
- **Contabilidad.** El traductor es el único que sabe cuánta memoria física
  ocupa el proceso y cuánta desperdicia, así que puede devolverla entera al
  liberar sin que nadie tenga que recordar dónde estaba.

---

## 3. Los tres esquemas

Los tres reciben la misma petición y devuelven lo mismo. Lo que cambia es cómo
se colocan.

### Contiguo

El proceso vive en un solo tramo. Se guardan dos números, base y límite, y la
traducción es una suma.

```
contiguo: el proceso vive en un solo tramo
  registro base 40, limite 290 (traduccion: fisica = 40 + logica)
  best-fit: elige [40, 4096) de 4056 unidades para 250 pedidas, resto 3806, descartados 0
```

Es el más barato de traducir y el que peor envejece: al liberar procesos quedan
huecos sueltos y un proceso que necesita un tramo grande no entra aunque sobre
memoria.

### Paginado

La memoria lógica se parte en páginas del mismo tamaño y la física en marcos del
mismo tamaño. Cualquier página cabe en cualquier marco, así que el hueco libre
solo tiene que ser del tamaño de una página.

```
paginacion: pagina de 16 unidades, 16 paginas (traduccion: marco * 16 + offset)
  pagina  0 logica [0, 16) -> marco 40 fisico [40, 56)
  pagina  1 logica [16, 32) -> marco 56 fisico [56, 72)
  ...
  ... y 8 paginas mas
```

El coste es la última página: 250 unidades con páginas de 16 ocupan 256. Seis
unidades que el proceso pidió y no puede usar.

### Segmentado

El proceso se declara como una lista de segmentos con nombre y tamaño, y cada
segmento se coloca en un tramo propio. La traducción busca el segmento que
contiene la dirección y suma el desplazamiento.

```
segmentacion: 2 segmentos (traduccion: base + desplazamiento)
  codigo   logico [0, 150) (150) -> fisico [40, 190)
  datos    logico [150, 250) (100) -> fisico [190, 290)
```

Aquí hay algo que el contiguo no tiene: si dos segmentos se colocan lejos, la
memoria entre ellos queda dentro del espacio lógico del proceso pero no apunta a
ningún sitio. Ese hueco no es direccionable y da `PosFault`, que es exactamente
lo que debe pasar.

---

## 4. La demostración que importa

Primero la garantía: el mismo proceso, la misma instrucción, los mismos números
en los tres esquemas. La memoria arranca con 40 unidades ocupadas por otro
proceso para que la traducción no pueda ser la identidad.

```
un proceso de 250 unidades logicas escribe 42 en la direccion 200 y la vuelve a leer

esquema              logico   fisico  desperdicio   fisica(200)  lectura
----------------------------------------------------------------------
contiguo                250      250            0           240       42
paginacion/16           250      256            6           240       42
segmentacion            250      250            0           240       42
```

Los tres devuelven 42 y los tres traducen la dirección 200 a la física 240. Lo
único que cambia es el coste: la paginación ocupa seis unidades de más.

Después, lo que de verdad distingue a los esquemas. Se llena una memoria de 256
unidades con cinco procesos de 50, se liberan el segundo y el quinto, y se pide
un proceso de 60:

```
  mapa tras colocar: 1111222333444555
  ocupadas 250 de 256
  mapa tras liberar: 1111...333444...
  ocupadas 150, huecos [50, 100) de 50, [200, 256) de 56
  el hueco mayor mide 56 unidades

ahora se pide un proceso de 60 unidades logicas

esquema                lo que necesita            resultado
------------------------------------------------------------------------------------------------
contiguo               un tramo de 60             no entra: no hay 60 unidades contiguas (el mayor hueco mide 56)
paginacion/16          4 marcos de 16 (64)        entra en [50, 66) + [66, 82) + [82, 98) + [200, 216)
segmentacion 1x60      un tramo de 60             no entra: no hay 60 unidades contiguas (el mayor hueco mide 56)
segmentacion 30+30     dos tramos de 30           entra en [50, 80) + [200, 230)
```

Sobran 106 unidades libres y el contiguo no puede meter un proceso de 60. Eso es
la fragmentación externa, medida, no descrita.

La segmentación aparece en las dos filas para que se vea que no es magia: con un
segmento de 60 choca igual que el contiguo; con dos de 30 entra, porque solo
necesita tramos de 30. La fragmentación de la segmentación depende del tamaño de
los segmentos, y eso es una decisión de diseño del programa, no del sistema.

Se puede reproducir con:

```bash
java -cp target/p-os-0.1.0-SNAPSHOT.jar pos.launch.Main --memoria
```

---

## 5. Integración con el núcleo

El núcleo pide la memoria **antes** de consumir el identificador del proceso:

```java
AddressSpace space = memory.newSpace(id, name);
nextProcessId++;
```

Si no cabe, se levanta `PosFault`, el proceso no llega a existir y no queda un
hueco en la numeración ni memoria tomada por un proceso fantasma. La prueba
`siNoCabeNoHayProceso` lo comprueba en las dos direcciones.

La liberación ocurre al terminar el hilo, no al cerrar el sistema. Liberar tarde
falsearía cualquier medida de fragmentación tomada a mitad de ejecución, y en la
fase 5 (sincronización) va a haber procesos que terminen con otros vivos.

La traza registra cada asignación y cada liberación con el esquema, las unidades
físicas, cuántos tramos y cuánto se desperdicia. El estado final se puede
comprobar con el invariante de la memoria física, que sigue siendo el mismo de
la fase 1.

El escenario de planificación de la fase 2 usa paginación de 16 unidades. Los
números de las tablas de planificación no han cambiado: la memoria no altera el
reloj, y eso también es una prueba.

---

## 6. Pruebas

Veintidós pruebas nuevas, una por regla de negocio:

| Prueba | Regla que defiende |
|---|---|
| `mismoValorEnLosTresEsquemas` | El programa no depende del esquema |
| `ningunoPisaLaMemoriaAjena` | El proceso se coloca fuera de lo ya ocupado |
| `contiguoTraduceConBase` | Base y límite, traducción por suma |
| `contiguoFallaSinHuecoSuficiente` | Sin tramo no hay proceso, y no queda memoria tomada |
| `paginacionDesperdiciaElRestoDeLaUltimaPagina` | 250 con páginas de 16 son 256 |
| `paginacionTraduceConPaginaYDesplazamiento` | Página y desplazamiento; dos páginas no comparten marco |
| `paginacionEntraDondeElContiguoNo` | Misma memoria, misma petición, distinto resultado |
| `segmentacionTraducePorSegmento` | Base por segmento y desplazamiento |
| `huecoEntreSegmentosFalla` | El hueco lógico no es direccionable |
| `segmentacionExigeQueElRepartoCuadre` | El tamaño lógico sale del reparto, y se comprueba antes de reservar |
| `direccionFueraDelEspacioFalla` | Protección en los tres esquemas |
| `liberarDevuelveTodo` | Se devuelve exactamente lo reservado |
| `dosProcesosNoCompartenMemoria` | Aislamiento entre procesos |
| `liberarUnoNoTocaAlOtro` | Liberar no corrompe al vecino |
| `sinLiberarLaMemoriaSigueOcupada` | Mientras el proceso vive, la memoria está ocupada |
| `elProcesoNaceConMemoria` | Al crear el proceso ya hay espacio |
| `elProgramaUsaSuMemoria` | `STORE` y `LOAD` funcionan de punta a punta |
| `escribirFueraDelEspacioEsUnFallo` | El fallo mata al hilo, no al sistema |
| `laMemoriaSeRecuperaAlTerminar` | Al terminar se devuelve todo |
| `siNoCabeNoHayProceso` | No hay proceso ni fuga de memoria |
| `losTresEsquemasDanLaMismaEjecucion` | El esquema no cambia el reloj ni las métricas |
| `elEsquemaPorDefectoDaParaVarios` | El núcleo sin configuración arranca con sitio de sobra |

Total del proyecto: **103 pruebas en verde**.

---

## 7. Lo que esta fase no hace, y por qué

- **No hay memoria de intercambio (swap).** El sistema entero cabe en memoria
  física. El apartado 8.4 de `docs/arquitectura.md` describe la paginación por
  demanda con FIFO, LRU y OPT, y su cota con la anomalía de Belady; eso **no está
  implementado aquí** y depende de la fase 4, porque el área de intercambio es un
  fichero de P-OS y todavía no hay sistema de ficheros donde ponerla.
- **No hay reemplazo de páginas.** Todas las páginas de un proceso están
  cargadas mientras el proceso vive. Sin swap no hay nada que reemplazar.
- **No hay paginación bajo segmentación.** Sería lo correcto en un sistema real
  (segmentos paginados) y también lo que más código añade. Queda como extensión
  documentada, no como deuda oculta.
- **No hay protección por permisos por segmento.** Un segmento no distingue
  lectura de escritura. Está en el diseño de la fase 5, junto con los cerrojos,
  porque es ahí donde la memoria de solo lectura tiene algo que proteger.

Estas cuatro las tiene que poder explicar quien defienda el proyecto como
decisiones, no como olvidos.

---

## 8. Siguiente fase

La fase 4 es el sistema de ficheros: bloques en disco simulado, asignación
indexada por defecto con contiguo y enlazado como alternativas, y los comandos
de la terminal (`ls`, `mkdir`, `cat`, `write`, `rm`, `df`...). Después, la
sincronización con cerrojos, semáforos y el banquero, y por último la interfaz
con terminal y panel lateral.
