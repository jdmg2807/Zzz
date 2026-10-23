# Fuentes y licencias del proyecto

Este documento deja por escrito **de dónde sale cada cosa** que hay en el motor. La regla es
simple: en este repositorio solo entra código propio o código/datos con una licencia que permita
usarlos y redistribuirlos, citando la fuente.

## Lo que sí se usa

| Fuente | Licencia | Qué se toma | Cómo se usa |
|---|---|---|---|
| **`InventivetalentDev/minecraft-assets`** (rama `1.21.9`) | Assets de Minecraft, publicados por ese repositorio | Texturas de bloque e ítem, modelos de ítem, `lang/es_es` y `en_us`, efectos de sonido | Se copian a `app/src/main/assets/vanilla/` y el motor los lee con `VanillaAssets` |
| **`PrismarineJS/minecraft-data`** | MIT | Tabla de recetas (patrón y resultado), datos de ítems | `tools/gen_recipes.py` genera `assets/vanilla/recipes.json`; el cliente Kotlin no incluye código de terceros |
| **`PrismarineJS/prismarine-physics`** | MIT | Modelo de movimiento del jugador (aceleración, inercia, salto, agua, escaleras) | Se usó como referencia de comportamiento; la implementación (`Player.kt`) es Kotlin propio |
| **Minecraft Wiki / wiki.vg** | Documentación pública | Fórmulas, constantes, formatos de archivo (región, `level.dat`, redstone, encantamientos) | Documentadas en `docs/JAVA_PARITY.md` |

Ninguna de estas fuentes contiene código de Mojang. Las tres primeras son redistribuibles con su
aviso; si en algún momento se incorpora código de una librería, hay que añadir aquí su LICENSE
completa.

## Lo que NO se usa: MCP / código descompilado de Minecraft

Se pidió usar `WangTingZheng/mcp940` (el *Minecraft Coder Pack* de la 1.12) como base del
proyecto. **No es posible y no se va a hacer**, por tres motivos:

1. **Es código con copyright de Mojang.** MCP no es código libre: es el resultado de descompilar
   el jar del juego con las mappings de la comunidad. Copiarlo a este repositorio público, y
   menos aún distribuir un APK construido a partir de él, es redistribuir el código de Mojang.
2. **El EULA de Minecraft** prohíbe ingeniería inversa y redistribución del juego o de partes de
   él; MCP940 existe para modificar el juego en local, no para publicar un motor derivado.
3. **Técnicamente no arrancaría en Android.** Ese código depende de LWJGL (OpenGL de escritorio),
   `java.awt`, `javax.swing`, hilos de escritorio y del propio jar del juego como *classpath*.
   Portarlo significaría reescribir toda la capa de plataforma, que es exactamente lo que este
   motor ya hace en Kotlin + OpenGL ES.

`mcsrc.dev` es el caso parecido: descarga el jar en el navegador y lo descompila allí. Su código
(el visor) es de FabricMC, pero el código que muestra es de Mojang.

## Entonces, ¿cómo se consigue la paridad?

La paridad no viene de copiar el código: viene de copiar **el comportamiento medido**. Para cada
sistema se busca el número o la regla pública (Wiki, wiki.vg, `minecraft-data`,
`prismarine-physics`, foros técnicos) y se implementa en Kotlin:

- física del jugador → constantes y orden de operaciones del tick (`docs/JAVA_PARITY.md` §12);
- recetas → tabla de datos generada, no código (`tools/gen_recipes.py`);
- bloques/ítems → ids, dureza y resistencia de la Wiki;
- mundo → formato de región Anvil de la Wiki (`McWorldImporter`).

Si un día el dueño del proyecto quiere licenciar el código bajo otra cosa, esta separación es la
que permite hacerlo sin arrastrar una violación de derechos de autor.
