# VoxelForge 3D — inspección y arquitectura (v2)

Este documento es el resultado de la **Fase 1** (inspección) y de la propuesta de arquitectura que
se está implementando. Todo lo que aquí se afirma está comprobado leyendo el código del
repositorio y compilando con GitHub Actions.

---

## 1. Estado real del proyecto (antes de este rediseño)

| Aspecto | Hallazgo |
| --- | --- |
| Lenguaje | Kotlin (1.9.24), JVM 17 |
| Framework | Android nativo + Jetpack Compose (BOM 2024.06.00), sin Flutter/React |
| Renderizado | **OpenGL ES 3.0 propio**, `GLSurfaceView` + shaders GLSL escritos a mano (`render/`). Sin motor externo (ni Filament, ni SceneView, ni three.js) |
| Geometría | Mallas indexadas simples (`MeshData`: posiciones + índices + normales + UV). Primitivas generadas por fórmulas (`scene/Primitives.kt`) |
| Escena | `SceneDocument` con nodos jerárquicos, materiales PBR, huesos y clips de animación. Forma equivalente a glTF 2.0 |
| IO | Lectores/escritores propios: glTF/GLB, FBX ASCII, OBJ+MTL, STL, PLY (`io/`) |
| IA | `ai/AiSchema` pide JSON de *partes con primitivas* y lo convierte en `SceneDocument`; `ai/AiHttp` habla con OpenRouter y Google AI Studio; `ModelRegistry` descarga catálogos |
| Estado/UI | `ui/AppState` centraliza mutaciones; `ui/Panels` + `ui/ModelerApp` componen la interfaz |
| Almacenamiento | `ai/ObjectStore`: ajustes y proyectos en `filesDir`, exportación a `Descargas/VoxelForge` |
| Pruebas | **Ninguna** (no había `src/test`) |
| CI | `.github/workflows/android-build.yml` compila solo `:modeler` y publica un APK suelto |

### Qué funciona y qué estaba incompleto

Funciona (comprobado en los APK 1.0.x/1.1.0):

- Visor: órbita, paneo, zoom, selección por rayo, gizmo de traslación, cuadrícula, wireframe.
- Primitivas, jerarquía, duplicar/ocultar/borrar, deslizadores de transformación.
- Materiales (color, metalítico, rugosidad, emisión).
- Animación por claves + giro automático + reproducción.
- Importación/exportación de los 6 formatos, proyectos en GLB, autoguardado, deshacer (12 pasos).
- IA: generar desde texto, modificar el modelo actual, catálogo en vivo con filtro gratis/pago.

Incompleto o ausente (lo que aborda este rediseño):

| Hueco | Consecuencia |
| --- | --- |
| La IA solo produce **primitivas y voxels** | No puede crear geometría real, ni editar mallas existentes |
| No hay mallas editables (vértices/aristas/caras) | Todo es ensamblaje, no modelado |
| No hay extrusión, bisel, subdivisión, booleanas, simetría, suavizado, decimación | — |
| No hay curvas ni barrido | — |
| No hay chat ni historial de conversación | Cada generación empieza de cero |
| No hay rehacer (solo deshacer) | — |
| Un solo modo de visualización (sólido + wireframe opcional) | — |
| Catálogo sin precios de entrada/salida ni capacidades | Filtro gratis/pago aproximado |
| Claves en texto plano (`ajustes.json`) | — |
| Exportación sin verificación posterior | — |
| Sin pruebas automáticas | Regresiones silenciosas |

---

## 2. Arquitectura propuesta (implementada en este cambio)

Se **conserva** el motor de renderizado OpenGL ES existente y todo el módulo `io/`. Se añaden
capas nuevas alrededor, sin reemplazar lo que ya funciona.

```
modeler/src/main/java/com/arena/modeler/
├─ core/        Math3D (existente) · Text64 (Base64 puro, para poder probar en JVM)
├─ scene/       Scene · Animation · Primitives           (sin cambios de API)
├─ geom/        NUEVO — núcleo de geometría puro Kotlin (sin Android, testeable en JVM)
│   ├─ EditableMesh.kt   vértices/aristas/caras, triangulación, normales, validación
│   ├─ MeshOps.kt        extrusión, bisel, inset, Catmull-Clark, suavizado, simetría,
│   │                    decimación, separación de componentes, reparación, pincel de esculpido
│   ├─ Csg.kt            booleanas unión/diferencia/intersección (BSP, port de csg.js)
│   └─ Curves.kt         Bézier, Catmull-Rom, barrido de perfil por trayectoria (tubos)
├─ tools/       NUEVO — ejecutor seguro de operaciones con validación y transacciones
│   ├─ ToolSpec.kt       catálogo de herramientas + esquema JSON de argumentos
│   ├─ ToolExecutor.kt   validación → copia → ejecución → verificación → commit/rollback
│   └─ SceneOps.kt       operaciones sobre la escena (crear, transformar, material, exportar)
├─ ai/
│   ├─ AiProvider      (existente) OpenRouter / Google AI Studio
│   ├─ providers/      NUEVO — adaptadores con interfaz común
│   │   ├─ AiProviderAdapter.kt   fetchModels · checkKey · complete(mensajes, imágenes)
│   │   ├─ GoogleAdapter.kt       Gemini generateContent + /v1beta/models
│   │   └─ OpenRouterAdapter.kt   chat/completions + /api/v1/models
│   ├─ ModelCatalog.kt  catálogo con precios entrada/salida, contexto, capacidades, fecha
│   ├─ ChatEngine.kt    chat → plan de operaciones → ejecutor (nunca código arbitrario)
│   ├─ ChatStore.kt     historial por proyecto
│   └─ AiSchema/AiHttp  (existentes, se mantienen para compatibilidad)
├─ settings/    NUEVO — SecureStore (AES-GCM con Android Keystore) + ajustes
├─ render/      (existente) + modos de visualización y gizmo de esculpido
├─ io/          (existente) + verificación de exportación
└─ ui/          rediseño: barra superior, visor con overlay, paneles plegables, chat
```

### Decisiones técnicas y por qué

1. **El motor de renderizado no se cambia.** Ya funciona en teléfonos reales, es pequeño y dibuja
   las mallas que produce el nuevo núcleo sin conversión. Migrar a Filament/SceneView añadiría
   decenas de MB y riesgo sin mejorar el resultado.
2. **`geom/` es Kotlin puro, sin `android.*`.** Eso permite pruebas unitarias reales en la JVM
   (rápidas, dentro del CI) y mantiene el núcleo independiente de la interfaz.
3. **La IA no ejecuta código.** Produce *llamadas a herramientas* con argumentos JSON; la app
   valida tipos, rangos y presupuesto de triángulos antes de tocar la escena, trabaja sobre una
   copia y solo confirma si el resultado es válido (rollback automático si falla).
4. **Los booleanos usan BSP (algoritmo de csg.js)**, no una librería externa: no hay ninguna
   dependencia de mallas para Android que sea fiable sin NDK/ABI extra, y el algoritmo es
   determinista y testeable. Límite: mallas de menos de ~8 000 triángulos por operando.
5. **Las claves se cifran con el Android Keystore** (AES-256-GCM, clave no exportable), no en un
   JSON plano. No hay backend ni claves de desarrollador en el APK.
6. **Catálogo dinámico** de cada proveedor, con precios entrada/salida, capacidades y fecha de
   actualización; `Precio desconocido` cuando el dato falta y aviso de datos desactualizados si
   la consulta falla.

### Lo que sigue fuera de alcance (documentado, sin botones falsos)

- **Generación de mallas orgánicas de alta calidad desde texto** (tipo Meshy/Tripo): requiere un
  servicio externo con su propia clave y API de pago. No se simula: el chat produce geometría
  procedural real (primitivas, curvas, booleanas, subdivisión) y el campo de servicio externo no
  se muestra hasta que exista un adaptador real.
- **Texturas generadas por IA**: no hay proveedor integrado; el material conserva el campo de
  textura para importar/exportar glTF, pero el visor no muestrea imágenes (limitación anotada en
  la UI).
- **Rigging con pesos pintados a mano**: se conserva la animación por nodos/claves existente.
