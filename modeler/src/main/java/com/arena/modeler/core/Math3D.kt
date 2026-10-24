package com.arena.modeler.core

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Vector 3D mutable. */
class V3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
    fun set(x: Float, y: Float, z: Float): V3 {
        this.x = x
        this.y = y
        this.z = z
        return this
    }

    fun set(other: V3): V3 = set(other.x, other.y, other.z)

    fun copy(): V3 = V3(x, y, z)

    fun add(other: V3): V3 = set(x + other.x, y + other.y, z + other.z)

    fun scale(s: Float): V3 = set(x * s, y * s, z * s)

    fun length(): Float = sqrt(x * x + y * y + z * z)

    fun normalized(): V3 {
        val l = length()
        return if (l > 1e-8f) V3(x / l, y / l, z / l) else V3()
    }

    fun cross(other: V3): V3 = V3(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    fun dot(other: V3): Float = x * other.x + y * other.y + z * other.z

    operator fun plus(o: V3): V3 = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3): V3 = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float): V3 = V3(x * s, y * s, z * s)

    override fun toString(): String = "($x, $y, $z)"
}

/** Quaternion for rotations; keeps rotations free of gimbal lock in animations. */
class Quat(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f, var w: Float = 1f) {

    fun copy(): Quat = Quat(x, y, z, w)

    fun normalize(): Quat {
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (n > 1e-8f) {
            x /= n
            y /= n
            z /= n
            w /= n
        } else {
            x = 0f
            y = 0f
            z = 0f
            w = 1f
        }
        return this
    }

    operator fun times(o: Quat): Quat = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z
    )

    /** Writes this rotation into [out] as a column-major 4x4 matrix. */
    fun toMatrix(out: FloatArray) {
        val xx = x * x
        val yy = y * y
        val zz = z * z
        val xy = x * y
        val xz = x * z
        val yz = y * z
        val wx = w * x
        val wy = w * y
        val wz = w * z
        out[0] = 1f - 2f * (yy + zz)
        out[1] = 2f * (xy + wz)
        out[2] = 2f * (xz - wy)
        out[3] = 0f
        out[4] = 2f * (xy - wz)
        out[5] = 1f - 2f * (xx + zz)
        out[6] = 2f * (yz + wx)
        out[7] = 0f
        out[8] = 2f * (xz + wy)
        out[9] = 2f * (yz - wx)
        out[10] = 1f - 2f * (xx + yy)
        out[11] = 0f
        out[12] = 0f
        out[13] = 0f
        out[14] = 0f
        out[15] = 1f
    }

    companion object {
        fun axisAngle(ax: Float, ay: Float, az: Float, degrees: Float): Quat {
            val len = sqrt(ax * ax + ay * ay + az * az)
            if (len < 1e-8f) return Quat()
            val half = Math.toRadians(degrees.toDouble()).toFloat() * 0.5f
            val s = sin(half) / len
            return Quat(ax * s, ay * s, az * s, cos(half)).normalize()
        }

        fun euler(pitchDeg: Float, yawDeg: Float, rollDeg: Float): Quat {
            // Orden YXZ: primero guiñada (Y), luego cabeceo (X), luego alabeo (Z).
            val qy = axisAngle(0f, 1f, 0f, yawDeg)
            val qx = axisAngle(1f, 0f, 0f, pitchDeg)
            val qz = axisAngle(0f, 0f, 1f, rollDeg)
            return (qy * qx * qz).normalize()
        }

        fun slerp(a: Quat, b: Quat, t: Float): Quat {
            var dot = a.x * b.x + a.y * b.y + a.z * b.z + a.w * b.w
            var bx = b.x
            var by = b.y
            var bz = b.z
            var bw = b.w
            if (dot < 0f) {
                dot = -dot
                bx = -bx
                by = -by
                bz = -bz
                bw = -bw
            }
            if (dot > 0.9995f) {
                return Quat(
                    a.x + (bx - a.x) * t,
                    a.y + (by - a.y) * t,
                    a.z + (bz - a.z) * t,
                    a.w + (bw - a.w) * t
                ).normalize()
            }
            val theta0 = acos(dot.toDouble()).toFloat()
            val theta = theta0 * t
            val sinTheta = sin(theta)
            val sinTheta0 = sin(theta0)
            val s0 = cos(theta) - dot * sinTheta / sinTheta0
            val s1 = sinTheta / sinTheta0
            return Quat(
                a.x * s0 + bx * s1,
                a.y * s0 + by * s1,
                a.z * s0 + bz * s1,
                a.w * s0 + bw * s1
            ).normalize()
        }
    }
}

/**
 * Column-major 4x4 matrices, the layout OpenGL expects (and `FloatArray(16)` everywhere), so no
 * transpose dance is needed between the scene graph and the GPU.
 */
object M4 {

    fun identity(out: FloatArray = FloatArray(16)): FloatArray {
        for (i in 0 until 16) out[i] = 0f
        out[0] = 1f
        out[5] = 1f
        out[10] = 1f
        out[15] = 1f
        return out
    }

    /** out = a * b (a applied after b). */
    fun multiply(out: FloatArray, a: FloatArray, b: FloatArray): FloatArray {
        val t = FloatArray(16)
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + r] * b[c * 4 + k]
                t[c * 4 + r] = sum
            }
        }
        System.arraycopy(t, 0, out, 0, 16)
        return out
    }

    fun translation(out: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        identity(out)
        out[12] = x
        out[13] = y
        out[14] = z
        return out
    }

    fun scaling(out: FloatArray, x: Float, y: Float, z: Float): FloatArray {
        identity(out)
        out[0] = x
        out[5] = y
        out[10] = z
        return out
    }

    /** Composes translation * rotation * scale into [out]. */
    fun compose(out: FloatArray, pos: V3, rot: Quat, scale: V3): FloatArray {
        rot.toMatrix(out)
        out[0] *= scale.x
        out[1] *= scale.x
        out[2] *= scale.x
        out[4] *= scale.y
        out[5] *= scale.y
        out[6] *= scale.y
        out[8] *= scale.z
        out[9] *= scale.z
        out[10] *= scale.z
        out[12] = pos.x
        out[13] = pos.y
        out[14] = pos.z
        return out
    }

    fun perspective(out: FloatArray, fovYDegrees: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovYDegrees.toDouble() / 2.0).toFloat())
        for (i in 0 until 16) out[i] = 0f
        out[0] = f / (if (aspect <= 0f) 1f else aspect)
        out[5] = f
        out[10] = (far + near) / (near - far)
        out[11] = -1f
        out[14] = 2f * far * near / (near - far)
        return out
    }

    fun ortho(out: FloatArray, l: Float, r: Float, b: Float, t: Float, n: Float, f: Float): FloatArray {
        for (i in 0 until 16) out[i] = 0f
        out[0] = 2f / (r - l)
        out[5] = 2f / (t - b)
        out[10] = -2f / (f - n)
        out[12] = -(r + l) / (r - l)
        out[13] = -(t + b) / (t - b)
        out[14] = -(f + n) / (f - n)
        out[15] = 1f
        return out
    }

    fun lookAt(out: FloatArray, eye: V3, target: V3, up: V3): FloatArray {
        val f = (target - eye).normalized()
        var s = f.cross(up).normalized()
        if (s.length() < 1e-6f) s = f.cross(V3(1f, 0f, 0f)).normalized()
        val u = s.cross(f)
        for (i in 0 until 16) out[i] = 0f
        out[0] = s.x
        out[4] = s.y
        out[8] = s.z
        out[1] = u.x
        out[5] = u.y
        out[9] = u.z
        out[2] = -f.x
        out[6] = -f.y
        out[10] = -f.z
        out[12] = -s.dot(eye)
        out[13] = -u.dot(eye)
        out[14] = f.dot(eye)
        out[15] = 1f
        return out
    }

    fun invert(out: FloatArray, m: FloatArray): Boolean {
        val a00 = m[0]; val a01 = m[1]; val a02 = m[2]; val a03 = m[3]
        val a10 = m[4]; val a11 = m[5]; val a12 = m[6]; val a13 = m[7]
        val a20 = m[8]; val a21 = m[9]; val a22 = m[10]; val a23 = m[11]
        val a30 = m[12]; val a31 = m[13]; val a32 = m[14]; val a33 = m[15]
        val b00 = a00 * a11 - a01 * a10
        val b01 = a00 * a12 - a02 * a10
        val b02 = a00 * a13 - a03 * a10
        val b03 = a01 * a12 - a02 * a11
        val b04 = a01 * a13 - a03 * a11
        val b05 = a02 * a13 - a03 * a12
        val b06 = a20 * a31 - a21 * a30
        val b07 = a20 * a32 - a22 * a30
        val b08 = a20 * a33 - a23 * a30
        val b09 = a21 * a32 - a22 * a31
        val b10 = a21 * a33 - a23 * a31
        val b11 = a22 * a33 - a23 * a32
        var det = b00 * b11 - b01 * b10 + b02 * b09 + b03 * b08 - b04 * b07 + b05 * b06
        if (abs(det) < 1e-12f) return false
        det = 1f / det
        out[0] = (a11 * b11 - a12 * b10 + a13 * b09) * det
        out[1] = (a02 * b10 - a01 * b11 - a03 * b09) * det
        out[2] = (a31 * b05 - a32 * b04 + a33 * b03) * det
        out[3] = (a22 * b04 - a21 * b05 - a23 * b03) * det
        out[4] = (a12 * b08 - a10 * b11 - a13 * b07) * det
        out[5] = (a00 * b11 - a02 * b08 + a03 * b07) * det
        out[6] = (a32 * b02 - a30 * b05 - a33 * b01) * det
        out[7] = (a20 * b05 - a22 * b02 + a23 * b01) * det
        out[8] = (a10 * b10 - a11 * b08 + a13 * b06) * det
        out[9] = (a01 * b08 - a00 * b10 - a03 * b06) * det
        out[10] = (a30 * b04 - a31 * b02 + a33 * b00) * det
        out[11] = (a21 * b02 - a20 * b04 - a23 * b00) * det
        out[12] = (a11 * b07 - a10 * b09 - a12 * b06) * det
        out[13] = (a00 * b09 - a01 * b07 + a02 * b06) * det
        out[14] = (a31 * b01 - a30 * b03 - a32 * b00) * det
        out[15] = (a20 * b03 - a21 * b01 + a22 * b00) * det
        return true
    }

    /** Upper-left 3x3 inverse-transpose, for normals under non-uniform scaling. */
    fun normalMatrix(out: FloatArray, model: FloatArray) {
        val inv = FloatArray(16)
        if (!invert(inv, model)) {
            identity(out)
            return
        }
        // Transpuesta de la inversa, tomando el bloque 3x3.
        out[0] = inv[0]; out[1] = inv[4]; out[2] = inv[8]
        out[3] = inv[1]; out[4] = inv[5]; out[5] = inv[9]
        out[6] = inv[2]; out[7] = inv[6]; out[8] = inv[10]
    }

    fun transformPoint(m: FloatArray, x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z + m[12],
        m[1] * x + m[5] * y + m[9] * z + m[13],
        m[2] * x + m[6] * y + m[10] * z + m[14]
    )

    fun transformDir(m: FloatArray, x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        m[0] * x + m[4] * y + m[8] * z,
        m[1] * x + m[5] * y + m[9] * z,
        m[2] * x + m[6] * y + m[10] * z
    )
}

/** Small geometric helpers used by the primitive builder and the editor. */
object Geom {
    fun easeInOut(t: Float): Float {
        val c = t.coerceIn(0f, 1f)
        return c * c * (3f - 2f * c)
    }

    /** Ray/triangle intersection (Möller–Trumbore). Returns t >= 0 or -1. */
    fun rayTriangle(
        ox: Float, oy: Float, oz: Float,
        dx: Float, dy: Float, dz: Float,
        ax: Float, ay: Float, az: Float,
        bx: Float, by: Float, bz: Float,
        cx: Float, cy: Float, cz: Float
    ): Float {
        val e1x = bx - ax; val e1y = by - ay; val e1z = bz - az
        val e2x = cx - ax; val e2y = cy - ay; val e2z = cz - az
        val px = dy * e2z - dz * e2y
        val py = dz * e2x - dx * e2z
        val pz = dx * e2y - dy * e2x
        val det = e1x * px + e1y * py + e1z * pz
        if (abs(det) < 1e-8f) return -1f
        val inv = 1f / det
        val tx = ox - ax; val ty = oy - ay; val tz = oz - az
        val u = (tx * px + ty * py + tz * pz) * inv
        if (u < 0f || u > 1f) return -1f
        val qx = ty * e1z - tz * e1y
        val qy = tz * e1x - tx * e1z
        val qz = tx * e1y - ty * e1x
        val v = (dx * qx + dy * qy + dz * qz) * inv
        if (v < 0f || u + v > 1f) return -1f
        val t = (e2x * qx + e2y * qy + e2z * qz) * inv
        return if (t > 1e-5f) t else -1f
    }

    fun angleTo(ax: Float, ay: Float, az: Float): Float = atan2(ax, az) * 180f / Math.PI.toFloat()

    fun degreesToRadians(d: Float): Float = d * Math.PI.toFloat() / 180f
}
