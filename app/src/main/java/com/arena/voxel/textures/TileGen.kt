package com.arena.voxel.textures

import android.graphics.Bitmap
import com.arena.voxel.world.Biomes
import java.util.Random
import kotlin.math.abs

/**
 * Procedural 16x16 tiles in the classic Minecraft style: flat colours, light noise and
 * fully tileable. Tintable tiles (grass, leaves, water, tall grass) are produced once per
 * biome so the mesher never has to tint anything at runtime.
 */
object TileGen {

    private const val S = 16

    private fun clamp(v: Int) = if (v < 0) 0 else if (v > 255) 255 else v

    private fun rgb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)

    private fun mul(color: Int, k: Float): Int {
        val r = ((color shr 16) and 0xff) * k
        val g = ((color shr 8) and 0xff) * k
        val b = (color and 0xff) * k
        return rgb(r.toInt(), g.toInt(), b.toInt(), (color ushr 24) and 0xff)
    }

    private fun toBitmap(px: IntArray): Bitmap =
        Bitmap.createBitmap(px, S, S, Bitmap.Config.ARGB_8888).copy(Bitmap.Config.ARGB_8888, false)

    private fun noise(base: Int, amount: Int, rnd: Random, alpha: Int = 255): IntArray {
        val px = IntArray(S * S)
        for (i in px.indices) {
            val d = rnd.nextInt(amount * 2 + 1) - amount
            px[i] = rgb(((base shr 16) and 0xff) + d, ((base shr 8) and 0xff) + d, (base and 0xff) + d, alpha)
        }
        return px
    }

    private fun put(px: IntArray, x: Int, y: Int, c: Int) {
        if (x in 0 until S && y in 0 until S) px[y * S + x] = c
    }

    private fun base(name: String): IntArray =
        noise(BASE_COLORS[name] ?: 0x808080, 12, Random(name.hashCode().toLong()))

    private val BASE_COLORS = mapOf(
        "stone" to 0x8a8a8a, "dirt" to 0x8b5a2b, "cobblestone" to 0x7d7d7d, "oak_planks" to 0xa9834f,
        "sand" to 0xdbcf94, "gravel" to 0x8a8078, "log_side" to 0x6b4f2a, "log_top" to 0xa2823f,
        "glass" to 0xd7e9f2, "snow" to 0xf2f6fa, "bedrock" to 0x565656, "brick" to 0xa2432f,
        "obsidian" to 0x2a1f3d, "glowstone" to 0xc9a13c, "sandstone" to 0xd8cb92,
        "cactus_side" to 0x0f7a30, "cactus_top" to 0x5ba63c, "spruce_log_side" to 0x3c2a17,
        "spruce_log_top" to 0x6b4f2a, "spruce_leaves" to 0x35543a, "birch_log_side" to 0xd8d4c4,
        "birch_log_top" to 0xc3b98b, "birch_leaves" to 0x80A755, "sugar_cane" to 0x94c96a,
        // v1.6.0
        "stone_bricks" to 0x7a7a7a, "mossy_cobblestone" to 0x6c7a5f, "deepslate" to 0x4a4a50,
        "andesite" to 0x8a8a8d, "granite" to 0x9a6b58, "diorite" to 0xc4c4ca,
        "tuff" to 0x6d6e63, "calcite" to 0xdfdfda, "clay" to 0x9ea4b4,
        "netherrack" to 0x6b2b2b, "ice" to 0x9fc7f0, "packed_ice" to 0x8fb6e8
    )

    /** Names that exist in several biome variants. */
    val TINTED = listOf("grass_top", "grass_side", "leaves", "tall_grass", "water")

    fun make(name: String): Bitmap = when (name) {
        "stone" -> toBitmap(stone())
        "cobblestone" -> toBitmap(cobble())
        "oak_planks" -> toBitmap(planks())
        "sand" -> toBitmap(sand())
        "gravel" -> toBitmap(gravel())
        "log_side" -> toBitmap(logSide())
        "log_top" -> toBitmap(logTop())
        "glass" -> toBitmap(glass())
        "snow" -> toBitmap(snow())
        "bedrock" -> toBitmap(bedrock())
        "brick" -> toBitmap(brick())
        "obsidian" -> toBitmap(obsidian())
        "tnt_side" -> toBitmap(tntSide())
        "tnt_top" -> toBitmap(tntTop())
        "glowstone" -> toBitmap(glowstone())
        "sandstone" -> toBitmap(sandstone())
        "cactus_side" -> toBitmap(cactusSide())
        "cactus_top" -> toBitmap(cactusTop())
        "dead_bush" -> toBitmap(deadBush())
        "dandelion" -> toBitmap(flower(0xF2D21B, 0x2E7A1E))
        "poppy" -> toBitmap(flower(0xD02B1C, 0x2E7A1E))
        "spruce_log_side" -> toBitmap(spruceLogSide())
        "spruce_log_top" -> toBitmap(logTop())
        "spruce_leaves" -> toBitmap(needles(0x33502F))
        "birch_log_side" -> toBitmap(birchLogSide())
        "birch_log_top" -> toBitmap(birchLogTop())
        "birch_leaves" -> toBitmap(needles(0x7DA34A))
        "sugar_cane" -> toBitmap(sugarCane())
        "torch" -> toBitmap(torch())
        "crack_1" -> toBitmap(cracks(1))
        "crack_2" -> toBitmap(cracks(2))
        "crack_3" -> toBitmap(cracks(3))
        "crack_4" -> toBitmap(cracks(4))
        "crack_5" -> toBitmap(cracks(5))
        "crack_6" -> toBitmap(cracks(6))
        "coal_ore" -> toBitmap(ore(0x2e2e2e, 6, 3))
        "iron_ore" -> toBitmap(ore(0xd8b28c, 5, 2))
        "gold_ore" -> toBitmap(ore(0xf5d742, 5, 2))
        "diamond_ore" -> toBitmap(ore(0x6de0d6, 5, 2))
        else -> toBitmap(base(name))
    }

    /** Variant of a tintable tile for the given biome. */
    fun makeTinted(name: String, biome: Int): Bitmap = when (name) {
        "grass_top" -> toBitmap(tint(grayNoise(0xAFAFAF, 16, 1), Biomes.GRASS[biome]))
        "grass_side" -> toBitmap(grassSide(Biomes.GRASS[biome]))
        "leaves" -> toBitmap(tint(leafAlpha(2), Biomes.FOLIAGE[biome]))
        "tall_grass" -> toBitmap(tallGrass(Biomes.GRASS[biome]))
        "water" -> toBitmap(water(Biomes.WATER[biome]))
        else -> toBitmap(base(name))
    }

    // ------------------------------------------------------------------ helpers

    private fun grayNoise(gray: Int, amount: Int, seed: Int): IntArray {
        val rnd = Random(seed.toLong())
        val px = IntArray(S * S)
        for (i in px.indices) {
            val d = rnd.nextInt(amount * 2 + 1) - amount
            val v = clamp(gray + d)
            px[i] = rgb(v, v, v)
        }
        return px
    }

    /** Multiply a grayscale tile by a colour, like Minecraft's biome tint. */
    private fun tint(gray: IntArray, color: Int): IntArray {
        val r = (color shr 16) and 0xff
        val g = (color shr 8) and 0xff
        val b = color and 0xff
        val px = IntArray(S * S)
        for (i in gray.indices) {
            val v = (gray[i] and 0xff) / 255f
            val a = (gray[i] ushr 24) and 0xff
            px[i] = rgb((r * v).toInt(), (g * v).toInt(), (b * v).toInt(), a)
        }
        return px
    }

    private fun leafAlpha(seed: Int): IntArray {
        val rnd = Random(seed.toLong())
        val px = IntArray(S * S)
        for (i in px.indices) {
            val v = 150 + rnd.nextInt(105)
            val a = if (rnd.nextInt(100) < 9) 0 else 255
            px[i] = rgb(v, v, v, a)
        }
        // A few darker clusters for depth.
        for (i in 0 until 22) {
            val x = rnd.nextInt(S)
            val y = rnd.nextInt(S)
            val c = px[y * S + x]
            if ((c ushr 24) == 0) continue
            px[y * S + x] = rgb(((c shr 16) and 0xff) * 4 / 5, ((c shr 8) and 0xff) * 4 / 5, (c and 0xff) * 4 / 5)
        }
        return px
    }

    private fun grassSide(grassColor: Int): IntArray {
        val px = base("dirt")
        val rnd = Random(7)
        for (x in 0 until S) {
            val depth = 3 + rnd.nextInt(3)
            for (y in 0 until depth) {
                val k = 0.82f + rnd.nextFloat() * 0.28f
                px[y * S + x] = mul(grassColor, k)
            }
        }
        return px
    }

    private fun tallGrass(color: Int): IntArray {
        val px = IntArray(S * S) { 0 }
        val rnd = Random(13)
        for (blade in 0 until 7) {
            val x0 = 1 + rnd.nextInt(14)
            val h = 6 + rnd.nextInt(9)
            var x = x0
            for (i in 0 until h) {
                val y = 15 - i
                if (x in 0 until S) {
                    px[y * S + x] = mul(color, 0.72f + rnd.nextFloat() * 0.45f)
                    if (rnd.nextBoolean() && x + 1 < S) px[y * S + x + 1] = mul(color, 0.66f + rnd.nextFloat() * 0.3f)
                }
                if (rnd.nextInt(6) == 0) x += if (rnd.nextBoolean()) 1 else -1
            }
        }
        return px
    }

    private fun water(color: Int): IntArray {
        val rnd = Random(101)
        val px = IntArray(S * S)
        for (y in 0 until S) for (x in 0 until S) {
            val wave = ((x + y * 2) % 8) < 3
            val k = if (wave) 0.95f else 0.82f
            px[y * S + x] = (180 shl 24) or (mul(color, k * (0.95f + rnd.nextFloat() * 0.1f)) and 0xffffff)
        }
        return px
    }

    private fun flower(petal: Int, stem: Int): IntArray {
        val px = IntArray(S * S) { 0 }
        for (y in 8..15) px[y * S + 7] = mul(stem, 0.9f)
        put(px, 6, 12, stem)
        put(px, 8, 13, stem)
        for (y in 3..7) for (x in 5..9) {
            val d = abs(x - 7) + abs(y - 5)
            if (d <= 2) px[y * S + x] = mul(petal, 0.85f + (d * 0.05f))
        }
        put(px, 7, 5, 0xFFFFF0.toInt())
        return px
    }

    private fun deadBush(): IntArray {
        val px = IntArray(S * S) { 0 }
        val rnd = Random(29)
        val c = 0x6b4f2a
        for (b in 0 until 5) {
            var x = 4 + rnd.nextInt(8)
            for (y in 15 downTo 6 + rnd.nextInt(4)) {
                put(px, x, y, mul(c, 0.8f + rnd.nextFloat() * 0.4f))
                if (rnd.nextInt(4) == 0) x += if (rnd.nextBoolean()) 1 else -1
            }
        }
        return px
    }

    private fun sugarCane(): IntArray {
        val px = IntArray(S * S) { 0 }
        val rnd = Random(37)
        for (x in intArrayOf(2, 7, 12)) {
            for (y in 0 until S) {
                if (rnd.nextInt(10) < 9) px[y * S + x] = mul(0x94c96a, 0.8f + rnd.nextFloat() * 0.35f)
            }
            for (y in 2 until S step 5) put(px, x + 1, y, mul(0x7cae55, 0.9f))
        }
        return px
    }

    private fun torch(): IntArray {
        val px = IntArray(S * S) { 0 }
        for (y in 6 until S) {
            put(px, 7, y, rgb(140, 100, 52))
            put(px, 8, y, rgb(108, 76, 38))
        }
        // Flame.
        for (y in 2..5) for (x in 6..9) {
            val d = abs(x - 7.5f) + abs(y - 3.5f)
            if (d < 2.6f) put(px, x, y, rgb(255, 170, 40 + (d * 30).toInt()))
        }
        put(px, 7, 3, rgb(255, 240, 150))
        put(px, 8, 3, rgb(255, 226, 120))
        put(px, 7, 2, rgb(255, 210, 90))
        return px
    }

    /** Progressive mining cracks; stage grows from 1 to 6. */
    private fun cracks(stage: Int): IntArray {
        val px = IntArray(S * S) { 0 }
        val rnd = Random((stage * 31 + 7).toLong())
        val lines = stage * 3
        repeat(lines) {
            var x = 2 + rnd.nextInt(12)
            var y = 2 + rnd.nextInt(12)
            val steps = 3 + rnd.nextInt(4)
            for (i in 0 until steps) {
                if (x in 0 until S && y in 0 until S) {
                    px[y * S + x] = rgb(20, 20, 20, 210)
                    if (rnd.nextBoolean()) px[y * S + (x + 1).coerceAtMost(S - 1)] = rgb(30, 30, 30, 150)
                }
                x += rnd.nextInt(3) - 1
                y += rnd.nextInt(3) - 1
            }
        }
        return px
    }

    private fun sandstone(): IntArray {
        val px = base("sandstone")
        for (y in 4..5) for (x in 0 until S) px[y * S + x] = mul(0xbfa971, 1f)
        for (y in 11..12) for (x in 0 until S) px[y * S + x] = mul(0xbfa971, 1f)
        return px
    }

    private fun cactusSide(): IntArray {
        val px = base("cactus_side")
        val rnd = Random(43)
        for (x in 0 until S) {
            px[x] = mul(0x0b5a22, 1f)
            px[15 * S + x] = mul(0x0b5a22, 1f)
        }
        for (i in 0 until 26) {
            val x = 2 + rnd.nextInt(12)
            val y = 1 + rnd.nextInt(14)
            put(px, x, y, mul(0x6fbb46, 1f))
            put(px, x + 1, y, mul(0x3f8c2c, 1f))
        }
        return px
    }

    private fun cactusTop(): IntArray {
        val px = base("cactus_top")
        val rnd = Random(47)
        for (y in 0 until S) for (x in 0 until S) {
            val edge = x == 0 || y == 0 || x == 15 || y == 15
            px[y * S + x] = if (edge) mul(0x0b5a22, 1f) else mul(0x5ba63c, 0.9f + rnd.nextFloat() * 0.2f)
        }
        return px
    }

    private fun spruceLogSide(): IntArray {
        val px = base("spruce_log_side")
        val rnd = Random(53)
        for (x in 0 until S) {
            val tone = 0.8f + rnd.nextFloat() * 0.3f
            for (y in 0 until S) {
                px[y * S + x] = if ((x + y / 4) % 4 == 0) mul(0x24170c, tone) else mul(0x3c2a17, tone)
            }
        }
        return px
    }

    private fun birchLogSide(): IntArray {
        val px = base("birch_log_side")
        val rnd = Random(59)
        for (i in 0 until 12) {
            val x = rnd.nextInt(S)
            val y = rnd.nextInt(S)
            for (d in 0 until 2 + rnd.nextInt(3)) put(px, x, y + d, mul(0x3a3a34, 1f))
        }
        return px
    }

    private fun birchLogTop(): IntArray {
        val px = base("birch_log_top")
        val rnd = Random(61)
        for (y in 0 until S) for (x in 0 until S) {
            val d = abs(x - 7.5) + abs(y - 7.5)
            px[y * S + x] = mul(if ((d.toInt() / 2) % 2 == 0) 0xd8cf9c else 0xb8ab74, 0.95f + rnd.nextFloat() * 0.1f)
        }
        return px
    }

    private fun needles(color: Int): IntArray {
        val px = IntArray(S * S)
        val rnd = Random(color.toLong())
        for (i in px.indices) {
            val k = 0.7f + rnd.nextFloat() * 0.5f
            px[i] = if (rnd.nextInt(100) < 6) 0 else mul(color, k)
        }
        return px
    }

    private fun stone(): IntArray {
        val px = base("stone")
        val rnd = Random(21)
        for (i in 0 until 14) {
            val x = rnd.nextInt(S)
            val y = rnd.nextInt(S)
            val c = mul(0x8a8a8a, 0.82f + rnd.nextFloat() * 0.3f)
            put(px, x, y, c)
            put(px, (x + 1) % S, y, mul(c, 0.96f))
            put(px, x, (y + 1) % S, mul(c, 0.93f))
        }
        return px
    }

    private fun cobble(): IntArray {
        val px = base("stone")
        val rnd = Random(33)
        for (gy in 0 until 4) for (gx in 0 until 4) {
            val ox = gx * 4
            val oy = gy * 4
            val tone = 0.75f + rnd.nextFloat() * 0.45f
            val w = if (gx == 3) 4 else 3 + rnd.nextInt(2)
            val h = if (gy == 3) 4 else 3 + rnd.nextInt(2)
            for (y in oy until (oy + h).coerceAtMost(S)) {
                for (x in ox until (ox + w).coerceAtMost(S)) {
                    if (x == ox && y == oy) px[y * S + x] = mul(0x4a4a4a, 1f)
                    else px[y * S + x] = mul(0x8f8f8f, tone * (0.92f + rnd.nextFloat() * 0.16f))
                }
            }
        }
        return px
    }

    private fun planks(): IntArray {
        val px = base("oak_planks")
        val rnd = Random(41)
        for (y in 0 until S) {
            val board = y / 4
            val tone = if (board % 2 == 0) 1.0f else 0.94f
            for (x in 0 until S) {
                var c = mul(0xa9834f, tone * (0.94f + rnd.nextFloat() * 0.12f))
                if (y % 4 == 3) c = mul(c, 0.78f)
                if ((board * 7 + 3) % 16 == x) c = mul(c, 0.86f)
                px[y * S + x] = c
            }
        }
        return px
    }

    private fun sand(): IntArray {
        val px = base("sand")
        val rnd = Random(51)
        for (i in 0 until 26) put(px, rnd.nextInt(S), rnd.nextInt(S), mul(0xe3d7a1, 0.9f + rnd.nextFloat() * 0.15f))
        return px
    }

    private fun gravel(): IntArray {
        val px = base("gravel")
        val rnd = Random(61)
        for (i in 0 until 40) {
            val c = when (rnd.nextInt(3)) {
                0 -> 0x6f655c
                1 -> 0xa39a91
                else -> 0x8b8177
            }
            put(px, rnd.nextInt(S), rnd.nextInt(S), mul(c, 0.9f + rnd.nextFloat() * 0.25f))
        }
        return px
    }

    private fun logSide(): IntArray {
        val px = base("log_side")
        val rnd = Random(71)
        for (x in 0 until S) {
            val tone = 0.85f + rnd.nextFloat() * 0.25f
            for (y in 0 until S) {
                if ((x + y / 3) % 5 == 0) px[y * S + x] = mul(0x4d3619, tone)
                else px[y * S + x] = mul(0x6b4f2a, tone * (0.94f + rnd.nextFloat() * 0.1f))
            }
        }
        return px
    }

    private fun logTop(): IntArray {
        val px = base("log_top")
        val rnd = Random(81)
        for (y in 0 until S) for (x in 0 until S) {
            val d = abs(x - 7.5) + abs(y - 7.5)
            val ring = (d.toInt() / 2) % 2 == 0
            px[y * S + x] = mul(if (ring) 0xa2823f else 0x8a6d32, 0.94f + rnd.nextFloat() * 0.12f)
        }
        return px
    }

    private fun glass(): IntArray {
        val px = IntArray(S * S) { rgb(0, 0, 0, 0) }
        for (i in 0 until S) {
            px[i] = rgb(210, 230, 240, 150)
            px[(S - 1) * S + i] = rgb(210, 230, 240, 150)
            px[i * S] = rgb(210, 230, 240, 150)
            px[i * S + (S - 1)] = rgb(230, 245, 255, 190)
        }
        for (i in 2 until 7) put(px, i + 1, i, rgb(255, 255, 255, 120))
        return px
    }

    private fun snow(): IntArray {
        val px = base("snow")
        val rnd = Random(111)
        for (i in 0 until 18) put(px, rnd.nextInt(S), rnd.nextInt(S), rgb(255, 255, 255))
        return px
    }

    private fun bedrock(): IntArray {
        val px = base("bedrock")
        val rnd = Random(121)
        for (i in 0 until 40) {
            val x = rnd.nextInt(S)
            val y = rnd.nextInt(S)
            val c = if (rnd.nextBoolean()) 0x3a3a3a else 0x757575
            put(px, x, y, c)
            put(px, (x + 1) % S, y, mul(c, 0.9f))
        }
        return px
    }

    private fun brick(): IntArray {
        val px = IntArray(S * S)
        val rnd = Random(131)
        for (y in 0 until S) for (x in 0 until S) {
            val row = y / 4
            val offset = if (row % 2 == 0) 0 else 4
            val mortar = y % 4 == 3 || (x + offset) % 8 == 7
            px[y * S + x] = if (mortar) rgb(190, 185, 175)
            else mul(0xa2432f, 0.9f + rnd.nextFloat() * 0.2f)
        }
        return px
    }

    private fun obsidian(): IntArray {
        val px = base("obsidian")
        val rnd = Random(141)
        for (i in 0 until 26) {
            val c = if (rnd.nextBoolean()) 0x3d2f57 else 0x1b1428
            put(px, rnd.nextInt(S), rnd.nextInt(S), c)
        }
        return px
    }

    private fun tntSide(): IntArray {
        val px = base("tnt_side")
        for (y in 5 until 11) for (x in 0 until S) {
            px[y * S + x] = if (y == 5 || y == 10) rgb(60, 60, 60) else rgb(235, 235, 235)
        }
        val letters = intArrayOf(0b110, 0b010, 0b010)
        for (x in 3 until 6) for (y in 6 until 9) {
            if ((letters[y - 6] shr (5 - x)) and 1 == 1) px[y * S + x] = rgb(30, 30, 30)
        }
        return px
    }

    private fun tntTop(): IntArray {
        val px = base("tnt_top")
        for (i in 0 until S) {
            px[i] = rgb(80, 80, 80)
            px[(S - 1) * S + i] = rgb(80, 80, 80)
            px[i * S] = rgb(80, 80, 80)
            px[i * S + S - 1] = rgb(80, 80, 80)
        }
        for (y in 5 until 11) for (x in 5 until 11) px[y * S + x] = rgb(45, 45, 45)
        return px
    }

    private fun glowstone(): IntArray {
        val px = base("glowstone")
        val rnd = Random(151)
        for (i in 0 until 34) put(px, rnd.nextInt(S), rnd.nextInt(S), rgb(255, 226, 130))
        return px
    }

    private fun ore(gem: Int, clusters: Int, size: Int): IntArray {
        val px = stone()
        val rnd = Random(gem.toLong())
        repeat(clusters) {
            val cx = 2 + rnd.nextInt(12)
            val cy = 2 + rnd.nextInt(12)
            for (dy in -size..size) for (dx in -size..size) {
                if (dx * dx + dy * dy <= size * size && rnd.nextInt(4) != 0) {
                    put(px, cx + dx, cy + dy, mul(gem, 0.85f + rnd.nextFloat() * 0.3f))
                }
            }
        }
        return px
    }

    /** Texture used when a mod registers a block with a colour but no PNG. */
    fun solid(color: Int): Bitmap {
        val rnd = Random(color.toLong())
        val px = IntArray(S * S) { mul(color, 0.94f + rnd.nextFloat() * 0.12f) }
        return toBitmap(px)
    }
}
