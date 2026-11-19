# P-OS

Sistema operativo didáctico escrito en Java, para la asignatura de Sistemas
Operativos. No simula desde fuera: mantiene memoria real por bloques, procesos e
hilos reales sobre hilos de la máquina virtual, un sistema de ficheros propio y
una terminal que es un proceso más del sistema.

## Estado

| Fase | Contenido | Estado |
|---|---|---|
| 0 | Entorno de compilación y esqueleto del proyecto | terminada |
| 1 | Memoria física por bloques y gestores de huecos | terminada |
| 2 | Procesos, hilos, intérprete y planificación FCFS / Round Robin | terminada |

Las fases restantes están descritas en `docs/arquitectura.md`.

## Cómo compilar y ejecutar

Requiere Java 21 y Maven. En este equipo ya están instalados y se cargan con:

```bash
. ~/.local/env-pos.sh
```

Después:

```bash
mvn test                     # pruebas; imprime las tablas comparativas
mvn package                  # genera el ejecutable
java -jar target/p-os-0.1.0-SNAPSHOT.jar
```

## Documentación

- `docs/arquitectura.md`: documento de arquitectura, con las decisiones y su
  motivo, el modelo de ejecución y las fases.
- `docs/fase-01-memoria.md`: qué se implementó en la primera fase, con las
  mediciones de los gestores de huecos.
- `docs/fase-02-procesos.md`: qué se implementó en la segunda, el modelo de
  ejecución y la comparación de planificadores.

## Convenciones

Comentarios en español y sin emojis. Nombres de clases y métodos en inglés, con
los términos de la asignatura. Los textos que ve el usuario, en español, salvo
los nombres de comando.
