package com.arena.modeler.io

import com.arena.modeler.core.Quat
import com.arena.modeler.core.V3
import com.arena.modeler.scene.AnimChannel
import com.arena.modeler.scene.AnimPath
import com.arena.modeler.scene.AnimationClip
import com.arena.modeler.scene.Material
import com.arena.modeler.scene.MeshData
import com.arena.modeler.scene.SceneDocument
import com.arena.modeler.scene.SceneNode
import java.util.Locale

/**
 * Autodesk FBX in its **ASCII** flavour, which is the one that can be written and read without
 * the proprietary SDK.
 *
 * What is exported per mesh: `Vertices`, `PolygonVertexIndex` (the last index of every polygon is
 * stored as `~index`), `LayerElementNormal`, a material and a `Model` per node with
 * `Lcl Translation / Lcl Rotation / Lcl Scaling`. Animations become an `AnimationStack` with one
 * `AnimationCurveNode` per property and one `AnimationCurve` per axis, connected with `OP`
 * connections exactly as the format requires. FBX time is in ticks of 1/46186158000 s.
 *
 * The reader understands the same subset (geometry, models, transforms and TRS animation curves),
 * which covers the files exported by Blender, Maya and 3ds Max with the default settings.
 */
object Fbx {

    private const val TICKS_PER_SECOND = 46186158000.0

    fun write(doc: SceneDocument): ByteArray {
        val sb = StringBuilder(1024 * 128)
        val meshes = doc.allNodes().filter { it.mesh != null && it.visible }
        val anims = doc.animations

        sb.append("; FBX 7.4.0 project file\n")
        sb.append("; Exported by VoxelForge 3D\n")
        sb.append("FBXHeaderExtension:  {\n  FBXHeaderVersion: 1003\n  FBXVersion: 7400\n")
        sb.append("  Creator: \"VoxelForge 3D\"\n}\n")
        sb.append("GlobalSettings:  {\n  Version: 1000\n  Properties70:  {\n")
        sb.append("    P: \"UpAxis\", \"int\", \"Integer\", \"\",1\n")
        sb.append("    P: \"UpAxisSign\", \"int\", \"Integer\", \"\",1\n")
        sb.append("    P: \"FrontAxis\", \"int\", \"Integer\", \"\",2\n")
        sb.append("    P: \"FrontAxisSign\", \"int\", \"Integer\", \"\",-1\n")
        sb.append("    P: \"CoordAxis\", \"int\", \"Integer\", \"\",0\n")
        sb.append("    P: \"CoordAxisSign\", \"int\", \"Integer\", \"\",1\n")
        sb.append("    P: \"UnitScaleFactor\", \"double\", \"Number\", \"\",100\n")
        sb.append("  }\n}\n")

        sb.append("Definitions:  {\n  Version: 100\n")
        sb.append("  Count: ").append(meshes.size + doc.materials.size + meshes.size + anims.size * 3).append('\n')
        if (meshes.isNotEmpty()) sb.append("  ObjectType: \"Geometry\" {\n    Count: ").append(meshes.size).append("\n  }\n")
        if (doc.materials.isNotEmpty()) sb.append("  ObjectType: \"Material\" {\n    Count: ").append(doc.materials.size).append("\n  }\n")
        sb.append("  ObjectType: \"Model\" {\n    Count: ").append(meshes.size).append("\n  }\n")
        if (anims.isNotEmpty()) {
            sb.append("  ObjectType: \"AnimationStack\" {\n    Count: ").append(anims.size).append("\n  }\n")
            sb.append("  ObjectType: \"AnimationLayer\" {\n    Count: ").append(anims.size).append("\n  }\n")
            sb.append("  ObjectType: \"AnimationCurveNode\" {\n    Count: ").append(anims.size * 3).append("\n  }\n")
            sb.append("  ObjectType: \"AnimationCurve\" {\n    Count: ")
                .append(anims.sumOf { it.channels.size * 3 }).append("\n  }\n")
        }
        sb.append("}\n\nObjects:  {\n")

        // ---- geometry + models -------------------------------------------------
        var geometryId = 1_000_000
        var modelId = 2_000_000
        val modelIdBySceneId = HashMap<Int, Long>()
        val geometryIdBySceneId = HashMap<Int, Long>()
        for (node in meshes) {
            val mesh = node.mesh!!
            val gid = geometryId++
            val mid = modelId++
            geometryIdBySceneId[node.id] = gid.toLong()
            modelIdBySceneId[node.id] = mid.toLong()

            sb.append("  Geometry: ").append(gid).append(", \"Geometry::").append(esc(node.name))
                .append("\", \"Mesh\" {\n")
            sb.append("    Vertices: *").append(mesh.positions.size).append(" {\n      a: ")
            for (i in mesh.positions.indices) {
                if (i > 0) sb.append(',')
                sb.append(f(mesh.positions[i]))
            }
            sb.append("\n    } \n")
            sb.append("    PolygonVertexIndex: *").append(mesh.indices.size).append(" {\n      a: ")
            for (t in 0 until mesh.indices.size / 3) {
                for (k in 0 until 3) {
                    if (t > 0 || k > 0) sb.append(',')
                    val v = mesh.indices[t * 3 + k]
                    sb.append(if (k == 2) (v.inv()) else v)
                }
            }
            sb.append("\n    } \n")
            sb.append("    GeometryVersion: 124\n")
            val normals = mesh.normals ?: run {
                val tmp = mesh.copy()
                tmp.recomputeNormals()
                tmp.normals
            }
            sb.append("    LayerElementNormal: 0 {\n      Version: 102\n      Name: \"\"\n")
            sb.append("      MappingInformationType: \"ByVertice\"\n")
            sb.append("      ReferenceInformationType: \"Direct\"\n      Normals: *")
                .append(normals!!.size).append(" {\n        a: ")
            for (i in normals.indices) {
                if (i > 0) sb.append(',')
                sb.append(f(normals[i]))
            }
            sb.append("\n      } \n    }\n")
            sb.append("    LayerElementMaterial: 0 {\n      Version: 101\n      Name: \"\"\n")
            sb.append("      MappingInformationType: \"AllSame\"\n      ReferenceInformationType: \"IndexToDirect\"\n")
            sb.append("      Materials: *1 {\n        a: 0 \n      }\n    }\n")
            sb.append("    Layer: 0 {\n      Version: 100\n      LayerElement:  {\n")
            sb.append("        Type: \"LayerElementNormal\"\n        TypedIndex: 0\n      }\n")
            sb.append("      LayerElement:  {\n        Type: \"LayerElementMaterial\"\n        TypedIndex: 0\n      }\n    }\n")
            sb.append("  }\n")

            sb.append("  Model: ").append(mid).append(", \"Model::").append(esc(node.name))
                .append("\", \"Mesh\" {\n    Version: 232\n    Properties70:  {\n")
            val e = eulerFromQuat(node.rotation)
            sb.append("      P: \"Lcl Translation\", \"Lcl Translation\", \"\", \"A\",")
                .append(f(node.position.x)).append(',').append(f(node.position.y)).append(',')
                .append(f(node.position.z)).append('\n')
            sb.append("      P: \"Lcl Rotation\", \"Lcl Rotation\", \"\", \"A\",")
                .append(f(e[0])).append(',').append(f(e[1])).append(',').append(f(e[2])).append('\n')
            sb.append("      P: \"Lcl Scaling\", \"Lcl Scaling\", \"\", \"A\",")
                .append(f(node.scale.x)).append(',').append(f(node.scale.y)).append(',')
                .append(f(node.scale.z)).append('\n')
            sb.append("    }\n    Shading: T\n    Culling: \"CullingOff\"\n  }\n")
        }

        // ---- materials ---------------------------------------------------------
        var materialId = 3_000_000
        for (m in doc.materials) {
            val id = materialId++
            sb.append("  Material: ").append(id).append(", \"Material::").append(esc(m.name))
                .append("\", \"\" {\n    Version: 102\n    ShadingModel: \"phong\"\n    MultiLayer: 0\n")
            sb.append("    Properties70:  {\n")
            sb.append("      P: \"AmbientColor\", \"Color\", \"\", \"A\",0.2,0.2,0.2\n")
            sb.append("      P: \"DiffuseColor\", \"Color\", \"\", \"A\",")
                .append(f(m.baseColor[0])).append(',').append(f(m.baseColor[1])).append(',')
                .append(f(m.baseColor[2])).append('\n')
            sb.append("      P: \"EmissiveColor\", \"Color\", \"\", \"A\",")
                .append(f(m.emission[0])).append(',').append(f(m.emission[1])).append(',')
                .append(f(m.emission[2])).append('\n')
            sb.append("      P: \"SpecularColor\", \"Color\", \"\", \"A\",")
                .append(f(m.metallic)).append(',').append(f(m.metallic)).append(',')
                .append(f(m.metallic)).append('\n')
            sb.append("      P: \"Shininess\", \"Number\", \"\", \"A\",")
                .append(f((1f - m.roughness) * 100f)).append('\n')
            sb.append("      P: \"Opacity\", \"Number\", \"\", \"A\",").append(f(m.baseColor[3])).append('\n')
            sb.append("    }\n  }\n")
        }

        // ---- animation ---------------------------------------------------------
        var stackId = 4_000_000L
        var layerId = 5_000_000L
        var curveNodeId = 6_000_000L
        var curveId = 7_000_000L
        val connections = StringBuilder()
        for (clip in anims) {
            val sid = stackId++
            val lid = layerId++
            sb.append("  AnimationStack: ").append(sid).append(", \"AnimStack::").append(esc(clip.name))
                .append("\", \"\" {\n    Properties70:  {\n")
            sb.append("      P: \"LocalStart\", \"KTime\", \"Time\", \"\",0\n")
            sb.append("      P: \"LocalStop\", \"KTime\", \"Time\", \"\",")
                .append((clip.duration() * TICKS_PER_SECOND).toLong()).append('\n')
            sb.append("    }\n  }\n")
            sb.append("  AnimationLayer: ").append(lid).append(", \"AnimLayer1\", \"\" {\n  }\n")
            connections.append("  C: \"OO\",").append(lid).append(',').append(sid).append('\n')

            // One curve node per (node, transform property), three curves per node (X/Y/Z).
            val groups = clip.channels.groupBy { it.nodeId to it.path }
            for ((key, channels) in groups) {
                val sceneId = key.first
                val path = key.second
                val modelIdOfNode = modelIdBySceneId[sceneId] ?: continue
                val cnId = curveNodeId++
                val propName = when (path) {
                    AnimPath.TRANSLATION -> "Lcl Translation"
                    AnimPath.ROTATION -> "Lcl Rotation"
                    AnimPath.SCALE -> "Lcl Scaling"
                }
                sb.append("  AnimationCurveNode: ").append(cnId).append(", \"AnimCurveNode::")
                    .append(propName).append("\", \"\" {\n    Properties70:  {\n")
                sb.append("      P: \"d|X\", \"Number\", \"\", \"A\",0\n")
                sb.append("      P: \"d|Y\", \"Number\", \"\", \"A\",0\n")
                sb.append("      P: \"d|Z\", \"Number\", \"\", \"A\",0\n")
                sb.append("    }\n  }\n")
                connections.append("  C: \"OP\",").append(cnId).append(',').append(modelIdOfNode)
                    .append(",\"").append(propName).append("\"\n")

                val channel = channels.firstOrNull()
                if (channel == null || channel.times.isEmpty()) continue
                // FBX curves are per axis; rotation channels store quaternions, so convert the
                // samples to Euler degrees per key before writing them out.
                val times = channel.times
                for (axis in 0 until 3) {
                    val values = FloatArray(times.size)
                    val buffer = FloatArray(4)
                    for (k in times.indices) {
                        channel.sample(times[k], buffer)
                        values[k] = when (path) {
                            AnimPath.ROTATION -> {
                                val e = eulerFromQuat(Quat(buffer[0], buffer[1], buffer[2], buffer[3]).normalize())
                                e[axis]
                            }
                            else -> buffer[axis]
                        }
                    }
                    val cid = curveId++
                    sb.append("  AnimationCurve: ").append(cid).append(", \"AnimCurve::\", \"\" {\n")
                    sb.append("    Default: 0\n    KeyVer: 4009\n    KeyTime: *").append(times.size)
                        .append(" {\n      a: ")
                    for (k in times.indices) {
                        if (k > 0) sb.append(',')
                        sb.append((times[k] * TICKS_PER_SECOND).toLong())
                    }
                    sb.append("\n    } \n")
                    sb.append("    KeyValueFloat: *").append(values.size).append(" {\n      a: ")
                    for (k in values.indices) {
                        if (k > 0) sb.append(',')
                        sb.append(f(values[k]))
                    }
                    sb.append("\n    } \n")
                    sb.append("    KeyAttrFlags: *1 {\n      a: 24840 \n    }\n")
                    sb.append("    KeyAttrDataFloat: *4 {\n      a: 0,0,218434821,0 \n    }\n")
                    sb.append("    KeyAttrRefCount: *1 {\n      a: ").append(values.size).append(" \n    }\n")
                    sb.append("  }\n")
                    connections.append("  C: \"OP\",").append(cid).append(',').append(cnId)
                        .append(", \"d|").append("XYZ"[axis]).append("\"\n")
                }
            }
        }
        sb.append("}\n\n")

        // ---- connections -------------------------------------------------------
        sb.append("Connections:  {\n")
        var materialIdx = 0
        for (node in meshes) {
            val gid = geometryIdBySceneId[node.id] ?: continue
            val mid = modelIdBySceneId[node.id] ?: continue
            sb.append("  C: \"OO\",").append(gid).append(',').append(mid).append('\n')
            sb.append("  C: \"OO\",").append(mid).append(",0\n")
            val matId = 3_000_000 + materialIdx
            if (materialIdx < doc.materials.size) {
                sb.append("  C: \"OO\",").append(matId).append(',').append(mid).append('\n')
            }
            materialIdx++
        }
        sb.append(connections)
        sb.append("}\n")
        sb.append("Takes:  {\n  Current: \"Take 001\"\n}\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    // ------------------------------------------------------------------ reader

    private class Obj(val id: String, val type: String, val name: String, val body: String)

    fun read(bytes: ByteArray, fileName: String): SceneDocument {
        val text = String(bytes, Charsets.UTF_8)
        if (!text.contains("FBXHeaderExtension") && !text.contains("Objects:")) {
            throw IllegalArgumentException("No parece un FBX ASCII")
        }
        val doc = SceneDocument(fileName.substringBeforeLast('.'))

        val objects = parseObjects(text)
        val geometries = objects.filter { it.type == "Geometry" }
        val models = objects.filter { it.type == "Model" }

        // Geometry: one node per Model when ids line up, otherwise one node per geometry.
        val materialByName = HashMap<String, Material>()
        for (o in objects.filter { it.type == "Material" }) {
            val color = propFloats(o.body, "DiffuseColor")
            val mat = Material(
                name = o.name,
                baseColor = floatArrayOf(
                    color.getOrElse(0) { 0.8f },
                    color.getOrElse(1) { 0.8f },
                    color.getOrElse(2) { 0.85f },
                    1f
                )
            )
            doc.materials.add(mat)
            materialByName[o.name] = mat
        }
        if (doc.materials.isEmpty()) doc.materials.add(Material("Material.1"))

        val geomById = HashMap<String, Obj>()
        for (g in geometries) geomById[g.id] = g

        fun buildMesh(g: Obj): MeshData? {
            val verts = floats(g.body, "Vertices")
            if (verts.size < 9) return null
            val polygon = ints(g.body, "PolygonVertexIndex")
            val nor = floats(g.body, "Normals")
            val indices = ArrayList<Int>(polygon.size + polygon.size / 3)
            var poly = ArrayList<Int>(4)
            for (raw in polygon) {
                if (raw < 0) {
                    poly.add(raw.inv())
                    for (k in 1 until poly.size - 1) {
                        indices.add(poly[0]); indices.add(poly[k]); indices.add(poly[k + 1])
                    }
                    poly = ArrayList(4)
                } else {
                    poly.add(raw)
                }
            }
            val positions = FloatArray(verts.size) { verts[it] }
            val mesh = MeshData(
                positions,
                indices.toIntArray(),
                if (nor.size == positions.size) FloatArray(nor.size) { nor[it] } else null,
                null
            )
            if (mesh.normals == null) mesh.recomputeNormals()
            return mesh
        }

        // Models carry the transforms; geometry is attached through the OO connections.
        val geomToModel = HashMap<String, String>()
        val modelToMaterial = HashMap<String, String>()
        val re = Regex("C:\\s*\"OO\",\\s*(-?\\d+),\\s*(-?\\d+)")
        for (m in re.findAll(text)) {
            val a = m.groupValues[1]
            val b = m.groupValues[2]
            if (geomById.containsKey(a) && objects.any { it.id == b && it.type == "Model" }) geomToModel[a] = b
            if (materialByName.isNotEmpty() && b != "0") {
                val matObj = objects.firstOrNull { it.id == a && it.type == "Material" }
                if (matObj != null) modelToMaterial[b] = matObj.name
            }
        }

        val nodeByModelId = HashMap<String, SceneNode>()
        for (model in models) {
            val node = doc.newNode(model.name.ifEmpty { "Modelo.${doc.allNodes().size}" })
            val t = propFloats(model.body, "Lcl Translation")
            if (t.size >= 3) node.position = V3(t[0], t[1], t[2])
            val r = propFloats(model.body, "Lcl Rotation")
            if (r.size >= 3) node.rotation = Quat.euler(r[0], r[1], r[2])
            val sc = propFloats(model.body, "Lcl Scaling")
            if (sc.size >= 3) node.scale = V3(sc[0], sc[1], sc[2])
            nodeByModelId[model.id] = node
            val matName = modelToMaterial[model.id]
            node.material = matName?.let { materialByName[it] } ?: doc.materials.first()
        }
        val attached = HashSet<String>()
        for ((geomId, modelId) in geomToModel) {
            val g = geomById[geomId] ?: continue
            val node = nodeByModelId[modelId] ?: continue
            node.mesh = buildMesh(g)
            attached.add(geomId)
        }
        // Geometry that no model references (or a file without models at all) is still imported,
        // one node per geometry, so nothing is silently lost.
        for (g in geometries) {
            if (!attached.add(g.id)) continue
            val mesh = buildMesh(g) ?: continue
            val node = doc.newNode(g.name.ifEmpty { "Geometria.${doc.allNodes().size}" })
            node.mesh = mesh
            node.material = doc.materials.first()
        }

        readAnimations(text, doc, objects, nodeByModelId)
        return doc
    }

    private fun readAnimations(
        text: String,
        doc: SceneDocument,
        objects: List<Obj>,
        nodeByModelId: Map<String, SceneNode>
    ) {
        val curves = objects.filter { it.type == "AnimationCurve" }
        if (curves.isEmpty()) return
        // Curve node -> (model, property) and curve -> (curve node, axis) from the OP connections.
        val curveNodeTarget = HashMap<String, Pair<String, String>>()
        val curveOwner = HashMap<String, Pair<String, String>>()
        val opRe = Regex("C:\\s*\"OP\",\\s*(-?\\d+),\\s*(-?\\d+),\\s*\"([^\"]+)\"")
        val modelByFbxId = nodeByModelId
        for (m in opRe.findAll(text)) {
            val child = m.groupValues[1]
            val parent = m.groupValues[2]
            val prop = m.groupValues[3]
            if (prop.startsWith("d|")) {
                curveOwner[child] = parent to prop
            } else {
                curveNodeTarget[child] = parent to prop
            }
        }
        val clip = AnimationClip("Animación")
        val channels = LinkedHashMap<Pair<Int, AnimPath>, Pair<FloatArray, MutableList<FloatArray>>>()
        for (curve in curves) {
            val times = longs(curve.body, "KeyTime").map { (it / TICKS_PER_SECOND).toFloat() }.toFloatArray()
            val values = floats(curve.body, "KeyValueFloat")
            if (times.isEmpty() || values.isEmpty()) continue
            val owner = curveOwner[curve.id] ?: continue
            val axisName = owner.second.removePrefix("d|")
            val axis = when (axisName) {
                "X" -> 0
                "Y" -> 1
                "Z" -> 2
                else -> continue
            }
            val target = curveNodeTarget[owner.first] ?: continue
            val modelId = target.first
            val prop = target.second
            val node = modelByFbxId[modelId] ?: continue
            val norm = prop.lowercase()
            val path = when {
                norm.contains("translation") || norm.contains("position") -> AnimPath.TRANSLATION
                norm.contains("rotation") -> AnimPath.ROTATION
                norm.contains("scaling") || norm.contains("scale") -> AnimPath.SCALE
                else -> AnimationClip.parsePath(prop)
            } ?: continue
            val key = node.id to path
            val entry = channels.getOrPut(key) { times.copyOf() to ArrayList() }
            // Store per-axis values, then merge into TRS/VEC4 after all three curves are read.
            while (entry.second.size <= axis) entry.second.add(FloatArray(times.size))
            entry.second[axis] = FloatArray(values.size) { values[it] }
        }
        for ((key, entry) in channels) {
            val times = entry.first
            val axes = entry.second
            val count = times.size
            val out: FloatArray
            if (key.second == AnimPath.ROTATION) {
                out = FloatArray(count * 4)
                for (k in 0 until count) {
                    val x = axes.getOrNull(0)?.getOrNull(k) ?: 0f
                    val y = axes.getOrNull(1)?.getOrNull(k) ?: 0f
                    val z = axes.getOrNull(2)?.getOrNull(k) ?: 0f
                    val q = Quat.euler(x, y, z)
                    out[k * 4] = q.x; out[k * 4 + 1] = q.y; out[k * 4 + 2] = q.z; out[k * 4 + 3] = q.w
                }
            } else {
                out = FloatArray(count * 3)
                for (k in 0 until count) {
                    out[k * 3] = axes.getOrNull(0)?.getOrNull(k) ?: 0f
                    out[k * 3 + 1] = axes.getOrNull(1)?.getOrNull(k) ?: 0f
                    out[k * 3 + 2] = axes.getOrNull(2)?.getOrNull(k) ?: 0f
                }
            }
            clip.channels.add(AnimChannel(key.first, key.second, times, out))
        }
        if (clip.channels.isNotEmpty()) doc.animations.add(clip)
    }

    /** Splits `Objects: { ... }` into typed blocks (id, name, body). */
    private fun parseObjects(text: String): List<Obj> {
        val start = text.indexOf("Objects:")
        if (start < 0) return emptyList()
        val out = ArrayList<Obj>()
        var i = start
        val header = Regex("(\\w+):\\s*(\\d+)\\s*,\\s*\"([^\"]*)\"")
        while (i < text.length) {
            val m = header.find(text, i) ?: break
            // Only accept blocks that are followed by a body in braces.
            val braceIdx = text.indexOf('{', m.range.last)
            if (braceIdx < 0) break
            // Entre el nombre y la llave solo puede venir la clase del objeto (`, "Mesh" `).
            val between = text.substring(m.range.last + 1, braceIdx)
            if (!Regex("^\\s*(,\\s*\"[^\"]*\"\\s*)?$").matches(between)) break
            val type = m.groupValues[1]
            if (type == "Connections" || type == "Takes") break
            var depth = 0
            var j = braceIdx
            while (j < text.length) {
                val c = text[j]
                if (c == '{') depth++
                if (c == '}') {
                    depth--
                    if (depth == 0) break
                }
                j++
            }
            if (j >= text.length) break
            out.add(Obj(m.groupValues[2], type, m.groupValues[3].substringAfterLast("::"), text.substring(braceIdx, j)))
            i = j + 1
        }
        return out
    }

    /**
     * Reads an FBX `P: "Name", "Type", "Label", "Flags", v, v, v` property. Strings can contain
     * commas, so the numeric part is taken after the last quoted field.
     */
    private fun propFloats(body: String, name: String): FloatArray {
        val line = Regex("P:\\s*\"" + Regex.escape(name) + "\"[^\\n]*").find(body)?.value ?: return FloatArray(0)
        val lastQuote = line.lastIndexOf('"')
        if (lastQuote < 0 || lastQuote == line.length - 1) return FloatArray(0)
        return line.substring(lastQuote + 1)
            .split(',')
            .mapNotNull { it.trim().toFloatOrNull() }
            .toFloatArray()
    }

    private fun floats(body: String, key: String): FloatArray {
        val m = Regex(Regex.escape(key) + ":\\s*\\*?(\\d+)?\\s*\\{\\s*a:\\s*([^}]*)\\}").find(body) ?: return FloatArray(0)
        return m.groupValues[2].split(',').mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
    }

    private fun ints(body: String, key: String): IntArray {
        val m = Regex(Regex.escape(key) + ":\\s*\\*?(\\d+)?\\s*\\{\\s*a:\\s*([^}]*)\\}").find(body) ?: return IntArray(0)
        return m.groupValues[2].split(',').mapNotNull { it.trim().toIntOrNull() }.toIntArray()
    }

    private fun longs(body: String, key: String): LongArray {
        val m = Regex(Regex.escape(key) + ":\\s*\\*?(\\d+)?\\s*\\{\\s*a:\\s*([^}]*)\\}").find(body) ?: return LongArray(0)
        return m.groupValues[2].split(',').mapNotNull { it.trim().toLongOrNull() }.toLongArray()
    }

    /**
     * Pitch/yaw/roll in degrees for [Quat.euler], which composes `Ry * Rx * Rz` (YXZ order), so
     * the inverse decomposition has to use the same order or a rotated import would drift.
     */
    fun eulerFromQuat(q: Quat): FloatArray {
        val x = q.x
        val y = q.y
        val z = q.z
        val w = q.w
        val toDeg = 180f / Math.PI.toFloat()
        // sin(pitch) = 2*(w*x - y*z)
        val sp = (2f * (w * x - y * z)).coerceIn(-1f, 1f)
        val pitch = Math.asin(sp.toDouble()).toFloat()
        val yaw = Math.atan2((2f * (x * z + w * y)).toDouble(), (1f - 2f * (x * x + y * y)).toDouble()).toFloat()
        val roll = Math.atan2((2f * (x * y + w * z)).toDouble(), (1f - 2f * (x * x + z * z)).toDouble()).toFloat()
        if (kotlin.math.abs(sp) > 0.9999f) {
            // Gimbal lock: only the combined yaw±roll is defined, keep everything on yaw.
            return floatArrayOf(pitch * toDeg, yaw * toDeg, 0f)
        }
        return floatArrayOf(pitch * toDeg, yaw * toDeg, roll * toDeg)
    }

    private fun f(v: Float): String = String.format(Locale.US, "%.6f", v)

    private fun esc(s: String): String = s.replace("\"", "'")
}
