package com.arena.voxel.player

import com.arena.voxel.world.World
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/** First person player: AABB collision, gravity, swimming and creative flight. */
class Player(var x: Double = 0.5, var y: Double = 70.0, var z: Double = 0.5) {

    companion object {
        const val WIDTH = 0.62
        const val HEIGHT = 1.8
        const val EYE = 1.62
    }

    var yaw = 0f
    var pitch = 0f
    var vy = 0.0
    var flying = false
    var onGround = false
    var inWater = false
    var hudText = ""

    fun eyeY(): Double = y + EYE

    fun forward(): FloatArray {
        val cp = cos(Math.toRadians(pitch.toDouble())).toFloat()
        val sp = sin(Math.toRadians(pitch.toDouble())).toFloat()
        val cy = cos(Math.toRadians(yaw.toDouble())).toFloat()
        val sy = sin(Math.toRadians(yaw.toDouble())).toFloat()
        return floatArrayOf(-sy * cp, sp, -cy * cp)
    }

    private fun collides(world: World, px: Double, py: Double, pz: Double): Boolean {
        val hw = WIDTH / 2.0
        val minX = floor(px - hw + 0.001).toInt()
        val maxX = floor(px + hw - 0.001).toInt()
        val minY = floor(py + 0.001).toInt()
        val maxY = floor(py + HEIGHT - 0.001).toInt()
        val minZ = floor(pz - hw + 0.001).toInt()
        val maxZ = floor(pz + hw - 0.001).toInt()
        for (bx in minX..maxX) {
            for (by in minY..maxY) {
                for (bz in minZ..maxZ) {
                    if (world.isSolid(bx, by, bz)) return true
                }
            }
        }
        return false
    }

    /**
     * @param moveX strafe -1..1 (right positive)
     * @param moveZ forward -1..1
     * @param ascend vertical intent -1..1 (used while flying)
     * @param jump jump/ascend button held
     */
    fun update(
        dt: Float, world: World, moveX: Float, moveZ: Float, ascend: Float, jump: Boolean, sprint: Boolean = false
    ) {
        val step = dt.coerceAtMost(0.05f)
        inWater = world.isLiquid(
            floor(x).toInt(),
            floor(y + 0.4).toInt(),
            floor(z).toInt()
        )

        val speed = when {
            flying -> if (sprint) 22.0 else 11.0
            inWater -> 3.4
            sprint -> 6.1
            else -> 4.7
        }

        val rad = Math.toRadians(yaw.toDouble())
        val fx = -sin(rad)
        val fz = -cos(rad)
        val rx = cos(rad)
        val rz = -sin(rad)
        var dx = fx * moveZ + rx * moveX
        var dz = fz * moveZ + rz * moveX
        val len = kotlin.math.hypot(dx, dz)
        if (len > 1.0) {
            dx /= len
            dz /= len
        }

        val vx = dx * speed * step
        val vz = dz * speed * step

        if (vx != 0.0) {
            val nx = x + vx
            if (!collides(world, nx, y, z)) x = nx
        }
        if (vz != 0.0) {
            val nz = z + vz
            if (!collides(world, x, y, nz)) z = nz
        }

        if (flying) {
            vy = ascend * 9.0
            if (jump) vy = 9.0
            if (vy != 0.0) {
                val ny = y + vy * step
                if (!collides(world, x, ny, z)) y = ny else vy = 0.0
            }
            onGround = false
        } else {
            if (inWater) {
                vy = max(vy - 9.0 * step, -3.0)
                if (jump) vy = 4.6
            } else {
                vy -= 27.0 * step
                if (jump && onGround) vy = 8.6
            }
            if (vy < -55.0) vy = -55.0
            val ny = y + vy * step
            if (!collides(world, x, ny, z)) {
                y = ny
                onGround = false
            } else {
                if (vy < 0.0) onGround = true
                vy = 0.0
            }
        }

        if (y < -20.0) {
            y = 80.0
            vy = 0.0
        }
    }
}
