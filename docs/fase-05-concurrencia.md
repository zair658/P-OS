# Fase 5: concurrencia acotada, cerrojos e interbloqueo

Las cuatro fases anteriores dejaron procesos que se turnan la CPU. Mientras cada
uno viva en su espacio de direcciones, turnarse es todo lo que hace falta. El
problema aparece en cuanto dos hilos del mismo proceso comparten una unidad de
memoria: el turno deja de ser una garantía y pasa a ser una amenaza, porque el
desalojo puede caer en cualquier instrucción, también entre la lectura y la
escritura de un contador.

Esta fase añade lo mínimo para que eso se pueda enseñar y arreglar: instrucciones
`LOCK` y `UNLOCK`, una tabla de cerrojos con cola de espera ordenada, cesión
directa al primero que espera y detección de ciclos de espera. Lo que se ha
construido, qué demuestra y qué se ha dejado fuera a propósito.

---

## 1. Qué hay ahora

| Pieza | Clase | Papel |
|---|---|---|
| Instrucciones | `pos.proc.Instruction.Lock`, `Unlock` | Dos instrucciones nuevas del repertorio, con nombre de cerrojo |
| Carga | `pos.proc.Assembler` | `LOCK contador` y `UNLOCK contador`; el nombre se valida |
| Ejecución | `pos.proc.Interpreter` | Pide el cerrojo al núcleo; si lo consigue sigue, si no pide bloqueo |
| Estado del hilo | `pos.proc.Tcb` | Qué cerrojo espera, qué cerrojo tiene y si está bloqueado por cerrojo o por espera |
| Tabla de cerrojos | `pos.sync.LockManager` | Dueño, cola FIFO de espera, concesiones pendientes de avisar |
| Núcleo | `pos.kernel.Kernel` | Bloquea, despierta, detecta ciclos y denuncia cerrojos huérfanos |
| Eventos | `pos.kernel.EventKind` | `LOCK`, `UNLOCK`, `BLOCK`, `WAKE`, `DEADLOCK`, `LOCK_ORPHAN` |
| Demostración | `pos.launch.ConcurrencyDemo` | Los tres actos de este documento (`--concurrencia`) |

El gestor de cerrojos no despierta a nadie. Cuando libera, **transfiere la
propiedad** y apunta el hilo en una lista de concesiones; el núcleo vacía esa
lista al final de cada turno y es él, y solo él, quien mueve hilos a la cola de
listos. La regla viene de la fase 2 y no se rompe aquí: un solo sitio toca la
cola de listos.

---

## 2. La decisión que ordena todo lo demás

Hay dos maneras de entregar un cerrojo recién liberado y la elección se nota en
el resultado, no solo en el código.

La primera es **despertar y volver a competir**: al liberar, el hilo que espera
vuelve a la cola de listos y tendrá que pedir el cerrojo otra vez cuando le toque
la CPU. La segunda es **ceder directamente**: al liberar, el cerrojo ya tiene
dueño nuevo antes de que nadie más lo intente.

Aquí se cede directamente, y el motivo es el desalojo. Con vuelta a competir,
un hilo recién llegado puede pedir el cerrojo en el hueco que el planificador le
da antes que el que lleva veinte tics esperando, y entonces el que espera no
entra nunca: eso tiene nombre, se llama inanición, y con cuanto de una
instrucción es fácil de provocar sin querer. Cediendo en el acto, el orden de
entrada es el orden de la cola y no hay forma de colarse.

El precio es un cambio de contexto que no se puede evitar: el cerrojo viaja a un
hilo que acaba de dejar de estar listo, así que el hueco de CPU que se abre no lo
aprovecha quien lo liberó, que sigue ejecutando hasta su próximo desalojo. Es el
mismo precio que paga cualquier sistema con cesión directa, y aquí se ve en la
traza.

---

## 3. Qué pasa exactamente al pedir un cerrojo

### 3.1 Libre

`LOCK` sobre un cerrojo sin dueño lo toma y sigue. Una instrucción, un tic.

### 3.2 Ocupado

El hilo entra en la cola del cerrojo, se marca `BLOCKED`, deja de estar listo y
**no gasta CPU mientras espera**. El intérprete devuelve `BLOCKED` al núcleo y
el núcleo lo saca de la cola de listos. Desde ahí el hilo no aparece en la traza
de turnos: no es que espere de pie, es que no está.

Detalle que conviene tener preparado para la defensa, porque es el punto donde
un tribunal suele apretar: el contador de programa avanza antes de ejecutar la
instrucción, así que el `LOCK` que no consigue el cerrojo deja el `pc` en la
instrucción siguiente, y cuando el hilo vuelve a la CPU **no repite el LOCK**.
Eso es correcto porque la adquisición ya está hecha: el dueño anterior se lo
cedió al liberar. La instrucción se completó mientras el hilo dormía, igual que
un `SLEEP` se completa durmiendo. La alternativa —retroceder el `pc` y
reintentar— obligaría a distinguir «vengo de una concesión» de «estoy pidiendo
dos veces el mismo cerrojo», y en este sistema lo segundo es un error
(`PosFault`), no un caso a tolerar. Hay una prueba dedicada a fijar ese contrato.

### 3.3 Cerrojos no reentrantes

Pedir un cerrojo que ya tienes es un fallo, no una ampliación del plazo. Un
cerrojo reentrante exige contar profundidad y liberar tantas veces como se tomó;
sin eso, un `UNLOCK` de más deja el cerrojo libre con un hilo dentro de su
sección crítica. Se prefiere el error explícito.

### 3.4 Cerrojo huérfano

Si el dueño termina sin liberar, el cerrojo queda en poder de un muerto. El
núcleo lo detecta al cerrar y lo denuncia con nombre de cerrojo y de hilo, porque
el síntoma —hilos que no despiertan— no dice nada por sí solo. No lo libera solo:
adivinar que el dueño no volverá es fácil; adivinar que los datos que protegía
están íntegros, no.

---

## 4. Interbloqueo

El detector mira el grafo de espera: una arista de cada hilo bloqueado al dueño
del cerrojo que espera. Si al seguirlas se vuelve al punto de partida, hay ciclo.

Se ejecuta cuando el sistema se queda sin nadie listo y sin nadie a quien
despertar, es decir, en el único momento en que el ciclo ya no puede romperse
solo. No se ejecuta en cada bloqueo: recorrer el grafo cuesta, y mientras queden
hilos listos el ciclo todavía podría deshacerse. La consecuencia práctica es
buena: la detección no cuesta nada en una ejecución normal.

El resultado es un anillo con nombres, no un número de identificación:

```
[    5] DEADLOCK         nucleo     ciclo de espera: p1/h2 espera 'a' de p1/h1; p1/h1 espera 'b' de p1/h2
[    5] DEADLOCK         nucleo     anillo: p1/h2 -> p1/h1 -> p1/h2
```

Y el sistema **termina**. Esto es lo que más cuesta conseguir y lo que más se
nota al enseñarlo: un programa con interbloqueo, en un sistema de verdad, deja
de responder; aquí la ejecución cierra con informe, la tabla de resultados se
imprime y la demo acaba. Lo que no hace el sistema es resolverlo: no mata a
nadie, no hace retroceder a nadie. Detectar y curar son dos problemas distintos y
esta fase solo resuelve el primero.

---

## 5. La demostración que importa

`--concurrencia` tiene tres actos y cada uno responde a una pregunta.

### Acto primero: la carrera existe y depende del planificador

Dos hilos suman 1 a la misma unidad de memoria, cinco veces cada uno. Lo que
debería quedar es 10.

```
escenario                                 valor esperado   tics  resultado
FCFS, sin desalojo                           10       10     97  correcto
Round Robin, cuanto 1                         5       10     97  pierde 5
Round Robin, cuanto 1, con cerrojo           10       10    117  correcto
```

El mismo binario, tres resultados. Sin desalojo el programa sale bien **por
casualidad**: cada hilo ejecuta sus cinco sumas seguidas y no hay ventana. Con
cuanto de una instrucción, el desalojo cae justo entre la lectura y la escritura,
los dos hilos leen el mismo valor, los dos guardan el mismo valor y se pierden
cinco actualizaciones.

Esa fila del medio es el argumento de la fase entera: **el programa sin cerrojo no
está mal escrito, está a merced del planificador**. Escribir el código más
correcto del mundo no arregla nada si el reparto de CPU decide el resultado.

Los tics cuentan la otra mitad de la historia: 117 frente a 97. Los veinte tics
de diferencia son exactamente las veinte instrucciones nuevas, dos por vuelta y
dos hilos. La exclusión mutua no sale gratis: se paga en instrucciones. Aquí es
un veinte por ciento sobre un programa diminuto; en uno real sería la parte que
importa del tiempo.

### Acto segundo: qué hace el cerrojo por dentro

La traza de sincronización, con el hilo que no consigue el cerrojo:

```
[    7] LOCK             p1/h1      toma el cerrojo 'contador'
[    8] LOCK             p1/h2      no consigue el cerrojo 'contador', en poder de p1/h1
[    9] BLOCK            p1/h2      espera el cerrojo 'contador', en poder de p1/h1
[   12] UNLOCK           p1/h1      libera el cerrojo 'contador' y se lo cede a p1/h2
[   13] WAKE             p1/h2      obtiene el cerrojo 'contador' y vuelve a listos
[   20] UNLOCK           p1/h2      libera el cerrojo 'contador', queda libre
```

Se lee de arriba abajo y no hace falta explicar nada más: pedir, no conseguir,
bloquearse, ceder, despertar, soltar. El hueco entre el 13 y el 20 es el hilo
nuevo ejecutando su sección crítica; el hueco entre el 9 y el 12 es el otro
hilo terminando la suya. Nadie está esperando de pie: entre el 9 y el 12 el
segundo hilo no consume CPU.

El valor final es 10, y el número de bloqueos es uno: a partir del segundo turno
los dos hilos se turnan sin encontrarse. La exclusión mutua funcionando se parece
mucho a que no pase nada.

### Acto tercero: dos cerrojos en orden contrario

El hilo principal toma `a` y pide `b`; el hijo toma `b` y pide `a`.

```
estado de los cerrojos cuando el sistema se detiene
  'a'         en poder de p1/h1; esperan p1/h2
  'b'         en poder de p1/h2; esperan p1/h1
```

Es el abrazo mortal de manual, y el sistema lo cuenta sin colgarse. La cura se
dice en la misma pantalla y es la que se enseña en clase: imponer un orden global
de adquisición, que los dos pidan `a` antes que `b`.

---

## 6. Lo que no se ha hecho, y por qué

Esta fase es deliberadamente estrecha. Queda fuera, con motivo:

- **Semáforos y variables de condición.** Un cerrojo basta para enseñar exclusión
  mutua y es el único mecanismo que necesita el interbloqueo para existir. Un
  semáforo añade una API y un contador, no un concepto nuevo de esta fase; las
  variables de condición sí añadirían uno, y ese merece su propia sesión.
- **Monitores.** Son cerrojos más condiciones por dentro: sin condiciones no hay
  nada que envolver.
- **Herencia de prioridad.** El planificador de esta fase no tenía prioridades, y
  el de ahora las tiene pero sin herencia: la inversión de prioridad se puede
  provocar y no se puede arreglar. Enseñarla aquí sería enseñar un problema con
  una solución que el sistema no tiene, así que la escena queda para cuando la
  herencia exista. Con cesión directa, además, la inanición queda descartada por
  construcción.
- **Banquero.** Es prevención, no detección: necesita que cada proceso declare
  sus máximos antes de ejecutar, lo que cambia el repertorio. Encaja mejor con el
  grafo de recursos completo que con la tabla de cerrojos.
- **Resolver el interbloqueo.** Matar un hilo o hacerlo retroceder exige
  deshacer escrituras ya hechas en memoria compartida. Sin transacciones no hay
  vuelta atrás honesta, y fingirla sería peor que no tenerla.

---

## 7. Pruebas

Veintiocho pruebas nuevas, repartidas por lo que fijan, no por clase:

| Grupo | Qué fija |
|---|---|
| `LockManagerTest` | Toma libre, cola en orden de llegada, cesión al primero, liberar sin ser dueño es fallo, cerrojo no reentrante, dueño muerto denunciado, ciclo encontrado y ciclo inexistente |
| `KernelConcurrencyTest` | La carrera pierde actualizaciones, el cerrojo las recupera, el que espera no consume CPU, el que hereda no repite el `LOCK`, el reloj se para en el interbloqueo, sin cerrojos la tabla queda vacía |
| `InterpreterTest` | `LOCK` libre sigue, `LOCK` ocupado devuelve `BLOCKED` y no entra en la sección crítica, `UNLOCK` avisa al núcleo |
| `AssemblerTest` | `LOCK`/`UNLOCK` se cargan con nombre; sin nombre o con número suelto son error |

La prueba que más vale de todas es la que comprueba que la ejecución **termina**
en el caso de interbloqueo. Un fallo ahí no da una aserción roja: cuelga la
máquina de pruebas y se come el tiempo límite de la clase. Por eso el detector no
es la única defensa; el bucle del núcleo corta por su cuenta cuando no queda
ningún hilo capaz de despertar.

Total del proyecto: 184 pruebas, todas en verde.

---

## 8. Estado y siguiente paso

Con esta fase el sistema sabe repartir CPU, repartir memoria, guardar ficheros y
sincronizar hilos. Lo que le falta para parecerse a lo que se describe en
`docs/arquitectura.md` es el resto de mecanismos (semáforos, condiciones,
monitores), el banquero, la terminal como proceso con comandos y la interfaz
gráfica.

El orden que se ha seguido hasta aquí —cada fase con su demostración y sus
pruebas antes de empezar la siguiente— es el que permite defender el conjunto:
cada afirmación del documento se puede reproducir con un comando.
