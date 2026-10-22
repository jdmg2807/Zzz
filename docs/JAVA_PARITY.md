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
