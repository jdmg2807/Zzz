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
    val color: Int = 0x808080
)

/**
 * Block palette. Index 0 is always air; every other block is addressed by a byte inside
 * the chunk storage, which keeps a 16x16x80 chunk at 20 KB of RAM.
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

        add("grass", "grass_top", "grass_side", "dirt", color = 0x74a63a)
        add("dirt", "dirt", "dirt", "dirt", color = 0x8b5a2b)
        add("stone", "stone", "stone", "stone", color = 0x8a8a8a)
        add("cobblestone", "cobblestone", "cobblestone", "cobblestone", color = 0x7d7d7d)
        add("planks", "oak_planks", "oak_planks", "oak_planks", color = 0xa9834f)
        add("oak_planks", "oak_planks", "oak_planks", "oak_planks", color = 0xa9834f)
        add("sand", "sand", "sand", "sand", color = 0xdbcf94)
        add("gravel", "gravel", "gravel", "gravel", color = 0x8a8078)
        add("log", "log_top", "log_side", "log_top", color = 0x6b4f2a)
        add("leaves", "leaves", "leaves", "leaves", opaque = false, color = 0x3f7d24)
        add("water", "water", "water", "water", solid = false, opaque = false, liquid = true, color = 0x3b6fd4)
        add("glass", "glass", "glass", "glass", opaque = false, color = 0xd7e9f2)
        add("snow", "snow", "snow_side", "dirt", color = 0xf2f6fa)
        add("bedrock", "bedrock", "bedrock", "bedrock", color = 0x565656)
        add("coal_ore", "coal_ore", "coal_ore", "coal_ore", color = 0x5a5a5a)
        add("iron_ore", "iron_ore", "iron_ore", "iron_ore", color = 0xc0a080)
        add("gold_ore", "gold_ore", "gold_ore", "gold_ore", color = 0xd8b52a)
        add("diamond_ore", "diamond_ore", "diamond_ore", "diamond_ore", color = 0x5fd8cf)
        add("brick", "brick", "brick", "brick", color = 0xa2432f)
        add("obsidian", "obsidian", "obsidian", "obsidian", color = 0x2a1f3d)
        add("tnt", "tnt_top", "tnt_side", "tnt_top", color = 0xd4462c)
        add("glowstone", "glowstone", "glowstone", "glowstone", color = 0xc9a13c)
    }

    private fun add(
        id: String,
        top: String, side: String, bottom: String,
        solid: Boolean = true, opaque: Boolean = true, liquid: Boolean = false, color: Int
    ) {
        add(id, tile(top), tile(side), tile(bottom), solid, opaque, liquid, color)
    }

    private fun add(
        id: String, top: Int, side: Int, bottom: Int,
        solid: Boolean, opaque: Boolean, liquid: Boolean = false, color: Int
    ) {
        if (byId.containsKey(id)) return
        byId[id] = list.size
        list.add(Block(id, top, side, bottom, solid, opaque, liquid, color))
    }

    private fun tile(name: String): Int {
        val i = Textures.indexOf(name)
        return if (i >= 0) i else 0
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
        list.add(Block(def.id, top, side, bottom, def.solid, def.opaque, false, def.sideColor))
        return list.size - 1
    }

    fun index(id: String): Int {
        byId[id]?.let { return it }
        // A few friendly aliases.
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

    fun indexOf(block: Block): Int = list.indexOf(block)

    fun ids(): List<String> = list.map { it.id }

    fun all(): List<Block> = list
}
