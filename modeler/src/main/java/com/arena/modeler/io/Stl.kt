package com.arena.modeler.io

import com.arena.modeler.scene.Material
import com.arena.modeler.scene.MeshData
import com.arena.modeler.scene.SceneDocument
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * STL: the format every slicer and 3D printer reads. Binary by default (50 bytes per triangle,
 * no indices) and ASCII when the file starts with "solid" and contains "facet".
 *
 * STL stores no materials, no units and no animation, so the export flattens every visible node
 * into one triangle soup (in world space) and the import creates a single object.
 */
object Stl {

    fun writeBinary(doc: SceneDocument): ByteArray {
        val triangles = ArrayList<FloatArray>()   // 9 floats per triangle (3 points)
        val normals = ArrayList<FloatArray>()     // 3 floats per triangle
        for (node in doc.allNodes()) {
            val mesh = node.mesh ?: continue
            if (!node.visible) continue
            val w = node.worldMatrix()
            var i = 0
            while (i + 2 < mesh.indices.size) {
                val a = transform(w, mesh, mesh.indices[i])
                val b = transform(w, mesh, mesh.indices[i + 1])
                val c = transform(w, mesh, mesh.indices[i + 2])
                triangles.add(floatArrayOf(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2]))
                normals.add(faceNormal(a, b, c))
                i += 3
            }
        }
        val size = 84 + triangles.size * 50
        val bb = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        val header = "VoxelForge 3D STL export".toByteArray(Charsets.US_ASCII)
        bb.put(header)
        for (i in header.size until 80) bb.put(0)
        bb.putInt(triangles.size)
        for (t in triangles.indices) {
            val n = normals[t]
            bb.putFloat(n[0]); bb.putFloat(n[1]); bb.putFloat(n[2])
            val tri = triangles[t]
            for (v in 0 until 9) bb.putFloat(tri[v])
            bb.putShort(0)
        }
        return bb.array()
    }

    fun writeAscii(doc: SceneDocument): ByteArray {
        val sb = StringBuilder(1024 * 32)
        sb.append("solid voxelforge\n")
        for (node in doc.allNodes()) {
            val mesh = node.mesh ?: continue
            if (!node.visible) continue
            val w = node.worldMatrix()
            var i = 0
            while (i + 2 < mesh.indices.size) {
                val a = transform(w, mesh, mesh.indices[i])
                val b = transform(w, mesh, mesh.indices[i + 1])
                val c = transform(w, mesh, mesh.indices[i + 2])
                val n = faceNormal(a, b, c)
                sb.append("  facet normal ").append(f(n[0])).append(' ').append(f(n[1])).append(' ')
                    .append(f(n[2])).append('\n')
                sb.append("    outer loop\n")
                for (p in listOf(a, b, c)) {
                    sb.append("      vertex ").append(f(p[0])).append(' ').append(f(p[1]))
                        .append(' ').append(f(p[2])).append('\n')
                }
                sb.append("    endloop\n  endfacet\n")
                i += 3
            }
        }
        sb.append("endsolid voxelforge\n")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }

    fun read(bytes: ByteArray, name: String): SceneDocument {
        val doc = SceneDocument(name.substringBeforeLast('.'))
        val looksAscii = bytes.size > 5 &&
            String(bytes, 0, minOf(5, bytes.size), Charsets.US_ASCII).equals("solid", ignoreCase = true) &&
            String(bytes, Charsets.US_ASCII).contains("facet")
        val triangles = ArrayList<FloatArray>()
        if (looksAscii) {
            readAscii(String(bytes, Charsets.UTF_8), triangles)
        } else {
            readBinary(bytes, triangles)
        }
        val positions = FloatArray(triangles.size * 9)
        val normals = FloatArray(triangles.size * 9)
        val indices = IntArray(triangles.size * 3)
        for ((t, tri) in triangles.withIndex()) {
            val n = faceNormal(
                floatArrayOf(tri[0], tri[1], tri[2]),
                floatArrayOf(tri[3], tri[4], tri[5]),
                floatArrayOf(tri[6], tri[7], tri[8])
            )
            for (k in 0 until 3) {
                positions[(t * 3 + k) * 3] = tri[k * 3]
                positions[(t * 3 + k) * 3 + 1] = tri[k * 3 + 1]
                positions[(t * 3 + k) * 3 + 2] = tri[k * 3 + 2]
                normals[(t * 3 + k) * 3] = n[0]
                normals[(t * 3 + k) * 3 + 1] = n[1]
                normals[(t * 3 + k) * 3 + 2] = n[2]
                indices[t * 3 + k] = t * 3 + k
            }
        }
        val node = doc.newNode("STL")
        node.mesh = MeshData(positions, indices, normals, null)
        val mat = Material("STL")
        doc.materials.add(mat)
        node.material = mat
        return doc
    }

    private fun readBinary(bytes: ByteArray, out: ArrayList<FloatArray>) {
        if (bytes.size < 84) return
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        bb.position(80)
        val count = bb.int
        val max = minOf(count, (bytes.size - 84) / 50)
        for (t in 0 until max) {
            bb.position(84 + t * 50 + 12)   // skip the stored normal
            val v = FloatArray(9)
            for (i in 0 until 9) v[i] = bb.float
            out.add(v)
        }
    }

    private fun readAscii(text: String, out: ArrayList<FloatArray>) {
        var current = FloatArray(9)
        var count = 0
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (!t.startsWith("vertex ", ignoreCase = true)) continue
            val parts = t.split(Regex("\\s+"))
            if (parts.size < 4) continue
            val x = parts[1].toFloatOrNull() ?: continue
            val y = parts[2].toFloatOrNull() ?: continue
            val z = parts[3].toFloatOrNull() ?: continue
            current[count * 3] = x
            current[count * 3 + 1] = y
            current[count * 3 + 2] = z
            count++
            if (count == 3) {
                out.add(current)
                current = FloatArray(9)
                count = 0
            }
        }
    }

    private fun transform(w: FloatArray, mesh: MeshData, index: Int): FloatArray =
        com.arena.modeler.core.M4.transformPoint(
            w, mesh.positions[index * 3], mesh.positions[index * 3 + 1], mesh.positions[index * 3 + 2]
        )

    private fun faceNormal(a: FloatArray, b: FloatArray, c: FloatArray): FloatArray {
        val e1 = floatArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2])
        val e2 = floatArrayOf(c[0] - a[0], c[1] - a[1], c[2] - a[2])
        val n = floatArrayOf(
            e1[1] * e2[2] - e1[2] * e2[1],
            e1[2] * e2[0] - e1[0] * e2[2],
            e1[0] * e2[1] - e1[1] * e2[0]
        )
        val len = kotlin.math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).coerceAtLeast(1e-9f)
        return floatArrayOf(n[0] / len, n[1] / len, n[2] / len)
    }

    private fun f(v: Float): String = String.format(Locale.US, "%.6f", v)
}
