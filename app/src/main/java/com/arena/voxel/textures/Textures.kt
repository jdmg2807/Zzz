package com.arena.voxel.textures

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect

/**
 * Tile registry + atlas builder.
 *
 * Tiles are addressed by name ("stone", "grass_top", ...). Resource packs override tiles
 * by name, mods append new tiles, and everything ends up in one atlas texture so the whole
 * world can be drawn with a single draw call per chunk.
 */
object Textures {

    /** Pixels per tile inside the atlas (source textures are scaled to this size). */
    const val TILE = 32
    const val GRID = 16
    const val ATLAS = TILE * GRID

    private class Tile(val name: String, var bitmap: Bitmap, var avg: Int)

    private val tiles = ArrayList<Tile>()
    private val byName = HashMap<String, Int>()
    private var atlas: Bitmap? = null

    /** Default tile names, in stable order. */
    val DEFAULTS = listOf(
        "grass_top", "grass_side", "dirt", "stone", "cobblestone", "oak_planks",
        "sand", "gravel", "log_side", "log_top", "leaves", "water",
        "glass", "snow", "snow_side", "bedrock",
        "coal_ore", "iron_ore", "gold_ore", "diamond_ore", "brick", "obsidian",
        "tnt_side", "tnt_top", "glowstone"
    )

    fun reset() {
        tiles.clear()
        byName.clear()
        atlas?.recycle()
        atlas = null
    }

    fun prepareDefaults() {
        reset()
        for (name in DEFAULTS) addTile(name, TileGen.make(name))
    }

    private fun addTile(name: String, bmp: Bitmap): Int {
        val idx = tiles.size
        tiles.add(Tile(name, bmp, average(bmp)))
        byName[name] = idx
        return idx
    }

    fun indexOf(name: String): Int = byName[name] ?: -1

    fun nameOf(index: Int): String = tiles.getOrNull(index)?.name ?: "?"

    /** Replaces an existing tile (resource pack override). Returns false if the name is unknown. */
    fun replace(name: String, bmp: Bitmap): Boolean {
        val idx = byName[name] ?: return false
        val old = tiles[idx]
        if (old.bitmap !== bmp) old.bitmap.recycle()
        old.bitmap = bmp
        old.avg = average(bmp)
        atlas?.recycle()
        atlas = null
        return true
    }

    /**
     * Adds a tile for a mod. If the name already exists it is overridden instead,
     * so mods can also restyle vanilla blocks.
     */
    fun put(name: String, bmp: Bitmap): Int {
        val existing = byName[name]
        if (existing != null) {
            replace(name, bmp)
            return existing
        }
        if (tiles.size >= GRID * GRID) return 0
        atlas?.recycle()
        atlas = null
        return addTile(name, bmp)
    }

    fun putSolid(name: String, color: Int): Int = put(name, TileGen.solid(color))

    fun averageColor(index: Int): Int {
        val t = tiles.getOrNull(index) ?: return 0xFF7F7F7F.toInt()
        return 0xFF000000.toInt() or (t.avg and 0xFFFFFF)
    }

    fun count(): Int = tiles.size

    fun allNames(): List<String> = tiles.map { it.name }

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
}
