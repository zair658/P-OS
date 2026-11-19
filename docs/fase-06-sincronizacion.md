# Fase 6: semáforos y variables de condición

La fase 5 dejó el cerrojo: exclusión mutua y nada más. Con un cerrojo se puede
impedir que dos hilos lean el mismo contador a la vez, y eso agota lo que el
cerrojo sabe hacer. No sirve para lo otro que aparece en cuanto hay un productor
y un consumidor: **esperar a que ocurra algo**. Un consumidor que necesita un
dato no puede protegerlo con un cerrojo, porque el dato todavía no existe;
tendría que quedarse girando alrededor del cerrojo preguntando si ya llegó, y eso
es gastar CPU para no hacer nada.

Esta fase añade los dos mecanismos con los que se escribe esa espera sin girar:
el **semáforo**, que es un contador con cola, y la **variable de condición**, que
es una cola de hilos dormidos atada a un cerrojo. Lo construido, lo que
demuestra, y lo que se ha dejado fuera a propósito.

---

## 1. Qué hay ahora

| Pieza | Clase | Papel |
|---|---|---|
| Instrucciones | `pos.proc.Instruction.SemInit`, `SemWait`, `SemPost` | Semáforo: crear con valor, tomar permiso, devolver permiso |
| Instrucciones | `pos.proc.Instruction.CondWait`, `CondSignal`, `CondBroadcast` | Condición: dormir soltando el cerrojo, despertar a uno, despertar a todos |
| Carga | `pos.proc.Assembler` | Instrucciones de dos operandos; nombre y valor validados al cargar |
| Ejecución | `pos.proc.Interpreter` | Pide la operación al núcleo, como ya hacía con `LOCK` |
| Estado del hilo | `pos.proc.Tcb` | Motivo del bloqueo (`BlockKind`) y cerrojo pendiente de readquirir |
| Cola de espera | `pos.sync.WaitQueue` | Cola FIFO con cesión directa, compartida por cerrojos, semáforos y condiciones |
| Tabla de semáforos | `pos.sync.SemaphoreManager` | Contador y cola por semáforo; valor, esperas y tabla legible |
| Tabla de condiciones | `pos.sync.ConditionManager` | Cola por condición y cerrojo asociado, fijado en el primer `COND_WAIT` |
| Núcleo | `pos.kernel.Kernel` | Bloquea, suelta el cerrojo al dormir, cede, y readquiere antes de seguir |
| Eventos | `pos.kernel.EventKind` | `SEM_INIT`, `SEM_WAIT`, `SEM_POST`, `COND_WAIT`, `COND_SIGNAL` |
| Demostración | `pos.launch.SyncDemo` | Los tres actos de este documento (`--sincronizacion`) |

Los tres mecanismos comparten la misma cola de espera. No es ahorro de código: es
que la cesión directa tiene que ser idéntica en los tres, y si cada uno tuviera
su copia habría tres maneras de equivocarse en el mismo sitio.

---

## 2. El error que arregla esta fase

El consumidor del acto segundo, escrito con cerrojo y sin semáforo, sería así:

```
bucle:  LOCK m
        LOAD 5          ; ¿hay dato?
        CMP 1
        JZ listo
        UNLOCK m
        JMP bucle       ; y vuelta a empezar
```

Funciona, en el sentido de que no corrompe nada. Y sin embargo está mal, por dos
razones distintas:

1. **Gasta CPU sin trabajar.** Cada vuelta del bucle son cinco instrucciones y un
   turno de CPU para averiguar que el dato sigue sin llegar.
2. **No hay forma de saber cuándo parar.** El bucle gasta el turno que el
   productor necesita para producir, así que cuanto más "espera" el consumidor,
   más tarda en llegar el dato que espera.

Un semáforo y una condición resuelven lo mismo por caminos distintos: el hilo
deja de estar listo y el sistema no lo vuelve a mirar hasta que alguien lo
despierte. La diferencia entre ambos es lo que cuesta el despertar.

---

## 3. Semáforos

Un semáforo es un entero y una cola. Dos operaciones, las de siempre:

- `SEM_WAIT` (esperar, P): si el contador es mayor que cero, baja uno y el hilo
  sigue. Si es cero, el hilo entra en la cola.
- `SEM_POST` (señalar, V): si hay alguien esperando, el permiso **pasa** al
  primero de la cola y el contador no sube. Si no hay nadie, el contador sube uno.

### 3.1 Por qué el contador no sube al ceder

Es el detalle que decide si el reparto es justo. La alternativa, subir el
contador y después despertar, tiene una ventana entre las dos cosas: cualquier
hilo que llame a `SEM_WAIT` en ese hueco ve el contador a uno, se lleva el
permiso y sigue. El hilo recién despertado vuelve a encontrarse el contador a
cero y se duerme otra vez, con lo que la espera más larga puede repetirse
indefinidamente. Es exactamente el mismo razonamiento que en la fase 5 con el
cerrojo, y por eso comparten `WaitQueue`: cesión directa, sin pasar por libre.

### 3.2 Un semáforo no tiene dueño

Esto se ve en cuanto se intenta denunciar un bloqueo. Con un cerrojo, el grafo de
espera dice quién lo tiene y el ciclo se puede nombrar. Con un semáforo no hay
dueño: el hilo espera un permiso que devolverá cualquiera, y el sistema no puede
afirmar más que la verdad disponible, que es **quién espera qué y desde cuándo**.
El mensaje de bloqueo lo dice así:

```
[    9] BLOCK   p1/h2   espera un permiso de 'llenos', que esta a cero y tiene 1 hilo(s) en la cola
```

Ni "en poder de", ni un ciclo inventado. Y si al cerrar el sistema el permiso no
llegó, la nota de la tabla de resultados lo deja claro sin disfrazarlo de éxito:

```
p1/h2   bloqueado para siempre esperando un permiso de 'llenos'
``` La consecuencia didáctica es directa:
un semáforo mal usado no produce un interbloqueo detectable, produce un hilo
dormido para siempre sin culpable. Por eso el sistema prefiere fallar ruidosamente
en el caso que sí puede detectar: usar un semáforo que no existe es un fallo con
mensaje, no una espera eterna.

### 3.3 Crear, no improvisar

`SEM_INIT nombre valor` es obligatorio y solo se puede ejecutar una vez por
nombre. Las dos decisiones son la misma: si `SEM_WAIT` creara el semáforo sobre
la marcha, una errata en el nombre se convertiría en un hilo dormido en un
semáforo que nadie va a tocar, y el error aparecería como un cuelgue sin
mensaje. Y reiniciar un semáforo existente escondería permisos en vuelo: la
cuenta volvería a un valor que ya no describe la realidad.

---

## 4. Variables de condición

Una condición no guarda un valor. Guarda hilos dormidos y el nombre del cerrojo
con el que se usa. Su protocolo tiene tres pasos, y el tercero es el que cuesta:

1. `COND_WAIT cond cerrojo`: el hilo **suelta el cerrojo y se duerme**, en ese
   orden y en el mismo paso. Si fueran dos pasos, entre soltar y dormir el hilo
   no estaría ni protegido ni enterado, y una señal en ese hueco se perdería.
2. `COND_SIGNAL cond`: despierta al primero de la cola. Aquí solo se apunta quién
   despierta; el núcleo lo pone en la cola de listos al final del turno, como
   siempre.
3. **El despertado no tiene el cerrojo.** Lo soltó al dormirse. Antes de correr
   tiene que volver a pedirlo, y mientras lo pide puede encontrárselo ocupado, en
   cuyo caso sigue durmiendo, ahora por el cerrojo.

Ese tercer paso obliga a un estado nuevo en el bloque del hilo: *despierto de una
condición, pendiente de readquirir el cerrojo*. Se guarda en `Tcb.reacquireLock()`
y lo resuelve el núcleo en `wakeWaiters()`. La traza lo enseña sin adornos:

```
[   12] COND_SIGNAL   p1/h1   señala 'hay_datos' y despierta a p1/h2
[   13] WAKE          p1/h2   despierta de la condicion 'hay_datos' pero el cerrojo 'm' esta en poder de p1/h1; vuelve a dormir, ahora por el cerrojo
[   13] UNLOCK        p1/h1   libera el cerrojo 'm' y se lo cede a p1/h2
[   14] WAKE          p1/h2   obtiene el cerrojo 'm' y vuelve a listos
```

### 4.1 La señal exige el cerrojo, y eso cambia la traza

`COND_SIGNAL` y `COND_BROADCAST` comprueban que quien llama tiene el cerrojo de
la condición. No es ortodoxia: es lo que hace que el estado compartido que
justifica la señal esté protegido mientras se comprueba y se señala. Si se
permitiera señalar sin el cerrojo, el que señala podría estar anunciando un dato
que otro está a punto de cambiar.

La consecuencia, medida en el acto tercero, es que **el despertado nunca
readquiere a la primera**: en el momento de la señal el cerrojo lo tiene el que
señala, así que el despertado cae en la cola del cerrojo y lo hereda al `UNLOCK`.
El camino de readquisición directa existe igualmente y se conserva, porque es el
que se recorrería si el cerrojo estuviera libre al despertar, y porque dejar ese
caso sin cubrir sería confiar en que nunca ocurre.

Con semáforos esto no pasa: el permiso se lleva en la mano y no hay nada que
volver a pedir. Ahí está la diferencia de coste entre los dos mecanismos: el
semáforo despierta y corre; la condición despierta, pide y quizá espera.

### 4.2 La señal se pierde, y por eso el bucle no es un `if`

Una señal sin nadie esperando no se guarda. Es lo que distingue una condición de
un semáforo: el semáforo recuerda el permiso, la condición no recuerda la señal.
Por eso en un monitor el candidato se comprueba en un **bucle**:

```
bucle:  LOAD 5
        CMP 1
        JZ listo
        COND_WAIT hay_datos m
        JMP bucle
listo:  ...
```

Si fuera un `if`, un hilo despertado por una señal que no era para él seguiría
adelante con la condición sin cumplir. El bucle hace que, después de cada
despertar, se vuelva a mirar el dato, que es lo único que no miente.

---

## 5. La demostración que importa

`--sincronizacion` ejecuta tres actos.

### Acto primero: un semáforo de valor uno es un cerrojo

El mismo contador de la fase 5, con las tres protecciones:

```
proteccion                          valor esperado   tics  resultado
ninguna                                 5       10     97  pierde 5
cerrojo                                10       10    117  correcto
semaforo de valor 1                    10       10    118  correcto
```

El semáforo cuesta un tick más que el cerrojo y da el mismo resultado, que es lo
que se quería comprobar: el cerrojo es el caso particular de contador igual a
uno. Lo que el contador añade no es otra exclusión, es poder decir "dos a la vez"
para un recurso del que hay dos unidades.

### Acto segundo: productor y consumidor sin gastar CPU

Dos semáforos contrapuestos, uno por cada condición del problema, con un solo
hueco:

```
[    7] SEM_WAIT    p1/h1   toma un permiso de 'huecos', quedan 0
[    8] SEM_WAIT    p1/h2   no quedan permisos en 'llenos', se pone a esperar
[   11] SEM_POST    p1/h1   devuelve un permiso a 'llenos' y se lo cede a p1/h2
[   12] WAKE        p1/h2   obtiene un permiso de 'llenos' y vuelve a listos
[   15] SEM_POST    p1/h2   devuelve un permiso a 'huecos', queda en 1
```

El consumidor empezó antes y se durmió en `llenos` sin mirar el dato ni una vez.
El productor no subió el contador: cedió el permiso. Y hay dos semáforos porque
hay dos condiciones distintas —"hay dato" y "hay hueco"—, no porque el problema
pida dos.

### Acto tercero: el monitor y su paso de más

El mismo problema con un cerrojo y una condición. La traza completa está en la
sección 4: el consumidor suelta `m` al dormirse, el productor lo hereda, señala,
y el consumidor vuelve a la cola del cerrojo. La foto final:

```
dato que dejo el productor (unidad 5) .... 1
dato que leyo el consumidor (unidad 6) ... 1
```

El consumidor leyó un uno, no un cero: el bucle de comprobación hizo su trabajo.

---

## 6. Semáforo o condición

| | Semáforo | Condición |
|---|---|---|
| Qué guarda | Un contador | Nada; solo la cola |
| Quién puede señalarlo | Cualquiera | Solo quien tiene el cerrojo |
| La señal se pierde | No: queda el permiso | Sí |
| Al despertar | Sigue con el permiso en la mano | Tiene que readquirir el cerrojo |
| Dueño | No tiene | El cerrojo asociado |
| Sirve para | Contar recursos, dar turnos | Esperar un estado que cambia |
| Se puede detectar un ciclo | No con certeza | Sí, vía el cerrojo |

La regla práctica que se deduce de la tabla: si el problema se puede enunciar
como *cuántos*, semáforo; si se enuncia como *cuándo*, condición. Contar huecos
es un semáforo. "Avisadme cuando el fichero esté cerrado" es una condición.

Todo esto se puede defender sin apelar al gusto: la sección 7 mide los mismos
mecanismos con el mismo problema y pone un número en cada fila de esta tabla.

---

## 7. Qué cuesta cada mecanismo, medido

La sección 6 los compara por lo que son. Falta lo que cuestan, que es la mitad
de la respuesta cuando alguien pregunta "¿y por qué este y no el otro?".

`p-os --medicion` corre tres escenarios y saca las cifras en **ticks**, que aquí
es la única unidad honesta: un tick es una instrucción retirada. Todo se mide con
cuanto 1, porque el contador sin protección solo pierde actualizaciones si al hilo
lo desalojan entre `LOAD` y `STORE`, y con cuanto 1 lo desalojan en cada
instrucción. El precio de forzar el fallo es que entonces cada tick es también un
cambio de contexto, así que esa columna saldría igual que el reloj y no se imprime:
contaría lo mismo dos veces. Quien la quiera, la tiene en la traza.

### 7.1 El mismo contador, tres protecciones

Dos hilos, cinco incrementos cada uno; debería quedar 10.

| mecanismo | resultado | reloj | bloqueos | despertares | sincro |
|---|---|---|---|---|---|
| sin protección | incorrecto: 5 de 10 | 97 | 0 | 0 | 0 |
| cerrojo | correcto | 117 | 1 | 1 | 20 |
| semáforo de valor 1 | correcto | 118 | 1 | 1 | 21 |

La fila rota es la más barata, y ese es el primer aviso: no es más rápida, es
más corta. Hace menos trabajo porque pierde cinco actualizaciones por el camino.
Una tabla de tiempos sin columna de resultado habría recomendado el error.

Entre cerrojo y semáforo hay un tick de diferencia, y no es ruido: el semáforo
necesita `SEM_INIT`, que es la instrucción que crea la tabla. Las dos filas
ejecutan las mismas 20 operaciones de sincronización (10 `LOCK` y 10 `UNLOCK`;
el semáforo, 10 `SEM_WAIT` y 10 `SEM_POST` más la creación). Para este problema,
elegir entre los dos no cambia nada medible, y eso también es una respuesta: un
semáforo de valor uno *es* un cerrojo con otro nombre, así que solo se justifica
cuando además hace falta contar.

### 7.2 Tres piezas por un hueco de uno

El consumidor debería llevarse las tres piezas.

| mecanismo | resultado | reloj | bloqueos | despertares | sincro | espera |
|---|---|---|---|---|---|---|
| giro sobre cerrojo | correcto | 107 | 6 | 6 | 14 | 47,5 |
| dos semáforos | correcto | 88 | 5 | 5 | 14 | 35,5 |
| un permiso por tres | **interbloqueo** | 30 | 2 | 0 | 5 | — |
| cerrojo y condición | correcto | 110 | 6 | 6 | 17 | 49,0 |

El giro acierta y cuesta 19 ticks más que los semáforos (un 22 %). No está
esperando: está dando vueltas. Cada vuelta pide el cerrojo, mira la bandera y lo
suelta. La espera media lo delata mejor que el reloj: 47,5 ticks por hilo frente a
35,5. Girar no es esperar mejor, es esperar varias veces.

La fila del permiso único es la que enseña para qué sirve medir dos cosas a la
vez. El productor avisa una sola vez, al final, en vez de avisar por pieza; el
consumidor se duerme esperando la segunda y el productor se queda sin hueco
libre. Los dos acaban dormidos, el núcleo lo declara interbloqueo y no se copia
ninguna pieza. Es la fila más barata de la tabla —30 ticks, un 66 % menos que los
semáforos— y la única rota. En la columna de espera pone una raya porque no
terminó ningún hilo: sin volcado de memoria no hay dato que comparar.

La condición es la más cara de las tres que funcionan: 22 ticks más que los
semáforos (un 25 %) y 3 operaciones de sincronización más. No es un defecto, es
el precio anunciado en 4.1: el despertado no hereda el cerrojo porque lo tiene
quien señala, así que da un rodeo para volver a entrar. Se paga a gusto cuando la
espera no se puede contar con permisos y hay que reevaluar una condición con el
cerrojo en la mano.

### 7.3 El coste crece con el trabajo, no con el mecanismo

El mismo contador con cerrojo, cambiando solo el número de vueltas:

| vueltas | valor | reloj | ticks por vuelta | sincro |
|---|---|---|---|---|
| 2 | 4 | 51 | 25,50 | 8 |
| 5 | 10 | 117 | 23,40 | 20 |
| 10 | 20 | 227 | 22,70 | 40 |
| 20 | 40 | 447 | 22,35 | 80 |

Los ticks por vuelta tienden a un valor fijo: el arranque solo se nota en la
primera fila. La sincronización crece exactamente a cuatro operaciones por vuelta
(dos `LOCK` y dos `UNLOCK`) y no se degrada al crecer la carga, porque no queda
estado de una vuelta para la siguiente: lo único que persiste es el cerrojo, y se
suelta siempre. Una columna que creciera aquí sería la señal de una fuga.

### 7.4 Lo que estas cifras no dicen

- **No son milisegundos.** No hay caché, ni predictor, ni memoria real. Sirven
  para comparar entre sí dentro de este simulador; extrapolarlas a un procesador
  de verdad sería inventar.
- **Todo está medido con cuanto 1**, que es el peor caso para el planificador:
  un cambio de contexto por instrucción. Con un cuanto mayor el reparto de ticks
  cambiaría y también las cifras; lo que no cambiaría es el orden de las filas,
  que es lo que la tabla defiende.
- **Un escenario, tres piezas.** No es un banco de pruebas, es una demostración:
  el tamaño está elegido para que se lea en una pantalla.
- **Y son reproducibles.** Dos ejecuciones dan las mismas cifras, y hay una
  prueba que lo comprueba. Una tabla que cambia en cada ejecución no prueba nada.

---

## 8. Lo que no se ha hecho, y por qué

- **Monitores con semántica de Hoare** (el que señala cede la CPU al señalado de
  inmediato). Se ha implementado la de Mesa, que es la de los sistemas reales:
  el señalado vuelve a la cola y comprueba. La de Hoare exige un cambio de
  contexto dentro de la propia instrucción y hace la traza mucho menos legible.
- **Barreras y eventos**. Se pueden construir con lo que hay; añadirlas sería
  repetir el mismo mecanismo con otro nombre, sin enseñar nada nuevo.
- **Semáforos por proceso**. La tabla es del sistema, no del proceso, y por eso
  dos procesos distintos podrían compartir un nombre. En un sistema real con
  espacios separados eso sería un error de diseño; aquí los procesos de la
  demostración no comparten memoria y el caso no se da. Queda anotado.
- **Prioridades en la cola de espera**. Todas las colas son FIFO, por la misma
  razón que en la fase 5: el orden de la traza tiene que ser reproducible.
- **Señal asíncrona desde otro hilo** (lo que en POSIX sería `pthread_cond_signal`
  sin el cerrojo). Ya está explicado en 4.1: se exige el cerrojo a propósito.

---

## 9. Pruebas

De 184 a **233 pruebas**, todas en verde. Las 49 nuevas:

| Prueba | Qué comprueba |
|---|---|
| `SemaphoreManagerTest` (10) | Valor inicial, permisos que se agotan, cesión FIFO, devolver sin espera, semáforo inexistente, no reiniciar, no nacer negativo, tabla |
| `ConditionManagerTest` (7) | Condición atada a su cerrojo, cerrojo distinto es fallo, señal al primero, señal perdida, broadcast, nadie despierto sin señal |
| `AssemblerTest` (+7) | Carga de las seis instrucciones, valor cero permitido, negativo rechazado, falta de operandos, exceso de operandos |
| `InterpreterTest` (+3) | `SEM_WAIT` cobra el permiso y bloquea al segundo, `SEM_POST` devuelve, `COND_WAIT` bloquea siempre |
| `KernelSyncTest` (13) | Exclusión con semáforo, el que espera no gasta CPU, productor/consumidor, monitor completo, readquisición, señal a uno solo, señal perdida, `COND_WAIT` sin cerrojo falla, broadcast, semáforo inexistente, traza reproducible, la tabla de cerrojos sigue vacía |
| `MetricsDemoTest` (9) | Que la tabla diga la verdad y no cambie: el contador sin protección pierde, cerrojo y semáforo lo salvan, el semáforo de valor uno no es más barato, los tres mecanismos entregan las piezas, el permiso único acaba en interbloqueo, el giro cuesta más que dormir, la condición cuesta más que el semáforo, las cifras se repiten, el coste por vuelta no crece |

Las dos que más costaron escribir fueron las de la traza, y por un motivo que
conviene contar: la primera versión de `sinPermisoSeDuerme` buscaba el primer
`BLOCK` de la traza y el siguiente `WAKE`, sin comprobar que fueran del mismo
hilo, y fallaba porque entre medias había turnos de otros. La prueba estaba mal,
no el sistema. La segunda, `alDespertarSeReadquiereElCerrojo`, esperaba un suceso
`LOCK ... readquiere` que en este escenario no puede ocurrir, por lo explicado en
4.1. Se reescribió para comprobar lo que sí ocurre: que el despertado vuelve a
dormir por el cerrojo y que no hay ni un turno suyo entre la señal y la herencia.

---

## 10. Estado y siguiente paso

Con esto el sistema tiene las tres piezas clásicas de la sincronización a bajo
nivel: cerrojo, semáforo y condición, las tres sobre la misma cola de espera y
con la misma regla de cesión directa.

Lo que queda por delante, en el orden en que tiene sentido:

1. **Terminal como proceso** y llamadas al sistema, para que la interfaz deje de
   ser una demostración por argumentos.
2. **Paginación bajo demanda con reemplazo en ejecución**, que hoy existe por
   separado del planificador.

La medición que faltaba ya está: la sección 7 es la tabla que este capítulo
necesitaba para defender las decisiones de la 6 sin apelar al gusto.
