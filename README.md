# VoxelCraft

Un motor de Minecraft **hecho desde cero para Android** (Kotlin + OpenGL ES 3.0), que carga
**resource packs de texturas de Minecraft Java**, **shaders GLSL** y **mods en Java** propios.
GitHub Actions compila el APK automáticamente.

**La interfaz es la de Bedrock Edition** (menú, HUD, controles táctiles, inventario) y la
**mecánica es la de Java** (física por ticks, generación, redstone, crafteo, contenedores, mobs).

> No es Minecraft oficial ni contiene código ni assets de Mojang. Es un motor independiente
> con estética voxel que *lee* los packs que ya tengas en tu teléfono.

---

## Descargar el APK

Cada push a este repositorio compila el juego:

1. Abre la pestaña **Actions** → workflow **Build Android APK**.
2. Entra a la ejecución más reciente y descarga el artefacto **VoxelCraft-APK**.
3. Dentro hay dos APK:
   - `VoxelCraft-<n>-release.apk` → el juego (~57 MB, incluye la banda sonora).
   - `VoxelCraft-<n>-debug.apk` → misma app (`com.arena.voxel`) con la misma clave de firma, así
     que **se instala como actualización** de la versión anterior, sin duplicar el icono.
   - `examplemod.jar` → mod de ejemplo listo para importar desde el juego.

También puedes crear un tag (`v1.0.0`) y el APK se publica como **GitHub Release**.

```bash
# instalar por ADB
adb install -r VoxelCraft-release.apk
```

Requisitos: Android 7.0+ (API 24) con OpenGL ES 3.0.

## Controles táctiles (estilo Bedrock)

| Acción | Gesto |
|---|---|
| Moverse | Joystick en la esquina inferior izquierda (aparece donde apoyas el dedo) |
| Esprintar | Doble toque adelante en el joystick |
| Mirar | Arrastrar cualquier zona libre de la pantalla |
| Saltar | Botón ▲ (doble toque en creativo = volar) |
| Agacharse / bajar volando | Botón ▼ |
| Subir / bajar volando | Botones ⤒ / ⤓ en creativo |
| Colocar bloque | Toque rápido en el mundo |
| Minar | Mantener pulsado |
| Inventario | Botón `···` a la derecha de la hotbar |
| Chat / comandos | Botón `T` |
| Pausa | Botón `II` |

## Qué incluye

| Sistema | Estado |
|---|---|
| Mundo infinito por chunks (16×16×256 bloques) con ruido procedural | ✅ |
| 6 biomas (llanura, bosque, nevado, desierto, taiga, jungla) con colores propios | ✅ |
| **Cuevas de ruido 1.18+** (cheese, spaghetti, noodle) y lava en el fondo | ✅ |
| **Estructuras**: ruinas con antorcha, mazmorra con generador y cofre, casa de aldea | ✅ |
| Animales: cerdos, ovejas, vacas y gallinas con IA de deambular | ✅ |
| **Mobs hostiles**: zombi, esqueleto y creeper con A*, daño y explosión | ✅ |
| **Combate 1.9**: cooldown, carga, crítico, barrido y retroceso | ✅ |
| **Durabilidad de herramientas** y nivel de pico exigido por mena | ✅ |
| **~400 bloques** con dureza, herramienta, luz y forma reales (incluidos los de 1.21) | ✅ |
| **Cofre, horno y yunque** con sus pantallas y persistencia | ✅ |
| **Redstone**: cable de 16 niveles, repetidores con retardo 1–4, lámparas, pistones, observadores | ✅ |
| **Agua con 8 niveles de flujo** y arena/grava que caen | ✅ |
| **Entidades de objeto**: los drops caen y se recogen | ✅ |
| **Partículas y audio procedural** (pasos, minar, colocar, comer, daño, nivel) | ✅ |
| **Chat y comandos** (`/give`, `/time`, `/tp`, `/heal`, `/gamemode`, `/help`, comandos de mods) | ✅ |
| Árboles por bioma (roble, abedul, pino, jungla), cactus, caña de azúcar, plantas | ✅ |
| Oclusión ambiental (AO) por vértice y sombreado por cara | ✅ |
| Iluminación: luz de cielo (cuevas oscuras) + luz de bloque (antorcha, glowstone) | ✅ |
| Bloque en la mano con animación de golpe y balanceo al caminar | ✅ |
| Minado al mantener pulsado con grietas progresivas | ✅ |
| Cielo con nubes a la deriva, sol, luna con fase y estrellas nocturnas | ✅ |
| Agua con fresnel, brillo del sol, oleaje y superficie 2/16 más baja | ✅ |
| Render por chunks con atlas de texturas, niebla suave y culling de caras | ✅ |
| Física del jugador: colisión AABB, salto, natación, vuelo creativo | ✅ |
| Colocar / romper bloques con raycast (toque y pulsación larga) | ✅ |
| Guardado automático del mundo (chunks comprimidos + jugador) | ✅ |
| **Resource packs de Minecraft Java** (.zip o carpeta, modernos y legacy) | ✅ |
| **Shader packs** (reemplazan terrain/water/sky/post con GLSL ES 3.0) | ✅ |
| **Mods en .jar** con API Java (bloques, texturas, HUD, mundo, chat) | ✅ |
| Iconos isométricos de bloque en el hotbar y en el selector | ✅ |
| Selector de bloques (también muestra los bloques de los mods) | ✅ |
| Interfaz Compose: menú, mods, packs, ajustes, HUD táctil | ✅ |

## Controles

- **Joystick izquierdo**: moverte.
- **Arrastrar en pantalla**: mirar alrededor.
- **Toque corto**: colocar el bloque seleccionado.
- **Mantener pulsado**: minar el bloque apuntado (grietas y rotura continua).
- **↥**: saltar / subir volando. **↧**: bajar volando. **✈**: activar vuelo creativo.
- **⛏ / ■**: romper / colocar (botones dedicados).
- **»**: esprintar (mantener). **▦**: selector de bloques (incluye los bloques añadidos por mods) para la ranura activa.
- **II** (arriba a la derecha): pausa, guardar y salir.

## Carpetas del juego

En el teléfono:

```
Android/data/com.arena.voxel/files/
├── mods/            ← tus .jar de mods
├── resourcepacks/   ← packs de texturas (.zip o carpeta)
├── shaderpacks/     ← shader packs (.zip o carpeta)
└── saves/mundo1/    ← partida guardada
```

También puedes importarlos desde las pantallas **Mods** y **Texturas / Shaders** con el
selector de archivos (no hacen falta permisos).

## Resource packs compatibles

Se detectan automáticamente estos formatos:

```
MiPack.zip
└── assets/minecraft/textures/block/stone.png        (Java moderno)
└── assets/minecraft/textures/blocks/stone.png       (legacy)
└── shaders/post.fsh                                 (opcional)
```
o packs sueltos con `textures/block/*.png` en la raíz.

Se aplican por nombre: `grass_top`, `dirt`, `stone`, `cobblestone`, `oak_planks`, `sand`,
`gravel`, `log_side`, `log_top`, `leaves`, `water`, `glass`, `snow`, `bedrock`, `coal_ore`,
`iron_ore`, `gold_ore`, `diamond_ore`, `brick`, `obsidian`, `tnt_side`, `tnt_top`,
`glowstone`, y alias legacy (`grass_block_top`, `oak_log`, `water_still`, `bricks`, …).
Las texturas se escalan al tamaño del atlas, así que también valen packs HD (32×, 64×…).

Ejemplo listo para probar: `samples/ResourcePackEjemplo/` (hierba de otoño, piedra helada,
agua tropical). Cópialo a `resourcepacks/` o comprímelo en .zip.

## Shaders

Los shaders integrados son:

| Archivo | Función |
|---|---|
| `terrain.vsh/fsh` | bloques opacos (UV, luz, niebla) |
| `water.vsh/fsh` | agua animada |
| `sky.vsh/fsh` | cielo + sol |
| `post.vsh/fsh` | pase de pantalla completa (color grading, viñeta, agua) |

Un shader pack puede reemplazar cualquiera de ellos con el mismo nombre. Uniforms
disponibles: `uMVP`, `uCam`, `uFogColor`, `uFog`, `uLightColor`, `uTime`, `uSunDir`,
`uZenith`, `uHorizon`, `uSunColor`, `uScene`, `uUnderwater`, `uResolution`.

En el juego: **Texturas / Shaders → Exportar shaders de ejemplo** escribe el pack integrado
completo en `shaderpacks/mis_shaders/` para editarlo. También hay un ejemplo de post-proceso
sepia en `samples/ShaderPackEjemplo/`.

Si un shader del pack no compila, el juego avisa una sola vez en el chat y usa el integrado
(nunca se rompe el arranque).

Los packs de **Iris/OptiFine** (BSL, Complementary, SEUS…) se detectan automáticamente y se
omiten con un aviso en la pantalla de packs: usan `gbuffers_*`, `composite`, `deferred`, pases
de sombra y `#version 330`, un pipeline distinto al de este motor. Sus texturas sí se aplican.

## Mods

Un mod es un **.jar** con un `voxelmod.json` y una clase que implementa
`com.arena.voxel.api.VoxelMod`:

```json
{
  "id": "examplemod",
  "name": "Example Mod",
  "version": "1.0.0",
  "author": "tu nombre",
  "entrypoint": "com.example.voxelmod.ExampleMod"
}
```

```java
public class ExampleMod implements VoxelMod {
    @Override public void onLoad(ModContext ctx) {
        ctx.registerBlock(new BlockDef("example_lamp")
            .color(0xFFD966, 0xE8B23A, 0xB07A20));
        ctx.chat().broadcast("¡Mod cargado!");
    }
    @Override public void onTick(float dt) {
        ctx.player().setHudText("Hola desde el mod");
    }
}
```

La API (`modkit/`) ofrece:

- `ModContext`: `world()`, `player()`, `chat()`, `registerBlock()`, `registerTexture()`, `readResource()` (archivos dentro del jar), `getLogger()`.
- `WorldAPI`: `getBlock`, `setBlock`, `fill`, `isSolid`, `getHighestBlockY`, `getSeed`, `getTime`.
- `PlayerAPI`: posición, `teleport`, `setFlying`, `setHudText`.
- `ChatAPI`: mensajes en el chat/toast del juego.
- `VoxelMod`: `onLoad`, `onWorldReady`, `onTick`, `onBlockPlaced`, `onBlockBroken`.

El mod de ejemplo completo está en `examplemod/` (añade dos bloques, uno con PNG propio,
construye una plataforma y muestra un HUD). Compílalo con:

```bash
gradle :examplemod:jar      # → examplemod/build/libs/examplemod.jar
```

e impórtalo desde la pantalla **Mods** (o cópialo a `mods/`).

> Los mods de Forge/Fabric **no** son compatibles: usan código de Minecraft y Mixin. Este
> motor define su propia API para que puedas escribir mods en Java sin Minecraft.

## Importar contenido de Minecraft Java

El menú principal tiene un botón por tipo de archivo, y todos los importadores **detectan el
contenido real** del zip/jar (la extensión no basta: mundos, packs y mods son todos zips):

| Botón | Acepta |
| --- | --- |
| **Importar mundo de Java** | `.zip` con `level.dat` + `region/` (o la carpeta del mundo) |
| **Importar texturas** | resource pack `.zip` (`assets/minecraft/textures/…` o `textures/block/…`, también suelto sin `pack.mcmeta`) |
| **Importar shaders** | shader pack `.zip` con `shaders/*.vsh` y `*.fsh` |
| **Importar mod** | `.jar` con `voxelmod.json` + clases que implementen `VoxelMod` |

Si eliges el archivo equivocado el mensaje dice qué es en realidad («parece un RESOURCE PACK de
texturas…»), así no se pierde tiempo adivinando.

Al importar un mundo, el juego:

1. Detecta la versión (1.8 → 1.21+) leyendo `level.dat` (NBT) y registra todas sus claves.
2. Saca spawn y semilla de `SpawnX/Y/Z`, `spawn.pos`, `spawn_pos`, `Player.Pos` o
   `WorldGenSettings.seed` (los saves nuevos mueven estos tags); si no hay spawn usa el primer
   chunk existente.
3. Lee las regiones `.mca` (Anvil, zlib/gzip/sin comprimir) y decodifica las paletas de bloques,
   incluidas las empaquetadas en longs (1.13–1.15 continuas, 1.16+ alineadas, 1.8–1.12 clásicas).
4. Convierte un radio de 6 chunks alrededor del spawn y **mapea los bloques de Java** a la paleta
   del juego: alias exactos, luego heurísticas por material, y lo desconocido que no es un bloque
   parcial se dibuja como piedra (nunca deja huecos). Todo queda en el log.
5. Guarda el resultado como partida normal, respalda la anterior en `saves/mundo-anterior` y la
   abre automáticamente.

### Texturas incluidas

El APK trae un pack integrado (`assets/builtinpack`) con las texturas de bloque de
**Faithful 64x** aportadas por el usuario del proyecto — 50 texturas que cubren todos los bloques
del motor. Es un subconjunto: solo los PNG que el motor usa de verdad. Crédito al
[Faithful Resource Pack](https://faithfulpack.net) (CC BY-SA 4.0). Cualquier pack que instales se
aplica después y tiene prioridad.

## Logs

Todo lo que pasa se escribe en un archivo de texto, incluidos los errores de shaders con el log
del compilador y los bloques de Java que no tienen equivalente:

```
Descargas/VoxelCraft/voxelcraft-<fecha>.txt
```

Se guarda automáticamente al pausar, al importar algo y ante un crash. También puedes forzarlo
desde **Ajustes → Guardar log en Descargas** o enviarlo con **Ajustes → Compartir log**.

## Documentación técnica

- `docs/JAVA_PARITY.md` — reglas públicas de luz, tick, física, dureza, generación y color de
  biomas, con estado ✅/🟡/⬜ de cada una.
- `docs/REFACTOR_PLAN_1.21.md` — plan de refactorización de los cinco subsistemas (GUI, físicas y
  redstone, mundo y +700 bloques, IA con A*, pipeline gráfico y mods), hoja de ruta por versiones y
  riesgos técnicos.

## Historial de versiones

Ver [CHANGELOG.md](CHANGELOG.md). La versión actual es la **v1.7.0**, que reúne el trabajo de
paridad con Java 1.21: GUI con escala real, HUD e inventarios con la geometría vanilla, combate
1.9, contenedores, redstone con retardos, cuevas de ruido, estructuras, ~400 bloques, física de
fluidos y entidades de objeto, partículas, audio y comandos.

## Compilar tú mismo

Requisitos: JDK 17, Android SDK 34, Gradle 8.7 (o Android Studio, que añade el wrapper).

```bash
gradle :app:assembleDebug        # APK debug
gradle :app:assembleRelease      # APK release firmado con la debug key
gradle :examplemod:jar           # mod de ejemplo
```

El workflow `.github/workflows/android-build.yml` instala el SDK, compila ambos APK y sube
los artefactos en cada push.

## Estructura

```
app/src/main/java/com/arena/voxel/
├── Game.kt                 estado global, chat, guardado, ajustes
├── MainActivity.kt         actividad única + Compose
├── core/                   contexto, carpetas, log
├── textures/               tiles procedurales + atlas
├── packs/                  resource packs y shaders (zip/carpeta)
├── world/                  bloques, chunks, generación, guardado
├── player/                 física, cámara, raycast
├── render/                 OpenGL ES 3.0: chunks, cielo, agua, post
├── mods/                   DexClassLoader + implementación de la API
└── ui/                     menú, HUD y controles táctiles

modkit/                     API pública para mods (Java)
examplemod/                 mod de ejemplo (.jar)
samples/                    pack de texturas y shader de ejemplo
```

## Limitaciones conocidas / ideas

- No hay crafteo, inventario completo ni mobs (es un motor creativo).
- Iluminación por bloque simplificada (sombra por cara + luz de día), sin ray tracing.
- Los shaders usan GLSL ES 3.0: los `.vsh/.fsh` de Optifine/iris con `#version 330` o
  uniforms `gbuffers_*` no compilan tal cual; adapta el código o usa los integrados como base.
- Guardado: un solo mundo (`mundo1`). Se puede ampliar a varias ranuras.
- Sin inventario/crafteo todavía (selector creativo por ahora).
- La luz es aproximada: cielo por profundidad bajo la superficie y luz de bloque por distancia
  a las fuentes (no hay propagación BFS de 15 niveles como en Java, ni suavizado completo).
- Packs de shaders tipo OptiFine/Iris no soportados (ver arriba).

Hecho con cariño para jugar en el móvil. Los PRs con nuevos mods, packs o mejoras del motor
son bienvenidos.
