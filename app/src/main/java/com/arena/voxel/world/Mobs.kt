package com.arena.voxel.world

import java.util.Random

/** A passive animal wandering on the surface. Coordinates are in blocks. */
class Mob(
    val type: Int,
    var x: Double,
    var y: Double,
    var z: Double,
    var yaw: Float,
    var vy: Double = 0.0,
    var moving: Boolean = false,
    var timer: Float = 0f,
    var walk: Float = 0f
) {
    companion object {
        const val PIG = 0
        const val SHEEP = 1
        const val COW = 2
        const val CHICKEN = 3
    }

    fun update(dt: Float, world: World) {
        timer -= dt
        if (timer <= 0f) {
            timer = 2.5f + Random().nextFloat() * 4.5f
            moving = Random().nextFloat() > 0.35f
            if (moving) yaw = Random().nextFloat() * 360f
        }
        val speed = when (type) {
            CHICKEN -> 1.5
            PIG -> 1.15
            SHEEP -> 1.0
            else -> 0.95
        }
        if (moving) {
            walk += dt * 7f
            val rad = Math.toRadians(yaw.toDouble())
            val dx = -Math.sin(rad) * speed * dt
            val dz = -Math.cos(rad) * speed * dt
            val nx = x + dx
            val nz = z + dz
            if (!blocked(world, nx, y, z)) {
                x = nx
                z = nz
            } else if (!blocked(world, nx, y + 1.0, nz) && !blocked(world, nx, y + 2.0, nz)) {
                x = nx
                z = nz
                y += 1.0
            } else {
                yaw += 180f
                moving = false
            }
        }

        // Gravity / step down.
        val below = world.isSolid(floorInt(x), floorInt(y - 0.1), floorInt(z))
        if (!below) {
            vy -= 22.0 * dt
            if (vy < -30.0) vy = -30.0
            val ny = y + vy * dt
            if (!blocked(world, x, ny, z)) {
                y = ny
            } else {
                vy = 0.0
            }
        } else if (vy < 0.0) {
            vy = 0.0
        } else {
            vy = 0.0
        }
        if (y < -5) y = 90.0
    }

    private fun blocked(world: World, px: Double, py: Double, pz: Double): Boolean {
        val x0 = floorInt(px - 0.3)
        val x1 = floorInt(px + 0.3)
        val z0 = floorInt(pz - 0.3)
        val z1 = floorInt(pz + 0.3)
        val y0 = floorInt(py)
        val y1 = floorInt(py + 1.1)
        for (bx in x0..x1) for (by in y0..y1) for (bz in z0..z1) {
            if (world.isSolid(bx, by, bz)) return true
        }
        return false
    }

    private fun floorInt(v: Double): Int = kotlin.math.floor(v).toInt()
}

/**
 * Animal types: palette, box model and spawning.
 * Models are described in 1/16 of a block, with the origin between the feet.
 */
object Mobs {

    class Part(
        val x0: Float, val y0: Float, val z0: Float,
        val x1: Float, val y1: Float, val z1: Float,
        val color: Int, val shade: Float = 1f
    )

    class TypeInfo(
        val name: String,
        val colors: IntArray,
        val parts: List<Part>,
        val eyeHeight: Float
    )

    private const val PINK = 0xF0A5A2
    private const val PINK_DARK = 0xD98C8A
    private const val WOOL = 0xEDEDED
    private const val WOOL_DARK = 0xCFCFCF
    private const val BOVINE = 0x5A4632
    private const val BOVINE_DARK = 0x3E2E22
    private const val BEAK = 0xF2C12E
    private const val FEATHER = 0xF7F7F7

    val TYPES: List<TypeInfo> = listOf(
        // Pig
        TypeInfo(
            "pig",
            intArrayOf(PINK, PINK_DARK),
            listOf(
                Part(-5f, 6f, -8f, 5f, 14f, 8f, PINK),          // body
                Part(-4f, 9f, -14f, 4f, 16f, -8f, PINK),        // head
                Part(-2.5f, 9f, -16f, 2.5f, 12.5f, -14f, PINK_DARK), // snout
                Part(-4f, 0f, -6f, -1f, 6f, -3f, PINK_DARK),
                Part(1f, 0f, -6f, 4f, 6f, -3f, PINK_DARK),
                Part(-4f, 0f, 4f, -1f, 6f, 7f, PINK_DARK),
                Part(1f, 0f, 4f, 4f, 6f, 7f, PINK_DARK)
            ),
            1.0f
        ),
        // Sheep
        TypeInfo(
            "sheep",
            intArrayOf(WOOL, WOOL_DARK),
            listOf(
                Part(-4.5f, 7f, -8f, 4.5f, 16f, 8f, WOOL),
                Part(-3f, 10f, -13f, 3f, 16f, -8f, WOOL_DARK),
                Part(-4f, 0f, -6f, -1.5f, 7f, -3f, WOOL_DARK),
                Part(1.5f, 0f, -6f, 4f, 7f, -3f, WOOL_DARK),
                Part(-4f, 0f, 3f, -1.5f, 7f, 6f, WOOL_DARK),
                Part(1.5f, 0f, 3f, 4f, 7f, 6f, WOOL_DARK)
            ),
            1.1f
        ),
        // Cow
        TypeInfo(
            "cow",
            intArrayOf(BOVINE, BOVINE_DARK),
            listOf(
                Part(-5f, 6f, -9f, 5f, 15f, 9f, BOVINE),
                Part(-4f, 10f, -15f, 4f, 17f, -9f, BOVINE_DARK),
                Part(-3f, 12f, -16f, 3f, 15f, -15f, BOVINE_DARK),
                Part(-4f, 0f, -7f, -1f, 6f, -4f, BOVINE_DARK),
                Part(1f, 0f, -7f, 4f, 6f, -4f, BOVINE_DARK),
                Part(-4f, 0f, 4f, -1f, 6f, 7f, BOVINE_DARK),
                Part(1f, 0f, 4f, 4f, 6f, 7f, BOVINE_DARK)
            ),
            1.2f
        ),
        // Chicken
        TypeInfo(
            "chicken",
            intArrayOf(FEATHER, BEAK),
            listOf(
                Part(-3f, 4f, -4f, 3f, 10f, 4f, FEATHER),
                Part(-2f, 9f, -8f, 2f, 14f, -4f, FEATHER),
                Part(-1.5f, 10f, -9.5f, 1.5f, 12f, -8f, BEAK),
                Part(-1.5f, 0f, -1.5f, -0.5f, 4f, 0.5f, BEAK),
                Part(0.5f, 0f, -1.5f, 1.5f, 4f, 0.5f, BEAK)
            ),
            0.6f
        )
    )

    fun typeOf(index: Int): TypeInfo = TYPES[index.coerceIn(0, TYPES.size - 1)]


    /** Spawns 0-3 animals on grass for a freshly generated chunk. */
    fun spawnInChunk(chunk: Chunk, gen: WorldGen) {
        if (chunk.mobs != null) return
        val rnd = Random(gen.hashAt(chunk.cx * 16 + 1, chunk.cz * 16 + 7).toLong())
        if (rnd.nextFloat() > 0.30f) {
            chunk.mobs = ArrayList()
            return
        }
        val count = 1 + rnd.nextInt(3)
        val list = ArrayList<Mob>(count)
        val baseX = chunk.cx * 16
        val baseZ = chunk.cz * 16
        repeat(count) {
            val lx = 2 + rnd.nextInt(12)
            val lz = 2 + rnd.nextInt(12)
            val wx = baseX + lx
            val wz = baseZ + lz
            val biome = gen.biomeAt(wx, wz)
            if (biome == Biomes.DESERT) return@repeat
            var y = -1
            for (ty in World.HEIGHT - 2 downTo 1) {
                if (chunk.get(lx, ty, lz) != Blocks.AIR) {
                    y = ty
                    break
                }
            }
            if (y < 0) return@repeat
            if (Blocks.get(chunk.get(lx, y, lz)).id != "grass") return@repeat
            val type = when {
                biome == Biomes.FOREST || biome == Biomes.TAIGA -> if (rnd.nextBoolean()) Mob.SHEEP else Mob.COW
                biome == Biomes.SNOWY -> Mob.SHEEP
                else -> when (rnd.nextInt(4)) {
                    0 -> Mob.PIG
                    1 -> Mob.SHEEP
                    2 -> Mob.COW
                    else -> Mob.CHICKEN
                }
            }
            list.add(
                Mob(
                    type,
                    wx + 0.5,
                    (y + 1).toDouble(),
                    wz + 0.5,
                    rnd.nextFloat() * 360f,
                    timer = rnd.nextFloat() * 3f
                )
            )
        }
        chunk.mobs = list
    }
}
