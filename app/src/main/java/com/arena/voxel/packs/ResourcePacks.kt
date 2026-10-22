package com.arena.voxel.packs

import com.arena.voxel.core.Ctx
import com.arena.voxel.core.Log
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

    /** Names used inside the atlas for vanilla tiles. */
    private val ALIASES = mapOf(
        "grass_block_top" to "grass_top",
        "grass_block_side" to "grass_side",
        "grass_block_side_overlay" to "grass_side",
        "grass_block_snow" to "snow_side",
        "grass_path_top" to "dirt",
        "oak_log" to "log_side",
        "oak_log_top" to "log_top",
        "oak_leaves" to "leaves",
        "oak_planks" to "oak_planks",
        "jungle_leaves" to "leaves",
        "birch_leaves" to "leaves",
        "water_still" to "water",
        "water_flow" to "water",
        "bricks" to "brick",
        "snow_block" to "snow",
        "iron_block" to "iron_ore",
        "stone_slab_top" to "stone",
        "cobblestone_slab_top" to "cobblestone"
    )

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
                    val tiles = entries.count { it.contains("/textures/") && it.endsWith(".png") }
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

    /**
     * Applies every resource pack, overriding the default tiles. Returns the applied pack names.
     * Must be called after [Textures.prepareDefaults].
     */
    fun applyAll(): List<String> {
        val applied = ArrayList<String>()
        val files = Ctx.packsDir.listFiles()?.filter {
            it.isDirectory || (it.isFile && it.name.lowercase().endsWith(".zip"))
        }?.sortedBy { it.name.lowercase() } ?: return applied

        for (file in files) {
            try {
                PackSource(file).use { src ->
                    var count = 0
                    for (entry in src.entries()) {
                        val lower = entry.lowercase()
                        if (!lower.endsWith(".png")) continue
                        if (!lower.contains("textures/")) continue
                        val name = normalize(entry) ?: continue
                        val bytes = src.read(entry) ?: continue
                        val bmp = Textures.decode(bytes) ?: continue
                        // Only known vanilla names are overridden; unknown names become new tiles.
                        if (!Textures.replace(name, bmp)) {
                            Textures.put(name, bmp)
                        }
                        count++
                    }
                    applied.add("${src.name} ($count)")
                    Log.i("Pack aplicado: ${src.name} ($count texturas)")
                }
            } catch (t: Throwable) {
                Log.e("Error aplicando pack ${file.name}", t)
            }
        }
        return applied
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
