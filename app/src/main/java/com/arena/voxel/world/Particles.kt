package com.arena.voxel.world

import kotlin.math.floor
import kotlin.math.sqrt

/**
 * CPU particle system for the effects the game needs on touch screens: block-break dust,
 * landing puffs and mob damage sparks.
 *
 * Java uses 16x16 textured quads; here the particles are point sprites tinted with the block
 * colour, which costs one draw call for the whole batch and looks close enough at 8 px GUs.
 */
object Particles {

    class Particle(
        var x: Float, var y: Float, var z: Float,
        var vx: Float, var vy: Float, var vz: Float,
        val r: Float, val g: Float, val b: Float, val a: Float,
        var life: Float,
        val size: Float,
        /** 0 = block dust (collides), 1 = spark (falls through everything). */
        val kind: Int = 0
    )

    private val list = ArrayList<Particle>(256)
    private const val MAX = 420

    fun all(): List<Particle> = list

    fun clear() = list.clear()

    /** Nothing at all to draw? The renderer skips the upload. */
    val isEmpty: Boolean get() = list.isEmpty()

    /** Adds one particle (public so mob explosions can re-use the same pool). */
    fun push(p: Particle) {
        if (list.size >= MAX) list.removeAt(0)
        list.add(p)
    }

    /** 8 dust particles in the block's colour, like Java's `addBlockBreakParticles`. */
    fun blockBreak(world: World, x: Int, y: Int, z: Int, blockId: String) {
        val color = Blocks.get(Blocks.index(blockId)).color
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        for (i in 0 until 8) {
            val vx = (Math.random() - 0.5) * 0.22
            val vy = Math.random() * 0.2 + 0.08
            val vz = (Math.random() - 0.5) * 0.22
            push(
                Particle(
                    (x + Math.random()).toFloat(),
                    (y + Math.random()).toFloat(),
                    (z + Math.random()).toFloat(),
                    vx.toFloat(), vy.toFloat(), vz.toFloat(),
                    (r * (0.8f + Math.random().toFloat() * 0.4f)).coerceIn(0f, 1f),
                    (g * (0.8f + Math.random().toFloat() * 0.4f)).coerceIn(0f, 1f),
                    (b * (0.8f + Math.random().toFloat() * 0.4f)).coerceIn(0f, 1f),
                    1f, 0.7f, 6f, 0
                )
            )
        }
    }

    /** Dust when a block is being mined but not yet broken. */
    fun mining(world: World, x: Int, y: Int, z: Int, blockId: String) {
        if (list.size > MAX - 16) return
        val color = Blocks.get(Blocks.index(blockId)).color
        val r = ((color shr 16) and 0xFF) / 255f
        val g = ((color shr 8) and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        push(
            Particle(
                (x + Math.random()).toFloat(),
                (y + Math.random()).toFloat() + 0.2f,
                (z + Math.random()).toFloat(),
                ((Math.random() - 0.5) * 0.06).toFloat(),
                0.05f,
                ((Math.random() - 0.5) * 0.06).toFloat(),
                r, g, b, 1f, 0.45f, 4f, 0
            )
        )
    }

    /** White puff on a hard landing (scaled with the fall distance). */
    fun landing(x: Double, y: Double, z: Double, strength: Int) {
        for (i in 0 until strength.coerceIn(1, 10)) {
            push(
                Particle(
                    (x + (Math.random() - 0.5) * 0.6).toFloat(),
                    y.toFloat() + 0.05f,
                    (z + (Math.random() - 0.5) * 0.6).toFloat(),
                    ((Math.random() - 0.5) * 0.15).toFloat(),
                    (Math.random() * 0.05).toFloat(),
                    ((Math.random() - 0.5) * 0.15).toFloat(),
                    0.85f, 0.85f, 0.85f, 1f, 0.5f, 5f, 1
                )
            )
        }
    }

    /** Red sparks when an entity takes damage (`entity_attack` particles in Java). */
    fun hurt(x: Double, y: Double, z: Double) {
        for (i in 0 until 6) {
            push(
                Particle(
                    (x + (Math.random() - 0.5) * 0.5).toFloat(),
                    y.toFloat() + 0.6f + Math.random().toFloat() * 0.6f,
                    (z + (Math.random() - 0.5) * 0.5).toFloat(),
                    ((Math.random() - 0.5) * 0.2).toFloat(),
                    (Math.random() * 0.15).toFloat(),
                    ((Math.random() - 0.5) * 0.2).toFloat(),
                    0.85f, 0.1f, 0.1f, 1f, 0.4f, 5f, 1
                )
            )
        }
    }

    fun update(dt: Float, world: World) {
        if (list.isEmpty()) return
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val p = iterator.next()
            p.life -= dt
            if (p.life <= 0f) {
                iterator.remove()
                continue
            }
            if (p.kind == 0) p.vy -= 12f * dt else p.vy -= 3f * dt
            val nx = p.x + p.vx * dt
            val ny = p.y + p.vy * dt
            val nz = p.z + p.vz * dt
            val bx = floor(nx).toInt()
            val by = floor(ny).toInt()
            val bz = floor(nz).toInt()
            if (p.kind == 0 && world.isSolid(bx, by, bz)) {
                // Rest on top of whatever we hit and stop moving.
                p.y = by + 1.01f
                p.vx = 0f
                p.vz = 0f
                p.vy = 0f
                continue
            }
            p.x = nx
            p.y = ny
            p.z = nz
        }
    }

    /** Cheap squared-distance cull so the renderer never uploads the whole world. */
    fun near(px: Float, pz: Float, radius: Float): List<Particle> {
        if (list.isEmpty()) return emptyList()
        val out = ArrayList<Particle>(list.size)
        val r2 = radius * radius
        for (p in list) {
            val dx = p.x - px
            val dz = p.z - pz
            if (dx * dx + dz * dz <= r2) out.add(p)
        }
        return out
    }

    fun magnitude(x: Float, y: Float, z: Float): Float = sqrt(x * x + y * y + z * z)
}
