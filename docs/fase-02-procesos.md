# Fase 2: procesos, hilos y planificación

Esta fase convierte el sistema en algo que ejecuta. Hasta aquí había memoria real
pero nadie la usaba; ahora hay procesos con su programa cargado, hilos que corren
de verdad sobre la máquina virtual y un planificador que decide a quién le toca.

Lo que se ha construido, lo que se ha medido y lo que queda pendiente para las
fases siguientes.

---

## 1. Qué hay ahora

| Pieza | Clase | Papel |
|---|---|---|
| Bloque de control de proceso | `pos.proc.Pcb` | Programa, espacio de direcciones e hilos del proceso |
| Bloque de control de hilo | `pos.proc.Tcb` | PC, registros, estado y métricas de un hilo |
| Repertorio | `pos.proc.Instruction` | 14 instrucciones, interfaz sellada con records |
| Cargador | `pos.proc.Assembler` | Texto a programa con saltos ya resueltos |
| Intérprete | `pos.proc.Interpreter` | Ejecuta una instrucción por llamada |
| Testigo de turno | `pos.kernel.TurnBaton` | Garantiza que solo un hilo ejecuta a la vez |
| Hilo portador | `pos.kernel.ProcessThread` | El hilo real de Java de cada hilo del sistema |
| Núcleo | `pos.kernel.Kernel` | Reloj, colas, admisión, desalojo, métricas |
| Traza | `pos.kernel.Trace` | Sucesos con tick y número de orden |
| Informe | `pos.kernel.KernelReport` | Tabla de métricas y promedios |
| Planificadores | `pos.sched` | FCFS y Round Robin con cuanto configurable |

---

## 2. Modelo de ejecución

Es el punto que hay que poder explicar en la defensa, así que va entero.

Cada hilo del sistema tiene **un hilo real de Java**. Pero solo uno ejecuta a la
vez: lo garantiza el testigo de turno. El núcleo concede el turno a un hilo, se
bloquea esperando que lo devuelva, y el hilo ejecuta instrucciones hasta que se
le acaba el cuanto, cede, se bloquea o termina.

```
núcleo                          hilo del proceso
  |                                   |
  |-- grant(t, cuanto) --notifica-->  | (despierta)
  | (espera)                           | ejecuta instrucción
  | (espera)                           | ejecuta instrucción
  | (espera)                           | se acaba el cuanto
  |<--------- returnTurn() ------------|
  | (lee el motivo y decide)
```

Consecuencias, todas deliberadas:

- **Un solo hilo dentro del intérprete a la vez.** Equivale a una CPU. Por eso no
  hay condiciones de carrera en el estado del sistema y la traza es
  reproducible: mismo escenario, misma traza, mismo tick, mismo orden.
- **El reloj avanza por instrucción retirada**, no por tiempo real. Un cuanto de
  4 son cuatro instrucciones. Esto es lo que hace comparables las tablas entre
  planificadores: la misma carga cuesta lo mismo en todos.
- **Un fallo dentro de un proceso no tumba el sistema.** Si el intérprete lanza
  un fallo (dirección fuera del espacio, salto fuera del programa), el hilo lo
  captura, se anota en la traza, se marca el bloque de control y se devuelve el
  turno igualmente. Está probado: un proceso que escribe en la dirección 900 con
  un espacio de 256 unidades falla y el otro proceso termina sin enterarse.
- **El testigo tiene plazo.** Si un hilo se colgara sin devolver el turno, el
  núcleo falla con un mensaje que dice qué hilo fue, en vez de esperar para
  siempre.

Hay un detalle que parece menor y no lo es: quien devuelve el turno limpia el
portador antes de avisar. Si no lo hiciera, el hilo podría volver a ver el turno
como suyo antes de que el núcleo lo recogiera y ejecutaría dos cuantos seguidos.
Ese fallo apareció al escribirlo y está comentado en `TurnBaton`.

---

## 3. Repertorio de instrucciones

Direcciones dentro del espacio del proceso, saltos a índice absoluto del
programa. Una unidad de memoria es un byte, así que los valores guardados van de
0 a 255; los programas de demostración se mantienen en ese rango a propósito.

| Instrucción | Efecto |
|---|---|
| `SET n` | acumulador = n |
| `LOAD dir` / `STORE dir` | lee o escribe una unidad del espacio del proceso |
| `ADD n` / `SUB n` | suma o resta al acumulador |
| `CMP n` | deja la bandera de igualdad |
| `JMP`, `JZ`, `JNZ` | salto incondicional o según la bandera |
| `SPAWN etiqueta` | crea un hilo hermano en el proceso, que empieza ahí |
| `YIELD` | cede el turno sin bloquearse |
| `SLEEP n` | se bloquea n ticks |
| `EXIT` | termina el hilo |
| `NOP` | no hace nada, sirve para etiquetar posiciones |

`SPAWN` es lo que hace que el planificador planifique hilos y no procesos: dos
hilos del mismo proceso comparten programa y espacio de direcciones pero tienen
contador de programa propio, así que pueden estar en bucles distintos.

---

## 4. Planificadores

Los dos que pide esta fase, elegidos para que la diferencia se vea.

- **FCFS**: elige por instante de llegada, sin expropiar. Cuanto sin límite.
- **Round Robin**: primero de la cola, cuanto configurable, vuelta al final.

El desempate es siempre por llegada, luego proceso, luego hilo. Nunca por orden de
iteración de una colección: eso haría que dos ejecuciones eligieran distinto.

---

## 5. Medición

Escenario: tres procesos que llegan en el tick 0. Dos de cálculo (`contador`,
26 ticks de CPU cada uno) y uno que solo espera (`SLEEP 8`, 2 ticks de CPU).

| Planificador | Retorno medio | Espera media | Respuesta media | Reloj |
|---|---:|---:|---:|---:|
| FCFS | 46,67 | 28,67 | 26,00 | 62 |
| Round Robin, cuanto 4 | 44,00 | 26,00 | 4,00 | 54 |
| Round Robin, cuanto 1 | 40,33 | 22,33 | 1,00 | 54 |

Lo que dicen los números, sin adornos:

- **FCFS deja al proceso corto esperando 52 ticks** para ejecutar 2. Es el
  problema del convoy, medido, no descrito.
- **Round Robin hunde el tiempo de respuesta** (de 26 a 4, y de 4 a 1 al bajar el
  cuanto) a cambio de más cambios de contexto: 4 con FCFS, 16 con cuanto 4 y 54
  con cuanto 1. El coste del cambio de contexto es lo que se paga por responder
  antes, y por eso el cuanto no puede bajarse sin más.
- El retorno medio mejora con cuanto pequeño en este escenario concreto porque el
  proceso corto sale antes. No es una ley general y el documento no lo presenta
  como tal; con más carga de cálculo la tendencia se invierte.

Reproducir:

```bash
. ~/.local/env-pos.sh
mvn package
java -jar target/p-os-0.1.0-SNAPSHOT.jar           # la tabla de arriba
java -jar target/p-os-0.1.0-SNAPSHOT.jar --traza   # y la traza completa
```

---

## 6. Pruebas

34 pruebas nuevas, 81 en total, todas en verde. Las que importan:

| Prueba | Qué demuestra |
|---|---|
| `AssemblerTest` (8) | Etiquetas resueltas a índices; errores con nombre y línea |
| `InterpreterTest` (6) | Aritmética, bucle, ceder, dormir, lanzar, dirección inválida |
| `SchedulerTest` (6) | Elección determinista y desempate explícito |
| `KernelSchedulingTest` (13) | Alternancia, reproducibilidad, SPAWN, SLEEP, fallo aislado, métricas |

La prueba de reproducibilidad compara la traza completa de dos ejecuciones
idénticas línea a línea. Si algún día alguien mete tiempo real en el reloj, esa
prueba falla, que es exactamente lo que debe pasar.

---

## 7. Lo que esta fase no hace, y por qué

- **El espacio de direcciones todavía no está en memoria física.** Cada proceso
  tiene su vector propio con acceso comprobado. Es un hueco consciente: la fase 3
  es justamente el traductor, y construir aquí una traducción a medias obligaría
  a rehacerla. Lo que sí está garantizado es que un proceso no puede escribir
  fuera de su espacio.
- **No hay cerrojos ni espera por recurso.** `SLEEP` es la única forma de
  bloquearse. `LOCK`/`UNLOCK` llegan en la fase 8, con el grafo de recursos, y
  necesitan el mismo testigo de turno más una cola de espera por cerrojo.
- **Una sola CPU.** Someter varios hilos a ejecución simultánea exigiría
  sincronizar el estado del sistema y perdería la reproducibilidad de la traza.
  Para comparar planificadores es una ventaja, no una limitación.
- **La admisión de procesos solo ocurre antes de arrancar.** Crear procesos en
  marcha es cosa de la terminal (fase 7), que será un proceso más del sistema.

---

## 8. Siguiente fase

Fase 3: los tres traductores (contiguo, paginación, segmentación). El punto de
entrada está claro: hoy `Pcb.load` y `Pcb.store` indexan un vector propio; ahí es
donde entra el traductor, y esa es toda la superficie que hay que tocar.
