# P-OS — Documento de arquitectura

Versión 0.1 — documento de planeación, previo a la escritura de código.
Estado: pendiente de aprobación.

---

## 1. Propósito y alcance

P-OS es un sistema operativo didáctico completo que se ejecuta sobre una máquina
virtual de Java. Implementa de forma real procesos, hilos, planificación de CPU,
gestión de memoria con tres esquemas de direccionamiento, memoria virtual,
sincronización, detección de interbloqueos y un sistema de ficheros propio
persistente en un fichero de imagen, todo gobernado desde una terminal de
comandos que es a su vez un proceso del sistema.

### Dentro del alcance

- Núcleo con estado propio, independiente de la interfaz y ejecutable sin ventana.
- Terminal con argumentos, redirección, tuberías e historial.
- Procesos con PCB, hilos reales dentro del proceso y ejecución verificable.
- Cuatro planificadores y modo de comparación sobre una misma carga.
- Memoria real por bloques con traducción de direcciones comprobable.
- Tres esquemas de direccionamiento conmutables en caliente.
- Memoria virtual con fallo de página y reemplazo FIFO, LRU y OPT.
- Sistema de ficheros propio sobre imagen única, con tres métodos de asignación.
- Sincronización con cerrojos, semáforos, variables de condición y monitores.
- Interbloqueos: grafo de asignación, detección de ciclos y algoritmo del banquero.
- Interfaz gráfica con seis vistas y tabla de resultados comparativa.
- Empaquetado en ejecutable y en imagen de máquina virtual.

### Fuera del alcance, declarado explícitamente

- Arranque en hardware desnudo. No lo hay: por debajo se ejecuta una máquina
  virtual de Java. El sistema arranca en una máquina virtual, no en un
  procesador. Esta frase debe aparecer así en toda la documentación.
  Que la máquina sea virtual no lo convierte en un simulador: no modela el
  estado para dibujarlo, lo ejecuta. Los procesos son hilos reales de la JVM,
  la memoria son bloques reales y el sistema de ficheros escribe bytes reales
  en una imagen. Lo único que no es de relojería es el tiempo, que avanza por
  ticks para que las medidas salgan iguales en cada ejecución.
- Kernel escrito en C o ensamblador.
- Aislamiento real de memoria entre procesos a nivel de hardware. La separación
  es lógica, impuesta por el traductor de direcciones del sistema.
- Usuarios, contraseñas y permisos. Existe una sesión con nombre, sin
  autenticación, que solo etiqueta la traza.
- Red, sonido y dispositivos externos.
- Drivers de hardware.

---

## 2. Principios de arquitectura

Estos seis principios deciden casi todas las discusiones de diseño que vendrán.
Cuando haya duda, se resuelve mirando aquí.

**P1. El núcleo no conoce la interfaz.** El paquete del núcleo no importa nada de
Swing (`javax.swing`, `java.awt`). Si algún día aparece una importación de Swing
fuera del paquete de interfaz, es un error de arquitectura, no un atajo.

**P2. La interfaz no calcula.** No traduce direcciones, no elige huecos, no
ordena colas, no decide estados. Solo lee el estado que publica el núcleo y
pinta. Toda cifra que aparezca en pantalla viene del núcleo.

**P3. Lo que varía es una interfaz.** Gestores de huecos, traductores de
direcciones, planificadores, métodos de asignación de disco, políticas de
reemplazo y comandos: todos son estrategias detrás de un contrato. Ningún
`switch` sobre el nombre de una estrategia dentro del núcleo.

**P4. El tiempo es virtual.** El reloj del sistema es un entero que avanza a
saltos. No se usa espera real para simular tiempo. La única espera real es la
velocidad de reproducción de la interfaz, que es cosmética y no afecta al
cálculo.

**P5. Todo es reproducible.** La misma carga con las mismas decisiones produce
la misma traza, byte a byte. Si dos corridas difieren, hay un fallo, no una
condición de carrera aceptable.

**P6. El estado tiene un solo dueño.** Cada dato del sistema tiene un único
módulo propietario que lo modifica. Los demás lo leen a través de él. No hay
estructuras duplicadas que se puedan desincronizar.

---

## 3. Modelo de ejecución

La decisión más delicada del proyecto, y la que condiciona todo lo demás.

Java no permite suspender un hilo de forma segura. Por tanto, la expropiación
no puede ser por interrupción, sino por cesión en un punto conocido. Esto obliga
a una decisión fuerte:

**Los procesos no ejecutan código Java arbitrario. Ejecutan programas de
instrucciones del sistema, interpretados por su hilo.**

Un programa de P-OS es una secuencia de instrucciones de un repertorio pequeño y
cerrado: cargar, almacenar, sumar, restar, comparar, saltar, crear hilo,
bloquear, desbloquear, ceder, terminar. El hilo del proceso ejecuta un
intérprete de ese repertorio. El límite entre dos instrucciones es el punto de
cesión.

Consecuencias, todas buenas:

- La expropiación es real a nivel de instrucción, porque el intérprete consulta
  su turno antes de cada una.
- Es imposible que un proceso se cuelgue para siempre: no hay bucles infinitos
  en Java, hay bucles de instrucciones que siempre pasan por el control de turno.
- El determinismo se puede garantizar, porque el orden lo fija el planificador,
  no el planificador del sistema anfitrión.
- La concurrencia es auténtica: los hilos de Java existen, compiten y se
  bloquean de verdad, pero solo avanzan cuando el núcleo les concede el turno.

El mecanismo de concesión es un encuentro de dos vías: el hilo del proceso se
declara listo y espera a que el núcleo le conceda el turno; el núcleo, cuando
planifica ese hilo, le concede el turno y espera a que lo devuelva. Nadie avanza
fuera de ese acuerdo. El hilo del núcleo nunca espera a un hilo al que no le ha
concedido el turno, y un hilo de proceso nunca avanza sin turno.

Riesgo residual y su control: si el hilo de un proceso muere por una excepción
no capturada mientras tiene el turno, el núcleo quedaría esperando. Mitigación
obligatoria: el bucle del proceso captura toda excepción, la convierte en un
fallo del proceso, lo notifica al núcleo y devuelve el turno. Un proceso que
falla no puede tumbar el sistema.

---

## 4. Capas

De dentro hacia fuera. Cada capa solo puede depender de las anteriores.

| Capa | Paquete | Contenido |
|---|---|---|
| 1. Base | `pos.base` | Unidades, direcciones, resultados, errores, traza, reloj, utilidades de texto |
| 2. Almacenamiento físico | `pos.phys` | Memoria física por bloques, mapa de marcos, área de intercambio |
| 3. Estrategias | `pos.alloc`, `pos.mm`, `pos.sched`, `pos.fs` | Gestores de huecos, traductores, planificadores, asignación de disco |
| 4. Ejecución | `pos.proc` | PCB, hilos, repertorio de instrucciones, intérprete |
| 5. Tareas del sistema | `pos.fs`, `pos.sync` | Sistema de ficheros e imagen, cerrojos, semáforos, monitores, grafo de recursos |
| 6. Núcleo | `pos.kernel` | Orquestación: arranque, bucle de planificación, despacho de llamadas, métricas |
| 7. Terminal | `pos.shell` | Analizador de línea, despachador, comandos, tuberías, historial |
| 8. Interfaz | `pos.ui` | Vistas, modelos observables, estilos |
| 9. Arranque | `pos.launch` | Modo consola, modo gráfico, autodiagnóstico |

La capa 8 es la única que importa Swing. La capa 9 es la única que decide qué
interfaz se levanta.

---

## 5. Módulos: responsabilidad y límites

Tabla de contratos. La columna "no hace" es tan importante como la primera: es
la que evita que un módulo se coma responsabilidades ajenas con el tiempo.

| Módulo | Responsabilidad | No hace |
|---|---|---|
| Memoria física | Guardar qué ocupa cada unidad y qué proceso la posee; dar y quitar bloques | Decidir dónde asignar |
| Mapa de marcos | Saber qué marco está libre, ocupado o reservado | Elegir víctima de reemplazo |
| Área de intercambio | Guardar páginas expulsadas | Decidir cuándo expulsar |
| Gestor de huecos | Dado un conjunto de huecos y un tamaño, elegir uno y explicar por qué | Escribir en memoria |
| Traductor de direcciones | Convertir dirección lógica en física y validar límites y permisos | Elegir huecos, gestionar el reloj |
| Planificador | Elegido el conjunto de listos, decidir quién ejecuta y por cuánto | Tocar memoria, cambiar estados |
| Proceso | Existir: identidad, estado, espacio de direcciones, recursos declarados | Decidir cuándo corre |
| Hilo | Ejecutar instrucciones y ceder el turno | Elegir su propio turno |
| Sistema de ficheros | Traducir nombre a FCB, y bloque lógico a bloque físico | Interpretar comandos |
| Imagen de disco | Persistir bloques en un fichero | Saber qué es un fichero |
| Sincronización | Bloquear y despertar hilos sobre cerrojos, semáforos y condiciones | Planificar |
| Grafo de recursos | Registrar peticiones y asignaciones; detectar ciclos | Decidir qué proceso muere |
| Núcleo | Orquestar todo lo anterior y publicar el estado | Pintar nada |
| Terminal | Leer una línea, analizarla y despachar el comando | Implementar el comando |
| Comando | Ejecutar una orden concreta y devolver texto y código de salida | Conocer la interfaz gráfica |
| Interfaz | Renderizar el estado publicado y enviar órdenes | Contener reglas de negocio |

---

## 6. Propiedad del estado

Regla P6 aplicada. Si un módulo necesita cambiar algo que no le pertenece, lo
pide; no lo toca.

| Estado | Dueño | Quién más lo lee |
|---|---|---|
| Ocupación de la memoria física | Memoria física | Métricas, interfaz |
| Tabla de páginas de un proceso | Traductor de paginación | Interfaz, métricas |
| Tabla de segmentos de un proceso | Traductor de segmentación | Interfaz, métricas |
| Lista de listos, bloqueados, ejecutando | Planificador | Interfaz, métricas |
| Estado y contadores de un proceso | Proceso | Planificador, interfaz |
| Reloj del sistema | Núcleo | Todos |
| Traza de eventos | Traza | Interfaz, exportación |
| Métricas acumuladas | Métricas | Interfaz, tabla de resultados |
| Bloques de la imagen de disco | Imagen | Sistema de ficheros |
| Valor de un semáforo | Sincronización | Interfaz |
| Aristas del grafo de recursos | Grafo | Interfaz, detección |

---

## 7. Contratos entre módulos

Descritos en prosa, sin código, para fijar qué entra y qué sale antes de
escribirlo.

### 7.1 Gestor de huecos

Entra: la lista de huecos libres, cada uno con inicio y longitud, y el tamaño
solicitado. Sale: el hueco elegido, o la indicación de que no hay ninguno
válido, más un motivo legible de la decisión que incluya los huecos descartados
y por qué.

El motivo no es un adorno: alimenta la traza, la tabla de resultados y el panel
de la interfaz. Un gestor que no sabe explicarse está mal implementado.

### 7.2 Traductor de direcciones

Entra: el proceso, la dirección lógica y la operación (leer o escribir). Sale:
la dirección física, o un fallo tipificado (fuera de límite, página ausente,
violación de permiso). En paginación, un fallo de página no es un error: es una
señal que el núcleo atiende trayendo la página.

Cada traductor expone además su estructura interna para inspección, porque la
interfaz tiene que poder dibujarla y la defensa tiene que poder explicarla.

### 7.3 Planificador

Entra: el conjunto de hilos listos con sus atributos, el tiempo actual y el
algoritmo activo. Sale: el hilo elegido y la duración del turno concedido. No
cambia estados: informa al núcleo, y el núcleo los cambia.

### 7.4 Proceso

Entra: identidad, programa, tamaño de memoria, número de hilos, recursos
máximos declarados y tiempo de llegada. Sale: su PCB y, durante la ejecución,
sus transiciones de estado.

La declaración de recursos máximos no es opcional: sin ella el banquero no puede
aplicarse. El comando de creación la aceptará con un valor por defecto sensato.

### 7.5 Comando de terminal

Entra: nombre, argumentos, entorno de ejecución con entrada y salida de texto,
y acceso al núcleo. Sale: texto (por la salida) y un código de salida entero.

Todo comando declara además su ayuda y su uso. La ayuda no es un extra: es lo
que hace usable la terminal en la defensa sin memorizar nada.

### 7.6 Estrategia de asignación de disco

Entra: la imagen, el tamaño solicitado en bloques. Sale: el primer bloque, o el
contenido de la cadena, o el bloque de índices, según el método. La interfaz con
el resto del sistema es la misma para los tres métodos.

---

## 8. Memoria

### 8.1 Dimensiones

Parámetros por defecto, todos configurables al crear el sistema:

- Memoria física: 4096 unidades.
- Marco de página: 64 unidades. Resultado: 64 marcos.
- Bloque de disco: 64 unidades, igual que el marco, para que los números sean
  coherentes entre módulos.
- Unidad: un byte lógico del sistema. No es memoria del proceso anfitrión.

### 8.2 Memoria real por bloques

Cada marco es un arreglo de bytes de Java, real, del tamaño del marco. Un
almacenamiento escribe en el arreglo del marco correspondiente; una lectura lo
recupera. La traducción no es un dibujo: es un cálculo que da un marco y un
desplazamiento, y ahí se escribe.

Esto permite la demostración central del proyecto: guardar un valor, cambiar el
esquema de asignación o el gestor de huecos, y comprobar que la dirección física
cambia mientras el valor sigue leyéndose bien.

### 8.3 Los tres esquemas

**Contiguo.** Cada proceso ocupa un tramo seguido. La traducción es base más
desplazamiento, con comprobación contra el límite. Aplica el gestor de huecos.
Sufre fragmentación externa.

**Paginación.** El espacio de direcciones del proceso se divide en páginas del
tamaño del marco. Una tabla de páginas por proceso traduce página a marco. No
hay fragmentación externa, hay interna en la última página de cada proceso.
Aplica el mapa de marcos, no el gestor de huecos.

**Segmentación.** El proceso se divide en segmentos de tamaño variable y
significado lógico: código, datos, pila. Una tabla de segmentos por proceso
guarda base, límite y permisos. La traducción comprueba el límite antes de
sumar. Aplica el gestor de huecos y sufre fragmentación externa.

### 8.4 Memoria virtual

Solo en paginación. Cada entrada de la tabla lleva bit de válido, de referencia
y de suciedad. Un acceso a página no válida produce un fallo de página que el
núcleo atiende: busca marco libre, y si no lo hay, elige víctima según la
política activa y la escribe en el área de intercambio si está sucia.

Políticas: FIFO, LRU y OPT. OPT es legítimo aquí porque los programas son
nuestros y el futuro de la traza es conocido; no es una trampa académica, es un
cálculo exacto sobre un programa determinado. Sirve de cota y permite demostrar
la anomalía de Belady con FIFO.

El área de intercambio es un fichero del sistema de ficheros del sistema, no del
anfitrión, salvo que se decida lo contrario al implementarlo.

**Estado:** implementada en la fase 3b, en cuanto la fase 4 dio un sistema de
ficheros donde poner el área de intercambio. Hay un cuarto esquema,
`pos.mm.DemandPagedSpace`, con bit de presencia y de suciedad; el área es el
fichero `/intercambio`, con cabecera propia y mapa de ranuras; y las tres
políticas comparten una vista de solo lectura de la tabla, así que la que elige
víctima no guarda estado. La demostración (`--swap`) reproduce la anomalía de
Belady y comprueba que las páginas sucias vuelven con su contenido. Lo que la
sección describe y **no** está: paginación bajo segmentación y permisos por
página, que van con los cerrojos de la fase 5. El detalle, en
`docs/fase-03b-intercambio.md`.

### 8.5 Invariantes de memoria

Se comprueban tras cada operación, y su incumplimiento es un fallo del núcleo,
no un aviso:

- La suma de unidades ocupadas más libres es igual al tamaño de la memoria.
- Ninguna unidad pertenece a dos procesos.
- Toda unidad asignada a un proceso está dentro de su base y su límite, o tiene
  una entrada válida en su tabla.
- En paginación, el número de marcos ocupados es igual al número de entradas
  válidas en todas las tablas de páginas.

---

## 9. Sistema de ficheros

### 9.1 Formato de la imagen

Un fichero único, con esta estructura:

1. Superbloque: identificador, versión, tamaño de bloque, número total de
   bloques, número de bloques libres, método de asignación activo, bloque raíz.
2. Mapa de bloques libres: un bit por bloque.
3. Tabla de FCB: por cada fichero, su identificador, nombre, tamaño, propietario
   lógico, fechas lógicas y la información de asignación según el método.
4. Bloques de datos.
5. Bloques de directorio: entrada por nombre con su identificador de FCB.

### 9.2 Métodos de asignación

Conmutables en caliente, con la misma carga para poder comparar:

- **Contigua**: primer bloque y longitud. Acceso directo, sufre fragmentación
  externa, crece mal. La colocación usa `pos.alloc.SlotManager` con BestFit: el
  mismo gestor de huecos que reparte memoria reparte bloques.
- **Enlazada**: cada bloque apunta al siguiente. Sin fragmentación externa,
  acceso directo malo, propenso a cadenas largas, cuatro bytes de puntero por
  bloque.
- **Indexada**: un bloque de índices por fichero, de un solo nivel. Acceso
  directo en dos lecturas, sin fragmentación externa, coste de un bloque de
  índices. Un fichero que no cabe en un índice no entra por esta vía.

El FCB guarda su asignación, no una lista de bloques físicos. Por eso el método
se puede cambiar en caliente, y por eso el cambio no convierte lo ya escrito:
el fichero antiguo sigue leyéndose con el método con el que se creó.

La comparación entre los tres reutiliza los mismos conceptos de huecos que la
memoria. Esa continuidad conceptual es deliberada y es un argumento fuerte en la
defensa.

### 9.3 Verificación

`FsCheck` compara el mapa con los FCB en los dos sentidos: un bloque ocupado que
ningún FCB reclama es una fuga; un bloque que un fichero reclama y el mapa da
por libre es una corrupción, y escribir ahí perdería datos. También comprueba el
superbloque contra el mapa, que los bloques de metadatos no estén libres, que
ningún FCB apunte fuera del rango de datos, que el tamaño declarado cuadre con
los bloques asignados y que todo FCB tenga su entrada de directorio.

### 9.4 Integridad

Al montar la imagen se verifica: superbloque coherente, mapa de libres
consistente con la tabla de FCB, y ninguna cadena o índice apuntando fuera de
rango. Si la verificación falla, el sistema arranca en modo degradado sin montar
y lo dice en el autodiagnóstico. No se repara automáticamente en esta versión.

---

## 10. Sincronización e interbloqueos

### 10.1 Mecanismos

**Implementado en la fase 5**: cerrojos con dueño y cola de espera ordenada por
llegada (`pos.sync.LockManager`), con dos instrucciones nuevas en el repertorio
(`LOCK nombre`, `UNLOCK nombre`). El intérprete pide el cerrojo al núcleo; si lo
consigue sigue, y si no devuelve `BLOCKED` y el núcleo lo saca de la cola de
listos. El cerrojo no se despierta solo: al liberar, el gestor transfiere la
propiedad y apunta el heredero, y es el núcleo, al final del turno, quien lo
devuelve a la cola de listos. La regla de la fase 2 se mantiene intacta: un solo
sitio toca la cola de listos.

La cesión es directa, no por vuelta a competir. Con vuelta a competir, un hilo
recién llegado podría adelantar al que lleva más tiempo esperando y dejarlo sin
entrar nunca; cediendo en el acto el orden de entrada es el orden de la cola. El
precio es un cambio de contexto que no se puede evitar.

Los cerrojos no son reentrantes: pedir uno que ya se tiene es un fallo
declarado. Los bloqueos son bloqueos de verdad de hilos de Java, autorizados por
el núcleo dentro de su bucle, de modo que el orden sigue siendo determinista.

**Implementado en la fase 6**: semáforos contadores
(`pos.sync.SemaphoreManager`) y variables de condición atadas a un cerrojo
(`pos.sync.ConditionManager`), con seis instrucciones nuevas (`SEM_INIT`,
`SEM_WAIT`, `SEM_POST`, `COND_WAIT`, `COND_SIGNAL`, `COND_BROADCAST`). Los tres
mecanismos comparten la misma cola de espera (`pos.sync.WaitQueue`) y la misma
regla de cesión directa, por la misma razón que el cerrojo: ceder en el acto es
lo único que impide que un recién llegado adelante al que lleva más tiempo
esperando.

El semáforo es un contador con cola; al ceder un permiso no sube el contador,
porque entre subirlo y despertar cabría un tercero que se colara. La condición
es solo una cola de dormidos: no guarda la señal, así que el que espera tiene
que comprobar el estado en un bucle. Dormir en una condición incluye soltar el
cerrojo en el mismo paso, y despertar obliga a readquirirlo: mientras lo pide
puede encontrárselo ocupado y volver a dormir. Ese estado —despierto de la
condición, pendiente del cerrojo— es el único que la fase 6 añade al bloque del
hilo. Se exige tener el cerrojo para señalar, de modo que el estado que
justifica la señal esté protegido mientras se comprueba y se anuncia.

Los monitores, por tanto, ya se pueden escribir con lo que hay: un cerrojo y una
condición por cada estado que se espere. No se añade una pieza aparte para ellos.

### 10.2 Interbloqueos

**Implementado en la fase 5**: grafo de espera con una arista por hilo bloqueado
hacia el dueño del cerrojo que espera. La detección de ciclos se ejecuta cuando
el sistema se queda sin hilos listos y sin nadie a quien despertar, que es el
único momento en que el ciclo ya no puede romperse solo; por eso no cuesta nada
en una ejecución normal. El ciclo se informa con nombres de hilo y de cerrojo, y
la ejecución termina con informe en vez de colgarse. No se resuelve: no se mata
ni se hace retroceder a nadie.

También se denuncian los cerrojos huérfanos, es decir, los que quedan en poder
de un hilo que terminó sin liberarlos: el síntoma —hilos que no despiertan— no
distingue por sí solo esa avería de un ciclo.

**Pendiente**: grafo de recursos completo con aristas de petición y asignación
para todos los recursos, detección a demanda con un comando y por umbral de
espera, y algoritmo del banquero con declaración de máximos al crear el proceso.
La demostración preparada —abrazo mortal entre dos hilos que toman dos cerrojos
en orden inverso— ya existe y es reproducible con un solo comando
(`--concurrencia`); el banquero necesitará su propia pantalla.

---

## 11. Traza, métricas y tabla de resultados

### 11.1 Traza

Cada evento se registra con tiempo, número de secuencia global, tipo, procesos
implicados y un texto legible. La secuencia global es lo que garantiza el orden
determinista incluso cuando varios eventos comparten tiempo.

Tipos de evento previstos: llegada, concesión de turno, cesión, expropiación,
cambio de estado, asignación de memoria, liberación, traducción de dirección,
fallo de página, reemplazo, operación de fichero, bloqueo, desbloqueo, creación
de hilo, terminación, detección de ciclo.

### 11.2 Métricas

Por proceso: tiempo de espera, de retorno, de respuesta, CPU consumida, número de
cambios de contexto, fallos de página, bloqueos.

Por sistema: medias de las anteriores, uso de CPU, ocupación de memoria,
fragmentación externa, fragmentación interna, número de huecos, mayor hueco
disponible, comparaciones realizadas por el gestor de huecos, fallos de
asignación.

Por comparación: las mismas métricas para cada planificador y para cada gestor
de huecos sobre la misma carga.

### 11.3 Tabla de resultados

Tres bloques en la misma vista: estado instantáneo de los procesos, métricas
acumuladas del algoritmo activo, y comparación de todos los algoritmos sobre la
carga actual. Exportable a texto, que la terminal puede redirigir a un fichero
del sistema de ficheros propio.

---

## 12. Terminal

### 12.1 Funcionamiento

La terminal es un proceso del sistema, con su PCB, su cuota de CPU y su
posibilidad de ser terminada. Su hilo lee una línea, la analiza y despacha.

Análisis de línea en este orden: separación en palabras respetando comillas,
identificación de operadores de redirección y tubería, agrupación en etapas,
resolución del comando de cada etapa, y ejecución encadenando la salida de una
con la entrada de la siguiente.

Operadores: `>` escribe, `>>` añade, `<` lee, `|` encadena. Un fallo en una
etapa detiene la cadena e informa con código de salida distinto de cero.

Historial de lo escrito, consultable con la orden `history`. Con `--interactivo`
además se navega con las flechas y se completa con el tabulador: ahí hay un
editor de línea de verdad (apartado 12.6). Con un guion, que es lo que usan las
pruebas y `--shell`, no hay teclas sueltas que editar y el historial es solo una
lista.

### 12.2 Familias de comandos

Se implementan en este orden, que es también el orden de sacrificio:

1. **Base**: ayuda, información del sistema, sesión, limpiar, salir.
2. **Procesos e hilos**: listar, crear, terminar, suspender, reanudar, listar
   hilos, crear hilo.
3. **Memoria y ficheros**: estado de memoria, listar, leer, escribir, borrar,
   crear directorio, cambiar de directorio, uso de disco, espacio libre,
   montar, formatear.
4. **Planificación**: elegir algoritmo, fijar quantum, fijar prioridad,
   comparar, restablecer.
5. **Sincronización e interbloqueo**: crear y operar cerrojos, semáforos y
   variables, grafo de recursos, banquero, detectar ciclo.
6. **Trabajos y estrés**: lanzar en segundo plano, listar trabajos, traer a
   primer plano, enviar a segundo plano, generar carga, demostración preparada.

### 12.3 Cambio de vista desde la terminal

Un comando permite cambiar la vista activa del panel. La terminal no pierde el
foco al hacerlo.

### 12.4 Lo construido en la fase 7

La fase 7 entrega la terminal del diseño con tres diferencias que conviene tener
escritas, porque son decisiones y no pendientes:

1. **Las tuberías no crean procesos.** Cada etapa es una orden que escribe en un
   buffer de texto y la siguiente lo lee. No hay `fork` ni descriptores
   compartidos, así que `cat fichero | head -n 1` lee el fichero entero. Se
   arreglará cuando haya señales, no antes: sin `SIGPIPE` no hay forma de cortar
   la primera etapa.
2. **La entrada puede ser un guion o el teclado.** `Console` entrega líneas con
   su tic, y con eso la sesión es determinista y las pruebas pueden afirmar sobre
   el texto que sale; es lo que usan `--shell` y casi todas las pruebas.
   `LiveKeys` hace lo mismo leyendo de la entrada estándar, en otro hilo, y deja
   las líneas en una cola; la terminal, en su turno, mira si hay algo y, si no lo
   hay, pide dormir unos tics y suelta el turno. Ese rodeo no es adorno: si la
   terminal se quedara parada en un `readLine` dentro de su turno, los demás
   procesos no correrían mientras alguien piensa qué escribir, y el sistema
   entero sería un solo hilo disfrazado. Es lo que hace `--interactivo` cuando no
   hay terminal donde poner el modo crudo, y es también lo que usa `--shell`.
   Con terminal de verdad entra `TtyKeys`, que lee bytes en vez de líneas: es lo
   único que permite separar una tecla de un texto, y sin eso no hay flechas,
   Ctrl-C ni tabulador (apartado 12.6).
3. **Las familias de comandos son cuatro, no seis.** Ficheros, texto y sistema,
   más el control de trabajos (apartado 12.5). Planificación, sincronización e
   interbloqueo se ven desde las demostraciones (`--traza`, `--concurrencia`,
   `--sincronizacion`), no desde órdenes de la terminal.

Lo que sí está entero: la frontera de llamadas al sistema, la tabla de
descriptores por proceso, la terminal como proceso nativo con cuota y bloqueos,
el análisis de línea con comillas, tuberías y redirecciones, el control de
trabajos y la contabilidad de llamadas por código y por proceso.

### 12.5 Control de trabajos

Un **trabajo** es un proceso que arrancó la sesión, con un número de la sesión,
un nombre puesto por quien lo lanzó y un plano: primer o segundo. El núcleo no
sabe nada de eso; la sesión lo guarda en `JobTable` y pregunta el estado al
núcleo cada vez que pinta la tabla. Guardar el estado en la tabla de trabajos
sería enseñar la foto de hace un rato: un trabajo que terminó hace veinte tics
seguiría apareciendo como «ejecutando».

Órdenes:

| Orden | Qué hace |
| --- | --- |
| `spawn programa ruta &` | arranca el proceso en segundo plano y lo anota como trabajo |
| `jobs` | lista los trabajos vivos con número, pid, estado, plano y nombre |
| `stop %n` | detiene el trabajo sin matarlo |
| `bg %n` | lo deja en segundo plano; si estaba detenido, lo reanuda |
| `fg %n` | lo trae al primer plano y la terminal espera a que termine |
| `kill %n` | termina el trabajo y lo saca de la tabla |

Todas aceptan `%n` o un pid. Un número suelto se interpreta siempre como pid,
porque `ps` y el resto del sistema hablan en pids.

Del lado del núcleo hacen falta tres operaciones nuevas, y con ellas un estado:

- `suspend(pid)` saca los hilos ejecutables del proceso de la cola de listos y
  los marca `STOPPED`. El hilo de Java que los transporta sigue esperando turno
  igual que antes: detener no cuesta ni un hilo del sistema anfitrión.
- `resume(pid)` los devuelve a la cola de listos.
- `waitForProcess(pid)` bloquea al que llama hasta que el proceso indicado
  termine; es lo que hace `fg`, y es una espera más de las que el núcleo ya sabe
  gestionar, no un caso aparte.

Decisiones que conviene tener escritas, porque son límites y no olvidos:

1. **Un hilo bloqueado no se detiene.** Está esperando un cerrojo o un permiso
   que el recurso ya le dio por concedido; congelarlo ahí dejaría la concesión en
   el aire y el recurso contaría como ocupado por quien no lo usa. Se detiene lo
   que puede ejecutar; lo que espera, sigue esperando.
2. **Nadie se detiene a sí mismo.** El hilo que ejecuta la orden está dentro de
   su turno: si se marcara detenido, al devolver el turno el núcleo lo volvería a
   encolar y la orden se contradiría. Un proceso puede detener a otro.
3. **Ctrl-Z llega, pero todavía no detiene nada.** Con el editor de línea
   (apartado 12.6) las teclas se leen sueltas, así que un Ctrl-Z ya no se pierde
   como texto. Lo que falta es la última pieza: que esa tecla detenga el trabajo
   en primer plano. Es una orden de la sesión y no del teclado, y mientras no
   esté, `stop %n` hace lo que en un terminal de verdad hace esa tecla. `fg`
   espera en lugar de recibir un aviso asíncrono, que es justo lo que permite no
   necesitar manejadores de señal.
4. **Un detenido no es un averiado.** Al cerrar el sistema, un hilo que seguía
   detenido se anota como «seguía detenido al cerrar el sistema», no como
   interbloqueo: no espera nada. La traza lo dice con la palabra «en pausa»
   (`IDLE`) en vez de `DEADLOCK`.

### 12.6 El editor de línea

Con `--interactivo` delante de un terminal de verdad, la sesión edita la línea
mientras se escribe. La pieza que lo hace posible es el **modo crudo**: el
terminal deja de acumular bytes hasta el salto de línea y entrega cada pulsación
tal cual llega. Se pide con `stty raw -echo` al arrancar, guardando antes
`stty -g`, y se devuelve al estado guardado al salir — también si la sesión
termina por un `Ctrl-D`, que es lo normal.

Tres clases, cada una con un trabajo:

| Clase | Qué hace | Qué no hace |
| --- | --- | --- |
| `Key` | una pulsación ya decidida: `UP`, `ENTER`, `CHAR`… | no toca bytes ni pantalla |
| `LineEditor` | la lógica: búfer, cursor, historial, autocompletado | no conoce el terminal ni los flujos |
| `TtyKeys` | lee bytes, los corta en teclas y pinta la línea | no decide qué significa una orden |

La separación no es decorativa: `LineEditor` se prueba con teclas y nada más,
sin terminal y sin hilos, y ahí están los 17 casos que cubren el historial, el
cursor y el tabulador. `TtyKeys` se prueba dándole bytes, que es exactamente lo
que llega de un terminal: `\u001b[A` es la flecha arriba y `ñ` son dos bytes.

Teclas:

| Tecla | Qué hace |
| --- | --- |
| imprimibles | se insertan donde esté el cursor, no al final |
| `Enter` | entrega la línea |
| flechas izquierda/derecha | mueven el cursor dentro de la línea |
| flechas arriba/abajo | recorren el historial; al bajar del todo vuelve al borrador |
| `Inicio` / `Fin`, o `Ctrl-A` / `Ctrl-E` | a los extremos de la línea |
| `Retroceso` / `Suprimir` | borran antes y bajo el cursor |
| `Ctrl-K` / `Ctrl-U` | borran hasta el final y la línea entera |
| `Tab` | completa el nombre de la orden |
| `Ctrl-C` | tira lo escrito y deja la línea vacía; no cierra la sesión |
| `Ctrl-D` | con la línea vacía cierra la sesión; con texto, borra bajo el cursor |

El tabulador completa contra los nombres de orden, que se copian al construir:
el hilo que lee no toca el shell. Si solo hay una orden que empieza así, la
escribe entera y añade un espacio. Si hay varias pero comparten más prefijo del
escrito, alarga hasta donde coinciden. Y si no puede decidir, **las enseña**:
callarse dejaría a quien escribe sin saber si el tabulador funcionó o no.

El aviso `pos:/>` lo pinta el editor, pegado a la línea, porque es el único que
sabe dónde está el cursor. Se lo da la terminal en su turno, cuando se queda sin
nada que ejecutar: el aviso sale del directorio actual y eso es estado del
sistema, no del teclado. Por eso el aviso no aparece antes de la respuesta del
comando anterior.

**Líneas más largas que la pantalla.** Cuando el texto pasa del ancho del
terminal, el terminal baja solo de fila, y a partir de ahí repintar ya no es
«borrar la línea»: hay que subir hasta la primera fila del bloque, borrar todo
lo de debajo y volver a escribir. El ancho se pide una vez al arrancar con
`stty size`; las pruebas se lo dan al construir, porque sin terminal no hay a
quien preguntar.

Cada repintado calcula primero la geometría en una función pura, `colocar`, y
la aplica después: cuántas filas ocupa el bloque (aviso más texto), en qué fila
y columna queda el cursor, cuántas filas hay que subir desde el final del texto
hasta él y en qué columna acaba lo escrito. Lo de la columna final no es
adorno: al pulsar `Enter` el cursor tiene que quedar detrás del último carácter,
y si está a media línea eso son dos movimientos —`\r` y tantas columnas a la
derecha—, no un salto desde donde esté.

El caso que rompía las cuentas es el borde exacto: un texto que acaba justo en
la última columna no baja de fila hasta que llega el carácter siguiente, así
que el terminal tiene una fila menos de las que parecen. Por eso `colocar`
devuelve un `salto` explícito y el editor pinta `\r\n` en ese momento; con eso
las filas que cuenta y las que hay de verdad coinciden.

Límite conocido: el ancho se lee al arrancar y no se vuelve a mirar, así que
cambiar el tamaño de la ventana a mitad de sesión descuadra el repintado hasta
que se sale. Se arregla con `SIGWINCH`, que llegará con las señales; para mirarlo
a ojo está `tools/pantalla.py`, que lanza la sesión en un terminal falso de
80 columnas y vuelca las filas tal como quedan.

Dos límites que conviene tener escritos:

1. **Sin `stty` no hay editor, y se nota.** En Windows no existe `stty`. Si se
   pidiera el modo crudo de todos modos, el terminal seguiría en modo cocinado:
   las flechas llegarían como basura y el eco se pintaría dos veces. Por eso
   `TtyKeys.conTerminalCrudo` comprueba primero y devuelve `null` si no puede;
   quien llama usa entonces la lectura por líneas. Una sesión sin flechas es
   peor que una con flechas, pero mucho mejor que una rota.
2. **El hilo que lee no ejecuta nada.** Solo encola. La terminal sigue mirando
   la cola en su turno y durmiendo unos tics si está vacía, como con `LiveKeys`.
   Si el hilo que lee ejecutara, el planificador se quedaría esperando a que
   alguien pulse una tecla y el sistema entero sería un solo hilo disfrazado.

---

## 13. Interfaz

### 13.1 Estructura

Una ventana. Franja de estado siempre visible, con uso de CPU, ocupación de
memoria, número de procesos e hilos, planificador activo y reloj del sistema.
Debajo, panel con seis vistas conmutables: Resumen, Procesos, Reparto, Memoria,
Sistema y Traza.

La ventana es de solo lectura: muestra el estado que publica el núcleo, no acepta
órdenes. La terminal vive en `--shell`, en otra ejecución; dentro de la ventana no
hay consola donde escribir. Una vista no interrumpe a otra porque no hay estado
de escritura que interrumpir: cada vista lee la misma foto (`Snapshot`).

### 13.2 Registro visual

Consola técnica densa, con agrupación en tarjetas sin sombras ni aire
decorativo. Tipografía monoespaciada para datos, direcciones, identificadores y
métricas; una sans neutra solo para títulos. Bordes redondeados discretos,
separadores de un píxel.

Paleta: fondo gris ratón oscuro, superficie un paso más clara, verde para lo
sano y lo activo, vinotinto para bloqueos, esperas y errores, gris neutro para
lo inactivo. Los estados nunca se distinguen solo por color: siempre llevan
además una marca de forma o de texto.

Prohibiciones explícitas, para no caer en el aspecto de plantilla generada:
degradados decorativos, cristal esmerilado, sombras grandes, iconos emoji,
titulares de gran tamaño centrados, y animación continua sin significado.

### 13.3 Movimiento

Solo lo que informa: barras que se desplazan al cambiar la carga, fila que
destaca una vez al cambiar de estado, transición de 150 milisegundos al cambiar
de vista. Nada se mueve de forma permanente y nada reacciona al paso del ratón
salvo los controles que se pueden pulsar.

---

## 14. Orden de construcción

Cada fase termina en algo verificable. Ninguna fase empieza sin la anterior
cerrada y probada. El orden está elegido por dependencias, no por vistosidad.

| Fase | Contenido | Verificación |
|---|---|---|
| F0 | Entorno, estructura, compilación, pruebas, integración continua local | Compila y ejecuta una prueba trivial |
| F1 | Memoria física por bloques, mapa de marcos, tres gestores de huecos | Pruebas de hueco exacto, sin hueco, desempate, fusión e invariantes |
| F2 | Procesos, hilos, repertorio de instrucciones, planificador con FCFS y RR | Dos procesos alternan turno y la traza es reproducible |
| F2 | *Después:* SJF y prioridades completan los cuatro de la comparación | La misma carga da cinco filas de tabla, con la misma CPU total en todas |
| F3 | Traductores contiguo, paginado y segmentado | Almacenar y leer el mismo valor bajo los tres esquemas |
| F4 | Memoria virtual, fallo de página, FIFO, LRU, OPT | Contador de fallos correcto y anomalía de Belady reproducida |
| F5 | Sistema de ficheros e imagen, tres métodos de asignación | Persistencia entre sesiones y verificación de integridad |
| F6 | Núcleo completo, métricas, traza, comparación de algoritmos | Misma carga con cuatro planificadores y tabla correcta |
| F7 | Terminal, comandos por familias, redirección y tuberías | Guion de comandos completo sin interfaz gráfica (hecho: `--shell`) |
| F8 | Sincronización, grafo de recursos, banquero | Abrazo mortal provocado y detectado de forma reproducible |
| F9 | Interfaz gráfica, seis vistas, tabla de resultados | Recorrido completo de la demo sobre la interfaz |
| F10 | Empaquetado en ejecutable y en imagen de máquina virtual | Arranque a pantalla completa sin escritorio visible |
| F11 | Demostración preparada y revisión final | Guion de tres minutos ejecutado sin intervención manual |

El sistema es usable desde F7, sin interfaz. Ese es el hito que hay que alcanzar
cuanto antes: a partir de ahí, todo lo demás es mejora, no requisito.
Alcanzado: con la fase 7 se puede abrir una sesión completa (`--shell`) sobre una
imagen de ficheros, sin tocar una línea de interfaz gráfica.

---

## 15. Seguridad y datos sensibles

El repositorio es público. Reglas:

- Ningún token, contraseña ni clave en el árbol del proyecto, ni en un comentario
  de ejemplo, ni en un fichero de prueba.
- Las credenciales, si alguna vez existen, viven fuera del proyecto, en el
  directorio de configuración del usuario, con permisos restringidos.
- El fichero de imagen del disco no se versiona: es estado de ejecución, no
  código, y puede contener datos de prueba.
- Los binarios compilados, las capturas con rutas del equipo y los ficheros de
  volcado no se versionan.
- Los mensajes de error que puedan aparecer en la traza no incluyen rutas
  absolutas del sistema anfitrión.
- La entrega se hace por el chat acordado, no por enlaces públicos.

---

## 16. Registro de decisiones

Alternativas descartadas y por qué. Este registro se mantiene porque en la
defensa la pregunta será siempre "¿por qué así y no de otra forma?".

| Decisión | Elegido | Descartado y motivo |
|---|---|---|
| Sustrato | Java sobre JVM en imagen de máquina virtual | Metal desnudo o kernel en C: incumple el requisito de Java; núcleo en Java sobre hardware: exige escribir antes una máquina virtual |
| Ejecución | Hilos reales con turnos concedidos por el núcleo | Todo simulado: no hay concurrencia que enseñar; todo real con expropiación por interrupción: Java no lo permite de forma segura |
| Memoria | Bloques reales de bytes | Solo mapa de ocupación: un almacenamiento no se podría recuperar |
| Esquemas | Los tres conmutables | Solo paginación: deja fuera segmentación y el problema de huecos |
| Gestor propio | BestFit con castigo de migas y dos parámetros ajustables | Castigo proporcional al hueco mayor: se comprobó que no cambia ninguna elección y se retiró; compactación: obliga a reubicar todos los procesos |
| Planificación | Cuarteto con modo comparación | Solo FCFS y Round Robin: sin sustancia en la tabla; cola multinivel: parámetros difíciles de justificar en el plazo |
| Sistema de ficheros | Imagen propia con tres métodos | Directorio real del anfitrión: el sistema de ficheros no sería nuestro |
| Cerrojos | Cesión directa al primero de la cola | Despertar y volver a competir: deja que un recién llegado adelante al que lleva más tiempo esperando, es decir, provoca inanición |
| Alcance de sincronización | Cerrojos y detección de ciclos | Semáforos, condiciones y monitores: añaden API y un concepto nuevo sin mejorar ninguna demostración; banquero: es prevención y exige declarar máximos antes de ejecutar |
| Salida del interbloqueo | Detectar, informar y cerrar | Matar un hilo o hacerlo retroceder: sin transacciones no se pueden deshacer las escrituras ya hechas en memoria compartida |
| Terminal | Con tuberías, redirección y trabajos | Despachador simple: limita las demostraciones y no se siente una terminal |
| Interfaz | Zonas fijas con vistas y franja de estado | Terminal a pantalla completa con panel oculto: rompe la idea de control desde la terminal |
| Estado inicial | Carga de ejemplo con reinicio | Sesión restaurada: obliga a serializar todo el sistema |

Nota sobre el gestor propio, porque es la decisión que más se va a preguntar. La
primera versión puntuaba `resto + castigo por usar el hueco mayor`. Se descartó
tras comprobar que el castigo es aritméticamente inerte: como el resto de un
hueco crece con su tamaño, un castigo proporcional conserva el orden de BestFit
y nunca cambia la elección, y un castigo fijo solo puede alterar el resultado
cuando el hueco mayor es el único que cabe, en cuyo caso no hay nada que elegir.
El criterio que queda es el que sí se puede medir: no dejar migas, es decir,
restos por debajo del mínimo utilizable. La medición está en
`docs/fase-01-memoria.md` y no favorece al gestor propio en todas las métricas.

---

## 17. Riesgos abiertos

| Riesgo | Control |
|---|---|
| El encuentro de turnos entre núcleo e hilos se bloquea | Toda excepción en un hilo de proceso devuelve el turno; prueba de fallo forzado |
| El determinismo se rompe con varios hilos | La secuencia global de la traza se asigna en el núcleo, no en los hilos; prueba de dos corridas idénticas |
| La interfaz se come el tiempo de los módulos | El sistema es usable desde F7 sin interfaz; la interfaz empieza en F9 |
| El empaquetado en máquina virtual falla al final | El ejecutable es la vía principal; la imagen es la presentación |
| La memoria virtual y el área de intercambio se complican | El área de intercambio vive en el sistema de ficheros propio, no en el anfitrión |
| El banquero no encaja en el bucle de ejecución | Se entrega probado con casos, con la detección de ciclos como demostración principal |

---

## 18. Convenciones

- Comentarios en español, sin emojis, explicando el porqué y no el qué.
- Nombres de clases y métodos en inglés, coherentes con los términos de la
  asignatura: `Pcb`, `PageTable`, `SegmentTable`, `SlotManager`, `Scheduler`.
- Los textos que ve el usuario, en español, salvo los nombres de comando, que
  son los habituales de un sistema operativo.
- Una prueba por regla de negocio, nombrada por la regla y no por el método.
- Ningún mensaje de error sin causa y sin el dato que lo provocó.
