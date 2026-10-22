package com.arena.voxel.world

import com.arena.voxel.api.BlockDef
import com.arena.voxel.textures.Textures

class Block(
    val id: String,
    val tileTop: Int,
    val tileSide: Int,
    val tileBottom: Int,
    val solid: Boolean,
    val opaque: Boolean,
    val liquid: Boolean = false,
    val cross: Boolean = false,
    val tintTop: Boolean = false,
    val tintSide: Boolean = false,
    val tintBottom: Boolean = false,
    val color: Int = 0x808080
)

/**
 * Block palette. Index 0 is always air; every other block is addressed by a byte inside
 * the chunk storage, which keeps a 16x16x80 chunk at 20 KB of RAM.
 *
 * Tinted blocks store the *base* atlas index of a multi-biome tile; the mesher adds the
 * biome index when the tint flag for that face is set.
 */
object Blocks {

    val list = ArrayList<Block>()
    private val byId = HashMap<String, Int>()
    private var loaded = false

    val AIR = 0

    /** Clears the palette so a new world can rebuild it (needed when mods change). */
    fun reset() {
        list.clear()
        byId.clear()
        loaded = false
    }

    fun init() {
        if (loaded) return
        loaded = true
        add("air", 0, 0, 0, solid = false, opaque = false, color = 0x000000)

        addTinted("grass", "grass_top", "grass_side", "dirt", tintTop = true, tintSide = true, color = 0x91BD59)
        add("dirt", "dirt", "dirt", "dirt", color = 0x8b5a2b)
        add("stone", "stone", "stone", "stone", color = 0x8a8a8a)
        add("cobblestone", "cobblestone", "cobblestone", "cobblestone", color = 0x7d7d7d)
        add("planks", "oak_planks", "oak_planks", "oak_planks", color = 0xa9834f)
        add("oak_planks", "oak_planks", "oak_planks", "oak_planks", color = 0xa9834f)
        add("sand", "sand", "sand", "sand", color = 0xdbcf94)
        add("sandstone", "sandstone", "sandstone", "sandstone", color = 0xd8cb92)
        add("gravel", "gravel", "gravel", "gravel", color = 0x8a8078)
        add("log", "log_top", "log_side", "log_top", color = 0x6b4f2a)
        addTinted("leaves", "leaves", "leaves", "leaves", opaque = false, tintTop = true, tintSide = true, tintBottom = true, color = 0x59AE30)
        addLiquid("water", "water", color = 0x3F76E4)
        add("glass", "glass", "glass", "glass", opaque = false, color = 0xd7e9f2)
        add("snow", "snow", "snow", "snow", color = 0xf2f6fa)
        add("snow_side", "snow", "snow", "dirt", color = 0xf2f6fa)
        add("bedrock", "bedrock", "bedrock", "bedrock", color = 0x565656)
        add("coal_ore", "coal_ore", "coal_ore", "coal_ore", color = 0x5a5a5a)
        add("iron_ore", "iron_ore", "iron_ore", "iron_ore", color = 0xc0a080)
        add("gold_ore", "gold_ore", "gold_ore", "gold_ore", color = 0xd8b52a)
        add("diamond_ore", "diamond_ore", "diamond_ore", "diamond_ore", color = 0x5fd8cf)
        add("brick", "brick", "brick", "brick", color = 0xa2432f)
        add("obsidian", "obsidian", "obsidian", "obsidian", color = 0x2a1f3d)
        add("tnt", "tnt_top", "tnt_side", "tnt_top", color = 0xd4462c)
        add("glowstone", "glowstone", "glowstone", "glowstone", color = 0xc9a13c)
        add("cactus", "cactus_top", "cactus_side", "cactus_top", color = 0x2f7a35)
        add("spruce_log", "spruce_log_top", "spruce_log_side", "spruce_log_top", color = 0x3c2a17)
        add("spruce_leaves", "spruce_leaves", "spruce_leaves", "spruce_leaves", opaque = false, color = 0x33502F)
        add("birch_log", "birch_log_top", "birch_log_side", "birch_log_top", color = 0xd8d4c4)
        add("birch_leaves", "birch_leaves", "birch_leaves", "birch_leaves", opaque = false, color = 0x7DA34A)

        // Cross-shaped plants (no culling, alpha tested).
        addCross("dead_bush", "dead_bush", color = 0x6b4f2a)
        addTinted("tall_grass", "tall_grass", "tall_grass", "tall_grass", opaque = false, cross = true, tintTop = true, tintSide = true, tintBottom = true, color = 0x91BD59)
        addCross("dandelion", "dandelion", color = 0xF2D21B)
        addCross("poppy", "poppy", color = 0xD02B1C)
        addCross("sugar_cane", "sugar_cane", color = 0x94c96a)
    }

    private fun tile(name: String): Int {
        val i = Textures.indexOf(name)
        return if (i >= 0) i else 0
    }

    private fun add(
        id: String,
        top: String, side: String, bottom: String,
        solid: Boolean = true, opaque: Boolean = true, color: Int
    ) {
        add(id, tile(top), tile(side), tile(bottom), solid, opaque, false, false, color)
    }

    private fun addTinted(
        id: String,
        top: String, side: String, bottom: String,
        solid: Boolean = true, opaque: Boolean = true, cross: Boolean = false,
        tintTop: Boolean = false, tintSide: Boolean = false, tintBottom: Boolean = false,
        color: Int
    ) {
        add(
            id, tile(top), tile(side), tile(bottom), solid, opaque, false, cross, color,
            tintTop, tintSide, tintBottom
        )
    }

    private fun addLiquid(id: String, texture: String, color: Int) {
        add(id, tile(texture), tile(texture), tile(texture), false, false, true, false, color, true, true, true)
    }

    private fun addCross(id: String, texture: String, color: Int) {
        add(id, tile(texture), tile(texture), tile(texture), false, false, false, true, color)
    }

    private fun add(
        id: String, top: Int, side: Int, bottom: Int,
        solid: Boolean, opaque: Boolean, liquid: Boolean = false, cross: Boolean = false, color: Int,
        tintTop: Boolean = false, tintSide: Boolean = false, tintBottom: Boolean = false
    ) {
        if (byId.containsKey(id)) return
        byId[id] = list.size
        list.add(
            Block(id, top, side, bottom, solid, opaque, liquid, cross, tintTop, tintSide, tintBottom, color)
        )
    }

    /** Registers a block declared by a mod. Returns its palette index. */
    fun registerMod(def: BlockDef): Int {
        val existing = byId[def.id]
        if (existing != null) return existing
        val texIndex = def.textureName?.let { Textures.indexOf(it) } ?: -1
        val top: Int
        val side: Int
        val bottom: Int
        if (texIndex >= 0) {
            top = texIndex
            side = texIndex
            bottom = texIndex
        } else {
            top = Textures.putSolid("mod_${def.id}_top", def.topColor)
            side = Textures.putSolid("mod_${def.id}_side", def.sideColor)
            bottom = Textures.putSolid("mod_${def.id}_bottom", def.bottomColor)
        }
        byId[def.id] = list.size
        list.add(Block(def.id, top, side, bottom, def.solid, def.opaque, false, false, color = def.sideColor))
        return list.size - 1
    }

    fun index(id: String): Int {
        byId[id]?.let { return it }
        when (id) {
            "grass_block" -> byId["grass"]?.let { return it }
            "wood", "oak_log" -> byId["log"]?.let { return it }
            "stone_block" -> byId["stone"]?.let { return it }
            "air" -> return AIR
        }
        return AIR
    }

    fun get(index: Int): Block = list.getOrElse(index) { list[0] }

    fun isSolid(index: Int): Boolean = get(index).solid

    fun isOpaque(index: Int): Boolean = get(index).opaque

    fun isLiquid(index: Int): Boolean = get(index).liquid

    fun ids(): List<String> = list.map { it.id }

    fun all(): List<Block> = list
}
