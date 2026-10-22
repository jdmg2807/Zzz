package com.arena.voxel.textures

import android.graphics.Bitmap
import java.util.Random
import kotlin.math.abs

/**
 * Procedural 16x16 tiles, drawn in the classic Minecraft style: flat, noisy,
 * slightly desaturated and fully tileable.
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

    private fun base(name: String): IntArray = noise(BASE_COLORS[name] ?: 0x808080, 12, Random(name.hashCode().toLong()))

    private val BASE_COLORS = mapOf(
        "stone" to 0x8a8a8a, "dirt" to 0x8b5a2b, "grass_top" to 0x74a63a, "grass_side" to 0x8b5a2b,
        "cobblestone" to 0x7d7d7d, "oak_planks" to 0xa9834f, "sand" to 0xdbcf94, "gravel" to 0x8a8078,
        "log_side" to 0x6b4f2a, "log_top" to 0xa2823f, "leaves" to 0x3f7d24, "water" to 0x3b6fd4,
        "glass" to 0xd7e9f2, "snow" to 0xf2f6fa, "snow_side" to 0x8b5a2b, "bedrock" to 0x565656,
        "coal_ore" to 0x8a8a8a, "iron_ore" to 0x8a8a8a, "gold_ore" to 0x8a8a8a, "diamond_ore" to 0x8a8a8a,
        "brick" to 0xa2432f, "obsidian" to 0x2a1f3d, "tnt_side" to 0xd4462c, "tnt_top" to 0xd4462c,
        "glowstone" to 0xc9a13c, "oak_leaves" to 0x3f7d24
    )

    fun make(name: String): Bitmap {
        val bmp = when (name) {
            "grass_top" -> toBitmap(grassTop())
            "grass_side" -> toBitmap(grassSide())
            "snow_side" -> toBitmap(snowSide())
            "stone" -> toBitmap(stone())
            "cobblestone" -> toBitmap(cobble())
            "oak_planks" -> toBitmap(planks())
            "sand" -> toBitmap(sand())
            "gravel" -> toBitmap(gravel())
            "log_side" -> toBitmap(logSide())
            "log_top" -> toBitmap(logTop())
            "leaves", "oak_leaves" -> toBitmap(leaves())
            "water" -> toBitmap(water())
            "glass" -> toBitmap(glass())
            "snow" -> toBitmap(snow())
            "bedrock" -> toBitmap(bedrock())
            "brick" -> toBitmap(brick())
            "obsidian" -> toBitmap(obsidian())
            "tnt_side" -> toBitmap(tntSide())
            "tnt_top" -> toBitmap(tntTop())
            "glowstone" -> toBitmap(glowstone())
            "coal_ore" -> toBitmap(ore(0x2e2e2e, 6, 3))
            "iron_ore" -> toBitmap(ore(0xd8b28c, 5, 2))
            "gold_ore" -> toBitmap(ore(0xf5d742, 5, 2))
            "diamond_ore" -> toBitmap(ore(0x6de0d6, 5, 2))
            else -> toBitmap(base(name))
        }
        return bmp
    }

    private fun grassTop(): IntArray {
        val rnd = Random(1)
        val px = IntArray(S * S)
        for (i in px.indices) {
            val r = 100 + rnd.nextInt(40)
            val g = 150 + rnd.nextInt(40)
            val b = 45 + rnd.nextInt(25)
            px[i] = rgb(r, g, b)
        }
        return px
    }

    private fun grassSide(): IntArray {
        val px = base("dirt")
        val rnd = Random(7)
        for (x in 0 until S) {
            val depth = 2 + rnd.nextInt(3)
            for (y in 0 until depth) {
                val g = 120 + rnd.nextInt(45)
                px[y * S + x] = rgb(g / 2, g, 40 + rnd.nextInt(25))
            }
        }
        return px
    }

    private fun snowSide(): IntArray {
        val px = base("dirt")
        val rnd = Random(11)
        for (x in 0 until S) {
            val depth = 3 + rnd.nextInt(3)
            for (y in 0 until depth) px[y * S + x] = rgb(240, 246, 250)
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
        val cells = ArrayList<IntArray>()
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
            cells.add(intArrayOf(ox, oy))
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

    private fun leaves(): IntArray {
        val px = IntArray(S * S)
        val rnd = Random(91)
        for (i in px.indices) {
            val v = rnd.nextInt(100)
            px[i] = when {
                v < 12 -> rgb(0, 0, 0, 0)
                v < 45 -> rgb(40 + rnd.nextInt(20), 92 + rnd.nextInt(35), 28 + rnd.nextInt(15))
                else -> rgb(52 + rnd.nextInt(24), 120 + rnd.nextInt(40), 36 + rnd.nextInt(18))
            }
        }
        return px
    }

    private fun water(): IntArray {
        val px = IntArray(S * S)
        val rnd = Random(101)
        for (y in 0 until S) for (x in 0 until S) {
            val wave = (((x + y * 2) % 8) < 3)
            val c = if (wave) 0x4a7fe0 else 0x3563c9
            px[y * S + x] = (180 shl 24) or (mul(c, 0.95f + rnd.nextFloat() * 0.1f) and 0xffffff)
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
        for (i in 0 until 34) {
            put(px, rnd.nextInt(S), rnd.nextInt(S), rgb(255, 226, 130))
        }
        return px
    }

    private fun ore(gem: Int, clusters: Int, size: Int): IntArray {
        val px = stone()
        val rnd = Random(gem)
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
