package com.arena.voxel.imports

import android.net.Uri
import com.arena.voxel.Game
import com.arena.voxel.core.Ctx
import com.arena.voxel.core.Log
import com.arena.voxel.core.Logs
import com.arena.voxel.mods.ModLoader
import com.arena.voxel.packs.ResourcePacks
import java.io.File
import java.util.zip.ZipFile

/**
 * Single entry point for "import something from the phone".
 *
 * In v1.5.x every picked file was pushed through the world importer, so a texture pack ended up
 * with "No encuentro level.dat" and the pack was never installed. Here the file type is sniffed
 * from its contents (extension alone is not enough: mods, worlds and packs are all zip-based) and
 * routed to the right place.
 */
object AutoImport {

    enum class Kind(val label: String) {
        WORLD("mundo de Minecraft Java"),
        TEXTURES("resource pack de texturas"),
        SHADERS("shader pack"),
        MOD("mod"),
        UNKNOWN("archivo desconocido")
    }

    class Result(val kind: Kind, val success: Boolean, val message: String)

    /** Sniffs what a zip/jar actually contains. */
    fun classify(file: File): Kind {
        val lower = file.name.lowercase()
        if (lower.endsWith(".jar")) return Kind.MOD
        if (!lower.endsWith(".zip") && !lower.endsWith(".mcworld")) return Kind.UNKNOWN
        return try {
            ZipFile(file).use { zf ->
                val names = ArrayList<String>(2048)
                for (entry in zf.entries()) {
                    if (names.size >= 6000) break
                    names.add(entry.name.lowercase())
                }
                classifyEntries(names)
            }
        } catch (t: Throwable) {
            Log.w("AutoImport: no puedo abrir ${file.name}: ${t.message}")
            Kind.UNKNOWN
        }
    }

    private fun classifyEntries(names: List<String>): Kind {
        var textures = 0
        var shaders = 0
        var level = 0
        var region = 0
        var mod = 0
        for (n in names) {
            if (n.endsWith("level.dat")) level++
            if (n.contains("region/") && n.endsWith(".mca")) region++
            if (n.endsWith("voxelmod.json") || n.endsWith(".class") && n.contains("boot-inf") == false && !n.startsWith("meta-inf")) mod++
            if (n.endsWith(".png") && (n.contains("textures/block/") || n.contains("textures/blocks/"))) textures++
            if (n.contains("shaders/") && (n.endsWith(".vsh") || n.endsWith(".fsh"))) shaders++
        }
        return when {
            level > 0 || region > 0 -> Kind.WORLD
            mod > 0 -> Kind.MOD
            textures > 0 -> Kind.TEXTURES
            shaders > 0 -> Kind.SHADERS
            else -> Kind.UNKNOWN
        }
    }

    /**
     * Copies [uri] into the imports folder, classifies it and installs it.
     * Runs on a worker thread (never on the GL thread).
     */
    fun perform(uri: Uri, hint: Kind? = null): Result {
        val dir = File(Ctx.base, "imports").apply { mkdirs() }
        val name = UriFiles.name(uri).ifBlank { "archivo.zip" }.replace('/', '_').replace('\\', '_')
        val target = File(dir, name)
        try {
            UriFiles.open(uri)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            } ?: return Result(hint ?: Kind.UNKNOWN, false, "No se pudo leer el archivo seleccionado")
        } catch (t: Throwable) {
            Log.e("AutoImport: error copiando $name", t)
            return Result(hint ?: Kind.UNKNOWN, false, "No se pudo copiar el archivo: ${t.message}")
        }

        Logs.info("IMPORT", "Archivo: $name (${target.length()} bytes)")
        val kind = if (hint != null && hint != Kind.UNKNOWN) hint else classify(target)
        Logs.info("IMPORT", "Detectado como: ${kind.label}")

        return when (kind) {
            Kind.WORLD -> {
                // `target` is the copy made above: the importer reuses it instead of copying twice.
                val r = McWorldImporter.importWorld(uri, dir, target)
                Result(Kind.WORLD, r.success, r.message)
            }
            Kind.TEXTURES -> installPack(target, Ctx.packsDir, Kind.TEXTURES)
            Kind.SHADERS -> installPack(target, Ctx.shaderPacksDir, Kind.SHADERS)
            Kind.MOD -> {
                val message = try {
                    ModLoader.importMod(target.inputStream(), packName(name, ".jar"))
                } catch (t: Throwable) {
                    "No se pudo instalar el mod: ${t.message}"
                }
                Logs.info("IMPORT", message)
                Result(Kind.MOD, !message.startsWith("No se pudo"), "$message\nSe carga al entrar a un mundo.")
            }
            Kind.UNKNOWN -> Result(
                Kind.UNKNOWN,
                false,
                "No reconozco '$name'.\n" +
                    "Acepta: mundos (zip con level.dat + region/), resource packs de texturas " +
                    "(zip con assets/minecraft/textures o textures/block), shader packs " +
                    "(zip con shaders/*.vsh/.fsh) y mods (.jar con voxelmod.json)."
            )
        }
    }

    private fun packName(name: String, ext: String): String {
        val base = name.replace(Regex("\\.[A-Za-z0-9]+$"), "")
        return if (base.lowercase().endsWith(ext)) base else base + ext
    }

    private fun installPack(source: File, dir: File, kind: Kind): Result {
        dir.mkdirs()
        var name = source.name
        if (!name.lowercase().endsWith(".zip")) name = packName(name, ".zip")
        val target = File(dir, name)
        // The user may keep importing the same pack: overwrite, but log it.
        if (target.exists()) {
            Logs.info("IMPORT", "Reemplazando pack existente ${target.name}")
            target.delete()
        }
        return try {
            source.copyTo(target, overwrite = true)
            val listed = if (kind == Kind.SHADERS) ResourcePacks.listShaderPacks() else ResourcePacks.list()
            val info = listed.firstOrNull { it.file.name == target.name }
            val detail = when {
                info == null -> "instalado"
                kind == Kind.SHADERS -> "${info.shaderFiles} shaders" +
                    if (info.javaShader) " (pipeline de Iris/OptiFine: se omite)" else ""
                else -> "${info.tiles} texturas · pack_format ${info.format}" +
                    if (info.shaderFiles > 0) " · ${info.shaderFiles} shaders" else ""
            }
            val message = "${if (kind == Kind.SHADERS) "Shader pack" else "Resource pack"} instalado: $name\n$detail\n" +
                "Se aplica al entrar a un mundo (Ajustes → texturas/shaders activados)."
            Logs.info("IMPORT", message.replace("\n", " · "))
            Result(kind, true, message)
        } catch (t: Throwable) {
            Log.e("AutoImport: no se pudo instalar $name", t)
            Result(kind, false, "No se pudo instalar el pack: ${t.message}")
        }
    }

    /** Installs a file that is already in the app storage (used by the packs/mods screens). */
    fun refreshPacksReport(): String {
        val packs = ResourcePacks.list()
        val shaders = ResourcePacks.listShaderPacks()
        return "Packs: ${packs.size} · shaders: ${shaders.size}"
    }

    /** Exposes the classification to the UI without touching the file twice. */
    fun kindOf(uri: Uri): Kind {
        val dir = File(Ctx.base, "imports").apply { mkdirs() }
        val name = UriFiles.name(uri).ifBlank { "archivo.zip" }.replace('/', '_').replace('\\', '_')
        val target = File(dir, name)
        return try {
            UriFiles.open(uri)?.use { input -> target.outputStream().use { out -> input.copyTo(out) } }
            classify(target)
        } catch (t: Throwable) {
            Kind.UNKNOWN
        }
    }

    /** Called from the menu: never blocks the UI thread. */
    fun run(uri: Uri, hint: Kind?, onDone: (String, Boolean, Kind) -> Unit) {
        Thread {
            val result = try {
                perform(uri, hint)
            } catch (t: Throwable) {
                Log.e("Import inesperado", t)
                Result(hint ?: Kind.UNKNOWN, false, "Error inesperado al importar: ${t.message}")
            }
            Game.reportImport(result.message)
            if (result.success && result.kind == Kind.WORLD) {
                Game.startLoadedWorld()
            }
            try {
                com.arena.voxel.core.Logs.exportToDownloads(Ctx.app)
            } catch (t: Throwable) {
                Log.w("No se pudo exportar el log: ${t.message}")
            }
            onDone(result.message, result.success, result.kind)
        }.start()
    }
}
