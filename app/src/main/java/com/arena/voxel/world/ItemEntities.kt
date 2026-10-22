package com.arena.voxel.world

import com.arena.voxel.Game
import com.arena.voxel.player.Player
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * Dropped items, the way Java handles `ItemEntity`:
 *
 * * A broken block spawns a 0.25-block cube that pops out with a little velocity.
 * * Gravity 0.04/tick, drag 0.98, and it stops dead when it lands (no bounce for items).
 * * The player picks it up within 1 block, walking towards it "magnets" it in.
 * * Merges with a nearby stack of the same item, and despawns after 5 minutes (6000 ticks).
 *
 * Rendering only needs the item cube, so [Renderer] builds one mesh per item id and reuses it.
 */
object ItemEntities {

    class Entity(
        val id: String,
        var count: Int,
        var x: Double,
        var y: Double,
        var z: Double,
        var vx: Double,
        var vy: Double,
        var vz: Double
    ) {
        var age = 0
        /** Pickup delay so the item you just dropped does not snap back instantly. */
        var delay = 10
        var spin = 0f
    }

    private val items = ArrayList<Entity>()

    fun all(): List<Entity> = items

    fun clear() = items.clear()

    fun spawn(id: String, count: Int, x: Double, y: Double, z: Double) {
        if (count <= 0) return
        items.add(
            Entity(
                id, count, x, y, z,
                vx = (Math.random() - 0.5) * 0.12,
                vy = 0.18,
                vz = (Math.random() - 0.5) * 0.12
            )
        )
    }

    /** Everything the player is carrying lands on the ground where they died. */
    fun dropInventory(player: Player) {
        val inv = Game.inventory
        for (i in 0 until inv.size) {
            val stack = inv.get(i) ?: continue
            spawn(stack.id, stack.count, player.x, player.y + 0.9, player.z)
            inv.set(i, null)
        }
        Game.syncHotbar()
    }

    private fun solid(world: World, x: Double, y: Double, z: Double): Boolean {
        val bx = floor(x).toInt()
        val by = floor(y).toInt()
        val bz = floor(z).toInt()
        if (by < 0 || by >= World.HEIGHT) return false
        return world.isSolid(bx, by, bz)
    }

    fun update(dt: Float, world: World, player: Player) {
        if (items.isEmpty()) return
        val ticks = (dt * 20f).coerceIn(0f, 4f)
        val iterator = items.iterator()
        while (iterator.hasNext()) {
            val e = iterator.next()
            e.age++
            if (e.delay > 0) e.delay--
            if (e.age > 6000) {
                iterator.remove()
                continue
            }
            e.spin = (e.spin + 90f * dt) % 360f

            // Physics (per-tick constants from the wiki: gravity 0.04, drag 0.98).
            e.vy -= 0.04 * ticks
            e.vx *= 0.98
            e.vz *= 0.98
            e.vy *= 0.98

            // Horizontal move with a simple step-up so items do not get stuck on slabs.
            val nx = e.x + e.vx * ticks
            val nz = e.z + e.vz * ticks
            if (!solid(world, nx, e.y + 0.1, e.z)) e.x = nx else e.vx = 0.0
            if (!solid(world, e.x, e.y + 0.1, nz)) e.z = nz else e.vz = 0.0

            val ny = e.y + e.vy * ticks
            if (solid(world, e.x, ny + 0.05, e.z)) {
                if (e.vy < 0) {
                    e.y = floor(e.y).toDouble() + if (solid(world, e.x, e.y, e.z)) 1.0 else 0.0
                }
                e.vy = 0.0
                e.vx *= 0.6
                e.vz *= 0.6
            } else {
                e.y = ny
            }

            // Pickup: 1 block radius, shrinking to 0.5 once the magnet pulls it in.
            val dx = player.x - e.x
            val dy = (player.y + 0.9) - e.y
            val dz = player.z - e.z
            val dist = sqrt(dx * dx + dy * dy + dz * dz)
            if (e.delay == 0 && dist < 1.6 && !player.isDead()) {
                if (dist < 0.8) {
                    val left = Game.inventory.add(
                        com.arena.voxel.items.ItemStack(e.id, e.count)
                    )
                    if (left <= 0) {
                        Game.syncHotbar()
                        iterator.remove()
                    } else {
                        e.count = left
                    }
                } else {
                    // Magnet: move a fraction of the way towards the player each tick.
                    e.x += dx * abs(0.14 * ticks).coerceAtMost(0.5)
                    e.z += dz * abs(0.14 * ticks).coerceAtMost(0.5)
                    e.y += dy * abs(0.10 * ticks).coerceAtMost(0.4)
                }
            }
        }
    }
}
