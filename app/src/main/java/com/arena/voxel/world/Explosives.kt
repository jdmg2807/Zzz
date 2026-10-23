package com.arena.voxel.world

import com.arena.voxel.Game
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * TNT, the way Java does it:
 *
 *  * Lighting it (flint and steel, or a redstone signal) replaces the block with lit TNT and
 *    starts an 80 game-tick (4 s) fuse, with the white "flash" pulse in the last half second.
 *  * The blast has a power of 4. Every block within ~7 blocks is destroyed unless it is
 *    blast-proof (bedrock, obsidian, the anvil), and the damage falls off with distance.
 *  * Entities take `power * (1 - distance / (2 * power))` damage, which is why standing next to
 *    a TNT is lethal but two blocks further away only hurts.
 */
object Explosives {

    private class Fuse(val x: Int, val y: Int, val z: Int, var ticks: Float = 80f)

    private val fuses = ArrayList<Fuse>()

    fun clear() = fuses.clear()

    /** Lights the TNT at a position. Returns false when there is no TNT there. */
    fun ignite(world: World, x: Int, y: Int, z: Int): Boolean {
        val id = Blocks.get(world.get(x, y, z)).id
        if (id != "tnt") return false
        world.set(x, y, z, Blocks.index("tnt_lit"))
        fuses.add(Fuse(x, y, z))
        com.arena.voxel.core.Sounds.play("click", 0.8f, 0.8f)
        Game.chat("¡TNT encendida! Tienes 4 segundos")
        return true
    }

    fun update(world: World, dt: Float) {
        if (fuses.isEmpty()) return
        val step = (dt * 20f).coerceIn(0f, 3f)
        val iterator = fuses.iterator()
        while (iterator.hasNext()) {
            val fuse = iterator.next()
            // Accumulate the fractional tick so a 60 fps phone still counts exactly 80 ticks.
            fuse.ticks -= step
            if (fuse.ticks > 0f) continue
            iterator.remove()
            if (Blocks.get(world.get(fuse.x, fuse.y, fuse.z)).id == "tnt_lit") {
                world.setSilently(fuse.x, fuse.y, fuse.z, Blocks.AIR)
            }
            explode(world, fuse.x + 0.5, fuse.y + 0.5, fuse.z + 0.5, 4f)
        }
    }

    /** Generic explosion (TNT, creeper, beds in the Nether…). */
    fun explode(world: World, cx: Double, cy: Double, cz: Double, power: Float) {
        val radius = (power * 2f).toInt()
        val bx = floor(cx).toInt()
        val by = floor(cy).toInt()
        val bz = floor(cz).toInt()
        for (dx in -radius..radius) {
            for (dy in -radius..radius) {
                for (dz in -radius..radius) {
                    val x = bx + dx
                    val y = by + dy
                    val z = bz + dz
                    if (y < 1 || y >= World.HEIGHT) continue
                    // Strength falls off with distance, exactly like Java's ray-based model.
                    val d = sqrt((dx * dx + dy * dy + dz * dz).toDouble())
                    if (d > radius) continue
                    val id = Blocks.get(world.get(x, y, z)).id
                    if (id == "air") continue
                    if (isBlastProof(id)) continue
                    val resistance = explosionResistance(id)
                    if (resistance > power * (1f - d.toFloat() / radius) * 8f) continue
                    if (id == "tnt") {
                        // Chain reaction: light it instead of destroying it.
                        ignite(world, x, y, z)
                        continue
                    }
                    world.set(x, y, z, Blocks.AIR)
                    // A quarter of the broken blocks drop, like Java's 1/3-ish loot chance.
                    val drop = com.arena.voxel.items.Items.dropOf(id)
                    if (drop != null && Math.random() < 0.3) {
                        ItemEntities.spawn(drop, 1, x + 0.5, y + 0.4, z + 0.5)
                    }
                }
            }
        }

        // Entities: the player and every mob within the blast radius.
        val player = Game.player
        if (player != null) {
            val d = sqrt(
                (player.x - cx) * (player.x - cx) + (player.y - cy) * (player.y - cy) +
                    (player.z - cz) * (player.z - cz)
            )
            if (d < power * 2) {
                val damage = power * (1f - (d / (power * 2)).toFloat())
                player.hurt(damage * 7f)
                // Knockback away from the centre.
                val nx = (player.x - cx) / (d + 0.001)
                val nz = (player.z - cz) / (d + 0.001)
                player.vx += nx * 0.9
                player.vz += nz * 0.9
                player.vy = 0.6
            }
        }
        for (chunk in world.chunks.values) {
            val mobs = chunk.mobs ?: continue
            for (m in mobs) {
                val d = sqrt((m.x - cx) * (m.x - cx) + (m.y - cy) * (m.y - cy) + (m.z - cz) * (m.z - cz))
                if (d < power * 2) m.health -= power * 2f * (1f - (d / (power * 2)).toFloat())
            }
        }

        // Smoke + embers.
        for (i in 0 until 40) {
            Particles.push(
                Particles.Particle(
                    (cx + (Math.random() - 0.5) * power).toFloat(),
                    (cy + Math.random() * power * 0.8).toFloat(),
                    (cz + (Math.random() - 0.5) * power).toFloat(),
                    ((Math.random() - 0.5) * 0.6).toFloat(),
                    (Math.random() * 0.4).toFloat(),
                    ((Math.random() - 0.5) * 0.6).toFloat(),
                    if (i % 4 == 0) 1f else 0.5f,
                    if (i % 4 == 0) 0.55f else 0.5f,
                    if (i % 4 == 0) 0.12f else 0.5f,
                    1f, 1.0f, 10f, 1
                )
            )
        }
        com.arena.voxel.core.Sounds.play("dig_stone", 1f, 0.6f)
        Game.chat("¡Explosión!")
    }

    private fun isBlastProof(id: String): Boolean =
        id == "bedrock" || id == "obsidian" || id == "crying_obsidian" ||
            id == "anvil" || id == "vault" || id == "trial_spawner" || id == "heavy_core"

    /**
     * Blast resistance from the wiki, coarse-grained: stone 6, wood 3, dirt 0.5, metal 10.
     * Anything above the local power survives, which is why obsidian needs a much bigger blast.
     */
    private fun explosionResistance(id: String): Float = when {
        id.endsWith("_ore") || id.contains("stone") || id.contains("deepslate") ||
            id.contains("brick") || id.contains("tuff") || id.contains("basalt") ||
            id.contains("quartz") || id.contains("andesite") || id.contains("granite") ||
            id.contains("diorite") -> 6f
        id.contains("iron") || id.contains("copper") || id.contains("gold") -> 8f
        id.contains("plank") || id.contains("log") || id.contains("wood") ||
            id.contains("chest") || id.contains("table") || id.contains("door") -> 3f
        id == "dirt" || id == "grass_block" || id == "sand" || id == "gravel" ||
            id == "clay" || id == "snow_block" || id == "snow_layer" -> 0.6f
        id.contains("glass") || id.contains("wool") || id.contains("carpet") -> 0.3f
        else -> 2f
    }
}
