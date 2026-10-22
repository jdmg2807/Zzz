package com.arena.voxel.world

/**
 * The bulk of the block palette, generated from the same colour families Minecraft uses.
 *
 * Hand-writing 700 block declarations is not practical, so the families are described
 * parametrically: 16 dye colours × wool/concrete/terracotta/stained glass/carpet, the wood
 * species with planks/stairs/slab/fence, the stone families with their polished and brick
 * variants, the copper oxidation ladder, and the 1.21 blocks (crafter, vault, trial spawner,
 * copper bulb/grate/door). Each entry carries the real hardness, tool and light level from the
 * wiki, and gets its own procedurally drawn 16×16 texture.
 *
 * The list is added to [Blocks] after the hand-tuned blocks, so existing ids keep their indices.
 */
object BlockGen {

    class Spec(
        val id: String,
        val top: String,
        val side: String,
        val bottom: String,
        val color: Int,
        val hardness: Float,
        val tool: String,
        val shape: BlockShape,
        val light: Int = 0,
        val opaque: Boolean = true,
        val solid: Boolean = true,
        val cross: Boolean = false
    )

    /** Tile name -> base colour, used by [com.arena.voxel.textures.TileGen]. */
    val colors = HashMap<String, Int>()

    /** Block id -> Spanish name, used by the item registry and the creative tabs. */
    val names = HashMap<String, String>()

    private val specsList = ArrayList<Spec>()

    val specs: List<Spec> get() = specsList

    /** Every texture name the palette needs. */
    fun tileNames(): List<String> {
        val out = LinkedHashSet<String>()
        for (s in specsList) {
            out.add(s.top)
            out.add(s.side)
            out.add(s.bottom)
        }
        return out.toList()
    }

    fun colorOf(tile: String): Int = colors[tile] ?: 0x808080

    // ------------------------------------------------------------------ building blocks

    private fun add(
        id: String,
        color: Int,
        nameEs: String,
        hardness: Float = 1f,
        tool: String = "",
        shape: BlockShape = BlockShape.FULL,
        light: Int = 0,
        opaque: Boolean = true,
        solid: Boolean = true,
        cross: Boolean = false,
        textures: List<String> = listOf(id)
    ) {
        val top = textures.getOrElse(0) { id }
        val side = textures.getOrElse(1) { top }
        val bottom = textures.getOrElse(2) { top }
        colors[top] = color
        colors[side] = color
        colors[bottom] = color
        specsList.add(Spec(id, top, side, bottom, color, hardness, tool, shape, light, opaque, solid, cross))
        names[id] = nameEs
    }

    /** Log-like block: distinct top and side textures. */
    private fun log(id: String, sideColor: Int, topColor: Int, nameEs: String) {
        val side = "${id}_side"
        val top = "${id}_top"
        colors[side] = sideColor
        colors[top] = topColor
        specsList.add(Spec(id, top, side, top, sideColor, 2f, "axe", BlockShape.FULL))
        names[id] = nameEs
    }

    private fun leaves(id: String, color: Int, nameEs: String) {
        colors[id] = color
        specsList.add(
            Spec(id, id, id, id, color, 0.2f, "hoe", BlockShape.FULL, opaque = false)
        )
        names[id] = nameEs
    }

    /** A stone family: base block plus slab and stairs made from the same texture. */
    private fun stoneFamily(base: String, color: Int, nameEs: String, hardness: Float, tool: String = "pickaxe") {
        val tex = "$base"
        add(tex, color, nameEs, hardness, tool)
        val key = base.removeSuffix("_block")
        add("${key}_slab", color, "$nameEs · losa", hardness, tool, BlockShape.SLAB_BOTTOM, textures = listOf(tex))
        add("${key}_stairs", color, "$nameEs · escaleras", hardness, tool, BlockShape.STAIRS_NORTH, textures = listOf(tex))
    }

    // ------------------------------------------------------------------ the palette

    private val DYES = listOf(
        Triple("white", 0xE9ECEC, "blanco"),
        Triple("orange", 0xF07613, "naranja"),
        Triple("magenta", 0xBD44B3, "magenta"),
        Triple("light_blue", 0x3AAFD9, "azul claro"),
        Triple("yellow", 0xF8C627, "amarillo"),
        Triple("lime", 0x70B919, "verde lima"),
        Triple("pink", 0xED8DAC, "rosa"),
        Triple("gray", 0x3E4447, "gris"),
        Triple("light_gray", 0x8E8E86, "gris claro"),
        Triple("cyan", 0x158991, "cian"),
        Triple("purple", 0x792AAC, "morado"),
        Triple("blue", 0x35399D, "azul"),
        Triple("brown", 0x724728, "marrón"),
        Triple("green", 0x546D1B, "verde"),
        Triple("red", 0xA12722, "rojo"),
        Triple("black", 0x141519, "negro")
    )

    private val WOODS = listOf(
        // id, planks colour, log side colour, log top colour, leaf colour (0 = no leaves)
        listOf("oak", "0xA9834F", "0x6B4F2A", "0xA2823F", "0x59AE30"),
        listOf("spruce", "0x735436", "0x3C2A17", "0x6B4F2A", "0x35543A"),
        listOf("birch", "0xC8B78A", "0xD8D4C4", "0xC3B98B", "0x80A755"),
        listOf("jungle", "0xA0704A", "0x554419", "0x9A7B4F", "0x30BB0B"),
        listOf("acacia", "0xB05B2A", "0x6A5A32", "0x76603A", "0x7A9A2B"),
        listOf("dark_oak", "0x4B3520", "0x3B2A1A", "0x4A3520", "0x3A6B1F"),
        listOf("mangrove", "0x773932", "0x5B3A2E", "0x6B4A32", "0x4A7A3A"),
        listOf("cherry", "0xE3B0A5", "0x3A2020", "0xE8C4C0", "0xF0A8C0")
    )

    init {
        // ---- dye families -------------------------------------------------------------
        for ((id, color, es) in DYES) {
            add("${id}_wool", color, "Lana $es", 0.8f, "", BlockShape.FULL)
            add("${id}_carpet", color, "Alfombra $es", 0.1f, "", BlockShape.LAYER, opaque = false,
                textures = listOf("${id}_wool"))
            add("${id}_concrete", color, "Hormigón $es", 1.8f, "pickaxe")
            add("${id}_terracotta", color, "Terracota $es", 1.25f, "pickaxe")
            add("${id}_stained_glass", color, "Cristal tintado $es", 0.3f, "", BlockShape.FULL, opaque = false)
        }

        // ---- wood species ------------------------------------------------------------
        for (row in WOODS) {
            val id = row[0]
            val planks = row[1].removePrefix("0x").toInt(16)
            val logSide = row[2].removePrefix("0x").toInt(16)
            val logTop = row[3].removePrefix("0x").toInt(16)
            val leaf = row[4].removePrefix("0x").toInt(16)
            val plural = when (id) {
                "oak" -> "roble"
                "spruce" -> "abeto"
                "birch" -> "abedul"
                "jungle" -> "jungla"
                "acacia" -> "acacia"
                "dark_oak" -> "roble oscuro"
                "mangrove" -> "mangle"
                else -> "cerezo"
            }
            add("${id}_planks", planks, "Tablones de $plural", 2f, "axe")
            log("${id}_log", logSide, logTop, "Tronco de $plural")
            add("${id}_stairs", planks, "Escaleras de $plural", 2f, "axe", BlockShape.STAIRS_NORTH,
                textures = listOf("${id}_planks"))
            add("${id}_slab", planks, "Losa de $plural", 2f, "axe", BlockShape.SLAB_BOTTOM,
                textures = listOf("${id}_planks"))
            add("${id}_fence", planks, "Valla de $plural", 2f, "axe", BlockShape.FENCE, opaque = false,
                textures = listOf("${id}_planks"))
            add("${id}_door", planks, "Puerta de $plural", 3f, "axe", BlockShape.FULL,
                textures = listOf("${id}_planks"))
            add("${id}_trapdoor", planks, "Trampilla de $plural", 3f, "axe", BlockShape.LAYER,
                textures = listOf("${id}_planks"))
            leaves("${id}_leaves", leaf, "Hojas de $plural")
        }

        // Nether woods (no leaves, different palette).
        for ((id, color, es) in listOf(
            Triple("crimson", 0x8E3A59, "carmesí"),
            Triple("warped", 0x2A6C6C, "distorsionado")
        )) {
            add("${id}_planks", color, "Tablones $es", 2f, "axe")
            log("${id}_stem", color, color, "Tallo $es")
            add("${id}_stairs", color, "Escaleras $es", 2f, "axe", BlockShape.STAIRS_NORTH,
                textures = listOf("${id}_planks"))
            add("${id}_slab", color, "Losa $es", 2f, "axe", BlockShape.SLAB_BOTTOM,
                textures = listOf("${id}_planks"))
            add("${id}_fence", color, "Valla $es", 2f, "axe", BlockShape.FENCE, opaque = false,
                textures = listOf("${id}_planks"))
        }

        // ---- stone families -----------------------------------------------------------
        stoneFamily("stone", 0x8A8A8A, "Piedra", 1.5f)
        stoneFamily("cobblestone", 0x7D7D7D, "Adoquín", 2f)
        stoneFamily("mossy_cobblestone", 0x6C7A5F, "Adoquín musgoso", 2f)
        stoneFamily("stone_bricks", 0x7A7A7A, "Ladrillos de piedra", 1.5f)
        stoneFamily("mossy_stone_bricks", 0x6C7560, "Ladrillos de piedra musgosos", 1.5f)
        stoneFamily("cracked_stone_bricks", 0x727272, "Ladrillos de piedra agrietados", 1.5f)
        stoneFamily("smooth_stone", 0x9F9F9F, "Piedra lisa", 2f)
        stoneFamily("granite", 0x9A6B58, "Granito", 1.5f)
        stoneFamily("polished_granite", 0x9A6B58, "Granito pulido", 1.5f)
        stoneFamily("diorite", 0xC4C4CA, "Diorita", 1.5f)
        stoneFamily("polished_diorite", 0xC9C9CF, "Diorita pulida", 1.5f)
        stoneFamily("andesite", 0x8A8A8D, "Andesita", 1.5f)
        stoneFamily("polished_andesite", 0x84848A, "Andesita pulida", 1.5f)
        stoneFamily("deepslate", 0x4A4A50, "Pizarra profunda", 3f)
        stoneFamily("cobbled_deepslate", 0x4E4E53, "Adoquín de pizarra", 3.5f)
        stoneFamily("polished_deepslate", 0x4A4A52, "Pizarra pulida", 3.5f)
        stoneFamily("deepslate_bricks", 0x3F4045, "Ladrillos de pizarra", 3.5f)
        stoneFamily("deepslate_tiles", 0x37383D, "Tejas de pizarra", 3.5f)
        stoneFamily("tuff", 0x6D6E63, "Toba", 1.5f)
        stoneFamily("polished_tuff", 0x6A6B60, "Toba pulida", 1.5f)
        stoneFamily("tuff_bricks", 0x63645A, "Ladrillos de toba", 1.5f)
        stoneFamily("blackstone", 0x3A3236, "Piedra negra", 1.5f)
        stoneFamily("polished_blackstone", 0x3B3539, "Piedra negra pulida", 2f)
        stoneFamily("sandstone", 0xD8CB92, "Arenisca", 0.8f)
        stoneFamily("red_sandstone", 0xB55A2A, "Arenisca roja", 0.8f)
        stoneFamily("quartz_block", 0xE8E3D8, "Bloque de cuarzo", 0.8f)
        stoneFamily("purpur_block", 0xA87BA8, "Bloque de purpur", 1.5f)
        stoneFamily("prismarine", 0x63A99A, "Prasmarina", 1.5f)
        stoneFamily("nether_bricks", 0x3A2021, "Ladrillos del Nether", 2f)
        stoneFamily("brick", 0xA2432F, "Ladrillos", 2f)
        stoneFamily("mud_bricks", 0x89685A, "Ladrillos de barro", 1.5f)

        // Decorative stone variants without a slab/stairs family.
        add("chiseled_stone_bricks", 0x767676, "Ladrillos de piedra cincelados", 1.5f, "pickaxe")
        add("chiseled_deepslate", 0x36373C, "Pizarra cincelada", 3.5f, "pickaxe")
        add("chiseled_tuff", 0x5F6055, "Toba cincelada", 1.5f, "pickaxe")
        add("chiseled_quartz_block", 0xE4DFD4, "Cuarzo cincelado", 0.8f, "pickaxe")
        add("chiseled_sandstone", 0xD6C88E, "Arenisca cincelada", 0.8f, "pickaxe")
        add("chiseled_red_sandstone", 0xB05528, "Arenisca roja cincelada", 0.8f, "pickaxe")
        add("calcite", 0xDFDFDA, "Calcita", 0.75f, "pickaxe")
        add("dripstone_block", 0x8A6650, "Bloque de estalactita", 1.5f, "pickaxe")
        add("basalt", 0x4A4A50, "Basalto", 1.25f, "pickaxe")
        add("smooth_basalt", 0x4C4C52, "Basalto liso", 1.25f, "pickaxe")
        add("obsidian", 0x2A1F3D, "Obsidiana", 50f, "pickaxe")
        add("crying_obsidian", 0x34205A, "Obsidiana llorosa", 50f, "pickaxe", light = 10)
        add("bedrock", 0x565656, "Piedra base", -1f, "")
        add("end_stone", 0xDDE0A0, "Piedra del End", 3f, "pickaxe")
        add("end_stone_bricks", 0xD8DB9C, "Ladrillos del End", 3f, "pickaxe")
        add("netherrack", 0x6B2B2B, "Infraescombro", 0.4f, "pickaxe")
        add("soul_sand", 0x51403A, "Arena de almas", 0.5f, "shovel")
        add("soul_soil", 0x4B3A32, "Tierra de almas", 0.5f, "shovel")
        add("magma_block", 0x8A3A1A, "Bloque de magma", 0.5f, "pickaxe", light = 3)
        add("glowstone", 0xC9A13C, "Piedra luminosa", 0.3f, "", light = 15)
        add("shroomlight", 0xF0A050, "Luz de hongo", 1f, "hoe", light = 15)
        add("sea_lantern", 0xBFD8D0, "Farol marino", 0.3f, "", light = 15)
        add("sponge", 0xC9C46A, "Esponja", 0.6f, "hoe")
        add("glass", 0xD7E9F2, "Cristal", 0.3f, "", opaque = false)
        add("glass_pane", 0xD7E9F2, "Panel de cristal", 0.3f, "", BlockShape.PANE, opaque = false,
            textures = listOf("glass"))
        add("iron_bars", 0xA8A8A8, "Barrotes de hierro", 5f, "pickaxe", BlockShape.PANE, opaque = false)

        // ---- dirt, sand and nature -----------------------------------------------------
        add("grass_block", 0x91BD59, "Bloque de césped", 0.6f, "shovel")
        add("dirt", 0x8B5A2B, "Tierra", 0.5f, "shovel")
        add("coarse_dirt", 0x7A5030, "Tierra estéril", 0.5f, "shovel")
        add("rooted_dirt", 0x8A5C3A, "Tierra enraizada", 0.5f, "shovel")
        add("podzol", 0x6B4A2A, "Podsol", 0.5f, "shovel")
        add("mycelium", 0x8B7A7A, "Micelio", 0.6f, "shovel")
        add("farmland", 0x6B4A2A, "Tierra de cultivo", 0.6f, "shovel")
        add("mud", 0x3C3838, "Barro", 0.5f, "shovel")
        add("packed_mud", 0x8F6B52, "Barro compacto", 1f, "pickaxe")
        add("clay", 0x9EA4B4, "Arcilla", 0.6f, "shovel")
        add("gravel", 0x8A8078, "Grava", 0.6f, "shovel")
        add("sand", 0xDBCF94, "Arena", 0.5f, "shovel")
        add("red_sand", 0xB55A2A, "Arena roja", 0.5f, "shovel")
        add("snow_block", 0xF5F8FB, "Bloque de nieve", 0.2f, "shovel")
        add("snow_layer", 0xF2F6FA, "Capa de nieve", 0.1f, "shovel", BlockShape.LAYER, opaque = false)
        add("ice", 0x9FC7F0, "Hielo", 0.5f, "pickaxe", opaque = false)
        add("packed_ice", 0x8FB6E8, "Hielo compacto", 0.5f, "pickaxe")
        add("blue_ice", 0x74A8F5, "Hielo azul", 2.8f, "pickaxe")
        add("moss_block", 0x5C7A2E, "Bloque de musgo", 0.1f, "hoe")
        add("hay_block", 0xC9A62A, "Fardo de heno", 0.5f, "hoe")
        add("pumpkin", 0xD48A1E, "Calabaza", 1f, "axe")
        add("carved_pumpkin", 0xD48A1E, "Calabaza tallada", 1f, "axe")
        add("jack_o_lantern", 0xE0A030, "Calabaza de Halloween", 1f, "axe", light = 15)
        add("melon", 0x6D9A2E, "Sandía", 1f, "axe")
        add("bookshelf", 0x8A6A3A, "Estantería", 1.5f, "axe")
        add("crafting_table", 0xB08A52, "Mesa de trabajo", 2.5f, "axe")
        add("barrel", 0x8A6A3A, "Barril", 2.5f, "axe")
        add("smithing_table", 0x4A4A55, "Mesa de herrería", 2.5f, "axe")
        add("stonecutter", 0x9A9A9A, "Cortapiedras", 3.5f, "pickaxe")
        add("grindstone", 0x8A8A8A, "Afiladora", 2f, "pickaxe")
        add("loom", 0xA08050, "Telar", 2.5f, "axe")
        add("composter", 0x8A6A3A, "Compostador", 0.6f, "axe")
        add("lectern", 0x9A7A4A, "Atril", 2.5f, "axe")
        add("cartography_table", 0x6A4A32, "Mesa de cartografía", 2.5f, "axe")
        add("fletching_table", 0xC8B78A, "Mesa de flechas", 2.5f, "axe")
        add("note_block", 0x6B4F2A, "Bloque musical", 0.8f, "axe")
        add("jukebox", 0x7A5A3A, "Tocadiscos", 2f, "axe")
        add("target", 0xC9A080, "Diana", 0.5f, "hoe")
        add("tnt", 0xD03A2A, "TNT", 0f, "")
        add("ladder", 0x8A6A3A, "Escalera de mano", 0.4f, "axe", BlockShape.FENCE, opaque = false)
        add("lantern", 0xC9A13C, "Farol", 3.5f, "pickaxe", light = 15)
        add("soul_lantern", 0x5AA0A0, "Farol de almas", 3.5f, "pickaxe", light = 10)
        add("campfire", 0x8A5A2A, "Hoguera", 2f, "axe", light = 15)
        add("enchanting_table", 0x5A2A6A, "Mesa de encantamientos", 5f, "pickaxe", light = 7)
        add("brewing_stand", 0x8A7A5A, "Soporte de pociones", 0.5f, "pickaxe", light = 1)
        add("cauldron", 0x4A4A4A, "Caldero", 2f, "pickaxe")
        add("anvil", 0x33333A, "Yunque", 5f, "pickaxe", BlockShape.ANVIL)
        add("chest", 0xA4712E, "Cofre", 2.5f, "axe")
        add("furnace", 0x7D7D7D, "Horno", 3.5f, "pickaxe")
        add("smoker", 0x77706A, "Ahumador", 3.5f, "pickaxe", light = 13)
        add("blast_furnace", 0x6F6F73, "Alto horno", 3.5f, "pickaxe", light = 13)
        add("torch", 0xFFC24A, "Antorcha", 0f, "", BlockShape.EMPTY, light = 14, opaque = false,
            solid = false, cross = true)
        add("soul_torch", 0x5AC8D8, "Antorcha de almas", 0f, "", BlockShape.EMPTY, light = 10,
            opaque = false, solid = false, cross = true)
        add("redstone_lamp", 0x4A3A2A, "Lámpara de redstone", 0.3f, "")
        add("redstone_block", 0xA82A1A, "Bloque de redstone", 5f, "pickaxe")

        // ---- ores ----------------------------------------------------------------------
        val ores = listOf(
            // id, colour, name, hardness, drop experience
            listOf("coal_ore", "0x5A5A5A", "Mena de carbón", "3"),
            listOf("iron_ore", "0xC0A080", "Mena de hierro", "3"),
            listOf("copper_ore", "0xB08A6A", "Mena de cobre", "3"),
            listOf("gold_ore", "0xD8B52A", "Mena de oro", "3"),
            listOf("redstone_ore", "0x8A2A2A", "Mena de redstone", "3"),
            listOf("lapis_ore", "0x3A5A9A", "Mena de lapislázuli", "3"),
            listOf("diamond_ore", "0x5FD8CF", "Mena de diamante", "3"),
            listOf("emerald_ore", "0x2ECC71", "Mena de esmeralda", "3")
        )
        for (row in ores) {
            val color = row[1].removePrefix("0x").toInt(16)
            add(row[0], color, row[2], row[3].toFloat(), "pickaxe")
            // Deepslate variant, 1.5x harder like in Java.
            add("deepslate_" + row[0], color, row[2] + " de pizarra", 4.5f, "pickaxe")
        }
        add("ancient_debris", 0x5C4B44, "Restos antiguos", 30f, "pickaxe")

        // ---- copper oxidation ladder ---------------------------------------------------
        val copper = listOf(
            Triple("copper", 0xC06A4A, "cobre"),
            Triple("exposed_copper", 0xA57A63, "cobre expuesto"),
            Triple("weathered_copper", 0x6F9A7C, "cobre degradado"),
            Triple("oxidized_copper", 0x52A07A, "cobre oxidado")
        )
        for ((id, color, es) in copper) {
            add(id, color, "Bloque de $es", 3f, "pickaxe")
            add("cut_$id", color, "Corte de $es", 3f, "pickaxe")
            add("cut_${id}_slab", color, "Losa de $es", 3f, "pickaxe", BlockShape.SLAB_BOTTOM,
                textures = listOf("cut_$id"))
            add("cut_${id}_stairs", color, "Escaleras de $es", 3f, "pickaxe",
                BlockShape.STAIRS_NORTH, textures = listOf("cut_$id"))
            add("chiseled_$id", color, "Cincelado de $es", 3f, "pickaxe")
            add("${id}_grate", color, "Rejilla de $es", 3f, "pickaxe", opaque = false)
            add("${id}_door", color, "Puerta de $es", 3f, "pickaxe")
            add("${id}_trapdoor", color, "Trampilla de $es", 3f, "pickaxe", BlockShape.LAYER)
        }
        add("copper_bulb", 0xD9A05A, "Bombilla de cobre", 3f, "pickaxe", light = 15)
        add("copper_bulb_lit", 0xE8B870, "Bombilla de cobre encendida", 3f, "pickaxe", light = 15)

        // ---- 1.21 blocks ---------------------------------------------------------------
        add("crafter", 0x8A6A4A, "Crafteador", 1.5f, "pickaxe")
        add("vault", 0x4D5257, "Bóveda", 50f, "pickaxe", light = 7)
        add("trial_spawner", 0x39424A, "Generador de pruebas", 50f, "pickaxe", light = 0)
        add("heavy_core", 0x4A4A55, "Núcleo pesado", 10f, "pickaxe")
        add("chiseled_copper", 0xB86A4A, "Cobre cincelado", 3f, "pickaxe")

        // ---- plants (cross models, no collision) ---------------------------------------
        val plants = listOf(
            listOf("dandelion", "0xF2D21B", "Diente de león"),
            listOf("poppy", "0xD02B1C", "Amapola"),
            listOf("blue_orchid", "0x2FA7D9", "Orquídea azul"),
            listOf("allium", "0xB06AC8", "Allium"),
            listOf("azure_bluet", "0xE8E8E0", "Rubia azulada"),
            listOf("oxeye_daisy", "0xE8E8D8", "Margarita"),
            listOf("cornflower", "0x4A6AE0", "Aciano"),
            listOf("lily_of_the_valley", "0xF0F5F0", "Lirio de los valles"),
            listOf("short_grass", "0x91BD59", "Hierba corta"),
            listOf("fern", "0x6BA03A", "Helecho"),
            listOf("dead_bush", "0x6B4F2A", "Arbusto muerto"),
            listOf("sugar_cane", "0x94C96A", "Caña de azúcar"),
            listOf("bamboo", "0xC3A44A", "Bambú")
        )
        for (row in plants) {
            val color = row[1].removePrefix("0x").toInt(16)
            add(row[0], color, row[2], 0f, "", BlockShape.EMPTY, opaque = false, solid = false, cross = true)
        }
        add("cactus", 0x0F7A30, "Cactus", 0.4f, "", BlockShape.CACTUS)
    }
}
