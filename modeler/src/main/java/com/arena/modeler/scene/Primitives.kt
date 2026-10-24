package com.arena.modeler.scene

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Mesh generators for the objects the app can create without any external asset.
 *
 * All of them produce unit-ish shapes (1 m wide, centred on the origin, base at y = 0 for the
 * "standing" primitives) with smooth normals and UVs, so they export cleanly to every format.
 */
object Primitives {

    enum class Kind(val label: String) {
        CUBE("Cubo"),
        SPHERE("Esfera"),
        CYLINDER("Cilindro"),
        CONE("Cono"),
        TORUS("Toro"),
        PLANE("Plano"),
        ICOSPHERE("Icósfera")
    }

    fun build(kind: Kind): MeshData = when (kind) {
        Kind.CUBE -> box(1f, 1f, 1f)
        Kind.SPHERE -> sphere(1f, 24, 16)
        Kind.CYLINDER -> cylinder(0.5f, 1f, 24)
        Kind.CONE -> cone(0.5f, 1f, 24)
        Kind.TORUS -> torus(0.5f, 0.18f, 24, 12)
        Kind.PLANE -> plane(1f, 1f, 1)
        Kind.ICOSPHERE -> icosphere(1f, 2)
    }

    /** Axis-aligned box of the given size, centred horizontally with its base on y = 0. */
    fun box(width: Float, height: Float, depth: Float): MeshData {
        val hw = width * 0.5f
        val hd = depth * 0.5f
        val hh = height * 0.5f
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val indices = ArrayList<Int>()

        fun face(
            nx: Float, ny: Float, nz: Float,
            corners: Array<FloatArray>
        ) {
            val base = positions.size / 3
            for (c in corners) {
                positions.add(c[0]); positions.add(c[1]); positions.add(c[2])
                normals.add(nx); normals.add(ny); normals.add(nz)
            }
            uvs.add(0f); uvs.add(1f)
            uvs.add(1f); uvs.add(1f)
            uvs.add(1f); uvs.add(0f)
            uvs.add(0f); uvs.add(0f)
            indices.add(base); indices.add(base + 1); indices.add(base + 2)
            indices.add(base); indices.add(base + 2); indices.add(base + 3)
        }

        // +X, -X, +Z, -Z, +Y, -Y
        face(1f, 0f, 0f, arrayOf(
            floatArrayOf(hw, -hh, hd), floatArrayOf(hw, -hh, -hd),
            floatArrayOf(hw, hh, -hd), floatArrayOf(hw, hh, hd)
        ))
        face(-1f, 0f, 0f, arrayOf(
            floatArrayOf(-hw, -hh, -hd), floatArrayOf(-hw, -hh, hd),
            floatArrayOf(-hw, hh, hd), floatArrayOf(-hw, hh, -hd)
        ))
        face(0f, 0f, 1f, arrayOf(
            floatArrayOf(-hw, -hh, hd), floatArrayOf(hw, -hh, hd),
            floatArrayOf(hw, hh, hd), floatArrayOf(-hw, hh, hd)
        ))
        face(0f, 0f, -1f, arrayOf(
            floatArrayOf(hw, -hh, -hd), floatArrayOf(-hw, -hh, -hd),
            floatArrayOf(-hw, hh, -hd), floatArrayOf(hw, hh, -hd)
        ))
        face(0f, 1f, 0f, arrayOf(
            floatArrayOf(-hw, hh, hd), floatArrayOf(hw, hh, hd),
            floatArrayOf(hw, hh, -hd), floatArrayOf(-hw, hh, -hd)
        ))
        face(0f, -1f, 0f, arrayOf(
            floatArrayOf(-hw, -hh, -hd), floatArrayOf(hw, -hh, -hd),
            floatArrayOf(hw, -hh, hd), floatArrayOf(-hw, -hh, hd)
        ))

        return MeshData(positions.toFloatArray(), indices.toIntArray(), normals.toFloatArray(), uvs.toFloatArray())
    }

    /** UV sphere with smooth normals; radius 0.5 unless told otherwise. */
    fun sphere(radius: Float = 0.5f, segments: Int = 24, rings: Int = 16): MeshData {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val indices = ArrayList<Int>()
        val r = radius
        for (y in 0..rings) {
            val v = y.toFloat() / rings
            val phi = v * Math.PI.toFloat()
            for (x in 0..segments) {
                val u = x.toFloat() / segments
                val theta = u * Math.PI.toFloat() * 2f
                val nx = sin(phi) * cos(theta)
                val ny = cos(phi)
                val nz = sin(phi) * sin(theta)
                positions.add(nx * r); positions.add(ny * r + r); positions.add(nz * r)
                normals.add(nx); normals.add(ny); normals.add(nz)
                uvs.add(u); uvs.add(1f - v)
            }
        }
        val rowLen = segments + 1
        for (y in 0 until rings) {
            for (x in 0 until segments) {
                val a = y * rowLen + x
                val b = a + rowLen
                indices.add(a); indices.add(b); indices.add(a + 1)
                indices.add(a + 1); indices.add(b); indices.add(b + 1)
            }
        }
        return MeshData(positions.toFloatArray(), indices.toIntArray(), normals.toFloatArray(), uvs.toFloatArray())
    }

    /** Straight cylinder with base on y = 0. */
    fun cylinder(radius: Float = 0.5f, height: Float = 1f, segments: Int = 24, capped: Boolean = true): MeshData {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val indices = ArrayList<Int>()
        for (y in 0..1) {
            for (i in 0..segments) {
                val u = i.toFloat() / segments
                val a = u * Math.PI.toFloat() * 2f
                val nx = cos(a)
                val nz = sin(a)
                positions.add(nx * radius); positions.add(if (y == 0) 0f else height); positions.add(nz * radius)
                normals.add(nx); normals.add(0f); normals.add(nz)
                uvs.add(u); uvs.add(1f - y.toFloat())
            }
        }
        val row = segments + 1
        for (i in 0 until segments) {
            indices.add(i); indices.add(i + row); indices.add(i + 1)
            indices.add(i + 1); indices.add(i + row); indices.add(i + row + 1)
        }
        if (capped) {
            addDisc(positions, normals, uvs, indices, radius, height, segments, true)
            addDisc(positions, normals, uvs, indices, radius, 0f, segments, false)
        }
        return MeshData(positions.toFloatArray(), indices.toIntArray(), normals.toFloatArray(), uvs.toFloatArray())
    }

    private fun addDisc(
        positions: ArrayList<Float>, normals: ArrayList<Float>, uvs: ArrayList<Float>,
        indices: ArrayList<Int>, radius: Float, y: Float, segments: Int, up: Boolean
    ) {
        val base = positions.size / 3
        positions.add(0f); positions.add(y); positions.add(0f)
        normals.add(0f); normals.add(if (up) 1f else -1f); normals.add(0f)
        uvs.add(0.5f); uvs.add(0.5f)
        for (i in 0..segments) {
            val a = i.toFloat() / segments * Math.PI.toFloat() * 2f
            positions.add(cos(a) * radius); positions.add(y); positions.add(sin(a) * radius)
            normals.add(0f); normals.add(if (up) 1f else -1f); normals.add(0f)
            uvs.add(0.5f + cos(a) * 0.5f); uvs.add(0.5f + sin(a) * 0.5f)
        }
        for (i in 0 until segments) {
            if (up) {
                indices.add(base); indices.add(base + 1 + i); indices.add(base + 2 + i)
            } else {
                indices.add(base); indices.add(base + 2 + i); indices.add(base + 1 + i)
            }
        }
    }

    /** Cone with base on y = 0 and the tip on y = height. */
    fun cone(radius: Float = 0.5f, height: Float = 1f, segments: Int = 24): MeshData {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val indices = ArrayList<Int>()
        // Lateral surface: apex is duplicated per segment so normals stay smooth per face.
        val slope = radius / height
        for (i in 0..segments) {
            val u = i.toFloat() / segments
            val a = u * Math.PI.toFloat() * 2f
            val nx = cos(a)
            val nz = sin(a)
            val nLen = sqrt(1f + slope * slope)
            val base = positions.size / 3
            positions.add(nx * radius); positions.add(0f); positions.add(nz * radius)
            normals.add(nx / nLen); normals.add(slope / nLen); normals.add(nz / nLen)
            uvs.add(u); uvs.add(1f)
            positions.add(0f); positions.add(height); positions.add(0f)
            normals.add(nx / nLen); normals.add(slope / nLen); normals.add(nz / nLen)
            uvs.add(u); uvs.add(0f)
            if (i < segments) {
                indices.add(base); indices.add(base + 2); indices.add(base + 1)
            }
        }
        addDisc(positions, normals, uvs, indices, radius, 0f, segments, false)
        return MeshData(positions.toFloatArray(), indices.toIntArray(), normals.toFloatArray(), uvs.toFloatArray())
    }

    /** Torus lying in the XZ plane, centred on y = radius of the tube. */
    fun torus(majorRadius: Float = 0.5f, tubeRadius: Float = 0.18f, majorSegments: Int = 24, minorSegments: Int = 12): MeshData {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val indices = ArrayList<Int>()
        for (i in 0..majorSegments) {
            val u = i.toFloat() / majorSegments
            val a = u * Math.PI.toFloat() * 2f
            for (j in 0..minorSegments) {
                val v = j.toFloat() / minorSegments
                val b = v * Math.PI.toFloat() * 2f
                val cx = cos(a) * majorRadius
                val cz = sin(a) * majorRadius
                val nx = cos(a) * cos(b)
                val ny = sin(b)
                val nz = sin(a) * cos(b)
                positions.add(cx + nx * tubeRadius)
                positions.add(ny * tubeRadius + tubeRadius)
                positions.add(cz + nz * tubeRadius)
                normals.add(nx); normals.add(ny); normals.add(nz)
                uvs.add(u); uvs.add(v)
            }
        }
        val row = minorSegments + 1
        for (i in 0 until majorSegments) {
            for (j in 0 until minorSegments) {
                val a = i * row + j
                val b = a + row
                indices.add(a); indices.add(b); indices.add(a + 1)
                indices.add(a + 1); indices.add(b); indices.add(b + 1)
            }
        }
        return MeshData(positions.toFloatArray(), indices.toIntArray(), normals.toFloatArray(), uvs.toFloatArray())
    }

    /** Horizontal plane of the given size with [divisions] cells per side. */
    fun plane(width: Float = 1f, depth: Float = 1f, divisions: Int = 1): MeshData {
        val positions = ArrayList<Float>()
        val normals = ArrayList<Float>()
        val uvs = ArrayList<Float>()
        val indices = ArrayList<Int>()
        val n = divisions.coerceAtLeast(1)
        for (z in 0..n) {
            for (x in 0..n) {
                val u = x.toFloat() / n
                val v = z.toFloat() / n
                positions.add((u - 0.5f) * width)
                positions.add(0f)
                positions.add((v - 0.5f) * depth)
                normals.add(0f); normals.add(1f); normals.add(0f)
                uvs.add(u); uvs.add(v)
            }
        }
        val row = n + 1
        for (z in 0 until n) {
            for (x in 0 until n) {
                val a = z * row + x
                val b = a + row
                indices.add(a); indices.add(b); indices.add(a + 1)
                indices.add(a + 1); indices.add(b); indices.add(b + 1)
            }
        }
        return MeshData(positions.toFloatArray(), indices.toIntArray(), normals.toFloatArray(), uvs.toFloatArray())
    }

    /** Geodesic sphere: triangles are uniform, which looks better than a UV sphere when low-poly. */
    fun icosphere(radius: Float = 0.5f, subdivisions: Int = 2): MeshData {
        val t = (1f + sqrt(5f)) / 2f
        var verts = ArrayList<FloatArray>()
        listOf(
            floatArrayOf(-1f, t, 0f), floatArrayOf(1f, t, 0f), floatArrayOf(-1f, -t, 0f), floatArrayOf(1f, -t, 0f),
            floatArrayOf(0f, -1f, t), floatArrayOf(0f, 1f, t), floatArrayOf(0f, -1f, -t), floatArrayOf(0f, 1f, -t),
            floatArrayOf(t, 0f, -1f), floatArrayOf(t, 0f, 1f), floatArrayOf(-t, 0f, -1f), floatArrayOf(-t, 0f, 1f)
        ).forEach { verts.add(it) }
        var faces = ArrayList<IntArray>()
        listOf(
            intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
            intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
            intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
            intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1)
        ).forEach { faces.add(it) }

        repeat(subdivisions.coerceIn(0, 4)) {
            val cache = HashMap<Long, Int>()
            val newFaces = ArrayList<IntArray>(faces.size * 4)
            fun midpoint(a: Int, b: Int): Int {
                val key = (minOf(a, b).toLong() shl 32) or maxOf(a, b).toLong()
                cache[key]?.let { return it }
                val va = verts[a]
                val vb = verts[b]
                val m = floatArrayOf((va[0] + vb[0]) / 2f, (va[1] + vb[1]) / 2f, (va[2] + vb[2]) / 2f)
                val len = sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
                m[0] /= len; m[1] /= len; m[2] /= len
                verts.add(m)
                cache[key] = verts.size - 1
                return verts.size - 1
            }
            for (f in faces) {
                val a = midpoint(f[0], f[1])
                val b = midpoint(f[1], f[2])
                val c = midpoint(f[2], f[0])
                newFaces.add(intArrayOf(f[0], a, c))
                newFaces.add(intArrayOf(f[1], b, a))
                newFaces.add(intArrayOf(f[2], c, b))
                newFaces.add(intArrayOf(a, b, c))
            }
            faces = newFaces
        }

        val positions = FloatArray(verts.size * 3)
        val normals = FloatArray(verts.size * 3)
        for (i in verts.indices) {
            val v = verts[i]
            val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
            val nx = v[0] / len
            val ny = v[1] / len
            val nz = v[2] / len
            positions[i * 3] = nx * radius
            positions[i * 3 + 1] = ny * radius + radius
            positions[i * 3 + 2] = nz * radius
            normals[i * 3] = nx
            normals[i * 3 + 1] = ny
            normals[i * 3 + 2] = nz
        }
        val indices = IntArray(faces.size * 3)
        for (i in faces.indices) {
            indices[i * 3] = faces[i][0]
            indices[i * 3 + 1] = faces[i][1]
            indices[i * 3 + 2] = faces[i][2]
        }
        return MeshData(positions, indices, normals, null)
    }
}
