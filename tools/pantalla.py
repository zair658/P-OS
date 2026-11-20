#!/usr/bin/env python3
"""Dibuja en texto la pantalla de una sesion interactiva de P-OS.

No mira el flujo de bytes: lo mete en un emulador de terminal (pyte) y saca la
rejilla que veria una persona. Es la unica forma de saber si el repintado de la
linea deja la pantalla como debe, porque los bytes correctos pueden dibujar mal
(saltos de linea, desbordes, restos de la linea anterior).

Uso:
    pantalla.py 80 24 "echo hola\\r" "\\x1b[A"

Cada argumento es un paso: se escribe tal cual (con escapes de Python) y despues
se imprime la pantalla resultante.

Pide dos cosas: `target/classes` compilado y la libreria `pyte` (un emulador de
terminal en Python). Con `--interactivo` el ancho se lee con `stty size`, asi
que lo que se le pasa aqui es el tamano que vera la sesion.
"""

import fcntl
import os
import pty
import select
import struct
import sys
import termios
import time

import pyte

RAIZ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
JAVA = ["java", "-cp", os.path.join(RAIZ, "target", "classes"),
        "pos.launch.Main", "--interactivo"]


def arrancar(cols, filas):
    maestro, esclavo = pty.openpty()
    fcntl.ioctl(esclavo, termios.TIOCSWINSZ, struct.pack("HHHH", filas, cols, 0, 0))
    pid = os.fork()
    if pid == 0:
        os.setsid()
        os.close(maestro)
        os.dup2(esclavo, 0)
        os.dup2(esclavo, 1)
        os.dup2(esclavo, 2)
        if esclavo > 2:
            os.close(esclavo)
        os.execvp(JAVA[0], JAVA)
    os.close(esclavo)
    return pid, maestro


def beber(maestro, datos, espera=0.25):
    """Lee lo que haya pendiente y lo acumula."""
    while True:
        listos, _, _ = select.select([maestro], [], [], espera)
        if not listos:
            return datos
        try:
            trozo = os.read(maestro, 8192)
        except OSError:
            return datos
        if not trozo:
            return datos
        datos += trozo


def main():
    cols, filas = int(sys.argv[1]), int(sys.argv[2])
    pasos = sys.argv[3:]
    pid, maestro = arrancar(cols, filas)
    datos = beber(maestro, b"", 0.8)

    for i, paso in enumerate(pasos, 1):
        os.write(maestro, paso.encode("utf-8").decode("unicode_escape").encode("latin-1"))
        datos = beber(maestro, datos)

        pantalla = pyte.Screen(cols, filas)
        flujo = pyte.ByteStream(pantalla)
        flujo.feed(datos)
        print("=" * cols)
        print(f"paso {i}: {paso!r}   cursor en fila {pantalla.cursor.y}, columna {pantalla.cursor.x}")
        print("=" * cols)
        for fila in pantalla.display:
            print("|" + fila + "|")

    os.write(maestro, b"\x04")
    time.sleep(0.3)
    os.close(maestro)
    os.waitpid(pid, 0)


if __name__ == "__main__":
    main()
