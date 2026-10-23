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
        const val ZOMBIE = 4
        const val SKELETON = 5
        const val CREEPER = 6
    }

    /** Hostile mobs (spawn in the dark, chase and attack the player). */
    var health = 20f
    var hostile = false
    var attackCooldown = 0f
    var fuse = -1f
    var path: List<IntArray>? = null
    var pathIndex = 0
    var pathTimer = 0f
    var target: com.arena.voxel.player.Player? = null

    fun update(dt: Float, world: World) = update(dt, world, null)

    fun update(dt: Float, world: World, playerTarget: com.arena.voxel.player.Player?) {
        hostile = type == ZOMBIE || type == SKELETON || type == CREEPER
        target = playerTarget
        if (attackCooldown > 0f) attackCooldown -= dt
        if (hostile && playerTarget != null && !playerTarget.isDead()) {
            if (updateHostile(dt, world, playerTarget)) return
        }
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

    /**
     * Chase/attack behaviour. Returns true when it handled movement, so the wander code below
     * does not fight it for control of the mob.
     *
     * AI goals, in Java's priority order: attack the target, path towards it, otherwise idle.
     */
    private fun updateHostile(dt: Float, world: World, p: com.arena.voxel.player.Player): Boolean {
        val dx = p.x - x
        val dz = p.z - z
        val dy = p.y - y
        val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz)
        if (dist > 24.0) {
            path = null
            return false
        }
        yaw = Math.toDegrees(kotlin.math.atan2(-dx, -dz)).toFloat()

        // ---- Creeper: walk up, hiss, explode -------------------------------------------
        if (type == CREEPER) {
            if (dist < 2.4 && fuse < 0f) fuse = 1.5f
            if (fuse >= 0f) {
                fuse -= dt
                if (fuse <= 0f) {
                    explode(world, p)
                    health = 0f
                    return true
                }
            } else if (dist > 2.4) {
                followPath(dt, world, p, 2.4)
            }
            applyGravity(dt, world)
            return true
        }

        // ---- Skeleton: keep distance and shoot -----------------------------------------
        if (type == SKELETON) {
            if (dist in 4.0..14.0) {
                if (attackCooldown <= 0f) {
                    attackCooldown = 2f
                    p.hurt(2f)
                    com.arena.voxel.core.Logs.info("MOB", "Esqueleto dispara al jugador (vida ${p.health.toInt()})")
                }
                path = null
                applyGravity(dt, world)
                return true
            }
            if (dist < 4.0) {
                // Back away.
                val nx = x - dx / dist * 1.6 * dt
                val nz = z - dz / dist * 1.6 * dt
                if (!blocked(world, nx, y, z)) x = nx
                if (!blocked(world, x, y, nz)) z = nz
                applyGravity(dt, world)
                return true
            }
            followPath(dt, world, p, 10.0)
            applyGravity(dt, world)
            return true
        }

        // ---- Zombie: melee -------------------------------------------------------------
        if (dist < 2.0) {
            if (attackCooldown <= 0f) {
                attackCooldown = 1f
                p.hurt(3f)
                com.arena.voxel.core.Logs.info("MOB", "Zombi golpea al jugador (vida ${p.health.toInt()})")
            }
            path = null
            applyGravity(dt, world)
            return true
        }
        followPath(dt, world, p, 1.5)
        applyGravity(dt, world)
        return true
    }

    /** Recomputes the A* path when needed and walks along it. */
    private fun followPath(dt: Float, world: World, p: com.arena.voxel.player.Player, stopAt: Double) {
        pathTimer -= dt
        val current = path
        val needsNew = current == null || pathIndex >= current.size ||
            pathTimer <= 0f ||
            (current.isNotEmpty() && pathIndex < current.size &&
                kotlin.math.abs(current[pathIndex][0] - p.x) > 3.0)
        if (needsNew && pathTimer <= 0f) {
            pathTimer = 1.2f
            val startY = kotlin.math.floor(y + 0.01).toInt()
            val found = Pathfinder.find(
                world,
                kotlin.math.floor(x).toInt(), startY, kotlin.math.floor(z).toInt(),
                kotlin.math.floor(p.x).toInt(), kotlin.math.floor(p.y).toInt(), kotlin.math.floor(p.z).toInt()
            )
            path = found
            pathIndex = 0
        }
        val list = path ?: return
        if (pathIndex >= list.size) return
        val node = list[pathIndex]
        val tx = node[0] + 0.5
        val ty = node[1].toDouble()
        val tz = node[2] + 0.5
        val ddx = tx - x
        val ddz = tz - z
        val d = kotlin.math.sqrt(ddx * ddx + ddz * ddz)
        val speed = when (type) {
            ZOMBIE -> 1.9
            SKELETON -> 2.2
            CREEPER -> 1.7
            else -> 1.5
        }
        if (d < 0.35) {
            // Reached the node: climb if it is above us.
            if (node[1] > y + 1.0) {
                vy = 6.3
            }
            pathIndex++
            return
        }
        walk += dt * 8f
        val nx = x + ddx / d * speed * dt
        val nz = z + ddz / d * speed * dt
        if (!blocked(world, nx, y, z)) x = nx
        if (!blocked(world, x, y, nz)) z = nz
        // Jump when the next node is one block higher.
        if (pathIndex < list.size && list[pathIndex][1] > kotlin.math.floor(y).toInt() + 0.1) {
            if (onGroundHeight(world)) vy = 6.3
        }
    }

    private fun onGroundHeight(world: World): Boolean =
        world.isSolid(kotlin.math.floor(x).toInt(), kotlin.math.floor(y - 0.05).toInt(), kotlin.math.floor(z).toInt())

    private fun applyGravity(dt: Float, world: World) {
        if (!onGroundHeight(world)) {
            vy -= 22.0 * dt
            if (vy < -30.0) vy = -30.0
            val ny = y + vy * dt
            if (!blocked(world, x, ny, z)) y = ny else vy = 0.0
        } else if (vy < 0.0) {
            vy = 0.0
        }
    }

    /** Creeper explosion: damages the player and removes nearby soft blocks in a 3 block radius. */
    private fun explode(world: World, p: com.arena.voxel.player.Player) {
        // A creeper blast is a power-3 explosion: same falloff, same block resistance table.
        Explosives.explode(world, x, y + 0.5, z, 3f)
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
        ,
        // Zombie (rotten green skin, blue trousers, arms forward)
        TypeInfo(
            "zombie",
            intArrayOf(0x4E7A46, 0x2E5A8A),
            listOf(
                Part(-4f, 6f, -2f, 4f, 18f, 2f, 0x2E5A8A),       // legs/body lower
                Part(-4f, 12f, -2f, 4f, 18f, 2f, 0x4E7A46),      // torso
                Part(-4f, 18f, -4f, 4f, 26f, 2f, 0x4E7A46),      // head
                Part(-6f, 15f, -4f, -4f, 25f, -2f, 0x4E7A46),    // arms forward
                Part(4f, 15f, -4f, 6f, 25f, -2f, 0x4E7A46)
            ),
            1.7f
        ),
        // Skeleton (bone white)
        TypeInfo(
            "skeleton",
            intArrayOf(0xD8D8D0, 0x9A9A94),
            listOf(
                Part(-3f, 8f, -1.5f, 3f, 26f, 1.5f, 0xD8D8D0),
                Part(-4f, 26f, -3f, 4f, 34f, 2f, 0xD8D8D0),
                Part(-5f, 22f, -2f, -3f, 32f, 0f, 0x9A9A94),
                Part(3f, 22f, -2f, 5f, 32f, 0f, 0x9A9A94),
                Part(-3f, 0f, -1.5f, -0.5f, 8f, 1.5f, 0xD8D8D0),
                Part(0.5f, 0f, -1.5f, 3f, 8f, 1.5f, 0xD8D8D0)
            ),
            1.8f
        ),
        // Creeper (green, four short legs, no arms)
        TypeInfo(
            "creeper",
            intArrayOf(0x5BBC4A, 0x2F7A2A),
            listOf(
                Part(-4f, 6f, -2f, 4f, 22f, 2f, 0x5BBC4A),
                Part(-4f, 18f, -4f, 4f, 26f, 0f, 0x5BBC4A),
                Part(-4f, 0f, -4f, -2f, 6f, -2f, 0x2F7A2A),
                Part(2f, 0f, -4f, 4f, 6f, -2f, 0x2F7A2A),
                Part(-4f, 0f, 2f, -2f, 6f, 4f, 0x2F7A2A),
                Part(2f, 0f, 2f, 4f, 6f, 4f, 0x2F7A2A)
            ),
            1.5f
        )
    )

    fun typeOf(index: Int): TypeInfo = TYPES[index.coerceIn(0, TYPES.size - 1)]

    /** Dark, safe spot check for hostile spawning (night + solid ground + sky access). */
    fun canSpawnHostile(world: World, gen: WorldGen, x: Int, y: Int, z: Int): Boolean {
        if (!world.isAir(x, y, z) || !world.isAir(x, y + 1, z)) return false
        if (!world.isSolid(x, y - 1, z)) return false
        val below = Blocks.get(world.get(x, y - 1, z)).id
        if (below == "water" || below == "leaves") return false
        return true
    }

    /** Night-time hostile spawn around the player, Java-style (dark + ground). */
    fun spawnHostiles(world: World, gen: WorldGen, px: Int, pz: Int, rnd: Random): Int {
        var spawned = 0
        repeat(6) {
            val x = px + rnd.nextInt(33) - 16
            val z = pz + rnd.nextInt(33) - 16
            val y = Pathfinder.groundY(world, x, z)
            if (!canSpawnHostile(world, gen, x, y, z)) return@repeat
            if (y < 8) return@repeat
            val chunk = world.getOrCreateChunk(x shr 4, z shr 4)
            val list = chunk.mobs ?: ArrayList<Mob>().also { chunk.mobs = it }
            if (list.size >= 6) return@repeat
            val type = when (rnd.nextInt(10)) {
                0, 1, 2, 3 -> Mob.ZOMBIE
                4, 5, 6 -> Mob.SKELETON
                else -> Mob.CREEPER
            }
            val mob = Mob(type, x + 0.5, y.toDouble(), z + 0.5, rnd.nextFloat() * 360f)
            mob.health = when (type) {
                Mob.ZOMBIE -> 20f
                Mob.SKELETON -> 20f
                else -> 20f
            }
            list.add(mob)
            spawned++
        }
        return spawned
    }


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
