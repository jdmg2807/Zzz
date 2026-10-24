package com.arena.modeler.io

import com.arena.modeler.core.M4
import com.arena.modeler.core.Quat
import com.arena.modeler.core.V3
import com.arena.modeler.scene.AnimChannel
import com.arena.modeler.scene.AnimPath
import com.arena.modeler.scene.AnimationClip
import com.arena.modeler.scene.Interpolation
import com.arena.modeler.scene.Joint
import com.arena.modeler.scene.Material
import com.arena.modeler.scene.MeshData
import com.arena.modeler.scene.SceneDocument
import com.arena.modeler.scene.SceneNode
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * glTF 2.0 reader/writer (GLB binary and .gltf with the buffer embedded in base64).
 *
 * glTF is the native format of the app because it is the only one that carries, in one file:
 * geometry, PBR materials, node hierarchy, **skins** (bones with inverse bind matrices) and
 * **animations** (translation/rotation/scale channels with interpolation). Everything else the app
 * supports is mapped onto this model on the way in and on the way out.
 *
 * No third-party library is used: the writer builds the JSON and the binary chunk by hand and the
 * reader walks the accessors, which keeps the APK small and the behaviour predictable.
 */
object Gltf {

    // ------------------------------------------------------------------ writer

    private const val COMP_FLOAT = 5126
    private const val COMP_UINT = 5125
    private const val COMP_USHORT = 5123
    private const val COMP_UBYTE = 5121
    private const val COMP_SHORT = 5122

    private class BinBuilder {
        private val out = ByteArrayOutputStream()
        var offset = 0

        fun write(bytes: ByteArray): Int {
            val start = offset
            out.write(bytes)
            offset += bytes.size
            while (offset % 4 != 0) {
                out.write(0)
                offset++
            }
            return start
        }

        fun floats(data: FloatArray): Int {
            val b = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            b.asFloatBuffer().put(data)
            return write(b.array())
        }

        fun ints(data: IntArray): Int {
            val b = ByteBuffer.allocate(data.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            b.asIntBuffer().put(data)
            return write(b.array())
        }

        fun shorts(data: ShortArray): Int {
            val b = ByteBuffer.allocate(data.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            b.asShortBuffer().put(data)
            return write(b.array())
        }

        fun bytes(data: ByteArray): Int = write(data)

        fun toByteArray(): ByteArray = out.toByteArray()
    }

    private class Writer {
        val bin = BinBuilder()
        val accessors = JSONArray()
        val bufferViews = JSONArray()
        val meshes = JSONArray()
        val materials = JSONArray()
        val nodes = JSONArray()
        val animations = JSONArray()
        val skins = JSONArray()
        val textures = JSONArray()
        val images = JSONArray()
        val samplersJson = JSONArray()
        val nodeIndex = HashMap<Int, Int>()   // scene node id -> glTF node index

        fun addBufferView(offset: Int, length: Int, target: Int? = null): Int {
            val o = JSONObject()
            o.put("buffer", 0)
            o.put("byteOffset", offset)
            o.put("byteLength", length)
            if (target != null) o.put("target", target)
            bufferViews.put(o)
            return bufferViews.length() - 1
        }

        fun addAccessor(
            view: Int, componentType: Int, count: Int, type: String,
            min: FloatArray? = null, max: FloatArray? = null
        ): Int {
            val a = JSONObject()
            a.put("bufferView", view)
            a.put("componentType", componentType)
            a.put("count", count)
            a.put("type", type)
            if (min != null) {
                val arr = JSONArray()
                for (v in min) arr.put(v.toDouble())
                a.put("min", arr)
            }
            if (max != null) {
                val arr = JSONArray()
                for (v in max) arr.put(v.toDouble())
                a.put("max", arr)
            }
            accessors.put(a)
            return accessors.length() - 1
        }

        fun floatsAccessor(data: FloatArray, type: String, comps: Int, withBounds: Boolean = false): Int {
            val offset = bin.floats(data)
            val view = addBufferView(offset, data.size * 4, 34962)
            val count = data.size / comps
            var min: FloatArray? = null
            var max: FloatArray? = null
            if (withBounds) {
                min = FloatArray(comps) { Float.MAX_VALUE }
                max = FloatArray(comps) { -Float.MAX_VALUE }
                for (i in 0 until count) {
                    for (c in 0 until comps) {
                        val v = data[i * comps + c]
                        if (v < min[c]) min[c] = v
                        if (v > max[c]) max[c] = v
                    }
                }
            }
            return addAccessor(view, COMP_FLOAT, count, type, min, max)
        }
    }

    fun writeGlb(doc: SceneDocument): ByteArray {
        val w = build(doc)
        val bin = w.bin.toByteArray()

        val json = w.buildRoot(glb = true)
        var jsonBytes = json.toString().toByteArray(Charsets.UTF_8)
        while (jsonBytes.size % 4 != 0) jsonBytes += ' '.code.toByte()

        var binBytes = bin
        while (binBytes.size % 4 != 0) binBytes += 0.toByte()

        val total = 12 + 8 + jsonBytes.size + 8 + binBytes.size
        val out = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        out.putInt(0x46546C67)          // "glTF"
        out.putInt(2)                    // version
        out.putInt(total)
        out.putInt(jsonBytes.size)
        out.putInt(0x4E4F534A)          // "JSON"
        out.put(jsonBytes)
        out.putInt(binBytes.size)
        out.putInt(0x004E4942)          // "BIN\0"
        out.put(binBytes)
        return out.array()
    }

    fun writeGltf(doc: SceneDocument): ByteArray {
        val w = build(doc)
        val bin = w.bin.toByteArray()
        val root = w.buildRoot(glb = false)
        val buffers = JSONArray()
        val b = JSONObject()
        b.put("byteLength", bin.size)
        b.put("uri", "data:application/octet-stream;base64," + com.arena.modeler.core.Text64.encode(bin))
        buffers.put(b)
        root.put("buffers", buffers)
        return root.toString(1).toByteArray(Charsets.UTF_8)
    }

    private fun Writer.buildRoot(glb: Boolean): JSONObject {
        val root = JSONObject()
        root.put("asset", JSONObject().put("version", "2.0").put("generator", "VoxelForge 3D"))
        if (bufferViews.length() > 0) root.put("bufferViews", bufferViews)
        if (accessors.length() > 0) root.put("accessors", accessors)
        if (materials.length() > 0) root.put("materials", materials)
        if (textures.length() > 0) root.put("textures", textures)
        if (images.length() > 0) root.put("images", images)
        if (samplersJson.length() > 0) root.put("samplers", samplersJson)
        if (meshes.length() > 0) root.put("meshes", meshes)
        if (skins.length() > 0) root.put("skins", skins)
        root.put("nodes", nodes)
        if (animations.length() > 0) root.put("animations", animations)
        val scene = JSONObject()
        scene.put("nodes", JSONArray().put(0))
        scene.put("name", "Escena")
        root.put("scenes", JSONArray().put(scene))
        root.put("scene", 0)
        if (glb) root.put("buffers", JSONArray().put(JSONObject().put("byteLength", bin.offset)))
        return root
    }

    private fun build(doc: SceneDocument): Writer {
        val w = Writer()

        // Materials first: primitives reference them by index.
        val materialIndex = HashMap<Material, Int>()
        for (m in doc.materials) {
            val o = JSONObject()
            o.put("name", m.name)
            val pbr = JSONObject()
            val bc = JSONArray()
            for (v in m.baseColor) bc.put(v.toDouble())
            pbr.put("baseColorFactor", bc)
            pbr.put("metallicFactor", m.metallic.toDouble())
            pbr.put("roughnessFactor", m.roughness.toDouble())
            o.put("pbrMetallicRoughness", pbr)
            val em = JSONArray()
            for (v in m.emission) em.put(v.toDouble())
            o.put("emissiveFactor", em)
            o.put("doubleSided", m.doubleSided)
            materialIndex[m] = w.materials.length()
            w.materials.put(o)
        }

        // Flatten the tree, root first, so parents always exist before their children.
        val ordered = ArrayList<SceneNode>()
        fun walk(n: SceneNode) {
            ordered.add(n)
            for (c in n.children) walk(c)
        }
        walk(doc.root)

        // One glTF node per scene node. Children are filled afterwards.
        for (n in ordered) {
            val o = JSONObject()
            o.put("name", n.name)
            if (n.position.length() > 1e-6f) {
                val t = JSONArray(); t.put(n.position.x.toDouble()); t.put(n.position.y.toDouble()); t.put(n.position.z.toDouble())
                o.put("translation", t)
            }
            if (kotlin.math.abs(n.rotation.w - 1f) > 1e-5f || n.rotation.x != 0f || n.rotation.y != 0f || n.rotation.z != 0f) {
                val r = JSONArray()
                r.put(n.rotation.x.toDouble()); r.put(n.rotation.y.toDouble())
                r.put(n.rotation.z.toDouble()); r.put(n.rotation.w.toDouble())
                o.put("rotation", r)
            }
            if (n.scale.x != 1f || n.scale.y != 1f || n.scale.z != 1f) {
                val s = JSONArray(); s.put(n.scale.x.toDouble()); s.put(n.scale.y.toDouble()); s.put(n.scale.z.toDouble())
                o.put("scale", s)
            }
            if (!n.visible) o.put("extras", JSONObject().put("visible", false))
            w.nodeIndex[n.id] = w.nodes.length()
            w.nodes.put(o)
        }
        for (n in ordered) {
            if (n.children.isEmpty()) continue
            val arr = JSONArray()
            for (c in n.children) arr.put(w.nodeIndex[c.id] ?: 0)
            w.nodes.getJSONObject(w.nodeIndex[n.id] ?: 0).put("children", arr)
        }

        // Meshes.
        for (n in ordered) {
            val mesh = n.mesh ?: continue
            val prim = JSONObject()
            val attrs = JSONObject()
            attrs.put(
                "POSITION", w.floatsAccessor(mesh.positions, "VEC3", 3, withBounds = true)
            )
            if (mesh.normals != null) attrs.put("NORMAL", w.floatsAccessor(mesh.normals!!, "VEC3", 3))
            if (mesh.uvs != null) attrs.put("TEXCOORD_0", w.floatsAccessor(mesh.uvs!!, "VEC2", 2))
            if (mesh.jointIndices != null && mesh.jointWeights != null) {
                val idxBytes = ByteArray(mesh.jointIndices!!.size * 2)
                val ib = ByteBuffer.wrap(idxBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (v in mesh.jointIndices!!) ib.putShort(v)
                val off = w.bin.bytes(idxBytes)
                val view = w.addBufferView(off, idxBytes.size, 34962)
                attrs.put("JOINTS_0", w.addAccessor(view, COMP_USHORT, mesh.vertexCount, "VEC4"))
                attrs.put("WEIGHTS_0", w.floatsAccessor(mesh.jointWeights!!, "VEC4", 4))
            }
            prim.put("attributes", attrs)
            val idxOffset = w.bin.ints(mesh.indices)
            val idxView = w.addBufferView(idxOffset, mesh.indices.size * 4, 34963)
            prim.put("indices", w.addAccessor(idxView, COMP_UINT, mesh.indices.size, "SCALAR"))
            val mat = n.material
            if (mat != null) {
                prim.put("material", materialIndex[mat] ?: 0)
                prim.put("mode", 4)   // TRIANGLES
            }
            val meshJson = JSONObject()
            meshJson.put("name", n.name + "_mesh")
            meshJson.put("primitives", JSONArray().put(prim))
            val meshIdx = w.meshes.length()
            w.meshes.put(meshJson)
            w.nodes.getJSONObject(w.nodeIndex[n.id] ?: 0).put("mesh", meshIdx)

            // Skin: joints + inverse bind matrices.
            if (n.skin.isNotEmpty()) {
                val jointsArr = JSONArray()
                for (j in n.skin) jointsArr.put(w.nodeIndex[j.nodeIndex] ?: 0)
                val ibm = FloatArray(n.skin.size * 16)
                for ((i, j) in n.skin.withIndex()) System.arraycopy(j.inverseBind, 0, ibm, i * 16, 16)
                val ibmOffset = w.bin.floats(ibm)
                val ibmView = w.addBufferView(ibmOffset, ibm.size * 4)
                val ibmAcc = w.addAccessor(ibmView, COMP_FLOAT, n.skin.size, "MAT4")
                val skin = JSONObject()
                skin.put("joints", jointsArr)
                skin.put("inverseBindMatrices", ibmAcc)
                w.skins.put(skin)
                w.nodes.getJSONObject(w.nodeIndex[n.id] ?: 0).put("skin", w.skins.length() - 1)
            }
        }

        // Animations.
        for (clip in doc.animations) {
            val anim = JSONObject()
            anim.put("name", clip.name)
            val samplersArr = JSONArray()
            val channelsArr = JSONArray()
            for (ch in clip.channels) {
                val inputOffset = w.bin.floats(ch.times)
                val inputView = w.addBufferView(inputOffset, ch.times.size * 4)
                var minT = Float.MAX_VALUE
                var maxT = -Float.MAX_VALUE
                for (t in ch.times) {
                    if (t < minT) minT = t
                    if (t > maxT) maxT = t
                }
                val inputAcc = w.addAccessor(
                    inputView, COMP_FLOAT, ch.times.size, "SCALAR",
                    floatArrayOf(if (ch.times.isEmpty()) 0f else minT),
                    floatArrayOf(if (ch.times.isEmpty()) 0f else maxT)
                )
                val valueAcc = w.floatsAccessor(
                    ch.values,
                    if (ch.path == AnimPath.ROTATION) "VEC4" else "VEC3",
                    ch.stride
                )
                val sampler = JSONObject()
                sampler.put("input", inputAcc)
                sampler.put("output", valueAcc)
                sampler.put(
                    "interpolation",
                    when (ch.interpolation) {
                        Interpolation.STEP -> "STEP"
                        Interpolation.CUBIC_SPLINE -> "CUBICSPLINE"
                        else -> "LINEAR"
                    }
                )
                val sIdx = samplersArr.length()
                samplersArr.put(sampler)
                val target = JSONObject()
                target.put("node", w.nodeIndex[ch.nodeId] ?: 0)
                target.put(
                    "path",
                    when (ch.path) {
                        AnimPath.TRANSLATION -> "translation"
                        AnimPath.ROTATION -> "rotation"
                        AnimPath.SCALE -> "scale"
                    }
                )
                val chObj = JSONObject()
                chObj.put("sampler", sIdx)
                chObj.put("target", target)
                channelsArr.put(chObj)
            }
            anim.put("samplers", samplersArr)
            anim.put("channels", channelsArr)
            w.animations.put(anim)
        }

        return w
    }

    // ------------------------------------------------------------------ reader

    fun read(bytes: ByteArray, fileName: String): SceneDocument {
        val parsed = parseContainer(bytes)
        return readJson(parsed.json, parsed.bin, fileName)
    }

    private class Container(val json: JSONObject, val bin: ByteArray?)

    private fun parseContainer(bytes: ByteArray): Container {
        if (bytes.size > 12 && leInt(bytes, 0) == 0x46546C67) {
            val total = leInt(bytes, 8)
            var pos = 12
            var json: JSONObject? = null
            var bin: ByteArray? = null
            while (pos + 8 <= minOf(total, bytes.size)) {
                val len = leInt(bytes, pos)
                val type = leInt(bytes, pos + 4)
                val start = pos + 8
                if (type == 0x4E4F534A) {
                    json = JSONObject(String(bytes, start, len, Charsets.UTF_8))
                } else if (type == 0x004E4942) {
                    bin = bytes.copyOfRange(start, start + len)
                }
                pos = start + len
                if (len <= 0) break
            }
            return Container(json ?: throw IllegalArgumentException("GLB sin bloque JSON"), bin)
        }
        val text = String(bytes, Charsets.UTF_8).trim()
        return Container(JSONObject(text), null)
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    private class ReaderContext(
        val root: JSONObject,
        val buffers: List<ByteArray>,
        val accessors: JSONArray,
        val bufferViews: JSONArray
    ) {
        fun accessorData(index: Int): AccessorData {
            val acc = accessors.getJSONObject(index)
            val compType = acc.getInt("componentType")
            val type = acc.getString("type")
            val count = acc.getInt("count")
            val comps = when (type) {
                "SCALAR" -> 1
                "VEC2" -> 2
                "VEC3" -> 3
                "VEC4" -> 4
                "MAT4" -> 16
                else -> 1
            }
            val compSize = when (compType) {
                COMP_UBYTE -> 1
                COMP_SHORT, COMP_USHORT -> 2
                else -> 4
            }
            val norm = acc.optBoolean("normalized", false)
            val out = AccessorData(compType, count, comps)
            if (!acc.has("bufferView")) return out
            val view = bufferViews.getJSONObject(acc.getInt("bufferView"))
            val bufferIndex = view.optInt("buffer", 0)
            val buf = buffers.getOrNull(bufferIndex) ?: return out
            val base = view.optInt("byteOffset", 0) + acc.optInt("byteOffset", 0)
            val stride = if (view.has("byteStride")) view.getInt("byteStride") else comps * compSize
            val values = FloatArray(count * comps)
            val ints = IntArray(count * comps)
            for (i in 0 until count) {
                val lineStart = base + i * stride
                for (c in 0 until comps) {
                    val o = lineStart + c * compSize
                    if (o + compSize > buf.size) break
                    val bb = ByteBuffer.wrap(buf, o, compSize).order(ByteOrder.LITTLE_ENDIAN)
                    when (compType) {
                        COMP_FLOAT -> values[i * comps + c] = bb.float
                        COMP_UINT -> ints[i * comps + c] = bb.int
                        COMP_USHORT -> {
                            val v = bb.short.toInt() and 0xFFFF
                            ints[i * comps + c] = v
                            values[i * comps + c] = if (norm) v / 65535f else v.toFloat()
                        }
                        COMP_SHORT -> {
                            val v = bb.short.toInt()
                            ints[i * comps + c] = v
                            values[i * comps + c] = if (norm) (v / 32767f).coerceIn(-1f, 1f) else v.toFloat()
                        }
                        COMP_UBYTE -> {
                            val v = bb.get().toInt() and 0xFF
                            ints[i * comps + c] = v
                            values[i * comps + c] = if (norm) v / 255f else v.toFloat()
                        }
                        else -> {}
                    }
                }
            }
            out.floats = values
            out.ints = ints
            return out
        }
    }

    private class AccessorData(val compType: Int, val count: Int, val comps: Int) {
        var floats: FloatArray = FloatArray(0)
        var ints: IntArray = IntArray(0)
        fun asInts(): IntArray = if (ints.isNotEmpty()) ints else Floats.toInts(floats)
        fun asFloats(componentType: Int): FloatArray = when (componentType) {
            COMP_UINT, COMP_USHORT, COMP_UBYTE, COMP_SHORT -> Floats.toFloats(ints)
            else -> floats
        }
    }

    private object Floats {
        fun toInts(f: FloatArray): IntArray = IntArray(f.size) { f[it].toInt() }
        fun toFloats(i: IntArray): FloatArray = FloatArray(i.size) { i[it].toFloat() }
    }

    private fun readJson(root: JSONObject, embeddedBin: ByteArray?, fileName: String): SceneDocument {
        val doc = SceneDocument(fileName.substringBeforeLast('.'))

        val buffers = ArrayList<ByteArray>()
        if (embeddedBin != null) buffers.add(embeddedBin)
        val buffersJson = root.optJSONArray("buffers")
        if (buffersJson != null) {
            for (i in 0 until buffersJson.length()) {
                val b = buffersJson.getJSONObject(i)
                val uri = b.optString("uri", "")
                if (i == 0 && embeddedBin != null) continue
                if (uri.startsWith("data:")) {
                    val comma = uri.indexOf(',')
                    if (comma > 0) {
                        try {
                            buffers.add(com.arena.modeler.core.Text64.decode(uri.substring(comma + 1)))
                        } catch (t: Throwable) {
                            buffers.add(ByteArray(0))
                        }
                    }
                } else if (uri.isNotBlank()) {
                    // External .bin cannot be resolved from a single imported file.
                    throw IllegalArgumentException(
                        "Este .gltf apunta a un archivo externo ($uri). " +
                            "Exporta como .glb o con los buffers incrustados."
                    )
                } else {
                    buffers.add(ByteArray(0))
                }
            }
        }
        if (buffers.isEmpty()) buffers.add(ByteArray(0))

        val ctx = ReaderContext(
            root,
            buffers,
            root.optJSONArray("accessors") ?: JSONArray(),
            root.optJSONArray("bufferViews") ?: JSONArray()
        )

        // Materials.
        val materialsJson = root.optJSONArray("materials")
        if (materialsJson != null) {
            for (i in 0 until materialsJson.length()) {
                val m = materialsJson.getJSONObject(i)
                val pbr = m.optJSONObject("pbrMetallicRoughness") ?: JSONObject()
                val color = floatArrayOf(0.8f, 0.8f, 0.85f, 1f)
                pbr.optJSONArray("baseColorFactor")?.let { arr ->
                    for (c in 0 until minOf(4, arr.length())) color[c] = arr.getDouble(c).toFloat()
                }
                val emission = floatArrayOf(0f, 0f, 0f)
                m.optJSONArray("emissiveFactor")?.let { arr ->
                    for (c in 0 until minOf(3, arr.length())) emission[c] = arr.getDouble(c).toFloat()
                }
                doc.materials.add(
                    Material(
                        name = m.optString("name", "Material.$i"),
                        baseColor = color,
                        metallic = pbr.optDouble("metallicFactor", 0.0).toFloat(),
                        roughness = pbr.optDouble("roughnessFactor", 0.6).toFloat(),
                        emission = emission,
                        doubleSided = m.optBoolean("doubleSided", false)
                    )
                )
            }
        }

        // Nodes: create them all first, then link by index.
        val nodesJson = root.optJSONArray("nodes") ?: JSONArray()
        val byIndex = HashMap<Int, SceneNode>()
        val gltfToScene = HashMap<Int, SceneNode>()
        for (i in 0 until nodesJson.length()) {
            val n = nodesJson.getJSONObject(i)
            val name = n.optString("name", "Nodo.$i")
            val sceneNode = doc.newNode(name)
            gltfToScene[i] = sceneNode
            byIndex[i] = sceneNode
            n.optJSONArray("translation")?.let {
                sceneNode.position = V3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat())
            }
            n.optJSONArray("rotation")?.let {
                sceneNode.rotation = Quat(
                    it.getDouble(0).toFloat(), it.getDouble(1).toFloat(),
                    it.getDouble(2).toFloat(), it.getDouble(3).toFloat()
                ).normalize()
            }
            n.optJSONArray("scale")?.let {
                sceneNode.scale = V3(it.getDouble(0).toFloat(), it.getDouble(1).toFloat(), it.getDouble(2).toFloat())
            }
            n.optJSONArray("matrix")?.let { arr ->
                val m = FloatArray(16) { arr.getDouble(it).toFloat() }
                decompose(m, sceneNode)
            }
        }
        // Children.
        for (i in 0 until nodesJson.length()) {
            val n = nodesJson.getJSONObject(i)
            val parent = gltfToScene[i] ?: continue
            n.optJSONArray("children")?.let { arr ->
                for (c in 0 until arr.length()) {
                    val child = gltfToScene[arr.getInt(c)] ?: continue
                    child.parent?.children?.remove(child)
                    parent.add(child)
                }
            }
        }
        // Detach any node whose parent ended up being the scene root twice (safe).
        doc.root.children.toList().forEach { child ->
            if (child.parent !== doc.root) doc.root.children.remove(child)
        }

        // Meshes.
        val meshesJson = root.optJSONArray("meshes") ?: JSONArray()
        val skinsJson = root.optJSONArray("skins")
        val accessorsJson = root.optJSONArray("accessors") ?: JSONArray()
        for (i in 0 until nodesJson.length()) {
            val n = nodesJson.getJSONObject(i)
            val sceneNode = gltfToScene[i] ?: continue
            if (!n.has("mesh")) continue
            val meshIdx = n.getInt("mesh")
            if (meshIdx >= meshesJson.length()) continue
            val meshJson = meshesJson.getJSONObject(meshIdx)
            val prims = meshJson.optJSONArray("primitives") ?: continue
            if (prims.length() == 0) continue
            // The app renders one primitive per node: merge them into one mesh.
            val mergedPos = ArrayList<Float>()
            val mergedNor = ArrayList<Float>()
            val mergedUv = ArrayList<Float>()
            val mergedIdx = ArrayList<Int>()
            val mergedJoints = ArrayList<Short>()
            val mergedWeights = ArrayList<Float>()
            var anyUv = true
            var anyNormal = true
            var anySkin = true
            var vertexBase = 0
            for (p in 0 until prims.length()) {
                val prim = prims.getJSONObject(p)
                val attrs = prim.optJSONObject("attributes") ?: continue
                if (!attrs.has("POSITION")) continue
                val pos = ctx.accessorData(attrs.getInt("POSITION"))
                val nor = if (attrs.has("NORMAL")) ctx.accessorData(attrs.getInt("NORMAL")) else null
                val uv = if (attrs.has("TEXCOORD_0")) ctx.accessorData(attrs.getInt("TEXCOORD_0")) else null
                val joints = if (attrs.has("JOINTS_0")) ctx.accessorData(attrs.getInt("JOINTS_0")) else null
                val weights = if (attrs.has("WEIGHTS_0")) ctx.accessorData(attrs.getInt("WEIGHTS_0")) else null
                val count = pos.count
                for (k in 0 until count * 3) mergedPos.add(pos.floats[k])
                if (nor != null) {
                    for (k in 0 until nor.count * 3) mergedNor.add(nor.floats[k])
                } else {
                    anyNormal = false
                }
                if (uv != null) {
                    for (k in 0 until uv.count * 2) mergedUv.add(uv.floats[k])
                } else {
                    anyUv = false
                }
                if (joints != null && weights != null) {
                    val ji = joints.asInts()
                    for (k in 0 until count * 4) mergedJoints.add(ji[k].toShort())
                    for (k in 0 until count * 4) mergedWeights.add(weights.floats[k])
                } else {
                    anySkin = false
                }
                if (prim.has("indices")) {
                    val idx = ctx.accessorData(prim.getInt("indices")).asInts()
                    for (v in idx) mergedIdx.add(v + vertexBase)
                } else {
                    for (v in 0 until count) mergedIdx.add(vertexBase + v)
                }
                vertexBase += count
                if (p == 0 && prim.has("material")) {
                    val mi = prim.getInt("material")
                    if (mi < doc.materials.size) sceneNode.material = doc.materials[mi]
                }
            }
            if (mergedPos.isEmpty()) continue
            val mesh = MeshData(
                positions = mergedPos.toFloatArray(),
                indices = mergedIdx.toIntArray(),
                normals = if (anyNormal && mergedNor.size == mergedPos.size) mergedNor.toFloatArray() else null,
                uvs = if (anyUv && mergedUv.size == mergedPos.size / 3 * 2) mergedUv.toFloatArray() else null,
                jointIndices = if (anySkin && mergedJoints.isNotEmpty()) mergedJoints.toShortArray() else null,
                jointWeights = if (anySkin && mergedWeights.isNotEmpty()) mergedWeights.toFloatArray() else null
            )
            if (mesh.normals == null) mesh.recomputeNormals()
            sceneNode.mesh = mesh
            if (sceneNode.material == null) {
                sceneNode.material = doc.materials.firstOrNull() ?: doc.materialFor()
            }

            // Skin.
            if (n.has("skin") && skinsJson != null) {
                val skinIdx = n.getInt("skin")
                if (skinIdx < skinsJson.length()) {
                    val skin = skinsJson.getJSONObject(skinIdx)
                    val jointsArr = skin.optJSONArray("joints")
                    val ibmAcc = if (skin.has("inverseBindMatrices")) skin.getInt("inverseBindMatrices") else -1
                    val ibm = if (ibmAcc >= 0 && ibmAcc < accessorsJson.length()) ctx.accessorData(ibmAcc).floats else FloatArray(0)
                    val list = ArrayList<Joint>()
                    if (jointsArr != null) {
                        for (j in 0 until jointsArr.length()) {
                            val nodeIdx = jointsArr.getInt(j)
                            val jointNode = gltfToScene[nodeIdx] ?: continue
                            val m = FloatArray(16)
                            if ((j + 1) * 16 <= ibm.size) System.arraycopy(ibm, j * 16, m, 0, 16) else M4.identity(m)
                            list.add(Joint(jointNode.id, m))
                        }
                    }
                    sceneNode.skin = list
                }
            }
        }

        // Animations.
        val animsJson = root.optJSONArray("animations")
        if (animsJson != null) {
            for (a in 0 until animsJson.length()) {
                val aj = animsJson.getJSONObject(a)
                val clip = AnimationClip(aj.optString("name", "Animación ${a + 1}"))
                val samplersArr = aj.optJSONArray("samplers") ?: continue
                val channelsArr = aj.optJSONArray("channels") ?: continue
                for (c in 0 until channelsArr.length()) {
                    val ch = channelsArr.getJSONObject(c)
                    val target = ch.optJSONObject("target") ?: continue
                    if (!target.has("node") || !target.has("path")) continue
                    val sceneNode = gltfToScene[target.getInt("node")] ?: continue
                    val path = AnimationClip.parsePath(target.getString("path")) ?: continue
                    val sIdx = ch.getInt("sampler")
                    if (sIdx >= samplersArr.length()) continue
                    val sampler = samplersArr.getJSONObject(sIdx)
                    val times = ctx.accessorData(sampler.getInt("input")).floats
                    val values = ctx.accessorData(sampler.getInt("output")).floats
                    if (times.isEmpty() || values.isEmpty()) continue
                    val interp = when (sampler.optString("interpolation", "LINEAR")) {
                        "STEP" -> Interpolation.STEP
                        "CUBICSPLINE" -> Interpolation.LINEAR   // cubics are read as linear
                        else -> Interpolation.LINEAR
                    }
                    clip.channels.add(AnimChannel(sceneNode.id, path, times, values, interp))
                }
                if (clip.channels.isNotEmpty()) doc.animations.add(clip)
            }
        }

        return doc
    }

    /** Splits a 4x4 into translation/quaternion/scale (used when a node has a `matrix`). */
    private fun decompose(m: FloatArray, node: SceneNode) {
        node.position = V3(m[12], m[13], m[14])
        var sx = kotlin.math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        val sy = kotlin.math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6])
        val sz = kotlin.math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
        if (sx < 1e-6f) sx = 1f
        val r00 = m[0] / sx
        val r10 = m[1] / sx
        val r20 = m[2] / sx
        val r01 = m[4] / (if (sy < 1e-6f) 1f else sy)
        val r11 = m[5] / (if (sy < 1e-6f) 1f else sy)
        val r21 = m[6] / (if (sy < 1e-6f) 1f else sy)
        val r02 = m[8] / (if (sz < 1e-6f) 1f else sz)
        val r12 = m[9] / (if (sz < 1e-6f) 1f else sz)
        val r22 = m[10] / (if (sz < 1e-6f) 1f else sz)
        val trace = r00 + r11 + r22
        val q = if (trace > 0f) {
            val s = kotlin.math.sqrt(trace + 1f) * 2f
            Quat((r21 - r12) / s, (r02 - r20) / s, (r10 - r01) / s, 0.25f * s)
        } else if (r00 > r11 && r00 > r22) {
            val s = kotlin.math.sqrt(1f + r00 - r11 - r22) * 2f
            Quat(0.25f * s, (r01 + r10) / s, (r02 + r20) / s, (r21 - r12) / s)
        } else if (r11 > r22) {
            val s = kotlin.math.sqrt(1f + r11 - r00 - r22) * 2f
            Quat((r01 + r10) / s, 0.25f * s, (r12 + r21) / s, (r02 - r20) / s)
        } else {
            val s = kotlin.math.sqrt(1f + r22 - r00 - r11) * 2f
            Quat((r02 + r20) / s, (r12 + r21) / s, 0.25f * s, (r10 - r01) / s)
        }
        node.rotation = q.normalize()
        node.scale = V3(sx, sy, sz)
    }
}
