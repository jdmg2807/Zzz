package com.arena.modeler.render

import com.arena.modeler.core.M4
import com.arena.modeler.core.V3
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Turntable camera: yaw/pitch around a target, the same interaction Blender has with the middle
 * mouse, but with two fingers on a phone. Distances are in metres because the scene is.
 */
class OrbitCamera {

    var yaw = 38f
    var pitch = 22f
    var distance = 4.2f
    var fovY = 45f
    val target = V3(0f, 0.9f, 0f)

    private val viewMatrix = FloatArray(16)
    private val projMatrix = FloatArray(16)
    private val vpMatrix = FloatArray(16)

    fun eye(): V3 {
        val yawRad = Math.toRadians(yaw.toDouble())
        val pitchRad = Math.toRadians(pitch.toDouble())
        val cp = cos(pitchRad).toFloat()
        return V3(
            target.x + distance * cp * sin(yawRad).toFloat(),
            target.y + distance * sin(pitchRad).toFloat(),
            target.z + distance * cp * cos(yawRad).toFloat()
        )
    }

    fun view(): FloatArray = M4.lookAt(viewMatrix, eye(), target, V3(0f, 1f, 0f))

    fun projection(aspect: Float): FloatArray =
        M4.perspective(projMatrix, fovY, if (aspect <= 0f) 1f else aspect, 0.03f, 300f)

    fun viewProj(aspect: Float): FloatArray {
        val v = view()
        val p = projection(aspect)
        return M4.multiply(vpMatrix, p, v)
    }

    fun orbit(dxPixels: Float, dyPixels: Float) {
        yaw -= dxPixels * 0.32f
        pitch = (pitch + dyPixels * 0.28f).coerceIn(-89f, 89f)
        if (yaw > 360f) yaw -= 360f
        if (yaw < -360f) yaw += 360f
    }

    /** Moves the target in the camera plane; [height] is the viewport height in pixels. */
    fun pan(dxPixels: Float, dyPixels: Float, height: Int) {
        val v = view()
        val right = V3(v[0], v[4], v[8]).normalized()
        val up = V3(v[1], v[5], v[9]).normalized()
        val scale = 2f * distance * tan(Math.toRadians(fovY.toDouble() * 0.5).toFloat()) /
            height.coerceAtLeast(1)
        target.set(
            target.x - right.x * dxPixels * scale + up.x * dyPixels * scale,
            target.y - right.y * dxPixels * scale + up.y * dyPixels * scale,
            target.z - right.z * dxPixels * scale + up.z * dyPixels * scale
        )
    }

    fun zoom(factor: Float) {
        distance = (distance * factor).coerceIn(0.25f, 120f)
    }

    fun dolly(deltaPixels: Float) {
        zoom(1f + deltaPixels * 0.0025f)
    }

    /** Points the camera at a bounding box [minX,minY,minZ,maxX,maxY,maxZ]. */
    fun frame(bounds: FloatArray, heightWidthRatio: Float = 1f) {
        val cx = (bounds[0] + bounds[3]) * 0.5f
        val cy = (bounds[1] + bounds[4]) * 0.5f
        val cz = (bounds[2] + bounds[5]) * 0.5f
        val dx = bounds[3] - bounds[0]
        val dy = bounds[4] - bounds[1]
        val dz = bounds[5] - bounds[2]
        val radius = sqrt(dx * dx + dy * dy + dz * dz) * 0.5f
        target.set(cx, cy, cz)
        distance = (radius * 2.4f / heightWidthRatio.coerceAtLeast(0.4f)).coerceIn(0.5f, 80f)
    }

    fun setFront() { yaw = 0f; pitch = 0f }
    fun setSide() { yaw = 90f; pitch = 0f }
    fun setTop() { yaw = 0f; pitch = 88f }

    /**
     * Builds a picking ray from normalised device coordinates (-1..1, +Y up).
     * [origin] receives the near-plane point and [dir] the normalised direction.
     */
    fun ray(ndcX: Float, ndcY: Float, aspect: Float, origin: V3, dir: V3) {
        val inv = FloatArray(16)
        if (!M4.invert(inv, viewProj(aspect))) return
        fun unproject(z: Float, out: FloatArray) {
            out[0] = inv[0] * ndcX + inv[4] * ndcY + inv[8] * z + inv[12]
            out[1] = inv[1] * ndcX + inv[5] * ndcY + inv[9] * z + inv[13]
            out[2] = inv[2] * ndcX + inv[6] * ndcY + inv[10] * z + inv[14]
            out[3] = inv[3] * ndcX + inv[7] * ndcY + inv[11] * z + inv[15]
            val w = if (kotlin.math.abs(out[3]) < 1e-8f) 1f else out[3]
            out[0] /= w
            out[1] /= w
            out[2] /= w
        }
        val near = FloatArray(4)
        val far = FloatArray(4)
        unproject(-1f, near)
        unproject(1f, far)
        origin.set(near[0], near[1], near[2])
        val dx = far[0] - near[0]
        val dy = far[1] - near[1]
        val dz = far[2] - near[2]
        val len = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
        dir.set(dx / len, dy / len, dz / len)
    }
}
