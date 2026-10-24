package com.arena.modeler.io

import com.arena.modeler.scene.Material
import com.arena.modeler.scene.MeshData
import com.arena.modeler.scene.SceneDocument
import java.util.Locale

/**
 * Wavefront OBJ writer and reader.
 *
 * OBJ is the most universally supported text format: almost every 3D program, engine and slicer
 * reads it. It has no animation and no real materials (only a name pointing at a .mtl file), so
 * this writer exports the geometry of every visible node, with a `usemtl` per material, and the
 * reader rebuilds one node per `o`/`g` group. Rotations and scales baked into the node transform
 * are written into the vertices, because OBJ has no scene graph.
 */
object Obj {

    fun write(doc: SceneDocument): ByteArray {
        val sb = StringBuilder(1024 * 64)
        sb.append("# VoxelForge 3D — exportado como Wavefront OBJ\n")
        sb.append("# unidades: metros, Y hacia arriba\n")
        val mtlName = sanitize(doc.name) + ".mtl"
        sb.append("mtllib ").append(mtlName).append('\n')

        val mtl = StringBuilder()
        mtl.append("# VoxelForge 3D — materiales\n")
        var vBase = 1
        var vtBase = 1
        var vnBase = 1

        for (node in doc.allNodes()) {
            val mesh = node.mesh ?: continue
            if (!node.visible) continue
            val world = node.worldMatrix()
            sb.append("\no ").append(sanitize(node.name)).append('\n')
            val mat = node.material
            if (mat != null) sb.append("usemtl ").append(sanitize(mat.name)).append('\n')

            val pos = FloatArray(mesh.positions.size)
            val nor = FloatArray(mesh.positions.size)
            for (i in 0 until mesh.vertexCount) {
                val p = com.arena.modeler.core.M4.transformPoint(
                    world, mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2]
                )
                pos[i * 3] = p[0]; pos[i * 3 + 1] = p[1]; pos[i * 3 + 2] = p[2]
                val n = if (mesh.normals != null) {
                    com.arena.modeler.core.M4.transformDir(
                        world, mesh.normals!![i * 3], mesh.normals!![i * 3 + 1], mesh.normals!![i * 3 + 2]
                    )
                } else {
                    floatArrayOf(0f, 1f, 0f)
                }
                val len = kotlin.math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).coerceAtLeast(1e-6f)
                nor[i * 3] = n[0] / len; nor[i * 3 + 1] = n[1] / len; nor[i * 3 + 2] = n[2] / len
            }

            for (i in 0 until mesh.vertexCount) {
                sb.append("v ").append(f(pos[i * 3])).append(' ').append(f(pos[i * 3 + 1]))
                    .append(' ').append(f(pos[i * 3 + 2])).append('\n')
            }
            val hasUv = mesh.uvs != null
            if (hasUv) {
                val uv = mesh.uvs!!
                for (i in 0 until mesh.vertexCount) {
                    sb.append("vt ").append(f(uv[i * 2])).append(' ').append(f(uv[i * 2 + 1])).append('\n')
                }
            }
            for (i in 0 until mesh.vertexCount) {
                sb.append("vn ").append(f(nor[i * 3])).append(' ').append(f(nor[i * 3 + 1]))
                    .append(' ').append(f(nor[i * 3 + 2])).append('\n')
            }
            var i = 0
            while (i + 2 < mesh.indices.size) {
                // OBJ is 1-based and stacks the three tables separately.
                val a = mesh.indices[i]
                val b = mesh.indices[i + 1]
                val c = mesh.indices[i + 2]
                val va = vBase + a
                val vb = vBase + b
                val vc = vBase + c
                val na = vnBase + a
                val nb = vnBase + b
                val nc = vnBase + c
                if (hasUv) {
                    val ta = vtBase + a
                    val tb = vtBase + b
                    val tc = vtBase + c
                    sb.append("f $va/$ta/$na $vb/$tb/$nb $vc/$tc/$nc\n")
                } else {
                    sb.append("f $va//$na $vb//$nb $vc//$nc\n")
                }
                i += 3
            }
            vBase += mesh.vertexCount
            if (hasUv) vtBase += mesh.vertexCount
            vnBase += mesh.vertexCount

            if (mat != null && !mtl.contains("newmtl ${sanitize(mat.name)}")) {
                mtl.append("\nnewmtl ").append(sanitize(mat.name)).append('\n')
                mtl.append("Kd ").append(f(mat.baseColor[0])).append(' ')
                    .append(f(mat.baseColor[1])).append(' ').append(f(mat.baseColor[2])).append('\n')
                mtl.append("Ke ").append(f(mat.emission[0])).append(' ')
                    .append(f(mat.emission[1])).append(' ').append(f(mat.emission[2])).append('\n')
                mtl.append("Ks ").append(f(mat.metallic)).append(' ').append(f(mat.metallic))
                    .append(' ').append(f(mat.metallic)).append('\n')
                mtl.append("Ns ").append(f((1f - mat.roughness) * 500f)).append('\n')
                mtl.append("d ").append(f(mat.baseColor[3])).append('\n')
                mtl.append("illum 2\n")
            }
        }
        ObjSidecar.mtl = mtl.toString()
        ObjSidecar.mtlName = mtlName
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    /** Companion .mtl text produced by the last [write] call (the editor saves it next to the .obj). */
    object ObjSidecar {
        var mtl: String = ""
        var mtlName: String = "material.mtl"
    }

    fun read(bytes: ByteArray, name: String): SceneDocument {
        val doc = SceneDocument(name.substringBeforeLast('.'))
        val text = String(bytes, Charsets.UTF_8)
        val v = ArrayList<FloatArray>()
        val vt = ArrayList<FloatArray>()
        val vn = ArrayList<FloatArray>()
        var current: SceneDocumentBuilder? = null
        val groups = LinkedHashMap<String, SceneDocumentBuilder>()

        fun newGroup(gname: String) {
            val g = SceneDocumentBuilder(gname)
            groups[gname] = g
            current = g
        }
        newGroup("Objeto.1")

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parts = line.split(Regex("\\s+"))
            when (parts[0]) {
                "v" -> if (parts.size >= 4) {
                    v.add(floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat()))
                }
                "vt" -> if (parts.size >= 3) {
                    vt.add(floatArrayOf(parts[1].toFloat(), parts[2].toFloat()))
                }
                "vn" -> if (parts.size >= 4) {
                    vn.add(floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat()))
                }
                "o", "g" -> {
                    val gname = if (parts.size > 1) parts[1] else "Objeto.${groups.size + 1}"
                    if (groups[gname] == null) newGroup(gname)
                    else current = groups[gname]
                }
                "usemtl" -> if (parts.size > 1) current?.materialName = parts[1]
                "f" -> {
                    val g = current ?: continue
                    val face = ArrayList<IntArray>(parts.size - 1)
                    for (i in 1 until parts.size) {
                        val seg = parts[i].split('/')
                        val vi = resolveIndex(seg.getOrNull(0), v.size)
                        val ti = if (seg.size > 1) resolveIndex(seg.getOrNull(1), vt.size) else -1
                        val ni = if (seg.size > 2) resolveIndex(seg.getOrNull(2), vn.size) else -1
                        face.add(intArrayOf(vi, ti, ni))
                    }
                    // Triangulate the polygon as a fan (OBJ allows n-gons).
                    for (k in 1 until face.size - 1) {
                        g.triangles.add(intArrayOf(face[0][0], face[k][0], face[k + 1][0]))
                        g.uvRefs.add(intArrayOf(face[0][1], face[k][1], face[k + 1][1]))
                        g.normalRefs.add(intArrayOf(face[0][2], face[k][2], face[k + 1][2]))
                    }
                }
            }
        }

        for ((gname, g) in groups) {
            if (g.triangles.isEmpty()) continue
            // Rebuild a compact vertex list from the referenced positions.
            val map = HashMap<Int, Int>()
            val positions = ArrayList<Float>(g.triangles.size * 9)
            val normals = ArrayList<Float>(g.triangles.size * 9)
            val uvs = ArrayList<Float>(g.triangles.size * 6)
            val indices = ArrayList<Int>(g.triangles.size * 3)
            var hasUv = true
            var hasNormal = true
            for ((t, tri) in g.triangles.withIndex()) {
                for (k in 0 until 3) {
                    val vi = tri[k]
                    // The key includes the uv/normal index so seams are preserved.
                    val key = vi * 100003 + (g.uvRefs[t][k] + 1) * 7 + (g.normalRefs[t][k] + 1) * 13
                    var out = map[key]
                    if (out == null) {
                        out = positions.size / 3
                        map[key] = out
                        val p = v.getOrNull(vi) ?: floatArrayOf(0f, 0f, 0f)
                        positions.add(p[0]); positions.add(p[1]); positions.add(p[2])
                        val n = vn.getOrNull(g.normalRefs[t][k])
                        if (n != null) {
                            normals.add(n[0]); normals.add(n[1]); normals.add(n[2])
                        } else {
                            hasNormal = false
                        }
                        val uv = vt.getOrNull(g.uvRefs[t][k])
                        if (uv != null) {
                            uvs.add(uv[0]); uvs.add(uv[1])
                        } else {
                            hasUv = false
                        }
                    }
                    indices.add(out)
                }
            }
            val mesh = MeshData(
                positions.toFloatArray(),
                indices.toIntArray(),
                if (hasNormal) normals.toFloatArray() else null,
                if (hasUv) uvs.toFloatArray() else null
            )
            if (mesh.normals == null) mesh.recomputeNormals()
            val node = doc.newNode(gname)
            node.mesh = mesh
            val mat = Material(g.materialName.ifEmpty { gname })
            doc.materials.add(mat)
            node.material = mat
        }

        if (doc.materials.isEmpty()) doc.materials.add(Material("Material.1"))
        // Materials are shared: make sure every node points at one of the document materials.
        for (node in doc.allNodes()) {
            if (node.material == null && node.mesh != null) node.material = doc.materials.first()
        }
        return doc
    }

    private fun resolveIndex(raw: String?, size: Int): Int {
        if (raw.isNullOrEmpty()) return -1
        val idx = raw.toIntOrNull() ?: return -1
        return if (idx < 0) size + idx else idx - 1
    }

    private class SceneDocumentBuilder(val name: String) {
        var materialName: String = ""
        val triangles = ArrayList<IntArray>()
        val uvRefs = ArrayList<IntArray>()
        val normalRefs = ArrayList<IntArray>()
    }

    private fun f(v: Float): String = String.format(Locale.US, "%.6f", v)

    private fun sanitize(name: String): String =
        name.replace(Regex("[^A-Za-z0-9_.-]"), "_").ifEmpty { "objeto" }
}
