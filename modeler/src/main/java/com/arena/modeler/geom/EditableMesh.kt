package com.arena.modeler.geom

import com.arena.modeler.scene.MeshData
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Malla editable: vértices sueltos y **caras de cualquier número de lados** (no solo triángulos).
 *
 * Es el formato con el que trabaja el motor de geometría (extrusión, bisel, Catmull-Clark…), porque
 * esas operaciones necesitan saber qué es un cuadrilátero o un n-gono; al final se triangula para
 * el visor y para exportar. No depende de Android: se prueba en la JVM.
 */
class EditableMesh(
    val positions: MutableList<Float> = ArrayList(),
    val faces: MutableList<IntArray> = ArrayList(),
    /** Índice de material por cara (0 si solo hay uno). */
    val faceMaterial: MutableList<Int> = ArrayList()
) {

    val vertexCount: Int get() = positions.size / 3
    val faceCount: Int get() = faces.size

    var name: String = "Malla"

    fun x(i: Int): Float = positions[i * 3]
    fun y(i: Int): Float = positions[i * 3 + 1]
    fun z(i: Int): Float = positions[i * 3 + 2]

    fun setVertex(i: Int, x: Float, y: Float, z: Float) {
        positions[i * 3] = x
        positions[i * 3 + 1] = y
        positions[i * 3 + 2] = z
    }

    fun vertex(i: Int): FloatArray = floatArrayOf(x(i), y(i), z(i))

    fun addVertex(x: Float, y: Float, z: Float): Int {
        positions.add(x)
        positions.add(y)
        positions.add(z)
        return vertexCount - 1
    }

    fun addFace(indices: IntArray, material: Int = 0): Int {
        faces.add(indices)
        faceMaterial.add(material)
        return faces.size - 1
    }

    fun addFace(vararg indices: Int): Int = addFace(indices.toList().toIntArray(), 0)

    fun copy(): EditableMesh {
        val m = EditableMesh()
        m.positions.addAll(positions)
        for (f in faces) m.faces.add(f.copyOf())
        m.faceMaterial.addAll(faceMaterial)
        m.name = name
        return m
    }

    fun isEmpty(): Boolean = faces.isEmpty() || positions.isEmpty()

    /** Número de triángulos que producirá la triangulación. */
    fun triangleCount(): Int {
        var t = 0
        for (f in faces) if (f.size >= 3) t += f.size - 2
        return t
    }

    fun bounds(): FloatArray {
        if (positions.isEmpty()) return floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i + 2 < positions.size) {
            val px = positions[i]
            val py = positions[i + 1]
            val pz = positions[i + 2]
            if (px < minX) minX = px
            if (py < minY) minY = py
            if (pz < minZ) minZ = pz
            if (px > maxX) maxX = px
            if (py > maxY) maxY = py
            if (pz > maxZ) maxZ = pz
            i += 3
        }
        return floatArrayOf(minX, minY, minZ, maxX, maxY, maxZ)
    }

    fun center(): FloatArray {
        val b = bounds()
        return floatArrayOf((b[0] + b[3]) * 0.5f, (b[1] + b[4]) * 0.5f, (b[2] + b[5]) * 0.5f)
    }

    /** Normal (sin normalizar) del polígono, por suma de Newell. */
    fun faceNormal(face: Int, out: FloatArray = FloatArray(3)): FloatArray {
        val f = faces[face]
        var nx = 0f
        var ny = 0f
        var nz = 0f
        for (i in f.indices) {
            val a = f[i]
            val b = f[(i + 1) % f.size]
            val ax = x(a)
            val ay = y(a)
            val az = z(a)
            val bx = x(b)
            val by = y(b)
            val bz = z(b)
            nx += (ay - by) * (az + bz)
            ny += (az - bz) * (ax + bx)
            nz += (ax - bx) * (ay + by)
        }
        out[0] = nx
        out[1] = ny
        out[2] = nz
        return out
    }

    fun faceCenter(face: Int, out: FloatArray = FloatArray(3)): FloatArray {
        val f = faces[face]
        var cx = 0f
        var cy = 0f
        var cz = 0f
        for (v in f) {
            cx += x(v)
            cy += y(v)
            cz += z(v)
        }
        val n = f.size.coerceAtLeast(1)
        out[0] = cx / n
        out[1] = cy / n
        out[2] = cz / n
        return out
    }

    fun faceArea(face: Int): Float {
        val n = faceNormal(face)
        return 0.5f * sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
    }

    /** Volumen con signo (teorema de la divergencia). Positivo = caras hacia fuera. */
    fun volume(): Float {
        var sum = 0f
        for (fi in faces.indices) {
            val f = faces[fi]
            if (f.size < 3) continue
            val a = f[0]
            for (k in 1 until f.size - 1) {
                val b = f[k]
                val c = f[k + 1]
                val ax = x(a)
                val ay = y(a)
                val az = z(a)
                val bx = x(b)
                val by = y(b)
                val bz = z(b)
                val cx = x(c)
                val cy = y(c)
                val cz = z(c)
                sum += (ax * (by * cz - bz * cy) - ay * (bx * cz - bz * cx) + az * (bx * cy - by * cx)) / 6f
            }
        }
        return sum
    }

    /** Gira todas las caras si el volumen salió negativo (malla "del revés"). */
    fun ensureOutward(): EditableMesh {
        if (volume() < 0f) {
            for (f in faces) f.reverse()
        }
        return this
    }

    fun transform(m: FloatArray): EditableMesh {
        var i = 0
        while (i + 2 < positions.size) {
            val px = positions[i]
            val py = positions[i + 1]
            val pz = positions[i + 2]
            positions[i] = m[0] * px + m[4] * py + m[8] * pz + m[12]
            positions[i + 1] = m[1] * px + m[5] * py + m[9] * pz + m[13]
            positions[i + 2] = m[2] * px + m[6] * py + m[10] * pz + m[14]
            i += 3
        }
        return this
    }

    fun translate(dx: Float, dy: Float, dz: Float): EditableMesh {
        var i = 0
        while (i + 2 < positions.size) {
            positions[i] += dx
            positions[i + 1] += dy
            positions[i + 2] += dz
            i += 3
        }
        return this
    }

    fun scaleMesh(sx: Float, sy: Float, sz: Float): EditableMesh {
        var i = 0
        while (i + 2 < positions.size) {
            positions[i] *= sx
            positions[i + 1] *= sy
            positions[i + 2] *= sz
            i += 3
        }
        return this
    }

    fun append(other: EditableMesh, materialOffset: Int = 0): EditableMesh {
        val base = vertexCount
        positions.addAll(other.positions)
        for (i in other.faces.indices) {
            val f = other.faces[i].copyOf()
            for (k in f.indices) f[k] += base
            faces.add(f)
            faceMaterial.add(other.faceMaterial.getOrElse(i) { 0 } + materialOffset)
        }
        return this
    }

    /** Copia con solo las caras indicadas (los vértices usados se conservan). */
    fun subset(faceIndices: Collection<Int>): EditableMesh {
        val out = EditableMesh()
        val remap = HashMap<Int, Int>()
        for (fi in faceIndices) {
            if (fi < 0 || fi >= faces.size) continue
            val f = faces[fi]
            val nf = IntArray(f.size)
            for (k in f.indices) {
                val v = f[k]
                val nv = remap.getOrPut(v) {
                    out.addVertex(x(v), y(v), z(v))
                }
                nf[k] = nv
            }
            out.addFace(nf, faceMaterial.getOrElse(fi) { 0 })
        }
        out.name = name
        return out
    }

    /** Elimina vértices que ninguna cara usa. */
    fun compact(): EditableMesh {
        val used = BooleanArray(vertexCount)
        for (f in faces) for (v in f) if (v in 0 until vertexCount) used[v] = true
        val remap = IntArray(vertexCount) { -1 }
        val out = EditableMesh()
        for (i in 0 until vertexCount) {
            if (!used[i]) continue
            remap[i] = out.addVertex(x(i), y(i), z(i))
        }
        for (fi in faces.indices) {
            val f = faces[fi]
            val nf = IntArray(f.size)
            var ok = true
            for (k in f.indices) {
                val r = if (f[k] in 0 until vertexCount) remap[f[k]] else -1
                if (r < 0) {
                    ok = false
                    break
                }
                nf[k] = r
            }
            if (ok) out.addFace(nf, faceMaterial.getOrElse(fi) { 0 })
        }
        out.name = name
        return out
    }

    /** Une vértices a menos de [epsilon] (y descarta las caras que quedan degeneradas). */
    fun weld(epsilon: Float = 1e-5f): EditableMesh {
        if (positions.isEmpty()) return this
        val cell = epsilon.coerceAtLeast(1e-6f) * 2f
        val grids = HashMap<Long, MutableList<Int>>()
        val remap = IntArray(vertexCount)
        val out = EditableMesh()
        for (i in 0 until vertexCount) {
            val px = x(i)
            val py = y(i)
            val pz = z(i)
            val gx = Math.floor((px / cell).toDouble()).toLong()
            val gy = Math.floor((py / cell).toDouble()).toLong()
            val gz = Math.floor((pz / cell).toDouble()).toLong()
            var found = -1
            for (dx in -1..1) for (dy in -1..1) for (dz in -1..1) {
                if (found >= 0) continue
                val key = packKey(gx + dx, gy + dy, gz + dz)
                val bucket = grids[key] ?: continue
                for (candidate in bucket) {
                    val dxp = out.x(candidate) - px
                    val dyp = out.y(candidate) - py
                    val dzp = out.z(candidate) - pz
                    if (dxp * dxp + dyp * dyp + dzp * dzp <= epsilon * epsilon) {
                        found = candidate
                        break
                    }
                }
            }
            if (found < 0) {
                found = out.addVertex(px, py, pz)
                grids.getOrPut(packKey(gx, gy, gz)) { ArrayList() }.add(found)
            }
            remap[i] = found
        }
        for (fi in faces.indices) {
            val f = faces[fi]
            val nf = ArrayList<Int>(f.size)
            for (v in f) {
                if (v !in 0 until vertexCount) continue
                val r = remap[v]
                if (nf.isEmpty() || nf[nf.size - 1] != r) nf.add(r)
            }
            if (nf.size > 2 && nf[0] == nf[nf.size - 1]) nf.removeAt(nf.size - 1)
            if (nf.size >= 3) out.addFace(nf.toIntArray(), faceMaterial.getOrElse(fi) { 0 })
        }
        out.name = name
        return out
    }

    private fun packKey(x: Long, y: Long, z: Long): Long =
        (x * 73856093L) xor (y * 19349663L) xor (z * 83492791L)

    // ------------------------------------------------------------------ topología

    class Edge(val a: Int, val b: Int) {
        fun key(): Long {
            val lo = min(a, b).toLong()
            val hi = max(a, b).toLong()
            return (lo shl 32) or hi
        }

        override fun equals(other: Any?): Boolean = other is Edge && key() == other.key()
        override fun hashCode(): Int = key().hashCode()
    }

    /** Aristas únicas (sin duplicar a→b y b→a). */
    fun edges(): List<Edge> {
        val seen = HashSet<Long>()
        val out = ArrayList<Edge>()
        for (f in faces) {
            for (i in f.indices) {
                val a = f[i]
                val b = f[(i + 1) % f.size]
                if (a == b) continue
                val e = Edge(a, b)
                if (seen.add(e.key())) out.add(e)
            }
        }
        return out
    }

    /** Caras que comparten vértice con cada vértice. */
    fun vertexFaces(): Array<MutableList<Int>> {
        val out = Array(vertexCount) { mutableListOf<Int>() }
        for (fi in faces.indices) {
            for (v in faces[fi]) if (v in 0 until vertexCount) out[v].add(fi)
        }
        return out
    }

    fun edgeFaces(): Map<Long, MutableList<Int>> {
        val out = HashMap<Long, MutableList<Int>>()
        for (fi in faces.indices) {
            val f = faces[fi]
            for (i in f.indices) {
                val a = f[i]
                val b = f[(i + 1) % f.size]
                if (a == b) continue
                out.getOrPut(Edge(a, b).key()) { ArrayList() }.add(fi)
            }
        }
        return out
    }

    /** Vecinos de cada vértice por arista. */
    fun vertexNeighbors(): Array<MutableList<Int>> {
        val out = Array(vertexCount) { mutableListOf<Int>() }
        val seen = HashSet<Long>()
        for (f in faces) {
            for (i in f.indices) {
                val a = f[i]
                val b = f[(i + 1) % f.size]
                if (a == b) continue
                val key = Edge(a, b).key()
                if (!seen.add(key)) continue
                out[a].add(b)
                out[b].add(a)
            }
        }
        return out
    }

    fun isManifoldClosed(): Boolean {
        val counts = edgeFaces()
        return counts.isNotEmpty() && counts.values.all { it.size == 2 }
    }

    // ------------------------------------------------------------------ conversión

    /**
     * Triangula con abanico desde el primer vértice de cada cara. Las caras deben ser convexas y
     * planas, que es lo que garantizan las operaciones de este paquete.
     */
    fun triangulate(): TriangulatedMesh {
        val idx = ArrayList<Int>(triangleCount() * 3)
        val mats = ArrayList<Int>(triangleCount())
        for (fi in faces.indices) {
            val f = faces[fi]
            if (f.size < 3) continue
            for (k in 1 until f.size - 1) {
                idx.add(f[0])
                idx.add(f[k])
                idx.add(f[k + 1])
                mats.add(faceMaterial.getOrElse(fi) { 0 })
            }
        }
        return TriangulatedMesh(idx.toIntArray(), mats.toIntArray())
    }

    class TriangulatedMesh(val indices: IntArray, val triangleMaterial: IntArray)

    /** Genera la malla del visor: posiciones propias, normales suaves o por cara y UV planas. */
    fun toMeshData(smooth: Boolean = true, withUv: Boolean = true): MeshData {
        val tri = triangulate()
        if (tri.indices.isEmpty()) return MeshData(FloatArray(0), IntArray(0))
        val b = bounds()
        val sizeX = (b[3] - b[0]).coerceAtLeast(1e-5f)
        val sizeY = (b[4] - b[1]).coerceAtLeast(1e-5f)
        val sizeZ = (b[5] - b[2]).coerceAtLeast(1e-5f)

        if (smooth) {
            val positions = FloatArray(vertexCount * 3)
            for (i in positions.indices) positions[i] = this.positions[i]
            val normals = FloatArray(vertexCount * 3)
            var t = 0
            while (t + 2 < tri.indices.size) {
                val a = tri.indices[t]
                val bb = tri.indices[t + 1]
                val c = tri.indices[t + 2]
                val ux = x(bb) - x(a)
                val uy = y(bb) - y(a)
                val uz = z(bb) - z(a)
                val vx = x(c) - x(a)
                val vy = y(c) - y(a)
                val vz = z(c) - z(a)
                val nx = uy * vz - uz * vy
                val ny = uz * vx - ux * vz
                val nz = ux * vy - uy * vx
                for (v in intArrayOf(a, bb, c)) {
                    normals[v * 3] += nx
                    normals[v * 3 + 1] += ny
                    normals[v * 3 + 2] += nz
                }
                t += 3
            }
            for (i in 0 until vertexCount) {
                val nx = normals[i * 3]
                val ny = normals[i * 3 + 1]
                val nz = normals[i * 3 + 2]
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 1e-9f) {
                    normals[i * 3] = nx / len
                    normals[i * 3 + 1] = ny / len
                    normals[i * 3 + 2] = nz / len
                } else {
                    normals[i * 3 + 1] = 1f
                }
            }
            val uvs = if (withUv) FloatArray(vertexCount * 2) else null
            if (uvs != null) {
                for (i in 0 until vertexCount) {
                    uvs[i * 2] = (x(i) - b[0]) / sizeX
                    uvs[i * 2 + 1] = (z(i) - b[2]) / sizeZ
                }
            }
            return MeshData(positions, tri.indices.copyOf(), normals, uvs)
        }

        // Caras planas: cada triángulo con sus propios vértices y su normal.
        val triCount = tri.indices.size / 3
        val positions = FloatArray(triCount * 9)
        val normals = FloatArray(triCount * 9)
        val uvs = if (withUv) FloatArray(triCount * 6) else null
        val indices = IntArray(triCount * 3)
        for (t in 0 until triCount) {
            val a = tri.indices[t * 3]
            val bIdx = tri.indices[t * 3 + 1]
            val c = tri.indices[t * 3 + 2]
            val ux = x(bIdx) - x(a)
            val uy = y(bIdx) - y(a)
            val uz = z(bIdx) - z(a)
            val vx = x(c) - x(a)
            val vy = y(c) - y(a)
            val vz = z(c) - z(a)
            var nx = uy * vz - uz * vy
            var ny = uz * vx - ux * vz
            var nz = ux * vy - uy * vx
            val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f)
            nx /= len
            ny /= len
            nz /= len
            val verts = intArrayOf(a, bIdx, c)
            for (k in 0 until 3) {
                val v = verts[k]
                val o = (t * 3 + k) * 3
                positions[o] = x(v)
                positions[o + 1] = y(v)
                positions[o + 2] = z(v)
                normals[o] = nx
                normals[o + 1] = ny
                normals[o + 2] = nz
                if (uvs != null) {
                    uvs[(t * 3 + k) * 2] = (x(v) - b[0]) / sizeX
                    uvs[(t * 3 + k) * 2 + 1] = (z(v) - b[2]) / sizeZ
                }
                indices[t * 3 + k] = t * 3 + k
            }
        }
        return MeshData(positions, indices, normals, uvs)
    }

    /** Separa la malla en una malla por material (el visor y glTF usan un material por nodo). */
    fun splitByMaterial(): List<EditableMesh> {
        val groups = LinkedHashMap<Int, ArrayList<Int>>()
        for (i in faces.indices) groups.getOrPut(faceMaterial.getOrElse(i) { 0 }) { ArrayList() }.add(i)
        if (groups.size <= 1) return listOf(this)
        return groups.map { (mat, faceList) -> subset(faceList).also { it.name = "$name#$mat" } }
    }

    // ------------------------------------------------------------------ constructores

    companion object {

        /**
         * Convierte una [MeshData] en malla editable: suelda vértices repetidos (los exportadores
         * duplican por normales/UV) y, opcionalmente, une triángulos coplanares en cuadriláteros
         * para que extruir y biselar se comporten como en un modelador de verdad.
         */
        fun fromMeshData(mesh: MeshData, weld: Boolean = true, mergeCoplanar: Boolean = true): EditableMesh {
            val m = EditableMesh()
            for (i in 0 until mesh.vertexCount) {
                m.addVertex(mesh.positions[i * 3], mesh.positions[i * 3 + 1], mesh.positions[i * 3 + 2])
            }
            var i = 0
            while (i + 2 < mesh.indices.size) {
                val a = mesh.indices[i]
                val b = mesh.indices[i + 1]
                val c = mesh.indices[i + 2]
                if (a != b && b != c && a != c) m.addFace(intArrayOf(a, b, c), 0)
                i += 3
            }
            var out = if (weld) m.weld(1e-5f) else m
            if (mergeCoplanar) out = mergeCoplanarFaces(out)
            return out
        }

        /**
         * Agrupa triángulos que están en el mismo plano y reconstruye el contorno de cada grupo
         * como un polígono. Es lo que convierte el cubo (12 triángulos) en 6 cuadriláteros.
         */
        fun mergeCoplanarFaces(mesh: EditableMesh): EditableMesh {
            if (mesh.faces.isEmpty()) return mesh
            val groups = LinkedHashMap<Long, MutableList<Int>>()
            val keyOf = HashMap<Int, Long>()
            val normal = FloatArray(3)
            for (fi in mesh.faces.indices) {
                if (mesh.faces[fi].size != 3) {
                    // Las caras que ya son polígonos no se fusionan entre sí.
                    keyOf[fi] = Long.MIN_VALUE + fi
                    groups.getOrPut(keyOf[fi]!!) { ArrayList() }.add(fi)
                    continue
                }
                mesh.faceNormal(fi, normal)
                val len = sqrt(normal[0] * normal[0] + normal[1] * normal[1] + normal[2] * normal[2])
                if (len < 1e-9f) {
                    keyOf[fi] = Long.MIN_VALUE + fi
                    groups.getOrPut(keyOf[fi]!!) { ArrayList() }.add(fi)
                    continue
                }
                val nx = normal[0] / len
                val ny = normal[1] / len
                val nz = normal[2] / len
                val d = nx * mesh.x(mesh.faces[fi][0]) + ny * mesh.y(mesh.faces[fi][0]) + nz * mesh.z(mesh.faces[fi][0])
                val kx = Math.round(nx * 200f).toLong()
                val ky = Math.round(ny * 200f).toLong()
                val kz = Math.round(nz * 200f).toLong()
                val kd = Math.round(d * 2000f).toLong()
                val key = (kx * 31 + ky) * 31 + kz + kd * 1_000_003L
                keyOf[fi] = key
                groups.getOrPut(key) { ArrayList() }.add(fi)
            }
            val out = EditableMesh()
            for (i in 0 until mesh.vertexCount) out.addVertex(mesh.x(i), mesh.y(i), mesh.z(i))
            out.name = mesh.name

            for ((_, faceList) in groups) {
                val mat = mesh.faceMaterial.getOrElse(faceList[0]) { 0 }
                if (faceList.size == 1) {
                    out.addFace(mesh.faces[faceList[0]].copyOf(), mat)
                    continue
                }
                // Aristas dirigidas del grupo; las que no tienen contraria forman el contorno.
                val directed = HashSet<Long>()
                fun dkey(a: Int, b: Int): Long = (a.toLong() shl 32) or b.toLong()
                for (fi in faceList) {
                    val f = mesh.faces[fi]
                    for (k in f.indices) directed.add(dkey(f[k], f[(k + 1) % f.size]))
                }
                val next = HashMap<Int, Int>()
                var boundaryEdges = 0
                for (fi in faceList) {
                    val f = mesh.faces[fi]
                    for (k in f.indices) {
                        val a = f[k]
                        val b = f[(k + 1) % f.size]
                        if (!directed.contains(dkey(b, a))) {
                            next[a] = b
                            boundaryEdges++
                        }
                    }
                }
                if (boundaryEdges < 3) {
                    for (fi in faceList) out.addFace(mesh.faces[fi].copyOf(), mat)
                    continue
                }
                val used = HashSet<Int>()
                val facesBefore = out.faces.size
                for (start in next.keys) {
                    if (used.contains(start)) continue
                    val loop = ArrayList<Int>()
                    var current = start
                    var guard = 0
                    while (guard < next.size + 1) {
                        if (!used.add(current)) break
                        loop.add(current)
                        val n = next[current] ?: break
                        if (n == start) break
                        current = n
                        guard++
                    }
                    if (loop.size >= 3) out.addFace(loop.toIntArray(), mat)
                }
                // Si la reconstrucción de este grupo falló, conservar sus triángulos originales.
                if (out.faces.size == facesBefore) {
                    for (fi in faceList) out.addFace(mesh.faces[fi].copyOf(), mat)
                }
            }
            return out
        }

        /** Caja con la base en y = 0 (igual que las primitivas del visor). */
        fun box(width: Float = 1f, height: Float = 1f, depth: Float = 1f): EditableMesh {
            val m = EditableMesh()
            val hw = width * 0.5f
            val hd = depth * 0.5f
            m.addVertex(-hw, 0f, -hd)     // 0
            m.addVertex(hw, 0f, -hd)      // 1
            m.addVertex(hw, 0f, hd)       // 2
            m.addVertex(-hw, 0f, hd)      // 3
            m.addVertex(-hw, height, -hd) // 4
            m.addVertex(hw, height, -hd)  // 5
            m.addVertex(hw, height, hd)   // 6
            m.addVertex(-hw, height, hd)  // 7
            m.addFace(intArrayOf(3, 2, 1, 0))               // abajo
            m.addFace(intArrayOf(4, 5, 6, 7))               // arriba
            m.addFace(intArrayOf(0, 1, 5, 4))               // -z
            m.addFace(intArrayOf(2, 3, 7, 6))               // +z
            m.addFace(intArrayOf(1, 2, 6, 5))               // +x
            m.addFace(intArrayOf(3, 0, 4, 7))               // -x
            m.ensureOutward()
            m.name = "Cubo"
            return m
        }

        fun plane(size: Float = 1f, divisions: Int = 1): EditableMesh {
            val n = divisions.coerceIn(1, 64)
            val m = EditableMesh()
            val half = size * 0.5f
            for (iz in 0..n) {
                for (ix in 0..n) {
                    m.addVertex(
                        -half + ix * size / n,
                        0f,
                        -half + iz * size / n
                    )
                }
            }
            val row = n + 1
            for (iz in 0 until n) {
                for (ix in 0 until n) {
                    val a = iz * row + ix
                    m.addFace(intArrayOf(a, a + row, a + row + 1, a + 1))
                }
            }
            m.name = "Plano"
            return m
        }

        fun cylinder(radius: Float = 0.5f, height: Float = 1f, segments: Int = 24, caps: Boolean = true): EditableMesh {
            val n = segments.coerceIn(3, 128)
            val m = EditableMesh()
            for (i in 0 until n) {
                val a = i.toFloat() / n * Math.PI.toFloat() * 2f
                m.addVertex(kotlin.math.cos(a) * radius, 0f, kotlin.math.sin(a) * radius)
            }
            for (i in 0 until n) {
                val a = i.toFloat() / n * Math.PI.toFloat() * 2f
                m.addVertex(kotlin.math.cos(a) * radius, height, kotlin.math.sin(a) * radius)
            }
            for (i in 0 until n) {
                val j = (i + 1) % n
                m.addFace(intArrayOf(i, j, j + n, i + n))
            }
            if (caps) {
                val bottom = IntArray(n) { (n - 1 - it) }
                m.addFace(bottom)
                val top = IntArray(n) { it + n }
                m.addFace(top)
            }
            m.ensureOutward()
            m.name = "Cilindro"
            return m
        }

        fun cone(radius: Float = 0.5f, height: Float = 1f, segments: Int = 24): EditableMesh {
            val n = segments.coerceIn(3, 128)
            val m = EditableMesh()
            for (i in 0 until n) {
                val a = i.toFloat() / n * Math.PI.toFloat() * 2f
                m.addVertex(kotlin.math.cos(a) * radius, 0f, kotlin.math.sin(a) * radius)
            }
            val apex = m.addVertex(0f, height, 0f)
            for (i in 0 until n) {
                val j = (i + 1) % n
                m.addFace(intArrayOf(i, j, apex))
            }
            m.addFace(IntArray(n) { n - 1 - it })
            m.ensureOutward()
            m.name = "Cono"
            return m
        }

        fun sphere(radius: Float = 0.5f, segments: Int = 24, rings: Int = 16): EditableMesh {
            val meshData = com.arena.modeler.scene.Primitives.sphere(radius, segments, rings)
            val m = fromMeshData(meshData, weld = true, mergeCoplanar = false)
            m.translate(0f, radius, 0f)
            m.name = "Esfera"
            return m
        }

        fun torus(majorRadius: Float = 0.5f, tubeRadius: Float = 0.18f, major: Int = 24, minor: Int = 12): EditableMesh {
            val meshData = com.arena.modeler.scene.Primitives.torus(majorRadius, tubeRadius, major, minor)
            val m = fromMeshData(meshData, weld = true, mergeCoplanar = false)
            m.translate(0f, tubeRadius, 0f)
            m.name = "Toro"
            return m
        }

        fun icosphere(radius: Float = 0.5f, subdivisions: Int = 2): EditableMesh {
            val meshData = com.arena.modeler.scene.Primitives.icosphere(radius, subdivisions)
            val m = fromMeshData(meshData, weld = true, mergeCoplanar = false)
            m.name = "Icósfera"
            return m
        }

        fun fromPrimitive(kind: com.arena.modeler.scene.Primitives.Kind): EditableMesh = when (kind) {
            com.arena.modeler.scene.Primitives.Kind.CUBE -> box()
            com.arena.modeler.scene.Primitives.Kind.SPHERE -> sphere()
            com.arena.modeler.scene.Primitives.Kind.CYLINDER -> cylinder()
            com.arena.modeler.scene.Primitives.Kind.CONE -> cone()
            com.arena.modeler.scene.Primitives.Kind.TORUS -> torus()
            com.arena.modeler.scene.Primitives.Kind.PLANE -> plane()
            com.arena.modeler.scene.Primitives.Kind.ICOSPHERE -> icosphere()
        }
    }
}
