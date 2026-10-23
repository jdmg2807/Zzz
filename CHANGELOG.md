# Changelog

## v1.7.0 — Combate 1.9+, contenedores, redstone con retardos, 400 bloques y audio

### Física y combate
- **Combate de la 1.9**: cooldown por velocidad del arma, multiplicador de carga `0.2 + 0.8·carga²`
  (carga completa = `20 / velocidad` ticks), crítico ×1,5 al golpear en caída, barrido de espada
  con 1 de daño a los mobs a menos de 2 bloques y retroceso.
- **Minado con la fórmula real**: `dureza · 1,5 / velocidad` según herramienta y nivel; cada mena
  exige su pico (`hierro→piedra`, `oro/redstone/diamante→hierro`, `obsidiana/restos→diamante`) y
  sin el pico correcto no hay drop.
- **Durabilidad**: 59/131/250/1561 usos, desgaste solo con la herramienta adecuada y rotura al
  llegar al límite.
- **Caída y muerte**: HUD de daño, pantalla de muerte con puntuación, reaparición en el spawn y
  suelta de todo el inventario como entidades de objeto.

### Contenedores
- **Cofre, horno y yunque jugables**: pantallas de 176×168 y 176×166 con la geometría vanilla,
  horno a 20 Hz (200 ticks por pieza, tabla de combustibles, textura encendida con luz 13),
  persistencia en `containers.dat` junto al mundo.
- **Reglas del yunque**: combinación de durabilidad con el bono del 12 %, coste de niveles y
  penalización por trabajos previos.
- **Inventario y HUD con las medidas de Java**: corazones 10×9, hambre espejada, XP 182×5 con la
  curva real de niveles, hotbar 182×22, rejilla 2×2/3×3 y creativo 195×136 con pestañas.

### Mundo
- **Cuevas de ruido 1.18+**: cheese, spaghetti y noodle, más lava en el fondo.
- **Agua con 8 niveles** (manantial 0, flujo 1–7, caída 8, paso cada 5 ticks, secado del flujo no
  alimentado) y **arena/grava que caen** al quitar el bloque de debajo.
- **Estructuras**: mazmorra de adoquín musgoso con generador y cofres; casa de aldea con mesa de
  trabajo, horno, cofre y tejado con alero.
- **Paleta de ~400 bloques** generada con dureza, herramienta, luz y forma reales: 16 tintes × 5
  familias, 8 maderas completas, 30 familias de piedra, menas normales y de pizarra, la escalera
  de oxidación del cobre con 8 formas × 4 estados y los bloques de 1.21 (crafter, vault, trial
  spawner, copper bulb/grate, heavy core). Almacenamiento de bloques en 16 bits (formato VCX4).
- **Redstone**: cable con 16 niveles, antorchas inversoras, lámparas, pistones, observadores y
  **repetidores con retardo 1–4 ticks** (se cicla tocándolos), con cola temporal real.

### Sistemas
- **Entidades de objeto**: los bloques rotos caen, flotan, se atraen y se recogen; desaparición a
  los 6000 ticks.
- **Partículas**: polvo al romper/minar, nube al aterrizar, chispas de daño, humo de explosión.
- **Audio procedural**: pasos, minar, colocar, comer, daño y subida de nivel sintetizados al
  arrancar y mezclados con `SoundPool` (ajuste de sonido en Ajustes).
- **Chat y comandos**: `/give`, `/time`, `/tp`, `/heal`, `/gamemode`, `/help` y comandos de mods.
- **API de mods ampliada**: `registerItem`, `registerCommand`, `ItemDef`, `CommandHandler`.
- **Recetas derivadas de toda la paleta**: cada madera y cada familia de piedra tiene sus
  escaleras/losas/vallas, lana → alfombra, lingotes → bloques, cubos, tijeras y mechero.

## v1.6.1 — GUI Scale de Java, menú principal replicado y plan de refactorización 1.21
- **Sistema de GUI Scale** (`ui/GuiScale.kt`): la interfaz se dibuja en píxeles virtuales con la
  fórmula de Java (`clamp(min(ancho/320, alto/240), 1, 4)`). Es la corrección real del bug de
  maquetación: antes había ~600 dp de contenido (9 botones de 42 dp + título + padding) dentro de
  un viewport de ~360 dp, con un ancho fijo de 360 dp que además desbordaba en móviles estrechos,
  y tipografía en `sp` que crecía con la escala de fuente del sistema. Ahora todo encoge de forma
  uniforme y **no puede salirse de la pantalla**.
- **Menú principal como Java**: panorámica procedural con parallax y desenfoque, logo en
  `height/4`, splash aleatoria rotada −20° con pulso y los tres botones de Java (`200×20`, 4 px de
  separación): **Un jugador**, **Ajustes…**, **Salir del juego** (sin Multijugador ni Realms).
- **Pantalla «Seleccionar mundo»** al pulsar Un jugador: lista enmarcada, Jugar / Crear mundo
  nuevo / Cancelar y diálogo **Importar de Java** (mundo, texturas, shaders, mod o autodetección).
- **Ajustes → Escala de interfaz** (Auto, 1–4) y accesos a «Texturas y shaders instalados» y
  «Mods instalados», que ya no viven en el menú principal.
- **`docs/REFACTOR_PLAN_1.21.md`**: análisis del fallo de layout y plan paso a paso de los cinco
  subsistemas (GUI, físicas/redstone, mundo y +700 bloques, IA/A*, pipeline y mods), con hoja de
  ruta por versiones, riesgos y límites técnicos (GLSL ES 3.00 frente a packs Iris de escritorio).

## v1.6.0 — Importaciones arregladas, texturas de Faithful y paridad documentada
- **Importar mundos vuelve a funcionar de verdad**: la paleta de bloques se prepara antes de
  convertir los chunks. En v1.5.x la paleta estaba vacía al importar desde el menú, así que
  *todos* los bloques salían como «sin equivalente» y el mundo quedaba hueco
  (`Chunks convertidos: 169 · bloques: 0`). Ahora se registra la paleta y se listan los mapeos.
- **Bloques desconocidos → piedra** en vez de aire (salvo plantas, antorchas, raíles, etc.), así
  un mundo importado no aparece lleno de huecos.
- **Spawn y semilla de 1.21+**: se leen `SpawnX/Y/Z`, `spawn.pos`, `spawn_pos`, `Player.Pos` y
  `WorldGenSettings.seed`; si el save no trae spawn se centra en el primer chunk existente.
  También se registran las claves de `level.dat` para diagnosticar formatos futuros.
- **Importación con detección de tipo**: un mismo botón instala mundos, resource packs, shader
  packs y mods (detectado por el contenido del zip/jar, no por la extensión). El menú principal
  recupera botones separados para **texturas**, **shaders** y **mods**, y si eliges el archivo
  equivocado el mensaje dice qué es en realidad.
- **Texturas de Faithful 64x incluidas** (subconjunto de bloques que usa el motor, con permiso del
  usuario que las aportó): el juego ya no se ve con arte de programador. Cualquier pack del
  usuario se aplica después y tiene prioridad.
- **Shaders**: arreglado el error de enlace del agua
  (`uTime` con precisión distinta entre vertex y fragment): se declara `mediump` en ambos.
- **Cactus**: se dibuja como caja de 14/16 × 15/16 y **ya no abre un hueco** en el bloque de
  debajo (solo un cubo completo oculta la cara del vecino).
- **Nuevos bloques** para mundos reales: sillería, adoquín musgoso, deepslate, andesita, granito,
  diorita, tuff, calcita, arcilla, netherrack, hielo y hielo compacto.
- **Texturas animadas**: se usa el primer fotograma (antes el agua se veía como una tira
  aplastada de 32 fotogramas).
- **Mundo más alto (256)**: los mundos importados ocupan Minecraft y = −64…191, así que castillos
  y montañas ya no se cortan por arriba (antes la columna era de 144 bloques, es decir y ≤ 79).
  Los guardados anteriores se siguen leyendo (se alinean abajo).
- **`docs/JAVA_PARITY.md`**: plan de paridad 100 % con las reglas públicas de luz, tick, física,
  dureza, generación, estructuras y color de biomas, y qué está hecho / aproximado / pendiente.

## v1.5.1
- El mundo importado se guarda en la ranura que carga «Continuar partida» (con copia de
  seguridad de la partida anterior en `saves/mundo-anterior`).
- Spawn del mundo importado recalculado con el offset de altura y subiendo hasta un hueco libre.
- Al terminar la importación se entra al mundo automáticamente.
- Los shaders se recompilan siempre al recuperar el contexto OpenGL.

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
