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
- Terminal con argumentos, redirección, tuberías, historial y autocompletado.
- Procesos con PCB, hilos reales dentro del proceso y ejecución verificable.
- Cuatro planificadores y modo de comparación sobre una misma carga.
- Memoria real por bloques con traducción de direcciones comprobable.
- Tres esquemas de direccionamiento conmutables en caliente.
- Memoria virtual con fallo de página y reemplazo FIFO, LRU y OPT.
- Sistema de ficheros propio sobre imagen única, con tres métodos de asignación.
- Sincronización con cerrojos, semáforos, variables de condición y monitores.
- Interbloqueos: grafo de asignación, detección de ciclos y algoritmo del banquero.
- Interfaz gráfica con cinco vistas y tabla de resultados comparativa.
- Empaquetado en ejecutable y en imagen de máquina virtual.

### Fuera del alcance, declarado explícitamente

- Arranque en hardware desnudo. No lo hay: por debajo se ejecuta una máquina
  virtual de Java. El sistema arranca en una máquina virtual, no en un
  procesador. Esta frase debe aparecer así en toda la documentación.
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
JavaFX. Si algún día aparece una importación de JavaFX fuera del paquete de
interfaz, es un error de arquitectura, no un atajo.

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
| 3. Estrategias | `pos.alloc`, `pos.addr`, `pos.sched`, `pos.disk` | Gestores de huecos, traductores, planificadores, asignación de disco |
| 4. Ejecución | `pos.proc` | PCB, hilos, repertorio de instrucciones, intérprete |
| 5. Tareas del sistema | `pos.fs`, `pos.sync` | Sistema de ficheros e imagen, cerrojos, semáforos, monitores, grafo de recursos |
| 6. Núcleo | `pos.kernel` | Orquestación: arranque, bucle de planificación, despacho de llamadas, métricas |
| 7. Terminal | `pos.shell` | Analizador de línea, despachador, comandos, tuberías, historial |
| 8. Interfaz | `pos.ui` | Vistas, modelos observables, estilos |
| 9. Arranque | `pos.launch` | Modo consola, modo gráfico, autodiagnóstico |

La capa 8 es la única que importa JavaFX. La capa 9 es la única que decide qué
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

Cerrojos con dueño, semáforos contadores, variables de condición asociadas a un
cerrojo, y monitores construidos sobre las dos anteriores. Cada mecanismo tiene
su cola de bloqueados y su representación en la interfaz.

Los bloqueos son bloqueos reales de hilos de Java, pero el desbloqueo lo
autoriza el núcleo dentro de su bucle, de modo que el orden sigue siendo
determinista.

### 10.2 Interbloqueos

Grafo dirigido con dos tipos de arista: petición y asignación. La detección de
ciclos se ejecuta a demanda, con un comando, y también automáticamente cuando un
hilo lleva bloqueado más de un umbral configurable.

Algoritmo del banquero: cada proceso declara sus máximos al crearse. El banquero
evalúa si un estado es seguro antes de conceder. Su resultado se publica como
secuencia segura o como negativa con el proceso que la provoca.

La demostración preparada es un abrazo mortal entre dos hilos que toman dos
cerrojos en orden inverso. Debe ser reproducible con un solo comando.

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

Historial navegable y autocompletado sobre nombres de comando y rutas del
sistema de ficheros propio.

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

---

## 13. Interfaz

### 13.1 Estructura

Una ventana. Franja de estado siempre visible, con uso de CPU, ocupación de
memoria, número de procesos e hilos, planificador activo y reloj del sistema.
Debajo, terminal y panel con cinco vistas conmutables: Procesos, Memoria,
Planificación, Ficheros, y Sincronización e interbloqueo.

Regla de navegación: cambiar de vista nunca interrumpe la escritura en la
terminal ni mueve el cursor.

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
| F3 | Traductores contiguo, paginado y segmentado | Almacenar y leer el mismo valor bajo los tres esquemas |
| F4 | Memoria virtual, fallo de página, FIFO, LRU, OPT | Contador de fallos correcto y anomalía de Belady reproducida |
| F5 | Sistema de ficheros e imagen, tres métodos de asignación | Persistencia entre sesiones y verificación de integridad |
| F6 | Núcleo completo, métricas, traza, comparación de algoritmos | Misma carga con cuatro planificadores y tabla correcta |
| F7 | Terminal, comandos por familias, redirección y tuberías | Guion de comandos completo sin interfaz gráfica |
| F8 | Sincronización, grafo de recursos, banquero | Abrazo mortal provocado y detectado de forma reproducible |
| F9 | Interfaz gráfica, cinco vistas, tabla de resultados | Recorrido completo de la demo sobre la interfaz |
| F10 | Empaquetado en ejecutable y en imagen de máquina virtual | Arranque a pantalla completa sin escritorio visible |
| F11 | Demostración preparada y revisión final | Guion de tres minutos ejecutado sin intervención manual |

El sistema es usable desde F7, sin interfaz. Ese es el hito que hay que alcanzar
cuanto antes: a partir de ahí, todo lo demás es mejora, no requisito.

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
