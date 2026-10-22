package com.arena.voxel.world

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Seed based value-noise terrain with biomes, ores, water and vegetation.
 * Everything is deterministic per coordinate so chunks can be generated in any order.
 */
class WorldGen(val seed: Long) {

    companion object {
        const val SEA_LEVEL = 32
        const val CLOUD_Y = 108f
    }

    private val s = seed.toInt()

    /** Public deterministic hash (0..2^31) used by other systems such as animal spawning. */
    fun hashAt(x: Int, z: Int): Int = (hash(x, z) * Int.MAX_VALUE).toInt()

    private fun hash(x: Int, z: Int): Float {
        var h = x * 374761393 + z * 668265263 + s * 1013904223
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return (h and 0x7fffffff) / 2147483647f
    }

    private fun hash3(x: Int, y: Int, z: Int): Float {
        var h = x * 374761393 + y * 1103515245 + z * 668265263 + s * 1013904223
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return (h and 0x7fffffff) / 2147483647f
    }

    private fun smooth(t: Float) = t * t * (3f - 2f * t)
    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    fun noise(x: Float, z: Float): Float {
        val x0 = floor(x).toInt()
        val z0 = floor(z).toInt()
        val fx = smooth(x - x0)
        val fz = smooth(z - z0)
        val n00 = hash(x0, z0)
        val n10 = hash(x0 + 1, z0)
        val n01 = hash(x0, z0 + 1)
        val n11 = hash(x0 + 1, z0 + 1)
        return lerp(lerp(n00, n10, fx), lerp(n01, n11, fx), fz)
    }

    /** Smooth 3D value noise for caves. */
    fun noise3(x: Float, y: Float, z: Float): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt(); val z0 = floor(z).toInt()
        val fx = smooth(x - x0); val fy = smooth(y - y0); val fz = smooth(z - z0)
        fun h(ax: Int, ay: Int, az: Int): Float = hash3(ax, ay, az)
        val c000 = h(x0, y0, z0); val c100 = h(x0 + 1, y0, z0)
        val c010 = h(x0, y0 + 1, z0); val c110 = h(x0 + 1, y0 + 1, z0)
        val c001 = h(x0, y0, z0 + 1); val c101 = h(x0 + 1, y0, z0 + 1)
        val c011 = h(x0, y0 + 1, z0 + 1); val c111 = h(x0 + 1, y0 + 1, z0 + 1)
        val x00 = lerp(c000, c100, fx); val x10 = lerp(c010, c110, fx)
        val x01 = lerp(c001, c101, fx); val x11 = lerp(c011, c111, fx)
        return lerp(lerp(x00, x10, fy), lerp(x01, x11, fy), fz)
    }

    fun fbm(x: Float, z: Float, octaves: Int): Float {
        var total = 0f
        var amp = 0.5f
        var freq = 1f
        var norm = 0f
        for (i in 0 until octaves) {
            total += noise(x * freq, z * freq) * amp
            norm += amp
            amp *= 0.5f
            freq *= 2.05f
        }
        return total / max(norm, 0.0001f)
    }

    /** 0 = frío, 1 = cálido. */
    fun temperature(wx: Int, wz: Int): Float = fbm(wx * 0.0017f + 37f, wz * 0.0017f - 19f, 3)

    /** 0 = seco, 1 = húmedo. */
    fun humidity(wx: Int, wz: Int): Float = fbm(wx * 0.0023f - 71f, wz * 0.0023f + 53f, 3)

    fun biomeAt(wx: Int, wz: Int): Int {
        val h = heightAt(wx, wz)
        val t = temperature(wx, wz)
        val w = humidity(wx, wz)
        if (h > SEA_LEVEL + 22) return if (t < 0.45f) Biomes.SNOWY else if (w > 0.55f) Biomes.TAIGA else Biomes.SNOWY
        if (t > 0.62f && w < 0.42f) return Biomes.DESERT
        if (w > 0.58f) return if (t > 0.60f) Biomes.JUNGLE else Biomes.FOREST
        if (t < 0.35f) return Biomes.TAIGA
        if (t < 0.45f) return Biomes.SNOWY
        return Biomes.PLAINS
    }

    fun heightAt(wx: Int, wz: Int): Int {
        val continent = fbm(wx * 0.0045f, wz * 0.0045f, 4)
        val hills = fbm(wx * 0.028f + 40f, wz * 0.028f - 17f, 4)
        val detail = noise(wx * 0.11f, wz * 0.11f)
        // Mountains only where the continent noise is high, like real terrain generators.
        val mountain = if (continent > 0.62f) (continent - 0.62f) * 90f else 0f
        var h = 22f + continent * 30f + hills * 12f + detail * 2.5f + mountain
        if (h < SEA_LEVEL) h = SEA_LEVEL - (SEA_LEVEL - h) * 0.55f
        return h.toInt().coerceIn(1, World.HEIGHT - 12)
    }

    /** Biomes for a whole chunk, cached by the mesher. */
    fun biomes(cx: Int, cz: Int): IntArray {
        val out = IntArray(256)
        for (lz in 0 until 16) for (lx in 0 until 16) {
            out[lz * 16 + lx] = biomeAt(cx * 16 + lx, cz * 16 + lz)
        }
        return out
    }

    fun generate(chunk: Chunk) {
        if (chunk.generated) return
        val baseX = chunk.cx * 16
        val baseZ = chunk.cz * 16
        val H = World.HEIGHT

        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                val wx = baseX + lx
                val wz = baseZ + lz
                val h = heightAt(wx, wz)
                val biome = biomeAt(wx, wz)
                val beach = h <= SEA_LEVEL + 1
                val peak = h > SEA_LEVEL + 26

                for (y in 0..h) {
                    val block = when {
                        y == 0 -> "bedrock"
                        y == h && (biome == Biomes.SNOWY || peak) -> "snow"
                        y == h && (biome == Biomes.DESERT || beach) -> "sand"
                        y == h -> "grass"
                        y > h - 4 -> when {
                            biome == Biomes.DESERT || beach -> "sand"
                            biome == Biomes.SNOWY -> "dirt"
                            else -> "dirt"
                        }
                        y > h - 8 && (biome == Biomes.DESERT) -> "sandstone"
                        else -> "stone"
                    }
                    chunk.set(lx, y, lz, Blocks.index(block))
                }

                // Caves: the three 1.18+ noise-cave types (cheese, spaghetti, noodle).
                for (y in 2 until max(3, h - 3)) {
                    if (y >= H) break
                    if (!caveAt(wx, y, wz)) continue
                    // Lava lakes fill the deepest part of the world, like y < -54 in Java.
                    chunk.set(lx, y, lz, if (y <= 8) Blocks.index("lava") else Blocks.AIR)
                }

                // Ore pockets.
                for (y in 2 until (h - 4)) {
                    if (y >= H) break
                    val r = hash3(wx, y, wz)
                    if (r < 0.012f) {
                        val block = when {
                            r < 0.0022f -> "diamond_ore"
                            r < 0.0042f -> "gold_ore"
                            r < 0.0080f -> "iron_ore"
                            else -> "coal_ore"
                        }
                        chunk.set(lx, y, lz, Blocks.index(block))
                    }
                }

                for (y in (h + 1) until SEA_LEVEL) {
                    chunk.set(lx, y, lz, Blocks.index("water"))
                }
            }
        }

        // Some caves break the surface: keep a thin skin so the player never spawns in a hole.
        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                val top = heightAt(baseX + lx, baseZ + lz)
                if (top + 1 < H && chunk.get(lx, top, lz) == Blocks.AIR && top > SEA_LEVEL) {
                    val biome = biomeAt(baseX + lx, baseZ + lz)
                    val fill = when {
                        biome == Biomes.DESERT -> "sand"
                        biome == Biomes.SNOWY -> "snow"
                        else -> "grass"
                    }
                    chunk.set(lx, top, lz, Blocks.index(fill))
                }
            }
        }

        maybeRuins(chunk)
        decorate(chunk)
        Mobs.spawnInChunk(chunk, this)
        chunk.maxY = chunk.blocks.indexOfLast { it != 0.toShort() }.let { idx ->
            if (idx < 0) 0 else idx / (16 * 16)
        }
        chunk.generated = true
        chunk.dirty = true
    }

    /**
     * Minecraft 1.18+ noise caves, rebuilt from the wiki's description:
     *
     * * **Cheese** — one low-frequency 3D field; everything above the threshold is carved, giving
     *   the big blobby caverns.
     * * **Spaghetti** — two independent fields; a tunnel exists where *both* are close to their
     *   midpoint, so the intersection is a long thin tube.
     * * **Noodle** — the same idea with a much higher frequency and a tighter threshold, which
     *   produces the rare hair-thin passages.
     *
     * Thresholds are tuned so roughly 1 block in 90 is cave, close to the overworld density.
     */
    fun caveAt(wx: Int, y: Int, wz: Int): Boolean {
        if (y < 2) return false
        val fy = y.toFloat()
        // Cheese caves.
        if (noise3(wx * 0.021f + 11f, fy * 0.028f - 7f, wz * 0.021f + 5f) > 0.80f) return true
        // Spaghetti caves.
        val s1 = noise3(wx * 0.041f - 23f, fy * 0.052f + 31f, wz * 0.041f + 17f)
        val s2 = noise3(wx * 0.041f + 61f, fy * 0.052f - 13f, wz * 0.041f - 43f)
        if (abs(s1 - 0.5f) < 0.020f && abs(s2 - 0.5f) < 0.020f) return true
        // Noodle caves.
        val n1 = noise3(wx * 0.075f + 91f, fy * 0.095f + 7f, wz * 0.075f - 29f)
        val n2 = noise3(wx * 0.075f - 53f, fy * 0.095f - 61f, wz * 0.075f + 37f)
        return abs(n1 - 0.5f) < 0.010f && abs(n2 - 0.5f) < 0.010f
    }

    /**
     * Small abandoned ruins: a cobblestone floor with broken pillars and a torch. They fit inside
     * a single chunk, so no cross-chunk bookkeeping is needed.
     */
    private fun maybeRuins(chunk: Chunk) {
        val region = 7
        val rx = Math.floorDiv(chunk.cx, region)
        val rz = Math.floorDiv(chunk.cz, region)
        if (hash(rx * 73 + 11, rz * 97 - 5) > 0.22f) return

        val lx = 4 + (hash(rx * 13 + 3, rz * 17 + 9) * 7).toInt()
        val lz = 4 + (hash(rx * 23 + 5, rz * 31 + 1) * 7).toInt()
        val wx = chunk.cx * 16 + lx
        val wz = chunk.cz * 16 + lz
        val ground = heightAt(wx, wz)
        if (ground <= SEA_LEVEL + 1) return
        val biome = biomeAt(wx, wz)
        if (biome == Biomes.SNOWY) return

        val cobble = Blocks.index("cobblestone")
        val planks = Blocks.index("planks")

        // Floor (5x5 with holes) and four pillars of different heights.
        for (dx in -2..2) {
            for (dz in -2..2) {
                val tx = lx + dx
                val tz = lz + dz
                if (tx !in 0..15 || tz !in 0..15) continue
                val y = ground
                if (Blocks.get(chunk.get(tx, y, tz)).id == "grass" ||
                    Blocks.get(chunk.get(tx, y, tz)).id == "sand"
                ) {
                    if (hash(wx + dx * 7, wz + dz * 11) > 0.22f) {
                        chunk.set(tx, y, tz, if (hash(wx + dx, wz + dz) > 0.6f) planks else cobble)
                    }
                }
            }
        }
        val corners = arrayOf(intArrayOf(-2, -2), intArrayOf(2, -2), intArrayOf(-2, 2), intArrayOf(2, 2))
        for ((index, c) in corners.withIndex()) {
            val tx = lx + c[0]
            val tz = lz + c[1]
            if (tx !in 0..15 || tz !in 0..15) continue
            val height = 1 + (hash(wx + c[0] * 31, wz + c[1] * 17) * 3).toInt()
            for (i in 1..height) {
                if (ground + i < World.HEIGHT) chunk.set(tx, ground + i, tz, cobble)
            }
            if (index == 0 && height >= 2) {
                chunk.set(tx, ground + height + 1, tz, Blocks.index("torch"))
            }
        }
    }

    private fun decorate(chunk: Chunk) {
        val baseX = chunk.cx * 16
        val baseZ = chunk.cz * 16

        fun ground(lx: Int, lz: Int): Int {
            for (y in World.HEIGHT - 1 downTo 0) {
                val b = chunk.get(lx, y, lz)
                if (b != Blocks.AIR) return y
            }
            return 0
        }

        // Trees. Kept 2 blocks away from chunk borders so a whole tree fits inside.
        for (lz in 2..13) {
            for (lx in 2..13) {
                val wx = baseX + lx
                val wz = baseZ + lz
                val biome = biomeAt(wx, wz)
                val density = when (biome) {
                    Biomes.FOREST -> 0.030f
                    Biomes.JUNGLE -> 0.034f
                    Biomes.TAIGA -> 0.024f
                    Biomes.SNOWY -> 0.010f
                    Biomes.PLAINS -> 0.005f
                    else -> 0f
                }
                if (density <= 0f) continue
                if (hash(wx * 17 + 3, wz * 31 - 7) > density) continue
                val gy = ground(lx, lz)
                val groundBlock = chunk.get(lx, gy, lz)
                if (groundBlock != Blocks.index("grass") && groundBlock != Blocks.index("dirt")) continue

                val roll = hash(wx * 7 + 11, wz * 13 + 5)
                when (biome) {
                    Biomes.SNOWY, Biomes.TAIGA -> spruce(chunk, lx, gy, lz, wx, wz)
                    Biomes.JUNGLE -> if (roll < 0.35f) jungle(chunk, lx, gy, lz, wx, wz)
                    else oak(chunk, lx, gy, lz, wx, wz, roll > 0.55f)
                    Biomes.FOREST -> if (roll < 0.30f) birch(chunk, lx, gy, lz, wx, wz)
                    else oak(chunk, lx, gy, lz, wx, wz, roll > 0.75f)
                    else -> oak(chunk, lx, gy, lz, wx, wz, false)
                }
            }
        }

        // Small plants, cacti and sugar cane.
        for (lz in 0..15) {
            for (lx in 0..15) {
                val wx = baseX + lx
                val wz = baseZ + lz
                val biome = biomeAt(wx, wz)
                val gy = ground(lx, lz)
                if (gy + 1 >= World.HEIGHT) continue
                val groundBlock = chunk.get(lx, gy, lz)
                if (chunk.get(lx, gy + 1, lz) != Blocks.AIR) continue
                val noise = hash(wx * 31 + 7, wz * 17 - 3)

                if (groundBlock == Blocks.index("sand")) {
                    if (biome == Biomes.DESERT) {
                        if (noise < 0.006f) {
                            val height = 1 + (hash(wx, wz) * 3).toInt()
                            for (i in 1..height) chunk.set(lx, gy + i, lz, Blocks.index("cactus"))
                            continue
                        }
                        if (noise < 0.020f) {
                            chunk.set(lx, gy + 1, lz, Blocks.index("dead_bush"))
                            continue
                        }
                    }
                    // Sugar cane next to water.
                    val nearWater = chunk.get(lx - 1, gy, lz) == Blocks.index("water") ||
                        chunk.get(lx + 1, gy, lz) == Blocks.index("water") ||
                        chunk.get(lx, gy, lz - 1) == Blocks.index("water") ||
                        chunk.get(lx, gy, lz + 1) == Blocks.index("water")
                    if (nearWater && noise < 0.45f && gy <= SEA_LEVEL + 1) {
                        val height = 1 + (hash(wx * 3, wz * 5) * 3).toInt()
                        for (i in 1..height) chunk.set(lx, gy + i, lz, Blocks.index("sugar_cane"))
                    }
                    continue
                }

                if (groundBlock != Blocks.index("grass") || biome == Biomes.SNOWY) continue
                when {
                    noise < 0.115f -> chunk.set(lx, gy + 1, lz, Blocks.index("tall_grass"))
                    noise < 0.125f -> chunk.set(lx, gy + 1, lz, Blocks.index("poppy"))
                    noise < 0.135f -> chunk.set(lx, gy + 1, lz, Blocks.index("dandelion"))
                }
            }
        }
    }

    private fun leafBlob(chunk: Chunk, cx: Int, cy: Int, cz: Int, radius: Int, leaf: String, wx: Int, wz: Int) {
        val leafIndex = Blocks.index(leaf)
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    val d = dx * dx + dz * dz + dy * dy * 2
                    if (d > radius * radius + radius) continue
                    if (dx == 0 && dz == 0 && dy < 0) continue
                    val tx = cx + dx
                    val ty = cy + dy
                    val tz = cz + dz
                    if (tx in 0..15 && tz in 0..15 && ty in 0 until World.HEIGHT) {
                        if (chunk.get(tx, ty, tz) == Blocks.AIR &&
                            hash3(wx + dx, ty, wz + dz) > 0.06f
                        ) {
                            chunk.set(tx, ty, tz, leafIndex)
                        }
                    }
                }
            }
        }
    }

    private fun oak(chunk: Chunk, lx: Int, gy: Int, lz: Int, wx: Int, wz: Int, big: Boolean) {
        val logIndex = Blocks.index("log")
        val trunk = 4 + (hash(wx, wz) * 3).toInt() + if (big) 2 else 0
        for (i in 1..trunk) chunk.set(lx, gy + i, lz, logIndex)
        leafBlob(chunk, lx, gy + trunk, lz, if (big) 3 else 2, "leaves", wx, wz)
    }

    private fun birch(chunk: Chunk, lx: Int, gy: Int, lz: Int, wx: Int, wz: Int) {
        val logIndex = Blocks.index("birch_log")
        val trunk = 5 + (hash(wx, wz) * 3).toInt()
        for (i in 1..trunk) chunk.set(lx, gy + i, lz, logIndex)
        leafBlob(chunk, lx, gy + trunk, lz, 2, "birch_leaves", wx, wz)
    }

    private fun jungle(chunk: Chunk, lx: Int, gy: Int, lz: Int, wx: Int, wz: Int) {
        val logIndex = Blocks.index("log")
        val trunk = 6 + (hash(wx, wz) * 4).toInt()
        for (i in 1..trunk) chunk.set(lx, gy + i, lz, logIndex)
        leafBlob(chunk, lx, gy + trunk, lz, 3, "leaves", wx, wz)
    }

    private fun spruce(chunk: Chunk, lx: Int, gy: Int, lz: Int, wx: Int, wz: Int) {
        val logIndex = Blocks.index("spruce_log")
        val leafIndex = Blocks.index("spruce_leaves")
        val trunk = 6 + (hash(wx, wz) * 4).toInt()
        for (i in 1..trunk) chunk.set(lx, gy + i, lz, logIndex)
        // Layered conical canopy.
        var layer = 0
        var radius = 2
        for (y in trunk downTo 3) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    if (dx * dx + dz * dz > radius * radius) continue
                    val tx = lx + dx
                    val tz = lz + dz
                    val ty = gy + y
                    if (tx in 0..15 && tz in 0..15 && ty in 0 until World.HEIGHT) {
                        if (chunk.get(tx, ty, tz) == Blocks.AIR) chunk.set(tx, ty, tz, leafIndex)
                    }
                }
            }
            layer++
            if (layer % 2 == 0) radius = if (radius > 0) radius - 1 else 0
        }
        chunk.set(lx, gy + trunk + 1, lz, leafIndex)
    }

    fun spawnPoint(): Triple<Double, Double, Double> {
        var bestX = 0
        var bestZ = 0
        var bestScore = -1e9f
        for (i in 0 until 96) {
            val ang = i * 0.7f
            val dist = 8 + i * 3f
            val x = (kotlin.math.cos(ang) * dist).toInt()
            val z = (kotlin.math.sin(ang) * dist).toInt()
            val h = heightAt(x, z)
            if (h <= SEA_LEVEL + 2) continue
            val biome = biomeAt(x, z)
            val biomeBonus = when (biome) {
                Biomes.PLAINS -> 6f
                Biomes.FOREST -> 4f
                Biomes.SNOWY -> -6f
                Biomes.DESERT -> -4f
                else -> 0f
            }
            val score = (h - SEA_LEVEL).toFloat() + biomeBonus - dist * 0.2f
            if (score > bestScore) {
                bestScore = score
                bestX = x
                bestZ = z
            }
        }
        val h = max(heightAt(bestX, bestZ), SEA_LEVEL + 2)
        return Triple(bestX + 0.5, h + 1.2, bestZ + 0.5)
    }

    fun highestAt(wx: Int, wz: Int): Int = min(heightAt(wx, wz), World.HEIGHT - 1)
}
