package com.arena.modeler.tools

import com.arena.modeler.core.Quat
import com.arena.modeler.core.V3
import com.arena.modeler.geom.Csg
import com.arena.modeler.geom.Curves
import com.arena.modeler.geom.EditableMesh
import com.arena.modeler.geom.MeshOps
import com.arena.modeler.io.ExportFormat
import com.arena.modeler.scene.Material
import com.arena.modeler.scene.Primitives
import com.arena.modeler.scene.SceneDocument
import com.arena.modeler.scene.SceneNode
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/** Una petición ya estructurada: nombre de herramienta + argumentos crudos. */
class ToolCall(val tool: String, val args: JSONObject) {
    fun describe(): String = if (args.length() == 0) tool else "$tool(${args.keys().asSequence().joinToString(", ")})"
}

/** Resultado de una herramienta. [undoHint] explica al usuario qué se puede deshacer. */
class ToolResult(
    val ok: Boolean,
    val message: String,
    val nodeId: Int = -1,
    val warnings: List<String> = emptyList()
)

/**
 * Ejecuta las herramientas de [ToolSpec] sobre un [SceneDocument] con validación estricta.
 *
 * Garantías que ofrece:
 * - Una herramienta desconocida, un argumento obligatorio ausente o un valor fuera de rango se
 *   rechazan **antes** de tocar la escena.
 * - Cada operación trabaja sobre copias (`EditableMesh` inmutable en la práctica), y solo escribe
 *   en el nodo si el resultado pasa la validación de mallas.
 * - Si una operación lanza una excepción, el nodo afectado se restaura a como estaba.
 */
class ToolExecutor(
    private val doc: SceneDocument,
    /** Se usa para `export_model`; devuelve la ruta escrita y lanza si falla. */
    private val exportHook: ((ExportFormat, String?) -> String)? = null
) {

    class ValidationException(message: String) : Exception(message)

    private val materialCache = HashMap<String, Material>()

    // ------------------------------------------------------------------ entrada

    /**
     * Convierte `{"tool": "...", "args": {...}}` (o `{"name": ...}`) en un [ToolCall].
     * Lanza [ValidationException] si la forma es incorrecta.
     */
    fun parseCall(json: JSONObject): ToolCall {
        val tool = json.optString("tool").ifBlank { json.optString("name") }.ifBlank {
            throw ValidationException("La operación no indica ninguna herramienta")
        }
        val args = json.optJSONObject("args") ?: json.optJSONObject("arguments") ?: JSONObject()
        return ToolCall(tool, args)
    }

    fun executeAll(calls: List<ToolCall>): List<ToolResult> = calls.map { execute(it) }

    fun execute(call: ToolCall): ToolResult {
        val spec = ToolSpec.find(call.tool)
            ?: return ToolResult(false, "Herramienta desconocida: «${call.tool}»")
        val args = try {
            validate(spec, call.args)
        } catch (e: ValidationException) {
            return ToolResult(false, "Argumentos inválidos en «${call.tool}»: ${e.message}")
        }
        return try {
            run(spec, args)
        } catch (e: MeshOps.MeshOpException) {
            ToolResult(false, e.message ?: "La operación de geometría falló")
        } catch (e: Csg.CsgException) {
            ToolResult(false, e.message ?: "La operación booleana falló")
        } catch (e: Exception) {
            ToolResult(false, "«${call.tool}» falló: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------------ validación

    private class Args(private val values: JSONObject) {
        fun has(name: String) = values.has(name) && !values.isNull(name)
        fun number(name: String, def: Float): Float =
            if (!has(name)) def else values.optDouble(name, def.toDouble()).toFloat()

        fun int(name: String, def: Int): Int =
            if (!has(name)) def else values.optInt(name, def)

        fun string(name: String, def: String): String = if (!has(name)) def else values.optString(name, def)
        fun bool(name: String, def: Boolean): Boolean = if (!has(name)) def else values.optBoolean(name, def)
        fun stringOrNull(name: String): String? = if (!has(name)) null else values.optString(name)

        fun vec3(name: String): FloatArray? {
            if (!has(name)) return null
            val arr = values.optJSONArray(name) ?: return null
            if (arr.length() < 3) return null
            return floatArrayOf(
                arr.optDouble(0, 0.0).toFloat(),
                arr.optDouble(1, 0.0).toFloat(),
                arr.optDouble(2, 0.0).toFloat()
            )
        }

        fun points(name: String, max: Int = 2000): List<FloatArray>? {
            val arr = values.optJSONArray(name) ?: return null
            val out = ArrayList<FloatArray>(minOf(arr.length(), max))
            for (i in 0 until minOf(arr.length(), max)) {
                val p = arr.optJSONArray(i) ?: continue
                if (p.length() < 3) continue
                out.add(
                    floatArrayOf(
                        p.optDouble(0, 0.0).toFloat(),
                        p.optDouble(1, 0.0).toFloat(),
                        p.optDouble(2, 0.0).toFloat()
                    )
                )
            }
            return out
        }

        fun faces(name: String, max: Int = 4000): List<IntArray>? {
            val arr = values.optJSONArray(name) ?: return null
            val out = ArrayList<IntArray>(minOf(arr.length(), max))
            for (i in 0 until minOf(arr.length(), max)) {
                val f = arr.optJSONArray(i) ?: continue
                if (f.length() < 3 || f.length() > 8) continue
                val idx = IntArray(f.length()) { f.optInt(it, -1) }
                if (idx.any { it < 0 }) continue
                out.add(idx)
            }
            return out
        }
    }

    private fun validate(spec: ToolSpec.Tool, raw: JSONObject): Args {
        for (p in spec.params) {
            if (p.required && !raw.has(p.name)) {
                throw ValidationException("falta el argumento «${p.name}»")
            }
            if (!raw.has(p.name)) continue
            when (p.kind) {
                ToolSpec.Kind.NUMBER, ToolSpec.Kind.INTEGER -> {
                    val v = raw.optDouble(p.name, Double.NaN)
                    if (v.isNaN()) throw ValidationException("«${p.name}» debe ser un número")
                    if (p.min != null && v < p.min) {
                        throw ValidationException("«${p.name}» debe ser ≥ ${p.min}")
                    }
                    if (p.max != null && v > p.max) {
                        throw ValidationException("«${p.name}» debe ser ≤ ${p.max}")
                    }
                    if (p.kind == ToolSpec.Kind.INTEGER && v != Math.floor(v)) {
                        throw ValidationException("«${p.name}» debe ser entero")
                    }
                }
                ToolSpec.Kind.STRING -> {
                    val v = raw.optString(p.name, "")
                    val options = p.options
                    if (options != null && options.none { it.equals(v, ignoreCase = true) }) {
                        throw ValidationException(
                            "«${p.name}» debe ser uno de: ${options.joinToString(", ")}"
                        )
                    }
                }
                ToolSpec.Kind.VECTOR3 -> {
                    val arr = raw.optJSONArray(p.name)
                        ?: throw ValidationException("«${p.name}» debe ser [x, y, z]")
                    if (arr.length() < 3) throw ValidationException("«${p.name}» necesita 3 números")
                    for (i in 0 until 3) {
                        if (arr.optDouble(i, Double.NaN).isNaN()) {
                            throw ValidationException("«${p.name}» tiene valores no numéricos")
                        }
                    }
                }
                ToolSpec.Kind.POINT_LIST -> {
                    val arr = raw.optJSONArray(p.name)
                        ?: throw ValidationException("«${p.name}» debe ser una lista de puntos")
                    if (arr.length() == 0) throw ValidationException("«${p.name}» está vacía")
                    if (arr.length() > 2000) throw ValidationException("«${p.name}» supera 2000 puntos")
                }
                ToolSpec.Kind.FACE_LIST -> {
                    val arr = raw.optJSONArray(p.name)
                        ?: throw ValidationException("«${p.name}» debe ser una lista de caras")
                    if (arr.length() == 0) throw ValidationException("«${p.name}» está vacía")
                    if (arr.length() > 4000) throw ValidationException("«${p.name}» supera 4000 caras")
                }
                else -> Unit
            }
        }
        return Args(raw)
    }

    // ------------------------------------------------------------------ ejecución

    private fun run(spec: ToolSpec.Tool, a: Args): ToolResult = when (spec.name) {
        "create_primitive" -> createPrimitive(a)
        "create_mesh" -> createMesh(a)
        "create_curve" -> createCurve(a)
        "extrude" -> editSelected(a) { mesh, args ->
            MeshOps.extrude(mesh, args.number("distance", 0.2f))
        }
        "inset" -> editSelected(a) { mesh, args -> MeshOps.inset(mesh, args.number("amount", 0.05f)) }
        "bevel" -> editSelected(a) { mesh, args ->
            MeshOps.bevel(mesh, args.number("amount", 0.02f), args.int("segments", 1))
        }
        "subdivide" -> editSelected(a) { mesh, args ->
            MeshOps.subdivide(mesh, args.int("iterations", 1), args.bool("smooth", true))
        }
        "smooth_mesh" -> editSelected(a) { mesh, args ->
            MeshOps.smoothMesh(mesh, args.int("iterations", 1), args.number("strength", 0.5f))
        }
        "mirror_mesh" -> editSelected(a) { mesh, args ->
            val axis = when (args.string("axis", "x").lowercase(Locale.ROOT)) {
                "y" -> MeshOps.Axis.Y
                "z" -> MeshOps.Axis.Z
                else -> MeshOps.Axis.X
            }
            MeshOps.mirror(mesh, axis)
        }
        "reduce_mesh" -> editSelected(a) { mesh, _ -> MeshOps.reduce(mesh) }
        "sculpt" -> sculpt(a)
        "boolean" -> boolean(a)
        "assign_material" -> assignMaterial(a)
        "transform_object" -> transformObject(a)
        "duplicate_object" -> duplicateObject(a)
        "delete_object" -> deleteObject(a)
        "inspect_scene" -> ToolResult(true, inspectScene())
        "export_model" -> exportModel(a)
        else -> ToolResult(false, "Herramienta no implementada: ${spec.name}")
    }

    private fun findNode(nameOrId: String?): SceneNode? {
        if (nameOrId == null) return null
        val id = nameOrId.toIntOrNull()
        if (id != null) doc.nodeById(id)?.let { return it }
        return doc.allNodes().firstOrNull { it.name.equals(nameOrId, ignoreCase = true) }
            ?: doc.allNodes().firstOrNull { it.name.replace(' ', '_').equals(nameOrId, ignoreCase = true) }
            ?: doc.allNodes().firstOrNull { it.name.contains(nameOrId, ignoreCase = true) }
    }

    private fun node(a: Args, key: String = "object"): SceneNode =
        findNode(a.stringOrNull(key)) ?: throw MeshOps.MeshOpException(
            "No encuentro el objeto «${a.stringOrNull(key)}». Usa inspect_scene para ver los nombres."
        )

    private fun materialFor(colorHex: String?): Material {
        if (colorHex.isNullOrBlank()) return doc.materialFor()
        val key = colorHex.lowercase(Locale.ROOT)
        materialCache[key]?.let { return it }
        val rgba = parseColor(key) ?: return doc.materialFor()
        val mat = Material(
            name = "Mat_${key.removePrefix("#")}",
            baseColor = rgba
        )
        doc.materials.add(mat)
        materialCache[key] = mat
        return mat
    }

    private fun parseColor(hex: String): FloatArray? {
        var s = hex.trim().removePrefix("#")
        if (s.length == 3) s = "${s[0]}${s[0]}${s[1]}${s[1]}${s[2]}${s[2]}"
        if (s.length != 6) return null
        val v = s.toLongOrNull(16) ?: return null
        return floatArrayOf(
            ((v shr 16) and 0xFF) / 255f,
            ((v shr 8) and 0xFF) / 255f,
            (v and 0xFF) / 255f,
            1f
        )
    }

    private fun newNamed(prefix: String): String {
        var candidate = prefix
        var i = 2
        val used = doc.allNodes().map { it.name.lowercase(Locale.ROOT) }.toHashSet()
        while (used.contains(candidate.lowercase(Locale.ROOT))) {
            candidate = "$prefix.$i"
            i++
        }
        return candidate
    }

    private fun applyTransform(node: SceneNode, a: Args) {
        a.vec3("position")?.let { node.position = V3(it[0], it[1], it[2]) }
        a.vec3("rotation")?.let { node.rotation = Quat.euler(it[0], it[1], it[2]) }
        a.vec3("scale")?.let {
            node.scale = V3(
                it[0].coerceIn(0.001f, 100f),
                it[1].coerceIn(0.001f, 100f),
                it[2].coerceIn(0.001f, 100f)
            )
        }
        node.markDirty()
    }

    private fun createPrimitive(a: Args): ToolResult {
        val kindName = a.string("kind", "cube").lowercase(Locale.ROOT)
        val kind = when (kindName) {
            "cube", "box" -> Primitives.Kind.CUBE
            "sphere", "ball" -> Primitives.Kind.SPHERE
            "cylinder", "tube" -> Primitives.Kind.CYLINDER
            "cone" -> Primitives.Kind.CONE
            "torus", "ring" -> Primitives.Kind.TORUS
            "plane" -> Primitives.Kind.PLANE
            "icosphere" -> Primitives.Kind.ICOSPHERE
            else -> return ToolResult(false, "Tipo de primitiva desconocido: $kindName")
        }
        val name = newNamed(a.stringOrNull("name") ?: kind.label)
        val node = doc.newNode(name)
        node.setEditableMesh(EditableMesh.fromPrimitive(kind), smooth = kind != Primitives.Kind.CUBE)
        node.material = materialFor(a.stringOrNull("color"))
        applyTransform(node, a)
        return ToolResult(true, "Creado «$name» (${kind.label})", node.id)
    }

    private fun createMesh(a: Args): ToolResult {
        val points = a.points("vertices") ?: return ToolResult(false, "Faltan los vértices")
        val faces = a.faces("faces") ?: return ToolResult(false, "Faltan las caras")
        if (points.size < 3) return ToolResult(false, "Se necesitan al menos 3 vértices")
        val mesh = EditableMesh()
        for (p in points) mesh.addVertex(p[0], p[1], p[2])
        for (f in faces) {
            if (f.any { it >= points.size }) {
                return ToolResult(false, "Una cara usa un índice de vértice inexistente")
            }
            mesh.addFace(f)
        }
        if (mesh.faces.isEmpty()) return ToolResult(false, "La malla no tiene caras válidas")
        val repaired = MeshOps.repair(mesh)
        if (repaired.faces.isEmpty()) return ToolResult(false, "La malla quedó vacía tras repararla")
        val name = newNamed(a.stringOrNull("name") ?: "Malla")
        val node = doc.newNode(name)
        node.setEditableMesh(repaired, smooth = false)
        node.material = materialFor(a.stringOrNull("color"))
        return ToolResult(
            true,
            "Creada «$name»: ${repaired.vertexCount} vértices, ${repaired.faces.size} caras",
            node.id
        )
    }

    private fun createCurve(a: Args): ToolResult {
        val points = a.points("points") ?: return ToolResult(false, "Faltan los puntos de la curva")
        if (points.size < 2) return ToolResult(false, "La curva necesita al menos 2 puntos")
        if (points.size > 12) return ToolResult(false, "Máximo 12 puntos de control")
        val kind = when (a.string("curve", "catmull-rom").lowercase(Locale.ROOT)) {
            "bezier", "bézier" -> Curves.Kind.BEZIER
            "polyline", "linea", "línea" -> Curves.Kind.POLYLINE
            else -> Curves.Kind.CATMULL_ROM
        }
        val radius = a.number("radius", 0.06f).coerceIn(0.005f, 0.5f)
        val sides = a.int("sides", 8).coerceIn(3, 32)
        val mesh = Curves.tube(points, kind, radius, sides, samples = 24)
        if (mesh.faces.isEmpty()) return ToolResult(false, "La curva no generó geometría")
        val name = newNamed(a.stringOrNull("name") ?: "Curva")
        val node = doc.newNode(name)
        node.setEditableMesh(mesh, smooth = true)
        node.material = materialFor(null)
        return ToolResult(true, "Creada «$name»: curva ${kind.label} con ${mesh.faces.size} caras", node.id)
    }

    private fun editSelected(a: Args, op: (EditableMesh, Args) -> EditableMesh): ToolResult {
        val node = node(a)
        val original = node.editableMesh()
            ?: return ToolResult(false, "«${node.name}» no tiene malla editable")
        val before = original.copy()
        val beforeMesh = node.mesh
        try {
            val result = op(original, a)
            val validation = MeshOps.validate(result)
            if (!validation.ok) {
                throw MeshOps.MeshOpException("La malla resultante no es válida: ${validation.problems.first()}")
            }
            if (result.faces.isEmpty()) throw MeshOps.MeshOpException("La operación dejó la malla vacía")
            node.setEditableMesh(result)
            return ToolResult(
                true,
                "«${node.name}»: ${result.faces.size} caras, ${result.triangleCount()} triángulos",
                node.id,
                if (validation.degenerateFaces > 0) {
                    listOf("Se descartaron ${validation.degenerateFaces} caras degeneradas")
                } else emptyList()
            )
        } catch (e: Exception) {
            // Restauración exacta: el nodo vuelve a como estaba antes de la operación.
            node.setEditableMesh(before)
            node.mesh = beforeMesh
            throw e
        }
    }

    private fun sculpt(a: Args): ToolResult {
        val node = node(a)
        val center = a.vec3("center") ?: return ToolResult(false, "Falta el centro del pincel")
        val radius = a.number("radius", 0.2f)
        val strength = a.number("strength", 0.05f)
        val mode = when (a.string("mode", "inflate").lowercase(Locale.ROOT)) {
            "smooth", "suavizar" -> MeshOps.SculptMode.SMOOTH
            "pinch", "pellizcar" -> MeshOps.SculptMode.PINCH
            else -> MeshOps.SculptMode.INFLATE
        }
        return editSelected(a) { mesh, _ ->
            MeshOps.sculpt(mesh, center, radius, strength, mode)
        }.let { result ->
            if (result.ok) result.copyMessage("«${node.name}» esculpido (${mode.label})") else result
        }
    }

    private fun ToolResult.copyMessage(message: String): ToolResult =
        ToolResult(ok, message, nodeId, warnings)

    private fun boolean(a: Args): ToolResult {
        val first = node(a, "object")
        val secondName = a.stringOrNull("other") ?: return ToolResult(false, "Falta el segundo objeto")
        val second = findNode(secondName)
            ?: return ToolResult(false, "No encuentro el objeto «$secondName»")
        if (first.id == second.id) return ToolResult(false, "No se puede operar un objeto consigo mismo")
        val meshA = first.editableMesh() ?: return ToolResult(false, "«${first.name}» no tiene malla")
        val meshB = second.editableMesh() ?: return ToolResult(false, "«${second.name}» no tiene malla")
        if (!Csg.withinBudget(meshA, meshB)) {
            return ToolResult(
                false,
                "La operación es demasiado pesada (${meshA.faces.size} × ${meshB.faces.size} caras). " +
                    "Reduce los objetos antes."
            )
        }
        // Los objetos en distinta posición deben operar en el mismo espacio: se lleva B al espacio
        // de A aplicando la transformación relativa de sus matrices de mundo.
        val worldA = first.worldMatrix().copyOf()
        val worldB = second.worldMatrix().copyOf()
        val invA = FloatArray(16)
        if (!com.arena.modeler.core.M4.invert(invA, worldA)) {
            return ToolResult(false, "«${first.name}» tiene una transformación no invertible")
        }
        val relative = FloatArray(16)
        com.arena.modeler.core.M4.multiply(relative, invA, worldB)
        val meshBinA = meshB.copy().transform(relative)

        val op = when (a.string("operation", "difference").lowercase(Locale.ROOT)) {
            "union", "unir" -> Csg.BooleanOp.UNION
            "intersection", "interseccion", "intersección" -> Csg.BooleanOp.INTERSECTION
            else -> Csg.BooleanOp.DIFFERENCE
        }
        val before = meshA.copy()
        try {
            val result = Csg.apply(meshA, meshBinA, op)
            if (result.faces.isEmpty()) throw Csg.CsgException("El resultado quedó vacío")
            first.setEditableMesh(result)
            doc.root.children.remove(second)
            second.removeFromParent()
            return ToolResult(
                true,
                "${op.label} aplicada: «${first.name}» = ${result.faces.size} caras; «${second.name}» eliminado",
                first.id
            )
        } catch (e: Exception) {
            first.setEditableMesh(before)
            throw e
        }
    }

    private fun assignMaterial(a: Args): ToolResult {
        val node = node(a)
        val hex = a.stringOrNull("color")
        val emissionHex = a.stringOrNull("emission")
        val mat = node.material ?: doc.materialFor().also { node.material = it }
        if (hex != null) {
            val rgba = parseColor(hex) ?: return ToolResult(false, "Color inválido: $hex")
            // Un material compartido no debe cambiar bajo los pies de otro objeto.
            val shared = doc.allNodes().count { it.material === mat } > 1
            if (shared) {
                val copy = mat.copy()
                copy.name = "${mat.name}_${node.id}"
                doc.materials.add(copy)
                node.material = copy
                copy.baseColor = rgba
            } else {
                mat.baseColor = rgba
            }
        }
        val target = node.material ?: mat
        if (a.has("metallic")) target.metallic = a.number("metallic", 0f).coerceIn(0f, 1f)
        if (a.has("roughness")) target.roughness = a.number("roughness", 0.6f).coerceIn(0.02f, 1f)
        if (emissionHex != null) {
            val e = parseColor(emissionHex) ?: return ToolResult(false, "Emisión inválida: $emissionHex")
            target.emission = floatArrayOf(e[0], e[1], e[2])
        }
        return ToolResult(true, "Material de «${node.name}» actualizado", node.id)
    }

    private fun transformObject(a: Args): ToolResult {
        val node = node(a)
        if (!a.has("position") && !a.has("rotation") && !a.has("scale")) {
            return ToolResult(false, "Indica al menos posición, rotación o escala")
        }
        applyTransform(node, a)
        return ToolResult(true, "«${node.name}» transformado", node.id)
    }

    private fun duplicateObject(a: Args): ToolResult {
        val source = node(a)
        val name = newNamed(a.stringOrNull("name") ?: source.name.substringBefore('.'))
        val copy = doc.newNode(name)
        copy.mesh = source.mesh?.copy()
        copy.material = source.material
        copy.position = source.position.copy()
        copy.rotation = source.rotation.copy()
        copy.scale = source.scale.copy()
        val offset = a.vec3("offset")
        if (offset != null) {
            copy.position = V3(
                source.position.x + offset[0],
                source.position.y + offset[1],
                source.position.z + offset[2]
            )
        }
        copy.markDirty()
        return ToolResult(true, "Duplicado «${source.name}» → «$name»", copy.id)
    }

    private fun deleteObject(a: Args): ToolResult {
        val node = node(a)
        doc.root.children.remove(node)
        node.removeFromParent()
        // También se elimina de los clips de animación, para no dejar canales huérfanos.
        for (clip in doc.animations) {
            clip.channels.removeAll { it.nodeId == node.id }
        }
        return ToolResult(true, "Eliminado «${node.name}»")
    }

    /** Resumen compacto de la escena: lo que el modelo necesita para razonar sin ver la malla. */
    fun inspectScene(): String {
        val sb = StringBuilder()
        sb.append("Escena «${doc.name}»: ")
        val nodes = doc.allNodes().filter { it.mesh != null }
        sb.append("${nodes.size} objetos con malla, ${doc.totalTriangles()} triángulos.\n")
        for (n in nodes.take(40)) {
            val mesh = n.mesh ?: continue
            val b = mesh.bounds()
            val sizeX = "%.2f".format(Locale.US, b[3] - b[0])
            val sizeY = "%.2f".format(Locale.US, b[4] - b[1])
            val sizeZ = "%.2f".format(Locale.US, b[5] - b[2])
            val mat = n.material
            val color = mat?.let {
                String.format(
                    Locale.US, "#%02X%02X%02X",
                    (it.baseColor[0] * 255).toInt().coerceIn(0, 255),
                    (it.baseColor[1] * 255).toInt().coerceIn(0, 255),
                    (it.baseColor[2] * 255).toInt().coerceIn(0, 255)
                )
            } ?: "sin material"
            sb.append(
                "- ${n.name}: ${mesh.triangleCount} triángulos, " +
                    "tamaño ${sizeX}×${sizeY}×${sizeZ}, " +
                    "posición (%.2f, %.2f, %.2f), color %s\n".format(
                        Locale.US, n.position.x, n.position.y, n.position.z, color
                    )
            )
        }
        if (nodes.size > 40) sb.append("… y ${nodes.size - 40} objetos más\n")
        if (doc.animations.isNotEmpty()) {
            sb.append("Animaciones: ${doc.animations.joinToString { it.name }}\n")
        }
        return sb.toString()
    }

    private fun exportModel(a: Args): ToolResult {
        val hook = exportHook ?: return ToolResult(false, "La exportación no está disponible ahora mismo")
        val formatName = a.string("format", "glb").lowercase(Locale.ROOT)
        val format = when (formatName) {
            "glb" -> ExportFormat.GLB
            "gltf" -> ExportFormat.GLTF
            "obj" -> ExportFormat.OBJ
            "stl" -> ExportFormat.STL_BINARY
            "ply" -> ExportFormat.PLY_BINARY
            "fbx" -> ExportFormat.FBX
            else -> return ToolResult(false, "Formato no soportado: $formatName")
        }
        return try {
            val path = hook(format, a.stringOrNull("name"))
            ToolResult(true, "Exportado a $path")
        } catch (e: Exception) {
            ToolResult(false, "La exportación falló: ${e.message}")
        }
    }

    /** Útil para las pruebas: lista de nombres aceptados por las herramientas. */
    fun objectNames(): List<String> = doc.allNodes().map { it.name }

    companion object {
        /** Paleta que se ofrece al modelo para que no invente colores raros. */
        val palette: List<String> = listOf(
            "#E7E9EE", "#B9BEC9", "#7E8593", "#3A3F4A",
            "#E8892B", "#E8C13B", "#57C06B", "#3D9BE0",
            "#4C63D2", "#9A5BD6", "#D6559B", "#D64C4C"
        )
    }
}

/** Helper para construir herramientas desde código (atajos de la UI). */
object ToolCalls {
    fun of(tool: String, vararg pairs: Pair<String, Any>): ToolCall {
        val args = JSONObject()
        for ((k, v) in pairs) args.put(k, v)
        return ToolCall(tool, args)
    }

    fun points(list: List<FloatArray>): JSONArray {
        val arr = JSONArray()
        for (p in list) arr.put(JSONArray().put(p[0].toDouble()).put(p[1].toDouble()).put(p[2].toDouble()))
        return arr
    }
}
