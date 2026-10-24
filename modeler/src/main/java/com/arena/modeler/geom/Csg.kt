package com.arena.modeler.geom

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Operaciones booleanas por árbol BSP, siguiendo el algoritmo de **csg.js** de Evan Wallace
 * (MIT): cada sólido se convierte en polígonos convexos, se reparten contra los planos de los
 * demás y se recompone el resultado con las caras que sobreviven a la operación.
 *
 * Se implementa aquí, sin dependencias, porque no hay ninguna librería de CSG para Android que
 * sea fiable sin NDK. Límite honesto: es un algoritmo O(n²) en el peor caso, así que se rechazan
 * los operandos grandes antes de empezar (ver [MAX_POLYGONS]).
 */
object Csg {

    const val MAX_POLYGONS = 12_000

    class CsgException(message: String) : Exception(message)

    private const val EPSILON = 1e-5f

    class Vertex(val x: Float, val y: Float, val z: Float) {
        fun lerp(o: Vertex, t: Float) = Vertex(
            x + (o.x - x) * t,
            y + (o.y - y) * t,
            z + (o.z - z) * t
        )

        fun distanceTo(o: Vertex): Float {
            val dx = x - o.x
            val dy = y - o.y
            val dz = z - o.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
    }

    class Plane(val nx: Float, val ny: Float, val nz: Float, val w: Float) {
        fun flip() = Plane(-nx, -ny, -nz, -w)

        fun splitPolygon(
            polygon: Polygon,
            coplanarFront: MutableList<Polygon>,
            coplanarBack: MutableList<Polygon>,
            front: MutableList<Polygon>,
            back: MutableList<Polygon>
        ) {
            val coplanar = 0
            val f = 1
            val b = 2
            val spanning = 3
            var polygonType = 0
            val types = IntArray(polygon.vertices.size)
            for (i in polygon.vertices.indices) {
                val t = nx * polygon.vertices[i].x + ny * polygon.vertices[i].y + nz * polygon.vertices[i].z - w
                val type = if (t < -EPSILON) b else if (t > EPSILON) f else coplanar
                polygonType = polygonType or type
                types[i] = type
            }
            when (polygonType) {
                coplanar -> {
                    val dot = nx * polygon.plane.nx + ny * polygon.plane.ny + nz * polygon.plane.nz
                    if (dot > 0f) coplanarFront.add(polygon) else coplanarBack.add(polygon)
                }
                f -> front.add(polygon)
                b -> back.add(polygon)
                else -> {
                    val fv = ArrayList<Vertex>()
                    val bv = ArrayList<Vertex>()
                    val size = polygon.vertices.size
                    for (i in 0 until size) {
                        val j = (i + 1) % size
                        val ti = types[i]
                        val tj = types[j]
                        val vi = polygon.vertices[i]
                        val vj = polygon.vertices[j]
                        if (ti != b) fv.add(vi)
                        if (ti != f) bv.add(vi)
                        if ((ti or tj) == spanning) {
                            val denom = nx * (vj.x - vi.x) + ny * (vj.y - vi.y) + nz * (vj.z - vi.z)
                            // Una arista casi paralela al plano no se puede cortar: cortar por
                            // cero produciría NaN y una malla imposible de usar.
                            if (abs(denom) > 1e-12f) {
                                var t = (w - (nx * vi.x + ny * vi.y + nz * vi.z)) / denom
                                if (t.isNaN() || t.isInfinite()) t = 0f
                                t = t.coerceIn(0f, 1f)
                                val v = vi.lerp(vj, t)
                                fv.add(v)
                                bv.add(v)
                            }
                        }
                    }
                    if (fv.size >= 3) front.add(Polygon(fv, polygon.plane, polygon.material))
                    if (bv.size >= 3) back.add(Polygon(bv, polygon.plane, polygon.material))
                }
            }
        }
    }

    class Polygon(val vertices: List<Vertex>, val plane: Plane, val material: Int = 0) {

        constructor(vertices: List<Vertex>, material: Int = 0) : this(
            vertices,
            planeFrom(vertices),
            material
        )

        fun flipped(): Polygon = Polygon(
            vertices.reversed(),
            Plane(-plane.nx, -plane.ny, -plane.nz, -plane.w),
            material
        )

        companion object {
            fun planeFrom(vertices: List<Vertex>): Plane {
                // Newell: más estable que el producto de dos aristas en polígonos casi planos.
                var nx = 0f
                var ny = 0f
                var nz = 0f
                var cx = 0f
                var cy = 0f
                var cz = 0f
                val n = vertices.size
                for (i in 0 until n) {
                    val a = vertices[i]
                    val b = vertices[(i + 1) % n]
                    nx += (a.y - b.y) * (a.z + b.z)
                    ny += (a.z - b.z) * (a.x + b.x)
                    nz += (a.x - b.x) * (a.y + b.y)
                    cx += a.x
                    cy += a.y
                    cz += a.z
                }
                val len = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-9f)
                nx /= len
                ny /= len
                nz /= len
                val c = n.toFloat()
                return Plane(nx, ny, nz, nx * (cx / c) + ny * (cy / c) + nz * (cz / c))
            }
        }
    }

    private class Node(val polygons: MutableList<Polygon> = ArrayList()) {
        var plane: Plane? = null
        var front: Node? = null
        var back: Node? = null

        fun invert() {
            for (i in polygons.indices) polygons[i] = polygons[i].flipped()
            plane = plane?.flip()
            front?.invert()
            back?.invert()
            val f = front
            front = back
            back = f
        }

        /** Devuelve los polígonos que recorta este árbol y los quita de la lista. */
        fun clipPolygons(input: List<Polygon>): List<Polygon> {
            val p = plane ?: return input.toList()
            var frontList = ArrayList<Polygon>()
            var backList = ArrayList<Polygon>()
            for (polygon in input) {
                p.splitPolygon(polygon, frontList, backList, frontList, backList)
            }
            if (front != null) frontList = ArrayList(front!!.clipPolygons(frontList))
            backList = if (back != null) ArrayList(back!!.clipPolygons(backList)) else ArrayList()
            frontList.addAll(backList)
            return frontList
        }

        fun clipTo(other: Node) {
            // Hay que recortar **antes** de vaciar la lista: `clipPolygons` la lee.
            val clipped = other.clipPolygons(polygons)
            polygons.clear()
            polygons.addAll(clipped)
            front?.clipTo(other)
            back?.clipTo(other)
        }

        fun allPolygons(): List<Polygon> {
            val out = ArrayList<Polygon>(polygons)
            front?.let { out.addAll(it.allPolygons()) }
            back?.let { out.addAll(it.allPolygons()) }
            return out
        }

        fun build(list: List<Polygon>) {
            if (list.isEmpty()) return
            if (plane == null) plane = list[0].plane
            val p = plane!!
            val frontList = ArrayList<Polygon>()
            val backList = ArrayList<Polygon>()
            for (polygon in list) {
                p.splitPolygon(polygon, polygons, polygons, frontList, backList)
            }
            if (frontList.isNotEmpty()) {
                if (front == null) front = Node()
                front!!.build(frontList)
            }
            if (backList.isNotEmpty()) {
                if (back == null) back = Node()
                back!!.build(backList)
            }
        }
    }

    // ------------------------------------------------------------------ API

    enum class BooleanOp(val label: String) {
        UNION("Unión"),
        DIFFERENCE("Diferencia"),
        INTERSECTION("Intersección")
    }

    /** Aplica [op] entre dos mallas editables y devuelve una malla nueva (o lanza con motivo). */
    fun apply(a: EditableMesh, b: EditableMesh, op: BooleanOp): EditableMesh {
        if (a.faces.isEmpty() || b.faces.isEmpty()) {
            throw CsgException("Las dos mallas deben tener geometría para operar")
        }
        if (a.faces.size > MAX_POLYGONS || b.faces.size > MAX_POLYGONS) {
            throw CsgException(
                "Las booleanas admiten hasta $MAX_POLYGONS caras por objeto. " +
                    "Reduce la malla («Reducir polígonos») antes de operar."
            )
        }
        val na = Node().also { it.build(toPolygons(a, 0)) }
        val nb = Node().also { it.build(toPolygons(b, 1)) }
        val result = when (op) {
            BooleanOp.UNION -> {
                na.clipTo(nb)
                nb.clipTo(na)
                nb.invert()
                nb.clipTo(na)
                nb.invert()
                na.build(nb.allPolygons())
                na
            }
            BooleanOp.DIFFERENCE -> {
                na.invert()
                na.clipTo(nb)
                nb.clipTo(na)
                nb.invert()
                nb.clipTo(na)
                nb.invert()
                na.build(nb.allPolygons())
                na.invert()
                na
            }
            BooleanOp.INTERSECTION -> {
                na.invert()
                nb.clipTo(na)
                nb.invert()
                na.clipTo(nb)
                nb.clipTo(na)
                na.build(nb.allPolygons())
                na.invert()
                na
            }
        }
        val polygons = result.allPolygons()
        if (polygons.isEmpty()) {
            throw CsgException(
                "La operación dejó la malla vacía (los objetos probablemente no se tocan)."
            )
        }
        return fromPolygons(polygons)
    }

    fun toPolygons(mesh: EditableMesh, materialOffset: Int): List<Polygon> {
        val out = ArrayList<Polygon>(mesh.faces.size)
        for (fi in mesh.faces.indices) {
            val f = mesh.faces[fi]
            if (f.size < 3) continue
            // Se comprueba que el polígono sea convexo y plano; si no, se parte en triángulos.
            val planar = isPlanar(mesh, fi)
            if (planar && f.size <= 8) {
                val verts = ArrayList<Vertex>(f.size)
                for (v in f) verts.add(Vertex(mesh.x(v), mesh.y(v), mesh.z(v)))
                out.add(Polygon(verts, mesh.faceMaterial.getOrElse(fi) { 0 } + materialOffset))
            } else {
                for (k in 1 until f.size - 1) {
                    val verts = arrayListOf(
                        Vertex(mesh.x(f[0]), mesh.y(f[0]), mesh.z(f[0])),
                        Vertex(mesh.x(f[k]), mesh.y(f[k]), mesh.z(f[k])),
                        Vertex(mesh.x(f[k + 1]), mesh.y(f[k + 1]), mesh.z(f[k + 1]))
                    )
                    out.add(Polygon(verts, mesh.faceMaterial.getOrElse(fi) { 0 } + materialOffset))
                }
            }
        }
        return out
    }

    private fun isPlanar(mesh: EditableMesh, face: Int): Boolean {
        val f = mesh.faces[face]
        if (f.size < 4) return true
        val n = mesh.faceNormal(face)
        val len = sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2])
        if (len < 1e-9f) return false
        val nx = n[0] / len
        val ny = n[1] / len
        val nz = n[2] / len
        val d = nx * mesh.x(f[0]) + ny * mesh.y(f[0]) + nz * mesh.z(f[0])
        for (v in f) {
            val dist = abs(nx * mesh.x(v) + ny * mesh.y(v) + nz * mesh.z(v) - d)
            if (dist > 1e-3f) return false
        }
        return true
    }

    fun fromPolygons(polygons: List<Polygon>): EditableMesh {
        val out = EditableMesh()
        out.name = "Booleana"
        val index = HashMap<Long, Int>()
        fun vertexIndex(v: Vertex): Int {
            val key = (Math.round(v.x * 10000f).toLong() * 73856093L) xor
                (Math.round(v.y * 10000f).toLong() * 19349663L) xor
                (Math.round(v.z * 10000f).toLong() * 83492791L)
            return index.getOrPut(key) { out.addVertex(v.x, v.y, v.z) }
        }
        for (p in polygons) {
            if (p.vertices.size < 3) continue
            if (p.vertices.any { !it.x.isFinite() || !it.y.isFinite() || !it.z.isFinite() }) continue
            val unique = LinkedHashSet<Int>()
            for (v in p.vertices) unique.add(vertexIndex(v))
            if (unique.size < 3) continue
            out.addFace(unique.toIntArray(), p.material.coerceAtLeast(0))
        }
        val cleaned = MeshOps.dropDegenerate(out)
        return cleaned
    }

    /**
     * Atajo para "restar": el caso más usado al modelar (agujeros, ventanas, cortes).
     */
    fun subtract(a: EditableMesh, b: EditableMesh): EditableMesh = apply(a, b, BooleanOp.DIFFERENCE)

    fun union(a: EditableMesh, b: EditableMesh): EditableMesh = apply(a, b, BooleanOp.UNION)

    fun intersect(a: EditableMesh, b: EditableMesh): EditableMesh = apply(a, b, BooleanOp.INTERSECTION)

    /** Utilidades compartidas por las pruebas. */
    fun estimateCost(a: EditableMesh, b: EditableMesh): Long =
        a.faces.size.toLong() * b.faces.size.toLong()

    fun withinBudget(a: EditableMesh, b: EditableMesh): Boolean =
        estimateCost(a, b) <= 40_000_000L && max(a.faces.size, b.faces.size) <= MAX_POLYGONS && min(a.faces.size, b.faces.size) > 0
}
