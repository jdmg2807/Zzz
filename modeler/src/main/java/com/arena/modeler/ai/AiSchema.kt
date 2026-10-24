package com.arena.modeler.ai

import com.arena.modeler.core.Quat
import com.arena.modeler.core.V3
import com.arena.modeler.scene.AnimChannel
import com.arena.modeler.scene.AnimPath
import com.arena.modeler.scene.AnimationClip
import com.arena.modeler.scene.Material
import com.arena.modeler.scene.MeshData
import com.arena.modeler.scene.Primitives
import com.arena.modeler.scene.SceneDocument
import com.arena.modeler.scene.SceneNode
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.max
import kotlin.math.min

/**
 * The contract between the app and any AI model: a small, strict JSON dialect that describes a
 * scene with primitives (and optionally raw meshes, voxels and animations). Asking for this
 * shape — instead of free-form Python or OBJ text — is what makes the output usable every time,
 * regardless of which provider generated it.
 *
 * ```
 * {
 *   "name": "Zorro",
 *   "parts": [
 *     { "name": "cuerpo", "type": "sphere",
 *       "position": [0, 0.4, 0], "scale": [1, 0.7, 0.6],
 *       "material": { "color": "#E8892B", "roughness": 0.8 } }
 *   ],
 *   "animations": [
 *     { "name": "Cola", "duration": 2, "tracks": [
 *       { "part": "cola", "path": "rotation",
 *         "keys": [ {"t": 0, "value": [0,0,0]}, {"t": 1, "value": [0,0,25]} ] } ] }
 *   ]
 * }
 * ```
 */
object AiSchema {

    const val SYSTEM_PROMPT: String = """Eres un modelador 3D experto que trabaja dentro de una app
móvil. A partir de una descripción en español construyes un modelo 3D sencillo y devuelves
ÚNICAMENTE un objeto JSON válido, sin texto adicional, sin explicaciones y sin bloques de código.

El JSON tiene esta forma exacta:
{
  "name": "Nombre corto del modelo",
  "parts": [
    {
      "name": "nombre_unico_de_la_parte",
      "type": "cube" | "sphere" | "cylinder" | "cone" | "torus" | "plane" | "icosphere" | "mesh" | "voxels",
      "position": [x, y, z],
      "rotation": [gradosX, gradosY, gradosZ],
      "scale": [sx, sy, sz],
      "material": { "color": "#RRGGBB", "metallic": 0.0, "roughness": 0.6, "emission": "#000000" }
    }
  ],
  "animations": [
    {
      "name": "Nombre de la animación",
      "duration": 2.0,
      "tracks": [
        { "part": "nombre_de_la_parte", "path": "translation" | "rotation" | "scale",
          "keys": [ { "t": 0.0, "value": [x, y, z] }, { "t": 1.0, "value": [x, y, z] } ] }
      ]
    }
  ]
}

Reglas obligatorias:
- Todas las partes son independientes y se ubican con "position" en metros, con Y hacia arriba.
  El suelo está en y = 0 y el modelo debe medir entre 1 y 2 unidades de alto.
- "type": "cube" es una caja de 1x1x1 con la base en y = 0; "sphere" una esfera de diámetro 1
  centrada en su origen; "cylinder"/"cone" miden 1 de alto con la base en y = 0; "torus" es un
  anillo de diámetro 1; "plane" es un cuadrado horizontal de 1x1.
- "scale" multiplica el tamaño del primitivo: úsalo para estirar y dar forma (por ejemplo un
  brazo es un cubo con "scale": [0.25, 0.8, 0.25]).
- "rotation" está en grados.
- Usa entre 3 y 30 partes, con nombres descriptivos (cabeza, torso, brazo_izquierdo…).
- Puedes usar "type": "voxels" con "voxels": [ {"position": [x,y,z], "size": 0.25, "color": "#RRGGBB"} ]
  para modelos de bloques; máximo 400 vóxeles.
- Para formas que no son primitivas usa "type": "mesh" con
  "vertices": [[x,y,z], ...] y "faces": [[0,1,2], ...] (índices desde 0, caras de 3 o 4 vértices).
- "material.color" siempre en hexadecimal. "metallic" y "roughness" entre 0 y 1.
- "animations" es opcional: inclúyela solo si la idea pide movimiento. Las claves se expresan en
  segundos, con "duration" mayor que la última clave. Para "rotation" el valor son grados
  (o un cuaternión [x,y,z,w] de 4 números).
- No incluyas comentarios, ni coma final, ni claves que no estén en el esquema."""

    fun userPrompt(idea: String): String = buildString {
        append("Crea el modelo 3D descrito y responde solo con el JSON.\n\n")
        append("Descripción: ")
        append(idea.trim())
        append("\n\nRecuerda: Y hacia arriba, el modelo apoyado en y = 0, entre 1 y 2 unidades de alto, ")
        append("entre 3 y 30 partes, y el JSON completo y válido.")
    }

    fun refinePrompt(idea: String, previous: String): String = buildString {
        append("Modifica el modelo 3D existente según la indicación y responde solo con el JSON completo.\n\n")
        append("Modelo actual (JSON):\n")
        append(previous.take(6000))
        append("\n\nCambio pedido: ")
        append(idea.trim())
        append("\n\nMantén los nombres de las partes que no cambien y devuelve el JSON completo.")
    }

    // ------------------------------------------------------------------ parsing

    /** Parses the model's answer into a scene. Throws with a readable message when the JSON is bad. */
    fun parseScene(raw: String, fallbackName: String = "Modelo IA"): SceneDocument {
        val jsonText = extractJson(raw)
        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            throw IllegalArgumentException("La IA no devolvió un JSON válido. Prueba otra vez o cambia de modelo.")
        }
        val body = root.optJSONObject("model") ?: root.optJSONObject("scene") ?: root
        val doc = SceneDocument(body.optString("name", fallbackName).ifBlank { fallbackName })

        val parts = body.optJSONArray("parts")
            ?: body.optJSONArray("objects")
            ?: body.optJSONArray("meshes")
            ?: JSONArray()
        if (parts.length() == 0) {
            throw IllegalArgumentException("El JSON no tiene partes («parts»). Prueba con otra descripción.")
        }

        val colorToMaterial = HashMap<String, Material>()
        fun materialFor(part: JSONObject): Material? {
            val m = part.optJSONObject("material") ?: part.optJSONObject("mat") ?: part
            val color = parseColor(m.opt("color") ?: m.opt("baseColor") ?: m.opt("hex"))
            val metallic = m.optDouble("metallic", 0.0).toFloat().coerceIn(0f, 1f)
            val roughness = m.optDouble("roughness", 0.6).toFloat().coerceIn(0.02f, 1f)
            val emission = parseColor(m.opt("emission") ?: m.opt("emissive"))
            if (color == null && metallic == 0f && emission == null) return null
            val base = color ?: floatArrayOf(0.78f, 0.8f, 0.86f, 1f)
            val key = "${base[0]},${base[1]},${base[2]},$metallic,$roughness,${emission?.joinToString()}"
            return colorToMaterial.getOrPut(key) {
                val mat = Material(
                    name = "IA.${colorToMaterial.size + 1}",
                    baseColor = base,
                    metallic = metallic,
                    roughness = roughness,
                    emission = emission?.let { floatArrayOf(it[0], it[1], it[2]) }
                        ?: floatArrayOf(0f, 0f, 0f)
                )
                doc.materials.add(mat)
                mat
            }
        }

        val usedNames = HashSet<String>()
        fun unique(base: String): String {
            var candidate = base.ifBlank { "Parte" }
            var i = 2
            while (!usedNames.add(candidate)) {
                candidate = "${base.ifBlank { "Parte" }}_$i"
                i++
            }
            return candidate
        }

        val limit = min(parts.length(), 160)
        for (i in 0 until limit) {
            val part = parts.optJSONObject(i) ?: continue
            val type = part.optString("type", part.optString("kind", "cube")).lowercase().trim()
            val partName = unique(part.optString("name", "Parte${i + 1}"))
            val node = doc.newNode(partName)
            node.position = vec(part.opt("position") ?: part.opt("pos"), 0f, 0f, 0f)
            node.rotation = euler(part.opt("rotation") ?: part.opt("rot"))
            node.scale = vec(
                part.opt("scale") ?: part.opt("size") ?: part.opt("dimensions"),
                1f, 1f, 1f
            ).let { s -> V3(s.x.coerceIn(0.001f, 200f), s.y.coerceIn(0.001f, 200f), s.z.coerceIn(0.001f, 200f)) }
            node.material = materialFor(part)

            val mesh: MeshData? = when (type) {
                "voxels", "voxel", "blocks", "block" -> null // built below, one node per colour
                "mesh", "custom", "geometry", "polygon" -> meshFromJson(part)?.also { if (it.normals == null) it.recomputeNormals() }
                "plane", "quad", "floor" -> Primitives.plane(1f, 1f, 1)
                "sphere", "ball", "uvsphere" -> Primitives.sphere(0.5f, 20, 14)
                "icosphere", "lowpoly" -> Primitives.icosphere(0.5f, 2)
                "cylinder", "tube", "rod" -> Primitives.cylinder(0.5f, 1f, 20)
                "cone", "pyramid" -> Primitives.cone(0.5f, 1f, 20)
                "torus", "ring", "donut", "doughnut" -> Primitives.torus(0.5f, 0.16f, 24, 12)
                "capsule" -> Primitives.sphere(0.5f, 16, 12)
                else -> Primitives.box(1f, 1f, 1f)
            }

            if (type == "voxels" || type == "voxel" || type == "blocks" || type == "block") {
                val voxels = part.optJSONArray("voxels") ?: part.optJSONArray("blocks")
                if (voxels != null) {
                    buildVoxels(doc, node, voxels, materialFor(part))
                } else {
                    node.mesh = Primitives.box(1f, 1f, 1f)
                }
            } else {
                node.mesh = mesh
            }
            if (node.mesh != null && node.material == null) {
                node.material = colorToMaterial.getOrPut("default") {
                    doc.materialFor().also { it.name = "IA.${doc.materials.size}" }
                }
            }
        }

        if (doc.allNodes().isEmpty()) {
            throw IllegalArgumentException("La respuesta de la IA estaba vacía. Inténtalo de nuevo.")
        }

        parseAnimations(body, doc)
        normalize(doc)
        return doc
    }

    /** Groups equal-coloured voxels per colour so a 400-block model stays at a handful of nodes. */
    private fun buildVoxels(doc: SceneDocument, parent: SceneNode, voxels: JSONArray, fallback: Material?) {
        val byColor = LinkedHashMap<String, MutableList<FloatArray>>()
        val count = min(voxels.length(), 1200)
        for (i in 0 until count) {
            when (val item = voxels.opt(i)) {
                is JSONObject -> {
                    val p = vec(item.opt("position") ?: item.opt("pos"), 0f, 0f, 0f)
                    val size = item.optDouble("size", 0.25).toFloat().coerceIn(0.02f, 2f)
                    val color = item.optString("color", "#B0B4BE")
                    byColor.getOrPut(color) { ArrayList() }.add(floatArrayOf(p.x, p.y, p.z, size))
                }
                is JSONArray -> {
                    // [x, y, z] or [x, y, z, size]
                    val p = vec(item, 0f, 0f, 0f)
                    val size = if (item.length() > 3) item.optDouble(3, 0.25).toFloat() else 0.25f
                    byColor.getOrPut("#B0B4BE") { ArrayList() }.add(floatArrayOf(p.x, p.y, p.z, size))
                }
            }
        }
        if (byColor.isEmpty()) {
            parent.mesh = Primitives.box(1f, 1f, 1f)
            parent.material = fallback
            return
        }
        var index = 0
        for ((colorKey, list) in byColor) {
            val positions = FloatArray(list.size * 24 * 3)
            val uvs = FloatArray(list.size * 24 * 2)
            val indices = IntArray(list.size * 36)
            var p = 0
            var u = 0
            var n = 0
            val box = Primitives.box(1f, 1f, 1f)
            for (v in list) {
                val base = p / 3
                val size = v[3]
                for (k in 0 until 24) {
                    positions[p++] = box.positions[k * 3] * size + v[0] - size * 0.5f
                    positions[p++] = box.positions[k * 3 + 1] * size + v[1]
                    positions[p++] = box.positions[k * 3 + 2] * size + v[2] - size * 0.5f
                    uvs[u++] = box.uvs?.get(k * 2) ?: 0f
                    uvs[u++] = box.uvs?.get(k * 2 + 1) ?: 0f
                }
                for (k in 0 until 36) indices[n++] = box.indices[k] + base
            }
            val mesh = MeshData(positions, indices, null, uvs)
            mesh.recomputeNormals()
            val child = doc.newNode("${parent.name}_${index++}")
            child.mesh = mesh
            child.material = colorFromHex(colorKey)?.let { c ->
                doc.materialFor(floatArrayOf(c[0], c[1], c[2], 1f))
            } ?: fallback
            // doc.newNode already attached it to the root; move it under the voxel group.
            doc.root.children.remove(child)
            parent.add(child)
        }
    }

    private fun parseAnimations(root: JSONObject, doc: SceneDocument) {
        val arr = root.optJSONArray("animations") ?: root.optJSONArray("animation") ?: run {
            val single = root.optJSONObject("animation") ?: return
            JSONArray().put(single)
        }
        for (i in 0 until min(arr.length(), 8)) {
            val a = arr.optJSONObject(i) ?: continue
            val clip = AnimationClip(a.optString("name", "Animación ${i + 1}"))
            val tracks = a.optJSONArray("tracks") ?: a.optJSONArray("channels") ?: JSONArray()
            for (t in 0 until min(tracks.length(), 60)) {
                val track = tracks.optJSONObject(t) ?: continue
                val partName = track.optString("part", track.optString("node", track.optString("target", "")))
                val node = doc.allNodes().firstOrNull { it.name.equals(partName, ignoreCase = true) }
                    ?: doc.allNodes().firstOrNull { it.name.equals(partName.replace(' ', '_'), ignoreCase = true) }
                    ?: doc.allNodes().firstOrNull()
                    ?: continue
                val path = AnimationClip.parsePath(
                    track.optString("path", track.optString("property", "rotation"))
                ) ?: AnimPath.ROTATION
                val keys = track.optJSONArray("keys") ?: track.optJSONArray("keyframes") ?: continue
                if (keys.length() == 0) continue
                val times = ArrayList<Float>(keys.length())
                val values = ArrayList<Float>(keys.length() * 4)
                for (k in 0 until keys.length()) {
                    val key = keys.optJSONObject(k) ?: continue
                    val time = key.optDouble("t", key.optDouble("time", 0.0)).toFloat()
                    val value = key.opt("value") ?: key.opt("v")
                    times.add(time.coerceAtLeast(0f))
                    when (path) {
                        AnimPath.ROTATION -> {
                            val v = floats(value)
                            if (v.size >= 4) {
                                val q = Quat(v[0], v[1], v[2], v[3]).normalize()
                                values.add(q.x); values.add(q.y); values.add(q.z); values.add(q.w)
                            } else {
                                val q = Quat.euler(v.getOrElse(0) { 0f }, v.getOrElse(1) { 0f }, v.getOrElse(2) { 0f })
                                values.add(q.x); values.add(q.y); values.add(q.z); values.add(q.w)
                            }
                        }
                        AnimPath.TRANSLATION -> {
                            val v = floats(value)
                            values.add(v.getOrElse(0) { 0f }); values.add(v.getOrElse(1) { 0f }); values.add(v.getOrElse(2) { 0f })
                        }
                        AnimPath.SCALE -> {
                            val v = floats(value)
                            values.add(v.getOrElse(0) { 1f }); values.add(v.getOrElse(1) { 1f }); values.add(v.getOrElse(2) { 1f })
                        }
                    }
                }
                if (times.size < 2) continue
                clip.channels.add(
                    AnimChannel(node.id, path, times.toFloatArray(), values.toFloatArray())
                )
            }
            if (clip.channels.isNotEmpty()) doc.animations.add(clip)
        }
    }

    /**
     * Puts the model on the floor and scales it to a comfortable size, wrapping everything in a
     * single group so the AI's own coordinates are preserved for animation edits.
     */
    fun normalize(doc: SceneDocument, target: Float = 1.8f) {
        val nodes = doc.allNodes().filter { it.mesh != null }
        if (nodes.isEmpty()) return
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (n in nodes) {
            val b = n.worldBounds() ?: continue
            minX = min(minX, b[0]); maxX = max(maxX, b[3])
            minY = min(minY, b[1]); maxY = max(maxY, b[4])
            minZ = min(minZ, b[2]); maxZ = max(maxZ, b[5])
        }
        if (minX > maxX) return
        val size = max(maxX - minX, max(maxY - minY, maxZ - minZ))
        if (size < 1e-6f) return
        val s = target / size
        val cx = (minX + maxX) * 0.5f
        val cz = (minZ + maxZ) * 0.5f
        val group = doc.newNode(doc.name.ifBlank { "Modelo" })
        group.position = V3(-cx * s, -minY * s, -cz * s)
        group.scale = V3(s, s, s)
        // Move the root's other children under the group, in the order they were created.
        val siblings = doc.root.children.toList()
        for (child in siblings) {
            if (child === group) continue
            doc.root.children.remove(child)
            group.add(child)
        }
        doc.root.markDirty()
    }

    /** Pulls the JSON object out of whatever wrapper the model wrapped it in (```json fences…). */
    fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(trimmed)
        val candidate = fenced?.groupValues?.get(1)?.trim() ?: trimmed
        val start = candidate.indexOf('{')
        val end = candidate.lastIndexOf('}')
        if (start < 0 || end <= start) return candidate
        return candidate.substring(start, end + 1)
    }

    // ------------------------------------------------------------------ scene -> JSON

    /**
     * Serialises the current scene back into the same dialect, so "modificar con IA" sends the
     * model it built before and the answer can be diffed against it.
     */
    fun sceneToJson(doc: SceneDocument, maxVerticesPerPart: Int = 1200): String {
        val root = JSONObject()
        root.put("name", doc.name)
        val parts = JSONArray()
        for (node in doc.allNodes()) {
            val mesh = node.mesh ?: continue
            if (!node.visible) continue
            val part = JSONObject()
            part.put("name", node.name)
            val e = com.arena.modeler.io.Fbx.eulerFromQuat(node.rotation)
            part.put("position", JSONArray().put(round(node.position.x)).put(round(node.position.y)).put(round(node.position.z)))
            part.put("rotation", JSONArray().put(round(e[0])).put(round(e[1])).put(round(e[2])))
            part.put("scale", JSONArray().put(round(node.scale.x)).put(round(node.scale.y)).put(round(node.scale.z)))
            val mat = node.material
            if (mat != null) {
                part.put("material", JSONObject().apply {
                    put("color", hex(mat.baseColor))
                    put("metallic", round(mat.metallic))
                    put("roughness", round(mat.roughness))
                })
            }
            if (mesh.vertexCount <= maxVerticesPerPart) {
                part.put("type", "mesh")
                val verts = JSONArray()
                for (v in 0 until mesh.vertexCount) {
                    verts.put(JSONArray().put(round(mesh.positions[v * 3]))
                        .put(round(mesh.positions[v * 3 + 1]))
                        .put(round(mesh.positions[v * 3 + 2])))
                }
                val faces = JSONArray()
                var i = 0
                while (i + 2 < mesh.indices.size) {
                    faces.put(JSONArray().put(mesh.indices[i]).put(mesh.indices[i + 1]).put(mesh.indices[i + 2]))
                    i += 3
                }
                part.put("vertices", verts)
                part.put("faces", faces)
            } else {
                part.put("type", "cube")
            }
            parts.put(part)
            if (parts.length() >= 80) break
        }
        root.put("parts", parts)

        val anims = JSONArray()
        for (clip in doc.animations) {
            val a = JSONObject()
            a.put("name", clip.name)
            a.put("duration", round(clip.duration().coerceAtLeast(1f)))
            val tracks = JSONArray()
            for (ch in clip.channels) {
                val nodeName = doc.nodeById(ch.nodeId)?.name ?: continue
                val track = JSONObject()
                track.put("part", nodeName)
                track.put("path", ch.path.name.lowercase())
                val keys = JSONArray()
                for (k in 0 until ch.keyCount) {
                    val value = JSONArray()
                    if (ch.path == AnimPath.ROTATION) {
                        val e = com.arena.modeler.io.Fbx.eulerFromQuat(
                            Quat(ch.values[k * 4], ch.values[k * 4 + 1], ch.values[k * 4 + 2], ch.values[k * 4 + 3])
                        )
                        value.put(round(e[0])).put(round(e[1])).put(round(e[2]))
                    } else {
                        val stride = ch.stride
                        for (c in 0 until stride) value.put(round(ch.values[k * stride + c]))
                    }
                    keys.put(JSONObject().put("t", round(ch.times[k])).put("value", value))
                }
                track.put("keys", keys)
                tracks.put(track)
            }
            a.put("tracks", tracks)
            anims.put(a)
        }
        if (anims.length() > 0) root.put("animations", anims)
        return root.toString()
    }

    private fun round(v: Float): Double = (Math.round(v * 10000.0) / 10000.0)

    private fun hex(c: FloatArray): String = String.format(
        "#%02X%02X%02X",
        (c[0] * 255).toInt().coerceIn(0, 255),
        (c[1] * 255).toInt().coerceIn(0, 255),
        (c[2] * 255).toInt().coerceIn(0, 255)
    )

    // ------------------------------------------------------------------ small helpers

    private fun floats(value: Any?): FloatArray {
        if (value !is JSONArray) {
            if (value is Number) return floatArrayOf(value.toFloat())
            return FloatArray(0)
        }
        val out = ArrayList<Float>(value.length())
        for (i in 0 until value.length()) {
            val v = value.opt(i)
            if (v is Number) out.add(v.toFloat())
        }
        return out.toFloatArray()
    }

    private fun vec(value: Any?, dx: Float, dy: Float, dz: Float): V3 {
        val f = floats(value)
        return V3(
            f.getOrElse(0) { dx },
            f.getOrElse(1) { dy },
            f.getOrElse(2) { dz }
        )
    }

    private fun euler(value: Any?): Quat {
        val f = floats(value)
        if (f.size >= 4) return Quat(f[0], f[1], f[2], f[3]).normalize()
        return Quat.euler(f.getOrElse(0) { 0f }, f.getOrElse(1) { 0f }, f.getOrElse(2) { 0f })
    }

    private fun meshFromJson(part: JSONObject): MeshData? {
        val rawVerts = part.optJSONArray("vertices") ?: part.optJSONArray("vertexes")
            ?: part.optJSONArray("points") ?: return null
        val positions = ArrayList<Float>(rawVerts.length() * 3)
        for (i in 0 until rawVerts.length()) {
            when (val item = rawVerts.opt(i)) {
                is JSONArray -> {
                    positions.add(item.optDouble(0, 0.0).toFloat())
                    positions.add(item.optDouble(1, 0.0).toFloat())
                    positions.add(item.optDouble(2, 0.0).toFloat())
                }
                is Number -> positions.add(item.toFloat())
            }
        }
        if (positions.size < 9) return null
        val vertexCount = positions.size / 3
        val indices = ArrayList<Int>()
        val rawFaces = part.optJSONArray("faces") ?: part.optJSONArray("triangles")
            ?: part.optJSONArray("indices") ?: part.optJSONArray("polygons")
        if (rawFaces == null) {
            var i = 0
            while (i + 2 < vertexCount) {
                indices.add(i); indices.add(i + 1); indices.add(i + 2)
                i += 3
            }
        } else {
            var maxIndex = 0
            val faces = ArrayList<IntArray>(rawFaces.length())
            if (rawFaces.length() > 0 && rawFaces.opt(0) is Number) {
                // Flat triangle list: [a, b, c, d, e, f, …]
                var i = 0
                while (i + 2 < rawFaces.length()) {
                    val f = intArrayOf(rawFaces.optInt(i, 0), rawFaces.optInt(i + 1, 0), rawFaces.optInt(i + 2, 0))
                    maxIndex = max(maxIndex, f.max())
                    faces.add(f)
                    i += 3
                }
            } else {
                for (i in 0 until rawFaces.length()) {
                    val item = rawFaces.opt(i)
                    if (item !is JSONArray) continue
                    val f = IntArray(item.length()) { item.optInt(it, 0) }
                    if (f.isNotEmpty()) {
                        maxIndex = max(maxIndex, f.max())
                        faces.add(f)
                    }
                }
            }
            val oneBased = maxIndex == vertexCount
            for (f in faces) {
                val idx = IntArray(f.size) { (if (oneBased) f[it] - 1 else f[it]).coerceIn(0, vertexCount - 1) }
                for (k in 1 until idx.size - 1) {
                    indices.add(idx[0]); indices.add(idx[k]); indices.add(idx[k + 1])
                }
            }
        }
        if (indices.size < 3) return null
        val mesh = MeshData(positions.toFloatArray(), indices.toIntArray(), null, null)
        mesh.recomputeNormals()
        return mesh
    }

    fun parseColor(value: Any?): FloatArray? {
        when (value) {
            null, JSONObject.NULL -> return null
            is String -> return colorFromHex(value)
            is JSONArray -> {
                val f = FloatArray(value.length()) { value.optDouble(it, 0.0).toFloat() }
                if (f.size < 3) return null
                val scaled = if (f.max() > 1.001f) floatArrayOf(f[0] / 255f, f[1] / 255f, f[2] / 255f)
                else floatArrayOf(f[0], f[1], f[2])
                return floatArrayOf(scaled[0], scaled[1], scaled[2], 1f)
            }
            is Number -> {
                val hex = value.toInt()
                return colorFromHex(String.format("#%06X", hex and 0xFFFFFF))
            }
        }
        return null
    }

    fun colorFromHex(raw: String): FloatArray? {
        var s = raw.trim().removePrefix("#")
        if (s.length == 3) s = "${s[0]}${s[0]}${s[1]}${s[1]}${s[2]}${s[2]}"
        if (s.length != 6 && s.length != 8) return null
        val v = s.toLongOrNull(16) ?: return null
        val r = ((v shr 16) and 0xFF).toFloat() / 255f
        val g = ((v shr 8) and 0xFF).toFloat() / 255f
        val b = (v and 0xFF).toFloat() / 255f
        val a = if (s.length == 8) ((v shr 24) and 0xFF).toFloat() / 255f else 1f
        return floatArrayOf(r, g, b, a)
    }
}
