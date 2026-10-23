# Assets de Minecraft Java Edition

Texturas de bloque e ítem, modelos de ítem, nombres en español e inglés y efectos de sonido
tomados del repositorio público **InventivetalentDev/minecraft-assets**
(rama `1.21.9`), que publica los assets de Minecraft Java Edition.

- `block/` — texturas de bloques (PNG 16x16 o animadas).
- `item/` — texturas de ítems.
- `models_item/` — modelos de ítem (`models/item/*.json`).
- `lang/` — `en_us.json` y `es_es.json` para los nombres.
- `sounds/` — efectos de sonido (`*.ogg`), organizados como en el juego original.

El motor lee estos archivos directamente del APK (`VanillaAssets.kt`). Los resource packs y
shader packs que importe el jugador se aplican **después** y tienen prioridad.

La tabla de recetas (`recipes.json`) se genera con `tools/gen_recipes.py` a partir de
**minecraft-data** (licencia MIT, PrismarineJS): son datos de recetas, no código del juego.

Minecraft es una marca de Mojang Studios. Este proyecto no está afiliado a Mojang ni a
Microsoft; los assets se usan únicamente como contenido de un motor propio compatible.
