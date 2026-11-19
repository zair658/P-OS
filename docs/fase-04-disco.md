# Fase 4: sistema de ficheros

Hasta aquí el sistema tenía procesos que se ejecutan en memoria y memoria que se
reparte. Nada sobrevivía al cierre: no había dónde dejar un fichero. Esta fase
añade una imagen de disco por bloques, el mapa de bloques libres, los FCB, los
directorios y los tres métodos de asignación, con la misma idea que ordenó la
memoria: el reparto es una política conmutable, no una propiedad del sistema.

Lo que se ha construido, cómo se demuestra y qué queda pendiente.

---

## 1. Qué hay ahora

| Pieza | Clase | Papel |
|---|---|---|
| Imagen | `pos.fs.DiskImage` | Bloques de tamaño fijo, contador de lecturas y escrituras |
| Mapa de libres | `pos.fs.FreeBlockMap` | Un bit por bloque; los huecos se calculan, no se guardan |
| Asignación | `pos.fs.Allocation` | Interfaz sellada: `Empty`, `Contiguous`, `Chained`, `Indexed` |
| Métodos | `pos.fs.AllocationMethod` | Contiguo, enlazado e indexado, conmutables en caliente |
| Reparto | `pos.fs.FsLayout` | Dónde empieza cada zona y cuántos bloques tiene |
| Superbloque | `pos.fs.Superblock` | Identificador, versión, tamaño, método activo, raíz |
| FCB | `pos.fs.Fcb` | Nombre, tamaño, padre, tipo, fechas lógicas y asignación |
| Directorio | `pos.fs.Directory` | Entradas nombre → identificador, en el contenido de un FCB |
| Sistema | `pos.fs.FileSystem` | Crear, leer, escribir, borrar, listar, guardar, montar |
| Verificación | `pos.fs.FsCheck` | Contraste mapa/FCB/directorios antes de dar la imagen por buena |
| Demostración | `pos.launch.FileSystemDemo` | Las tablas de este documento, reproducibles (`--disco`) |

La imagen se guarda en un `byte[]`. No hay fichero en disco del anfitrión: la
imagen es un valor, se puede copiar, comparar y estropear a propósito en una
prueba sin tocar nada más.

---

## 2. La decisión que ordena todo lo demás

**El FCB guarda su asignación, no sus bloques.** Un fichero no es una lista de
bloques: es un tamaño y un objeto de asignación. Quien sabe traducir «bloque
lógico 3» a «bloque físico 41» es el método, y cada método lo hace a su manera.

Eso es lo que permite cambiar de método en caliente y comparar los tres con la
misma carga. Si el FCB guardara números de bloque, cambiar de método obligaría a
reescribir todos los ficheros. Guardando la asignación, el cambio es cambiar el
traductor.

Tiene un precio, y conviene decirlo en la defensa: **cambiar de método no
convierte los ficheros ya escritos**. Un fichero creado como contiguo sigue
leyéndose como contiguo después del cambio; el método nuevo se aplica a lo que
se escriba a partir de ahí. Convertir de verdad exigiría leer y reescribir, que
es justo lo que esta fase no hace.

---

## 3. Los tres métodos

Los tres reciben la misma petición —«quiero N bloques»— y devuelven una
asignación que sabe leer y escribir sus bloques. Lo que cambia es cómo se
colocan y cuánto cuesta llegar al bloque N.

### Contiguo

Se guardan el primer bloque y la longitud. Traducir es una suma; no gasta ni un
bloque de metadatos. A cambio, el fichero no puede crecer si el bloque siguiente
está ocupado, y el disco se llena de huecos que en total sobran pero que no
sirven para un fichero grande.

La colocación no se inventa aquí: se apoya en `pos.alloc.SlotManager`, el mismo
gestor de huecos de la memoria, con BestFit. Reutilizar la pieza es deliberado:
el mismo algoritmo que coloca procesos en memoria coloca ficheros en disco, y
eso es un argumento de continuidad, no una coincidencia.

### Enlazada

Cada bloque empieza con el número del siguiente. No hay fragmentación externa:
cualquier bloque libre vale. El precio es doble: se pierden cuatro bytes de cada
bloque en el puntero y llegar al bloque N obliga a recorrer la cadena, N
lecturas.

### Indexada

Un bloque de índices por fichero con los números de sus bloques de datos. Llegar
al bloque N son dos lecturas: el índice y el bloque. Cuesta un bloque entero de
metadatos por fichero, aunque el fichero sea de un bloque.

Es de un solo nivel: el índice cabe en un bloque o el fichero no entra por esta
vía. Con bloques de 64 bytes son 16 punteros; un fichero de más de 16 bloques
necesitaría índices de dos niveles, y esta fase no los tiene.

---

## 4. La demostración que importa

`--disco` imprime cinco secciones. La que hay que enseñar es la tercera.

### Los tres guardan lo mismo

Mismo contenido, tres métodos, contando lecturas y escrituras:

```
metodo      bloques  indices    total escrituras   lecturas
contiguo          3        0        3          6          6
enlazada          4        0        4         11         23
indexada          3        1        4          7         15
```

El contiguo mete 380 bytes en tres bloques y no gasta índices. El enlazado
pierde cuatro bytes por bloque en el puntero y necesita uno más. El indexado
paga un bloque entero para la tabla, pero lee el bloque que quiere en dos
lecturas en vez de recorrer la cadena: 15 lecturas frente a 23.

### El mismo hueco para los tres

Se llena el disco con 13 ficheros de 16 bloques, se borran los pares y se pide
un fichero de 24 bloques. Quedan 122 bloques libres, más que de sobra.

```
metodo     resultado  detalle
contiguo   no entra   no hay un tramo contiguo de 24 bloques
                      (ningun hueco alcanza 24 unidades; el hueco mayor mide 18)
enlazada   entra      24 bloques: cadena 19 -> 20 -> ... -> 34 -> 51 -> ... -> 58
indexada   entra      24 bloques: indice en el bloque 20 -> [21, ... , 61]
```

Esa es la frase de la fase: **hay sitio y no cabe**. El contiguo tiene 122
bloques libres y rechaza el fichero porque ningún hueco llega a 24. El enlazado
y el indexado toman los bloques sueltos y los encadenan: no les importa dónde
estén.

### Una imagen incoherente no monta

Se toma una imagen buena, se marca como libre el primer bloque de un fichero y
se intenta montar:

```
montaje correcto .. 256 bloques de 128 bytes, 235 libres (91%), 1 ficheros
montaje roto ...... rechazado
  la imagen no monta: 2 problemas
    el superbloque dice 235 bloques libres y el mapa cuenta 236
    el bloque 18 lo reclama el FCB 1 pero el mapa lo da por libre
```

El montaje contrasta el mapa con los FCB en los dos sentidos: un bloque ocupado
que nadie reclama es una fuga; un bloque que un fichero reclama y el mapa da por
libre es una corrupción, y escribir ahí encima perdería datos. El sistema
prefiere no montar y decirlo antes que arrancar sobre una imagen que no entiende.

---

## 5. Integración con el núcleo

`FileSystem.mount` lanza `PosFault` con la lista de problemas. El arranque lo
captura y se queda en modo degradado: el resto del sistema funciona, pero no hay
disco. Es la misma política que ya usaba el autodiagnóstico para la memoria.

El tamaño de bloque no viaja en los bytes de la imagen. Quien monta tiene que
saberlo, o el superbloque se lee en la posición equivocada. Es una limitación
consciente: en un sistema real el tamaño de bloque está en un formato de
cabecera fijo o se prueba con varios; aquí se pasa como parámetro y la
demostración lo hace explícito.

---

## 6. Pruebas

`FileSystemTest` cubre contratos, no detalles de implementación, y está partido
en seis grupos:

| Grupo | Qué fija |
|---|---|
| `Rendimiento` | El coste de acceso de cada método no empeora al crecer el fichero |
| `Invariantes` | Mapa y FCB cuadran tras cada operación; borrar devuelve todo |
| `Metodos` | Los tres dan el mismo contenido; el contiguo falla donde debe |
| `Directorios` | Anidar, listar, borrar con contenido y no dejar FCB huérfanos |
| `Montaje` | Guardar y montar devuelve lo mismo; una imagen rota no monta |
| `Conversion` | Cambiar de método no rompe lo ya escrito |

Total de la suite: 138 pruebas, todas en verde. Las de esta fase son 35.

---

## 7. Lo que esta fase no hace, y por qué

- **Índices de dos niveles.** Un fichero indexado no pasa de un bloque de
  punteros. Añadirlo duplica la estructura y no añade nada al argumento.
- **Reparación.** Una imagen incoherente se rechaza, no se arregla. Reparar
  exige decidir de quién es el bloque en disputa, y eso es una decisión de
  política que no toca aquí.
- **Convertir ficheros entre métodos.** Cambiar el método activo no reescribe lo
  anterior, y se dice en voz alta en vez de disimularlo.
- **Bloques de disco reales.** La imagen vive en memoria. Escribir en el
  anfitrión no aporta nada al comportamiento que se quiere enseñar.

---

## 8. Siguiente fase

La fase 5 es la sincronización: cerrojos con dueño, semáforos contadores,
variables de condición y monitores, con hilos reales autorizados por el núcleo,
más el grafo de recursos con detección de ciclos y el algoritmo del banquero.
El sistema de ficheros ya está preparado para no ser el cuello de botella: toda
la mutación pasa por `FileSystem`, que es el único sitio donde habrá que poner
el cerrojo.
