package com.arena.modeler.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arena.modeler.ai.AiCatalog
import com.arena.modeler.ai.AiClient
import com.arena.modeler.ai.AiProvider
import com.arena.modeler.ai.ModelRegistry
import com.arena.modeler.ai.ObjectStore
import com.arena.modeler.core.Quat
import com.arena.modeler.core.V3
import com.arena.modeler.io.ExportFormat
import com.arena.modeler.io.Gltf
import com.arena.modeler.io.ModelFormats
import com.arena.modeler.io.UnsupportedFormatException
import com.arena.modeler.render.ViewportView
import com.arena.modeler.scene.AnimPath
import com.arena.modeler.scene.AnimationClip
import com.arena.modeler.scene.Material
import com.arena.modeler.scene.Primitives
import com.arena.modeler.scene.SceneDocument
import com.arena.modeler.scene.SceneNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The tabs of the bottom panel. */
enum class Tab(val label: String) {
    OBJECTS("Objetos"),
    EDIT("Editar"),
    MATERIAL("Material"),
    ANIMATE("Animar"),
    AI("IA"),
    FILES("Archivo")
}

/**
 * Single place where the UI mutates the document. Compose state lives here; the document itself
 * (plain Kotlin objects) is guarded by its own monitor because the GL thread reads it too.
 */
class AppState(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    var doc by mutableStateOf<SceneDocument?>(null)
        private set

    var selectedId by mutableStateOf(-1)
        private set

    var tab by mutableStateOf(Tab.OBJECTS)
    var panelOpen by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)
    var busyLabel by mutableStateOf<String?>(null)
    var showSettings by mutableStateOf(false)
    var undoDepth by mutableIntStateOf(0)
    var revision by mutableIntStateOf(0)
    var dirty by mutableStateOf(false)

    var playing by mutableStateOf(false)
    var animTime by mutableStateOf(0f)
    var clipIndex by mutableIntStateOf(0)

    // ---- settings mirrored as Compose state ---------------------------------
    private val stored = ObjectStore.settings()
    var provider by mutableStateOf(AiProvider.fromId(stored.provider))
    var openRouterKey by mutableStateOf(stored.openRouterKey)
    var googleKey by mutableStateOf(stored.googleKey)
    var openRouterModel by mutableStateOf(stored.openRouterModel)
    var googleModel by mutableStateOf(stored.googleModel)
    var showGrid by mutableStateOf(stored.showGrid)
    var showWireframe by mutableStateOf(stored.showWireframe)
    var autoRotate by mutableStateOf(stored.autoRotate)
    var lastExportFormat by mutableStateOf(stored.lastExportFormat)

    var aiPrompt by mutableStateOf("")
    var aiRefine by mutableStateOf(false)

    // ---- model catalogue ----------------------------------------------------
    var modelList by mutableStateOf<List<ModelRegistry.ModelEntry>>(emptyList())
        private set
    var modelFilter by mutableStateOf(ModelRegistry.PriceFilter.ALL)
        private set
    var modelQuery by mutableStateOf("")
    var modelLoading by mutableStateOf(false)
        private set
    var modelStatus by mutableStateOf<String?>(null)
    var modelsShown by mutableIntStateOf(24)

    private var viewport: ViewportView? = null
    private val undoStack = ArrayDeque<ByteArray>()

    init {
        active = this
        modelList = ModelRegistry.cached(provider)
        val autosaved = ObjectStore.autosaved()
        val initial = autosaved ?: SceneDocument("Sin título").also { it.materialFor() }
        doc = initial
        selectedId = initial.allNodes().firstOrNull()?.id ?: -1
    }

    // ------------------------------------------------------------------ viewport

    fun attach(view: ViewportView, frameAllOnAttach: Boolean = true) {
        viewport = view
        view.state.onSelect = { id ->
            selectedId = id
            if (id >= 0 && tab == Tab.OBJECTS) tab = Tab.EDIT
        }
        view.state.onTransform = { revision++ }
        view.state.doc = doc
        syncViewport()
        if (frameAllOnAttach) view.frameAll()
    }

    fun detach() {
        viewport?.state?.onSelect = null
        viewport?.state?.onTransform = null
        viewport = null
    }

    /** Pushes every UI-owned flag into the GL state. Called on each recomposition. */
    fun syncViewport(frame: Boolean = false) {
        val v = viewport ?: return
        if (v.state.doc !== doc) {
            v.setDocument(doc, frame)
        }
        v.state.selectedId = selectedId
        v.state.showGrid = showGrid
        v.state.showWireframe = showWireframe
        v.state.autoRotate = autoRotate
        v.state.showBones = true
        v.state.playing = playing
        v.state.clipIndex = clipIndex
        v.state.animTime = animTime
        v.setAnimated(playing || autoRotate)
    }

    fun selectedNode(): SceneNode? {
        val d = doc ?: return null
        if (selectedId < 0) return null
        return d.nodeById(selectedId)
    }

    fun select(id: Int) {
        selectedId = id
        syncViewport()
        viewport?.refresh()
    }

    fun frameAll() {
        viewport?.frameAll()
    }

    fun frameSelection() {
        val node = selectedNode() ?: run { frameAll(); return }
        val b = node.worldBounds() ?: run { frameAll(); return }
        viewport?.state?.camera?.frame(b, 1f)
        viewport?.refresh()
    }

    fun cameraFront() { viewport?.state?.camera?.setFront(); viewport?.refresh() }
    fun cameraSide() { viewport?.state?.camera?.setSide(); viewport?.refresh() }
    fun cameraTop() { viewport?.state?.camera?.setTop(); viewport?.refresh() }

    // ------------------------------------------------------------------ settings

    fun persistSettings() {
        stored.provider = provider.id
        stored.openRouterKey = openRouterKey
        stored.googleKey = googleKey
        stored.openRouterModel = openRouterModel
        stored.googleModel = googleModel
        stored.showGrid = showGrid
        stored.showWireframe = showWireframe
        stored.autoRotate = autoRotate
        stored.lastExportFormat = lastExportFormat
        ObjectStore.saveSettings()
    }

    fun currentModel(): String =
        if (provider == AiProvider.OPENROUTER) openRouterModel else googleModel

    fun currentKey(): String =
        if (provider == AiProvider.OPENROUTER) openRouterKey else googleKey

    fun setKey(key: String) {
        if (provider == AiProvider.OPENROUTER) openRouterKey = key.trim() else googleKey = key.trim()
        persistSettings()
        // With a fresh key the catalogue can be downloaded, so show the real models right away.
        if (key.isNotBlank()) refreshModels(provider, silent = true)
    }

    fun setModel(model: String) {
        if (provider == AiProvider.OPENROUTER) openRouterModel = model else googleModel = model
        persistSettings()
    }

    fun chooseProvider(p: AiProvider) {
        if (provider == p) return
        provider = p
        modelQuery = ""
        modelsShown = 24
        modelList = ModelRegistry.cached(p)
        modelStatus = null
        persistSettings()
    }

    // ------------------------------------------------------------------ model catalogue

    /** Downloads the provider's live list (all models the account can use). */
    fun refreshModels(target: AiProvider = provider, silent: Boolean = false) {
        if (modelLoading) return
        val key = if (target == AiProvider.OPENROUTER) openRouterKey else googleKey
        if (target == AiProvider.GOOGLE_AI_STUDIO && key.isBlank()) {
            if (!silent) message = "Pega primero la API key de Google AI Studio para ver sus modelos."
            return
        }
        modelLoading = true
        if (!silent) modelStatus = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ModelRegistry.fetch(target, key) }
            }
            modelLoading = false
            result.fold(
                onSuccess = { listing ->
                    ModelRegistry.save(target, listing.models)
                    if (target == provider) {
                        modelList = listing.models
                        modelsShown = 24
                        val free = listing.models.count { it.free }
                        modelStatus = "${listing.models.size} modelos · $free gratis"
                        if (!silent) message = "Lista actualizada: ${listing.models.size} modelos disponibles"
                    }
                },
                onFailure = { e ->
                    if (target == provider && modelList.isEmpty()) {
                        modelList = ModelRegistry.cached(target)
                    }
                    modelStatus = "No se pudo actualizar"
                    if (!silent) {
                        message = "No se pudo descargar la lista: ${e.message}"
                    }
                }
            )
        }
    }

    fun applyPriceFilter(filter: ModelRegistry.PriceFilter) {
        modelFilter = filter
        modelsShown = 24
    }

    /** Models that pass the free/paid filter and the search box. */
    fun visibleModels(): List<ModelRegistry.ModelEntry> {
        val query = modelQuery.trim().lowercase()
        return modelList.filter { m ->
            val priceOk = when (modelFilter) {
                ModelRegistry.PriceFilter.ALL -> true
                ModelRegistry.PriceFilter.FREE -> m.free
                ModelRegistry.PriceFilter.PAID -> !m.free
            }
            val queryOk = query.isEmpty() ||
                m.id.lowercase().contains(query) || m.label.lowercase().contains(query)
            priceOk && queryOk
        }
    }

    fun freeModelCount(): Int = modelList.count { it.free }

    fun paidModelCount(): Int = modelList.count { !it.free }

    // ------------------------------------------------------------------ document

    fun newScene(name: String = "Sin título") {
        pushUndo()
        val d = SceneDocument(name)
        d.materialFor()
        adopt(d, frame = true)
        dirty = false
    }

    fun adopt(d: SceneDocument, frame: Boolean = true) {
        stopPlayback()
        doc = d
        clipIndex = 0
        animTime = 0f
        selectedId = d.allNodes().firstOrNull()?.id ?: -1
        revision++
        dirty = true
        viewport?.setDocument(d, frame)
        viewport?.state?.selectedId = selectedId
    }

    fun replaceScene(d: SceneDocument) {
        pushUndo()
        adopt(d, frame = true)
    }

    fun addPrimitive(kind: Primitives.Kind) {
        val d = doc ?: return
        pushUndo()
        var created: SceneNode? = null
        synchronized(d) {
            val node = d.newNode(d.nextNodeName(kind.label))
            node.mesh = Primitives.build(kind)
            node.material = d.materialFor()
            val target = viewport?.state?.camera?.target
            node.position = V3(target?.x ?: 0f, 0f, target?.z ?: 0f)
            created = node
        }
        created?.let { selectedId = it.id }
        revision++
        dirty = true
        viewport?.refresh()
    }

    fun deleteSelected() {
        val d = doc ?: return
        val node = selectedNode() ?: return
        pushUndo()
        synchronized(d) {
            node.parent?.children?.remove(node)
            node.removeFromParent()
        }
        selectedId = -1
        revision++
        dirty = true
        viewport?.refresh()
    }

    fun duplicateSelected() {
        val d = doc ?: return
        val source = selectedNode() ?: return
        pushUndo()
        var copy: SceneNode? = null
        synchronized(d) {
            val node = d.newNode(d.nextNodeName(source.name.substringBefore('.')))
            node.mesh = source.mesh?.copy()
            node.material = source.material ?: d.materialFor()
            node.position = V3(source.position.x + 0.35f, source.position.y, source.position.z)
            node.rotation = source.rotation.copy()
            node.scale = source.scale.copy()
            val target = source.parent
            if (target != null && target !== d.root) {
                d.root.children.remove(node)
                target.add(node)
            }
            copy = node
        }
        copy?.let { selectedId = it.id }
        revision++
        dirty = true
        viewport?.refresh()
    }

    fun renameSelected(name: String) {
        val node = selectedNode() ?: return
        node.name = name.ifBlank { "Objeto" }
        revision++
        dirty = true
    }

    fun setVisible(node: SceneNode, visible: Boolean) {
        node.visible = visible
        revision++
        viewport?.refresh()
    }

    fun setPosition(x: Float, y: Float, z: Float) {
        val node = selectedNode() ?: return
        node.position.set(x, y, z)
        node.markDirty()
        revision++
        dirty = true
        viewport?.refresh()
    }

    fun setRotation(x: Float, y: Float, z: Float) {
        val node = selectedNode() ?: return
        node.rotation = Quat.euler(x, y, z)
        node.markDirty()
        revision++
        dirty = true
        viewport?.refresh()
    }

    fun setScale(x: Float, y: Float, z: Float) {
        val node = selectedNode() ?: return
        node.scale.set(x.coerceAtLeast(0.01f), y.coerceAtLeast(0.01f), z.coerceAtLeast(0.01f))
        node.markDirty()
        revision++
        dirty = true
        viewport?.refresh()
    }

    fun dropToFloor() {
        val d = doc ?: return
        val node = selectedNode() ?: return
        val b = node.worldBounds() ?: return
        val local = node.localMatrix()
        val scaleY = kotlin.math.sqrt(local[1] * local[1] + local[5] * local[5] + local[9] * local[9])
        val parentY = node.parent?.worldMatrix()?.get(13) ?: 0f
        node.position.y -= (b[1] - parentY) / (if (scaleY > 1e-4f) scaleY else 1f)
        node.markDirty()
        revision++
        viewport?.refresh()
    }

    fun setMaterialColor(color: FloatArray) {
        val node = selectedNode() ?: return
        val d = doc ?: return
        val mat = node.material ?: d.materialFor(color).also { node.material = it }
        mat.baseColor = color
        revision++
        dirty = true
    }

    fun setMaterialProps(metallic: Float? = null, roughness: Float? = null) {
        val node = selectedNode() ?: return
        val d = doc ?: return
        val mat = node.material ?: d.materialFor().also { node.material = it }
        if (metallic != null) mat.metallic = metallic.coerceIn(0f, 1f)
        if (roughness != null) mat.roughness = roughness.coerceIn(0.02f, 1f)
        revision++
        dirty = true
    }

    fun setEmission(on: Boolean) {
        val node = selectedNode() ?: return
        val d = doc ?: return
        val mat = node.material ?: d.materialFor().also { node.material = it }
        mat.emission = if (on) floatArrayOf(0.35f, 0.55f, 1f)
        else floatArrayOf(0f, 0f, 0f)
        revision++
        dirty = true
        viewport?.refresh()
    }

    // ------------------------------------------------------------------ animation

    fun currentClip(): AnimationClip? {
        val d = doc ?: return null
        if (clipIndex < 0 || clipIndex >= d.animations.size) return null
        return d.animations[clipIndex]
    }

    fun createTurntable() {
        val d = doc ?: return
        val node = selectedNode() ?: d.allNodes().firstOrNull() ?: return
        pushUndo()
        synchronized(d) { AnimationClip.demo(d, node, 4f) }
        clipIndex = d.animations.size - 1
        revision++
        dirty = true
        togglePlay(true)
    }

    fun deleteClip() {
        val d = doc ?: return
        val clip = currentClip() ?: return
        pushUndo()
        synchronized(d) { d.animations.remove(clip) }
        clipIndex = 0
        revision++
        dirty = true
    }

    fun keyframe(path: AnimPath) {
        val d = doc ?: return
        val node = selectedNode() ?: return
        val clip = currentClip() ?: run {
            val created = AnimationClip("Animación")
            d.animations.add(created)
            clipIndex = d.animations.size - 1
            created
        }
        synchronized(d) { clip.keyNode(node, animTime, path) }
        revision++
        dirty = true
        message = "Clave añadida en ${"%.2f".format(animTime)} s"
    }

    fun seekAnimation(t: Float) {
        val clip = currentClip() ?: return
        animTime = t.coerceIn(0f, clip.duration().coerceAtLeast(0.01f))
        clip.apply(doc ?: return, animTime)
        viewport?.refresh()
    }

    fun togglePlay(force: Boolean? = null) {
        val clip = currentClip()
        if (clip == null) {
            message = "Primero crea una animación (botón «Crear giro»)"
            return
        }
        val next = force ?: !playing
        if (next && !playing) viewport?.state?.captureRest()
        if (!next && playing) viewport?.state?.restoreRest()
        playing = next
        if (next) animTime = 0f
        viewport?.setAnimated(playing || autoRotate)
    }

    fun setBackground(color: FloatArray) {
        viewport?.state?.background = color
        viewport?.refresh()
    }

    fun enableAutoRotate(on: Boolean) {
        autoRotate = on
        persistSettings()
        viewport?.setAnimated(playing || autoRotate)
    }

    fun stopPlayback() {
        if (playing) {
            playing = false
            viewport?.state?.restoreRest()
        }
        animTime = 0f
    }

    // ------------------------------------------------------------------ files

    fun export(format: ExportFormat) {
        val d = doc ?: return
        lastExportFormat = format.name
        persistSettings()
        busyLabel = "Exportando ${format.extension.uppercase()}…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val export = ModelFormats.export(d, format)
                    ObjectStore.exportToDownloads(export)
                }
            }
            busyLabel = null
            result.fold(
                onSuccess = { path -> message = "Guardado en $path" },
                onFailure = { e -> message = "No se pudo exportar: ${e.message}" }
            )
        }
    }

    /** Reads a document picked with the system file picker (SAF) on a background thread. */
    fun importUri(uri: Uri) {
        val name = ObjectStore.displayName(uri)
        busyLabel = "Abriendo $name…"
        scope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { ObjectStore.readUri(uri) }
            }
            busyLabel = null
            bytes.fold(
                onSuccess = { importBytes(it, name) },
                onFailure = { message = "No se pudo leer el archivo: ${it.message}" }
            )
        }
    }

    fun importBytes(bytes: ByteArray, fileName: String) {
        busyLabel = "Abriendo $fileName…"
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val imported = ModelFormats.read(bytes, fileName)
                    imported.name = fileName.substringBeforeLast('.').ifBlank { "Importado" }
                    if (imported.allNodes().isEmpty()) {
                        throw IllegalArgumentException("El archivo no tenía geometría")
                    }
                    imported
                }
            }
            busyLabel = null
            outcome.fold(
                onSuccess = { imported ->
                    replaceScene(imported)
                    message = "Importado: ${imported.totalTriangles()} triángulos, ${imported.animations.size} animación(es)"
                },
                onFailure = { e ->
                    message = if (e is UnsupportedFormatException) e.message else "Error al abrir: ${e.message}"
                }
            )
        }
    }

    fun saveProject() {
        val d = doc ?: return
        busyLabel = "Guardando proyecto…"
        scope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { ObjectStore.saveProject(d) } }
            busyLabel = null
            info.fold(
                onSuccess = {
                    dirty = false
                    message = "Proyecto guardado: ${it.title}"
                },
                onFailure = { message = "No se pudo guardar: ${it.message}" }
            )
        }
    }

    fun projects(): List<ObjectStore.ProjectInfo> = ObjectStore.listProjects()

    fun openProject(info: ObjectStore.ProjectInfo) {
        busyLabel = "Abriendo ${info.title}…"
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { runCatching { ObjectStore.loadProject(info.id) } }
            busyLabel = null
            loaded.fold(
                onSuccess = {
                    adopt(it, frame = true)
                    message = "Proyecto abierto: ${info.title}"
                },
                onFailure = { message = "No se pudo abrir: ${it.message}" }
            )
        }
    }

    fun deleteProject(info: ObjectStore.ProjectInfo) {
        ObjectStore.deleteProject(info.id)
        revision++
        message = "Proyecto «${info.title}» eliminado"
    }

    fun shareText(): String = buildString {
        val d = doc ?: return@buildString
        appendLine("${d.name} — hecho con VoxelForge 3D")
        appendLine("${d.allNodes().size} objetos, ${d.totalTriangles()} triángulos")
        appendLine("Animaciones: ${d.animations.joinToString { it.name }.ifBlank { "ninguna" }}")
        append("Formatos: glTF/GLB, FBX ASCII, OBJ, STL, PLY")
    }

    // ------------------------------------------------------------------ AI

    fun runAi(idea: String, refine: Boolean) {
        val prompt = idea.trim()
        if (prompt.isEmpty()) {
            message = "Escribe qué quieres crear"
            return
        }
        val key = currentKey()
        if (key.isBlank()) {
            message = "Falta la API key de ${provider.label}. Pégala arriba."
            return
        }
        val model = currentModel()
        busyLabel = if (refine) "Modificando con IA…" else "Creando el modelo con IA…"
        val previous = if (refine) doc else null
        val providerSnapshot = provider
        scope.launch {
            val result = runCatching { AiClient.generate(prompt, providerSnapshot, key, model, previous) }
            busyLabel = null
            result.fold(
                onSuccess = { generated ->
                    replaceScene(generated)
                    message = "Modelo creado: ${generated.allNodes().size} partes, ${generated.totalTriangles()} triángulos"
                    aiPrompt = ""
                },
                onFailure = { e ->
                    val text = e.message ?: "La IA falló sin dar detalles"
                    message = text
                    // The model was retired by the provider: fetch the new list right away so the
                    // fix is one tap away instead of a guessing game.
                    if (text.contains("no está disponible", ignoreCase = true) ||
                        text.contains("modelo ya no", ignoreCase = true) ||
                        text.contains("404", ignoreCase = true)
                    ) {
                        refreshModels(providerSnapshot, silent = true)
                    }
                }
            )
        }
    }

    fun aiIdeas(): List<String> = AiCatalog.ideas

    fun modelsFor(provider: AiProvider): List<String> = AiCatalog.models(provider).map { it.id }

    fun modelLabel(provider: AiProvider, id: String): String {
        val cachedEntry = (if (provider == this.provider) modelList else ModelRegistry.cached(provider))
            .firstOrNull { it.id == id }
        if (cachedEntry != null) return "${cachedEntry.label} (${cachedEntry.id})"
        return AiCatalog.find(provider, id)?.let { "${it.label} (${it.id})" } ?: id
    }

    fun currentModelEntry(): ModelRegistry.ModelEntry? = modelList.firstOrNull { it.id == currentModel() }

    // ------------------------------------------------------------------ undo

    private fun snapshot(): ByteArray? = doc?.let { runCatching { Gltf.writeGlb(it) }.getOrNull() }

    fun pushUndo() {
        val bytes = snapshot() ?: return
        undoStack.addLast(bytes)
        while (undoStack.size > 12) undoStack.removeFirst()
        undoDepth = undoStack.size
    }

    fun undo() {
        val bytes = undoStack.removeLastOrNull() ?: return
        undoDepth = undoStack.size
        val name = doc?.name ?: "Modelo"
        val restored = runCatching { ModelFormats.read(bytes, "deshacer.glb") }.getOrNull() ?: return
        restored.name = name
        adopt(restored, frame = false)
        message = "Deshecho"
    }

    // ------------------------------------------------------------------ lifecycle

    fun onPause() {
        persistSettings()
        // Save the rest pose, not a frame in the middle of a playing clip.
        if (playing) viewport?.state?.restoreRest()
        playing = false
        doc?.let { ObjectStore.autosave(it) }
    }

    fun dispose() {
        if (active === this) active = null
        scope.cancel()
    }

    companion object {
        /** The instance alive right now, so the activity can autosave from `onPause`. */
        var active: AppState? = null
            private set
    }
}
