# Fase 3b: paginación por demanda y área de intercambio

La fase 3 dejó este hueco abierto y escrito como tal: todas las páginas de un
proceso estaban cargadas mientras el proceso vivía, no había reemplazo y no
había sitio donde dejar una página que no cupiera. El motivo era de orden, no de
ganas: el área de intercambio tenía que ser un fichero de P-OS y hasta la fase 4
no hubo sistema de ficheros donde ponerlo.

Ya lo hay. Esto cierra el apartado 8.4 de `docs/arquitectura.md`: qué se ha
construido, qué se ha medido y qué sigue sin hacerse.

---

## 1. Qué hay ahora

| Pieza | Clase | Papel |
|---|---|---|
| Espacio por demanda | `pos.mm.DemandPagedSpace` | Tabla de páginas con presencia y suciedad; traduce y falla |
| Área de intercambio | `pos.mm.SwapArea` | Fichero `/intercambio` con cabecera, ranuras y mapa de ocupación |
| Política de reemplazo | `pos.mm.PagePolicy` | Enumeración sellada: FIFO, LRU, OPT |
| Vista para la política | `PagePolicy.Resident` | Lo único que una política puede consultar de la memoria |
| Esquema | `pos.mm.MemoryScheme.DemandPaged` | Cuarto esquema, junto a contiguo, paginado y segmentado |
| Aviso de acceso | `pos.mm.AddressSpace#noteAccess` | `load` y `store` avisan antes de traducir |
| Demostración | `pos.launch.SwapDemo` | `--swap`: las tablas de este documento |

`AddressSpace` ganó dos ganchos, `noteAccess` y `noteWrite`, con cuerpo vacío por
defecto. Los tres esquemas de la fase 3 no cambian ni una línea de
comportamiento: los ignoran. El cuarto los usa para saber qué página se toca y
cuáles están sucias.

---

## 2. Las decisiones

### La política no guarda estado

Una política de reemplazo solo mira la memoria y devuelve qué índice de marco
sale. Todo lo que necesita saber se lo da una vista de solo lectura sobre la
tabla de páginas: cuántas hay, qué página ocupa cada marco, cuándo se cargó,
cuándo se usó por última vez y cuándo se usará la próxima vez.

Así las tres caben en la misma interfaz y la diferencia entre ellas es una
función de tres líneas:

- **FIFO** devuelve el marco con la carga más antigua.
- **LRU** devuelve el marco con el último uso más antiguo.
- **OPT** devuelve el marco cuya página tarda más en volver a usarse, y si alguna
  no vuelve nunca, esa.

La política no decide cuándo se expulsa ni escribe nada. Eso lo hace el espacio,
que es el único que conoce el área de intercambio y la contabilidad.

### OPT es legítimo porque la traza es nuestra

En un sistema real OPT no se puede implementar: exige conocer el futuro. Aquí el
futuro es un dato de entrada, la lista de direcciones que el proceso va a tocar,
y el espacio la consume a medida que avanza.

Eso obliga a una regla: la traza se consume en `load` y `store`, no al traducir.
La primera versión la consumía dentro de `translate`, y entonces cualquier
llamada de diagnóstico a `physicalAddressOf` contaba como un acceso del proceso y
desplazaba el futuro. OPT fallaba por una razón que no tenía nada que ver con la
memoria. Se separó: los dos métodos públicos avisan con `noteAccess` antes de
traducir, y traducir ya no mueve nada.

Si la traza se queda corta, el espacio no adivina: cuenta el desvío
(`traceMisses`) y usa FIFO para esa expulsión. La demostración imprime el
contador y comprueba que es cero, porque con un desvío la comparación con OPT no
vale nada.

### Una página que nunca se escribió son ceros

El sistema no tiene imagen de programa en disco, así que en el primer fallo de
una página no hay nada que leer: se entrega rellena de cero y se cuenta como
`zeroFills`. Un cero relleno es un fallo de página barato, no una lectura, y la
contabilidad los separa para que se vea.

### La cota de marcos es una decisión del proceso, no de la memoria

`DemandPagedSpace` recibe cuántos marcos puede usar. Si la memoria física se
acaba antes que la cota, el espacio no falla: expulsa a la política como si la
cota fuera el número de marcos que le quedan. Con eso la anomalía de Belady se
puede reproducir en una memoria de sobra y no depende de cuánta RAM tenga la
máquina.

### El área de intercambio se reescribe entera

Cada `write` o `discard` reconstruye la imagen completa del fichero y la guarda
con el sistema de ficheros. Es más caro que tocar una ranura, y a propósito: la
imagen en disco siempre es coherente, se puede montar en cualquier momento y no
queda un byte a medias. Para una asignatura el coste no se nota; para un sistema
real habría que escribir solo la ranura, y eso queda dicho aquí.

---

## 3. Lo que se mide

Traza clásica de la anomalía de Belady, doce accesos a cinco páginas con
páginas de 16 unidades, y cada acceso escribiendo en su página (así casi todas
salen sucias y el intercambio trabaja de verdad):

| Política | Marcos | Fallos | Salidas | Entradas | Ceros | En memoria |
|---|---|---|---|---|---|---|
| FIFO | 3 | 9 | 6 | 4 | 5 | 3 |
| LRU | 3 | 10 | 7 | 5 | 5 | 3 |
| OPT | 3 | 7 | 4 | 2 | 5 | 3 |
| FIFO | 4 | 10 | 6 | 5 | 5 | 4 |

- **OPT falla 7 veces con tres marcos, LRU 10.** La diferencia es exactamente el
  valor de saber el futuro, y aquí está medido, no citado.
- **FIFO con cuatro marcos falla 10 y con tres fallaba 9.** Más memoria, más
  fallos: la anomalía de Belady, reproducida con dos ejecuciones del mismo
  código. LRU y OPT no la sufren porque son políticas de pila: con más marcos,
  la memoria grande contiene siempre las páginas de la pequeña.
- **Ceros: 5 de 7 fallos con OPT.** La primera vez que se toca cada una de las
  cinco páginas de la traza no hay nada que leer y entra en cero; los dos fallos
  que sobran son reentradas desde el área de intercambio, esas sí leídas. El
  reparto entre «cero» y «leído» es lo que dice si el intercambio sirvió de algo.

Y la prueba que de verdad importa, un proceso de seis páginas con tres marcos:

```
tras escribir las 6 paginas: fallos 6 de 6 accesos (100%), salidas 3, entradas 0, ceros 6, en memoria 3/6
tras leer al reves:           fallos 9 de 12 accesos (75%), salidas 6, entradas 3, ceros 6, en memoria 3/6
valores recuperados: los 6 correctos (ninguno se invento al volver del disco)
memoria fisica ocupada por el proceso: 48 unidades de 96 logicas
```

El proceso declara 96 unidades y ocupa 48. Las tres páginas que no caben salen
sucias a disco y vuelven con su contenido: al leerlas al revés, los seis valores
están donde se escribieron. El 75 % de fallos es lo esperado con tres marcos para
seis páginas accedidas en orden inverso.

El área de intercambio, al final de la demostración:

```
directorio raiz: intercambio
fichero /intercambio: 588 bytes, 3 de 24 ranuras ocupadas
los primeros bytes: 50 53 57 31 00 00 00 10 00 00 00 18 00 00 00 03 ...
```

`50 53 57 31` es `PSW1`, la magia; `0x10` son 16 unidades por página, `0x18` las
24 ranuras y `0x03` las ocupadas. Detrás van las páginas expulsadas, cada una con
su proceso y su número de página delante. Nada de eso vive en memoria del núcleo:
el fichero se puede montar y leer como cualquier otro, y hay una prueba que lo
hace.

---

## 4. Un número que al principio salió mal

La prueba del área llena esperaba el fallo del sistema una página antes de donde
ocurre. Con un marco y una ranura, la primera página sucia que sale todavía cabe;
la segunda no. Es decir: el área de intercambio no se llena al ritmo de páginas
tocadas, sino al ritmo de páginas **sucias expulsadas**, y va siempre una por
detrás de los marcos.

Cuando el área está llena, el fallo es del sistema y se levanta `PosFault`. No se
expulsa en silencio una página que se perdería: la escritura se intenta antes de
tocar la tabla, así que si no cabe, el proceso se queda con su página y con el
error. Es el comportamiento correcto y costó una prueba fallida darse cuenta.

---

## 5. Lo que esta fase no hace

- **No hay segmentación paginada.** Los segmentos siguen siendo tramos contiguos.
  Paginar dentro de cada segmento es lo que haría un sistema real y lo que más
  código añade; sigue siendo una extensión documentada, no una deuda oculta.
- **No hay permisos por página.** Una página no distingue lectura de escritura.
  Va con los cerrojos de la fase 5, que es donde la memoria de solo lectura tiene
  algo que proteger.
- **No hay reemplazo global entre procesos.** Cada proceso expulsa de sus propios
  marcos. Un sistema real puede quitarle un marco a otro proceso; aquí la cota es
  por proceso y la memoria no se reparte en caliente.
- **No hay imagen de programa.** Todas las páginas nacen a cero. Cargar un
  ejecutable desde disco sería el siguiente paso y no está.

---

## 6. Pruebas

`pos.mm.DemandPagingTest`, 18 pruebas nuevas:

| Prueba | Qué fija |
|---|---|
| `fallosEsperados` | FIFO 9, LRU 10, OPT 7 con tres marcos |
| `anomaliaDeBelady` | FIFO con cuatro marcos falla más que con tres |
| `sinAnomalia` | LRU y OPT no empeoran al crecer |
| `optSinDesvios` | OPT no registra desvíos de traza |
| `optNecesitaTraza` | OPT sin traza, sin marcos o con traza vacía no se construye |
| `desvioSeCuenta` | una dirección fuera de la traza se cuenta, no revienta |
| `vuelveIntacta` | una página sucia expulsada vuelve con su contenido |
| `paginaLimpiaNoSaleADisco` | lo que solo se leyó no se escribe en el intercambio |
| `areaLlena` | sin ranuras libres hay `PosFault`, no pérdida silenciosa |
| `paginaQueNoCuadra` | el tamaño de página del área tiene que ser el del esquema |
| `cabecera` | magia, medidas y tamaño del fichero |
| `sobreviveAlMontaje` | guardar la imagen y volver a montarla conserva las páginas |
| `reapertura` | reabrir el área no inventa ranuras ocupadas |
| `noEsArea` | un fichero cualquiera no se abre como área |
| `expulsaEnVezDeFallar` | sin marcos libres se expulsa, no se falla |
| `proteccion` | fuera del espacio lógico se falla antes de tocar la política |
| `liberar` | al terminar se devuelven todos los marcos |
| `cotaMayorQuePaginas` | una cota mayor que las páginas no reserva de más |

Total del proyecto: **156 pruebas en verde**.

---

## 7. Siguiente fase

La fase 5 del documento de arquitectura es la concurrencia: cerrojos, espera y
memoria de solo lectura. Esa fase es la que convierte los permisos por página y
por segmento en algo que se puede comprobar.
