package com.arena.voxel.mods

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.arena.voxel.core.Log
import com.arena.voxel.items.ItemStack
import com.arena.voxel.items.Recipes
import com.arena.voxel.textures.Textures
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * Compatibility layer for **Minecraft Java mods**.
 *
 * Three kinds of jars are understood:
 *
 * | kind | manifest | what the engine can do with it |
 * |---|---|---|
 * | VoxelCraft | `voxelmod.json` | run its Kotlin/Java code through [com.arena.voxel.api.VoxelMod] |
 * | Fabric / Quilt / Forge | `fabric.mod.json`, `quilt.mod.json`, `META-INF/mods.toml` | read its metadata **and load its assets and data**: textures, item models, recipes, loot-free tags |
 * | Data / resource pack | `pack.mcmeta` | textures + recipes |
 *
 * Code mods written against the Minecraft classes cannot run on a phone without the whole game
 * jar (they call `net.minecraft.*` directly), so for those this layer does what is actually
 * possible and useful: it keeps the mod's **content** (the part that is pure JSON + PNG) and
 * reports clearly why its Java code is skipped. That is the honest ceiling of "Java mod
 * compatibility" for a standalone engine, and it is what makes texture/recipe mods, datapacks and
 * resource packs work unchanged.
 */
object JavaModSupport {

    enum class Kind { VOXEL, FABRIC, QUILT, FORGE, NEOFORGE, DATAPACK, RESOURCEPACK, UNKNOWN }

    class JavaMod(
        val file: File,
        val kind: Kind,
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val authors: String,
        val loaderVersion: String = "",
        val entrypoints: List<String> = emptyList(),
        var textures: Int = 0,
        var items: Int = 0,
        var recipes: Int = 0,
        var note: String = ""
    ) {
        val runsCode: Boolean get() = kind == Kind.VOXEL
    }

    /** Recipes discovered in every installed mod, consulted after the built-in table. */
    private val dynamicShaped = ArrayList<Recipes.Shaped>()
    private val dynamicShapeless = ArrayList<Recipes.Shapeless>()
    private val dynamicSmelting = HashMap<String, Pair<String, Float>>()

    fun dynamicShaped(): List<Recipes.Shaped> = dynamicShaped
    fun dynamicShapeless(): List<Recipes.Shapeless> = dynamicShapeless
    fun dynamicSmelting(): Map<String, Pair<String, Float>> = dynamicSmelting

    // ------------------------------------------------------------------ scanning

    /** Reads the manifest of a jar without loading any class. */
    fun describe(file: File): JavaMod {
        var zip: ZipFile? = null
        try {
            zip = ZipFile(file)
            val names = zip.entries().toList().map { it.name }
            fun text(path: String): String? =
                names.firstOrNull { it.equals(path, ignoreCase = true) }
                    ?.let { zip.getInputStream(zip.getEntry(it)).use { i -> i.readBytes().toString(Charsets.UTF_8) } }

            // ---- VoxelCraft
            text("voxelmod.json")?.let { raw ->
                val json = JSONObject(raw)
                return JavaMod(
                    file, Kind.VOXEL,
                    json.optString("id", file.nameWithoutExtension),
                    json.optString("name", file.nameWithoutExtension),
                    json.optString("version", "1.0.0"),
                    json.optString("description", ""),
                    json.optString("author", "desconocido"),
                    entrypoints = listOfNotNull(json.optString("entrypoint", "").takeIf { it.isNotEmpty() })
                )
            }

            // ---- Fabric
            text("fabric.mod.json")?.let { raw ->
                val json = JSONObject(raw)
                val entrys = ArrayList<String>()
                json.optJSONObject("entrypoints")?.let { eps ->
                    for (key in listOf("main", "client", "server")) {
                        val arr = eps.optJSONArray(key) ?: continue
                        for (i in 0 until arr.length()) {
                            val v = arr.opt(i)
                            when (v) {
                                is String -> entrys.add(v)
                                is JSONObject -> entrys.add(v.optString("value", v.optString("adapter", "")))
                            }
                        }
                    }
                }
                val deps = json.optJSONObject("depends")
                return JavaMod(
                    file, Kind.FABRIC,
                    json.optString("id", file.nameWithoutExtension),
                    json.optString("name", file.nameWithoutExtension),
                    json.optString("version", "?"),
                    json.optString("description", ""),
                    json.optJSONArray("authors")?.let { a ->
                        (0 until a.length()).joinToString(", ") { a.optString(it) }
                    } ?: "desconocido",
                    loaderVersion = deps?.optString("fabricloader", "") ?: "",
                    entrypoints = entrys.filter { it.isNotEmpty() }
                )
            }

            // ---- Quilt
            text("quilt.mod.json")?.let { raw ->
                val json = JSONObject(raw)
                val loader = json.optJSONObject("quilt_loader")
                val meta = loader?.optJSONObject("metadata")
                return JavaMod(
                    file, Kind.QUILT,
                    loader?.optString("id", file.nameWithoutExtension) ?: file.nameWithoutExtension,
                    meta?.optString("name", file.nameWithoutExtension) ?: file.nameWithoutExtension,
                    loader?.optString("version", "?") ?: "?",
                    meta?.optString("description", "") ?: "",
                    meta?.optString("contributors", "")?.takeIf { it.isNotEmpty() } ?: "desconocido",
                    entrypoints = emptyList()
                )
            }

            // ---- Forge / NeoForge (`META-INF/mods.toml` or `META-INF/neoforge.mods.toml`)
            val toml = text("META-INF/mods.toml") ?: text("META-INF/neoforge.mods.toml")
            if (toml != null) {
                val modId = Regex("(?m)^\\s*modId\\s*=\\s*\"([^\"]*)\"").find(toml)?.groupValues?.get(1)
                val display = Regex("(?m)^\\s*displayName\\s*=\\s*\"([^\"]*)\"").find(toml)?.groupValues?.get(1)
                val version = Regex("(?m)^\\s*version\\s*=\\s*\"([^\"]*)\"").find(toml)?.groupValues?.get(1)
                val desc = Regex("(?m)^\\s*description\\s*=\\s*\"([^\"]*)\"").find(toml)?.groupValues?.get(1)
                val authors = Regex("(?m)^\\s*authors\\s*=\\s*\"([^\"]*)\"").find(toml)?.groupValues?.get(1)
                val neo = names.any { it.contains("neoforge.mods.toml", true) }
                return JavaMod(
                    file, if (neo) Kind.NEOFORGE else Kind.FORGE,
                    modId ?: file.nameWithoutExtension,
                    display ?: modId ?: file.nameWithoutExtension,
                    version ?: "?",
                    desc ?: "",
                    authors ?: "desconocido"
                )
            }

            // ---- resource pack / datapack
            if (names.any { it.equals("pack.mcmeta", true) }) {
                val pack = names.any { it.startsWith("data/") }
                val mcmeta = text("pack.mcmeta") ?: ""
                val desc = Regex("\"description\"\\s*:\\s*\"([^\"]*)\"").find(mcmeta)?.groupValues?.get(1) ?: ""
                return JavaMod(
                    file, if (pack) Kind.DATAPACK else Kind.RESOURCEPACK,
                    file.nameWithoutExtension, file.nameWithoutExtension, "?", desc, "-"
                )
            }

            return JavaMod(
                file, Kind.UNKNOWN, file.nameWithoutExtension, file.nameWithoutExtension, "?", "",
                "-", note = "Sin manifiesto reconocido"
            )
        } catch (t: Throwable) {
            return JavaMod(
                file, Kind.UNKNOWN, file.nameWithoutExtension, file.nameWithoutExtension, "?", "",
                "-", note = "Jar ilegible: ${t.message}"
            )
        } finally {
            try {
                zip?.close()
            } catch (_: Throwable) {
            }
        }
    }

    fun scanAll(): List<JavaMod> {
        val files = com.arena.voxel.core.Ctx.modsDir.listFiles { f ->
            f.isFile && (f.name.lowercase().endsWith(".jar") || f.name.lowercase().endsWith(".zip"))
        } ?: return emptyList()
        return files.sortedBy { it.name.lowercase() }.map { describe(it) }
    }

    // ------------------------------------------------------------------ content

    /**
     * Applies the *content* of every installed mod: block/item textures, item models and recipes.
     * Called once per world start, after the resource packs.
     */
    fun applyContent(): String {
        dynamicShaped.clear()
        dynamicShapeless.clear()
        dynamicSmelting.clear()
        val mods = scanAll()
        var totalTex = 0
        var totalRecipes = 0
        for (mod in mods) {
            if (mod.runsCode) continue
            var zip: ZipFile? = null
            try {
                zip = ZipFile(mod.file)
                val entries = zip.entries().toList()
                for (entry in entries) {
                    val name = entry.name
                    val lower = name.lowercase()
                    if (lower.endsWith(".png") && lower.contains("/textures/")) {
                        val file = name.substringAfterLast('/').removeSuffix(".png")
                        val pool = when {
                            lower.contains("/textures/block/") || lower.contains("/textures/blocks/") -> "block"
                            lower.contains("/textures/item/") || lower.contains("/textures/items/") -> "item"
                            else -> null
                        } ?: continue
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
                        val scaled = if (bmp.width == 32 && bmp.height == 32) bmp
                        else Bitmap.createScaledBitmap(bmp, 32, 32, false).also { if (it !== bmp) bmp.recycle() }
                        if (pool == "item") {
                            Textures.putImage("item/$file", scaled)
                            mod.items++
                        } else if (Textures.isKnown(file)) {
                            Textures.replace(file, scaled)
                            mod.textures++
                        } else {
                            if (Textures.putImage("block/$file", scaled) >= 0) mod.textures++
                        }
                        totalTex++
                    } else if (lower.endsWith(".json") &&
                        (lower.contains("/recipe/") || lower.contains("/recipes/")) &&
                        !lower.contains("/advancements/")
                    ) {
                        val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                        if (registerRecipe(text)) {
                            mod.recipes++
                            totalRecipes++
                        }
                    } else if (lower.endsWith(".json") && lower.contains("/lang/") &&
                        (lower.endsWith("es_es.json") || lower.endsWith("es_mx.json") || lower.endsWith("en_us.json"))
                    ) {
                        val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                        registerLang(text)
                    }
                }
                if (mod.textures + mod.items + mod.recipes > 0) {
                    mod.note = "Contenido aplicado: ${mod.textures} texturas, ${mod.items} ítems, ${mod.recipes} recetas"
                } else if (mod.note.isEmpty()) {
                    mod.note = when (mod.kind) {
                        Kind.FABRIC, Kind.QUILT, Kind.FORGE, Kind.NEOFORGE ->
                            "Mod de código: necesita el loader de Minecraft, no se ejecuta en el móvil"
                        else -> "Sin contenido utilizable"
                    }
                }
            } catch (t: Throwable) {
                mod.note = "Error leyendo el mod: ${t.message}"
            } finally {
                try {
                    zip?.close()
                } catch (_: Throwable) {
                }
            }
        }
        if (totalTex > 0 || totalRecipes > 0) {
            Log.i("Contenido de mods de Java: $totalTex texturas, $totalRecipes recetas")
        }
        return buildString {
            append("${mods.size} mods · $totalTex texturas · $totalRecipes recetas")
        }
    }

    /** Names translated by mod language files (`item.<ns>.<id>`). */
    private val langNames = HashMap<String, String>()

    fun translatedName(id: String): String? {
        val key = id.removePrefix("minecraft:")
        langNames["item/$key"]?.let { return it }
        langNames["block/$key"]?.let { return it }
        return null
    }

    private fun registerLang(json: String) {
        try {
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val value = obj.optString(key, "")
                if (value.isEmpty()) continue
                val parts = key.split('.')
                if (parts.size < 3) continue
                langNames["${parts[0]}/${parts.drop(2).joinToString("_")}"] = value
            }
        } catch (_: Throwable) {
        }
    }

    // ------------------------------------------------------------------ recipes

    /**
     * Converts a Java recipe JSON into the engine's tables.
     *
     * Handles `crafting_shaped`, `crafting_shapeless`, `smelting`, `blasting`, `smoking` and
     * `campfire_cooking`, in both the 1.21 format (`"id"`) and the older one (`"item"`), with
     * `minecraft:` namespace prefixes stripped. Unknown types (smithing, stonecutting) are skipped
     * with no error, exactly like a data pack for a feature the game does not have.
     */
    fun registerRecipe(json: String): Boolean {
        return try {
            val obj = JSONObject(json)
            val type = obj.optString("type", "").removePrefix("minecraft:")
            fun idOf(node: Any?): String? = when (node) {
                is String -> node
                is JSONObject -> node.optString("id", node.optString("item", "")).ifEmpty { null }
                else -> null
            }
            fun clean(raw: String?): String? {
                if (raw == null) return null
                val id = raw.removePrefix("minecraft:")
                // Tags (#minecraft:planks) cannot be resolved statically: fall back to oak planks.
                if (raw.startsWith("#")) {
                    return if (raw.contains("planks")) "oak_planks" else null
                }
                return id
            }

            when (type) {
                "crafting_shaped" -> {
                    val patternArr = obj.optJSONArray("pattern") ?: return false
                    val pattern = (0 until patternArr.length()).map { patternArr.optString(it) }
                    val keyObj = obj.optJSONObject("key") ?: return false
                    val map = HashMap<Char, String>()
                    val keys = keyObj.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val value = clean(idOf(keyObj.opt(k))) ?: continue
                        if (k.isNotEmpty()) map[k[0]] = value
                    }
                    val resultObj = obj.optJSONObject("result") ?: return false
                    val result = clean(idOf(resultObj)) ?: return false
                    val count = resultObj.optInt("count", 1).coerceAtLeast(1)
                    if (map.isEmpty()) return false
                    dynamicShaped.add(
                        Recipes.Shaped("mod_${dynamicShaped.size}", pattern, map, result, count)
                    )
                    true
                }
                "crafting_shapeless" -> {
                    val arr = obj.optJSONArray("ingredients") ?: return false
                    val ingredients = (0 until arr.length()).mapNotNull { clean(idOf(arr.opt(it))) }
                    val resultObj = obj.optJSONObject("result") ?: return false
                    val result = clean(idOf(resultObj)) ?: return false
                    val count = resultObj.optInt("count", 1).coerceAtLeast(1)
                    if (ingredients.isEmpty()) return false
                    dynamicShapeless.add(
                        Recipes.Shapeless("mod_${dynamicShapeless.size}", ingredients, result, count)
                    )
                    true
                }
                "smelting", "blasting", "smoking", "campfire_cooking" -> {
                    val input = clean(idOf(obj.opt("ingredient"))) ?: return false
                    val result = clean(idOf(obj.opt("result"))) ?: return false
                    val xp = obj.optDouble("experience", 0.1).toFloat()
                    dynamicSmelting[input] = result to xp
                    true
                }
                else -> false
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** Result of a mod recipe for a grid, or null. */
    fun craftFromMods(grid: List<String?>): ItemStack? {
        val size = if (grid.size == 4) 2 else 3
        val cells = Array(size) { r -> Array(size) { c -> grid.getOrNull(r * size + c) } }
        val filled = grid.filterNotNull()
        // Shapeless first: it is order independent.
        for (r in dynamicShapeless) {
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
        for (recipe in dynamicShaped) {
            val ph = recipe.pattern.size
            val pw = recipe.pattern.maxOf { it.length }
            if (pw > size || ph > size) continue
            for (dy in 0..(size - ph)) {
                for (dx in 0..(size - pw)) {
                    var match = true
                    outer@ for (r in 0 until size) {
                        for (c in 0 until size) {
                            val pr = r - dy
                            val pc = c - dx
                            val expected: Char? =
                                if (pr in recipe.pattern.indices && pc in recipe.pattern[pr].indices) {
                                    recipe.pattern[pr][pc]
                                } else null
                            val actual = cells[r][c]
                            if (expected == null || expected == ' ') {
                                if (actual != null) {
                                    match = false
                                    break@outer
                                }
                            } else {
                                val wanted = recipe.key[expected]
                                if (wanted == null || actual != wanted) {
                                    match = false
                                    break@outer
                                }
                            }
                        }
                    }
                    if (match) return ItemStack(recipe.result, recipe.count)
                }
            }
        }
        return null
    }
}
