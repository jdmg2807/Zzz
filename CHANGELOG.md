# Changelog

## v3.2.0 — VoxelForge 3D 1.2.0: estudio de modelado con IA que trabaja sobre la escena
- **Chat que modela de verdad**: el asistente ya no solo genera primitivas; pide **herramientas**
  (`extrude`, `bevel`, `boolean`, `subdivide`, `sculpt`, `mirror`, `join_objects`,
  `split_components`…) con argumentos validados (tipo, rango, límite de caras) que la app ejecuta
  sobre la escena. El modelo nunca ejecuta código y las operaciones destructivas piden
  confirmación. Fuera del alcance: la IA **no** genera mallas orgánicas de alta calidad ni
  texturas; eso necesitaría un servicio externo aparte (ver `docs/ARQUITECTURA_VOXELFORGE.md`).
- **Motor de geometría propio** (`geom/`): mallas editables con caras de n lados, extrusión
  (por región y por cara), inset, bisel, Catmull-Clark, suavizado, simetría, decimación,
  booleanas BSP (unión/diferencia/intersección), barrido de perfiles por curvas Bézier y
  Catmull-Rom, pincel de esculpido (inflar/suavizar/pellizcar) y validación/reparación de mallas.
- **Interfaz rediseñada**: barra superior con deshacer/rehacer/guardar/ajustes, visor a pantalla
  completa con superposiciones (modos sólido/alámbrico/material, cámara, pincel), panel inferior
  plegable con pestañas Escena/Crear/Editar/Material/Animar/Chat/Archivo/Historial, e historial de
  cambios legible.
- **Deshacer y rehacer** de 20 pasos sobre estados completos de la escena (también deshace lo que
  hizo la IA).
- **Catálogo de modelos con precios reales**: entrada y salida por millón de tokens, contexto,
  capacidades, fecha de actualización, filtros Todos/Gratis/De pago/Precio desconocido y aviso de
  datos desactualizados si el proveedor falla. Sin modelos escritos a mano en el APK.
- **Claves cifradas** con el Android Keystore (AES-256-GCM, clave no exportable) en lugar de
  `ajustes.json` en texto plano; aviso y degradación controlada si el dispositivo no tiene
  Keystore.
- **Exportación verificada**: cada archivo se vuelve a leer y se comprueba que la geometría está
  antes de decir «exportado»; si la verificación falla, se informa del motivo.
- **Pruebas automáticas** (66 en la JVM, `:modeler:testDebugUnitTest`) del motor de geometría,
  escena, formatos de archivo y ejecutor de herramientas, integradas en el workflow. Gracias a
  ellas se corrigieron errores reales en las booleanas (el BSP vaciaba la lista antes de
  recortarla y el resultado soldaba vértices por un hash de posición), en la extrusión de objetos
  cerrados, en el espejo, y en la lectura de FBX ASCII y PLY binario.
- Versión de la app: 1.2.0 (versionCode 3).

## v3.1.0 — Cualquier modelo, con filtro gratis/de pago, y APK suelto
- **Catálogo de modelos en vivo**: la app descarga la lista real de OpenRouter (`/api/v1/models`)
  y de Google AI Studio (`/v1beta/models`) con tu clave, así que sirve **cualquier modelo
  disponible**, incluso uno publicado después de este APK. Se guarda en caché para elegir sin
  conexión.
- **Filtro Gratis / De pago** en el selector: OpenRouter usa sus precios reales por millón de
  tokens (los `:free` y los de coste 0 quedan en «Gratis»); Google marca Flash/Flash-Lite/Gemma
  como nivel gratuito y Pro como de pago. Incluye buscador, tamaño de contexto y campo para
  escribir un id exacto.
- **Arreglo del error «ese modelo ya no está disponible»**: el mensaje ahora explica el problema
  y la app **actualiza la lista automáticamente** para elegir otro modelo de un toque.
- **APK sin zip y sin el juego**: el workflow `Build VoxelForge APK` compila solo `:modeler` y el
  Release publica un único archivo `VoxelForge-<n>.apk`. El juego (VoxelCraft) queda terminado y
  fuera de la compilación automática.
- Versión de la app: 1.1.0 (versionCode 2).

## v3.0.0 — VoxelForge 3D: modelado, animación e IA
- **App nueva en el mismo repositorio** (módulo `modeler/`, `com.arena.modeler`): se instala y
  actualiza aparte del juego, con su propio icono y su propia firma (`keystore/modeler.p12`).
- **Modelado**: cubo, esfera, cilindro, cono, toro, plano e icósfera; jerarquía, duplicar,
  ocultar, borrar, y edición numérica de posición/rotación/escala.
- **Visor OpenGL ES 3.0 propio**: órbita con un dedo, paneo con dos, zoom con pellizco,
  selección por rayo, gizmo de traslación por ejes, cuadrícula, wireframe y huesos.
- **IA dentro de la app** con **OpenRouter** o **Google AI Studio** (clave del usuario): genera
  el modelo desde una descripción o modifica el actual, con un esquema JSON propio que admite
  primitivas, mallas y animaciones.
- **Formatos**: lectura y escritura de glTF/GLB, FBX ASCII, OBJ+MTL, STL (texto/binario) y PLY
  (texto/binario). La animación sobrevive en GLB/glTF/FBX; mensajes claros para FBX binario,
  `.blend`, `.dae`, `.3ds` y paquetes `.3mf/.zip/.usdz`.
- **Archivos**: exportación a `Descargas/VoxelForge`, proyectos locales en GLB, autoguardado y
  deshacer (12 pasos).
- **CI**: el workflow compila `:modeler:assembleDebug/Release` y publica
  `VoxelForge-<n>-release.apk` y `VoxelForge-<n>-debug.apk` en el artefacto y en el Release.

## v2.2.1 — Ajustes con el diseño de Bedrock
- **Pantalla de Ajustes reconstruida**: barra superior con flecha de volver, panel oscuro con
  secciones (controles, interfaz, sonido, contenido, diagnóstico), deslizadores de piedra con
  relleno verde como los de Bedrock (sensibilidad, campo de visión, distancia de render y escala
  de interfaz) e interruptores SÍ/NO en lugar de los de Material.
- Se añade `BedrockSlider`, el deslizador que faltaba en la biblioteca visual del juego.

## v2.2.0 — Mundo nuevo y propiedades reales de los bloques
- **Generación de mundo reescrita**: continentalidad, erosión, crestas y ríos como el generador
  moderno del juego. Ahora hay continentes, océanos de verdad, costas, montañas que llegan a ~150
  bloques y ríos que bajan el terreno. El nivel del mar pasa a y = 63 (el del juego).
- **Biomas corregidos**: el clima se normaliza (el ruido salía con media −0,34, por eso el 80 % del
  mapa era nevado); reparto medido: llanura 35 %, bosque 27 %, taiga 15 %, nevado 9 %, desierto 3 %,
  jungla 1 % de la tierra.
- **Cuevas** cheese/spaghetti/noodle con salidas al exterior y 5,75 % de aire subterráneo; **menas**
  en bolsas con distribución por altura (carbón arriba, diamante bajo y 14) y ≈3 % del volumen.
- **Superficie por bioma**: hierba+tierra, arena+arenisca, nieve y línea de nieve, grava en fondos
  profundos, piedra en los picos, deepslate bajo y 8; hielo en el agua de los biomas fríos.
- **1165 bloques con sus propiedades reales** (`blocks.json` desde `minecraft-data`): dureza,
  resistencia a explosiones, luz emitida, herramienta y nivel mínimo, apilado. El tiempo de picado
  usa la fórmula del juego (dureza × 1,5 s con la herramienta correcta, × 5 s sin ella) y los
  multiplicadores reales (madera 2, piedra 4, hierro 6, diamante 8, netherita 9).
- **Pantalla "Mundos" reconstruida con el diseño de Bedrock**: barra superior con flecha y título,
  panel oscuro con tarjetas de mundo (miniatura, nombre, datos y botón Jugar) y barra inferior de
  acciones; los diálogos de importación y de resultado usan el mismo lenguaje visual.

## v2.1.2 — 1660 recetas de vanilla
- **Tabla de recetas completa**: se generan 1660 recetas (con forma y sin forma) desde los datos
  públicos de `minecraft-data` (MIT) con `tools/gen_recipes.py`. Antes había un puñado escritas a
  mano; ahora la mesa de trabajo, el horno y el inventario 2×2 aceptan las recetas del juego.
- **Familias de materiales**: como los datos traen las etiquetas resueltas a un solo miembro
  (`#planks` llega como `acacia_planks`), el motor considera intercambiables cualquier tablón,
  cualquier tronco/tallo y cualquier lana, como en el juego.
- **Las recetas de un mod siguen ganando**: se registran antes que las de vanilla.
- Documento `docs/FUENTES_Y_LICENCIAS.md` con el origen y la licencia de cada cosa.

## v2.1.1 — Física real de Java Edition
- **Movimiento reescrito con el modelo del juego**: la velocidad ya no persigue un objetivo, se
  suma aceleración (0,1 andando / 0,13 esprintando en suelo, 0,02 en el aire) y después se aplica
  la inercia (0,546 en suelo, 0,91 en el aire). Resultado: 4,317 / 5,612 / 1,295 b/s y la misma
  sensación de arranque y frenada que en Java.
- **Salto correcto**: 0,42 hacia arriba (ápice 1,252 bloques) y el impulso de 0,2 horizontal solo
  al esprintar; antes se sumaba a la altura y el salto esprintando era más alto que el normal.
- **Agua, lava y escaleras** con los valores del juego (inercia 0,8 / 0,5, flotación −0,02,
  subir +0,04, clamp 0,15 y trepada 0,2).
- **Agacharse ya no bloquea**: el guardia de borde estaba invertido (anulaba la velocidad si *no*
  había pared), ahora retrocede 0,05 bloques por paso como el juego.
- **Colisión por subpasos de 0,4 bloques**: al caer a 3,92 b/tick el jugador podía atravesar un
  suelo de un bloque de grosor.
- Velocidades por debajo de 0,003 b/tick se anulan, como en el juego.

## v2.1.0 — Assets de Minecraft Java, sonidos, mods y toques
- **Sonidos reales**: 514 efectos de `minecraft-assets` (romper, colocar, pasos, daño, comer,
  subir de nivel, salpicaduras y clics) con variantes aleatorias.
- **Toque para colocar arreglado**: el micro-movimiento del dedo cancelaba la colocación y la
  ventana de toque (0,20 s) era más corta que un toque real; ahora 0,30 s y umbral de arrastre.
- **Assets de vanilla** consumidos por el motor: texturas de bloque e ítem, modelos de ítem y
  nombres en español.
- Índice de sonidos cacheado y atribución en `assets/vanilla/README.md`.

## v2.0.1 — Ajustes de los controles táctiles
- **Los botones de acción tienen prioridad sobre la hotbar**: en vertical el botón de agacharse
  se solapaba con el extremo derecho de la hotbar y la pulsación seleccionaba una ranura en vez
  de agacharse.
- **Girar la cámara ya no coloca bloques**: al soltar después de arrastrar, la pulsación se
  descarta; solo un toque corto y quieto coloca el bloque y solo mantener pulsado mina.
- Informe de CI con el tamaño del APK y la huella del certificado de firma en cada compilación.

## v2.0.0 — Interfaz de Bedrock, controles táctiles nuevos, arreglo del movimiento y APK completo

> **Cambio de rumbo**: la interfaz deja de imitar Java y pasa a ser la de **Bedrock Edition**.
> La **mecánica sigue siendo la de Java** (física por ticks, generación, redstone, crafteo,
> contenedores, mobs, formato de mundos/mods/texturas de Java).

### Arreglo crítico: ya se puede mover
- **La causa real**: el `GLSurfaceView` (un `AndroidView` dentro de Compose) tenía
  `setOnTouchListener { _, _ -> true }`, así que se tragaba *todos* los toques de la pantalla del
  juego antes de que llegaran a Compose. El joystick dibujado nunca recibía el dedo.
- **Solución**: los toques del juego ahora viven en `GameSurfaceView` (un `GLSurfaceView` propio
  que hace `onTouchEvent` con multitoque real). Cuando hay un panel abierto devuelve `false` y el
  panel de Compose recupera el control.
- **Colisión**: `groundHeight()` arrancaba en `py`, por lo que el jugador nunca se apoyaba en el
  suelo ni podía subir una losa, y si el spawn caía dentro de un bloque el AABB quedaba atascado
  para siempre. Ahora el barrido mira un escalón por encima y por debajo, y `Player.unstick()`
  saca al jugador del terreno.
- Zona de joystick = toda la esquina inferior izquierda (no un cuadro de 150 dp), así que el dedo
  nunca "falla" el control.

### Controles táctiles de Bedrock
- **Joystick** con marco y perilla que aparece donde apoyas el dedo (doble toque adelante =
  esprintar).
- **Girar la cámara** arrastrando cualquier punto libre de la pantalla, con multitoque real
  (mover y mirar a la vez).
- **Botones de acción** abajo a la derecha: saltar (▲), agacharse (▼) y volar (✈); en creativo
  aparecen también subir (⤒) y bajar (⤓). Doble toque en saltar = volar, como en Bedrock.
- **Tocar** el mundo coloca el bloque; **mantener** lo mina con grietas progresivas.
- **Hotbar de 9 ranuras** abajo al centro con el botón `···` del inventario a su derecha y los
  botones de chat y pausa arriba a la derecha.

### Interfaz Bedrock
- **Menú principal** con logotipo en 3D, texto splash amarillo rotado, botones de piedra con
  bisel (`Jugar`, `Ajustes`, `Mods`, `Paquetes`, `Salir del juego`) y panorama animado.
- **HUD Bedrock**: corazones, muslos de pollo, burbujas de aire, barra de XP verde, hotbar oscura
  con marco blanco en la ranura elegida y punto de mira.
- **Inventario Bedrock**: panel oscuro con barra de título, esquina de crafteo 2×2 con resultado,
  modelo del personaje, rejilla 3×9 y fila de hotbar separada; el modo creativo usa pestañas
  laterales con icono y página de 9×5.
- **Menú de pausa y pantalla de muerte** con placas de piedra; cofre, horno y yunque con el mismo
  lenguaje visual.
- Todos los textos de la interfaz pasan a tipografía sans (Bedrock) con sombra dura.

### Instalación como actualización
- **Clave de firma fija** en `keystore/voxelcraft.p12` (PKCS12, alias `voxelcraft`): antes cada
  runner de CI generaba una clave de depuración distinta y Android rechazaba instalar la nueva
  versión encima (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`); ahora todos los APK comparten clave.
- `applicationId` único `com.arena.voxel` también para el APK de depuración (ya no aparece una
  segunda app `.debug` en el lanzador).
- `versionCode` 13 / `versionName` 2.0.0.

### APK de ~57 MB con contenido real
- **Banda sonora propia**: 6 pistas ambientales (~54 MB) sintetizadas desde cero con
  `tools/gen_music.py` (sin audio de Mojang), en `assets/music`. Se reproducen con `MediaPlayer`
  desde el APK: menú, mundo y cuevas.
- Nuevo ajuste **Música** en Ajustes y pausa automática al salir de la app.

## v1.7.1 — TNT y explosiones
- **TNT jugable**: el mechero o una señal de redstone encienden la mecha de 4 s (80 ticks); la
  textura encendida emite luz 7 y la explosión tiene potencia 4 con caída por distancia.
- **Motor de explosiones único**: tabla de resistencia (piedra 6, metales 8, madera 3, tierra 0,6),
  cadena de TNT, 30 % de drops, daño y retroceso al jugador y a los mobs, humo y ascuas.
- El **creeper** usa el mismo motor con potencia 3, así que ahora rompe según la resistencia real
  del bloque en vez de un radio fijo.
- **Mechero** (`flint_and_steel`) con su receta, y `/give` ya puede dar cualquier objeto nuevo.

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
