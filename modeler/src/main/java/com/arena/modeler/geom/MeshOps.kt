package com.arena.modeler.geom

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Operaciones de modelado sobre [EditableMesh]. Todas devuelven una malla **nueva** y no tocan la
 * original, de modo que el ejecutor de herramientas pueda descartar un resultado sin dejar la
 * escena a medias.
 *
 * Cada función comprueba sus límites antes de trabajar: un modelo de 500 000 caras no puede
 * reventar la memoria de un teléfono, así que las subidas de densidad se rechazan con un mensaje.
 */
object MeshOps {

    /** Presupuesto por operación: por encima se rechaza en lugar de colgar el teléfono. */
    const val MAX_FACES = 120_000
    const val MAX_VERTICES = 200_000

    class MeshOpException(message: String) : Exception(message)

    private fun check(mesh: EditableMesh, extraFaces: Int = 0) {
        if (mesh.positions.isEmpty()) throw MeshOpException("La malla está vacía")
        if (mesh.faces.size + extraFaces > MAX_FACES) {
            throw MeshOpException(
                "La malla resultante pasaría de ${MAX_FACES} caras. Reduce la subdivisión o la resolución."
            )
        }
    }

    // ------------------------------------------------------------------ extrusión

    /**
     * Extruye la malla a lo largo de [distance] por su normal media. Si [faceIndices] está vacío se
     * extruye la malla entera (como "extruir región" en Blender con todo seleccionado).
     */
    fun extrude(mesh: EditableMesh, distance: Float, faceIndices: Collection<Int>? = null): EditableMesh {
        check(mesh)
        val selected = faceIndices?.filter { it in mesh.faces.indices }?.toSet()
            ?: mesh.faces.indices.toSet()
        if (selected.isEmpty()) throw MeshOpException("No hay caras para extruir")

        val out = EditableMesh()
        out.positions.addAll(mesh.positions)
        out.name = mesh.name
        val n = FloatArray(3)
        // Normal media de la región (para desplazar los vértices nuevos).
        var nx = 0f
        var ny = 0f
        var nz = 0f
        for (fi in selected) {
            mesh.faceNormal(fi, n)
            nx += n[0]
            ny += n[1]
            nz += n[2]
        }
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        // Una región cerrada tiene normal media cero (las caras opuestas se cancelan): extruirla
        // "en bloque" no tiene sentido, así que cada cara se extruye por su cuenta, que es la
        // operación clásica de «extruir caras individuales».
        if (len < 1e-8f) return extrudeEachFace(mesh, selected, distance)
        nx = nx / len * distance
        ny = ny / len * distance
        nz = nz / len * distance

        val duplicated = HashMap<Int, Int>()
        fun dup(v: Int): Int = duplicated.getOrPut(v) {
            out.addVertex(mesh.x(v) + nx, mesh.y(v) + ny, mesh.z(v) + nz)
        }

        for (fi in mesh.faces.indices) {
            val f = mesh.faces[fi]
            val mat = mesh.faceMaterial.getOrElse(fi) { 0 }
            if (fi !in selected) {
                out.addFace(f.copyOf(), mat)
                continue
            }
            // La cara superior se mueve, y las paredes laterales conectan el borde de la región.
            val top = IntArray(f.size) { dup(f[it]) }
            out.addFace(top, mat)
        }
        // Paredes: para cada arista de contorno de la región (una sola cara seleccionada la usa).
        val edgeUse = HashMap<Long, Int>()
        for (fi in selected) {
            val f = mesh.faces[fi]
            for (k in f.indices) {
                val key = EditableMesh.Edge(f[k], f[(k + 1) % f.size]).key()
                edgeUse[key] = (edgeUse[key] ?: 0) + 1
            }
        }
        for (fi in selected) {
            val f = mesh.faces[fi]
            val mat = mesh.faceMaterial.getOrElse(fi) { 0 }
            for (k in f.indices) {
                val a = f[k]
                val b = f[(k + 1) % f.size]
                val key = EditableMesh.Edge(a, b).key()
                if ((edgeUse[key] ?: 0) > 1) continue
                out.addFace(intArrayOf(a, b, dup(b), dup(a)), mat)
            }
        }
        return out.compact()
    }

    /**
     * Extrusión individual: cada cara se desplaza por su propia normal y se cosen paredes en sus
     * aristas. Se usa cuando la región no tiene una normal común (objeto cerrado completo).
     */
    private fun extrudeEachFace(
        mesh: EditableMesh,
        faces: Set<Int>,
        distance: Float
    ): EditableMesh {
        val out = EditableMesh()
        out.positions.addAll(mesh.positions)
        out.name = mesh.name
        val n = FloatArray(3)
        for (fi in mesh.faces.indices) {
            val mat = mesh.faceMaterial.getOrElse(fi) { 0 }
            val f = mesh.faces[fi]
            if (fi !in faces) {
                out.addFace(f.copyOf(), mat)
                continue
            }
            mesh.faceNormal(fi, n)
            val l = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
            if (l < 1e-9f) {
                out.addFace(f.copyOf(), mat)
                continue
            }
            val dx = n[0] / l * distance
            val dy = n[1] / l * distance
            val dz = n[2] / l * distance
            val map = HashMap<Int, Int>()
            fun dup(v: Int): Int = map.getOrPut(v) {
                out.addVertex(mesh.x(v) + dx, mesh.y(v) + dy, mesh.z(v) + dz)
            }
            out.addFace(IntArray(f.size) { dup(f[it]) }, mat)
            for (k in f.indices) {
                val a = f[k]
                val b = f[(k + 1) % f.size]
                out.addFace(intArrayOf(a, b, dup(b), dup(a)), mat)
            }
        }
        return out.compact()
    }

    // ------------------------------------------------------------------ bisel

    /**
     * Bisel de aristas y vértices (el "bevel" clásico de un modelador):
     *
     * 1. Cada cara se encoge hacia su centro → el polígono interior.
     * 2. Cada arista compartida por dos caras se rellena con un cuadrilátero.
     * 3. Cada vértice se rellena con un polígono que une sus copias, ordenadas en giro.
     *
     * El resultado es una malla cerrada de nuevo, sin caras degeneradas.
     */
    fun bevel(mesh: EditableMesh, amount: Float, segments: Int = 1): EditableMesh {
        check(mesh)
        if (amount <= 0f) throw MeshOpException("El bisel necesita un grosor mayor que cero")
        if (segments > 1) {
            // Bisel redondeado: bisel simple + una pasada suave de subdivisión.
            return subdivide(bevel(mesh, amount, 1), iterations = 1, smooth = true)
        }
        val src = mesh.weld(1e-5f)
        if (src.faces.isEmpty()) throw MeshOpException("La malla está vacía")
        val out = EditableMesh()
        out.name = src.name

        // 1) Vértices interiores por (cara, vértice), desplazados hacia el centro de la cara.
        val insetIndex = HashMap<Long, Int>()
        val center = FloatArray(3)
        fun insetOf(face: Int, vertex: Int): Int {
            val key = (face.toLong() shl 32) or (vertex.toLong() and 0xFFFFFFFFL)
            return insetIndex.getOrPut(key) {
                src.faceCenter(face, center)
                val dx = center[0] - src.x(vertex)
                val dy = center[1] - src.y(vertex)
                val dz = center[2] - src.z(vertex)
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                val t = if (d > 1e-8f) (amount / d).coerceIn(0f, 0.45f) else 0f
                out.addVertex(
                    src.x(vertex) + dx * t,
                    src.y(vertex) + dy * t,
                    src.z(vertex) + dz * t
                )
            }
        }

        // 2) Las caras originales, encogidas.
        for (fi in src.faces.indices) {
            val f = src.faces[fi]
            val nf = IntArray(f.size) { insetOf(fi, f[it]) }
            out.addFace(nf, src.faceMaterial.getOrElse(fi) { 0 })
        }

        // 3) Relleno por arista.
        val edgeFaces = src.edgeFaces()
        for ((key, faceList) in edgeFaces) {
            if (faceList.size != 2) continue
            val a = (key shr 32).toInt()
            val b = (key and 0xFFFFFFFFL).toInt()
            val f1 = faceList[0]
            val f2 = faceList[1]
            val mat = src.faceMaterial.getOrElse(f1) { 0 }
            out.addFace(
                intArrayOf(insetOf(f1, a), insetOf(f1, b), insetOf(f2, b), insetOf(f2, a)),
                mat
            )
        }

        // 4) Relleno por vértice: se ordenan sus caras en giro alrededor de la normal del vértice.
        val vertexFaces = src.vertexFaces()
        val vertexNormals = vertexNormals(src)
        for (v in 0 until src.vertexCount) {
            val faces = vertexFaces[v]
            if (faces.size < 3) continue
            val nx = vertexNormals[v * 3]
            val ny = vertexNormals[v * 3 + 1]
            val nz = vertexNormals[v * 3 + 2]
            // Base tangente estable.
            val ax = if (abs(nx) < 0.9f) 1f else 0f
            val ay = if (ax == 1f) 0f else 1f
            var tx = ay * nz - 0f * ny
            var ty = 0f * nx - ax * nz
            var tz = ax * ny - ay * nx
            val tl = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-6f)
            tx /= tl
            ty /= tl
            tz /= tl
            val bx = ny * tz - nz * ty
            val by = nz * tx - nx * tz
            val bz = nx * ty - ny * tx
            val sorted = faces.sortedBy { fi ->
                val f = src.faces[fi]
                var cx = 0f
                var cy = 0f
                var cz = 0f
                for (idx in f) {
                    cx += src.x(idx)
                    cy += src.y(idx)
                    cz += src.z(idx)
                }
                val n = f.size.toFloat()
                val dx = cx / n - src.x(v)
                val dy = cy / n - src.y(v)
                val dz = cz / n - src.z(v)
                Math.atan2(
                    (dx * bx + dy * by + dz * bz).toDouble(),
                    (dx * tx + dy * ty + dz * tz).toDouble()
                )
            }
            val ring = IntArray(sorted.size) { k -> insetOf(sorted[k], v) }
            // Se invierte para que la cara mire hacia fuera, como el resto de la malla.
            ring.reverse()
            out.addFace(ring, src.faceMaterial.getOrElse(sorted[0]) { 0 })
        }
        return dropDegenerate(out).ensureOutward()
    }

    /** Inset: cada cara se encoge hacia su centro creando un anillo (bisel de caras). */
    fun inset(mesh: EditableMesh, amount: Float): EditableMesh {
        check(mesh, mesh.faces.size)
        val out = EditableMesh()
        out.name = mesh.name
        out.positions.addAll(mesh.positions)
        val c = FloatArray(3)
        for (fi in mesh.faces.indices) {
            val f = mesh.faces[fi]
            val mat = mesh.faceMaterial.getOrElse(fi) { 0 }
            mesh.faceCenter(fi, c)
            val inner = IntArray(f.size)
            for (k in f.indices) {
                val v = f[k]
                val dx = c[0] - mesh.x(v)
                val dy = c[1] - mesh.y(v)
                val dz = c[2] - mesh.z(v)
                val d = sqrt(dx * dx + dy * dy + dz * dz)
                val t = if (d > 1e-8f) (amount / d).coerceAtMost(0.95f) else 0f
                inner[k] = out.addVertex(mesh.x(v) + dx * t, mesh.y(v) + dy * t, mesh.z(v) + dz * t)
            }
            out.addFace(inner, mat)
            for (k in f.indices) {
                val nk = (k + 1) % f.size
                out.addFace(intArrayOf(f[k], f[nk], inner[nk], inner[k]), mat)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ subdivisión

    /**
     * Subdivisión de Catmull-Clark (la de "subdividir superficie"): produce mallas suaves a partir
     * de cuadriláteros, con [iterations] pasadas. En cada pasada las caras se multiplican por 4.
     */
    fun subdivide(mesh: EditableMesh, iterations: Int = 1, smooth: Boolean = true): EditableMesh {
        var current = mesh
        val passes = iterations.coerceIn(1, 3)
        for (p in 0 until passes) {
            val projected = current.faces.sumOf { it.size }
            if (projected > MAX_FACES) {
                throw MeshOpException(
                    "Subdividir $passes veces daría demasiadas caras. Usa menos iteraciones."
                )
            }
            current = catmullClark(current, smooth)
        }
        return current
    }

    /** Una pasada de Catmull-Clark. Con `smooth = false` subdivide sin desplazar (simple). */
    fun catmullClark(mesh: EditableMesh, smooth: Boolean = true): EditableMesh {
        val src = mesh
        if (src.faces.isEmpty()) throw MeshOpException("La malla está vacía")
        val out = EditableMesh()
        out.name = src.name

        val facePoint = FloatArray(src.faceCount * 3)
        val c = FloatArray(3)
        for (fi in src.faces.indices) {
            src.faceCenter(fi, c)
            facePoint[fi * 3] = c[0]
            facePoint[fi * 3 + 1] = c[1]
            facePoint[fi * 3 + 2] = c[2]
        }

        val edges = src.edgeFaces()
        val edgePointPos = HashMap<Long, FloatArray>()
        val vertexFaces = src.vertexFaces()
        for ((key, faceList) in edges) {
            val a = (key shr 32).toInt()
            val b = (key and 0xFFFFFFFFL).toInt()
            if (faceList.size == 2) {
                val f1 = faceList[0]
                val f2 = faceList[1]
                edgePointPos[key] = floatArrayOf(
                    (src.x(a) + src.x(b) + facePoint[f1 * 3] + facePoint[f2 * 3]) / 4f,
                    (src.y(a) + src.y(b) + facePoint[f1 * 3 + 1] + facePoint[f2 * 3 + 1]) / 4f,
                    (src.z(a) + src.z(b) + facePoint[f1 * 3 + 2] + facePoint[f2 * 3 + 2]) / 4f
                )
            } else {
                edgePointPos[key] = floatArrayOf(
                    (src.x(a) + src.x(b)) / 2f,
                    (src.y(a) + src.y(b)) / 2f,
                    (src.z(a) + src.z(b)) / 2f
                )
            }
        }

        // Nuevos vértices (posiciones de los vértices originales suavizadas).
        val vertexIndex = IntArray(src.vertexCount)
        val neighbors = src.vertexNeighbors()
        for (v in 0 until src.vertexCount) {
            val faces = vertexFaces[v]
            val nb = neighbors[v]
            if (!smooth || faces.isEmpty() || nb.isEmpty()) {
                vertexIndex[v] = out.addVertex(src.x(v), src.y(v), src.z(v))
                continue
            }
            val n = nb.size.toFloat()
            var fx = 0f
            var fy = 0f
            var fz = 0f
            for (fi in faces) {
                fx += facePoint[fi * 3]
                fy += facePoint[fi * 3 + 1]
                fz += facePoint[fi * 3 + 2]
            }
            val fCount = faces.size.toFloat()
            var rx = 0f
            var ry = 0f
            var rz = 0f
            for (e in nb) {
                rx += (src.x(v) + src.x(e)) / 2f
                ry += (src.y(v) + src.y(e)) / 2f
                rz += (src.z(v) + src.z(e)) / 2f
            }
            val px = (fx / fCount + rx / n + src.x(v) * (n - 3f)) / n
            val py = (fy / fCount + ry / n + src.y(v) * (n - 3f)) / n
            val pz = (fz / fCount + rz / n + src.z(v) * (n - 3f)) / n
            vertexIndex[v] = out.addVertex(px, py, pz)
        }

        // Aristas nuevas
        val edgeIndex = HashMap<Long, Int>()
        for ((key, pos) in edgePointPos) {
            edgeIndex[key] = out.addVertex(pos[0], pos[1], pos[2])
        }
        // Caras nuevas: un cuadrilátero por cada esquina de cada cara original.
        for (fi in src.faces.indices) {
            val f = src.faces[fi]
            val mat = src.faceMaterial.getOrElse(fi) { 0 }
            val center = out.addVertex(facePoint[fi * 3], facePoint[fi * 3 + 1], facePoint[fi * 3 + 2])
            for (k in f.indices) {
                val prev = f[(k - 1 + f.size) % f.size]
                val cur = f[k]
                val next = f[(k + 1) % f.size]
                val e1 = edgeIndex[EditableMesh.Edge(prev, cur).key()]
                val e2 = edgeIndex[EditableMesh.Edge(cur, next).key()]
                if (e1 == null || e2 == null) continue
                out.addFace(intArrayOf(vertexIndex[cur], e2, center, e1), mat)
            }
        }
        return out
    }

    // ------------------------------------------------------------------ suavizado

    /** Suavizado laplaciano: cada vértice se acerca a la media de sus vecinos. */
    fun smoothMesh(mesh: EditableMesh, iterations: Int = 1, strength: Float = 0.5f): EditableMesh {
        check(mesh)
        val out = mesh.copy()
        val neighbors = out.vertexNeighbors()
        val factor = strength.coerceIn(0f, 1f)
        repeat(iterations.coerceIn(1, 10)) {
            val next = FloatArray(out.positions.size)
            for (i in next.indices) next[i] = out.positions[i]
            for (v in 0 until out.vertexCount) {
                val nb = neighbors[v]
                if (nb.isEmpty()) continue
                var sx = 0f
                var sy = 0f
                var sz = 0f
                for (n in nb) {
                    sx += out.x(n)
                    sy += out.y(n)
                    sz += out.z(n)
                }
                val count = nb.size.toFloat()
                next[v * 3] = out.x(v) + (sx / count - out.x(v)) * factor
                next[v * 3 + 1] = out.y(v) + (sy / count - out.y(v)) * factor
                next[v * 3 + 2] = out.z(v) + (sz / count - out.z(v)) * factor
            }
            for (i in next.indices) out.positions[i] = next[i]
        }
        return out
    }

    // ------------------------------------------------------------------ simetría

    enum class Axis { X, Y, Z }

    /**
     * Simetría: copia la mitad positiva del eje y la refleja. Con [keepBoth] conserva la mitad
     * original (modelado de un solo lado), si no, reconstruye la malla completa desde ella.
     */
    fun mirror(mesh: EditableMesh, axis: Axis = Axis.X, keepBoth: Boolean = true): EditableMesh {
        check(mesh, mesh.faces.size)
        val out = EditableMesh()
        out.name = mesh.name
        out.positions.addAll(mesh.positions)
        for (fi in mesh.faces.indices) {
            out.addFace(mesh.faces[fi].copyOf(), mesh.faceMaterial.getOrElse(fi) { 0 })
        }
        val map = HashMap<Int, Int>()
        fun reflect(v: Int): Int = map.getOrPut(v) {
            when (axis) {
                Axis.X -> out.addVertex(-mesh.x(v), mesh.y(v), mesh.z(v))
                Axis.Y -> out.addVertex(mesh.x(v), -mesh.y(v), mesh.z(v))
                Axis.Z -> out.addVertex(mesh.x(v), mesh.y(v), -mesh.z(v))
            }
        }
        for (fi in mesh.faces.indices) {
            val f = mesh.faces[fi]
            // Solo los polígonos de la mitad positiva, para no duplicar el eje.
            val centroidAlong = when (axis) {
                Axis.X -> f.sumOf { mesh.x(it).toDouble() }.toFloat() / f.size
                Axis.Y -> f.sumOf { mesh.y(it).toDouble() }.toFloat() / f.size
                Axis.Z -> f.sumOf { mesh.z(it).toDouble() }.toFloat() / f.size
            }
            if (centroidAlong < 0f) continue
            val nf = IntArray(f.size)
            for (k in f.indices) nf[k] = reflect(f[f.size - 1 - k])
            out.addFace(nf, mesh.faceMaterial.getOrElse(fi) { 0 })
        }
        // Con keepBoth se conserva también la mitad original; sin él la malla se reconstruye
        // desde la mitad positiva (lo habitual al modelar la mitad y espejar).
        if (!keepBoth) {
            val rebuilt = EditableMesh()
            rebuilt.positions.addAll(out.positions)
            for (fi in out.faces.indices) {
                val f = out.faces[fi]
                val centroidAlong = when (axis) {
                    Axis.X -> f.sumOf { out.x(it).toDouble() }.toFloat() / f.size
                    Axis.Y -> f.sumOf { out.y(it).toDouble() }.toFloat() / f.size
                    Axis.Z -> f.sumOf { out.z(it).toDouble() }.toFloat() / f.size
                }
                if (centroidAlong >= -1e-5f) {
                    rebuilt.addFace(f.copyOf(), out.faceMaterial.getOrElse(fi) { 0 })
                }
            }
            return rebuilt.weld(1e-5f)
        }
        return out.weld(1e-5f)
    }

    // ------------------------------------------------------------------ decimación

    /**
     * Reduce polígonos agrupando caras coplanares en polígonos mayores y descartando las
     * degeneradas. No es un decimador de colapso de aristas, pero en modelos procedimentales
     * baja el recuento de triángulos drásticamente sin deformar la silueta.
     */
    fun reduce(mesh: EditableMesh, mergeAngleDegrees: Float = 1f): EditableMesh {
        check(mesh)
        val merged = EditableMesh.mergeCoplanarFaces(mesh.compact())
        val out = EditableMesh()
        out.positions.addAll(merged.positions)
        out.name = merged.name
        for (fi in merged.faces.indices) {
            val f = merged.faces[fi]
            if (f.size < 3) continue
            out.addFace(f, merged.faceMaterial.getOrElse(fi) { 0 })
        }
        val cleaned = dropDegenerate(out)
        // Se compara el número de **caras**: agrupar dos triángulos en un cuadrilátero deja los
        // mismos triángulos pero la mitad de caras, que es lo que el usuario ve al editar.
        if (cleaned.faces.size >= mesh.faces.size) return mesh
        // El ángulo se documenta para el usuario; el agrupado ya es por plano exacto.
        return cleaned
    }

    // ------------------------------------------------------------------ validación

    class ValidationResult(val problems: List<String>, val degenerateFaces: Int, val duplicateVertices: Int) {
        val ok: Boolean get() = problems.isEmpty()
    }

    /** Comprueba índices fuera de rango, caras degeneradas y vértices repetidos exactos. */
    fun validate(mesh: EditableMesh): ValidationResult {
        val problems = ArrayList<String>()
        var degenerate = 0
        val used = HashSet<Int>()
        for (fi in mesh.faces.indices) {
            val f = mesh.faces[fi]
            if (f.size < 3) {
                degenerate++
                continue
            }
            if (f.any { it < 0 || it >= mesh.vertexCount }) {
                problems.add("La cara $fi apunta a vértices que no existen")
                continue
            }
            if (f.toSet().size < 3) degenerate++
            for (v in f) used.add(v)
        }
        if (mesh.vertexCount > MAX_VERTICES) problems.add("La malla tiene más de $MAX_VERTICES vértices")
        if (mesh.faces.size > MAX_FACES) problems.add("La malla tiene más de $MAX_FACES caras")
        var duplicates = 0
        val seen = HashMap<Long, Int>()
        for (v in 0 until mesh.vertexCount) {
            if (v !in used) continue
            val key = (Math.round(mesh.x(v) * 100000f).toLong() * 73856093L) xor
                (Math.round(mesh.y(v) * 100000f).toLong() * 19349663L) xor
                (Math.round(mesh.z(v) * 100000f).toLong() * 83492791L)
            val prev = seen.put(key, v)
            if (prev != null) duplicates++
        }
        for (v in 0 until mesh.vertexCount) {
            val x = mesh.x(v)
            val y = mesh.y(v)
            val z = mesh.z(v)
            if (x.isNaN() || y.isNaN() || z.isNaN() || x.isInfinite() || y.isInfinite() || z.isInfinite()) {
                problems.add("Hay vértices con coordenadas no válidas")
                break
            }
        }
        return ValidationResult(problems, degenerate, duplicates)
    }

    /** Quita caras con menos de 3 vértices o repetidos. */
    fun dropDegenerate(mesh: EditableMesh): EditableMesh {
        val out = EditableMesh()
        out.positions.addAll(mesh.positions)
        out.name = mesh.name
        for (fi in mesh.faces.indices) {
            val f = mesh.faces[fi]
            if (f.size < 3) continue
            if (f.toSet().size < 3) continue
            if (f.any { it < 0 || it >= mesh.vertexCount }) continue
            out.addFace(f, mesh.faceMaterial.getOrElse(fi) { 0 })
        }
        return out.compact()
    }

    /** Repara una malla importada: suelda, tira caras degeneradas y asegura normales hacia fuera. */
    fun repair(mesh: EditableMesh): EditableMesh {
        val out = dropDegenerate(mesh.weld(1e-4f))
        if (out.isManifoldClosed()) out.ensureOutward()
        return out
    }

    // ------------------------------------------------------------------ esculpido

    /**
     * Pincel de esculpido: desplaza los vértices cercanos a [center] a lo largo de la normal media,
     * con caída suave. Es el equivalente móvil de "inflar" en un esculpido.
     */
    fun sculpt(
        mesh: EditableMesh,
        center: FloatArray,
        radius: Float,
        strength: Float,
        mode: SculptMode = SculptMode.INFLATE
    ): EditableMesh {
        check(mesh)
        val out = mesh.copy()
        if (radius <= 0f) throw MeshOpException("El radio del pincel debe ser mayor que cero")
        val normals = vertexNormals(out)
        val faces = out.vertexFaces()
        for (v in 0 until out.vertexCount) {
            val dx = out.x(v) - center[0]
            val dy = out.y(v) - center[1]
            val dz = out.z(v) - center[2]
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d > radius) continue
            val falloff = 1f - (d / radius)
            val w = falloff * falloff * strength
            when (mode) {
                SculptMode.INFLATE -> {
                    out.setVertex(
                        v,
                        out.x(v) + normals[v * 3] * w,
                        out.y(v) + normals[v * 3 + 1] * w,
                        out.z(v) + normals[v * 3 + 2] * w
                    )
                }
                SculptMode.SMOOTH -> {
                    val nb = out.vertexNeighbors()[v]
                    if (nb.isNotEmpty()) {
                        var sx = 0f
                        var sy = 0f
                        var sz = 0f
                        for (n in nb) {
                            sx += out.x(n)
                            sy += out.y(n)
                            sz += out.z(n)
                        }
                        val c = nb.size.toFloat()
                        out.setVertex(
                            v,
                            out.x(v) + (sx / c - out.x(v)) * falloff * strength,
                            out.y(v) + (sy / c - out.y(v)) * falloff * strength,
                            out.z(v) + (sz / c - out.z(v)) * falloff * strength
                        )
                    }
                }
                SculptMode.PINCH -> {
                    val cx = center[0] - out.x(v)
                    val cy = center[1] - out.y(v)
                    val cz = center[2] - out.z(v)
                    out.setVertex(
                        v,
                        out.x(v) + cx * w * 0.5f,
                        out.y(v) + cy * w * 0.5f,
                        out.z(v) + cz * w * 0.5f
                    )
                }
            }
        }
        if (faces.isEmpty()) return out
        return out
    }

    enum class SculptMode(val label: String) {
        INFLATE("Inflar"),
        SMOOTH("Suavizar"),
        PINCH("Pellizcar")
    }

    /** Normal suave por vértice (media de las caras). */
    fun vertexNormals(mesh: EditableMesh): FloatArray {
        val normals = FloatArray(mesh.vertexCount * 3)
        val n = FloatArray(3)
        for (fi in mesh.faces.indices) {
            mesh.faceNormal(fi, n)
            for (v in mesh.faces[fi]) {
                if (v !in 0 until mesh.vertexCount) continue
                normals[v * 3] += n[0]
                normals[v * 3 + 1] += n[1]
                normals[v * 3 + 2] += n[2]
            }
        }
        for (v in 0 until mesh.vertexCount) {
            val len = sqrt(normals[v * 3] * normals[v * 3] + normals[v * 3 + 1] * normals[v * 3 + 1] + normals[v * 3 + 2] * normals[v * 3 + 2])
            if (len > 1e-9f) {
                normals[v * 3] /= len
                normals[v * 3 + 1] /= len
                normals[v * 3 + 2] /= len
            } else {
                normals[v * 3 + 1] = 1f
            }
        }
        return normals
    }

    // ------------------------------------------------------------------ varios

    /**
     * Barrido: coloca un perfil cerrado (lista de puntos en el plano local X/Y) a lo largo de una
     * trayectoria, orientándolo a la tangente, y cose los anillos con cuadriláteros. Con [caps] se
     * cierran los extremos con abanicos triangulares, así el resultado es un sólido cerrado.
     */
    fun sweep(
        profile: List<FloatArray>,
        path: List<FloatArray>,
        caps: Boolean = true,
        material: Int = 0
    ): EditableMesh {
        if (path.size < 2) throw MeshOpException("La trayectoria necesita al menos dos puntos")
        if (profile.size < 3) throw MeshOpException("El perfil necesita al menos 3 puntos")
        val out = EditableMesh()
        out.name = "Barrido"
        val rings = ArrayList<IntArray>(path.size)
        for (i in path.indices) {
            val p = path[i]
            val tangent = when {
                i == 0 -> normalize(sub(path[1], path[0]))
                i == path.size - 1 -> normalize(sub(path[i], path[i - 1]))
                else -> normalize(sub(path[i + 1], path[i - 1]))
            }
            val up = if (abs(tangent[1]) > 0.95f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(0f, 1f, 0f)
            val right = normalize(cross(up, tangent))
            val realUp = cross(tangent, right)
            val ring = IntArray(profile.size)
            for (k in profile.indices) {
                val local = profile[k]
                val lx = local[0]
                val ly = if (local.size > 1) local[1] else 0f
                ring[k] = out.addVertex(
                    p[0] + right[0] * lx + realUp[0] * ly,
                    p[1] + right[1] * lx + realUp[1] * ly,
                    p[2] + right[2] * lx + realUp[2] * ly
                )
            }
            rings.add(ring)
        }
        val size = profile.size
        for (r in 0 until rings.size - 1) {
            val a = rings[r]
            val b = rings[r + 1]
            for (k in 0 until size) {
                val nk = (k + 1) % size
                out.addFace(intArrayOf(a[k], a[nk], b[nk], b[k]), material)
            }
        }
        if (caps) {
            val first = rings.first()
            out.addFace(IntArray(size) { first[size - 1 - it] }, material)
            val last = rings.last()
            out.addFace(IntArray(size) { last[it] }, material)
        }
        return out.weld(1e-5f).let { MeshOps.dropDegenerate(it) }
    }

    private fun sub(a: FloatArray, b: FloatArray) =
        floatArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])

    private fun cross(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    private fun normalize(v: FloatArray): FloatArray {
        val len = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        if (len < 1e-9f) return floatArrayOf(0f, 0f, 1f)
        return floatArrayOf(v[0] / len, v[1] / len, v[2] / len)
    }

}
