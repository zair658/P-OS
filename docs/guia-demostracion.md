# Guía de demostración

Guion para enseñar P-OS en unos cinco minutos, en el orden en que las preguntas
aparecen solas. Cada paso dice qué ejecutar, qué mirar y qué frase lleva la idea.
No hace falta explicar cómo está hecho por dentro: el sistema lo va enseñando.

Antes de empezar, preparar el terminal con el jar ya construido:

```bash
mvn -q package
JAR=target/p-os-0.1.0-SNAPSHOT.jar
```

Si la demo es en un equipo sin pantalla, la ventana se sustituye por las capturas
del paso 1 y el resto del guion no cambia.

---

## 1. La ventana (45 s)

```bash
java -jar $JAR --ui
```

Se abre en la pestaña **Resumen**. Tres cosas a señalar, en este orden:

1. **El reloj de arriba a la derecha sube.** El sistema está corriendo de verdad,
   no es una imagen.
2. **La franja de procesador.** Un solo hilo en verde a la vez. Eso es el
   planificador decidiendo a quién le toca.
3. **El mapa de memoria, con casillas de colores.** Las oscuras son huecos
   libres, y no están juntas: la memoria libre queda repartida en trozos.

> «Un proceso no corre porque sí: corre cuando el núcleo le deja. Los hilos son
> reales, pero el procesador es explícito y hay un único punto donde se decide.»

Cambiar a **Memoria** y a **Procesos** con las pestañas: cada una contesta una
pregunta distinta sobre la misma foto.

## 2. Los tres esquemas de memoria (60 s)

```bash
java -jar $JAR --memoria
```

El mismo programa con memoria contigua, paginada y segmentada, cada uno con su
tabla de traducción, y luego la prueba de fragmentación.

> «El contiguo es el más rápido de traducir y el que peor aprovecha: hay que
> meter el proceso entero en un hueco. El paginado acaba con el hueco grande y
> paga una tabla por proceso.»

Señalar la fila donde el contiguo rechaza y el paginado admite: es la misma
memoria y la misma petición.

## 3. Intercambio (45 s)

```bash
java -jar $JAR --swap
```

FIFO, LRU y OPT sobre la misma traza, con los fallos de página al lado; la
anomalía de Belady reproducida, y un proceso con más páginas que marcos que
recupera sus datos del área de intercambio.

> «Más marcos no siempre es menos fallos: con FIFO hay trazas en las que al
> aumentar marcos aumentan los fallos. Aquí está pasando.»

## 4. Concurrencia de verdad (60 s)

```bash
java -jar $JAR --concurrencia
```

Primero la carrera: dos hilos suman sobre el mismo contador y se pierden
actualizaciones. Después la misma suma con cerrojo, que da el resultado correcto,
y por último un abrazo mortal que el sistema detecta en vez de colgarse.

> «Esto no está simulado: son dos hilos de la JVM peleándose por una variable.
> La pérdida de actualizaciones es real, y por eso el cerrojo arregla algo que
> estaba roto de verdad.»

## 5. Coste medido (45 s)

```bash
java -jar $JAR --medicion
```

La misma tarea resuelta con cerrojo, semáforo y monitor, cronometrada en ticks,
con la columna de resultado al lado.

> «La tabla no dice cuál es mejor: dice cuánto cuesta cada uno y si el resultado
> es correcto. Lo más barato que da mal el resultado no es una opción.»

Si hay prisa, `--medicion --csv` y la tabla se abre en una hoja de cálculo.

## 6. La terminal es un proceso más (60 s)

```bash
java -jar $JAR --shell
```

Cuatro bloques: una sesión con tuberías y redirecciones, una sesión de control
de trabajos, la terminal compitiendo con dos procesos de cálculo, y la
contabilidad de llamadas que atendió el núcleo.

En el bloque de trabajos se ve arrancar un bucle en segundo plano (`&`), pararlo
(`stop %1`), mirar la tabla (`jobs`), devolverlo al fondo (`bg %1`) y matarlo
(`kill %1`). Mientras está parado, `ps` lo enseña como **detenido**: sigue
existiendo, conserva su memoria y no consume turno. La traza del núcleo lo
confirma con un `STOP` y un `CONT`.

Señalar en el segundo bloque que **los contadores avanzan mientras la terminal
espera**: no tiene turno preferente. Y en el tercero, que cada orden es una suma
de llamadas al sistema con su coste en tics.

> «La terminal no es la interfaz del simulador: es un proceso. Si tuviera
> privilegios de planificación, los contadores no avanzarían mientras espera
> teclas. Avanzan.»

> «Y el trabajo detenido no está muerto: está fuera de la cola de listos, con su
> memoria puesta. Eso es lo que hace `stop` en el núcleo: sacarlo de la cola, no
> matarlo.»

## 7. Cierre (30 s)

```bash
mvn test
```

Al final de la salida: las pruebas en verde y las tablas comparativas que alimentan
los documentos.

> «Las cifras de los documentos salen de estas ejecuciones, no están escritas a
> mano. Si un cambio rompe una medición, la prueba lo dice.»

---

## Si sobra tiempo

- **Sistema de ficheros:** `--disco` enseña los tres métodos de asignación sobre
  el mismo contenido y una imagen estropeada que se niega a montar. La negativa a
  montar es la mitad del mensaje: un sistema de ficheros que acepta basura no
  sirve.
- **Sincronización:** `--sincronizacion` con el productor y el consumidor que se
  dan el turno sin gastar procesador, y el monitor donde se ve que el despertado
  tiene que volver a pedir el cerrojo.
- **Sin pantalla:** `--captura carpeta` genera las ocho vistas como PNG.

## Las preguntas que van a caer

**¿Es un sistema operativo de verdad?** No, y el nombre lo dice: es didáctico. Lo
que sí es real es el estado: memoria por bloques, procesos con su bloque de
control, hilos sobre hilos de la JVM, ficheros en una imagen propia. Lo que no
hay es hardware debajo.

**¿Por qué Java?** Porque el objetivo es ver los mecanismos, y la JVM da hilos de
verdad, concurrencia de verdad y recolección de basura que no estorba. El precio
es que no hay control de memoria física directa, y se asume.

**¿Cómo se para un proceso sin señales?** Con una orden explícita, `stop`, que
es una llamada al sistema. Un terminal de verdad para con Ctrl-Z porque hay
señales; aquí la entrada es un guion, así que la orden hace de señal. `fg` espera
al proceso en vez de recibir un aviso asíncrono: por eso no hacen falta
manejadores.

**¿Qué se descartó?** Está escrito en `docs/arquitectura.md`, con el motivo. El
caso que más se pregunta es el gestor de huecos propio: el castigo proporcional
al hueco mayor no cambiaba ninguna elección y se retiró, en vez de dejarlo para
lucir en la tabla donde ganara.
