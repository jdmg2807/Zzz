package com.arena.voxel.world

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Seed based value-noise terrain generator (hills, beaches, snow caps, ores and trees). */
class WorldGen(val seed: Long) {

    companion object {
        const val SEA_LEVEL = 32
    }

    private val s = seed.toInt()

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

    fun heightAt(wx: Int, wz: Int): Int {
        val continent = fbm(wx * 0.0045f, wz * 0.0045f, 4)
        val hills = fbm(wx * 0.028f + 40f, wz * 0.028f - 17f, 4)
        val detail = noise(wx * 0.11f, wz * 0.11f)
        var h = 22f + continent * 34f + hills * 12f + detail * 2.5f
        // Flatten the sea floor a little.
        if (h < SEA_LEVEL) h = SEA_LEVEL - (SEA_LEVEL - h) * 0.55f
        return h.toInt().coerceIn(1, World.HEIGHT - 12)
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
                val beach = h <= SEA_LEVEL + 2
                val snowy = h > SEA_LEVEL + 20
                for (y in 0..h) {
                    val block = when {
                        y == 0 -> "bedrock"
                        y == h && snowy -> "snow"
                        y == h && beach -> "sand"
                        y == h -> "grass"
                        y > h - 4 -> if (beach) "sand" else "dirt"
                        else -> "stone"
                    }
                    chunk.set(lx, y, lz, Blocks.index(block))
                }
                // Ore pockets inside the stone.
                for (y in 2 until (h - 4)) {
                    if (y >= H) break
                    val r = hash3(baseX + lx, y, baseZ + lz)
                    if (r < 0.010f) {
                        val block = when {
                            r < 0.0025f -> "diamond_ore"
                            r < 0.0045f -> "gold_ore"
                            r < 0.0075f -> "iron_ore"
                            else -> "coal_ore"
                        }
                        chunk.set(lx, y, lz, Blocks.index(block))
                    }
                }
                // Water up to sea level.
                for (y in (h + 1) until SEA_LEVEL) {
                    chunk.set(lx, y, lz, Blocks.index("water"))
                }
            }
        }

        // Trees (kept 2 blocks away from chunk borders so a whole tree fits inside).
        for (lz in 2..13) {
            for (lx in 2..13) {
                val wx = baseX + lx
                val wz = baseZ + lz
                val r = hash(wx * 17 + 3, wz * 31 - 7)
                if (r > 0.015f) continue
                val h = heightAt(wx, wz)
                if (h <= SEA_LEVEL + 1 || h > SEA_LEVEL + 20) continue
                val ground = chunk.get(lx, h, lz)
                if (ground != Blocks.index("grass")) continue
                val trunk = 4 + (hash(wx, wz) * 3).toInt()
                for (i in 1..trunk) {
                    if (h + i < H) chunk.set(lx, h + i, lz, Blocks.index("log"))
                }
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        for (dz in -2..2) {
                            if (dx * dx + dz * dz + dy * dy > 6) continue
                            if (dx == 0 && dz == 0 && dy <= 0) continue
                            val tx = lx + dx
                            val ty = h + trunk + dy
                            val tz = lz + dz
                            if (tx in 0..15 && tz in 0..15 && ty in 0 until H) {
                                if (chunk.get(tx, ty, tz) == Blocks.AIR) {
                                    chunk.set(tx, ty, tz, Blocks.index("leaves"))
                                }
                            }
                        }
                    }
                }
            }
        }

        chunk.generated = true
        chunk.dirty = true
    }

    fun spawnPoint(): Triple<Double, Double, Double> {
        var bestX = 0
        var bestZ = 0
        var bestScore = -1f
        for (i in 0 until 64) {
            val ang = i * 0.7f
            val dist = 8 + i * 3f
            val x = (kotlin.math.cos(ang) * dist).toInt()
            val z = (kotlin.math.sin(ang) * dist).toInt()
            val h = heightAt(x, z)
            // Prefer dry, grassy land close to the origin.
            val score = (h - SEA_LEVEL).toFloat() - dist * 0.05f
            if (h > SEA_LEVEL + 1 && score > bestScore) {
                bestScore = score
                bestX = x
                bestZ = z
            }
        }
        val h = max(heightAt(bestX, bestZ), SEA_LEVEL + 2)
        return Triple(bestX + 0.5, h + 1.2, bestZ + 0.5)
    }

    fun highestAt(wx: Int, wz: Int): Int {
        val h = heightAt(wx, wz)
        return min(h, World.HEIGHT - 1)
    }
}
