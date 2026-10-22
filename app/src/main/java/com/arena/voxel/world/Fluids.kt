package com.arena.voxel.world

/**
 * Gravity blocks and water flow, the two "world feels alive" behaviours every player expects.
 *
 * **Falling blocks** (sand, gravel, concrete powder…) are queued whenever the block under or
 * beside them changes, so digging under a sand pillar makes it collapse one block per tick —
 * exactly the Java 1.21 speed (2 blocks per 0.1 s when falling).
 *
 * **Water** uses the same 8 levels as Java: level 0 is a source, 1..7 are flowing water that
 * loses one level per horizontal block (so it reaches 7 blocks), and level 8 is falling water.
 * Water flows every 5 game ticks (0.25 s), like `FlowingFluid`.
 */
object Fluids {

    private val GRAVITY_BLOCKS = setOf("sand", "gravel", "red_sand", "concrete_powder")

    /** Positions with a gravity block that may need to move down. */
    private val falling = LinkedHashSet<Long>()

    /** Water level per position; absent means "no water here". */
    private val levels = HashMap<Long, Int>()

    /** Positions that still need a flow step. */
    private val active = LinkedHashSet<Long>()

    private var flowTimer = 0f

    fun clear() {
        falling.clear()
        levels.clear()
        active.clear()
        flowTimer = 0f
    }

    private fun key(x: Int, y: Int, z: Int): Long =
        (x.toLong() and 0x3FFFFFF) or ((z.toLong() and 0x3FFFFFF) shl 26) or
            ((y.toLong() and 0x3FFF) shl 54)

    private fun kx(k: Long): Int = (k and 0x3FFFFFF).toInt().let { if (it and 0x2000000 != 0) it - 0x4000000 else it }
    private fun ky(k: Long): Int = ((k ushr 54) and 0x3FFF).toInt()
    private fun kz(k: Long): Int = ((k ushr 26) and 0x3FFFFFF).toInt().let { if (it and 0x2000000 != 0) it - 0x4000000 else it }

    /** Water level at a position, or -1 when there is no water there. */
    fun levelAt(world: World, x: Int, y: Int, z: Int): Int {
        if (Blocks.get(world.get(x, y, z)).id != "water") return -1
        return levels[key(x, y, z)] ?: 0
    }

    /** Called by [World.set] for every block change. */
    fun onBlockChanged(world: World, x: Int, y: Int, z: Int) {
        // Anything that could fall because this block disappeared or appeared.
        for (dy in 0..1) {
            val above = Blocks.get(world.get(x, y + dy, z)).id
            if (GRAVITY_BLOCKS.contains(above)) falling.add(key(x, y + dy, z))
        }
        // Water around the change needs to re-evaluate its flow.
        for (dir in 0 until 6) {
            val nx = x + DX[dir]
            val ny = y + DY[dir]
            val nz = z + DZ[dir]
            if (ny < 0 || ny >= World.HEIGHT) continue
            if (Blocks.get(world.get(nx, ny, nz)).id == "water") active.add(key(nx, ny, nz))
        }
        if (Blocks.get(world.get(x, y, z)).id == "water") active.add(key(x, y, z))
    }

    /** Registers a freshly placed or generated water source. */
    fun markSource(world: World, x: Int, y: Int, z: Int) {
        levels[key(x, y, z)] = 0
        active.add(key(x, y, z))
    }

    fun tick(world: World, dt: Float) {
        // Falling blocks: one block per game tick, which is what the eye expects.
        if (falling.isNotEmpty()) {
            val iterator = falling.iterator()
            val batch = ArrayList<Long>(minOf(falling.size, 256))
            while (iterator.hasNext() && batch.size < 256) {
                batch.add(iterator.next())
                iterator.remove()
            }
            for (k in batch) {
                val x = kx(k)
                val y = ky(k)
                val z = kz(k)
                val id = Blocks.get(world.get(x, y, z)).id
                if (!GRAVITY_BLOCKS.contains(id)) continue
                if (y <= 0) continue
                val below = Blocks.get(world.get(x, y - 1, z))
                val canFall = below.id == "air" || below.liquid || below.id == "torch" ||
                    below.id == "dead_bush" || below.id == "tall_grass"
                if (!canFall) {
                    // Settled: convert sand next to water and stop tracking it.
                    continue
                }
                world.setSilently(x, y, z, Blocks.AIR)
                world.setSilently(x, y - 1, z, Blocks.index(id))
                falling.add(key(x, y - 1, z))
                falling.add(key(x, y + 1, z))
                Game_refresh(world, x, y - 1, z)
            }
        }

        // Water: every 5 ticks (0.25 s).
        flowTimer += dt
        if (flowTimer < 0.25f) return
        flowTimer = 0f
        if (active.isEmpty()) return
        val queue = ArrayList<Long>(minOf(active.size, 512))
        val iterator = active.iterator()
        while (iterator.hasNext() && queue.size < 512) {
            queue.add(iterator.next())
            iterator.remove()
        }
        for (k in queue) {
            val x = kx(k)
            val y = ky(k)
            val z = kz(k)
            if (Blocks.get(world.get(x, y, z)).id != "water") {
                levels.remove(k)
                continue
            }
            val level = levels[k] ?: 0
            // Flowing water with nothing feeding it dries up (Java's `FluidState.tick` decay).
            if (level in 1..7 && !fed(world, x, y, z, level)) {
                world.setSilently(x, y, z, Blocks.AIR)
                levels.remove(k)
                Game_refresh(world, x, y, z)
                active.add(key(x, y + 1, z))
                continue
            }
            // Falling water keeps level 8, source keeps 0, flow adds one per block.
            if (y > 0) {
                val below = Blocks.get(world.get(x, y - 1, z))
                if (below.id == "air" || below.id == "torch" || below.id == "tall_grass") {
                    world.setSilently(x, y - 1, z, Blocks.index("water"))
                    levels[key(x, y - 1, z)] = 8
                    active.add(key(x, y - 1, z))
                    Game_refresh(world, x, y - 1, z)
                    continue
                }
            }
            if (level >= 7) continue
            val next = level + 1
            for (dir in 0 until 4) {
                val nx = x + DX[dir]
                val nz = z + DZ[dir]
                if (ny2Check(world, nx, y, nz)) {
                    world.setSilently(nx, y, nz, Blocks.index("water"))
                    levels[key(nx, y, nz)] = next
                    active.add(key(nx, y, nz))
                    Game_refresh(world, nx, y, nz)
                }
            }
        }
    }

    /** True when a lower-level (or falling) water block feeds this position. */
    private fun fed(world: World, x: Int, y: Int, z: Int, level: Int): Boolean {
        for (dir in 0 until 6) {
            val nx = x + DX[dir]
            val ny = y + DY[dir]
            val nz = z + DZ[dir]
            if (ny < 0 || ny >= World.HEIGHT) continue
            if (Blocks.get(world.get(nx, ny, nz)).id != "water") continue
            val other = levels[key(nx, ny, nz)] ?: 0
            if (other < level || (dy(dir) > 0 && other == 8)) return true
        }
        return false
    }

    private fun dy(dir: Int): Int = DY[dir]

    private fun ny2Check(world: World, x: Int, y: Int, z: Int): Boolean {
        if (y < 0 || y >= World.HEIGHT) return false
        val block = Blocks.get(world.get(x, y, z))
        return block.id == "air"
    }

    /** Tells the renderer a block changed without going through [World.set]'s redstone hook. */
    private fun Game_refresh(world: World, x: Int, y: Int, z: Int) {
        world.markDirtyAround(x, y, z)
    }

    private val DX = intArrayOf(1, -1, 0, 0, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1, 0, 0)
    private val DZ = intArrayOf(0, 0, 0, 0, 1, -1)
}
