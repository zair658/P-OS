# Fase 7: llamadas al sistema y terminal

Hasta aquí el proceso sabía hacer dos cosas: ejecutar instrucciones y pelearse
por el procesador. Todo lo demás estaba fuera de su alcance. No podía leer un
fichero, ni saber qué hora es, ni pedir que otro proceso arrancase. Y no era un
olvido: era el único reparto posible mientras el juego de instrucciones fuera la
frontera. Un proceso con acceso directo a la imagen de disco no es un proceso,
es un programa que se cree el sistema.

Esta fase abre la puerta por donde se pide lo que no se puede hacer uno mismo,
y la abre una sola vez: **una llamada al sistema es la única manera que tiene un
proceso de conseguir que el núcleo haga algo en su nombre**. Sobre esa puerta se
construye lo que hasta ahora no existía: una terminal.

---

## 1. Qué hay ahora

| Pieza | Clase | Papel |
|---|---|---|
| Llamada | `pos.sys.Syscall` | Interfaz sellada: 21 registros, cada uno con código y nombre |
| Resultado | `pos.sys.SyscallResult` | Éxito o fallo con texto; nunca una excepción hacia el proceso |
| Descriptores | `pos.sys.FdTable` | Ficheros abiertos por proceso: ruta, modo, contenido, posición |
| Atención | `pos.sys.SystemCalls` | Despacho, tablas por proceso, contadores y coste en tics |
| Terminal | `pos.shell.Terminal` | La sesión vista como tarea nativa del núcleo |
| Entrada | `pos.shell.Keys`, `Console`, `Input` | De dónde llegan las líneas: guion o teclado, y el texto de entrada |
| Salida | `pos.shell.Output`, `TextBuffer` | Sumidero de texto; el mismo para pantalla y tubería |
| Línea | `pos.shell.LineParser` | Etapas, tuberías, redirecciones y comillas |
| Sesión | `pos.shell.Shell` | Estado de la sesión: directorio, historia, reloj, tablas |
| Órdenes | `pos.shell.Commands`, `CommandTable`, `FileCommands`, `TextCommands`, `SystemCommands` | 33 órdenes en tres familias |
| Demostración | `pos.launch.ShellDemo` | Los tres actos de este documento (`--shell`) |
| Pruebas | `pos.sys.SyscallTest`, `pos.shell.ShellTest` | 47 pruebas nuevas sobre la frontera y la sesión |

---

## 2. Por qué no son instrucciones

La tentación es evidente: si hay que leer un fichero, se añade una instrucción
`READ` al intérprete y ya está. Se descartó por tres motivos que se refuerzan
entre sí:

1. **La instrucción tendría que llevar datos que no son números.** El juego de
   instrucciones actual es aritmética sobre enteros. Un `READ` necesita una ruta,
   un modo y un descriptor; meterlo obliga a inventar direcciones de cadenas, y
   con ellas un modelo de memoria para texto que esta fase no necesita.
2. **El proceso dejaría de ser comprobable.** Una instrucción se ejecuta porque
   el programa la contiene. Una llamada se atiende porque el núcleo decide
   atenderla, y ahí caben la denegación, el bloqueo y la contabilidad. La
   diferencia es justo la que hay entre un lenguaje y un sistema operativo.
3. **El intérprete no debería saber lo que es un fichero.** Hoy el intérprete
   avisa de cada instrucción retirada y pide cerrojos y semáforos al núcleo a
   través de `ExecutionHost`. Añadir ahí quince operaciones de sistema de
   ficheros convertiría ese contrato mínimo en un catálogo.

Así que la frontera no se movió: se le puso una puerta.

---

## 3. La frontera

```java
public sealed interface Syscall
        permits Syscall.Exit, Syscall.Write, Syscall.Read, Syscall.Open, ... {

    int code();

    String name();
}
```

Cada llamada es un `record`: inmutable, con sus datos dentro y sin estado propio.
Eso importa más de lo que parece. Una llamada no puede quedarse «abierta» a
medio atender; o se atiende entera en el tic que cuesta, o no se atiende. Lo que
sobrevive a la llamada es el estado del núcleo —la tabla de descriptores, el
directorio de trabajo—, no el objeto que la pidió.

Los códigos son un rango compacto del 1 al 21, y el nombre es el que aparece en
la tabla de contabilidad:

| Código | Llamada | Qué pide |
|---|---|---|
| 1 | `exit` | Terminar el proceso con un código de salida |
| 2 | `write` | Escribir texto en un descriptor abierto |
| 3 | `read` | Leer hasta N caracteres de un descriptor |
| 4 | `open` | Abrir una ruta en lectura, escritura o añadido |
| 5 | `close` | Cerrar y guardar lo escrito |
| 6 | `stat` | Datos de una entrada del sistema de ficheros |
| 7 | `list` | Contenido de un directorio |
| 8 | `mkdir` | Crear un directorio |
| 9 | `unlink` | Borrar una entrada |
| 10 | `chdir` | Cambiar el directorio de trabajo |
| 11 | `getcwd` | Consultar el directorio de trabajo |
| 12 | `ps` | Tabla de procesos |
| 13 | `kill` | Terminar otro proceso |
| 14 | `mem` | Resumen de memoria física y virtual |
| 15 | `sleep` | Dormir el hilo N tics |
| 16 | `clock` | Tic actual |
| 17 | `spawn` | Crear un proceso a partir de código |
| 18 | `fsinfo` | Ocupación de la imagen |
| 19 | `fscheck` | Verificación de la imagen |
| 20 | `setmethod` | Cambiar el método de asignación |
| 21 | `getmethod` | Consultar el método de asignación |

Y la respuesta:

```java
public record SyscallResult(boolean ok, String text) {

    public static SyscallResult success(String text) { ... }

    public static SyscallResult failure(String text) { ... }

    public long number() { ... }  // valor numérico del texto, 0 si no lo es
}
```

**Un fallo no es una excepción.** El núcleo devuelve `failure` con el motivo en
texto, y el proceso decide qué hacer. Si el núcleo lanzara, una orden mal escrita
en la terminal tumbaría la terminal, y con ella la sesión entera del usuario. La
terminal traduce el fallo a una línea de error y sigue leyendo. `Shell.must` es
la excepción a esa regla y está al otro lado de la frontera: cuando una orden
necesita que la llamada salga bien para poder continuar, lanza dentro de la
orden, y el intérprete de órdenes la recoge, imprime y sigue.

---

## 4. Descriptores

`FdTable` es la tabla de ficheros abiertos de un proceso. Una entrada guarda la
ruta, el modo, el contenido y la posición de lectura. El proceso solo ve un
número: la ruta y el contenido no salen del núcleo, y eso es lo que impide que
un proceso se fabrique un puntero a la imagen de disco.

Tres decisiones que se notan al usarla:

**Los descriptores 0, 1 y 2 no están en la tabla.** Son la consola y los lleva la
terminal. El primero que se reparte es el 3, y las pruebas lo comprueban
explícitamente porque un día alguien cambiará el reparto y querrá enterarse.

**La escritura se acumula y se guarda al cerrar.** `write` añade al buffer de la
entrada; `close` escribe el contenido en el sistema de ficheros y libera el
descriptor. Tiene una consecuencia que conviene tener presente: un proceso que
muere sin cerrar pierde lo escrito. `abandon()` cuenta las entradas modificadas
para poder decirlo en la traza en vez de perderlas en silencio.

**La tabla tiene tope.** Dieciséis entradas por proceso. Cuando se llena, `open`
falla con «no quedan descriptores libres» y la orden lo imprime. Es el mismo
límite que cualquier sistema real pone a un proceso que abre ficheros en un
bucle, y aquí es la única defensa posible: el núcleo no puede adivinar cuáles
sobran.

### El fallo que encontraron las pruebas

La primera versión repartía descriptores con un contador: `nextFd++`. Al cerrar
se liberaba la entrada, pero el contador no retrocedía. Un proceso que abriera y
cerrara en un bucle agotaba la tabla **sin tener nada abierto**, y a partir de
ahí todas las aperturas fallaban para siempre. El fallo no aparecía al abrir el
primer fichero, ni el segundo: aparecía el decimocuarto, lejos de su causa.

Lo encontró la prueba de «abrir, escribir, cerrar y volver a abrir». La versión
corregida busca el número más bajo que esté libre:

```java
int fd = FIRST_FD;
while (entries.containsKey(fd)) {
    fd++;
}
```

El comentario en el código dice por qué, porque es el tipo de línea que alguien
«simplifica» dentro de dos años y vuelve a romper.

---

## 5. La terminal es un proceso

Lo importante de esta fase no es que haya una terminal, sino que la terminal
**no es un caso especial**. Es un proceso con su PCB, su hilo, su cuota y sus
tics, como el contador de la fase 2. Lo único distinto es quién ejecuta sus
pasos.

```java
@FunctionalInterface
public interface HostedTask {

    StepResult step(ExecutionHost host, Tcb thread);
}
```

Un hilo con tarea enganchada no ejecuta instrucciones: cada vez que le toca el
turno, el núcleo le pide un paso. El contrato es el mismo que el del intérprete
—`CONTINUE`, `BLOCKED`, `YIELDED`, `EXITED`— y esa es toda la gracia: el núcleo
no distingue un hilo interpretado de uno nativo. Sigue habiendo cuota, cambios de
contexto, bloqueos y tics.

Un paso de la terminal hace, en este orden:

1. Si no quedan teclas, cierra la sesión con código 0 y termina.
2. Si la siguiente tecla lleva un tic que aún no ha llegado, se duerme lo que
   falta (`BLOCKED`): la terminal no gira esperando, igual que un `SLEEP`.
3. Escribe el prompt y la línea tecleada en el transcriptor.
4. Ejecuta la línea, que es donde se hacen las llamadas al sistema.
5. Cobra un tic por la línea, como una instrucción retirada.
6. Si la orden pidió dormir, se duerme; si pidió salir, termina; y si no, **cede
   el turno**.

El último punto es el que hace visible el reparto. Sin la cesión, la terminal
tendría un turno infinito mientras queden teclas: acabaría la sesión entera de
golpe y los demás procesos no avanzarían hasta el final. Cediendo, entre orden y
orden se ve en la traza que el procesador pasa por los otros, y `ps` en mitad de
una sesión enseña algo distinto de una foto del arranque.

El cierre con código 0 también se aprende de las pruebas. Antes, cuando se
acababan las teclas, el hilo simplemente terminaba: no había código de salida
apuntado, y una sesión normal parecía un fallo. Ahora la terminal llama a
`Shell.close()`, que pide `exit 0` si nadie lo había pedido ya.

---

## 6. La shell

Una vez que hay llamadas, la terminal necesita hablar el idioma de siempre: una
línea, unas palabras, y a veces una tubería. `LineParser` convierte el texto en
etapas:

```
cat /datos.txt | grep -v dos | wc -l
└──── etapa 0 ────┘└ etapa 1 ┘└ etapa 2 ┘
```

Cada etapa lleva sus palabras, el fichero del que lee (`<`) y el fichero al que
escribe (`>`, `>>`). Las comillas dobles mantienen los espacios dentro de un
argumento. Una comilla sin cerrar no se ejecuta: se avisa.

La ejecución encadena las etapas con un `TextBuffer` en medio:

```
etapa 0 → buffer → etapa 1 → buffer → etapa 2 → pantalla
```

No hay procesos nuevos ni tuberías del sistema operativo: es un buffer de texto
pasado de una orden a la siguiente. Es la decisión correcta para esta fase,
porque lo que se está enseñando es la frontera, no la concurrencia de las
tuberías. Y tiene una consecuencia honesta: `cat fichero | head -n 1` lee el
fichero entero antes de que `head` vea nada. Un sistema real con `SIGPIPE` corta
antes; aquí no hay señales todavía.

La redirección en medio de una tubería (`foo > f | bar`) hace las dos cosas: el
texto va al fichero y además sigue por el tubo. Es lo que hace una shell de
verdad, y aquí no cuesta nada porque el buffer ya está construido.

Las 33 órdenes están en tres familias:

| Familia | Órdenes |
|---|---|
| Ficheros | `ls` `cat` `pwd` `cd` `mkdir` `touch` `rm` `cp` `mv` `stat` `tree` `df` `fsck` |
| Texto | `echo` `head` `tail` `wc` `grep` |
| Sistema | `ps` `kill` `mem` `sleep` `clock` `uptime` `syscalls` `method` `spawn` `history` `help` `clear` `version` `uname` `exit` |

Ninguna orden sabe lo que es un descriptor. Todas piden a `Shell`, y `Shell`
pide al núcleo. La cadena es la misma para `cat` que para `ps`, y esa uniformidad
es lo que permite contar las llamadas de una sesión entera sin tocar una sola
orden.

---

## 7. Contabilidad

Cada llamada atendida cuesta un tic de núcleo, y se apunta. `SystemCalls` lleva
tres contadores: total de llamadas, tics gastados y veces por código, además del
código de salida de cada proceso.

La demostración termina con la tabla de una sesión completa:

```
cod   llamada         veces
----------------------------
1     exit                1
2     write               1
3     read                4
4     open                3
5     close               3
6     stat                3
7     list                1
11    getcwd              6
----------------------------
total llamadas           22
tics de nucleo           22
```

Leída despacio, esa tabla dice bastante: `getcwd` seis veces son los seis
prompts (la terminal pregunta el directorio cada vez que va a escribir `pos:/>`);
`open`/`close`/`stat` van en pareja porque `cat`, `stat` y `cp` abren y cierran;
`exit` aparece una vez porque la sesión se cerró a mano. Y las 22 llamadas son
22 tics que el núcleo gastó atendiendo, encima de los tics que la terminal gastó
ejecutando.

---

## 8. Lo que el núcleo niega

Una frontera solo se demuestra por lo que se queda del otro lado. Estos son los
«no» que el núcleo da hoy, cada uno por un motivo distinto:

| Petición | Respuesta | Motivo |
|---|---|---|
| `kill` del propio proceso | «un proceso no se mata a si mismo: para eso esta exit» | Matarse a uno mismo es salir, y salir tiene código y limpieza |
| `kill` de un pid que no existe | Se avisa, no se ignora | Un «vale» silencioso oculta un error de tecleo |
| `read` de un descriptor cerrado | «el descriptor N no está abierto» | Un número suelto no puede servir de nada |
| `write` en uno abierto en lectura | «esta abierto en lectura» | El modo se fija al abrir, no al escribir |
| `open` con la tabla llena | «no quedan descriptores libres» | El tope es del proceso, no del sistema |
| Cualquier llamada sin sistema de ficheros montado | «no hay sistema de ficheros montado» | El mensaje dice qué falta, no revienta con un nulo |
| `spawn` con código que no carga | Falla y no deja proceso a medias | Cargar es antes de existir; si falla, no existe |
| `read` más allá del final | Devuelve vacío, sin fallo | Leer hasta el final es normal, no un error |

El último es el más importante de la lista, porque es el que distingue una API
pensada y una API que solo funciona en el caso bueno.

---

## 9. Pruebas

Dos ficheros nuevos, 47 pruebas:

- **`pos.sys.SyscallTest` (15)** prueba la frontera sin pasar por la terminal, con
  una *sonda*: un proceso nativo que hace llamadas en nombre de su propio hilo,
  guarda las respuestas y termina. Es la forma más corta de comprobar el
  contrato del núcleo: que responde, que cobra, que se niega y que no revienta
  cuando falta una pieza.
- **`pos.shell.ShellTest` (32)** prueba la terminal desde fuera: se le dan teclas,
  se monta el núcleo y se mira el texto que sale. Ninguna prueba llama a una
  orden a mano; si alguna se cuelga, es que la terminal se quedó esperando un
  turno que no llega, y eso también interesa saberlo.

Las pruebas encontraron dos fallos reales, ya corregidos: el contador de
descriptores que no retrocedía (§4) y la sesión que terminaba sin código de
salida (§5). Ninguno de los dos se veía usando el sistema a mano; los dos
aparecían en cuanto algo lo usaba en serie.

La suite completa son 280 pruebas y ningún fallo.

---

## 10. Lo que queda fuera, a propósito

- **Señales.** `kill` termina, no avisa. No hay forma de decirle a un proceso
  «prepara» en vez de «muere», y por eso las tuberías no cortan antes de tiempo.
- **Permisos y usuarios.** Cualquier proceso puede leer cualquier fichero y
  matar a cualquier otro. Sin usuarios, los permisos serían decoración.
- **Redirección de descriptores.** `2>&1` no existe: `write` va siempre a la
  consola del proceso, no a un descriptor elegido.
- **Trabajo en segundo plano.** *(Resuelto después de la fase 7.)* En su momento
  no había `&` ni trabajos y una orden se esperaba entera. Hoy hay `&`, `jobs`,
  `bg`, `fg` y `stop`, con los estados de trabajo en `pos.sched.Order` y en la
  tabla `jobs`.
- **Comodines y variables.** `*` y `$HOME` no se expanden. El parser separa
  palabras; no interpreta un lenguaje.
- **Entrada interactiva de verdad.** *(Resuelto después de la fase 7.)* `Console`
  sigue recibiendo líneas con su tic, y eso es lo que mantiene las pruebas
  deterministas; al lado hay ahora `LiveKeys`, que lee del teclado en otro hilo y
  deja las líneas en una cola para no bloquear el turno de la terminal. Es lo que
  hay detrás de `--interactivo` cuando no hay terminal donde poner el modo crudo.
  Con terminal de verdad entra `TtyKeys`, que lee bytes y edita la línea: flechas,
  historial, tabulador y Ctrl-C (apartado 12.6 de la arquitectura). *(Resuelto
  también.)* Ctrl-Z llegó después, por la misma puerta: el lector de teclado deja
  el aviso en un buzón y el núcleo lo recoge al principio de su vuelta, con el
  turno devuelto. Sigue sin haber manejador de señal; la diferencia es que ahora
  no hace falta.

---

## 11. Cómo verlo

```bash
. ~/.local/env-pos.sh
mvn -o test
mvn -o -q package
java -cp target/classes pos.launch.Main --shell
```

`--shell` ejecuta los tres actos de este documento: una sesión de terminal sobre
una imagen de ficheros, la misma terminal compartiendo procesador con dos
procesos de cálculo (con su traza de cambios de turno), y la contabilidad que
queda en el núcleo.
