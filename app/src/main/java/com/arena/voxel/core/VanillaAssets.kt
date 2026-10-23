package com.arena.voxel.core

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.arena.voxel.world.Blocks
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Vanilla **Java Edition assets** shipped inside the APK.
 *
 * Source: the `InventivetalentDev/minecraft-assets` repository (branch `1.21.9`), which republishes
 * the files extracted from the official client jar: block and item textures, item models, the
 * `es_es`/`en_us` language files and the OGG sound effects. They live under `assets/vanilla/`:
 *
 * ```
 * vanilla/block/<name>.png        block textures      (1166 files)
 * vanilla/item/<name>.png         item sprites        (768 files)
 * vanilla/models_item/<name>.json item models         (textures.layer0 -> sprite)
 * vanilla/lang/es_es.json         display names
 * vanilla/sounds/ (recursivo)      sound effects (OGG)
 * ```
 *
 * Using real assets is what makes the inventory icons, the block textures and the sound effects
 * look and sound like the game instead of approximations; the engine code stays original.
 * The folder can be deleted (or trimmed) without breaking the build: everything here is optional
 * and falls back to the procedural art and synthesisers.
 */
object VanillaAssets {

    private const val ROOT = "vanilla"

    /** Display name map: item/block id -> translated name. */
    private val names = ConcurrentHashMap<String, String>()
    private var langLoaded = false

    /** id -> item sprite path inside `vanilla/item` (cached). */
    private val itemSprites = ConcurrentHashMap<String, String>()

    private val bitmapCache = ConcurrentHashMap<String, Bitmap?>(64)

    fun available(): Boolean = try {
        Ctx.app.assets.list(ROOT)?.isNotEmpty() == true
    } catch (t: Throwable) {
        false
    }

    private fun list(vararg path: String): Array<String> = try {
        Ctx.app.assets.list(path.joinToString("/")) ?: emptyArray()
    } catch (t: Throwable) {
        emptyArray()
    }

    override fun toString(): String = "VanillaAssets"

    // ------------------------------------------------------------------ language

    private fun ensureLang() {
        if (langLoaded) return
        langLoaded = true
        for (file in listOf("lang/es_es.json", "lang/es_mx.json", "lang/en_us.json")) {
            val text = readText(file) ?: continue
            try {
                val json = JSONObject(text)
                val keys = json.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = json.optString(key, "")
                    if (value.isEmpty()) continue
                    // "block.minecraft.stone" -> "block/stone"; "item.minecraft.torch" -> "item/torch"
                    val parts = key.split('.')
                    if (parts.size < 3) continue
                    val kind = parts[0]
                    val id = parts.drop(2).joinToString("_")
                    names["$kind/$id"] = value
                }
            } catch (t: Throwable) {
                Log.w("Lang vanilla ilegible: $file (${t.message})")
            }
            if (names.isNotEmpty()) break
        }
        if (names.isNotEmpty()) Log.i("Nombres de vanilla cargados: ${names.size}")
    }

    /** Spanish display name for a block/item id, or null when the assets do not know it. */
    fun displayName(id: String): String? {
        ensureLang()
        val key = id.removePrefix("minecraft:")
        if (Blocks.index(key) != 0) names["block/$key"]?.let { return it }
        names["item/$key"]?.let { return it }
        names["block/$key"]?.let { return it }
        return null
    }

    // ------------------------------------------------------------------ item sprites

    /**
     * Resolves the sprite of an item from its Java model (`assets/minecraft/models/item/<id>.json`),
     * following the `parent` chain until a `layer0` texture shows up.
     */
    fun itemSprite(id: String): String? {
        val key = id.removePrefix("minecraft:")
        itemSprites[key]?.let { return it.ifEmpty { null } }
        var current = key
        var depth = 0
        var resolved = ""
        while (depth < 6) {
            val text = readText("models_item/$current.json") ?: break
            try {
                val json = JSONObject(text)
                val textures = json.optJSONObject("textures")
                val layer = textures?.optString("layer0", "")?.takeIf { it.isNotEmpty() }
                    ?: textures?.optString("texture", "")?.takeIf { it.isNotEmpty() }
                if (layer != null) {
                    resolved = layer.removePrefix("minecraft:")
                        .removePrefix("item/")
                        .removePrefix("block/")
                    break
                }
                val parent = json.optString("parent", "").removePrefix("minecraft:")
                if (parent.isEmpty() || parent.startsWith("builtin/")) break
                // "item/generated" is a builtin template, the sprite is the id itself.
                if (!parent.startsWith("item/")) break
                current = parent.removePrefix("item/")
                if (current == "generated" || current.startsWith("handheld") || current.startsWith("template")) {
                    resolved = key
                    break
                }
            } catch (t: Throwable) {
                break
            }
            depth++
        }
        if (resolved.isEmpty()) resolved = key
        itemSprites[key] = if (exists("item/$resolved.png")) resolved else ""
        return itemSprites[key]?.ifEmpty { null }
    }

    /** Decoded item sprite, or null when this id has no vanilla sprite. */
    fun itemBitmap(id: String): Bitmap? {
        val sprite = itemSprite(id) ?: return null
        bitmapCache[id]?.let { return it }
        val bmp = decode("item/$sprite.png")
        bitmapCache[id] = bmp
        return bmp
    }

    // ------------------------------------------------------------------ block textures

    /**
     * Applies the vanilla block textures onto the engine's tiles.
     *
     * Only tiles the palette actually uses are decoded (the atlas holds 1024 tiles and vanilla
     * ships 1166 block textures), which keeps start-up fast and the atlas compact.
     */
    fun applyBlockTextures(alias: (String) -> String?, replace: (String, Bitmap) -> Boolean): Int {
        var applied = 0
        for (file in list("block")) {
            if (!file.endsWith(".png")) continue
            val raw = file.removeSuffix(".png")
            val name = alias(raw) ?: raw
            val bytes = read("block/$file") ?: continue
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            val scaled = if (bmp.width == 32 && bmp.height == 32) bmp
            else Bitmap.createScaledBitmap(bmp, 32, 32, false).also { if (it !== bmp) bmp.recycle() }
            // Animated strips arrive as tall images: keep the first frame.
            if (scaled.height > scaled.width && scaled.height % scaled.width == 0) {
                val frame = Bitmap.createBitmap(scaled, 0, 0, scaled.width, scaled.width)
                scaled.recycle()
                if (replace(name, frame)) applied++ else frame.recycle()
                continue
            }
            if (replace(name, scaled)) applied++ else scaled.recycle()
        }
        Log.i("Texturas de vanilla aplicadas: $applied")
        return applied
    }

    // ------------------------------------------------------------------ sound files

    /** Absolute asset path of a vanilla sound effect, or null. */
    fun soundPath(relative: String): String? {
        val path = "sounds/$relative"
        return if (exists(path)) "$ROOT/$path" else null
    }

    /** Every OGG file under `sounds/` whose path contains [folder], as asset paths. */
    fun soundFiles(folder: String): List<String> {
        val out = ArrayList<String>()
        fun walk(path: String) {
            for (entry in list(path)) {
                val full = "$path/$entry"
                if (entry.endsWith(".ogg")) {
                    if (path.contains(folder)) out.add("$ROOT/$full")
                } else if (!entry.contains('.')) {
                    walk(full)
                }
            }
        }
        walk("sounds")
        return out
    }

    // ------------------------------------------------------------------ io helpers

    /** AssetFileDescriptor for [SoundPool.load], or null when the asset is missing. */
    fun open(path: String): android.content.res.AssetFileDescriptor? = try {
        Ctx.app.assets.openFd("$ROOT/$path")
    } catch (t: Throwable) {
        null
    }

    fun exists(path: String): Boolean = try {
        Ctx.app.assets.open("$ROOT/$path").use { true }
    } catch (t: Throwable) {
        false
    }

    fun read(path: String): ByteArray? = try {
        Ctx.app.assets.open("$ROOT/$path").use { it.readBytes() }
    } catch (t: Throwable) {
        null
    }

    fun readText(path: String): String? = read(path)?.toString(Charsets.UTF_8)

    fun decode(path: String): Bitmap? = read(path)?.let { bytes ->
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    /** Convenience for the settings screen. */
    fun summary(): String {
        val blocks = list("block").count { it.endsWith(".png") }
        val items = list("item").count { it.endsWith(".png") }
        val models = list("models_item").count { it.endsWith(".json") }
        return "$blocks texturas de bloque · $items de ítem · $models modelos"
    }

    /** Root folder used by the shader loader for `assets/minecraft/shaders` (unused placeholder). */
    val rootFolder: File? get() = null
}
