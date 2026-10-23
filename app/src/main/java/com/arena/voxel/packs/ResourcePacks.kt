package com.arena.voxel.packs

import com.arena.voxel.core.Ctx
import com.arena.voxel.core.Log
import com.arena.voxel.core.Logs
import com.arena.voxel.textures.Textures
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Resource pack support.
 *
 * Accepted layouts (both are auto-detected):
 *  - <code>pack.zip</code> / <code>folder/</code> with <code>assets/minecraft/textures/...</code> (modern Java)
 *  - legacy packs with <code>assets/minecraft/textures/blocks/...</code>
 *  - loose packs with <code>textures/block/...</code> at the root
 *
 * Shaders are picked up from <code>shaders/terrain.vsh</code> style files inside a pack, or from any
 * folder/zip inside the <code>shaderpacks</code> folder.
 */
object ResourcePacks {

    data class PackInfo(
        val file: File,
        val name: String,
        val format: Int,
        val description: String,
        val kind: String,
        val tiles: Int = 0,
        val shaderFiles: Int = 0,
        /** True for Iris/OptiFine packs: their programs follow a different pipeline. */
        val javaShader: Boolean = false
    )

    class PackSource(val file: File) : AutoCloseable {
        private val zip: ZipFile? = if (file.isFile && file.name.lowercase().endsWith(".zip")) ZipFile(file) else null
        private val folder: File? = if (file.isDirectory) file else null

        val name: String = file.nameWithoutExtension

        fun entries(): List<String> {
            zip?.let { z -> return z.entries().toList().map { it.name } }
            folder?.let { f ->
                val out = ArrayList<String>()
                f.walkTopDown().forEach { if (it.isFile) out.add(it.relativeTo(f).invariantSeparatorsPath) }
                return out
            }
            return emptyList()
        }

        fun open(path: String): InputStream? {
            zip?.let { z -> return z.getEntry(path)?.let { z.getInputStream(it) } }
            folder?.let { f -> return File(f, path).takeIf { it.isFile }?.inputStream() }
            return null
        }

        fun read(path: String): ByteArray? = try {
            open(path)?.use { it.readBytes() }
        } catch (t: Throwable) {
            null
        }

        override fun close() {
            try {
                zip?.close()
            } catch (_: Throwable) {
            }
        }
    }

    /**
     * Vanilla texture names -> atlas tile names. Only base textures belong here: overlays
     * (grass_block_side_overlay), particles or GUI art must never replace a block texture or
     * the block ends up with an empty/invisible face.
     */
    private val ALIASES = mapOf(
        "grass_block_top" to "grass_top",
        "grass_block_side" to "grass_side",
        "oak_log" to "log_side",
        "oak_log_top" to "log_top",
        "oak_leaves" to "leaves",
        "jungle_leaves" to "leaves",
        "acacia_leaves" to "leaves",
        "dark_oak_leaves" to "leaves",
        "water_still" to "water",
        "water_flow" to "water",
        "bricks" to "brick",
        "snow_block" to "snow",
        "short_grass" to "tall_grass",
        "grass" to "tall_grass",
        "sandstone_top" to "sandstone",
        "sandstone_normal" to "sandstone",
        "oak_planks" to "oak_planks",
        "cobblestone" to "cobblestone",
        "dirt_path_top" to "dirt",
        "deadbush" to "dead_bush",
        // Log tiles are split into side/top in our atlas.
        "spruce_log" to "spruce_log_side",
        "spruce_log_top" to "spruce_log_top",
        "birch_log" to "birch_log_side",
        "birch_log_top" to "birch_log_top",
        "oak_log_side" to "log_side",
        "sandstone_side" to "sandstone",
        "snow_block" to "snow",
        "packed_ice" to "packed_ice"
    )

    /** Built-in pack shipped inside the APK (assets/builtinpack). */
    private const val BUILTIN_DIR = "builtinpack/textures/block"

    /**
     * Applies the textures bundled in the APK so the game does not look like flat programmer art
     * out of the box. They are plain 64x PNGs taken from the Faithful 64x pack the user provided
     * (see README for attribution); a user pack applied afterwards still wins.
     */
    fun applyBuiltin(): Int {
        val assets = try {
            com.arena.voxel.core.Ctx.app.assets
        } catch (t: Throwable) {
            return 0
        }
        val files = try {
            assets.list(BUILTIN_DIR)?.toList() ?: emptyList()
        } catch (t: Throwable) {
            return 0
        }
        var applied = 0
        var skipped = 0
        for (file in files) {
            if (!file.lowercase().endsWith(".png")) continue
            val name = normalize("textures/block/$file") ?: continue
            if (!Textures.isKnown(name)) {
                skipped++
                continue
            }
            val bmp = try {
                assets.open("$BUILTIN_DIR/$file").use { Textures.decode(it.readBytes()) }
            } catch (t: Throwable) {
                null
            } ?: continue
            if (!Textures.isUsableTexture(bmp)) {
                bmp.recycle()
                skipped++
                continue
            }
            if (PLANTS.any { name == it || name.startsWith(it) } && Textures.isOpaqueTexture(bmp)) {
                bmp.recycle()
                skipped++
                continue
            }
            if (Textures.replace(name, bmp)) applied++ else bmp.recycle()
        }
        Logs.info("PACK", "Pack integrado (Faithful 64x): $applied texturas, $skipped ignoradas")
        return applied
    }

    /** Only real block textures take part in the atlas. */
    private fun isBlockTexture(entry: String): Boolean {
        val lower = entry.lowercase()
        if (!lower.endsWith(".png")) return false
        if (!lower.contains("/textures/block/") && !lower.contains("/textures/blocks/") &&
            !lower.startsWith("textures/block/") && !lower.startsWith("textures/blocks/")
        ) return false
        // Skip animated/auxiliary files and anything that is not a base texture.
        if (lower.contains("_overlay") || lower.contains("destroy_stage") || lower.contains("_e.")
            || lower.contains("_emissive") || lower.contains("_normal") || lower.contains("_height")
        ) return false
        return true
    }

    private fun normalize(rawName: String): String? {
        val base = rawName.substringAfterLast('/').removeSuffix(".png").removeSuffix(".tga").lowercase()
        if (base.isEmpty()) return null
        ALIASES[base]?.let { return it }
        return base
    }

    /** All packs discovered in the resourcepack folder. */
    fun list(): List<PackInfo> = discover(Ctx.packsDir, "texturas")

    /** All shader packs discovered in the shaderpack folder. */
    fun listShaderPacks(): List<PackInfo> = discover(Ctx.shaderPacksDir, "shaders")

    private fun discover(dir: File, kind: String): List<PackInfo> {
        val files = dir.listFiles()?.filter {
            it.isDirectory || (it.isFile && it.name.lowercase().endsWith(".zip"))
        }?.sortedBy { it.name.lowercase() } ?: return emptyList()

        return files.mapNotNull { file ->
            try {
                PackSource(file).use { src ->
                    val entries = src.entries()
                    val format = readFormat(src)
                    val desc = readDescription(src)
                    val tiles = entries.count { isBlockTexture(it) }
                    val shaders = entries.count { it.contains("shaders/") && (it.endsWith(".vsh") || it.endsWith(".fsh")) }
                    val javaShader = isJavaShaderPack(src, entries)
                    PackInfo(file, src.name, format, desc, kind, tiles, shaders, javaShader)
                }
            } catch (t: Throwable) {
                Log.w("Pack invalido ${file.name}: ${t.message}")
                null
            }
        }
    }

    /**
     * Iris / OptiFine shader packs (BSL, Complementary, SEUS...) use gbuffers/composite/deferred
     * programs, shadow passes and uniforms that this engine does not implement, so feeding their
     * files into our slots only produces GLSL errors. They are detected and skipped.
     */
    private fun isJavaShaderPack(src: PackSource, entries: List<String>): Boolean {
        for (entry in entries) {
            val lower = entry.lowercase()
            if (!lower.contains("shaders/")) continue
            if (lower.endsWith(".properties")) return true
            val fileName = lower.substringAfterLast('/')
            if (fileName.startsWith("gbuffers_") || fileName.startsWith("composite") ||
                fileName.startsWith("deferred") || fileName.startsWith("shadow") ||
                fileName == "final.fsh" || fileName == "final.vsh"
            ) return true
            if (lower.endsWith(".fsh") || lower.endsWith(".vsh")) {
                val head = src.read(entry)?.let { String(it.copyOf(minOf(it.size, 400)), Charsets.UTF_8) }
                if (head != null && (head.contains("#version 330") || head.contains("#version 400") ||
                        head.contains("#version 410") || head.contains("#version 430") ||
                        head.contains("iris_") || head.contains("MC_"))
                ) return true
            }
        }
        return false
    }

    private fun readFormat(src: PackSource): Int {
        val mcmeta = src.entries().firstOrNull { it.endsWith("pack.mcmeta") } ?: return 0
        val text = src.read(mcmeta)?.toString(Charsets.UTF_8) ?: return 0
        return Regex("\"pack_format\"\\s*:\\s*(\\d+)").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    private fun readDescription(src: PackSource): String {
        val mcmeta = src.entries().firstOrNull { it.endsWith("pack.mcmeta") } ?: return ""
        val text = src.read(mcmeta)?.toString(Charsets.UTF_8) ?: return ""
        return Regex("\"description\"\\s*:\\s*\"([^\"]*)\"").find(text)?.groupValues?.get(1) ?: ""
    }

    /** Java item sprite path, any pack layout (`textures/item/`, `textures/items/`). */
    private fun isItemTexture(entry: String): Boolean {
        val lower = entry.lowercase()
        if (!lower.endsWith(".png")) return false
        if (!(lower.contains("textures/item/") || lower.contains("textures/items/"))) return false
        // GUI icons, armour layers and animated strips are not item sprites.
        val file = lower.substringAfterLast('/')
        return !file.contains("_layer") && !file.contains("empty") && !file.startsWith("gui")
    }

    /**
     * Normalises a Java item texture path to the engine's item id.
     *
     * Packs name the torch `torch.png`, the iron pickaxe `iron_pickaxe.png`, and 1.13+ vanilla
     * uses the same ids as the game, so the file name *is* the id. Legacy `items/` folders and
     * the `item/` folder are accepted, and a few old names are translated.
     */
    private fun normalizeItem(entry: String): String? {
        var name = entry.substringAfterLast('/')
        if (!name.lowercase().endsWith(".png")) return null
        name = name.substring(0, name.length - 4)
        if (name.isEmpty()) return null
        return when (name) {
            "apple_golden" -> "golden_apple"
            "bread" -> "bread"
            "seeds_wheat" -> "wheat_seeds"
            "door_wood" -> "oak_door"
            "boat" -> "oak_boat"
            else -> name
        }
    }

    /** Plant tiles drawn as crossed quads: they must keep transparency. */
    private val PLANTS = setOf("tall_grass", "dead_bush", "poppy", "dandelion", "sugar_cane", "torch")

    /**
     * Applies every resource pack, overriding the default tiles. Returns the applied pack names.
     * Must be called after [Textures.prepareDefaults].
     */
    fun applyAll(): List<String> {
        val applied = ArrayList<String>()
        val builtin = applyBuiltin()
        if (builtin > 0) applied.add("Integrado Faithful 64x ($builtin)")
        // Vanilla Java assets bundled in the APK (assets/vanilla/block). Real pack files still
        // override them, because they are applied after this line.
        val vanilla = applyVanilla()
        if (vanilla > 0) applied.add("Vanilla Java 1.21 ($vanilla)")
        val files = Ctx.packsDir.listFiles()?.filter {
            it.isDirectory || (it.isFile && it.name.lowercase().endsWith(".zip"))
        }?.sortedBy { it.name.lowercase() } ?: return applied

        for (file in files) {
            try {
                PackSource(file).use { src ->
                    var appliedCount = 0
                    var skipped = 0
                    var itemCount = 0
                    val seen = HashSet<String>()
                    for (entry in src.entries()) {
                        // ---- item sprites (Java: assets/minecraft/textures/item/<name>.png) ------
                        if (isItemTexture(entry)) {
                            val itemName = normalizeItem(entry)
                            if (itemName != null && seen.add("item/$itemName")) {
                                val bytes = src.read(entry) ?: continue
                                val bmp = Textures.decode(bytes, scaleToTile = false) ?: continue
                                Textures.putImage("item/$itemName", bmp)
                                itemCount++
                            }
                            continue
                        }
                        if (!isBlockTexture(entry)) continue
                        val name = normalize(entry) ?: continue
                        // Unknown names are still stored: mod blocks, 1.21 blocks the palette
                        // does not use yet and the built-in palette can pick them up by name.
                        if (!Textures.isKnown(name)) {
                            if (!seen.add("raw/$name")) continue
                            val bytes = src.read(entry) ?: continue
                            val bmp = Textures.decode(bytes) ?: continue
                            if (Textures.putImage("block/$name", bmp) >= 0) appliedCount++
                            continue
                        }
                        if (!seen.add(name)) continue
                        val bytes = src.read(entry) ?: continue
                        val bmp = Textures.decode(bytes) ?: continue
                        if (!Textures.isUsableTexture(bmp)) {
                            // Fully transparent texture would make the block invisible.
                            Logs.warn("PACK", "${src.name}: '$entry' es transparente, se ignora")
                            bmp.recycle()
                            skipped++
                            continue
                        }
                        if (PLANTS.any { name == it || name.startsWith(it) } && Textures.isOpaqueTexture(bmp)) {
                            // An opaque plant texture turns crossed quads into solid green walls.
                            Logs.warn(
                                "PACK",
                                "${src.name}: '$entry' es opaca para una planta ($name), se ignora"
                            )
                            bmp.recycle()
                            skipped++
                            continue
                        }
                        if (Textures.replace(name, bmp)) {
                            appliedCount++
                        } else {
                            bmp.recycle()
                        }
                    }
                    applied.add(
                        if (itemCount > 0) "${src.name} ($appliedCount bloques, $itemCount ítems)"
                        else "${src.name} ($appliedCount)"
                    )
                    Log.i("Pack aplicado: ${src.name} ($appliedCount texturas, $skipped ignoradas)")
                }
            } catch (t: Throwable) {
                Log.e("Error aplicando pack ${file.name}", t)
            }
        }
        return applied
    }

    /** Applies the vanilla block textures that ship inside the APK (assets/vanilla/block). */
    fun applyVanilla(): Int = try {
        if (!com.arena.voxel.core.VanillaAssets.available()) 0
        else com.arena.voxel.core.VanillaAssets.applyBlockTextures(
            alias = { name -> normalizeVanillaName(name) },
            replace = { name, bmp -> Textures.replace(name, bmp) }
        )
    } catch (t: Throwable) {
        Log.e("Texturas vanilla", t)
        0
    }

    /** `grass_block_top.png` -> `grass_top`, `bricks.png` -> `brick`, etc. using [ALIASES]. */
    private fun normalizeVanillaName(raw: String): String? {
        val name = raw.substringAfterLast('/')
        ALIASES[name]?.let { return it }
        if (Textures.isKnown(name)) return name
        // Log textures are split into side/top tiles in this atlas.
        if (name.endsWith("_log")) return null
        return name
    }

    /**
     * Loads the first usable **Minecraft Java shader pack** (OptiFine/Iris or vanilla post chain)
     * from `shaderpacks/`, then from `resourcepacks/`. Returns null when there is none, in which
     * case the built-in pipeline stays active.
     */
    fun loadJavaShaderPack(): JavaShaderPack.Loaded? {
        val candidates = ArrayList<File>()
        Ctx.shaderPacksDir.listFiles()?.filter {
            it.isDirectory || (it.isFile && it.name.lowercase().endsWith(".zip"))
        }?.sortedBy { it.name.lowercase() }?.let { candidates.addAll(it) }
        Ctx.packsDir.listFiles()?.filter {
            it.isDirectory || (it.isFile && it.name.lowercase().endsWith(".zip"))
        }?.sortedBy { it.name.lowercase() }?.let { candidates.addAll(it) }
        if (candidates.isEmpty()) return null
        for (file in candidates) {
            try {
                PackSource(file).use { src ->
                    val entries = src.entries()
                    if (!JavaShaderPack.looksLikeJavaPack(entries)) return@use
                    val loaded = JavaShaderPack.load(src.name, entries) { path ->
                        src.read(path)?.toString(Charsets.UTF_8)
                    }
                    if (loaded != null) {
                        Log.i("Shader pack de Java: ${loaded.packName} (${loaded.passes.size} pasadas)")
                        return loaded
                    }
                }
            } catch (t: Throwable) {
                Log.e("Shader pack ${file.name} no se pudo leer", t)
            }
        }
        return null
    }

    /** Collects shader sources from every pack. Later packs win. */
    fun loadShaderSources(): Pair<String, Map<String, String>> {
        val sources = HashMap<String, String>()
        var packName = "integrado"

        fun collect(src: PackSource, onlyShaders: Boolean) {
            for (entry in src.entries()) {
                val lower = entry.lowercase()
                if (!lower.contains("shaders/")) continue
                if (!(lower.endsWith(".vsh") || lower.endsWith(".fsh") || lower.endsWith(".glsl"))) continue
                val fileName = entry.substringAfterLast('/')
                val key = fileName.lowercase()
                val text = src.read(entry)?.toString(Charsets.UTF_8) ?: continue
                sources[key] = text
                if (onlyShaders) packName = src.name
            }
        }

        val shaderFiles = Ctx.shaderPacksDir.listFiles()?.filter {
            it.isDirectory || (it.isFile && it.name.lowercase().endsWith(".zip"))
        }?.sortedBy { it.name.lowercase() } ?: emptyList()

        for (file in shaderFiles) {
            try {
                PackSource(file).use { src ->
                    if (isJavaShaderPack(src, src.entries())) {
                        Log.i("Shader pack ${src.name}: pipeline de Iris/OptiFine, se omite")
                    } else {
                        collect(src, true)
                    }
                }
            } catch (t: Throwable) {
                Log.e("Shader pack invalido ${file.name}", t)
            }
        }
        val resFiles = Ctx.packsDir.listFiles()?.filter {
            it.isDirectory || (it.isFile && it.name.lowercase().endsWith(".zip"))
        }?.sortedBy { it.name.lowercase() } ?: emptyList()
        for (file in resFiles) {
            try {
                PackSource(file).use { src ->
                    if (!isJavaShaderPack(src, src.entries())) collect(src, false)
                }
            } catch (_: Throwable) {
            }
        }
        return packName to sources
    }

    /** Writes the built-in shaders to a folder so users can tweak them. */
    fun exportExampleShaderPack(): String {
        val root = File(Ctx.shaderPacksDir, "mis_shaders")
        root.mkdirs()
        File(root, "pack.mcmeta").writeText(
            "{\n  \"pack\": {\"pack_format\": 15, \"description\": \"Shader pack de ejemplo VoxelCraft\"}\n}\n"
        )
        val sh = File(root, "shaders").apply { mkdirs() }
        for ((name, src) in ShaderSources.all()) {
            File(sh, name).writeText(src)
        }
        return Ctx.relativePath(root)
    }
}
