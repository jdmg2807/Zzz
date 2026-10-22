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
    val color: Int = 0x808080,
    /** Emitted light level 0..15. */
    val light: Int = 0,
    /** Horizontal inset in blocks, e.g. 1/16 for cactus. 0 = full cube. */
    val inset: Float = 0f,
    /** Vertical cut in blocks, e.g. 1/16 for the 15/16 cactus. 0 = full height. */
    val heightCut: Float = 0f,
    /** Collision + mesh boxes. Empty for plants, torches and liquids. */
    val shape: BlockShape = BlockShape.FULL,
    /** How long it takes to break bare-handed, in the same units as the wiki. */
    val hardness: Float = 1f,
    /** Preferred tool: "pickaxe", "axe", "shovel", "hoe", "" (none). */
    val tool: String = ""
) {
    /**
     * True when the block hides the face of its neighbour. Only full cubes do: an inset block
     * (cactus), a crossed plant or a liquid must never cull the neighbour face, otherwise a
     * 1/16 hole appears around it and you can see through the terrain.
     */
    val occludes: Boolean get() = opaque && !cross && inset <= 0f && heightCut <= 0f && shape.isFullCube

    /** True when the block can be walked on / stands in the way. */
    val collides: Boolean get() = !shape.isEmpty
}

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
        // Lava: same fluid rules as water but opaque, emissive and lethal.
        add("lava", tile("lava"), tile("lava"), tile("lava"), false, false, true, false, 0xE25822,
            light = 15, shape = BlockShape.EMPTY)
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
        add("glowstone", "glowstone", "glowstone", "glowstone", color = 0xc9a13c, light = 15)
        add("cactus", "cactus_top", "cactus_side", "cactus_top", color = 0x2f7a35, inset = 1f / 16f, heightCut = 1f / 16f)
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
        addCross("torch", "torch", color = 0xFFC24A, light = 14)

        // ---- v1.6.0: real-world blocks that show up in imported Minecraft saves ----------
        add("stone_bricks", "stone_bricks", "stone_bricks", "stone_bricks", color = 0x7a7a7a)
        add("mossy_cobblestone", "mossy_cobblestone", "mossy_cobblestone", "mossy_cobblestone", color = 0x6c7a5f)
        add("deepslate", "deepslate", "deepslate", "deepslate", color = 0x4a4a50)
        add("andesite", "andesite", "andesite", "andesite", color = 0x8a8a8d)
        add("granite", "granite", "granite", "granite", color = 0x9a6b58)
        add("diorite", "diorite", "diorite", "diorite", color = 0xc4c4ca)
        add("tuff", "tuff", "tuff", "tuff", color = 0x6d6e63)
        add("calcite", "calcite", "calcite", "calcite", color = 0xdfdfda)
        add("clay", "clay", "clay", "clay", color = 0x9ea4b4)
        add("netherrack", "netherrack", "netherrack", "netherrack", color = 0x6b2b2b)
        add("packed_ice", "packed_ice", "packed_ice", "packed_ice", color = 0x8fb6e8)
        add("ice", "ice", "ice", "ice", opaque = false, color = 0x9fc7f0)

        // ---- v1.9.0: workstations (each one opens its own GUI) ---------------------------
        add("crafting_table", "crafting_table_top", "crafting_table_side", "oak_planks",
            color = 0xb08a52, hardness = 2.5f, tool = "axe")
        add("chest", "chest_top", "chest_side", "chest_top", color = 0xa4712e,
            hardness = 2.5f, tool = "axe")
        add("furnace", "furnace_top", "furnace_side", "furnace_top", color = 0x7d7d7d,
            hardness = 3.5f, tool = "pickaxe")
        add("furnace_lit", "furnace_top", "furnace_front_on", "furnace_top", color = 0x7d7d7d,
            hardness = 3.5f, tool = "pickaxe", light = 13)
        add("anvil", "anvil_top", "anvil_side", "anvil_top", color = 0x33333a,
            shape = BlockShape.ANVIL, hardness = 5f, tool = "pickaxe")

        // ---- v1.7.0: non-full shapes (slabs, stairs, fences) -----------------------------
        add("stone_slab", "stone_slab", "stone_slab", "stone_slab", color = 0x8a8a8a,
            shape = BlockShape.SLAB_BOTTOM, hardness = 2f, tool = "pickaxe")
        add("cobblestone_slab", "cobblestone_slab", "cobblestone_slab", "cobblestone_slab",
            color = 0x7d7d7d, shape = BlockShape.SLAB_BOTTOM, hardness = 2f, tool = "pickaxe")
        add("oak_slab", "oak_planks", "oak_planks", "oak_planks", color = 0xa9834f,
            shape = BlockShape.SLAB_BOTTOM, hardness = 2f, tool = "axe")
        add("sandstone_slab", "sandstone_slab", "sandstone_slab", "sandstone_slab",
            color = 0xd8cb92, shape = BlockShape.SLAB_BOTTOM, hardness = 2f, tool = "pickaxe")
        add("stone_brick_slab", "stone_bricks", "stone_bricks", "stone_bricks", color = 0x7a7a7a,
            shape = BlockShape.SLAB_BOTTOM, hardness = 1.5f, tool = "pickaxe")
        add("stone_slab_top", "stone_slab", "stone_slab", "stone_slab", color = 0x8a8a8a,
            shape = BlockShape.SLAB_TOP, hardness = 2f, tool = "pickaxe")
        add("cobblestone_stairs", "cobblestone", "cobblestone", "cobblestone", color = 0x7d7d7d,
            shape = BlockShape.STAIRS_NORTH, hardness = 2f, tool = "pickaxe")
        add("stone_stairs", "stone", "stone", "stone", color = 0x8a8a8a,
            shape = BlockShape.STAIRS_NORTH, hardness = 1.5f, tool = "pickaxe")
        add("oak_stairs", "oak_planks", "oak_planks", "oak_planks", color = 0xa9834f,
            shape = BlockShape.STAIRS_NORTH, hardness = 2f, tool = "axe")
        add("stone_brick_stairs", "stone_bricks", "stone_bricks", "stone_bricks", color = 0x7a7a7a,
            shape = BlockShape.STAIRS_NORTH, hardness = 1.5f, tool = "pickaxe")
        add("oak_fence", "oak_planks", "oak_planks", "oak_planks", opaque = false, color = 0xa9834f,
            shape = BlockShape.FENCE, hardness = 2f, tool = "axe")
        add("cobblestone_wall", "cobblestone", "cobblestone", "cobblestone", opaque = false,
            color = 0x7d7d7d, shape = BlockShape.FENCE, hardness = 2f, tool = "pickaxe")
        add("carpet_red", "carpet_red", "carpet_red", "carpet_red", opaque = false, color = 0x9e2a2a,
            shape = BlockShape.LAYER, hardness = 0.1f)
        add("snow_layer", "snow", "snow", "snow", opaque = false, color = 0xf2f6fa,
            shape = BlockShape.LAYER, hardness = 0.1f, tool = "shovel")

        // ---- v1.7.0: wood and colour palette (building blocks people expect) ---------------
        add("spruce_planks", "spruce_planks", "spruce_planks", "spruce_planks", color = 0x735436, hardness = 2f, tool = "axe")
        add("birch_planks", "birch_planks", "birch_planks", "birch_planks", color = 0xc8b78a, hardness = 2f, tool = "axe")
        add("dark_oak_planks", "dark_oak_planks", "dark_oak_planks", "dark_oak_planks", color = 0x4b3520, hardness = 2f, tool = "axe")
        add("jungle_planks", "jungle_planks", "jungle_planks", "jungle_planks", color = 0xa0704a, hardness = 2f, tool = "axe")
        add("acacia_planks", "acacia_planks", "acacia_planks", "acacia_planks", color = 0xb05b2a, hardness = 2f, tool = "axe")
        add("mangrove_planks", "mangrove_planks", "mangrove_planks", "mangrove_planks", color = 0x773932, hardness = 2f, tool = "axe")
        add("cherry_planks", "cherry_planks", "cherry_planks", "cherry_planks", color = 0xe3b0a5, hardness = 2f, tool = "axe")
        add("bamboo_planks", "bamboo_planks", "bamboo_planks", "bamboo_planks", color = 0xc3a44a, hardness = 2f, tool = "axe")

        add("white_wool", "white_wool", "white_wool", "white_wool", color = 0xe9ecec, hardness = 0.8f)
        add("black_wool", "black_wool", "black_wool", "black_wool", color = 0x1d1d21, hardness = 0.8f)
        add("red_wool", "red_wool", "red_wool", "red_wool", color = 0xa12722, hardness = 0.8f)
        add("blue_wool", "blue_wool", "blue_wool", "blue_wool", color = 0x35399d, hardness = 0.8f)
        add("green_wool", "green_wool", "green_wool", "green_wool", color = 0x546d1b, hardness = 0.8f)
        add("yellow_wool", "yellow_wool", "yellow_wool", "yellow_wool", color = 0xf0c123, hardness = 0.8f)

        add("iron_block", "iron_block", "iron_block", "iron_block", color = 0xd8d8d8, hardness = 5f, tool = "pickaxe")
        add("gold_block", "gold_block", "gold_block", "gold_block", color = 0xf6d743, hardness = 3f, tool = "pickaxe")
        add("diamond_block", "diamond_block", "diamond_block", "diamond_block", color = 0x4fd6cf, hardness = 5f, tool = "pickaxe")
        add("emerald_block", "emerald_block", "emerald_block", "emerald_block", color = 0x2ecc71, hardness = 5f, tool = "pickaxe")
        add("coal_block", "coal_block", "coal_block", "coal_block", color = 0x1a1a1a, hardness = 5f, tool = "pickaxe")

        // 1.21 copper family: normal, exposed, weathered, oxidised (+ chiselled variants).
        add("copper_block", "copper_block", "copper_block", "copper_block", color = 0xc06a4a, hardness = 3f, tool = "pickaxe")
        add("exposed_copper", "exposed_copper", "exposed_copper", "exposed_copper", color = 0xa57a63, hardness = 3f, tool = "pickaxe")
        add("weathered_copper", "weathered_copper", "weathered_copper", "weathered_copper", color = 0x6f9a7c, hardness = 3f, tool = "pickaxe")
        add("oxidized_copper", "oxidized_copper", "oxidized_copper", "oxidized_copper", color = 0x529f7a, hardness = 3f, tool = "pickaxe")
        add("chiseled_copper", "chiseled_copper", "chiseled_copper", "chiseled_copper", color = 0xb86a4a, hardness = 3f, tool = "pickaxe")
        add("waxed_copper_block", "copper_block", "copper_block", "copper_block", color = 0xc97757, hardness = 3f, tool = "pickaxe")

        // 1.21 functional blocks (behaviour hooks arrive with redstone/containers).
        add("crafter", "crafter_top", "crafter_side", "crafter_bottom", color = 0x8a6a4a, hardness = 1.5f, tool = "pickaxe")
        add("vault", "vault_front", "vault_side", "vault_top", opaque = false, color = 0x5b5b5b, light = 7, hardness = 50f, tool = "pickaxe")
        add("trial_spawner", "trial_spawner_side", "trial_spawner_side", "trial_spawner_top",
            opaque = false, color = 0x39424a, light = 4, hardness = 50f, tool = "pickaxe")
        add("deepslate_bricks", "deepslate_bricks", "deepslate_bricks", "deepslate_bricks", color = 0x3f4045, hardness = 3.5f, tool = "pickaxe")
        add("mud_bricks", "mud_bricks", "mud_bricks", "mud_bricks", color = 0x89685a, hardness = 1.5f, tool = "pickaxe")
        add("quartz_block", "quartz_block", "quartz_block", "quartz_block", color = 0xe8e3d8, hardness = 0.8f, tool = "pickaxe")

        // ---- v1.8.0: redstone components -------------------------------------------------
        // The wire uses one block id per power level: the mesher then needs no per-block tint.
        for (level in 0..15) {
            add(
                "redstone_wire_$level", "redstone_wire_$level", "redstone_wire_$level",
                "redstone_wire_$level", solid = false, opaque = false, color = 0x8a1a10,
                shape = BlockShape.LAYER, hardness = 0f
            )
        }
        add("redstone_block", "redstone_block", "redstone_block", "redstone_block",
            color = 0xa82a1a, light = 0, hardness = 5f, tool = "pickaxe")
        add("lever_off", "lever", "lever", "lever", solid = false, opaque = false, color = 0x8a7a5a,
            shape = BlockShape.POST, hardness = 0.5f)
        add("lever_on", "lever", "lever", "lever", solid = false, opaque = false, color = 0x8a7a5a,
            shape = BlockShape.POST, hardness = 0.5f)
        addCross("redstone_torch_on", "redstone_torch_on", color = 0xFF5A3A, light = 7)
        addCross("redstone_torch_off", "redstone_torch_off", color = 0x6A2A1A)
        add("repeater_off", "repeater", "repeater", "repeater", solid = false, opaque = false,
            color = 0x9a9a9a, shape = BlockShape.LAYER, hardness = 0f)
        add("repeater_on", "repeater_on", "repeater_on", "repeater_on", solid = false, opaque = false,
            color = 0xb04a3a, shape = BlockShape.LAYER, hardness = 0f, light = 0)
        add("comparator_off", "comparator", "comparator", "comparator", solid = false, opaque = false,
            color = 0x9a9a9a, shape = BlockShape.LAYER, hardness = 0f)
        add("comparator_on", "comparator_on", "comparator_on", "comparator_on", solid = false,
            opaque = false, color = 0xa06a3a, shape = BlockShape.LAYER, hardness = 0f)
        add("redstone_lamp_off", "redstone_lamp_off", "redstone_lamp_off", "redstone_lamp_off",
            color = 0x4a3a2a, hardness = 0.3f)
        add("redstone_lamp_on", "redstone_lamp_on", "redstone_lamp_on", "redstone_lamp_on",
            color = 0xe0a860, light = 15, hardness = 0.3f)
        add("observer", "observer_front", "observer_back", "observer_back", color = 0x5a5a5a,
            hardness = 3f, tool = "pickaxe")
        add("observer_on", "observer_front", "observer_back", "observer_back", color = 0x6a6a6a,
            hardness = 3f, tool = "pickaxe")
        add("piston", "piston_top", "piston_side", "piston_top", color = 0x8a7a5a, hardness = 1.5f)
        add("piston_extended", "piston_top_extended", "piston_side", "piston_top", color = 0x9a8a6a,
            hardness = 1.5f)
        add("stone_button_off", "stone_button", "stone_button", "stone_button", solid = false,
            opaque = false, color = 0x8a8a8a, shape = BlockShape.LAYER, hardness = 0.5f)
        add("stone_button_on", "stone_button", "stone_button", "stone_button", solid = false,
            opaque = false, color = 0x8a8a8a, shape = BlockShape.LAYER, hardness = 0.5f)
    }

    /** All ids of a redstone wire power level, index = level. */
    fun wireId(level: Int): String = "redstone_wire_${level.coerceIn(0, 15)}"

    /** Power level encoded in a wire block id, or 0. */
    fun wireLevel(id: String): Int =
        if (id.startsWith("redstone_wire_")) id.removePrefix("redstone_wire_").toIntOrNull() ?: 0 else 0

    /** Hardness / tool table for the blocks declared before v1.7.0 (used by mining speed). */
    private val HARDNESS = mapOf(
        "grass" to 0.6f, "dirt" to 0.5f, "sand" to 0.5f, "gravel" to 0.6f, "clay" to 0.6f,
        "stone" to 1.5f, "cobblestone" to 2f, "stone_bricks" to 1.5f, "deepslate" to 3f,
        "obsidian" to 50f, "bedrock" to -1f, "planks" to 2f, "oak_planks" to 2f, "log" to 2f,
        "leaves" to 0.2f, "glass" to 0.3f, "glowstone" to 0.3f, "snow" to 0.2f, "ice" to 0.5f,
        "packed_ice" to 0.5f, "coal_ore" to 3f, "iron_ore" to 3f, "gold_ore" to 3f,
        "diamond_ore" to 3f, "brick" to 2f, "tnt" to 0f, "cactus" to 0.4f, "netherrack" to 0.4f
    )

    /**
     * Makes the palette usable from any thread/screen.
     *
     * The importer runs from the main menu, i.e. before the renderer has ever built a world, so
     * without this the whole palette was empty and *every* Minecraft block was reported as
     * "sin equivalente" (that was the v1.5.x bug where an imported world came out empty).
     */
    fun ensureReady() {
        if (Textures.count() == 0) Textures.prepareDefaults()
        init()
    }

    private fun tile(name: String): Int {
        val i = Textures.indexOf(name)
        return if (i >= 0) i else 0
    }

    private fun add(
        id: String,
        top: String, side: String, bottom: String,
        solid: Boolean = true, opaque: Boolean = true, color: Int, light: Int = 0, inset: Float = 0f,
        heightCut: Float = 0f, shape: BlockShape = BlockShape.FULL, hardness: Float = 1f, tool: String = ""
    ) {
        add(
            id, tile(top), tile(side), tile(bottom), solid, opaque, false, false, color,
            light = light, inset = inset, heightCut = heightCut, shape = shape,
            hardness = hardness, tool = tool
        )
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
            tintTop, tintSide, tintBottom,
            shape = if (cross) BlockShape.EMPTY else BlockShape.FULL
        )
    }

    private fun addLiquid(id: String, texture: String, color: Int) {
        add(
            id, tile(texture), tile(texture), tile(texture), false, false, true, false, color,
            true, true, true, shape = BlockShape.EMPTY
        )
    }

    private fun addCross(id: String, texture: String, color: Int, light: Int = 0) {
        add(
            id, tile(texture), tile(texture), tile(texture), false, false, false, true, color,
            light = light, shape = BlockShape.EMPTY
        )
    }

    private fun add(
        id: String, top: Int, side: Int, bottom: Int,
        solid: Boolean, opaque: Boolean, liquid: Boolean = false, cross: Boolean = false, color: Int,
        tintTop: Boolean = false, tintSide: Boolean = false, tintBottom: Boolean = false, light: Int = 0,
        inset: Float = 0f, heightCut: Float = 0f, shape: BlockShape = BlockShape.FULL,
        hardness: Float = 1f, tool: String = ""
    ) {
        if (byId.containsKey(id)) return
        byId[id] = list.size
        list.add(
            Block(
                id, top, side, bottom, solid, opaque, liquid, cross,
                tintTop, tintSide, tintBottom, color, light, inset, heightCut, shape, hardness, tool
            )
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

    /**
     * Maps a Minecraft Java block id ("minecraft:grass_block") to our palette.
     * Unknown blocks return air and are counted in [stats] so the log can list them.
     */
    fun fromMinecraft(rawName: String, stats: MutableMap<String, Int>? = null): Int {
        ensureReady()
        val name = rawName.removePrefix("minecraft:")
        val direct = byId[name]
        if (direct != null) {
            stats?.merge(name, 1, Int::plus)
            return direct
        }
        VALID_ALIASES[name]?.let { alias ->
            byId[alias]?.let {
                stats?.merge(name, 1, Int::plus)
                return it
            }
        }
        // Heuristics for the hundreds of variants we do not model.
        val guess = when {
            name.contains("leaves") -> "leaves"
            name == "spruce_leaves" -> "spruce_leaves"
            name == "birch_leaves" -> "birch_leaves"
            name.contains("log") || name.contains("wood") || name.contains("stem") -> "log"
            name.contains("planks") || name.contains("fence") || name.contains("door") ||
                name.contains("table") || name.contains("chest") || name.contains("shelf") -> "planks"
            name.contains("cobble") -> "cobblestone"
            name.contains("stone_brick") -> "stone_bricks"
            name.contains("brick") -> "brick"
            name.contains("sandstone") -> "sandstone"
            name.contains("sand") -> "sand"
            name.contains("gravel") -> "gravel"
            name.contains("dirt") || name.contains("path") || name.contains("farmland") -> "dirt"
            name.contains("grass") || name.contains("moss") -> "grass"
            name.contains("snow") -> "snow"
            name.contains("packed_ice") -> "packed_ice"
            name.contains("ice") -> "ice"
            name.contains("water") -> "water"
            name.contains("glass") -> "glass"
            name.contains("coal") -> "coal_ore"
            name.contains("iron_ore") -> "iron_ore"
            name.contains("gold_ore") -> "gold_ore"
            name.contains("diamond") -> "diamond_ore"
            name.contains("obsidian") || name.contains("bedrock") -> "obsidian"
            name.contains("deepslate") -> "deepslate"
            name.contains("mossy_cobble") -> "mossy_cobblestone"
            name.contains("andesite") -> "andesite"
            name.contains("granite") -> "granite"
            name.contains("diorite") -> "diorite"
            name.contains("tuff") -> "tuff"
            name.contains("calcite") -> "calcite"
            name.contains("clay") -> "clay"
            name.contains("netherrack") || name.contains("nether_") -> "netherrack"
            name.contains("lantern") || name.contains("lamp") || name.contains("shroomlight") -> "glowstone"
            name.contains("torch") -> "torch"
            name.contains("cactus") -> "cactus"
            name.contains("wool") || name.contains("carpet") -> "snow"
            name.contains("stone") -> "stone"
            name.contains("air") -> null
            else -> null
        }
        if (guess != null) {
            byId[guess]?.let {
                stats?.merge(name, 1, Int::plus)
                return it
            }
        }
        stats?.merge("?$name", 1, Int::plus)
        // Unknown block: a hole in the world is worse than an approximation, so anything that is
        // not obviously a thin/partial block becomes stone. The log still lists every name.
        if (!isPartial(name)) byId["stone"]?.let { return it }
        return AIR
    }

    /** Names that must never become a full cube (air, plants, rails, thin attachments...). */
    private fun isPartial(name: String): Boolean {
        val n = name.lowercase()
        val markers = arrayOf(
            "air", "plant", "flower", "sapling", "grass", "bush", "fern", "vine", "carpet",
            "torch", "candle", "rail", "button", "lever", "sign", "banner", "pane", "cobweb",
            "tripwire", "ladder", "scaffolding", "snow_layer", "lily", "seagrass", "kelp",
            "coral", "mushroom", "roots", "sprouts", "sugar_cane", "wheat", "carrots",
            "potatoes", "beetroots", "melon_stem", "pumpkin_stem", "portal", "fire", "bubble",
            "spawner", "chain", "bars", "fence_gate", "door", "trapdoor", "bed", "cake",
            "comparator", "repeater", "piston_head", "structure_void", "light"
        )
        return markers.any { n.contains(it) }
    }

    private val VALID_ALIASES = mapOf(
        "grass_block" to "grass",
        "short_grass" to "tall_grass",
        "grass" to "tall_grass",
        "fern" to "tall_grass",
        "tall_grass" to "tall_grass",
        "seagrass" to "tall_grass",
        "kelp" to "tall_grass",
        "oak_leaves" to "leaves",
        "jungle_leaves" to "leaves",
        "acacia_leaves" to "leaves",
        "dark_oak_leaves" to "leaves",
        "mangrove_leaves" to "leaves",
        "azalea_leaves" to "leaves",
        "oak_log" to "log",
        "stripped_oak_log" to "log",
        "birch_log" to "birch_log",
        "spruce_log" to "spruce_log",
        "oak_planks" to "planks",
        "water" to "water",
        "flowing_water" to "water",
        "bubble_column" to "water",
        "snow_block" to "snow",
        "snow" to "snow",
        "powder_snow" to "snow",
        "glass" to "glass",
        "glass_pane" to "glass",
        "bricks" to "brick",
        "stone_bricks" to "brick",
        "coal_ore" to "coal_ore",
        "deepslate_coal_ore" to "coal_ore",
        "iron_ore" to "iron_ore",
        "deepslate_iron_ore" to "iron_ore",
        "gold_ore" to "gold_ore",
        "diamond_ore" to "diamond_ore",
        "deepslate_diamond_ore" to "diamond_ore",
        "dead_bush" to "dead_bush",
        "poppy" to "poppy",
        "dandelion" to "dandelion",
        "sugar_cane" to "sugar_cane",
        "sand" to "sand",
        "red_sand" to "sand",
        "grass_path" to "dirt",
        "dirt_path" to "dirt",
        "coarse_dirt" to "dirt",
        "podzol" to "dirt",
        "mycelium" to "dirt",
        "bedrock" to "bedrock",
        "tnt" to "tnt",
        "glowstone" to "glowstone",
        "lava" to "glowstone",
        "crying_obsidian" to "obsidian",
        "granite" to "stone",
        "diorite" to "stone",
        "andesite" to "stone",
        "deepslate" to "stone",
        "tuff" to "stone",
        "calcite" to "stone",
        "ice" to "snow",
        "packed_ice" to "snow",
        "blue_ice" to "snow",
        "clay" to "gravel",
        "white_wool" to "snow",
        // v1.6.0
        "spruce_log" to "spruce_log",
        "birch_log" to "birch_log",
        "stone_bricks" to "stone_bricks",
        "chiseled_stone_bricks" to "stone_bricks",
        "cracked_stone_bricks" to "stone_bricks",
        "mossy_stone_bricks" to "stone_bricks",
        "mossy_cobblestone" to "mossy_cobblestone",
        "cobbled_deepslate" to "deepslate",
        "polished_deepslate" to "deepslate",
        "deepslate_bricks" to "deepslate",
        "deepslate_tiles" to "deepslate",
        "chiseled_deepslate" to "deepslate",
        "polished_andesite" to "andesite",
        "polished_granite" to "granite",
        "polished_diorite" to "diorite",
        "smooth_stone" to "stone",
        "chiseled_sandstone" to "sandstone",
        "cut_sandstone" to "sandstone",
        "smooth_sandstone" to "sandstone",
        "wall_torch" to "torch",
        "redstone_torch" to "torch",
        "soul_torch" to "torch",
        "lantern" to "glowstone",
        "campfire" to "glowstone",
        "lava" to "glowstone"
    )

    /** Hardness in Java units; -1 means unbreakable (bedrock). */
    fun hardnessOf(id: String): Float {
        val explicit = byId[id]?.let { list[it].hardness } ?: 1f
        val fallback = HARDNESS[id]
        // Blocks declared without a hardness keep the table value (or 1 s as a last resort).
        return when {
            fallback != null && explicit == 1f -> fallback
            else -> explicit
        }
    }

    /** Tool family that speeds this block up ("pickaxe", "axe", "shovel", "hoe" or ""). */
    fun toolOf(id: String): String = byId[id]?.let { list[it].tool } ?: ""

    fun ids(): List<String> = list.map { it.id }

    fun all(): List<Block> = list
}
