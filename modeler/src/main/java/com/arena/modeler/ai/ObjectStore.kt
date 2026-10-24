package com.arena.modeler.ai

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.arena.modeler.io.ExportResult
import com.arena.modeler.io.Gltf
import com.arena.modeler.io.ModelFormats
import com.arena.modeler.scene.SceneDocument
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Settings the user edits in the app and the projects saved on the device. Everything is plain
 * text/GLB inside the app's private folder: no database, no cloud, nothing leaves the phone
 * unless the user exports or calls an AI provider.
 */
object ObjectStore {

    private const val SETTINGS = "ajustes.json"
    private const val PROJECTS = "proyectos"
    private const val AUTOSAVE = "autoguardado.glb"

    private lateinit var appContext: Context

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    // ------------------------------------------------------------------ settings

    /** Provider chosen by the user and the keys typed in the AI panel. */
    class Settings(
        var provider: String = AiProvider.OPENROUTER.id,
        var openRouterKey: String = "",
        var googleKey: String = "",
        /** Model id per provider, so switching provider remembers both choices. */
        var openRouterModel: String = AiCatalog.openRouterModels.first().id,
        var googleModel: String = AiCatalog.googleModels.first().id,
        var showGrid: Boolean = true,
        var showWireframe: Boolean = false,
        var autoRotate: Boolean = false,
        var snap: Boolean = true,
        var lastExportFormat: String = "GLB"
    ) {
        fun currentProvider(): AiProvider = AiProvider.fromId(provider)
        fun keyFor(p: AiProvider): String = when (p) {
            AiProvider.OPENROUTER -> openRouterKey
            AiProvider.GOOGLE_AI_STUDIO -> googleKey
        }
        fun modelFor(p: AiProvider): String = when (p) {
            AiProvider.OPENROUTER -> openRouterModel
            AiProvider.GOOGLE_AI_STUDIO -> googleModel
        }
        fun setKey(p: AiProvider, key: String) {
            when (p) {
                AiProvider.OPENROUTER -> openRouterKey = key.trim()
                AiProvider.GOOGLE_AI_STUDIO -> googleKey = key.trim()
            }
        }
        fun setModel(p: AiProvider, model: String) {
            when (p) {
                AiProvider.OPENROUTER -> openRouterModel = model
                AiProvider.GOOGLE_AI_STUDIO -> googleModel = model
            }
        }
        fun hasKey(p: AiProvider = currentProvider()): Boolean = keyFor(p).isNotBlank()

        fun toJson(): JSONObject = JSONObject().apply {
            put("provider", provider)
            put("openRouterKey", openRouterKey)
            put("googleKey", googleKey)
            put("openRouterModel", openRouterModel)
            put("googleModel", googleModel)
            put("showGrid", showGrid)
            put("showWireframe", showWireframe)
            put("autoRotate", autoRotate)
            put("snap", snap)
            put("lastExportFormat", lastExportFormat)
        }

        fun load(json: JSONObject) {
            provider = json.optString("provider", provider)
            openRouterKey = json.optString("openRouterKey", openRouterKey)
            googleKey = json.optString("googleKey", googleKey)
            openRouterModel = json.optString("openRouterModel", openRouterModel)
            googleModel = json.optString("googleModel", googleModel)
            showGrid = json.optBoolean("showGrid", showGrid)
            showWireframe = json.optBoolean("showWireframe", showWireframe)
            autoRotate = json.optBoolean("autoRotate", autoRotate)
            snap = json.optBoolean("snap", snap)
            lastExportFormat = json.optString("lastExportFormat", lastExportFormat)
        }
    }

    private var cached: Settings? = null

    fun settings(): Settings {
        cached?.let { return it }
        val s = Settings()
        val f = File(appContext.filesDir, SETTINGS)
        if (f.exists()) {
            runCatching { s.load(JSONObject(f.readText())) }
        }
        cached = s
        return s
    }

    fun saveSettings() {
        val s = cached ?: return
        runCatching { File(appContext.filesDir, SETTINGS).writeText(s.toJson().toString(2)) }
    }

    // ------------------------------------------------------------------ projects

    class ProjectInfo(val fileName: String, val title: String, val modified: Long, val bytes: Long) {
        val id: String get() = fileName.substringBeforeLast('.')
    }

    private fun projectsDir(): File =
        File(appContext.filesDir, PROJECTS).also { if (!it.exists()) it.mkdirs() }

    fun listProjects(): List<ProjectInfo> {
        val files = projectsDir().listFiles { f -> f.isFile && f.name.endsWith(".glb") } ?: return emptyList()
        return files.sortedByDescending { it.lastModified() }.map { f ->
            ProjectInfo(f.name, f.name.substringBeforeLast('.').replace('_', ' '), f.lastModified(), f.length())
        }
    }

    /** Saves the document as GLB (the only format that keeps meshes, materials and animation). */
    fun saveProject(doc: SceneDocument): ProjectInfo {
        val name = ModelFormats.safeName(doc.name)
        val f = File(projectsDir(), "$name.glb")
        val bytes = Gltf.writeGlb(doc)
        f.writeBytes(bytes)
        return ProjectInfo(f.name, name, f.lastModified(), f.length())
    }

    fun loadProject(id: String): SceneDocument {
        val f = File(projectsDir(), "$id.glb")
        if (!f.exists()) throw IllegalArgumentException("El proyecto «$id» ya no existe")
        return ModelFormats.read(f.readBytes(), f.name).also { it.name = id.replace('_', ' ') }
    }

    fun deleteProject(id: String): Boolean = File(projectsDir(), "$id.glb").delete()

    /** Keeps a copy of whatever is on screen, so a crash never loses the whole model. */
    fun autosave(doc: SceneDocument) {
        runCatching {
            File(appContext.filesDir, AUTOSAVE).writeBytes(Gltf.writeGlb(doc))
            File(appContext.filesDir, "autoguardado.nombre").writeText(doc.name)
        }
    }

    fun autosaved(): SceneDocument? {
        val f = File(appContext.filesDir, AUTOSAVE)
        if (!f.exists() || f.length() < 64L) return null
        return runCatching {
            ModelFormats.read(f.readBytes(), "autoguardado.glb").also {
                it.name = File(appContext.filesDir, "autoguardado.nombre").let { n ->
                    if (n.exists()) n.readText() else "Autoguardado"
                }
            }
        }.getOrNull()
    }

    // ------------------------------------------------------------------ import / export

    /** Reads a file the user picked in the system picker. */
    fun readUri(uri: Uri): ByteArray {
        val stream = appContext.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("No se pudo abrir el archivo")
        return stream.use { it.readBytes() }
    }

    fun displayName(uri: Uri): String {
        if (uri.scheme == "file") return uri.lastPathSegment ?: "modelo"
        var name: String? = null
        runCatching {
            appContext.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
            }
        }
        return name ?: uri.lastPathSegment?.substringAfterLast('/') ?: "modelo"
    }

    /**
     * Writes the exported model (and its companions) where the user can find it: the public
     * Downloads folder on Android 10+, an app folder on older versions (no permission needed).
     */
    fun exportToDownloads(result: ExportResult): String {
        val folder = "VoxelForge"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, result.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(result.fileName))
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("No se pudo crear el archivo en Descargas")
            resolver.openOutputStream(uri)?.use { it.write(result.bytes) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            for (c in result.companions) {
                val cv = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, c.fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(c.fileName))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$folder")
                }
                val cu = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: continue
                resolver.openOutputStream(cu)?.use { it.write(c.bytes) }
            }
            return "Descargas/$folder/${result.fileName}"
        }
        val root = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            ?: appContext.filesDir
        val dir = File(root, folder).also { it.mkdirs() }
        File(dir, result.fileName).writeBytes(result.bytes)
        for (c in result.companions) File(dir, c.fileName).writeBytes(c.bytes)
        return dir.absolutePath + "/" + result.fileName
    }

    private fun mimeFor(fileName: String): String = when (fileName.substringAfterLast('.').lowercase()) {
        "glb" -> "model/gltf-binary"
        "gltf" -> "model/gltf+json"
        "obj" -> "text/plain"
        "mtl" -> "text/plain"
        "stl" -> "model/stl"
        "fbx" -> "application/octet-stream"
        "ply" -> "application/octet-stream"
        else -> "application/octet-stream"
    }

    /** Text dump of a project list, handy for the "abrir" sheet. */
    fun projectsJson(): String {
        val arr = JSONArray()
        for (p in listProjects()) {
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("title", p.title)
                    put("modified", p.modified)
                    put("size", p.bytes)
                }
            )
        }
        return arr.toString()
    }
}
