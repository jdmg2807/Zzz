package com.arena.modeler.io

import com.arena.modeler.scene.Material
import com.arena.modeler.scene.MeshData
import com.arena.modeler.scene.SceneDocument
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PLY (Stanford polygon file), ASCII and binary little endian.
 *
 * PLY is the format of the 3D-scanning world: it carries arbitrary per-vertex properties, which
 * makes it the natural container for the meshes an AI service returns. The reader understands the
 * usual `x y z [nx ny nz] [red green blue]` vertex block and polygon/vertex face lists.
 */
object Ply {

    fun write(doc: SceneDocument, ascii: Boolean = true): ByteArray {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val colors = ArrayList<Int>()
        val indices = ArrayList<Int>()
        for (node in doc.allNodes()) {
            val mesh = node.mesh ?: continue
            if (!node.visible) continue
            val w = node.worldMatrix()
            val base = positions.size / 3
            val mat = node.material
            val cr = ((mat?.baseColor?.get(0) ?: 0.8f) * 255f).toInt().coerceIn(0, 255)
            val cg = ((mat?.baseColor?.get(1) ?: 0.8f) * 255f).toInt().coerceIn(0, 255)
            val cb = ((mat?.baseColor?.get(2) ?: 0.85f) * 255f).toInt().coerceIn(0, 255)
            for (i in 0 until mesh.vertexCount) {
                val p = com.arena.modeler.core.M4.transformPoint(
                    w, mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2]
                )
                positions.add(p[0]); positions.add(p[1]); positions.add(p[2])
                val n = if (mesh.normals != null) {
                    com.arena.modeler.core.M4.transformDir(
                        w, mesh.normals!![i * 3], mesh.normals!![i * 3 + 1], mesh.normals!![i * 3 + 2]
                    )
                } else {
                    floatArrayOf(0f, 1f, 0f)
                }
                val len = kotlin.math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).coerceAtLeast(1e-6f)
                normals.add(n[0] / len); normals.add(n[1] / len); normals.add(n[2] / len)
                colors.add((cr shl 16) or (cg shl 8) or cb)
            }
            for (v in mesh.indices) indices.add(base + v)
        }
        val count = positions.size / 3
        val faces = indices.size / 3

        if (ascii) {
            val sb = StringBuilder(64 + count * 64)
            sb.append("ply\nformat ascii 1.0\ncomment VoxelForge 3D\nelement vertex ").append(count).append('\n')
            sb.append("property float x\nproperty float y\nproperty float z\n")
            sb.append("property float nx\nproperty float ny\nproperty float nz\n")
            sb.append("property uchar red\nproperty uchar green\nproperty uchar blue\n")
            sb.append("element face ").append(faces).append('\n')
            sb.append("property list uchar int vertex_indices\nend_header\n")
            for (i in 0 until count) {
                sb.append(positions[i * 3]).append(' ').append(positions[i * 3 + 1]).append(' ')
                    .append(positions[i * 3 + 2]).append(' ')
                sb.append(normals[i * 3]).append(' ').append(normals[i * 3 + 1]).append(' ')
                    .append(normals[i * 3 + 2]).append(' ')
                val c = colors[i]
                sb.append((c shr 16) and 0xFF).append(' ').append((c shr 8) and 0xFF).append(' ')
                    .append(c and 0xFF).append('\n')
            }
            for (i in 0 until faces) {
                sb.append("3 ").append(indices[i * 3]).append(' ').append(indices[i * 3 + 1]).append(' ')
                    .append(indices[i * 3 + 2]).append('\n')
            }
            return sb.toString().toByteArray(Charsets.UTF_8)
        }

        val header = buildString {
            append("ply\nformat binary_little_endian 1.0\ncomment VoxelForge 3D\n")
            append("element vertex ").append(count).append('\n')
            append("property float x\nproperty float y\nproperty float z\n")
            append("property float nx\nproperty float ny\nproperty float nz\n")
            append("property uchar red\nproperty uchar green\nproperty uchar blue\n")
            append("element face ").append(faces).append('\n')
            append("property list uchar int vertex_indices\nend_header\n")
        }.toByteArray(Charsets.US_ASCII)
        val body = ByteBuffer.allocate(count * 27 + faces * 13).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until count) {
            body.putFloat(positions[i * 3]); body.putFloat(positions[i * 3 + 1]); body.putFloat(positions[i * 3 + 2])
            body.putFloat(normals[i * 3]); body.putFloat(normals[i * 3 + 1]); body.putFloat(normals[i * 3 + 2])
            val c = colors[i]
            body.put(((c shr 16) and 0xFF).toByte())
            body.put(((c shr 8) and 0xFF).toByte())
            body.put((c and 0xFF).toByte())
        }
        for (i in 0 until faces) {
            body.put(3.toByte())
            body.putInt(indices[i * 3]); body.putInt(indices[i * 3 + 1]); body.putInt(indices[i * 3 + 2])
        }
        val out = ByteArray(header.size + body.position())
        System.arraycopy(header, 0, out, 0, header.size)
        System.arraycopy(body.array(), 0, out, header.size, body.position())
        return out
    }

    fun read(bytes: ByteArray, name: String): SceneDocument {
        val doc = SceneDocument(name.substringBeforeLast('.'))
        val headerEnd = indexOf(bytes, "end_header".toByteArray(Charsets.US_ASCII))
        if (headerEnd < 0) throw IllegalArgumentException("PLY sin cabecera")
        var bodyStart = headerEnd + 10
        while (bodyStart < bytes.size && (bytes[bodyStart] == '\n'.code.toByte() || bytes[bodyStart] == '\r'.code.toByte())) bodyStart++

        val header = String(bytes, 0, headerEnd, Charsets.US_ASCII)
        var vertexCount = 0
        var faceCount = 0
        var binary = false
        val props = ArrayList<String>()
        var inVertex = false
        for (line in header.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("format binary") -> binary = true
                t.startsWith("element vertex") -> {
                    vertexCount = t.split(Regex("\\s+")).getOrNull(2)?.toIntOrNull() ?: 0
                    inVertex = true
                }
                t.startsWith("element face") -> {
                    faceCount = t.split(Regex("\\s+")).getOrNull(2)?.toIntOrNull() ?: 0
                    inVertex = false
                }
                t.startsWith("property") && inVertex -> props.add(t)
            }
        }
        val positions = FloatArray(vertexCount * 3)
        val normals = FloatArray(vertexCount * 3)
        val colors = FloatArray(vertexCount * 3) { 0.8f }
        val indices = ArrayList<Int>(faceCount * 3)

        if (!binary) {
            val text = String(bytes, bodyStart, bytes.size - bodyStart, Charsets.UTF_8)
            var lineIdx = 0
            val lines = text.lineSequence().toList()
            for (v in 0 until vertexCount) {
                while (lineIdx < lines.size && lines[lineIdx].isBlank()) lineIdx++
                if (lineIdx >= lines.size) break
                val p = lines[lineIdx].trim().split(Regex("\\s+"))
                lineIdx++
                positions[v * 3] = p.getOrNull(0)?.toFloatOrNull() ?: 0f
                positions[v * 3 + 1] = p.getOrNull(1)?.toFloatOrNull() ?: 0f
                positions[v * 3 + 2] = p.getOrNull(2)?.toFloatOrNull() ?: 0f
                var o = 3
                for (prop in props.drop(3)) {
                    when {
                        prop.endsWith(" nx") -> normals[v * 3] = p.getOrNull(o)?.toFloatOrNull() ?: 0f
                        prop.endsWith(" ny") -> normals[v * 3 + 1] = p.getOrNull(o)?.toFloatOrNull() ?: 0f
                        prop.endsWith(" nz") -> normals[v * 3 + 2] = p.getOrNull(o)?.toFloatOrNull() ?: 0f
                        prop.contains(" red") -> colors[v * 3] = (p.getOrNull(o)?.toFloatOrNull() ?: 200f) / 255f
                        prop.contains(" green") -> colors[v * 3 + 1] = (p.getOrNull(o)?.toFloatOrNull() ?: 200f) / 255f
                        prop.contains(" blue") -> colors[v * 3 + 2] = (p.getOrNull(o)?.toFloatOrNull() ?: 210f) / 255f
                    }
                    o++
                }
            }
            for (f in 0 until faceCount) {
                while (lineIdx < lines.size && lines[lineIdx].isBlank()) lineIdx++
                if (lineIdx >= lines.size) break
                val p = lines[lineIdx].trim().split(Regex("\\s+"))
                lineIdx++
                val n = p.getOrNull(0)?.toIntOrNull() ?: 0
                if (n >= 3) {
                    for (k in 1 until n - 1) {
                        indices.add(p.getOrNull(1)?.toIntOrNull() ?: 0)
                        indices.add(p.getOrNull(k + 1)?.toIntOrNull() ?: 0)
                        indices.add(p.getOrNull(k + 2)?.toIntOrNull() ?: 0)
                    }
                }
            }
        } else {
            val bb = ByteBuffer.wrap(bytes, bodyStart, bytes.size - bodyStart).order(ByteOrder.LITTLE_ENDIAN)
            // Las propiedades declaradas en la cabecera deciden qué hay que consumir de cada
            // vértice; si no se respeta el orden, el bucle de caras lee basura (BufferUnderflow).
            val hasNormals = props.any { it.trim().endsWith(" nx") }
            val hasColors = props.any { it.trim().endsWith(" red") }
            val vertexStride = 12 + (if (hasNormals) 12 else 0) + (if (hasColors) 3 else 0)
            for (v in 0 until vertexCount) {
                if (bb.remaining() < vertexStride) break
                positions[v * 3] = bb.float
                positions[v * 3 + 1] = bb.float
                positions[v * 3 + 2] = bb.float
                if (hasNormals) {
                    normals[v * 3] = bb.float
                    normals[v * 3 + 1] = bb.float
                    normals[v * 3 + 2] = bb.float
                }
                if (hasColors) {
                    colors[v * 3] = (bb.get().toInt() and 0xFF) / 255f
                    colors[v * 3 + 1] = (bb.get().toInt() and 0xFF) / 255f
                    colors[v * 3 + 2] = (bb.get().toInt() and 0xFF) / 255f
                }
            }
            for (f in 0 until faceCount) {
                if (bb.remaining() < 1) break
                val n = bb.get().toInt() and 0xFF
                if (n < 3 || bb.remaining() < n * 4) break
                val v = IntArray(n) { bb.int }
                for (k in 1 until n - 1) {
                    indices.add(v[0]); indices.add(v[k]); indices.add(v[k + 1])
                }
            }
        }

        val node = doc.newNode("PLY")
        val mesh = MeshData(positions, indices.toIntArray(), normals, null)
        mesh.recomputeNormals()
        node.mesh = mesh
        val mat = Material("PLY", baseColor = floatArrayOf(colors[0], colors[1], colors[2], 1f))
        doc.materials.add(mat)
        node.material = mat
        return doc
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}
