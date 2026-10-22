# Changelog

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
