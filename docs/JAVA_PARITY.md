# VoxelCraft ↔ Minecraft Java: plan de paridad 100 %

Este documento es la **referencia técnica** del proyecto para replicar Minecraft Java Edition
desde cero. Todo sale de documentación pública y de investigación de la comunidad:

- [Minecraft Wiki](https://minecraft.wiki) — páginas técnicas (formato de nivel, luz, opacidad,
  generación de mundo, biomas, estructuras, mecánicas).
- [wiki.vg](https://wiki.vg) — protocolo, formato de paquetes, datos de ítems/bloques.
- Foros y blog técnico de la comunidad de mods (mecánicas de tick, física, colormaps).

**No se usa ni se copia código de Mojang.** Los valores que aparecen aquí son constantes,
fórmulas y formatos descritos públicamente, reimplementados en Kotlin propio.

Estado: `✅` hecho · `🟡` aproximado · `⬜` pendiente

---

## 1. Modelo de luz

Fuente: [minecraft.wiki/w/Light](https://minecraft.wiki/w/Light), [Opacity](https://minecraft.wiki/w/Opacity)

| Regla | Estado |
| --- | --- |
| 16 niveles (0–15) | ✅ (luz de bloque 0–15, cielo 0–1 escalado por profundidad) |
| Luz de cliente = `max(luzCielo, luzBloque)` | 🟡 (el shader combina cielo·día y bloque con `max`) |
| Luz de bloque: `−1` por bloque de distancia taxicab | ⬜ (hoy es una caída radial aproximada) |
| Luz de cielo = 15 en bloques expuestos al cielo | 🟡 (columna → 1.0, atenuación por profundidad) |
| Cielo hacia abajo por bloque transparente: **sin** pérdida | ⬜ |
| Cielo horizontal/asciende: `−1` por bloque | ⬜ |
| Atenuación extra al atravesar: hojas `−2`, agua `−1`, telaraña `−1` | ⬜ |
| Luz de sol 15 · lluvia/nieve 12 · tormenta 10 · luna 4 | ⬜ (hoy `uDay` continuo) |
| Agua: en Java **no** añade atenuación a la luz de bloque, solo difumina la de cielo | ⬜ |

**Plan**: cambiar el mesher por un flood-fill real por chunk (cola de 16 niveles × 2 canales),
guardar `skyLight`/`blockLight` por bloque (nibble arrays) y recalcular solo los chunks sucios.
El shader pasará a recibir el nivel 0–15 y aplicar la curva de brillo
`b = l / (4 − 3·l)` (curva observada por la comunidad, equivalente a la tabla de brillo del juego).

---

## 2. Tick, tiempo y cielo

| Constante | Valor de Java | Estado |
| --- | --- | --- |
| Tickrate | 20 ticks/s (50 ms) | ✅ (delta-time fijo de 1/20) |
| Día completo | 24 000 ticks (20 min) | 🟡 |
| Amanecer/atardecer | 0–12 000 día, 12 000–24 000 noche | 🟡 |
| Ángulo celeste | `(ticks/24000 − 0.25)·360°` | ⬜ |
| Altura de nubes (1.18+) | y = 192, deriva ≈ 0.03 b/tick | 🟡 (nubes en shader) |
| Nivel del mar | y = 63 | ✅ (altura de generación propia) |
| Rango vertical (1.18+) | −64 … 320 | 🟡 (usamos 0…143 con offset 64) |

---

## 3. Física del jugador

Constantes públicas de la comunidad (el juego aplica todo por tick):

| Magnitud | Valor |
| --- | --- |
| Gravedad | 0.08 b/tick² (`0.08` por tick, acumulado en velocidad) |
| Velocidad vertical | `v = (v − 0.08) · 0.98` |
| Rozamiento horizontal en aire | `· 0.91` |
| Rozamiento en suelo | `· 0.6` (bloque) / `· 0.546` (con deslizamiento) |
| Salto | `v.y = 0.42` (`0.2` extra de sprint en carrera) |
| Velocidad de caminata | 4.317 b/s · sprint 5.612 b/s · agachado 1.295 b/s |
| Caída máxima | 3.92 b/tick |
| Altura del jugador | 1.8 (ojos 1.62), *sneak* 1.5 (ojos 1.27) |
| Alcance | 4.5 bloques (creativo 5) |

Estado: 🟡 — gravedad, salto y rozamiento ya existen pero con valores propios; falta el
`0.6 / 0.91` exacto, el *step* de 0.6 y el agua (empuje 0.014, `·0.8` de rozamiento).

---

## 4. Romper bloques

Fórmula pública (wiki: *Breaking*):

```
dañoPorTick = velocidadHerramienta / dureza / 30        (si puede romperse)
             ó velocidadHerramienta / dureza / 100      (si no puede)
ticks = ceil(1 / dañoPorTick)
```

| Material | Dureza |
| --- | --- |
| Tierra / hierba / arena / grava | 0.5–0.6 |
| Piedra | 1.5 |
| Madera / tablones | 2.0 |
| Sillería, ladrillo, adoquín | 1.5–2.0 |
| Obsidiana | 50 |
| Piedra base (bedrock) | ∞ |

Multiplicadores de herramienta: madera 2, piedra 4, hierro 6, diamante 8, netherita 9.
Estado: 🟡 (progreso de minado por tiempo constante) → ⬜ dureza por bloque + tiers.

---

## 5. Generación de mundo

Referencia: wiki *World generation*, *Biome*, *Noise*, *Custom world generation*.

| Pieza | Java | Estado |
| --- | --- | --- |
| Ruido base | `NormalNoise` (Perlin/simplex con octavas, semilla por hash) | 🟡 (hash + value noise) |
| Router de densidad 3D | splines de continentalidad/erosión/rareza | ⬜ |
| Biomas | parámetros climáticos (temperature, humidity, continentalness, erosion, weirdness, depth) | 🟡 (6 biomas por ruido) |
| Océano / playa / río | nivel del mar 63, profundidad | 🟡 |
| Cuevas | *cheese* (cámaras) + *spaghetti* (túneles), acuíferos | 🟡 (2 campos de ruido) |
| Menas | vetas con `count/size/height` por bioma | 🟡 (dispersión simple) |
| Estructuras | `StructureSet` con `spacing`/`separation`/`salt` + jigsaw | 🟡 (ruinas propias) |
| Aldeas / templos / fortalezas | plantillas NBT + jigsaw | ⬜ |
| Hielo, nieve, cactus, cañas | decoración por bioma y temperatura | 🟡 |

Siguiente paso concreto: **importar las plantillas de estructura** desde `.nbt` públicos
(`data/minecraft/structures/...`) para poder colocar aldeas sin inventar geometría.

---

## 6. Entidades y mobs

| Regla | Valor |
| --- | --- |
| Aparecen hostiles con luz de bloque ≤ 0 en 1.18+ (≤ 7 antes) | ⬜ |
| Distancia de despawn | 128 bloques |
| Ciclo de intentos de spawn | cada 20 ticks, por chunk |
| Gravedad de mobs | igual que el jugador (0.08) |
| Cerdos/ovejas/vacas/gallinas | pasivos, 2–3 por manada sobre hierba | ✅ (modelos de cajas) |

---

## 7. Color de biomas

Java interpola dos ejes (temperatura, humedad) en el *colormap* `grass.png`/`foliage.png` con
interpolación triangular y aplica `color · textura`. Estado: 🟡 — usamos una tabla de colores por
bioma, equivalente en resultado a los 6 biomas actuales; falta el colormap completo (16384 celdas)
y los tonos de agua por bioma.

---

## 8. Interfaz

| Elemento | Estado |
| --- | --- |
| Menú principal estilo Java (botones de piedra, splash amarillo, panorama) | ✅ |
| HUD: hotbar de 9, selección, corazón/hambre | 🟡 (hotbar ✅, vida/hambre ⬜) |
| F3 (coordenadas, FPS, bioma, luz) | ⬜ |
| Inventario con cuadrícula y *crafting* 3×3 (recetas JSON públicas) | ⬜ |
| Pantalla de pausa con opciones | ✅ |
| Pantalla de ajustes (render distance, FOV, sensibilidad) | ✅ |

---

## 9. Formato de datos (importación)

Implementado en `imports/Nbt.kt` + `imports/McWorldImporter.kt`:

- NBT completo (compound, list, arrays, todos los tipos numéricos). ✅
- `level.dat`: nombre, versión, `DataVersion`, spawn y semilla con **múltiples formatos**
  (`SpawnX/Y/Z`, `spawn.pos`, `spawn_pos`, `Player.Pos`, `WorldGenSettings.seed`…). ✅
- Regiones Anvil (`.mca`): cabecera de 4 KiB, compresión gzip/zlib/sin comprimir. ✅
- Secciones 1.18+ (`sections[].block_states`), 1.16–17 (`Level.Sections`), 1.13–15 (empaquetado
  continuo) y 1.8–12 (ids clásicos). ✅
- Paleta de bloques → bloques del motor con alias y heurísticas; lo desconocido que **no** es un
  bloque parcial se convierte en piedra (un hueco es peor que una aproximación). ✅
- `entities/`, `poi/`, `data/` (mapas, avances): ⬜.

---

## 10. Orden de trabajo propuesto

1. **Luz real por flood-fill** (visible en cuevas, antorchas y al quitar bloques).
2. **Romper bloques con dureza/herramientas** y sonidos/partículas.
3. **Física exacta** (0.6/0.91/0.98, agua, escaleras, *sprint-jump*).
4. **Generación por splines** + biomas por parámetros climáticos, nivel del mar 63.
5. **Estructuras NBT** (aldeas, templos, fortalezas) desde plantillas públicas.
6. **Inventario + crafting** con las recetas JSON públicas.
7. **HUD y F3** completos, luego menús restantes (selección de mundo, opciones de vídeo).
8. **Mobs hostiles** con spawn por luz y despawn a 128 bloques.

---

## 11. Estado de paridad al cerrar la v1.7.x

Lo implementado y verificado en esta tanda, con la fuente de cada número:

| Sistema | Detalle | Fuente |
|---|---|---|
| GUI | escala `clamp(min(w/320, h/240), 1, 4)`, botón 200×20, hotbar 182×22, corazones 9 px | Minecraft Wiki *GUI* |
| Inventario | 176×166, rejilla 2×2 en (98,18), resultado (154,28), jugador (8,84), hotbar (8,142) | *Inventory* |
| Mesa de trabajo | rejilla 3×3 en (30,17), resultado (124,35) | *Crafting table* |
| Cofre | 176×168, 27 huecos en (8,18), jugador en (8,85) | *Chest* |
| Horno | 176×166, entrada (56,17), combustible (56,53), salida (116,31), 200 ticks por pieza | *Furnace* |
| Yunque | coste 1 nivel por renombrar, bono de reparación del 12 %, penalización 2^n | *Anvil mechanics* |
| Combate 1.9 | carga `0.2 + 0.8·c²`, cooldown `20/velocidad`, crítico ×1,5, barrido 1 | *Damage* |
| Ruptura | `dureza · 1,5 / velocidad`, niveles de pico por mena | *Breaking* |
| Durabilidad | 59 / 131 / 250 / 1561 / 2031 | *Durability* |
| Redstone | 15 niveles, −1 por bloque, −1 al subir, repetidor 1–4 ticks (0,1–0,4 s) | *Redstone circuit* |
| Agua | manantial 0, flujo 1–7, caída 8, paso cada 5 ticks | *Water* |
| Gravedad de bloques | 1 bloque por tick (arena, grava) | *Falling block* |
| Cuevas | cheese (1 campo), spaghetti (2 campos), noodle (2 campos a alta frecuencia) | *Cave* |
| Explosiones | potencia 4 (TNT) y 3 (creeper), caída lineal, resistencia por material | *Explosion* |
| Objetos | gravedad 0,04, rozamiento 0,98, recogida a 1 bloque, 6000 ticks | *Item (entity)* |
| Experiencia | 7+2n / 9+5n / 9+9n | *Experience* |
| Comida | puntos + saturación con modificador por alimento, 1,6 s por bocado | *Food* |

Pendiente declarado (no forma parte de esta entrega): traducción completa de shaders Iris
(`#version 330` → GLSL ES 3.00 con gbuffers/composite), estructuras multi-chunk de aldea,
encantamientos y pociones, y el client-server real (aquí el mundo vive en un solo proceso).

---

## 12. Movimiento del jugador (v2.1.x)

El movimiento se reescribió siguiendo el modelo real del juego en lugar de perseguir una
velocidad objetivo. La referencia de comportamiento usada es `prismarine-physics`
(licencia MIT, `PrismarineJS`), que reproduce el movimiento de Java Edition a partir de
mediciones de la comunidad; el código Kotlin de este repositorio es propio.

| Paso | Fórmula | Valor |
|---|---|---|
| Aceleración | `vel += dirección · accel` con `accel / max(velocidad_de_entrada, 1)` | 0,1 andando / 0,13 esprintando |
| Inercia en suelo | `vel × (deslizamiento · 0,91)` | 0,546 (hielo 0,98 → 0,8918) |
| Aceleración en suelo | `atributo · 0,16277136 / inercia³` | 0,1 y 0,13 con la inercia por defecto |
| Aire | `accel` 0,02 (0,026 esprintando), inercia 0,91 | — |
| Salto | `vy = 0,42`; esprintando `+0,2` horizontal en la dirección de la vista | ápice 1,252 bloques |
| Gravedad | `vy -= 0,08`, `vy *= 0,98` | velocidad límite 3,92 b/tick |
| Agua / lava | inercia 0,8 / 0,5, flotación −0,02, subir +0,04 | impulso al salir 0,3 |
| Escalera | clamp horizontal 0,15, trepar 0,2 | — |
| Agachado | entrada × 0,3 y retroceso de 0,05 en el borde | ya no congela el movimiento |
| Umbral | velocidades < 0,003 b/tick se anulan | — |
| Colisión | subpasos de 0,4 bloques por eje | impide atravesar suelos al caer |

Las velocidades sostenidas que salen de esas fórmulas son 4,317 b/s andando, 5,612 b/s
esprintando y 1,295 b/s agachado, las de Java Edition.

### Sobre el código fuente de Minecraft

`mcsrc.dev` descomprime y descompila el jar de Minecraft **en el navegador del visitante**
(descargado de los servidores de Mojang, con Vineflower): no hay ninguna URL que sirva el
código, y copiarlo a este repositorio sería redistribuir código de Mojang. Este proyecto
sigue siendo limpio: los números vienen de la wiki, de mediciones de la comunidad y de
implementaciones libres como `prismarine-physics`.

---

## 13. Generación de mundo (v2.2)

La generación anterior era un único FBM de cuatro octavas: el terreno salía con la misma forma en
todas partes, el nivel del mar estaba en y = 32 y los biomas se elegían por altura, así que no
había continentes, ni costas, ni ríos, ni montañas de verdad. Se reescribió con la estructura por
capas del generador moderno, tal como la describe la Minecraft Wiki (*World generation*, *Noise
router*, *Carver*, *Ore*):

| Capa | Ruido | Rango | Qué controla |
|---|---|---|---|
| Continentalidad | Perlin 4 octavas, 1/625 bloques | −1..1 | océano profundo → plataforma → costa → interior |
| Erosión | Perlin 3 octavas, 1/312 bloques | −1..1 | terreno plano o quebrado; solo con erosión baja hay montañas |
| Crestas | Perlin en valor absoluto, 4 octavas | 0..1 | picos afilados (no colinas redondas) |
| Detalle | Perlin 3 octavas | −1..1 | rugosidad local de 2-3 bloques |
| Ríos | Perlin 1 octava, 1/476 bloques | banda 0,05 | valle de río de hasta 13 bloques de profundidad |
| Clima | Perlin (temperatura y humedad) | 0..1 | bioma, corregido por altura |

Ajuste de escala y sesgo: el ruido Perlin crudo sale en ±0,35 con media distinta de cero según el
campo (continentalidad +0,05, temperatura −0,34, humedad −0,14). Sin normalizar, el 55 % del mapa
quedaba bajo el agua y el 80 % de la tierra era nevada; con los factores medidos
(×2,8 / ×2,6 / +0,17 / +0,07) el reparto queda como en la tabla siguiente.

**Distribución medida** (muestreo de 65 536 columnas alrededor del origen):
océano 38 %, tierra 62 %; de la tierra: llanura 35 %, bosque 27 %, taiga 15 %, nevado 9 %,
desierto 3 %, jungla 1 %. Alturas de 13 a 154 bloques, media 69.

**Cuevas**: *cheese* (una cavidad), *spaghetti* (dos campos que se cruzan) y *noodle* (frecuencia
alta). Se ensanchan hacia la superficie para que haya simas, y no se carva a menos de 6 bloques
del lecho marino para que el agua no se vacíe dentro. Fracción de aire subterráneo medida: 5,75 %.

**Menas**: bolsas en celdas de 4×4×4 (6 % de celdas, esfera central), ≈3 % del volumen de piedra,
con la distribución por altura del juego: carbón hasta y 96, cobre 56, hierro 52, lapislázuli 34,
oro 30, redstone 18 y diamante 14. Lava en las cuevas por debajo de y 9.

**Superficie**: hierba + tierra en clima templado, arena + arenisca en desierto y playa (una sola
columna sobre el mar), nieve en los biomas fríos y sobre la línea de nieve (y > 101), grava en los
fondos marinos profundos, piedra desnuda en los picos y deepslate por debajo de y 8.

**Estructuras**: ruinas, mazmorra con spawner y cofres, y casa de aldea con cimiento de piedra,
muros de tablón, ventanas de cristal, tejado a dos aguas y muebles. Se colocan por regiones
deterministas, así que no dependen del orden en que se generen los chunks.

## 14. Propiedades de los bloques

`assets/vanilla/blocks.json` (generado con `tools/gen_blocks.py` desde `minecraft-data`, MIT)
trae 1165 bloques con dureza, resistencia a explosiones, luz emitida, luz filtrada, apilado,
herramienta y nivel mínimo de herramienta. `BlockData` lo aplica a la paleta al arrancar.

Con eso el tiempo de picado es el del juego:

    con la herramienta correcta:  tiempo = dureza × 1,5 / velocidad
    sin la herramienta correcta:  tiempo = dureza × 5   / velocidad

donde la velocidad es 2 (madera), 4 (piedra), 6 (hierro), 8 (diamante) o 9 (netherita), y 1 a mano.
Ejemplos: piedra 1,5 s con pico de madera, 0,38 s con diamante; obsidiana 50 s con diamante;
antorcha instantánea; bedrock indestructible (−1). El drop exige además el nivel mínimo: hierro
necesita pico de piedra, diamante pico de hierro, obsidiana pico de diamante.
