# VoxelForge 3D

Modelador y animador 3D para Android, pensado para hacer en el teléfono lo que normalmente
obligaría a abrir Blender: montar un modelo con primitivas, darle materiales, animarlo,
**generarlo con IA** y exportarlo en el formato que pida el programa de destino.

- Aplicación **independiente** del juego: `applicationId` `com.arena.modeler`, icono y firma
  propios (`keystore/modeler.p12`), así que se instala y actualiza por separado de VoxelCraft.
- Código en el módulo `modeler/` de este mismo repositorio; el APK lo compila GitHub Actions.
- Todo funciona sin conexión **excepto** la generación con IA, que llama directamente al
  proveedor que elijas con tu propia clave.

---

## 1. Qué se puede hacer

| Área | Detalle |
| --- | --- |
| Modelado | Cubo, esfera, cilindro, cono, toro, plano e icósfera como **mallas editables**; extrusión (región o cara a cara), inset, bisel, subdivisión Catmull-Clark, suavizado, simetría, decimación, unir y separar piezas. |
| Esculpido | Pincel esférico sobre la superficie con modo **inflar / suavizar / pellizcar**, radio y fuerza ajustables; se pinta arrastrando en el visor. |
| Booleanas | Unión, diferencia e intersección entre dos objetos (BSP propio, sin dependencias). |
| Curvas | Tubos por curva de Bézier, Catmull-Rom o polilínea. |
| Materiales | Color, metalicidad, rugosidad y emisión por objeto (PBR, lo que guarda glTF). |
| Animación | Clips con claves de posición / rotación / escala, reproducción, barra de tiempo y giro automático de 4 s. |
| Chat de IA | Conversación con contexto del proyecto que **ejecuta herramientas**: «hazle un asa», «bisela las aristas», «reduce a 2 000 triángulos». Las operaciones destructivas piden confirmación y todo se puede deshacer. |
| Catálogo de modelos | Lista descargada del proveedor con **precios de entrada y salida por millón de tokens**, contexto, capacidades, fecha de actualización y filtros Todos / Gratis / De pago / Precio desconocido. |
| Importar | glTF, GLB, FBX ASCII, OBJ, STL (texto y binario), PLY (texto y binario). |
| Exportar | GLB, .gltf, FBX ASCII, OBJ + MTL, STL binario/texto, PLY texto/binario. Cada archivo se **vuelve a leer y se verifica** antes de decir «exportado». |
| Proyectos | Guardado local en GLB, lista de proyectos, autoguardado y **deshacer/rehacer de 20 pasos**. |

El visor es OpenGL ES 3.0: un dedo orbita, dos dedos desplazan, pellizcar acerca, tocar
selecciona, las flechas del gizmo mueven el objeto en un eje y, con el pincel activo, arrastrar
esculpe.

---

## 2. Instalar

1. Pestaña **Releases** del repositorio → última versión.
2. Descarga **`VoxelForge-<n>.apk`** — un `.apk` suelto, sin zip y sin el APK del juego.
3. Instálalo. Las siguientes versiones se instalan **encima** (misma clave de firma), sin
   desinstalar nada.

El workflow `Build VoxelForge APK` compila solo este módulo; el juego ya no forma parte de la
compilación ni de los releases (proyecto terminado).

Requisitos: Android 7.0 (API 24) o superior y GPU con OpenGL ES 3.0.

---

## 3. Conectar la IA

La app no tiene servidor propio: la petición sale del teléfono hacia el proveedor.

### OpenRouter
1. Entra en <https://openrouter.ai/keys> y crea una clave (`sk-or-v1-…`).
2. En la app: pestaña **IA** → proveedor **OpenRouter** → pega la clave → **Guardar clave**.
3. Elige modelo. Cualquiera de la lista sirve; Gemini 2.0 Flash y GPT-4o mini son los más
   baratos, Claude 3.5 Sonnet el que mejor resuelve formas complejas.

### Google AI Studio
1. Entra en <https://aistudio.google.com/apikey> y genera una clave (`AIza…`). Tiene nivel
   gratuito, sin tarjeta.
2. En la app: pestaña **IA** → proveedor **Google AI Studio** → pega la clave → **Guardar clave**.
3. Modelo recomendado: **Gemini 2.0 Flash**.

La clave se guarda **cifrada con el Android Keystore** (AES-256-GCM, clave no exportable): no
queda en texto plano en ningún archivo. Se usa solo en la cabecera de la petición al proveedor
elegido y no se envía a ningún otro sitio. Si el dispositivo no ofrece Keystore, la app lo avisa
y guarda la clave en el almacenamiento privado de la app. Si cambias de proveedor, cada uno
conserva su clave y su modelo.

### Elegir modelo (cualquiera del proveedor)

La lista **no está fijada en el APK**: se descarga de cada proveedor y se guarda en caché para
poder elegir sin conexión.

- **Actualizar lista** descarga el catálogo actual:
  - *OpenRouter*: `GET /api/v1/models`, con `pricing` por token → el filtro **Gratis** muestra
    los que cuestan 0 (`:free` incluidos) y **De pago** el resto, con el precio por millón de
    tokens y el tamaño de contexto.
  - *Google AI Studio*: `GET /v1beta/models` con tu clave; se listan solo los que generan texto
    (`generateContent`), y **Gratis** marca Flash / Flash-Lite / Gemma (nivel gratuito de AI
    Studio), **De pago** los Pro.
- **Filtros**: `Todos`, `Gratis`, `De pago`, `Precio desconocido`. Se combinan con el buscador
  («gemini», «claude»…). Cada fila muestra el precio de **entrada** y de **salida** por millón de
  tokens, el contexto y las capacidades (texto, imágenes, herramientas).
- **Fecha del catálogo** y aviso de «desactualizado» si la última descarga falló: nunca se
  presentan precios viejos como si fueran actuales.
- **Otro modelo (id exacto)**: si conoces un id que no aparece (uno recién publicado), escríbelo
  y pulsa *Usar ese id*; la app lo usará tal cual.
- Si el proveedor retira un modelo, el error lo dice y la app **actualiza la lista sola** para
  que elijas otro de un toque.

El filtro de Google es orientativo (su API no publica precios): Flash y Gemma tienen cuota
gratuita, los Pro necesitan facturación al agotarla. La app nunca cobra nada; el consumo va a tu
cuenta del proveedor.

### Cómo pedir modelos que salgan bien

- Di **qué es**, para qué se usa y de qué estilo: «una espada mágica, hoja flotante, estilo
  low-poly, con brillo azul».
- Menciona partes y proporciones si te importan: «cuerpo bajo y ancho, cola larga, orejas
  triangulares».
- Pide animación explícitamente: «que la cola se mueva de lado a lado en 2 segundos».
- Si el resultado no te convence, usa **Modificar el actual** con un cambio concreto
  («más alto», «cambia el azul por naranja», «añade una animación de salto»).
- La app limita la respuesta a 3–30 partes y normaliza el modelo a ~1,8 unidades de alto con
  la base en el suelo, así que siempre aparece encuadrado en el visor.

### Qué se envía y qué no
Se envían **solo los textos**: tu descripción y, al modificar, un resumen JSON de la escena
(nombres, transformaciones, mallas pequeñas y claves de animación). No se suben las texturas,
ni el proyecto, ni la clave a terceros.

---

### Chat que trabaja sobre la escena

El chat no pide «un modelo entero» en cada mensaje: el modelo **interpreta** la frase y responde
con un plan de operaciones; la app **valida** cada argumento (tipo, rango, límite de caras) y las
ejecuta con el motor de geometría. El modelo nunca ejecuta código ni ve la malla completa: recibe
un resumen (objetos, transformaciones, tamaños y recuento de triángulos).

- Operación destructiva (`delete_object`, `boolean`, `mirror_mesh`, `reduce_mesh`): la app pide
  confirmación antes de aplicarla.
- Cualquier cambio se puede deshacer con `Deshacer` (20 pasos) y queda en el historial.
- Ejemplos: «hazle un asa a la taza», «bisela las aristas 2 mm», «une las dos piezas», «separa en
  objetos», «reduce los polígonos», «pon el material azul metálico».

Límite honesto: la IA **no** genera mallas orgánicas de alta calidad (tipo Meshy/Tripo) ni
texturas; eso requiere un servicio externo con su propia API de pago y no se simula aquí.

---

## 4. Formatos: qué conserva cada uno

| Formato | Geometría | Materiales | Jerarquía | Animación |
| --- | :---: | :---: | :---: | :---: |
| GLB (`.glb`) | sí | sí (PBR) | sí | **sí** |
| glTF (`.gltf`) | sí | sí (PBR) | sí | **sí** |
| FBX ASCII (`.fbx`) | sí | sí (phong) | plana | **sí** (curvas TRS) |
| OBJ + MTL | sí | color/brillo | plana | no |
| STL (`.stl`) | sí | no | no | no |
| PLY (`.ply`) | sí | color | no | no |

Recomendación: **GLB** siempre que puedas. Es un solo archivo, conserva todo y lo abren
Blender, Unity, Unreal, three.js, Godot y casi cualquier visor web.

### Límites conocidos al importar
- **FBX binario**: no se puede leer (hace falta el SDK propietario). Desde Blender: *Archivo →
  Exportar → FBX* y marca **ASCII**; o exporta a glTF.
- **`.gltf` con `.bin` externo**: la app solo lee buffers incrustados. Exporta `.glb`.
- **COLLADA (`.dae`)**, **`.3ds`**, **`.blend`**, **`.3mf`**, **`.usdz`**: sin lector. Convierte
  a glTF/OBJ/STL desde Blender.
- Al exportar a OBJ/STL/PLY, las transformaciones se aplican a los vértices (esos formatos no
  tienen escena), así que el modelo sale donde lo ves, no con el gizmo en el origen.

---

## 5. Flujo típico

1. **Crear** → añade una primitiva, o abre **Chat IA** y pide «una taza con asa».
2. **Editar** → posición/rotación/escala con los deslizadores o el gizmo; extruye, bisela,
   subdivide, aplica booleanas o activa **Pincel** y arrastra para esculpir.
3. **Material** → color y acabado de cada parte.
4. **Animar** → *Crear giro automático* para probar, o mueve el tiempo y añade claves.
5. **Archivo** → *Guardar proyecto* (GLB local) y *Exportar GLB* a `Descargas/VoxelForge`. La app
   verifica el archivo antes de confirmar.

Los archivos exportados quedan en **Descargas/VoxelForge** (Android 10+) o en la carpeta de
descargas privada de la app en versiones anteriores. El `.mtl` del OBJ se guarda al lado.

---

## 6. Arquitectura (para quien toque el código)

```
modeler/src/main/java/com/arena/modeler/
├─ core/      V3, Quat, M4 (column-major), Geom (ray-triangle), Text64 (Base64 puro)
├─ scene/     MeshData, Material, SceneNode, SceneDocument, AnimationClip, Primitives
├─ geom/      EditableMesh, MeshOps (extrusión, bisel, subdivisión, simetría, decimación,
│             esculpido, barrido, componentes), Csg (BSP), Curves
├─ tools/     ToolSpec (catálogo + esquema) y ToolExecutor (validación, transacción, rollback)
├─ io/        Gltf, Fbx, Obj, Stl, Ply, ModelFormats (exportación con verificación)
├─ ai/        providers/{AiProviderAdapter, GoogleAdapter, OpenRouterAdapter}, ModelCatalog,
│             ChatEngine, ChatStore, ProviderRegistry, AiSchema, AiClient, ObjectStore
├─ settings/  SecureStore (AES-GCM con Android Keystore)
├─ render/    GLCommon, MeshBatch, OrbitCamera, ViewportState, SceneRenderer, ViewportView
└─ ui/        AppState (estado y operaciones), Components, Panels, ModelerApp, MainActivity
```

Decisiones que conviene respetar:

- **La escena es el formato de intercambio.** Todo importador produce `SceneDocument`; la UI
  nunca habla con un formato concreto.
- **El motor de geometría es Kotlin puro** (sin `android.*`), por eso se prueba en la JVM con
  `:modeler:testDebugUnitTest`.
- **La IA no ejecuta código**: solo puede pedir herramientas del catálogo, con argumentos que se
  validan antes de tocar la escena.
- **Un único `AppState`** muta el documento; toda mutación pasa por un envoltorio que guarda el
  estado anterior (deshacer) y registra la entrada del historial.
- **El catálogo de modelos es dinámico**: no hay ids ni precios escritos a mano en el APK.
- **`ModelFormats.export` es el único camino de exportación** y verifica el archivo releyéndolo.
- **Sin dependencias de terceros** para 3D: `org.json` y `HttpURLConnection` vienen en Android y
  Compose solo se usa para la interfaz.
- **Firma estable**: `keystore/modeler.p12` (contraseña `modeler`, alias `modeler`).

### Pruebas

62 pruebas de JVM en `modeler/src/test/java/com/arena/modeler/`:

```bash
gradle :modeler:testDebugUnitTest
```

Cubren primitivas, cada operación de `MeshOps`, booleanas (volumen y validez), curvas y barrido,
`MeshData` ↔ malla editable, escena y animación, ida y vuelta por GLB/glTF/FBX/OBJ/STL/PLY,
verificación de exportación y el ejecutor de herramientas (argumentos inválidos, herramienta
desconocida, booleanas y esculpido). El workflow las ejecuta **antes** de compilar el APK: si una
prueba falla, no se publica nada.

Compilar a mano:

```bash
./gradlew :modeler:assembleDebug          # APK de depuración
./gradlew :modeler:assembleRelease        # APK firmado con keystore/modeler.p12
```

El workflow `.github/workflows/android-build.yml` ejecuta las pruebas y compila solo `:modeler`
en cada push; en un tag, publica un único `VoxelForge-<n>.apk` suelto en el Release.
