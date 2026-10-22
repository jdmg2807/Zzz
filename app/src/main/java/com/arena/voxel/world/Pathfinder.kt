package com.arena.voxel.world

import kotlin.math.abs
import kotlin.math.floor
import java.util.PriorityQueue

/**
 * 3D A* over the voxel grid, tuned for Minecraft-like mobs.
 *
 * Nodes are block positions; a node is walkable when the block at its feet and the one above are
 * free and the block below is solid. Moves:
 *
 *  - step to a neighbour at the same height (cost 1)
 *  - step up exactly one block (cost 2, the mob "jumps")
 *  - step down up to 3 blocks (cost 1 + fall distance, more expensive the higher the drop)
 *  - diagonal moves cost 1.41 and are only allowed with a free corner
 *
 * Dangerous nodes (lava, fire, cactus, water deeper than 1) are rejected, which is what makes a
 * zombie path around a lava pool instead of walking through it.
 */
object Pathfinder {

    private class Node(val x: Int, val y: Int, val z: Int) {
        var g = Float.MAX_VALUE
        var f = Float.MAX_VALUE
        var parent: Node? = null
        var closed = false
    }

    private fun key(x: Int, y: Int, z: Int): Long =
        (x.toLong() and 0x3FFFFFF) or ((z.toLong() and 0x3FFFFFF) shl 26) or ((y.toLong() and 0x3FF) shl 52)

    private fun solid(world: World, x: Int, y: Int, z: Int): Boolean {
        val block = Blocks.get(world.get(x, y, z))
        return !block.shape.isEmpty && !block.liquid
    }

    private fun air(world: World, x: Int, y: Int, z: Int): Boolean {
        val block = Blocks.get(world.get(x, y, z))
        return block.shape.isEmpty
    }

    /** Blocks a mob would refuse to stand on or in. */
    private fun dangerous(world: World, x: Int, y: Int, z: Int): Boolean {
        val below = Blocks.get(world.get(x, y - 1, z))
        val feet = Blocks.get(world.get(x, y, z))
        if (below.id == "lava" || feet.id == "lava") return true
        if (below.id == "cactus" || feet.id == "cactus") return true
        if (feet.id == "fire" || below.id == "fire") return true
        // Deep water is not a valid walking path.
        if (below.liquid && Blocks.get(world.get(x, y - 2, z)).liquid) return true
        return false
    }

    private fun standable(world: World, x: Int, y: Int, z: Int): Boolean =
        solid(world, x, y - 1, z) && air(world, x, y, z) && air(world, x, y + 1, z) && !dangerous(world, x, y, z)

    /**
     * @return waypoints as `IntArray(x, y, z)` from start (exclusive) to goal (inclusive), or an
     * empty list when unreachable within [maxNodes].
     */
    fun find(
        world: World,
        sx: Int, sy: Int, sz: Int,
        gx: Int, gy: Int, gz: Int,
        maxNodes: Int = 900,
        maxDrop: Int = 3
    ): List<IntArray> {
        if (sx == gx && sy == gy && sz == gz) return emptyList()
        val open = PriorityQueue<Node>(compareBy { it.f })
        val seen = HashMap<Long, Node>()
        val start = Node(sx, sy, sz).also {
            it.g = 0f
            it.f = heuristic(sx, sy, sz, gx, gy, gz)
        }
        seen[key(sx, sy, sz)] = start
        open.add(start)
        var visited = 0

        while (open.isNotEmpty() && visited < maxNodes) {
            val current = open.poll() ?: break
            if (current.closed) continue
            current.closed = true
            visited++

            if (abs(current.x - gx) <= 1 && abs(current.y - gy) <= 1 && abs(current.z - gz) <= 1) {
                return reconstruct(current)
            }

            for (dir in DIRS) {
                val nx = current.x + dir[0]
                val nz = current.z + dir[2]
                val diag = dir[0] != 0 && dir[2] != 0
                if (diag) {
                    // Diagonals need both adjacent corners free.
                    if (!air(world, current.x + dir[0], current.y, current.z) ||
                        !air(world, current.x, current.y, current.z + dir[2])
                    ) continue
                }
                // Same level.
                if (standable(world, nx, current.y, nz)) {
                    relax(seen, open, current, nx, current.y, nz, if (diag) 1.41f else 1f, gx, gy, gz)
                    continue
                }
                // Jump one block up.
                if (standable(world, nx, current.y + 1, nz) &&
                    air(world, current.x, current.y + 2, current.z)
                ) {
                    relax(seen, open, current, nx, current.y + 1, nz, if (diag) 2.4f else 2f, gx, gy, gz)
                    continue
                }
                // Fall down up to maxDrop.
                for (drop in 1..maxDrop) {
                    val ny = current.y - drop
                    if (standable(world, nx, ny, nz)) {
                        relax(seen, open, current, nx, ny, nz, (if (diag) 1.41f else 1f) + drop * 0.6f, gx, gy, gz)
                        break
                    }
                    // Do not tunnel through solid ground while falling.
                    if (solid(world, nx, ny, nz)) break
                }
            }
        }
        return emptyList()
    }

    private fun relax(
        seen: HashMap<Long, Node>,
        open: PriorityQueue<Node>,
        parent: Node,
        x: Int, y: Int, z: Int,
        cost: Float,
        gx: Int, gy: Int, gz: Int
    ) {
        val k = key(x, y, z)
        val existing = seen[k]
        val node = if (existing == null) Node(x, y, z).also { seen[k] = it } else existing
        if (node.closed) return
        val ng = parent.g + cost
        if (ng < node.g) {
            node.g = ng
            node.f = ng + heuristic(x, y, z, gx, gy, gz)
            node.parent = parent
            open.add(node)
        }
    }

    private fun heuristic(x: Int, y: Int, z: Int, gx: Int, gy: Int, gz: Int): Float =
        (abs(x - gx) + abs(z - gz) + abs(y - gy) * 1.5f).toFloat()

    private val DIRS = arrayOf(
        intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
        intArrayOf(0, 0, 1), intArrayOf(0, 0, -1),
        intArrayOf(1, 0, 1), intArrayOf(1, 0, -1),
        intArrayOf(-1, 0, 1), intArrayOf(-1, 0, -1)
    )

    private fun reconstruct(node: Node): List<IntArray> {
        val out = ArrayList<IntArray>()
        var n: Node? = node
        while (n != null) {
            out.add(intArrayOf(n.x, n.y, n.z))
            n = n.parent
        }
        out.reverse()
        return if (out.isNotEmpty()) out.drop(1) else out
    }

    /** Highest standable block at a column (used to place mobs on the ground). */
    fun groundY(world: World, x: Int, z: Int, from: Int = World.HEIGHT - 2): Int {
        for (y in from downTo 1) {
            if (standable(world, x, y, z)) return y
        }
        return 1
    }

    fun blockPos(v: Double): Int = floor(v).toInt()
}
