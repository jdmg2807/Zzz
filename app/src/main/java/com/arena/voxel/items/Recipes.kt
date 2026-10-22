package com.arena.voxel.items

/**
 * Crafting and smelting, modelled on Java's data-driven recipes but kept in Kotlin.
 *
 * A shaped recipe is a 2x2 or 3x3 pattern where every character maps to an item id; `#` is a
 * placeholder for "any plank" and ` ` is an empty slot. Patterns are matched with offset,
 * mirroring and trimming, exactly like the game does, so a recipe works anywhere in the grid.
 */
object Recipes {

    class Shaped(
        val name: String,
        val pattern: List<String>,
        val key: Map<Char, String>,
        val result: String,
        val count: Int = 1
    )

    class Shapeless(val name: String, val ingredients: List<String>, val result: String, val count: Int)

    /** Any of these counts as "planks" for the `#` key. */
    private val PLANKS = listOf(
        "oak_planks", "spruce_planks", "birch_planks", "dark_oak_planks", "jungle_planks",
        "acacia_planks", "mangrove_planks", "cherry_planks", "bamboo_planks", "planks"
    )

    private fun anyPlank(id: String): Boolean = PLANKS.contains(id)

    private fun toolSet(prefix: String, material: String): List<Shaped> = listOf(
        Shaped("$prefix pickaxe", listOf("MMM", " S ", " S "), mapOf('M' to material, 'S' to "stick"), "${prefix}_pickaxe"),
        Shaped("$prefix axe", listOf("MM ", "MS ", " S "), mapOf('M' to material, 'S' to "stick"), "${prefix}_axe"),
        Shaped("$prefix shovel", listOf(" M ", " S ", " S "), mapOf('M' to material, 'S' to "stick"), "${prefix}_shovel"),
        Shaped("$prefix sword", listOf(" M ", " M ", " S "), mapOf('M' to material, 'S' to "stick"), "${prefix}_sword")
    )

    val SHAPED: List<Shaped> = buildList {
        // Wood family
        add(Shaped("crafting_table", listOf("##", "##"), mapOf('#' to "oak_planks"), "crafting_table"))
        add(Shaped("chest", listOf("###", "# #", "###"), mapOf('#' to "oak_planks"), "chest"))
        add(Shaped("furnace", listOf("###", "# #", "###"), mapOf('#' to "cobblestone"), "furnace"))
        // Anvil: three iron blocks on top, four iron ingots under them (Java 1.21 recipe).
        add(Shaped("anvil", listOf("BBB", " I ", "III"), mapOf('B' to "iron_block", 'I' to "iron_ingot"), "anvil"))
        add(Shaped("sticks", listOf("#", "#"), mapOf('#' to "oak_planks"), "stick", 4))
        add(Shaped("torches", listOf("C", "S"), mapOf('C' to "coal", 'S' to "stick"), "torch", 4))
        add(Shaped("fence", listOf("#S#", "#S#"), mapOf('#' to "oak_planks", 'S' to "stick"), "oak_fence", 3))
        add(Shaped("oak_stairs", listOf("#  ", "## ", "###"), mapOf('#' to "oak_planks"), "oak_stairs", 4))
        add(Shaped("stone_stairs", listOf("#  ", "## ", "###"), mapOf('#' to "stone"), "stone_stairs", 4))
        add(
            Shaped(
                "cobblestone_stairs", listOf("#  ", "## ", "###"),
                mapOf('#' to "cobblestone"), "cobblestone_stairs", 4
            )
        )
        add(
            Shaped(
                "stone_brick_stairs", listOf("#  ", "## ", "###"),
                mapOf('#' to "stone_bricks"), "stone_brick_stairs", 4
            )
        )
        add(Shaped("stone_slab", listOf("###"), mapOf('#' to "stone"), "stone_slab", 6))
        add(Shaped("cobblestone_slab", listOf("###"), mapOf('#' to "cobblestone"), "cobblestone_slab", 6))
        add(Shaped("oak_slab", listOf("###"), mapOf('#' to "oak_planks"), "oak_slab", 6))
        add(Shaped("sandstone_slab", listOf("###"), mapOf('#' to "sandstone"), "sandstone_slab", 6))
        add(
            Shaped(
                "stone_brick_slab", listOf("###"),
                mapOf('#' to "stone_bricks"), "stone_brick_slab", 6
            )
        )
        add(Shaped("stone_bricks", listOf("##", "##"), mapOf('#' to "stone"), "stone_bricks", 4))
        add(Shaped("sandstone", listOf("##", "##"), mapOf('#' to "sand"), "sandstone"))
        add(Shaped("bricks", listOf("##", "##"), mapOf('#' to "clay"), "brick"))
        add(Shaped("furnace", listOf("###", "# #", "###"), mapOf('#' to "cobblestone"), "furnace"))
        add(Shaped("bucket", listOf("# #", " # "), mapOf('#' to "iron_ingot"), "bucket"))
        add(Shaped("bow", listOf(" S#", "S #", " S#"), mapOf('S' to "string", '#' to "stick"), "bow"))
        add(Shaped("shield", listOf("##H", "###", " # "), mapOf('#' to "oak_planks", 'H' to "iron_ingot"), "shield"))
        add(Shaped("iron_block", listOf("###", "###", "###"), mapOf('#' to "iron_ingot"), "iron_block"))
        add(Shaped("gold_block", listOf("###", "###", "###"), mapOf('#' to "gold_ingot"), "gold_block"))
        add(Shaped("diamond_block", listOf("###", "###", "###"), mapOf('#' to "diamond"), "diamond_block"))
        add(Shaped("emerald_block", listOf("###", "###", "###"), mapOf('#' to "emerald"), "emerald_block"))
        add(Shaped("coal_block", listOf("###", "###", "###"), mapOf('#' to "coal"), "coal_block"))
        add(Shaped("copper_block", listOf("###", "###", "###"), mapOf('#' to "copper_ingot"), "copper_block"))
        add(Shaped("bread", listOf("###"), mapOf('#' to "wheat"), "bread"))
        add(Shaped("wool_white", listOf("##", "##"), mapOf('#' to "string"), "white_wool"))
        add(Shaped("redstone_torch", listOf("R", "S"), mapOf('R' to "redstone", 'S' to "stick"), "redstone_torch"))

        addAll(toolSet("wooden", "planks"))
        addAll(toolSet("stone", "cobblestone"))
        addAll(toolSet("iron", "iron_ingot"))
        addAll(toolSet("diamond", "diamond"))
    }

    val SHAPELESS: List<Shapeless> = listOf(
        // One log gives four planks of its own wood: handled in `craft` because the result
        // depends on the input log type.
        Shapeless("iron_ingot_from_block", listOf("iron_block"), "iron_ingot", 9),
        Shapeless("gold_ingot_from_block", listOf("gold_block"), "gold_ingot", 9),
        Shapeless("diamond_from_block", listOf("diamond_block"), "diamond", 9),
        Shapeless("emerald_from_block", listOf("emerald_block"), "emerald", 9),
        Shapeless("coal_from_block", listOf("coal_block"), "coal", 9),
        Shapeless("copper_ingot_from_block", listOf("copper_block"), "copper_ingot", 9)
    )

    /**
     * What actually pops out of a broken block, straight from the wiki's block-drop tables.
     * `null` means "nothing" (glass, ice, leaves without shears…).
     */
    val DROPS: Map<String, String> = mapOf(
        "stone" to "cobblestone",
        "grass" to "dirt",
        "coal_ore" to "coal",
        "iron_ore" to "raw_iron",
        "gold_ore" to "raw_gold",
        "diamond_ore" to "diamond",
        "redstone_wire_0" to "redstone",
        "gravel" to "gravel",
        "clay" to "clay",
        "snow_layer" to "snowball",
        "furnace_lit" to "furnace",
        "redstone_lamp_on" to "redstone_lamp_off",
        "redstone_torch_on" to "redstone_torch_off",
        "repeater_on" to "repeater_off",
        "comparator_on" to "comparator_off",
        "lever_on" to "lever_off",
        "stone_button_on" to "stone_button_off",
        "observer_on" to "observer",
        "piston_extended" to "piston",
        "snow" to "snowball"
    )

    /** Blocks that drop nothing when broken without shears/silk touch. */
    val NO_DROP: Set<String> = setOf(
        "glass", "ice", "packed_ice", "leaves", "spruce_leaves", "birch_leaves",
        "tall_grass", "dead_bush", "bedrock"
    )

    /** Smelting table (input -> result, experience). */
    val SMELTING: Map<String, Pair<String, Float>> = mapOf(
        "raw_iron" to ("iron_ingot" to 0.7f),
        "raw_gold" to ("gold_ingot" to 1.0f),
        "iron_ore" to ("iron_ingot" to 0.7f),
        "gold_ore" to ("gold_ingot" to 1.0f),
        "copper_ore" to ("copper_ingot" to 0.7f),
        "sand" to ("glass" to 0.1f),
        "cobblestone" to ("stone" to 0.1f),
        "stone" to ("stone_bricks" to 0.1f),
        "clay" to ("brick" to 0.3f),
        "raw_beef" to ("cooked_beef" to 0.35f),
        "porkchop" to ("cooked_porkchop" to 0.35f),
        "log" to ("charcoal" to 0.15f)
    )

    /** Fuel burn times in ticks (Java uses 200 ticks per coal). */
    val FUEL: Map<String, Int> = mapOf(
        "coal" to 1600, "charcoal" to 1600, "coal_block" to 16000,
        "oak_planks" to 300, "spruce_planks" to 300, "birch_planks" to 300,
        "stick" to 100, "log" to 300, "lava_bucket" to 20000
    )

    private fun matchesAny(key: Char, expected: String, actual: String): Boolean = when {
        expected == "#" -> anyPlank(actual)
        else -> expected == actual
    }

    /**
     * Matches a crafting grid (row-major, size 4 for 2x2 and 9 for 3x3) and returns the result.
     * Handles offset placement, mirroring and shaped/shapeless recipes.
     */
    fun craft(grid: List<String?>): ItemStack? {
        val size = if (grid.size == 4) 2 else 3
        val cells = Array(size) { r -> Array(size) { c -> grid.getOrNull(r * size + c) } }

        // Log -> planks (any wood type keeps its family).
        val filled = grid.filterNotNull()
        if (filled.size == 1) {
            val only = filled[0]
            val plank = when (only) {
                "log" -> "oak_planks"
                "spruce_log" -> "spruce_planks"
                "birch_log" -> "birch_planks"
                "dark_oak_log" -> "dark_oak_planks"
                "jungle_log" -> "jungle_planks"
                "acacia_log" -> "acacia_planks"
                else -> null
            }
            if (plank != null) return ItemStack(plank, 4)
        }

        // Shapeless
        for (r in SHAPELESS) {
            if (r.ingredients.size != filled.size) continue
            val pool = filled.toMutableList()
            var ok = true
            for (ing in r.ingredients) {
                val idx = pool.indexOfFirst { it == ing }
                if (idx < 0) {
                    ok = false
                    break
                }
                pool.removeAt(idx)
            }
            if (ok) return ItemStack(r.result, r.count)
        }

        // Shaped, with offset + mirror.
        for (recipe in SHAPED) {
            val ph = recipe.pattern.size
            val pw = recipe.pattern.maxOf { it.length }
            if (pw > size || ph > size) continue
            for (dy in 0..(size - ph)) {
                for (dx in 0..(size - pw)) {
                    for (mirror in listOf(false, true)) {
                        if (check(cells, recipe, dx, dy, mirror, size)) {
                            return ItemStack(recipe.result, recipe.count)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun check(
        cells: Array<Array<String?>>,
        recipe: Shaped,
        dx: Int, dy: Int, mirror: Boolean, size: Int
    ): Boolean {
        for (r in 0 until size) {
            for (c in 0 until size) {
                val pr = r - dy
                val pcRaw = c - dx
                val pc = if (mirror) (recipe.pattern[0].length - 1 - pcRaw) else pcRaw
                val expectedChar: Char = if (pr in recipe.pattern.indices && pcRaw in recipe.pattern[pr].indices) {
                    recipe.pattern[pr][pc]
                } else {
                    ' '
                }
                val expected = if (expectedChar == ' ') null else recipe.key[expectedChar] ?: continue
                val actual = cells[r][c]
                if (expected == null) {
                    if (actual != null) return false
                } else {
                    if (actual == null) return false
                    if (!matchesAny(expectedChar, expected, actual)) return false
                }
            }
        }
        return true
    }

    /** Smelting result for an input id, or null. */
    fun smelt(input: String): Pair<String, Float>? = SMELTING[input]
}
