# Interfaz Bedrock sobre mecánica Java

Documento de referencia de la fase 2.0: **qué se copia de Bedrock Edition y qué se mantiene de
Java Edition**, con las fuentes de comportamiento usadas (Minecraft Wiki, no código de Mojang).

## Reparto de responsabilidades

| Capa | Edición de la que viene |
|---|---|
| Menú principal, ajustes, HUD, inventario, controles táctiles, tipografía | **Bedrock** |
| Física por ticks, generación del mundo, biomas, cuevas, estructuras | Java |
| Redstone, crafteo, contenedores, mobs, TNT, durabilidad, XP | Java |
| Formatos de mundo (`level.dat` + regiones), resource packs, shaders | Java |
| Mods (`.jar` + `modkit`) | Java |

## Controles táctiles

`BedrockMetrics` calcula **una sola vez** todas las posiciones como fracciones de la pantalla y
la usan los dos consumidores:

* `render/GameSurfaceView.kt` → `onTouchEvent` (píxeles),
* `ui/BedrockHud.kt` → dibujo del HUD (dp).

Ambos alimentan `ui/BedrockInput.kt`, el controlador único:

```
Touch DOWN ─┬─ hotbar / ··· / T / II / ▲ / ▼ / ⤒ / ⤓ / ✈ ───────────► acción directa
            ├─ zona inferior izquierda ─► joystick analógico (Game.move[0..1])
            └─ resto de la pantalla ────► cámara (Game.addLook) + pressActive
```

* `Game.pressActive` lo interpreta el renderer: **toque corto = colocar**, **mantener = minar**
  con grietas progresivas (> 0,2 s).
* Arrastrar la cámara incrementa `Game.pressSeq` al soltar, así que un giro nunca coloca bloques.
* `Game.uiBlocking` es la frontera: mientras un panel de Compose está abierto, el view devuelve
  `false` y los toques van al panel. Esa es la pieza que antes rompía el juego, porque
  `setOnTouchListener { _, _ -> true }` se tragaba todos los eventos.

## Geometría Bedrock usada

```
hotbar        9 ranuras de 0,085·alto (máx. 0,078·ancho) centradas abajo, marco blanco
              en la seleccionada + botón ··· a la derecha
corazones     10 sprites encima de la hotbar, alineados a su borde izquierdo; hambre a la derecha
XP            barra verde justo encima de la hotbar, nivel centrado
botones       ▲ saltar (grande), ▼ agachar/descender, ⤒ ⤓ volar, ✈ alternar vuelo, abajo a la
              derecha; doble toque en ▲ = volar (creativo)
joystick      aparece donde apoyas el dedo en la esquina inferior izquierda
```

## Inventario

* Supervivencia: panel oscuro 178×168 GUI px, crafteo 2×2 + resultado, modelo del personaje,
  rejilla 3×9, separador y fila de hotbar.
* Mesa de trabajo: crafteo 3×3 con el mismo lenguaje visual.
* Creativo: pestañas laterales con icono + página 9×5, pulsar coge una pila completa.

## Audio

Seis pistas ambientales propias (`assets/music`) generadas por `tools/gen_music.py`: acordes
sostenidos, notas pentatónicas con caída exponencial, ruido de aire y eco. `core/Music.kt` las
reproduce con `MediaPlayer` desde el APK (los `.wav` van sin comprimir en el APK para que
`AssetManager.openFd` funcione).

## Firma y actualizaciones

`keystore/voxelcraft.p12` (PKCS12, alias `voxelcraft`) está en el repositorio y es la clave de
todas las compilaciones: release y debug comparten `applicationId com.arena.voxel` y la misma
clave, así que cada APK se instala **encima** del anterior. El workflow publica en el issue #1 la
huella SHA-256 del certificado y el tamaño del APK en cada compilación.
