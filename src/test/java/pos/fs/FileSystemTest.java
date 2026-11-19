package pos.fs;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import pos.alloc.BestFit;
import pos.base.PosFault;

@DisplayName("El sistema de ficheros guarda, recupera y verifica lo que promete")
class FileSystemTest {

    private static final int BLOQUE = 64;
    private static final int BLOQUES = 64;

    /** Imagen de 64 bloques con la tabla de FCB por defecto. */
    private static FileSystem nuevo(AllocationMethod method) {
        return FileSystem.format(FsLayout.standard(BLOQUES, BLOQUE), method, new BestFit());
    }

    /** Imagen pequena: cuatro bloques de datos y dos FCB. */
    private static FileSystem pequeno(AllocationMethod method) {
        return FileSystem.format(new FsLayout(20, BLOQUE, 2), method, new BestFit());
    }

    private static byte[] relleno(int length, int semilla) {
        byte[] data = new byte[length];
        new Random(semilla).nextBytes(data);
        return data;
    }

    /** Copia del mapa de bits, para probar metodos sobre el mismo escenario. */
    private static FreeBlockMap copia(FreeBlockMap map) {
        return FreeBlockMap.fromBytes(map.toBytes(), map.blockCount());
    }

    @Nested
    @DisplayName("Ficheros")
    class Ficheros {

        @Test
        @DisplayName("un fichero de un byte ocupa un bloque y se lee igual")
        void ficheroMinimo() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.create("/uno", new byte[] { 7 });

            assertArrayEquals(new byte[] { 7 }, fs.read("/uno"));
            assertEquals(1, fs.resolve("/uno").allocation().dataBlocks());
            assertTrue(fs.verify().ok(), () -> String.join("\n", fs.verify().problems()));
        }

        @Test
        @DisplayName("un fichero de varios bloques vuelve byte a byte")
        void ficheroGrande() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            byte[] data = relleno(500, 1);
            fs.create("/datos", data);

            // 500 bytes con bloques de 64: ocho bloques, el ultimo a medias.
            assertEquals(8, fs.resolve("/datos").allocation().dataBlocks());
            assertArrayEquals(data, fs.read("/datos"));
            assertTrue(fs.verify().ok());
        }

        @Test
        @DisplayName("escribir mas de lo que cabe recoloca y conserva el contenido")
        void crecerRecoloca() {
            FileSystem fs = nuevo(new AllocationMethod.Contiguous());
            fs.create("/log", "corto");
            assertTrue(fs.verify().ok());

            fs.write("/log", relleno(300, 2));
            assertArrayEquals(relleno(300, 2), fs.read("/log"));
            assertEquals(5, fs.resolve("/log").allocation().dataBlocks());
            assertTrue(fs.verify().ok(), () -> String.join("\n", fs.verify().problems()));
        }

        @Test
        @DisplayName("anadir al final mantiene lo anterior")
        void anadir() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.create("/nota", "uno");
            fs.append("/nota", " dos");
            fs.append("/nota", " tres");

            assertEquals("uno dos tres", fs.readText("/nota"));
            assertTrue(fs.verify().ok());
        }

        @Test
        @DisplayName("borrar devuelve todos los bloques al mapa")
        void borrarLibera() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            int libresAlPrincipio = fs.freeMap().freeCount();
            fs.create("/temporal", relleno(200, 3));
            assertTrue(fs.freeMap().freeCount() < libresAlPrincipio);

            fs.remove("/temporal");
            assertEquals(libresAlPrincipio, fs.freeMap().freeCount());
            assertTrue(fs.verify().ok());
        }

        @Test
        @DisplayName("leer un fichero que no existe es un fallo explicado")
        void leerInexistente() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            PosFault fault = assertThrows(PosFault.class, () -> fs.read("/fantasma"));
            assertTrue(fault.getMessage().contains("no existe"), fault.getMessage());
        }

        @Test
        @DisplayName("un nombre de mas de doce caracteres no entra")
        void nombreLargo() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            PosFault fault = assertThrows(PosFault.class,
                    () -> fs.create("/nombredemasiadolargo", "x"));
            assertTrue(fault.getMessage().contains("12"), fault.getMessage());
        }

        @Test
        @DisplayName("la tabla de FCB llena se avisa en vez de romperse")
        void tablaLlena() {
            FileSystem fs = pequeno(new AllocationMethod.Indexed());
            fs.create("/a", "1");
            PosFault fault = assertThrows(PosFault.class, () -> fs.create("/b", "2"));
            assertTrue(fault.getMessage().contains("llena"), fault.getMessage());
        }
    }

    @Nested
    @DisplayName("Directorios y rutas")
    class Directorios {

        @Test
        @DisplayName("un directorio guarda ficheros y los lista ordenados")
        void listar() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.mkdir("/etc");
            fs.create("/etc/zeta", "z");
            fs.create("/etc/alfa", "a");

            List<String> nombres = fs.list("/etc").stream().map(Directory.Entry::name).toList();
            assertEquals(List.of("alfa", "zeta"), nombres);
            assertEquals("a", fs.readText("/etc/alfa"));
            assertTrue(fs.verify().ok(), () -> String.join("\n", fs.verify().problems()));
        }

        @Test
        @DisplayName("las rutas relativas se resuelven contra el directorio actual")
        void rutasRelativas() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.mkdir("/home");
            fs.chdir("/home");
            fs.create("carta", "hola");

            assertEquals("/home/carta", fs.normalize("carta"));
            assertEquals("/home/carta", fs.normalize("./carta"));
            assertEquals("/carta", fs.normalize("../carta"));
            assertEquals("/home/carta", fs.normalize("/home/./carta"));
            assertEquals("hola", fs.readText("/home/carta"));
        }

        @Test
        @DisplayName("un directorio con contenido no se borra")
        void directorioNoVacio() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.mkdir("/caja");
            fs.create("/caja/dentro", "x");

            PosFault fault = assertThrows(PosFault.class, () -> fs.remove("/caja"));
            assertTrue(fault.getMessage().contains("no esta vacio"), fault.getMessage());

            fs.remove("/caja/dentro");
            fs.remove("/caja");
            assertTrue(fs.verify().ok());
        }

        @Test
        @DisplayName("no se puede crear un fichero dentro de un fichero")
        void padreNoDirectorio() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.create("/plano", "x");
            assertThrows(PosFault.class, () -> fs.create("/plano/dentro", "y"));
        }
    }

    @Nested
    @DisplayName("Los tres metodos de asignacion")
    class Metodos {

        /** El mismo juego de ficheros en los tres metodos. */
        private Map<String, byte[]> mismoJuego(AllocationMethod method) {
            FileSystem fs = nuevo(method);
            Map<String, byte[]> contenidos = new TreeMap<>();
            fs.mkdir("/datos");
            for (int i = 0; i < 6; i++) {
                byte[] data = relleno(30 + i * 47, 10 + i);
                String path = "/datos/f" + i;
                fs.create(path, data);
                contenidos.put(path, data);
            }
            assertTrue(fs.verify().ok(), () -> method.name() + ": "
                    + String.join("\n", fs.verify().problems()));
            for (Map.Entry<String, byte[]> entry : contenidos.entrySet()) {
                assertArrayEquals(entry.getValue(), fs.read(entry.getKey()),
                        () -> method.name() + " en " + entry.getKey());
            }
            return contenidos;
        }

        @Test
        @DisplayName("contiguo, enlazado e indexado guardan el mismo contenido")
        void mismoContenido() {
            Map<String, byte[]> contiguo = mismoJuego(new AllocationMethod.Contiguous());
            Map<String, byte[]> enlazado = mismoJuego(new AllocationMethod.Linked());
            Map<String, byte[]> indexado = mismoJuego(new AllocationMethod.Indexed());

            assertEquals(contiguo.keySet(), enlazado.keySet());
            assertEquals(contiguo.keySet(), indexado.keySet());
            for (String path : contiguo.keySet()) {
                assertArrayEquals(contiguo.get(path), enlazado.get(path), path);
                assertArrayEquals(contiguo.get(path), indexado.get(path), path);
            }
        }

        @Test
        @DisplayName("acceder al bloque cien cuesta cero, cien y una lectura segun el metodo")
        void costeDeAcceso() {
            int logico = 100;
            // Bloques de 512 bytes: el indice apunta 128 bloques y llega al 101.
            int bloque = 512;
            byte[] data = relleno(logico * bloque + 10, 20);

            assertEquals(0, lecturasHasta(new AllocationMethod.Contiguous(), data, logico, bloque));
            assertEquals(logico, lecturasHasta(new AllocationMethod.Linked(), data, logico, bloque));
            assertEquals(1, lecturasHasta(new AllocationMethod.Indexed(), data, logico, bloque));
        }

        private long lecturasHasta(AllocationMethod method, byte[] data, int logico, int bloque) {
            DiskImage image = new DiskImage(400, bloque);
            FreeBlockMap map = new FreeBlockMap(400);
            map.markUsed(0, 4);
            Allocation allocation = method.allocate(image, map, new BestFit(),
                    method.dataBlocksFor(data.length, bloque));
            // Se escribe el contenido sin contar, y se mide solo el acceso.
            for (int i = 0; i < allocation.dataBlocks(); i++) {
                int from = Math.min(i * method.capacityPerBlock(bloque), data.length);
                int to = Math.min(from + method.capacityPerBlock(bloque), data.length);
                method.storeBlock(image, allocation, i,
                        java.util.Arrays.copyOfRange(data, from, to));
            }
            image.resetCounters();
            int block = method.physicalBlock(image, allocation, logico);
            assertTrue(block > 0);
            return image.blockReads();
        }

        @Test
        @DisplayName("el contiguo falla cuando no hay un hueco del tamano pedido")
        void contiguoFragmentado() {
            DiskImage image = new DiskImage(20, BLOQUE);
            FreeBlockMap map = new FreeBlockMap(20);
            map.markUsed(0, 4);
            map.markUsed(4, 4);
            map.markUsed(10, 2);
            map.markUsed(14, 5);

            // Libres: 8, 9, 12, 13 y 19. Hay cinco bloques, pero ninguno de tres seguidos.
            AllocationMethod contiguo = new AllocationMethod.Contiguous();
            PosFault fault = assertThrows(PosFault.class,
                    () -> contiguo.allocate(image, map, new BestFit(), 3));
            assertTrue(fault.getMessage().contains("contiguo"), fault.getMessage());

            // Los otros dos metodos usan el mismo mapa, pero cada uno necesita el suyo:
            // el primero que asigna se queda los huecos.
            assertTrue(new AllocationMethod.Linked()
                    .allocate(image, copia(map), new BestFit(), 3) instanceof Allocation.Chained);
            assertTrue(new AllocationMethod.Indexed()
                    .allocate(image, copia(map), new BestFit(), 3) instanceof Allocation.Indexed);
        }

        @Test
        @DisplayName("en el enlazado cada bloque pierde cuatro bytes en el puntero")
        void capacidadEnlazada() {
            assertEquals(BLOQUE, new AllocationMethod.Contiguous().capacityPerBlock(BLOQUE));
            assertEquals(BLOQUE - 4, new AllocationMethod.Linked().capacityPerBlock(BLOQUE));
            assertEquals(BLOQUE, new AllocationMethod.Indexed().capacityPerBlock(BLOQUE));

            // 64 bytes caben en un bloque contiguo y piden dos en el enlazado.
            assertEquals(1, new AllocationMethod.Contiguous().dataBlocksFor(64, BLOQUE));
            assertEquals(2, new AllocationMethod.Linked().dataBlocksFor(64, BLOQUE));
            // El indexado gasta un bloque de indices por fichero.
            assertEquals(2, new AllocationMethod.Indexed().totalBlocksFor(64, BLOQUE));
            assertEquals(1, new AllocationMethod.Contiguous().totalBlocksFor(64, BLOQUE));
        }

        @Test
        @DisplayName("el codigo del metodo viaja en el superbloque")
        void codigos() {
            assertEquals(0, new AllocationMethod.Contiguous().code());
            assertEquals(1, new AllocationMethod.Linked().code());
            assertEquals(2, new AllocationMethod.Indexed().code());
            assertTrue(AllocationMethod.of(1) instanceof AllocationMethod.Linked);
            assertThrows(PosFault.class, () -> AllocationMethod.of(9));
        }
    }

    @Nested
    @DisplayName("Cambio de metodo en caliente")
    class Conversion {

        @Test
        @DisplayName("convertir a contiguo conserva todos los ficheros")
        void aContiguo() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.mkdir("/etc");
            Map<String, byte[]> antes = new TreeMap<>();
            for (int i = 0; i < 4; i++) {
                byte[] data = relleno(40 + i * 60, 30 + i);
                String path = "/etc/conf" + i;
                fs.create(path, data);
                antes.put(path, data);
            }

            FileSystem.Conversion conversion = fs.switchMethod(new AllocationMethod.Contiguous());

            assertEquals("indexada", conversion.from());
            assertEquals("contiguo", conversion.to());
            // Los cuatro ficheros y los dos directorios con FCB: raiz y /etc.
            assertEquals(6, conversion.files());
            assertEquals("contiguo", fs.method().name());
            for (Map.Entry<String, byte[]> entry : antes.entrySet()) {
                assertArrayEquals(entry.getValue(), fs.read(entry.getKey()), entry.getKey());
            }
            assertTrue(fs.verify().ok(), () -> String.join("\n", fs.verify().problems()));
        }

        @Test
        @DisplayName("convertir a enlazado cambia la colocacion y no el contenido")
        void aEnlazado() {
            FileSystem fs = nuevo(new AllocationMethod.Contiguous());
            fs.create("/a", relleno(250, 40));
            int bloquesAntes = fs.resolve("/a").allocation().dataBlocks();

            fs.switchMethod(new AllocationMethod.Linked());

            // El puntero de cuatro bytes hace que el mismo contenido pida mas bloques:
            // 250 bytes son cuatro bloques en contiguo y cinco en enlazado.
            assertEquals(4, bloquesAntes);
            assertEquals(5, fs.resolve("/a").allocation().dataBlocks());
            assertArrayEquals(relleno(250, 40), fs.read("/a"));
            assertTrue(fs.verify().ok());
        }

        @Test
        @DisplayName("convertir al mismo metodo no toca la imagen")
        void mismoMetodo() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.create("/a", "x");
            long escrituras = fs.image().blockWrites();

            FileSystem.Conversion conversion = fs.switchMethod(new AllocationMethod.Indexed());

            assertEquals(0, conversion.files());
            assertEquals("indexada", conversion.from());
            assertEquals(escrituras, fs.image().blockWrites());
        }
    }

    @Nested
    @DisplayName("Persistencia y montaje")
    class Montaje {

        @Test
        @DisplayName("guardar y montar devuelve el mismo arbol")
        void idaYVuelta() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.mkdir("/home");
            fs.create("/home/nota", "texto de la nota");
            fs.create("/grande", relleno(700, 50));

            byte[] raw = fs.save();
            FileSystem montado = FileSystem.mount(raw);

            assertEquals("texto de la nota", montado.readText("/home/nota"));
            assertArrayEquals(relleno(700, 50), montado.read("/grande"));
            assertEquals(List.of("nota"), montado.list("/home").stream()
                    .map(Directory.Entry::name).toList());
            assertTrue(montado.verify().ok(), () -> String.join("\n", montado.verify().problems()));
        }

        @Test
        @DisplayName("el mapa de libres sobrevive al montaje")
        void mapaPersistente() {
            FileSystem fs = nuevo(new AllocationMethod.Contiguous());
            fs.create("/a", relleno(100, 60));
            int libres = fs.freeMap().freeCount();

            FileSystem montado = FileSystem.mount(fs.save());
            assertEquals(libres, montado.freeMap().freeCount());
            assertEquals(fs.method().name(), montado.method().name());
        }

        @Test
        @DisplayName("un byte cambiado en el mapa de bits se detecta al montar")
        void mapaCorrupto() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.create("/a", "x");
            byte[] raw = fs.save();

            // El bloque 1 es el mapa de bits: cambiar un bit de un bloque de datos.
            raw[BLOQUE + 3] ^= 0x10;

            PosFault fault = assertThrows(PosFault.class, () -> FileSystem.mount(raw));
            assertTrue(fault.getMessage().contains("no monta"), fault.getMessage());
        }

        @Test
        @DisplayName("un byte cambiado en un FCB se detecta por la suma de comprobacion")
        void fcbCorrupto() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.create("/a", "x");
            byte[] raw = fs.save();

            // Los FCB empiezan en el bloque 2; el byte 6 es el primer caracter del nombre.
            int fcbBlock = fs.layout().fcbFirst();
            raw[fcbBlock * BLOQUE + 6] = (byte) 'Z';

            PosFault fault = assertThrows(PosFault.class, () -> FileSystem.mount(raw));
            assertTrue(fault.getMessage().contains("corrupto"), fault.getMessage());
        }

        @Test
        @DisplayName("una imagen que no es nuestra no monta")
        void marcaAjena() {
            byte[] raw = new byte[BLOQUE * 20];
            PosFault fault = assertThrows(PosFault.class, () -> FileSystem.mount(raw));
            assertTrue(fault.getMessage().contains("no es de este sistema"), fault.getMessage());
        }

        @Test
        @DisplayName("un bloque ocupado sin dueno aparece en la verificacion")
        void bloqueFantasma() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.create("/a", relleno(100, 70));
            // Se ocupa un bloque de datos a mano: nadie lo reclama.
            int victima = fs.layout().dataFirst() + 30;
            fs.freeMap().markUsed(victima);

            FsCheck check = fs.verify();
            assertFalse(check.ok());
            assertTrue(check.problems().stream().anyMatch(p -> p.contains("nadie") || p.contains("ningun FCB")),
                    () -> String.join("\n", check.problems()));
        }
    }

    @Nested
    @DisplayName("Invariantes de la imagen")
    class Invariantes {

        @Test
        @DisplayName("el superbloque cuenta los mismos libres que el mapa")
        void contadorLibres() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.create("/a", relleno(300, 80));
            fs.create("/b", "b");
            fs.remove("/a");

            assertEquals(fs.freeMap().freeCount(), fs.superblock().freeBlocks());
            assertTrue(fs.verify().ok(), () -> String.join("\n", fs.verify().problems()));
        }

        @Test
        @DisplayName("los metadatos nunca se dan por libres")
        void metadatosOcupados() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.create("/a", relleno(100, 90));
            for (int block = 0; block < fs.layout().dataFirst(); block++) {
                assertTrue(fs.freeMap().isUsed(block), "bloque de metadatos " + block);
            }
        }

        @Test
        @DisplayName("los bloques de un fichero son exactamente los que pide su tamano")
        void bloquesExactos() {
            for (AllocationMethod method : List.of(new AllocationMethod.Contiguous(),
                    new AllocationMethod.Linked(), new AllocationMethod.Indexed())) {
                FileSystem fs = nuevo(method);
                for (int size : new int[] { 1, 63, 64, 65, 200 }) {
                    String path = "/" + size;
                    fs.create(path, relleno(size, size));
                    Fcb fcb = fs.resolve(path);
                    assertEquals(method.dataBlocksFor(size, BLOQUE),
                            fcb.allocation().dataBlocks(),
                            method.name() + " con " + size + " bytes");
                }
                assertTrue(fs.verify().ok(), () -> method.name() + ": "
                        + String.join("\n", fs.verify().problems()));
            }
        }

        @Test
        @DisplayName("el uso del disco cuadra con lo que hay dentro")
        void uso() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.create("/a", "12345678");
            FileSystem.Usage usage = fs.usage();

            assertEquals(BLOQUES, usage.blocks());
            // Un bloque del fichero y otro del directorio raiz, que guarda su entrada.
            assertEquals(2, usage.dataBlocksInFiles());
            assertEquals(1, usage.files());
            assertEquals(1, usage.directories());
            assertEquals(fs.freeMap().freeCount(), usage.freeBlocks());
        }

        @Test
        @DisplayName("la descripcion de la asignacion dice donde esta cada bloque")
        void describe() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.create("/a", relleno(130, 100));
            Fcb fcb = fs.resolve("/a");
            List<String> lineas = fs.method().describe(fs.image(), fcb.allocation());

            assertFalse(lineas.isEmpty());
            assertTrue(lineas.get(0).contains("indice"), lineas.get(0));
        }

        @Test
        @DisplayName("un directorio vacio no gasta bloques")
        void directorioVacio() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            Fcb dir = fs.mkdir("/vacio");
            assertEquals(0, dir.size());
            assertEquals(0, dir.allocation().dataBlocks());
            assertEquals(0, dir.allocation().dataBlocks());
        }
    }

    @Nested
    @DisplayName("Rendimiento del guardado")
    class Rendimiento {

        @Test
        @DisplayName("guardar no lee bloques de datos")
        void guardarNoLee() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            fs.create("/a", relleno(100, 110));
            fs.image().resetCounters();
            fs.write("/a", relleno(200, 111));
            // Escribir necesita leer el bloque de indices, pero no los de datos.
            assertTrue(fs.image().blockWrites() >= 4, "escrituras " + fs.image().blockWrites());
        }

        @Test
        @DisplayName("el contiguo escribe menos que el enlazado para el mismo contenido")
        void escriturasPorMetodo() {
            byte[] data = relleno(200, 120);

            FileSystem contiguo = nuevo(new AllocationMethod.Contiguous());
            contiguo.image().resetCounters();
            contiguo.create("/a", data);

            FileSystem indexado = nuevo(new AllocationMethod.Indexed());
            indexado.image().resetCounters();
            indexado.create("/a", data);

            // El indexado paga un bloque de indices; el contiguo solo sus datos.
            assertTrue(contiguo.image().blockWrites() < indexado.image().blockWrites());
            assertNotEquals(contiguo.image().blockWrites(), indexado.image().blockWrites());
        }

        @Test
        @DisplayName("el texto se guarda en UTF-8 y vuelve igual")
        void utf8() {
            FileSystem fs = nuevo(new AllocationMethod.Indexed());
            String texto = "canon: no";
            fs.create("/t", texto);
            assertEquals(texto, new String(fs.read("/t"), StandardCharsets.UTF_8));
        }
    }
}
