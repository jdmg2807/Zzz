package com.arena.modeler.geom

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Curvas y barridos. Una curva es una lista de puntos de control; se evalúa en la CPU (son pocos
 * puntos) y se convierte en **geometría real**: un tubo o un perfil extruido a lo largo de la
 * trayectoria, que es lo que acaba en la escena y en el archivo exportado.
 */
object Curves {

    enum class Kind(val label: String) {
        BEZIER("Bézier"),
        CATMULL_ROM("Catmull-Rom"),
        POLYLINE("Polilínea")
    }

    /** Evalúa la curva y devuelve [samples] puntos. */
    fun evaluate(points: List<FloatArray>, kind: Kind, samples: Int = 32): List<FloatArray> {
        val n = samples.coerceIn(2, 512)
        if (points.size < 2) throw IllegalArgumentException("La curva necesita al menos 2 puntos")
        return when (kind) {
            Kind.POLYLINE -> {
                val out = ArrayList<FloatArray>(n)
                val segments = points.size - 1
                for (i in 0 until n) {
                    val t = i.toFloat() / (n - 1) * segments
                    val idx = t.toInt().coerceIn(0, segments - 1)
                    val f = t - idx
                    out.add(lerp(points[idx], points[idx + 1], f))
                }
                out
            }
            Kind.BEZIER -> {
                val out = ArrayList<FloatArray>(n)
                for (i in 0 until n) {
                    val t = i.toFloat() / (n - 1)
                    out.add(deCasteljau(points, t))
                }
                out
            }
            Kind.CATMULL_ROM -> {
                val out = ArrayList<FloatArray>(n)
                val pts = ArrayList<FloatArray>()
                pts.add(points[0])
                pts.addAll(points)
                pts.add(points[points.size - 1])
                val segments = pts.size - 3
                for (i in 0 until n) {
                    val t = i.toFloat() / (n - 1) * segments
                    val seg = t.toInt().coerceIn(0, segments - 1)
                    val f = t - seg
                    out.add(catmullRom(pts[seg], pts[seg + 1], pts[seg + 2], pts[seg + 3], f))
                }
                out
            }
        }
    }

    private fun lerp(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(
        a[0] + (b[0] - a[0]) * t,
        a[1] + (b[1] - a[1]) * t,
        a[2] + (b[2] - a[2]) * t
    )

    private fun deCasteljau(points: List<FloatArray>, t: Float): FloatArray {
        var current = points.map { it.copyOf() }
        while (current.size > 1) {
            val next = ArrayList<FloatArray>(current.size - 1)
            for (i in 0 until current.size - 1) next.add(lerp(current[i], current[i + 1], t))
            current = next
        }
        return current[0]
    }

    private fun catmullRom(p0: FloatArray, p1: FloatArray, p2: FloatArray, p3: FloatArray, t: Float): FloatArray {
        val t2 = t * t
        val t3 = t2 * t
        fun c(a: Float, b: Float, c: Float, d: Float): Float =
            0.5f * ((2f * b) + (-a + c) * t + (2f * a - 5f * b + 4f * c - d) * t2 + (-a + 3f * b - 3f * c + d) * t3)
        return floatArrayOf(
            c(p0[0], p1[0], p2[0], p3[0]),
            c(p0[1], p1[1], p2[1], p3[1]),
            c(p0[2], p1[2], p2[2], p3[2])
        )
    }

    /** Tubo de [sides] lados y radio [radius] siguiendo la curva (geometría real, cerrada). */
    fun tube(
        points: List<FloatArray>,
        kind: Kind = Kind.CATMULL_ROM,
        radius: Float = 0.06f,
        sides: Int = 8,
        samples: Int = 32
    ): EditableMesh {
        if (radius <= 0f) throw IllegalArgumentException("El radio del tubo debe ser positivo")
        val path = evaluate(points, kind, samples)
        return MeshOps.sweep(circle(sides.coerceIn(3, 32), radius), path, caps = true)
    }

    /** Perfil circular cerrado en el plano local (X/Y) del barrido. */
    fun circle(sides: Int, radius: Float): List<FloatArray> {
        val out = ArrayList<FloatArray>(sides)
        for (i in 0 until sides) {
            val a = i.toFloat() / sides * Math.PI.toFloat() * 2f
            out.add(floatArrayOf(cos(a) * radius, sin(a) * radius, 0f))
        }
        return out
    }

    /** Perfil cuadrado cerrado (para vigas y marcos). */
    fun square(size: Float): List<FloatArray> {
        val h = size * 0.5f
        return listOf(
            floatArrayOf(-h, -h, 0f),
            floatArrayOf(h, -h, 0f),
            floatArrayOf(h, h, 0f),
            floatArrayOf(-h, h, 0f)
        )
    }

    /** Línea (polilínea) como malla fina, útil para trayectorias visibles. */
    fun polylineMesh(points: List<FloatArray>, radius: Float = 0.01f): EditableMesh =
        tube(points, Kind.POLYLINE, radius, 6, (points.size - 1).coerceAtLeast(1) * 6)

    /** Distancia total de la curva (para mostrar longitud en la UI). */
    fun length(points: List<FloatArray>, kind: Kind, samples: Int = 64): Float {
        val pts = evaluate(points, kind, samples)
        var total = 0f
        for (i in 1 until pts.size) {
            val dx = pts[i][0] - pts[i - 1][0]
            val dy = pts[i][1] - pts[i - 1][1]
            val dz = pts[i][2] - pts[i - 1][2]
            total += sqrt(dx * dx + dy * dy + dz * dz)
        }
        return total
    }
}
