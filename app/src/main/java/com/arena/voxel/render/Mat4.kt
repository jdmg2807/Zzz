package com.arena.voxel.render

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Minimal column-major 4x4 matrix helpers (same layout as OpenGL). */
object Mat4 {

    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f
    )

    fun perspective(fovYDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = 1f / tan(Math.toRadians(fovYDeg / 2.0)).toFloat()
        val m = FloatArray(16)
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = (2f * far * near) / (near - far)
        return m
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0 until 4) {
            for (r in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + r] * b[c * 4 + k]
                out[c * 4 + r] = sum
            }
        }
        return out
    }

    fun translation(x: Float, y: Float, z: Float): FloatArray {
        val m = identity()
        m[12] = x
        m[13] = y
        m[14] = z
        return m
    }

    fun scale(x: Float, y: Float, z: Float): FloatArray {
        val m = identity()
        m[0] = x
        m[5] = y
        m[10] = z
        return m
    }

    fun rotationX(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val m = identity()
        m[5] = c; m[6] = s
        m[9] = -s; m[10] = c
        return m
    }

    fun rotationY(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val m = identity()
        m[0] = c; m[2] = -s
        m[8] = s; m[10] = c
        return m
    }

    fun rotationZ(deg: Float): FloatArray {
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        val m = identity()
        m[0] = c; m[1] = s
        m[4] = -s; m[5] = c
        return m
    }

    fun view(eyeX: Float, eyeY: Float, eyeZ: Float, yawDeg: Float, pitchDeg: Float): FloatArray {
        val yaw = Math.toRadians(yawDeg.toDouble())
        val pitch = Math.toRadians(pitchDeg.toDouble())
        val cp = cos(pitch).toFloat()
        val sp = sin(pitch).toFloat()
        val cy = cos(yaw).toFloat()
        val sy = sin(yaw).toFloat()

        // forward
        val fx = -sy * cp
        val fy = sp
        val fz = -cy * cp
        // right
        val rx = cy
        val ry = 0f
        val rz = -sy
        // up = right x forward
        val ux = ry * fz - rz * fy
        val uy = rz * fx - rx * fz
        val uz = rx * fy - ry * fx

        val m = FloatArray(16)
        m[0] = rx; m[1] = ux; m[2] = -fx; m[3] = 0f
        m[4] = ry; m[5] = uy; m[6] = -fy; m[7] = 0f
        m[8] = rz; m[9] = uz; m[10] = -fz; m[11] = 0f
        m[12] = -(rx * eyeX + ry * eyeY + rz * eyeZ)
        m[13] = -(ux * eyeX + uy * eyeY + uz * eyeZ)
        m[14] = fx * eyeX + fy * eyeY + fz * eyeZ
        m[15] = 1f
        return m
    }

    fun invert(m: FloatArray): FloatArray? {
        val inv = FloatArray(16)
        inv[0] = m[5] * m[10] * m[15] - m[5] * m[11] * m[14] - m[9] * m[6] * m[15] +
            m[9] * m[7] * m[14] + m[13] * m[6] * m[11] - m[13] * m[7] * m[10]
        inv[4] = -m[4] * m[10] * m[15] + m[4] * m[11] * m[14] + m[8] * m[6] * m[15] -
            m[8] * m[7] * m[14] - m[12] * m[6] * m[11] + m[12] * m[7] * m[10]
        inv[8] = m[4] * m[9] * m[15] - m[4] * m[11] * m[13] - m[8] * m[5] * m[15] +
            m[8] * m[7] * m[13] + m[12] * m[5] * m[11] - m[12] * m[7] * m[9]
        inv[12] = -m[4] * m[9] * m[14] + m[4] * m[10] * m[13] + m[8] * m[5] * m[14] -
            m[8] * m[6] * m[13] - m[12] * m[5] * m[10] + m[12] * m[6] * m[9]
        inv[1] = -m[1] * m[10] * m[15] + m[1] * m[11] * m[14] + m[9] * m[2] * m[15] -
            m[9] * m[3] * m[14] - m[13] * m[2] * m[11] + m[13] * m[3] * m[10]
        inv[5] = m[0] * m[10] * m[15] - m[0] * m[11] * m[14] - m[8] * m[2] * m[15] +
            m[8] * m[3] * m[14] + m[12] * m[2] * m[11] - m[12] * m[3] * m[10]
        inv[9] = -m[0] * m[9] * m[15] + m[0] * m[11] * m[13] + m[8] * m[1] * m[15] -
            m[8] * m[3] * m[13] - m[12] * m[1] * m[11] + m[12] * m[3] * m[9]
        inv[13] = m[0] * m[9] * m[14] - m[0] * m[10] * m[13] - m[8] * m[1] * m[14] +
            m[8] * m[2] * m[13] + m[12] * m[1] * m[10] - m[12] * m[2] * m[9]
        inv[2] = m[1] * m[6] * m[15] - m[1] * m[7] * m[14] - m[5] * m[2] * m[15] +
            m[5] * m[3] * m[14] + m[13] * m[2] * m[7] - m[13] * m[3] * m[6]
        inv[6] = -m[0] * m[6] * m[15] + m[0] * m[7] * m[14] + m[4] * m[2] * m[15] -
            m[4] * m[3] * m[14] - m[12] * m[2] * m[7] + m[12] * m[3] * m[6]
        inv[10] = m[0] * m[5] * m[15] - m[0] * m[7] * m[13] - m[4] * m[1] * m[15] +
            m[4] * m[3] * m[13] + m[12] * m[1] * m[7] - m[12] * m[3] * m[5]
        inv[14] = -m[0] * m[5] * m[14] + m[0] * m[6] * m[13] + m[4] * m[1] * m[14] -
            m[4] * m[2] * m[13] - m[12] * m[1] * m[6] + m[12] * m[2] * m[5]
        inv[3] = -m[1] * m[6] * m[11] + m[1] * m[7] * m[10] + m[5] * m[2] * m[11] -
            m[5] * m[3] * m[10] - m[9] * m[2] * m[7] + m[9] * m[3] * m[6]
        inv[7] = m[0] * m[6] * m[11] - m[0] * m[7] * m[10] - m[4] * m[2] * m[11] +
            m[4] * m[3] * m[10] + m[8] * m[2] * m[7] - m[8] * m[3] * m[6]
        inv[11] = -m[0] * m[5] * m[11] + m[0] * m[7] * m[9] + m[4] * m[1] * m[11] -
            m[4] * m[3] * m[9] - m[8] * m[1] * m[7] + m[8] * m[3] * m[5]
        inv[15] = m[0] * m[5] * m[10] - m[0] * m[6] * m[9] - m[4] * m[1] * m[10] +
            m[4] * m[2] * m[9] + m[8] * m[1] * m[6] - m[8] * m[2] * m[5]

        val det = m[0] * inv[0] + m[1] * inv[4] + m[2] * inv[8] + m[3] * inv[12]
        if (kotlin.math.abs(det) < 1e-8f) return null
        val invDet = 1f / det
        for (i in 0 until 16) inv[i] *= invDet
        return inv
    }
}
