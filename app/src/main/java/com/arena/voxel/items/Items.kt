package com.arena.voxel.items

import com.arena.voxel.world.Blocks

/**
 * An item stack, exactly the Java model: an id, a count (1..64) and optional damage.
 * Blocks are items too (`minecraft:stone` is both), which is why the hotbar can hold them.
 */
data class ItemStack(val id: String, var count: Int = 1, var damage: Int = 0) {

    val isEmpty: Boolean get() = count <= 0

    fun copyStack(): ItemStack = ItemStack(id, count, damage)

    fun split(half: Boolean): ItemStack? {
        if (count <= 0) return null
        val take = if (half) (count + 1) / 2 else 1
        if (take <= 0) return null
        count -= take
        return ItemStack(id, take, damage)
    }

    companion object {
        const val MAX_STACK = 64
        fun of(id: String, count: Int = 1) = ItemStack(id, count)
    }
}

/**
 * Item registry. Every block is an item; a small set of non-block items (tools, food, materials)
 * is declared here so crafting and the creative tabs have something real to show.
 */
object Items {

    class Def(
        val id: String,
        val name: String,
        val maxStack: Int = 64,
        /** Tool tier: 0 = hand, 1 = wood, 2 = stone, 3 = iron, 4 = diamond, 5 = netherite. */
        val tier: Int = 0,
        val toolType: String = "",
        /** Attack damage bonus, like Java's `attack_damage` attribute. */
        val attack: Float = 1f,
        /** Attack speed, used by the 1.9 cooldown. */
        val attackSpeed: Float = 4f,
        val food: Int = 0,
        val block: String? = null
    )

    private val defs = LinkedHashMap<String, Def>()

    /** Blocks that exist in the palette, with Spanish display names. */
    private val BLOCK_NAMES = mapOf(
        "grass" to "Césped", "dirt" to "Tierra", "stone" to "Piedra",
        "cobblestone" to "Adoquín", "planks" to "Tablones", "oak_planks" to "Tablones de roble",
        "sand" to "Arena", "gravel" to "Grava", "log" to "Tronco", "leaves" to "Hojas",
        "water" to "Agua", "glass" to "Cristal", "snow" to "Nieve", "bedrock" to "Piedra base",
        "coal_ore" to "Mena de carbón", "iron_ore" to "Mena de hierro", "gold_ore" to "Mena de oro",
        "diamond_ore" to "Mena de diamante", "brick" to "Ladrillos", "obsidian" to "Obsidiana",
        "tnt" to "TNT", "tnt_lit" to "TNT", "glowstone" to "Piedra luminosa", "cactus" to "Cactus",
        "sandstone" to "Arenisca", "desert" to "Arena", "spruce_leaves" to "Hojas de abeto",
        "birch_leaves" to "Hojas de abedul", "dead_bush" to "Arbusto muerto",
        "tall_grass" to "Hierba alta", "dandelion" to "Diente de león", "poppy" to "Amapola",
        "sugar_cane" to "Caña de azúcar", "torch" to "Antorcha",
        "stone_bricks" to "Ladrillos de piedra", "mossy_cobblestone" to "Adoquín musgoso",
        "deepslate" to "Pizarra profunda", "andesite" to "Andesita", "granite" to "Granito",
        "diorite" to "Diorita", "tuff" to "Toba", "calcite" to "Calcita", "clay" to "Arcilla",
        "netherrack" to "Infraescombro", "ice" to "Hielo", "packed_ice" to "Hielo compacto",
        "stone_slab" to "Losa de piedra", "cobblestone_slab" to "Losa de adoquín",
        "oak_slab" to "Losa de roble", "sandstone_slab" to "Losa de arenisca",
        "stone_brick_slab" to "Losa de ladrillos de piedra", "stone_slab_top" to "Losa de piedra",
        "cobblestone_stairs" to "Escaleras de adoquín", "stone_stairs" to "Escaleras de piedra",
        "oak_stairs" to "Escaleras de roble", "stone_brick_stairs" to "Escaleras de ladrillos",
        "oak_fence" to "Valla de roble", "cobblestone_wall" to "Muro de adoquín",
        "carpet_red" to "Alfombra roja", "snow_layer" to "Capa de nieve",
        "spruce_planks" to "Tablones de abeto", "birch_planks" to "Tablones de abedul",
        "dark_oak_planks" to "Tablones de roble oscuro", "jungle_planks" to "Tablones de jungla",
        "acacia_planks" to "Tablones de acacia", "mangrove_planks" to "Tablones de mangle",
        "cherry_planks" to "Tablones de cerezo", "bamboo_planks" to "Tablones de bambú",
        "white_wool" to "Lana blanca", "black_wool" to "Lana negra", "red_wool" to "Lana roja",
        "blue_wool" to "Lana azul", "green_wool" to "Lana verde", "yellow_wool" to "Lana amarilla",
        "iron_block" to "Bloque de hierro", "gold_block" to "Bloque de oro",
        "diamond_block" to "Bloque de diamante", "emerald_block" to "Bloque de esmeralda",
        "coal_block" to "Bloque de carbón", "copper_block" to "Bloque de cobre",
        "exposed_copper" to "Cobre expuesto", "weathered_copper" to "Cobre degradado",
        "oxidized_copper" to "Cobre oxidado", "chiseled_copper" to "Cobre cincelado",
        "waxed_copper_block" to "Cobre encerado", "crafter" to "Crafteador",
        "vault" to "Bóveda", "trial_spawner" to "Generador de pruebas",
        "deepslate_bricks" to "Ladrillos de pizarra", "mud_bricks" to "Ladrillos de barro",
        "quartz_block" to "Bloque de cuarzo",
        "crafting_table" to "Mesa de trabajo", "chest" to "Cofre", "furnace" to "Horno",
        "furnace_lit" to "Horno", "anvil" to "Yunque", "redstone_wire_0" to "Redstone",
        "lever_off" to "Palanca", "lever_on" to "Palanca", "repeater_off" to "Repetidor",
        "repeater_on" to "Repetidor",
        "repeater_off_d1" to "Repetidor", "repeater_off_d2" to "Repetidor",
        "repeater_off_d3" to "Repetidor", "repeater_off_d4" to "Repetidor",
        "repeater_on_d1" to "Repetidor", "repeater_on_d2" to "Repetidor",
        "repeater_on_d3" to "Repetidor", "repeater_on_d4" to "Repetidor", "comparator_off" to "Comparador",
        "comparator_on" to "Comparador", "redstone_lamp_off" to "Lámpara de redstone",
        "redstone_lamp_on" to "Lámpara de redstone", "piston" to "Pistón",
        "piston_sticky" to "Pistón pegajoso", "observer" to "Observador",
        "observer_on" to "Observador", "redstone_block" to "Bloque de redstone",
        "redstone_torch_on" to "Antorcha de redstone", "redstone_torch_off" to "Antorcha de redstone",
        "stone_button_off" to "Botón de piedra", "stone_button_on" to "Botón de piedra"
    )

    init {
        // Blocks as items.
        for (block in Blocks.all()) {
            if (block.id == "air") continue
            defs[block.id] = Def(
                id = block.id,
                name = BLOCK_NAMES[block.id]
                    ?: com.arena.voxel.world.BlockGen.names[block.id]
                    ?: block.id.replace('_', ' ').replaceFirstChar { it.uppercase() },
                block = block.id
            )
        }
        // Tools (tiers: wood 1, stone 2, iron 3, diamond 4, netherite 5).
        fun tool(id: String, name: String, type: String, tier: Int, attack: Float, speed: Float) {
            defs[id] = Def(id, name, 1, tier, type, attack, speed, 0, null)
        }
        tool("wooden_pickaxe", "Pico de madera", "pickaxe", 1, 2f, 1.2f)
        tool("stone_pickaxe", "Pico de piedra", "pickaxe", 2, 3f, 1.2f)
        tool("iron_pickaxe", "Pico de hierro", "pickaxe", 3, 4f, 1.2f)
        tool("diamond_pickaxe", "Pico de diamante", "pickaxe", 4, 5f, 1.2f)
        tool("wooden_axe", "Hacha de madera", "axe", 1, 7f, 0.8f)
        tool("stone_axe", "Hacha de piedra", "axe", 2, 9f, 0.8f)
        tool("iron_axe", "Hacha de hierro", "axe", 3, 9f, 0.9f)
        tool("diamond_axe", "Hacha de diamante", "axe", 4, 9f, 1.0f)
        tool("wooden_shovel", "Pala de madera", "shovel", 1, 2.5f, 1f)
        tool("stone_shovel", "Pala de piedra", "shovel", 2, 3.5f, 1f)
        tool("iron_shovel", "Pala de hierro", "shovel", 3, 4.5f, 1f)
        tool("diamond_shovel", "Pala de diamante", "shovel", 4, 5.5f, 1f)
        tool("wooden_sword", "Espada de madera", "sword", 1, 4f, 1.6f)
        tool("stone_sword", "Espada de piedra", "sword", 2, 5f, 1.6f)
        tool("iron_sword", "Espada de hierro", "sword", 3, 6f, 1.6f)
        tool("diamond_sword", "Espada de diamante", "sword", 4, 7f, 1.6f)
        tool("bow", "Arco", "bow", 0, 1f, 1f)
        tool("shield", "Escudo", "shield", 0, 1f, 1f)

        // Materials and food.
        fun item(id: String, name: String, stack: Int = 64, food: Int = 0) {
            // Never clobber a block definition: chest/furnace/lever/… are placeable blocks first.
            if (defs.containsKey(id)) return
            defs[id] = Def(id, name, stack, food = food)
        }
        item("stick", "Palo")
        item("coal", "Carbón")
        item("charcoal", "Carbón vegetal")
        item("iron_ingot", "Lingote de hierro")
        item("gold_ingot", "Lingote de oro")
        item("diamond", "Diamante")
        item("emerald", "Esmeralda")
        item("copper_ingot", "Lingote de cobre")
        item("flint", "Pedernal")
        item("string", "Hilo")
        item("feather", "Pluma")
        item("gunpowder", "Pólvora")
        item("wheat", "Trigo")
        item("seeds", "Semillas")
        item("rotten_flesh", "Carne podrida", food = 4)
        item("bone", "Hueso")
        item("arrow", "Flecha")
        item("apple", "Manzana", food = 4)
        item("bread", "Pan", food = 5)
        item("cooked_beef", "Filete asado", food = 8)
        item("raw_beef", "Filete crudo", food = 3)
        item("porkchop", "Chuleta", food = 3)
        item("cooked_porkchop", "Chuleta asada", food = 8)
        item("bucket", "Cubo", 16)
        item("shears", "Tijeras", 1)
        item("flint_and_steel", "Mechero", 1)
        item("water_bucket", "Cubo de agua", 1)
        item("lava_bucket", "Cubo de lava", 1)
        item("wooden_door", "Puerta de madera", 64)
        item("chest", "Cofre", 64)
        item("crafting_table", "Mesa de trabajo", 64)
        item("furnace", "Horno", 64)
        item("raw_iron", "Hierro en bruto")
        item("raw_gold", "Oro en bruto")
        item("raw_copper", "Cobre en bruto")
        item("snowball", "Bola de nieve", 16)
        item("redstone", "Redstone")
        item("redstone_torch", "Antorcha de redstone")
        item("repeater", "Repetidor")
        item("comparator", "Comparador")
        item("lever", "Palanca")
        item("piston", "Pistón")
        item("sticky_piston", "Pistón pegajoso")
        item("observer", "Observador")
        item("redstone_lamp", "Lámpara de redstone")
    }

    /** Registers an item declared by a mod jar. */
    fun registerMod(def: com.arena.voxel.api.ItemDef) {
        if (defs.containsKey(def.id)) return
        defs[def.id] = Def(
            id = def.id,
            name = def.name,
            maxStack = def.maxStack.coerceIn(1, 64),
            food = def.food
        )
    }

    fun def(id: String): Def? = defs[id]
    fun exists(id: String): Boolean = defs.containsKey(id)
    /**
     * Display name: the mod/engine name first, then the Spanish vanilla name from the bundled
     * Java language file, then the raw id. This is what makes every vanilla item readable
     * ("Pico de hierro" instead of "iron_pickaxe").
     */
    fun nameOf(id: String): String =
        defs[id]?.name
            ?: com.arena.voxel.core.VanillaAssets.displayName(id)
            ?: com.arena.voxel.mods.JavaModSupport.translatedName(id)
            ?: id.replace('_', ' ')
    fun isBlock(id: String): Boolean = Blocks.index(id) != 0 && id != "air"
    fun maxStack(id: String): Int =
        defs[id]?.maxStack ?: Blocks.stackSizeOf(id).takeIf { it in 1..64 } ?: 64
    fun food(id: String): Int = defs[id]?.food ?: 0

    /** Java's saturation modifier per food (steak 0.8, bread 0.6, apple 0.3, rotten flesh 0.1). */
    fun saturationOf(id: String): Float = when (id) {
        "cooked_beef", "cooked_porkchop" -> 0.8f
        "bread", "porkchop", "raw_beef" -> 0.6f
        "apple" -> 0.3f
        "rotten_flesh" -> 0.1f
        else -> 0.5f
    }
    fun tier(id: String): Int = defs[id]?.tier ?: 0
    fun toolType(id: String): String = defs[id]?.toolType ?: ""
    /** Item id that a broken block leaves behind (null = nothing). */
    fun dropOf(blockId: String): String? = dropOf(blockId, "")

    /**
     * Loot of a block given the held item. Shears are special: they collect leaves, grass and
     * wool that bare hands destroy, exactly like Java.
     */
    fun dropOf(blockId: String, held: String): String? = when {
        held == "shears" && (blockId.contains("leaves") || blockId == "tall_grass" ||
            blockId == "short_grass" || blockId == "fern" || blockId.endsWith("_wool")) ->
            if (exists(blockId)) blockId else null
        Recipes.NO_DROP.contains(blockId) -> null
        Recipes.DROPS.containsKey(blockId) -> Recipes.DROPS[blockId]
        else -> if (exists(blockId)) blockId else null
    }

    /**
     * Minimum tool tier needed for a block to drop anything (Java's `needs_iron_tool` etc.):
     * 1 = wood, 2 = stone, 3 = iron, 4 = diamond. 0 = bare hands are enough.
     */
    fun requiredTier(blockId: String): Int {
        val fromTable = Blocks.tierOf(blockId)
        return if (fromTable > 0) fromTable else legacyTier(blockId)
    }

    /** Hand-written fallback for ids the vanilla table does not know (mods, aliases). */
    private fun legacyTier(blockId: String): Int = when (blockId) {
        "iron_ore", "deepslate_iron_ore", "copper_ore", "deepslate_copper_ore",
        "lapis_ore", "deepslate_lapis_ore" -> 2
        "gold_ore", "deepslate_gold_ore", "redstone_ore", "deepslate_redstone_ore",
        "diamond_ore", "deepslate_diamond_ore", "emerald_ore", "deepslate_emerald_ore" -> 3
        "ancient_debris", "obsidian" -> 4
        else -> 0
    }

    /** True when the held tool is good enough for the block to drop its loot. */
    fun canHarvest(blockId: String, held: String): Boolean {
        val tool = Blocks.toolOf(blockId)
        val required = requiredTier(blockId)
        if (required == 0) return true
        return toolType(held) == tool && tier(held) >= required
    }

    /** Durability of a tool id (0 = unbreakable, like blocks and food). */
    fun maxDamage(id: String): Int {
        val def = defs[id] ?: return 0
        if (def.toolType.isEmpty()) return 0
        return when (def.tier) {
            1 -> 59
            2 -> 131
            3 -> 250
            4 -> 1561
            5 -> 2031
            else -> 0
        }
    }

    fun attackSpeed(id: String): Float = defs[id]?.attackSpeed ?: 4f
    fun attackDamage(id: String): Float = defs[id]?.attack ?: 1f

    fun all(): List<Def> = defs.values.toList()

    /** Creative inventory categories, in Java's tab order. */
    fun creativeTabs(): List<Pair<String, List<String>>> = buildTabs()

    private fun buildTabs(): List<Pair<String, List<String>>> {
        val tabs = ArrayList<Pair<String, List<String>>>(BASE_TABS)
        // Generated families: one tab each so the 45-slot page is not flooded by one category.
        val gen = com.arena.voxel.world.BlockGen.names
        fun of(predicate: (String) -> Boolean): List<String> =
            gen.keys.filter(predicate).filter { exists(it) }.sorted().take(45)
        tabs.add("Lanas" to of { it.endsWith("_wool") || it.endsWith("_carpet") })
        tabs.add("Hormigón" to of { it.endsWith("_concrete") || it.endsWith("_terracotta") })
        tabs.add("Maderas" to of {
            it.endsWith("_planks") || it.endsWith("_log") || it.endsWith("_stem") ||
                it.endsWith("_stairs") || it.endsWith("_slab") || it.endsWith("_fence")
        })
        tabs.add("Piedra" to of {
            it.endsWith("_bricks") || it.endsWith("_tiles") || it.contains("stone") ||
                it.contains("deepslate") || it.contains("tuff") || it.contains("basalt") ||
                it.contains("quartz") || it.contains("sandstone")
        })
        tabs.add("Minerales" to of { it.endsWith("_ore") || it.endsWith("_block") })
        tabs.add("Cobre" to of { it.contains("copper") })
        tabs.add("1.21" to of {
            it == "crafter" || it == "vault" || it == "trial_spawner" || it == "heavy_core" ||
                it.contains("copper_bulb") || it.contains("copper_grate")
        })
        tabs.add("Plantas" to of {
            it.contains("leaves") || it == "cactus" || it == "bamboo" || it == "sugar_cane" ||
                it == "dead_bush" || it == "fern" || it.endsWith("_orchid") || it == "allium" ||
                it == "poppy" || it == "dandelion" || it == "cornflower" || it == "oxeye_daisy" ||
                it == "azure_bluet" || it == "lily_of_the_valley" || it == "short_grass"
        })
        return tabs
    }

    private val BASE_TABS: List<Pair<String, List<String>>> = listOf(
        "Construcción" to listOf(
            "stone", "cobblestone", "stone_bricks", "andesite", "granite", "diorite", "deepslate",
            "deepslate_bricks", "tuff", "calcite", "dirt", "grass", "sand", "sandstone", "gravel",
            "clay", "mud_bricks", "quartz_block", "brick", "obsidian", "netherrack",
            "oak_planks", "spruce_planks", "birch_planks", "dark_oak_planks", "jungle_planks",
            "acacia_planks", "mangrove_planks", "cherry_planks", "bamboo_planks",
            "log", "glass", "stone_slab", "oak_slab", "sandstone_slab", "stone_brick_slab",
            "cobblestone_stairs", "stone_stairs", "oak_stairs", "stone_brick_stairs",
            "oak_fence", "cobblestone_wall", "iron_block", "gold_block", "diamond_block",
            "emerald_block", "coal_block", "copper_block", "exposed_copper", "weathered_copper",
            "oxidized_copper", "chiseled_copper", "snow", "ice", "packed_ice"
        ),
        "Decoración" to listOf(
            "white_wool", "black_wool", "red_wool", "blue_wool", "green_wool", "yellow_wool",
            "carpet_red", "torch", "glowstone", "snow_layer", "leaves", "spruce_leaves",
            "birch_leaves", "tall_grass", "dandelion", "poppy", "dead_bush", "sugar_cane",
            "cactus", "crafter", "vault", "trial_spawner", "tnt"
        ),
        "Redstone" to listOf(
            "redstone", "redstone_torch_on", "lever_off", "repeater_off", "comparator_off",
            "piston", "observer", "redstone_lamp_off", "redstone_block", "stone_button_off",
            "redstone_wire_0", "chest", "furnace", "crafting_table", "anvil"
        ),
        "Herramientas" to listOf(
            "wooden_pickaxe", "stone_pickaxe", "iron_pickaxe", "diamond_pickaxe",
            "wooden_axe", "stone_axe", "iron_axe", "diamond_axe",
            "wooden_shovel", "stone_shovel", "iron_shovel", "diamond_shovel",
            "bucket", "water_bucket", "lava_bucket", "flint", "stick"
        ),
        "Comida" to listOf(
            "apple", "bread", "raw_beef", "cooked_beef", "porkchop", "cooked_porkchop",
            "wheat", "seeds", "rotten_flesh"
        ),
        "Combate" to listOf(
            "wooden_sword", "stone_sword", "iron_sword", "diamond_sword",
            "bow", "arrow", "shield"
        ),
        "Ingredientes" to listOf(
            "coal", "charcoal", "iron_ingot", "gold_ingot", "copper_ingot", "diamond",
            "emerald", "string", "feather", "gunpowder", "bone", "coal_ore", "iron_ore",
            "gold_ore", "diamond_ore"
        )
    )
}
