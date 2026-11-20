# Fase 8: la ventana

El sistema ya funcionaba antes de esta fase: planificaba, repartía memoria,
bloqueaba hilos y contestaba llamadas. Lo que no tenía era forma de mirarlo. Se
podía leer la traza al final, que es como leer el parte del accidente cuando el
coche ya está en el desguace.

Esta fase añade lo que faltaba: **una ventana que enseña el sistema mientras
corre**, sin cambiarlo. Ese "sin cambiarlo" es la regla que ordena todo lo de
aquí abajo.

---

## 1. Qué hay ahora

| Pieza | Clase | Papel |
|---|---|---|
| Foto del sistema | `pos.ui.Snapshot` | Todo lo que se puede enseñar, copiado e inmutable |
| Copia | `pos.ui.Snapshotter` | Recorre el núcleo una vez y llena la foto |
| Puente | `pos.ui.UiFeed` | Recibe fotos y guarda la última y una serie corta |
| Tablas | `pos.ui.ProcessTable`, `SchedulingTable`, `MemoryTable`, `CallsTable`, `SyncTable` | Filas ya calculadas, sin Swing dentro |
| Ventana | `pos.ui.swing.Shell` | Seis pestañas sobre el mismo puente |
| Capturas | `pos.ui.swing.Capture`, `UiLaunch` | Ocho PNG sin pantalla ni servidor gráfico |

`pos.ui` no sabe que existe Swing. Las tablas devuelven `List<List<String>>` y
unos pocos números; quien pinta decide fuentes y colores. Así la lógica de "qué
se enseña" se puede probar sin abrir una ventana, y de hecho se prueba:
`SnapshotTest` recorre el escenario entero y comprueba las filas.

---

## 2. La interfaz mira, no toca

El núcleo no se modificó para que la ventana funcionase. Se le añadieron tres
cosas, todas de salida:

- `KernelObserver`, una interfaz con un solo método, `afterTurn(Kernel)`.
- `Kernel.setObserver(...)`, para apuntar quién mira.
- `Kernel.readyQueue()` y `blockedQueue()`, que devuelven copias de las colas.

Nada de eso cambia lo que el núcleo hace. Si no hay observador, el código nuevo
no se ejecuta más que un `if (observer == null) return;`.

La alternativa -- que la ventana entrase a leer las listas internas -- habría
atado el diseño del núcleo al de la pantalla: cada cambio de estructura interna
rompería la vista.

### Cuándo se avisa

Al cerrar cada turno, no en cualquier momento. Un turno cerrado es el único
instante en que el intérprete está vacío y las colas están quietas: se puede
leer el sistema entero sin cerrojos y sin pillar un hilo a medio mover.

También se avisa al arrancar (para que la primera foto no sea la de después del
primer turno) y al terminar (para que la última no se pierda).

Si el observador lanza una excepción, se anota una vez en la traza y se
desconecta. Una ventana rota no puede tumbar el sistema que está mirando, y
repetir el aviso solo llenaría la traza de miles de líneas iguales.

---

## 3. Las siete vistas

Todas las pestañas leen la misma foto. Una pestaña no consulta al núcleo por su
cuenta: si lo hiciera, dos pestañas podrían estar enseñando instantes distintos.

| Pestaña | Qué contesta |
|---|---|
| Resumen | Cuánta CPU se lleva cada estado, quién está listo, y el mapa de memoria con la evolución del sistema debajo |
| Procesos | Qué procesos existen, con sus hilos, y en qué estado está cada uno |
| Reparto | Qué hilo corre, cuánto lleva, y el orden exacto de la cola de listos |
| Memoria | El mapa de unidades físicas y la tabla de tramos por proceso |
| Sistema | Cerrojos, semáforos y condiciones, con dueño y cola de espera |
| Traza | Los últimos eventos, con reloj y actor |
| Terminal | Una sesión viva donde se escribe: la orden se ejecuta en el mismo núcleo que alimenta las otras seis |

La pestaña de procesos enseña el estado tal cual lo dice el núcleo, y eso
incluye **detenido**: un trabajo parado con `stop %n` sigue en la tabla, con su
memoria puesta y sus hilos en `STOPPED`. No es una foto vieja ni un proceso a
medio morir, y la ventana no puede esconderlo: si lo filtrara, el usuario vería
desaparecer un proceso que no ha muerto. Un proceso terminado, en cambio, se
queda en la tabla con su memoria final ya devuelta; cuando lo matan a mitad de
sesión, la liberación ocurre en el acto y el mapa de memoria lo refleja en el
siguiente aviso de turno.

El mapa de memoria es lo que más cuesta: son 192 unidades y hay que poder
contar cuántas tiene cada proceso. Se pinta como una rejilla de 64 columnas; los
huecos libres van más oscuros que el panel, porque memoria libre es ausencia de
algo y lo ocupado tiene que resaltar.

### El ancho de las columnas

Una tabla de Swing reparte el ancho que sobra entre sus columnas, y lo hace
multiplicando: una columna que necesita 45 px para su número acababa con 136, y
el valor de la fila aparecía a un palmo de su cabecera. Se arregló de raíz.

Cada columna lleva un tope (`maxWidth`) igual al ancho que necesita de verdad, y
una última columna vacía se come el sobrante. Los modos de reparto automático de
`JTable` no sirven para esto: `doLayout` los aplica con
`setWidthsFromPreferredWidths(false)`, así que solo respetan el ancho mínimo y
el máximo; con el máximo libre, la columna crece hasta llenar la tabla. Poniendo
el tope, el texto queda debajo de su cabecera y la tabla sigue ocupando el ancho
de la ventana.

Los topes se miden, no se adivinan: `audit/Audit.java` monta el `Shell` sin
pantalla, mide con la fuente real el contenido más ancho de cada columna y avisa
cuando no cabe. Esa medición destapó lo que el reparto estirado tapaba --
columnas más estrechas que su propio encabezado --. Si aun así un valor no cabe,
la celda lo recorta con puntos suspensivos en vez de cortar la palabra a mitad
de letra.

---

## 4. Capturas sin pantalla

Un `JFrame` exige servidor gráfico. Por eso `Shell` no es una ventana: es un
`JPanel` con el contenido montado (`rootComponent()`), y `frame()` lo envuelve
cuando de verdad hay pantalla.

Con eso, `java -Djava.awt.headless=true ... UiLaunch --captura capturas` genera
las ocho imágenes con `BufferedImage` y `paint`, sin X11 y sin nadie mirando.

Las seis primeras fotos son del **mismo instante**: se deja correr el escenario
con una pausa corta y, al llegar al instante que interesa, se congela subiendo la
pausa (el freno vive en `UiFeed`, no en el núcleo). Si cada captura fuese de un
momento distinto, el conjunto no se podría leer como una sola foto.

Ese instante ya no es un reloj fijo sino la primera foto con un **trabajo
detenido**: es lo único que se ve durante unos turnos y justo lo que la fase de
trabajos tiene que enseñar. Con un reloj fijo, las capturas podían caer fuera de
esa ventana y salir sin ningún «detenido» en la tabla.

Las dos últimas son del cierre: todo terminado y la memoria libre.

Detalle de entorno: el JDK de Microsoft no trae `conf/fonts`, así que las
fuentes se cargan de ficheros TTF, no por nombre lógico. Pedir "SansSerif" en
una máquina sin fontconfig revienta al construir el objeto `Font`.

---

## 5. Cómo se ejecuta

```bash
. ~/.local/env-pos.sh
mvn -q compile

java -cp target/classes pos.launch.Main --ui              # ventana, si hay pantalla
java -cp target/classes pos.launch.Main --captura         # PNG en capturas/
java -cp target/classes pos.launch.Main --captura salida  # PNG donde se diga
```

Las capturas se pueden lanzar también sin pasar por el lanzador:

```bash
java -Djava.awt.headless=true -cp target/classes pos.ui.swing.UiLaunch --captura capturas
```

Lo que se ve es el escenario de `pos.launch.UiDemo`: cinco procesos, dieciséis
hilos lógicos, memoria paginada de 192 unidades y un planificador Round Robin de
tres ticks. La terminal de la fase 7 se sigue abriendo con `--shell`.
