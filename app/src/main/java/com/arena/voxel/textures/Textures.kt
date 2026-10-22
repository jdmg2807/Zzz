package com.arena.voxel.textures

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.arena.voxel.world.Biomes

/**
 * Tile registry + atlas builder + inventory icons.
 *
 * Tiles are addressed by name ("stone", "grass_top", ...). Tintable tiles (grass, leaves,
 * tall grass, water) are stored as one variant per biome, so the mesher can pick the right
 * colours just by adding the biome index — no runtime tinting and no atlas rebuilds.
 * Resource packs override tiles by name (tinted tiles get a single variant for every biome).
 */
object Textures {

    /** Pixels per tile inside the atlas (source textures are scaled to this size). */
    const val TILE = 32
    const val GRID = 32
    const val ATLAS = TILE * GRID

    private class Tile(val name: String, var bitmap: Bitmap, var avg: Int)

    private val tiles = ArrayList<Tile>()
    private val byName = HashMap<String, Int>()
    private val tinted = HashSet<String>()
    private val variantCount = HashMap<String, Int>()
    private var atlas: Bitmap? = null

    /** Plain tiles, in stable atlas order. */
    private val SIMPLE = listOf(
        "dirt", "stone", "cobblestone", "oak_planks", "sand", "gravel", "log_side", "log_top",
        "glass", "snow", "bedrock", "coal_ore", "iron_ore", "gold_ore", "diamond_ore", "brick",
        "obsidian", "tnt_side", "tnt_top", "glowstone", "sandstone", "cactus_side", "cactus_top",
        "dead_bush", "dandelion", "poppy", "spruce_log_side", "spruce_log_top", "spruce_leaves",
        "birch_log_side", "birch_log_top", "birch_leaves", "sugar_cane",
        "torch", "crack_1", "crack_2", "crack_3", "crack_4", "crack_5", "crack_6"
    )

    /** Tiles rendered once per biome. */
    private val TINTED = listOf("grass_top", "grass_side", "leaves", "tall_grass", "water")

    val DEFAULTS: List<String> get() = TINTED + SIMPLE

    fun reset() {
        tiles.clear()
        byName.clear()
        tinted.clear()
        variantCount.clear()
        atlas?.recycle()
        atlas = null
    }

    fun prepareDefaults() {
        reset()
        for (name in TINTED) {
            val base = tiles.size
            byName[name] = base
            tinted.add(name)
            variantCount[name] = Biomes.COUNT
            for (biome in 0 until Biomes.COUNT) {
                val bmp = TileGen.makeTinted(name, biome)
                tiles.add(Tile(if (biome == 0) name else "$name@$biome", bmp, average(bmp)))
            }
        }
        for (name in SIMPLE) {
            byName[name] = tiles.size
            variantCount[name] = 1
            val bmp = TileGen.make(name)
            tiles.add(Tile(name, bmp, average(bmp)))
        }
    }

    fun indexOf(name: String): Int = byName[name] ?: -1

    fun isTinted(name: String): Boolean = tinted.contains(name)

    /** True when this pack texture maps onto a tile the engine actually uses. */
    fun isKnown(name: String): Boolean = byName.containsKey(name)

    /**
     * Rejects textures that would make a block invisible (fully transparent PNGs happen in
     * packs that ship placeholder or overlay art). Returns true when the bitmap is usable.
     */
    fun isUsableTexture(bmp: Bitmap): Boolean {
        var opaque = 0
        var total = 0
        val stepX = (bmp.width / 8).coerceAtLeast(1)
        val stepY = (bmp.height / 8).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                if ((bmp.getPixel(x, y) ushr 24) > 30) opaque++
                total++
                x += stepX
            }
            y += stepY
        }
        return total > 0 && opaque.toFloat() / total > 0.02f
    }

    fun nameOf(index: Int): String = tiles.getOrNull(index)?.name ?: "?"

    /** Replaces a tile from a resource pack. Tinted tiles replace every biome variant. */
    fun replace(name: String, bmp: Bitmap): Boolean {
        val base = byName[name] ?: return false
        val count = variantCount[name] ?: 1
        for (i in 0 until count) {
            val tile = tiles.getOrNull(base + i) ?: continue
            // Tinted tiles need one copy per biome variant so every biome shows the pack art.
            val copy = if (count > 1) bmp.copy(Bitmap.Config.ARGB_8888, false) ?: bmp else bmp
            if (tile.bitmap !== copy) tile.bitmap.recycle()
            tile.bitmap = copy
            tile.avg = average(copy)
        }
        invalidate()
        return true
    }

    /** Adds a new tile (mod textures, unknown pack textures). */
    fun put(name: String, bmp: Bitmap): Int {
        byName[name]?.let { existing ->
            replace(name, bmp)
            return existing
        }
        if (tiles.size >= GRID * GRID) return 0
        invalidate()
        val idx = tiles.size
        tiles.add(Tile(name, bmp, average(bmp)))
        byName[name] = idx
        variantCount[name] = 1
        return idx
    }

    fun putSolid(name: String, color: Int): Int = put(name, TileGen.solid(color))

    fun averageColor(index: Int): Int {
        val t = tiles.getOrNull(index) ?: return 0xFF7F7F7F.toInt()
        return 0xFF000000.toInt() or (t.avg and 0xFFFFFF)
    }

    fun count(): Int = tiles.size

    fun allNames(): List<String> = tiles.map { it.name }

    fun bitmapOf(index: Int): Bitmap? = tiles.getOrNull(index)?.bitmap

    fun decode(bytes: ByteArray, scaleToTile: Boolean = true): Bitmap? {
        val opts = BitmapFactory.Options()
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        val raw = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        if (!scaleToTile || (raw.width == TILE && raw.height == TILE)) return raw
        val scaled = Bitmap.createScaledBitmap(raw, TILE, TILE, false)
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    private fun average(bmp: Bitmap): Int {
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        val stepX = (bmp.width / 8).coerceAtLeast(1)
        val stepY = (bmp.height / 8).coerceAtLeast(1)
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                val c = bmp.getPixel(x, y)
                if ((c ushr 24) > 40) {
                    r += (c shr 16) and 0xff
                    g += (c shr 8) and 0xff
                    b += c and 0xff
                    n++
                }
                x += stepX
            }
            y += stepY
        }
        if (n == 0L) return 0x7f7f7f
        return (((r / n).toInt() and 0xff) shl 16) or (((g / n).toInt() and 0xff) shl 8) or ((b / n).toInt() and 0xff)
    }

    fun invalidate() {
        atlas?.recycle()
        atlas = null
    }

    fun buildAtlas(): Bitmap {
        atlas?.let { return it }
        val out = Bitmap.createBitmap(ATLAS, ATLAS, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
            isDither = false
        }
        val dst = Rect()
        for (i in 0 until tiles.size.coerceAtMost(GRID * GRID)) {
            val col = i % GRID
            val row = i / GRID
            dst.set(col * TILE, row * TILE, (col + 1) * TILE, (row + 1) * TILE)
            canvas.drawBitmap(tiles[i].bitmap, null, dst, paint)
        }
        atlas = out
        return out
    }

    // ------------------------------------------------------------------ icons

    /**
     * Isometric block icon (like the Minecraft hotbar) built from the top and side tiles.
     * Returns null when the tiles are missing.
     */
    fun blockIcon(topTile: Int, sideTile: Int, out: Int = 48): Bitmap? {
        val top = tiles.getOrNull(topTile)?.bitmap ?: return null
        val side = tiles.getOrNull(sideTile)?.bitmap ?: return null
        val s = out
        val px = IntArray(s * s)
        val k = s / 48f

        fun sample(bmp: Bitmap, u: Float, v: Float): Int {
            val x = (u * bmp.width).toInt().coerceIn(0, bmp.width - 1)
            val y = (v * bmp.height).toInt().coerceIn(0, bmp.height - 1)
            return bmp.getPixel(x, y)
        }

        fun shade(c: Int, f: Float): Int {
            val a = (c ushr 24) and 0xff
            val r = (((c shr 16) and 0xff) * f).toInt().coerceIn(0, 255)
            val g = (((c shr 8) and 0xff) * f).toInt().coerceIn(0, 255)
            val b = ((c and 0xff) * f).toInt().coerceIn(0, 255)
            return (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        // Parallelogram: origin + u*e1 + v*e2 (inverted per pixel).
        fun fillQuad(ox: Float, oy: Float, e1x: Float, e1y: Float, e2x: Float, e2y: Float, bmp: Bitmap, f: Float) {
            val det = e1x * e2y - e1y * e2x
            if (kotlin.math.abs(det) < 0.0001f) return
            for (y in 0 until s) {
                for (x in 0 until s) {
                    val dx = x + 0.5f - ox
                    val dy = y + 0.5f - oy
                    val u = (dx * e2y - dy * e2x) / det
                    val v = (dy * e1x - dx * e1y) / det
                    if (u < 0f || u > 1f || v < 0f || v > 1f) continue
                    val c = sample(bmp, u, v)
                    if ((c ushr 24) < 100) continue
                    px[y * s + x] = shade(c, f)
                }
            }
        }

        // Cube in a 48x48 box: top diamond, then the two visible sides.
        val ax = 24f * k; val ay = 1f * k
        val bx = 47f * k; val by = 12f * k
        val cx = 24f * k; val cy = 23f * k
        val dx = 1f * k; val dy = 12f * k
        val lift = 23f * k

        // Left face (D -> C, down)
        fillQuad(dx, dy, cx - dx, cy - dy, 0f, lift, side, 0.74f)
        // Right face (C -> B, down)
        fillQuad(cx, cy, bx - cx, by - cy, 0f, lift, side, 0.56f)
        // Top face (A -> B, A -> D)
        fillQuad(ax, ay, bx - ax, by - ay, dx - ax, dy - ay, top, 1.0f)

        return Bitmap.createBitmap(px, s, s, Bitmap.Config.ARGB_8888)
    }
}
