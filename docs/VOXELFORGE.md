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
| Modelado | Cubo, esfera, cilindro, cono, toro, plano e icósfera; mover, rotar, escalar, duplicar, ocultar y borrar. Jerarquía con grupos. |
| Materiales | Color, metalicidad, rugosidad y emisión por objeto (PBR, lo mismo que guarda glTF). |
| Animación | Clips con claves de posición / rotación / escala, interpolación lineal o por pasos, reproducción en el visor y giro automático de 4 s para empezar. |
| IA | Escribe una idea («un zorro voxel sentado») y la app pide el modelo a **OpenRouter** o a **Google AI Studio**, con tu clave. También puede *modificar* el modelo que ya tienes. |
| Importar | glTF, GLB, FBX ASCII, OBJ, STL (texto y binario), PLY (texto y binario). |
| Exportar | GLB, .gltf, FBX ASCII, OBJ + MTL, STL binario/texto, PLY texto/binario. |
| Proyectos | Guardado local en GLB (conserva malla, materiales y animaciones), lista de proyectos y autoguardado al salir. |

El visor es OpenGL ES 3.0: un dedo orbita, dos dedos desplazan, pellizcar acerca, tocar
selecciona y las flechas roja/verde/azul del gizmo mueven el objeto en un eje.

---

## 2. Instalar

1. Pestaña **Actions** → workflow **Build Android APK** → ejecución más reciente.
2. Descarga el artefacto **APKs**.
3. Instala `VoxelForge-<n>-release.apk`. Las siguientes versiones se instalan **encima**
   (misma clave de firma), sin desinstalar nada.

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

La clave se guarda en el almacenamiento privado de la app (`filesDir/ajustes.json`) y solo se
usa en la cabecera de la petición. No se envía a ningún otro sitio. Si cambias de proveedor,
cada uno conserva su clave y su modelo.

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

1. **Objetos** → añade primitivas o **IA** → «una casa pequeña con techo a dos aguas».
2. **Editar** → ajusta posición, rotación y escala con los deslizadores (o con el gizmo).
3. **Material** → color y acabado de cada parte.
4. **Animar** → *Crear giro automático* para probar, o mueve el tiempo y añade claves.
5. **Archivo** → *Guardar proyecto* (GLB local) y *Exportar GLB* a `Descargas/VoxelForge`.

Los archivos exportados quedan en **Descargas/VoxelForge** (Android 10+) o en la carpeta de
descargas privada de la app en versiones anteriores. El `.mtl` del OBJ se guarda al lado.

---

## 6. Arquitectura (para quien toque el código)

```
modeler/src/main/java/com/arena/modeler/
├─ core/      V3, Quat, M4 (column-major), Geom (ray-triangle)
├─ scene/     MeshData, Material, SceneNode, SceneDocument, AnimationClip, Primitives
├─ io/        Gltf, Fbx, Obj, Stl, Ply, ModelFormats (detección + despacho)
├─ ai/        AiCatalog, AiSchema (esquema JSON ↔ escena), AiHttp, AiClient, ObjectStore
├─ render/    GLCommon (shaders + líneas), MeshBatch, OrbitCamera, SceneRenderer, ViewportState, ViewportView
└─ ui/        AppState (estado y operaciones), Components, Panels, ModelerApp, MainActivity
```

Decisiones que conviene respetar:

- **La escena es el formato de intercambio.** Todo importador produce `SceneDocument` y todo
  exportador lo consume; la UI nunca habla con un formato concreto.
- **`ModelFormats` es el único punto de entrada** de archivos: detecta por extensión y por
  bytes mágicos, y convierte cualquier fallo en un mensaje en español.
- **El visor no muta nada**: lee `ViewportState` (banderas y documento) y dibuja; los cambios
  los hace `AppState`, que además sincroniza el estado de Compose.
- **Sin dependencias de terceros** para 3D: no hay motor ni librería de mallas; `org.json` y
  `HttpURLConnection` vienen en Android, y Compose solo se usa para la interfaz.
- **Firma estable**: `keystore/modeler.p12` (contraseña `modeler`, alias `modeler`) está en el
  repo para que las actualizaciones se instalen encima de la versión anterior.

Compilar a mano:

```bash
./gradlew :modeler:assembleDebug          # APK de depuración
./gradlew :modeler:assembleRelease        # APK firmado con keystore/modeler.p12
```

El workflow `.github/workflows/android-build.yml` compila juego + modelador en cada push y
publica `VoxelForge-<n>-release.apk` / `-debug.apk` junto a los APK del juego.
