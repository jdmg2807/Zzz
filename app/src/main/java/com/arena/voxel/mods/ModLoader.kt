package com.arena.voxel.mods

import com.arena.voxel.Game
import com.arena.voxel.api.VoxelMod
import com.arena.voxel.core.Ctx
import com.arena.voxel.core.Log
import dalvik.system.DexClassLoader
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

data class ModDescriptor(
    val file: File,
    val id: String,
    val name: String,
    val version: String,
    val author: String,
    val entrypoint: String,
    var error: String? = null,
    var loaded: Boolean = false
)

/**
 * Loads .jar mods from the mods folder with a DexClassLoader.
 * Each jar can ship Java/Kotlin classes and a voxelmod.json descriptor.
 */
object ModLoader {

    val mods = ArrayList<VoxelMod>()
    private val hosts = HashMap<String, ModHost>()

    fun scan(): List<ModDescriptor> {
        val out = ArrayList<ModDescriptor>()
        val files = Ctx.modsDir.listFiles { f -> f.isFile && f.name.lowercase().endsWith(".jar") }
            ?: return out
        for (file in files.sortedBy { it.name.lowercase() }) {
            var zip: ZipFile? = null
            try {
                zip = ZipFile(file)
                val entry = zip.getEntry("voxelmod.json")
                    ?: zip.entries().toList().firstOrNull { it.name.endsWith("voxelmod.json") }
                if (entry == null) {
                    out.add(
                        ModDescriptor(
                            file, file.nameWithoutExtension, file.nameWithoutExtension,
                            "?", "?", "", "Falta voxelmod.json"
                        )
                    )
                } else {
                    val text = zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
                    val json = JSONObject(text)
                    out.add(
                        ModDescriptor(
                            file = file,
                            id = json.optString("id", file.nameWithoutExtension),
                            name = json.optString("name", file.nameWithoutExtension),
                            version = json.optString("version", "1.0.0"),
                            author = json.optString("author", "desconocido"),
                            entrypoint = json.optString("entrypoint", "")
                        )
                    )
                }
            } catch (t: Throwable) {
                out.add(
                    ModDescriptor(
                        file, file.nameWithoutExtension, file.nameWithoutExtension,
                        "?", "?", "", "Jar invalido: ${t.message}"
                    )
                )
            } finally {
                try {
                    zip?.close()
                } catch (_: Throwable) {
                }
            }
        }
        return out
    }

    /** Loads and enables every mod jar. Called on the render thread before the world is playable. */
    fun loadAll(): String {
        unloadAll()
        val descriptors = scan()
        val report = StringBuilder()
        for (d in descriptors) {
            if (d.entrypoint.isEmpty()) {
                d.error = "entrypoint vacio"
                report.append("${d.name}: sin entrypoint\n")
                continue
            }
            var loader: DexClassLoader? = null
            try {
                val codeCache = File(Ctx.app.codeCacheDir, "mods").apply { mkdirs() }
                // Android 14+ requires dynamically loaded code files to be read-only.
                d.file.setReadOnly()
                loader = DexClassLoader(d.file.absolutePath, codeCache.absolutePath, null, javaClass.classLoader)
                val cls = loader.loadClass(d.entrypoint)
                val instance = cls.getDeclaredConstructor().newInstance()
                if (instance !is VoxelMod) {
                    d.error = "La clase ${d.entrypoint} no implementa VoxelMod"
                    report.append("${d.name}: clase incorrecta\n")
                    continue
                }
                val host = ModHost(d)
                hosts[d.id] = host
                instance.onLoad(host)
                mods.add(instance)
                d.loaded = true
                report.append("${d.name} ${d.version} cargado\n")
                Log.i("Mod cargado: ${d.name} ${d.version}")
            } catch (t: Throwable) {
                d.error = t.toString()
                Log.e("Error cargando mod ${d.name}", t)
                report.append("${d.name}: ${t.message ?: t.toString()}\n")
            }
        }
        if (descriptors.isEmpty()) report.append("No hay mods instalados")
        return report.toString().trim()
    }

    fun unloadAll() {
        mods.clear()
        hosts.clear()
    }

    fun tick(dt: Float) {
        for (m in mods) {
            try {
                m.onTick(dt)
            } catch (t: Throwable) {
                Log.e("Error en onTick", t)
            }
        }
    }

    fun worldReady() {
        for (m in mods) {
            try {
                m.onWorldReady()
            } catch (t: Throwable) {
                Log.e("Error en onWorldReady", t)
            }
        }
    }

    fun blockPlaced(x: Int, y: Int, z: Int, id: String) {
        for (m in mods) {
            try {
                m.onBlockPlaced(x, y, z, id)
            } catch (t: Throwable) {
                Log.e("Error en onBlockPlaced", t)
            }
        }
    }

    fun blockBroken(x: Int, y: Int, z: Int, id: String) {
        for (m in mods) {
            try {
                m.onBlockBroken(x, y, z, id)
            } catch (t: Throwable) {
                Log.e("Error en onBlockBroken", t)
            }
        }
    }

    fun count(): Int = mods.size

    fun installed(): List<ModDescriptor> = scan()

    fun importMod(source: java.io.InputStream, fileName: String): String {
        return try {
            val target = File(Ctx.modsDir, fileName)
            if (target.exists()) target.setWritable(true)
            target.outputStream().use { out -> source.use { it.copyTo(out) } }
            target.setReadOnly()
            "Mod importado: $fileName"
        } catch (t: Throwable) {
            "No se pudo importar: ${t.message}"
        } finally {
            Game.chat("Mods recargados al entrar al mundo")
        }
    }
}
