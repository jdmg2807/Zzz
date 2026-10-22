# VoxelCraft → Minecraft Java Edition 1.21: plan de refactorización

Documento de ingeniería. Analiza el estado real del código, explica **por qué** fallaba el menú
principal (ya corregido en esta versión) y define el plan paso a paso de los cinco subsistemas
pedidos, con entregables, criterios de aceptación, riesgos y fuentes.

Reglas del proyecto que condicionan todo lo de abajo:

- Código propio. **Cero código de Mojang**: comportamiento, formatos y constantes se obtienen de
  [Minecraft Wiki](https://minecraft.wiki), [wiki.vg](https://wiki.vg) e investigación pública de
  la comunidad (documentada en `docs/JAVA_PARITY.md`).
- Singleplayer. Nada de protocolo de red: wiki.vg se usa solo como referencia de **formatos de
  datos** (NBT, estados de bloque, entidades), no de paquetes.
- El APK se compila en GitHub Actions; todo cambio debe compilar en ese entorno (Kotlin 1.9.24,
  AGP 8.5, GLSL ES 3.00, `minSdk 24`).

---

## 0. Diagnóstico del menú principal (causa raíz y corrección aplicada)

**Síntoma reportado:** el menú no cabe; botones fuera del viewport en pantallas de móvil.

**Causa raíz (medida, no supuesta).** El `MainMenu` anterior construía una `Column` con
`fillMaxSize() + verticalScroll() + verticalArrangement = Center` y ponía **nueve** widgets de
`42 dp` más cuatro filas, una cabecera de título de ~150 dp y `padding(horizontal = 32.dp)` sobre
una columna de **ancho fijo 360 dp**. Total de contenido vertical ≈ **600 dp** en un viewport de
juego típico de **360 dp** en horizontal (y ~640 dp en vertical con barras). Tres defectos
encadenados:

1. **Sin sistema de escala de GUI.** Todo se medía en `dp` absolutos, así que el contenido no se
   adaptaba: en pantallas pequeñas la suma excedía el alto y los últimos botones quedaban fuera
   del área visible inicial (`verticalScroll` los hace alcanzables, pero un menú de juego no debe
   requerir scroll; además `Arrangement.Center` sobre contenido mayor que el viewport rompe el
   centrado y recorta arriba y abajo).
2. **Ancho fijo 360 dp + 64 dp de padding = 424 dp** mínimos: en pantallas de 320–360 dp el bloque
   se sale horizontalmente.
3. **Tipografía en `sp` sin acotar**: con escala de fuente del sistema alta (accesibilidad), las
   etiquetas desbordaban los botones.

**Corrección (v1.6.1, en este commit).**

- `ui/GuiScale.kt`: se implementa el **GUI Scale real de Java**. La GUI se dibuja en píxeles
  virtuales; el factor es `clamp(min(ancho/320, alto/240), 1, 4)` (modo Auto) o un valor fijo 1–4.
  Todos los widgets usan `gui.px(n)` = `n / escala` dp, de modo que **todo encoge de forma
  uniforme** y es imposible salirse del viewport. La tipografía usa `gui.text(px)`, que convierte
  píxeles GUI a `sp` neutralizando la escala de fuente del sistema.
- `ui/Menu.kt`: layout reconstruido con las **medidas de Java**: botón `200×20`, separación `4`,
  logo centrado con borde superior en `height/4`, splash rotada −20° con pulso, y solo los tres
  botones pedidos (**Un jugador**, **Ajustes…**, **Salir del juego**; sin Multijugador ni Realms).
  Fondo: panorama procedural con parallax de tres capas + desenfoque (`Modifier.blur`, activo en
  Android 12+) y velo oscuro, en el papel del cubemap de panorama de Java.
- `ui/Singleplayer.kt`: nueva pantalla **Seleccionar mundo** (lista enmarcada, Jugar / Crear /
  Cancelar) con el diálogo de importación (mundo, texturas, shaders, mod, autodetección). Los
  antiguos botones sueltos de Mods/Packs se han movido a **Ajustes**, donde Java también los tiene.
- **Ajustes → Escala de interfaz**: Auto o 1–4, persistido en `SharedPreferences`.

Criterio de aceptación cumplido: en 320×240 dp virtuales los tres botones y el título caben sin
scroll ni recortes; en tablet el conjunto se ve más pequeño (escala 4), como en Java.

---

## 1. Reorganización de arquitectura (prerrequisito de todo lo demás)

Hoy hay 30 archivos en un solo módulo `:app` con paquetes por dominio. Para soportar 700 bloques,
redstone, IA y GUI compleja hay que fijar fronteras antes de escribir features:

```
:app            Android, Compose, ciclo de vida, entrada táctil
:engine-core    log, math (Vec3, AABB, Random), tick loop, config
:engine-world   chunk, sections, BlockState, palette, lighting, worldgen, Anvil
:engine-physics AABB/collision, físicas de entidades, fluidos
:engine-entity  ECS ligera: componentes + goals + pathfinding
:engine-render  mesher, chunks GL, pipeline de shaders, GUI renderer
:engine-gui     layout de pantallas Java, widgets, atlas de GUI
:modkit         API pública (Java) + eventos
```

Paso 0 (una versión completa, sin features): extraer `:engine-*` como módulos Gradle manteniendo
el paquete `com.arena.voxel.*` (mover archivos y ajustar `settings.gradle.kts`). Esto rompe la
compilación una sola vez y evita refactors dolorosos después. **No** se hace big-bang: se mueve un
paquete por commit y se valida con CI en cada paso.

Decisiones transversales que hay que tomar ahora porque son caras luego:

| Decisión | Ahora | Necesario | Motivo |
| --- | --- | --- | --- |
| Índice de bloque | `ByteArray` (256 máx.) | `ShortArray` (paleta + `Short`) | +700 bloques no caben en un byte |
| Almacenamiento de chunk | 1 array 16×16×256 | **secciones 16³** con paleta por sección | memoria y meshing incremental |
| Guardado | `VCX2` (byte) | `VCX3` (short + secciones) | migración con remapeo por nombre |
| Tick | variable por frame | acumulador fijo 20 Hz | redstone, IA y física requieren ticks exactos |

Estimación de memoria con `ShortArray` y columna completa: 16·16·256·2 = **128 KB/chunk**; con 169
chunks ≈ 21 MB, aceptable, pero el meshing debe ser por secciones para no reconstruir 128 KB por
cada bloque roto.

---

## 2. Subsistema 1 — GUI (Java 1.21)

### Estado actual
Menú principal y pantalla "Seleccionar mundo" ya usan GUI Scale y medidas de Java. `GameScreen`
tiene HUD propio (hotbar de 46 dp por slot, sin vida/hambre/XP/aire) y un menú de pausa Material.
Inventario, crafting, horno, yunque, cofres: **no existen**.

### Arquitectura objetivo
`engine-gui` con tres capas:

1. **`GuiCanvas`**: espacio virtual (`Gui`), origen arriba-izquierda, unidades = píxeles GUI.
2. **`NinePatch`**: dibujo de paneles a partir de un atlas de GUI con las **medidas reales** de
   Java: `widgets.png` (botones 200×20 con estados normal/resaltado/deshabilitado),
   `container.png` (inventario 176×166, cofre 176×168, horno 176×166, yunque 176×166),
   `icons.png` (corazón 9×9 ×2 estados, comida 9×9, armadura), `hotbar.png` (182×22).
3. **`Screen` + `Widget`**: pila de pantallas, foco, `Esc` = volver, y un `Slot` genérico con
   validación por tipo de contenedor (base del inventario, cofres, horno, crafteo y yunque).

### Pasos
1. **Atlas de GUI** (`res/builtinpack/gui/*.png` generados por código, igual que `TileGen`): hoy
   todo se dibuja con rectángulos de Compose. Se necesita un `GuiAtlas` con las regiones
   documentadas en [minecraft.wiki/w/GUI](https://minecraft.wiki/w/GUI) para que bordes, esquinas
   y estados se vean idénticos. Criterio: un botón renderizado con `NinePatch` debe ser
   indistinguible del de Java a escala 1, 2, 3 y 4.
2. **HUD**: hotbar 182×22 centrada abajo (con selector 24×24 dibujado **detrás**), corazones
   (10 × 9×9, parpadeo y daño), hambre (10 × 9×9, con saciedad), barra de XP (182×5 + nivel),
   burbujas de aire (10) al estar bajo el agua, y la mano/ítem en primera persona. Posiciones:
   hotbar en `(width/2 - 91, height - 22)`, vida encima, hambre a la derecha — mismas coordenadas
   que Java, expresadas en píxeles GUI.
3. **Inventario de supervivencia**: panel 176×166, rejilla 3×9 + hotbar, slots 18×18 con ítem
   16×16, armadura y offhand; arrastrar y soltar con *split* (clic derecho) y *shift-click*.
4. **Inventario creativo**: pestañas de categorías (construcción, decoración, redstone, transporte,
   comida, herramientas, combate, pociones, ingredientes, spawn eggs), buscador, scroll por página,
   y el panel 195×136 de Java.
5. **Mesa de crafteo** (3×3 + resultado), **horno** (entrada/combustible/salida con 200 ticks y
   progreso), **yunque** (reparación y renombrado con coste de niveles), **cofre** (27 + 9),
   **pausa** (Escape: "Volver al juego", "Opciones...", "Guardar y salir") y **muerte** (respawn).
6. **Sistema de ítems**: `ItemStack(id, count, damage)`, `Item` con `maxStackSize`, iconos 16×16
   desde el atlas, y `Container` compartido entre UI y lógica (fuente de verdad en el mundo, no en
   Compose).

### Criterios de aceptación
- Ninguna pantalla puede dibujar fuera del rectángulo virtual: test de layout automático que, para
  320×240 / 360×640 / 800×360 / 1280×800, comprueba que todo widget está dentro.
- HUD idéntico al de Java a escala 1 (comparación visual contra las medidas de la wiki).
- Arrastrar un stack entre hotbar e inventario persiste al cerrar y al guardar el mundo.

---

## 3. Subsistema 2 — Físicas, combate y redstone

### Estado actual
`Player.kt` mueve con `dt` variable, velocidad fija por estado (4.7 / 6.1 / 3.4), AABB de
0.62×1.8 comprobando **bloques completos** (`isSolid`), gravedad propia y vuelo creativo. No hay
losas/escaleras, ni superficies con fricción distinta, ni combate con cooldown, ni redstone.

### 3.1 Tick loop determinista (base de todo)
Acumulador: `acc += dtReal; while (acc >= 0.05) { tick(); acc -= 0.05 }` con clamp anti-espiral
(máx. 10 ticks por frame). Física y redstone se integran **por tick** con las constantes de Java;
el render interpola con `acc / 0.05`.

### 3.2 Físicas exactas
| Constante | Valor Java (público) | Acción |
| --- | --- | --- |
| Gravedad | `vy -= 0.08` por tick, luego `vy *= 0.98` | implementar exacto |
| Salto | `vy = 0.42` (+ `0.2` de impulso de sprint) | implementar |
| Fricción suelo | `*0.546` (bloque normal) / `*0.6` según versión, `*0.989` deslizamiento | por bloque |
| Fricción aire | `*0.91` horizontal | implementar |
| Velocidad | caminar 4.317, sprint 5.612, agachado 1.295, nadar 2.2 m/s | derivar de la fórmula de movimiento |
| Agua | `vy = 0.02` de flotación, `*0.8` de arrastre, `*0.9` con sprint | implementar |
| Escalones | `stepHeight 0.6` | implementar |
| Altura jugador | 1.8 (ojos 1.62), agachado 1.5 (ojos 1.27) | ✅ base |

**Formas de bloque**: sustituir `isSolid` por `VoxelShape` (lista de AABBs por `BlockState`):
cubo completo, losa (8/16), escalera (dos cajas), valla (4/16 + colisión 1.5 de alto), puerta,
cama, y `null` para no colisionables (plantas, antorchas, líquidos). Las colisiones se resuelven
por eje con *sweep* (primero Y, luego X, luego Z) para no atravesar esquinas.

### 3.3 Combate 1.9+
- Cooldown por arma: `attackSpeed` (espada 1.6, hacha 1.0/0.8, mano 4.0); `progress = min(1, ticks/ (20/attackSpeed))`;
  daño escalado `0.2 + progress² * 0.8`.
- Crítico: caída (`vy < 0`, no en suelo, sin agua, sin escalera) → ×1.5 y partículas.
- Barrido: espada + sprint sin crítico → daño 1 a entidades en 1 bloque de radio, knockback.
- Invulnerabilidad de 10 ticks (`hurtResistantTime`) y `knockback 0.4`.
- Escudo: bloqueo frontal 180°, reduce 100 %, `*0.33` de knockback.

### 3.4 Redstone (el subsistema más grande)
Modelo: **fuente de verdad = `BlockState` + lista de actualizaciones programadas** por chunk.
1. **Propagación**: BFS de 15 niveles desde fuentes (polvo, bloque, antorcha, palanca, botón,
   repetidor, comparador, observador, presión). El polvo pierde 1 por bloque; subir un escalón
   cuesta 1 extra (regla de Java). Implementar como `SignalGraph` por chunk con recomputo local
   (vecindad 3×3 de chunks) al cambiar un bloque.
2. **Ticks**: 1 redstone tick = 2 game ticks (0.1 s). Repetidor: 1–4 ticks de retardo
   configurables por clic derecho. `scheduledTicks` por bloque (como el `TickScheduler` de Java:
   `(pos, block, delay, priority)`).
3. **Componentes**: antorcha (inversor, 1 tick), repetidor (diodo, bloqueo por alimentación
   lateral), comparador (modos comparar/restar, lectura de contenedores por llenado), observador
   (detecta cambio de estado y emite 2 ticks), pistón/sticky (mueve hasta 12 bloques, no mueve
   bedrock/obsidiana/cofres), dispensador, dropper, rail, puerta, lámpara, TNT.
4. **Quasi-conectividad**: en Java el pistón se activa si el bloque *de arriba* recibe energía
   (bug histórico que es comportamiento canónico). Debe replicarse o las granjas de la comunidad
   no funcionan. Documentarlo como decisión de fidelidad.
5. **Persistencia**: guardar `signal` y `scheduledTicks` en el save; al cargar, recomputar el
   grafo en un radio de 2 chunks alrededor de cada bloque activo.

### Criterios de aceptación
- Circuitos de prueba versionados en `docs/redstone-tests.md`: inversor, puerta AND/OR, reloj de
  repetidores, contador con observers, pistón 12 bloques, comparador leyendo un cofre. Cada uno
  con tick de salida esperado.
- Física: test de "saltar 1 bloque", "caer 3 bloques y sobrevivir / 4 y morir", "subir escalera
  sin frenar", "deslizar en hielo 8 bloques".

---

## 4. Subsistema 3 — Mundo, Anvil y +700 bloques

### Estado actual
`McWorldImporter` lee NBT + `.mca` (1.8 → 1.21) y decodifica paletas por sección, con Y negativas
mapeadas a `y + 64` en un mundo de 256 de alto. La paleta del juego tiene ~45 bloques en un
`ByteArray`; los desconocidos se convierten en piedra. `WorldGen` es ruido propio (value noise con
hash), 6 biomas, cuevas de 2 campos y ruinas.

### 4.1 Registro de bloques (+700)
Tabla de datos generada (no escrita a mano): un archivo `blocks.json` propio, con un registro
`(id, hardness, resistance, tool, tier, light, opacity, cullface, shapes, sounds, flammable)`.
Se puede **generar** desde los datos públicos de estados de bloque (nombres, dureza y resistencia
aparecen en las páginas de cada bloque de la wiki) y versionarlo en el repo. Estimación: 1 100+
entradas de bloque en 1.21; el objetivo de 700 cubre lo que un jugador encuentra en overworld +
nether + end.
- `BlockState` con propiedades tipadas (`facing`, `half`, `waterlogged`, `powered`, `age`, ...) y
  un `StateModel` para el mesher (cajas + texturas por cara + `cullface`).
- Paleta: `ShortArray` + secciones 16³ (ver §1).
- Bloques 1.21 pedidos: cobre (normal, expuesto, oxidado, oxidado + variantes cinceladas y
  cortadas), **Crafter** (autocrafteo con señales de comparador), **Vault** (apertura con llave,
  loot público), **Trial Spawner** (oleadas por dificultad y cooldown). Todos necesitan estado +
  lógica de tick; se implementan tras el sistema de contenedores/ítems del §2.

### 4.2 Anvil e importación
Ya soporta 1.8–1.21 con `sections[].block_states`. Pendiente: propiedades de bloque (hoy solo el
nombre), biomas por sección (`biomes.palette`), `Heightmaps`, entidades (`entities`), `poi`,
`block_entities` (cofres con contenido, carteles, spawners) y `Data.Player` para inventario.
Criterio: importar un mundo 1.21 con un cofre lleno y encontrar el contenido dentro.

### 4.3 Worldgen
1. **Ruido**: Perlin/simplex propio con `NormalNoise`-style (octavas + `firstOctave`, amplitud
   normalizada) sembrado por hash, como ya hace `WorldGen.hashAt` pero con implementación dedicada
   y test de reproducibilidad por semilla.
2. **Router de densidad** (1.18+): `continentalness`, `erosion`, `weirdness`, `temperature`,
   `humidity`, `depth` → splines → densidad 3D → altura. La wiki documenta los parámetros; se
   implementa con una tabla de puntos de control propia.
3. **Cuevas**: tres tipos — *cheese* (cámaras), *spaghetti* (túneles 1 y 2) y *noodle* — cada uno
   con su rango de ruido y umbral; acuíferos para agua subterránea.
4. **Biomas**: colocación por parámetros climáticos (no por ruido suelto), con tabla de biomas
   ordenada por "rareza" como en el generador multi-noise.
5. **Estructuras**: lector de plantillas `.nbt` públicas + colocación por `StructureSet`
   (`spacing`, `separation`, `salt`, `spread_type`). Empezar por ruinas y naufragios (pequeñas),
   luego aldeas (jigsaw), fortalezas y templos.
6. **Decoración**: menas por altura y bioma, árboles por bioma, hielo/nieve por temperatura,
   cactus y cañas por humedad.

### Criterios de aceptación
- Determinismo: misma semilla → mismo mundo en dos ejecuciones y en dos dispositivos.
- Un chunk generado debe tardar < 15 ms y no bloquear el frame (generación en hilos con cola).
- Comparación visual con capturas públicas de 1.21 para 5 semillas conocidas (bioma y silueta).

---

## 5. Subsistema 4 — IA, pathfinding y goals

### Estado actual
`Mobs.kt`: cuatro animales pasivos con gravedad, deambular aleatorio y giro al chocar. Sin
pathfinding, sin hostiles, sin estados.

### Arquitectura
- **Componentes**: `Position`, `Velocity`, `Health`, `AI`, `Look`, `Inventory`, `Equipment`.
- **Goal selector** con prioridades (como Java): cada goal tiene `canStart/canContinue/tick` y se
  ejecuta el de menor prioridad que pueda empezar; `MutexFlags` (MOVE, LOOK, JUMP) evitan que dos
  goals muevan a la vez. Goals base: `FloatGoal` (agua), `PanicGoal`, `MeleeAttackGoal`,
  `RangedAttackGoal`, `RandomStrollGoal`, `LookAtPlayerGoal`, `AvoidEntityGoal`,
  `OpenDoorGoal`, `MoveToBlockGoal` (trabajo de aldeanos), `RestrictSunGoal`, `FleeSunGoal`.
- **Pathfinding A\***: grafo de nodos sobre el chunk (no por bloque suelto): vecinos = caminar,
  saltar 1, caer hasta 3, nadar, abrir puerta. Coste base 1, penalización por peligro
  (lava/fuego/cactus/agua profunda) y por caída; `heuristic = distancia Manhattan × 1.0`.
  Ejecutar en un hilo de trabajo con presupuesto por tick (p. ej. 200 nodos/tick) y caché por
  entidad; si el objetivo se mueve > 1 bloque, recalcular.
- **Hostiles**: zombie (persigue jugador/aldeanos, rompe puertas en dificultad difícil, quema al
  sol), esqueleto (mantiene 8–16 bloques, dispara con arco y predicción, huye al acercarse),
  creeper (se acerca, silba, explota), araña (escala paredes). Spawn por luz (§2 de
  `JAVA_PARITY.md`), despawn a 128 bloques.
- **Aldeanos**: rutina día/noche (trabajo → casa → dormir), pánico al ser atacados, propagación de
  rumores (simplificada), comercio con `VillagerProfession` y ofertas por nivel.

### Criterios de aceptación
- Zombi rodeado de bloques encuentra al jugador en < 3 s sin atravesar paredes ni caer a la lava.
- 40 mobs a la vez no bajan de 30 FPS en el dispositivo de prueba; coste de IA < 2 ms/frame.
- Test automatizado de pathfinding en mapas sintéticos (habitación con puerta, escalera de 3,
  foso de 4, laberinto).

---

## 6. Subsistema 5 — Pipeline gráfico, shaders y mods

### 6.1 Renderizado
- **Estado**: mesher por bloque con `inset`/`heightCut`, AO aproximado, agua en malla aparte,
  frustum culling por chunk, atlas 32×32 de tiles de 32 px.
- **Objetivo**: secciones de 16³ con VBO propio y `dirty` por sección; **greedy meshing** para
  caras coplanares con misma textura/luz/AO (reduce 3–6× los vértices en superficies grandes);
  *occlusion culling* por chunk (ya hay frustum); render en dos pasadas (opaco → recortado) +
  translúcidos ordenados por distancia; `glMultiDraw`-style batching por buffer.
- Presupuesto: 60 FPS a 8 chunks de distancia en un gama media; malla de chunk < 8 ms.

### 6.2 Shaders
- **Estado**: 8 programas propios (terrain/water/sky/post), packs GLSL ES 3.00 con los mismos
  nombres, detección y omisión de packs Iris/OptiFine.
- **Realidad técnica que hay que decir claro**: los packs de Iris/OptiFine declaran
  `#version 330/400/430`, usan `gbuffers_*`, `shadow`, `composite`, `deferred`, *attribute*
  locations de OpenGL de escritorio y uniforms como `gbuffersTexture`, `shadowMap`,
  `modelViewMatrix`, `projectionMatrix`, `frameTime`, `sunPosition`. **GLSL ES 3.00 no puede
  compilar esos shaders tal cual** y Android no expone el pipeline de escritorio. La vía viable es
  una **capa de compatibilidad**:
  1. Preprocesador: reescribir `#version` a `300 es`, convertir `in/out` antiguos, inyectar
     `precision`, mapear `texture2D`→`texture`, `gl_FragColor`→`out vec4`.
  2. Tabla de uniforms: `frameTime`→`uTime`, `sunPosition`→`uSunDir`, `modelViewMatrix`/
     `projectionMatrix`→`uMVP` descompuesto, `shadowMap`→ textura de profundidad propia (FBO).
  3. Pases soportados: `gbuffers_terrain`, `gbuffers_water`, `gbuffers_skybasic`,
     `composite`, `final`. Lo que use pases no soportados se rechaza con mensaje claro (no
     "compila y falla").
  4. Informe por shader en el log: uniforms no resueltos, pases omitidos, motivo.
- Uniforms estándar a exponer: `uTime`, `uFrameTime`, `uSunDir`/`sunPosition`, `uCam`,
  `uModelView`, `uProjection`, `uMVP`, `uShadowMap`, `uResolution`, `uFog`, `uDay`, `uUnderwater`,
  `uSunColor`, `uZenith`, `uHorizon`, `uCloudY`, `uCloudCover`, `uLightColor`.

### 6.3 Mods (estilo Fabric)
- **Estado**: `.jar` con `voxelmod.json` + `DexClassLoader`, interfaz `VoxelMod` con callbacks
  (`onLoad`, `onWorldReady`, `onTick`, `onBlockPlaced`, `onBlockBroken`), `ModContext` con
  `WorldAPI`, `PlayerAPI`, `ChatAPI`.
- **Objetivo**: registro + bus de eventos con fases:
  - `Registry`: `registerBlock`, `registerItem`, `registerBlockEntity`, `registerRecipe`,
    `registerBiome` — todo devuelve un `RegistryKey` con namespace (`modid:block`).
  - `EventBus` tipado: `init`, `register` (mods), `data` (recetas/etiquetas), `worldTick`,
    `playerTick`, `blockBreak` (cancelable), `blockPlace` (cancelable), `entityTick`,
    `screenOpen`, `renderOverlay`.
  - `ModEntrypoint` por descriptor (`"entrypoints": {"main": [...], "client": [...]}`), orden de
    carga por `depends`/`breaks`.
  - Aislamiento: cada jar con su `DexClassLoader` y lista blanca de paquetes expuestos
    (`com.arena.voxel.api.*`), para que un mod no pueda tocar internals ni romper el motor.
  - Versión de API (`apiVersion` en el descriptor) y compatibilidad hacia atrás declarada.

### Criterios de aceptación
- Un mod de ejemplo registra bloque, ítem, receta y escucha `blockBreak` sin tocar el motor.
- Un shader pack simple (solo `gbuffers_terrain` + `final`, GLSL 1.20-style) se traduce y compila;
  uno con `deferred` se rechaza con motivo explícito.
- Greedy meshing reduce ≥ 50 % de vértices en un mundo plano de 8 chunks.

---

## 7. Hoja de ruta por versiones

Cada versión es un APK instalable, compilado por CI, con changelog y criterios de aceptación
verificados en el dispositivo de prueba. Ninguna versión rompe lo anterior (regresión = bug P0).

| Versión | Contenido | Depende de |
| --- | --- | --- |
| **v1.6.1** ✅ | GUI Scale + menú Java + pantalla Seleccionar mundo (este commit) | — |
| **v1.7.0** | Tick 20 Hz determinista · física exacta · AABB por formas (losas, escaleras, vallas) · agacharse · nadar | — |
| **v1.8.0** | Ítems + contenedores + HUD completo (vida, hambre, XP, aire) + inventario supervivencia/creativo + cofres + horno | v1.7 |
| **v1.9.0** | Paleta `Short` + secciones 16³ + registro de +700 bloques con dureza/transparencia + greedy meshing | v1.8 |
| **v1.10.0** | Worldgen multi-noise (splines, cheese/spaghetti/noodle, biomas por clima) + estructuras NBT (ruinas, naufragios) | v1.9 |
| **v1.11.0** | Redstone completo (polvo, antorcha, repetidor, comparador, observador, pistones, quasi-conectividad) | v1.9 |
| **v1.12.0** | IA: goals + A\* + zombi/esqueleto/creeper + aldeanos básicos | v1.9 |
| **v1.13.0** | Combate 1.9+ (cooldown, crítico, barrido, escudo) + encantamientos base | v1.8 |
| **v1.14.0** | Capa de compatibilidad Iris/OptiFine (pases terrain/water/sky/composite/final) | v1.9 |
| **v1.15.0** | API de mods estilo Fabric (registro + eventos + entrypoints + aislamiento) | v1.12 |

Orden elegido por dependencias reales: los ítems y contenedores (v1.8) son prerrequisito del
crafter/vault/trial spawner y del horno; las secciones (v1.9) son prerrequisito de redstone (que
necesita `BlockState` por sección) y del worldgen nuevo.

---

## 8. Riesgos y límites (declarados de antemano)

| Riesgo | Impacto | Mitigación |
| --- | --- | --- |
| GLSL ES 3.00 vs packs de escritorio | Alto: la mayoría de packs no compilarán | Traductor + subconjunto de pases + mensajes claros (§6.2) |
| 700+ bloques y memoria | Medio: chunks de 128 KB | Secciones con paleta local + `Short` y meshing incremental |
| Redstone completo | Alto: muchas reglas sutiles | Tests por circuito desde el día 1; quasi-conectividad documentada |
| Worldgen multi-noise | Alto: fórmulas largas | Empezar por splines simplificados y calibrar con capturas públicas |
| Rendimiento en móvil | Medio | Presupuesto por frame medido en CI (benchmark) y en el dispositivo |
| Sin emulador en CI | Medio: errores runtime solo en el móvil | Log exportable + importación autodiagnóstica + tests puros en JVM (`:engine-*` testeable) |

**Nota sobre "réplica exacta":** con información pública se puede replicar comportamiento,
formatos, constantes y layout. Lo que no se puede es garantizar bit a bit el mismo terreno que
Java sin reimplementar exactamente su ruido y su router de densidad; el criterio de aceptación
realista es "mismo bioma, misma silueta y mismas estructuras para una semilla", no "mismo bloque
en la misma coordenada".

---

## 9. Referencias

- GUI y assets de interfaz: <https://minecraft.wiki/w/GUI>
- Física y transporte: <https://minecraft.wiki/w/Transportation>
- Redstone: <https://minecraft.wiki/w/Redstone_circuit>
- Formato Anvil: <https://minecraft.wiki/w/Anvil_file_format>
- Estados de bloque: <https://minecraft.wiki/w/Block_states>
- NBT: <https://wiki.vg/NBT_Class>
- Entidades e IA: <https://minecraft.wiki/w/Entity>
- Shaders Iris: <https://github.com/IrisShaders/Iris/wiki>
- Luz, opacidad, tick y fórmulas de ruptura: ver `docs/JAVA_PARITY.md`
