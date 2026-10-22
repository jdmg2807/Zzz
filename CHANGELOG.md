# Changelog

## v1.5.0 — Importar mundos de Java, log exportable y bugs de cactus/antorcha
- **Importar mundos de Minecraft Java**: botón en el menú principal. Acepta el `.zip` del mundo
  (o una carpeta con `level.dat` + `region/`). Lector NBT + Anvil propio con soporte de:
  - 1.18+ (`sections[].block_states` con paleta y longs empaquetados)
  - 1.16–1.17 (`Level.Sections` con `Palette` + `BlockStates`)
  - 1.13–1.15 (empaquetado continuo antiguo)
  - 1.8–1.12 (`Blocks` byte array, ids clásicos)
  Importa un radio de 6 chunks alrededor del spawn, mapea los bloques a la paleta del juego y
  guarda el resultado como partida normal; luego se abre con «Continuar partida».
- **Mundo más alto (144)**: los mundos importados conservan cuevas y montañas (offset +64).
  El mallado solo recorre hasta el último bloque de cada chunk, así que no cuesta FPS.
  Los guardados antiguos se siguen leyendo (formato VCX1 → VCX2 con altura).
- **Log de diagnóstico**: todo queda registrado (arranque, texturas aplicadas/ignoradas, shaders
  con su log del compilador, mods, importación y errores) y se exporta automáticamente a
  **Descargas/VoxelCraft/voxelcraft-<fecha>.txt** al pausar, al importar y ante un crash.
  En Ajustes: «Guardar log en Descargas» y «Compartir log».
- **Cactus arreglado**: ahora es una caja de 14/16 como en Java (antes cubo completo) y las
  plantas de los packs ya no pueden convertirse en paredes verdes opacas (se rechazan texturas
  sin transparencia para plantas).
- **Antorchas (y flores, hierba alta, caña) ya se pueden romper**: el raycast solo golpeaba
  bloques sólidos, así que los bloques en cruz eran inmutables.

## v1.4.0 — Animales, cuevas y ruinas
- **Animales**: cerdos, ovejas, vacas y gallinas con modelos de cajas, colores propios, gravedad,
  paso automático, giro al chocar y deambular aleatorio. Aparecen sobre hierba según el bioma y
  solo se actualizan/dibujan cerca del jugador.
- **Cuevas 3D**: dos campos de ruido (túneles estrechos + cámaras amplias) tallados en la piedra,
  con oscuridad real gracias a la luz de cielo por profundidad.
- **Ruinas**: torres de cobblestone derruidas con suelo de tablones, huecos y una antorcha,
  generadas de forma determinista sin cruzar chunks.
- **Spawn seguro**: si una cueva abre la superficie el bloque de arriba se restituye, así no
  apareces en un agujero.

## v1.3.1 — Hotfix del frustum culling
- Corregida la extracción de planos del frustum (faltaba sumar la fila `w`), que en v1.3.0
  descartaba casi todos los chunks y hacía parecer que el mundo estaba vacío.
- Red de seguridad: si el culling rechazara todo, se dibuja el radio completo.

## v1.3.0 — Corrección de bugs visuales y rendimiento
- **Bloques invisibles / agujeros**: las caras de los bordes de chunk dependían del chunk vecino y no
  se recalculaban cuando este aparecía. Ahora se marcan sucios los 4 vecinos al generar un chunk.
- **Paleta de bloques guardada en la partida** (`level.dat` v2): al añadir bloques nuevos en
  versiones anteriores los ids se desplazaban y los mundos guardados mostraban bloques
  equivocados o invisibles. Ahora se remapea por nombre al cargar (los saves antiguos avisan).
- **Triángulos gigantes tras pausar**: al perderse el contexto EGL los VBOs quedaban inválidos y se
  dibujaba basura. Ahora se recrean texturas, shaders y todas las mallas; también se libera el
  mundo al volver al menú.
- **Texturas de packs**: solo se aplican texturas reales de bloque (`textures/block|blocks`), se
  ignoran overlays/partículas/GUI y las imágenes totalmente transparentes; el atlas pasa de
  256 a 1024 huecos, así un pack como Faithful 64x ya no lo desborda ni deja bloques sin textura.
- **Rendimiento**: caché de fuentes de luz por chunk (antes se escaneaban 9 chunks por malla),
  descarte por frustum por chunk, generación/mallado con presupuesto de 6 ms por frame y
  liberación temprana de mallas lejanas sin perder las construcciones del jugador.
- **Ajustes**: interruptores para resource packs, shaders y mods (se aplican al entrar al mundo).
- **Menú estilo Minecraft Java**: botones de piedra con borde y sombra, texto monoespaciado con
  sombra, fondo de panorama (cielo, sol, colinas de bloques), splash amarillo, versión y aviso legal.
- **Generación**: cuevas 3D (túneles + cámaras), ruinas de cobblestone con antorcha y spawn seguro
  para no aparecer dentro de una cueva.
- Bloque en la mano más pequeño y mejor encuadrado.

## v1.2.0 — Iluminación y sensación de "primera persona"
- Luz de cielo por profundidad: las cuevas y el interior de construcciones se oscurecen.
- Luz de bloque real (antorcha 14, glowstone 15) con caída por distancia.
- Bloque nuevo: **antorcha** (sprite con llama, ilumina de noche).
- **Bloque sostenido en la mano** con sombreado 3D, balanceo al caminar y animación de golpe al
  colocar/romper.
- **Minar manteniendo pulsado** con 6 etapas de grietas; el toque corto sigue colocando.
- Atributos de luz nuevos en el shader (`aSky`, `aBlock`) y uniform `uDay`; el agua también
  responde al día/noche.
- Alcance de interacción 5 bloques (como el creativo de Java).

## v1.1.0 — Biomas, vegetación y cielo
- 6 biomas con colores propios de hierba, hojas y agua: llanura, bosque, nevado, desierto,
  taiga y jungla.
- Árboles por bioma: roble, abedul, pino (copa cónica por capas), jungla; cactus, arbustos
  secos, caña de azúcar junto al agua, hierba alta y flores.
- Oclusión ambiental (AO) por vértice con giro de triángulos para suavizar el gradiente.
- Cielo nuevo: nubes a la deriva, disco solar, luna con fase y estrellas nocturnas.
- Agua nueva: fresnel, brillo especular del sol, oleaje y superficie 2/16 más baja.
- Iconos isométricos de bloque en el hotbar y en el selector (adiós a los cuadros planos).
- Botón de esprintar (**»**).
- Los packs de shaders de **Iris/OptiFine** (BSL, Complementary…) se detectan y se omiten con
  aviso claro en lugar de llenar el chat de errores de compilación.

## v1.0.0 — Primera versión jugable
- Motor voxel propio en Kotlin + OpenGL ES 3.0: chunks 16×16×80, atlas, niebla, culling.
- Mundo infinito procedural con menas, lagos y árboles; guardado del mundo y del jugador.
- Resource packs de Minecraft Java (modernos y legacy) y shader packs GLSL ES 3.0 con fallback.
- Mods en .jar con API Java propia (`modkit`) cargada con DexClassLoader.
- UI Compose: menú, mods, texturas/shaders, ajustes, HUD y controles táctiles.
- GitHub Actions compilando APK debug/release + mod de ejemplo en cada push.

---

## Próximos pasos (hacia Minecraft Java)
1. **Inventario real**: pantalla con pestañas, arrastrar objetos, crafteo 3×3 y supervivencia
   (vida, hambre, caídas, ahogarse).
2. **Sonido**: pasos por material, romper/colocar, ambiente, música procedural.
3. **Mobs y entidades**: generación, IA básica, drops; cerdos/ovejas/zombis.
4. **Ciclo de tiempo guardado y clima**: lluvia/tormenta con partículas, nieve en biomas fríos.
5. **Mundo más rico**: cuevas 3D reales, cavernas, menas por altura, estructuras (aldeas).
6. **Luz propagada por BFS** de 15 niveles con suavizado completo entre bloques.
7. **Optimización**: meshing en hilos, frustum culling por chunk, atlas más grande.
8. **Más compatibilidad**: lector de packs con `model/*.json`, shaders con `#version 330`
   traducidos automáticamente a ES 3.0.
