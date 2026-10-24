package com.arena.modeler.ui

import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arena.modeler.ai.AiClient
import com.arena.modeler.ai.ChatEngine
import com.arena.modeler.ai.ChatStore
import com.arena.modeler.ai.ModelCatalog
import com.arena.modeler.ai.ObjectStore
import com.arena.modeler.ai.ProviderRegistry
import com.arena.modeler.ai.providers.AiProviderAdapter
import com.arena.modeler.ai.providers.ChatMessage
import com.arena.modeler.ai.providers.ModelInfo
import com.arena.modeler.ai.providers.ProviderException
import com.arena.modeler.core.Quat
import com.arena.modeler.core.V3
import com.arena.modeler.geom.EditableMesh
import com.arena.modeler.geom.MeshOps
import com.arena.modeler.io.ExportFormat
import com.arena.modeler.io.Gltf
import com.arena.modeler.io.ModelFormats
import com.arena.modeler.io.UnsupportedFormatException
import com.arena.modeler.render.ViewMode
import com.arena.modeler.render.ViewportView
import com.arena.modeler.scene.AnimPath
import com.arena.modeler.scene.AnimationClip
import com.arena.modeler.scene.Material
import com.arena.modeler.scene.Primitives
import com.arena.modeler.scene.SceneDocument
import com.arena.modeler.scene.SceneNode
import com.arena.modeler.settings.SecureStore
import com.arena.modeler.tools.ToolExecutor
import com.arena.modeler.tools.ToolCall
import com.arena.modeler.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Pestañas del panel inferior. */
enum class Tab(val label: String) {
    OBJECTS("Escena"),
    CREATE("Crear"),
    EDIT("Editar"),
    MATERIAL("Material"),
    ANIMATE("Animar"),
    CHAT("Chat IA"),
    FILES("Archivo"),
    HISTORY("Historial")
}

/** Una entrada del historial de cambios, para que el usuario entienda qué pasó. */
class HistoryEntry(val text: String, val detail: String, val at: Long = System.currentTimeMillis())

/**
 * Estado único de la aplicación: documento, selección, ajustes, catálogo de modelos y chat.
 *
 * Reglas de diseño:
 * - La UI solo llama a métodos de esta clase; nunca modifica la escena directamente.
 * - Toda operación que cambia la escena pasa por [mutate], que guarda el estado anterior para
 *   deshacer y registra la entrada en el historial.
 * - Las tareas largas (IA, exportación, importación) corren fuera del hilo principal y se pueden
 *   cancelar cuando la plataforma lo permite (las peticiones HTTP se cortan al cancelar el job).
 */
class AppState(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // ------------------------------------------------------------------ documento

    var doc by mutableStateOf<SceneDocument?>(null)
        private set

    var selectedId by mutableStateOf(-1)
        private set

    var revision by mutableIntStateOf(0)
        private set

    var dirty by mutableStateOf(false)

    // ------------------------------------------------------------------ interfaz

    var tab by mutableStateOf(Tab.OBJECTS)
    var panelOpen by mutableStateOf(true)
    var message by mutableStateOf<String?>(null)
    var busyLabel by mutableStateOf<String?>(null)
    var showSettings by mutableStateOf(false)
    var showModelPicker by mutableStateOf(false)

    var playing by mutableStateOf(false)
    var animTime by mutableStateOf(0f)
    var clipIndex by mutableIntStateOf(0)
    var viewMode by mutableStateOf(ViewMode.MATERIAL)

    // ------------------------------------------------------------------ ajustes

    private val stored = ObjectStore.settings()
    private val catalog = ModelCatalog(ObjectStore.files())
    private val chatStore = ChatStore(ObjectStore.files())

    var providerId by mutableStateOf(stored.provider)
        private set

    var modelId by mutableStateOf(stored.modelFor(stored.currentProvider()))
    var apiKey by mutableStateOf(stored.keyFor(stored.currentProvider()))
    var keyVisible by mutableStateOf(false)
    var connectionStatus by mutableStateOf<String?>(null)
    var checkingKey by mutableStateOf(false)

    var catalogModels by mutableStateOf<List<ModelInfo>>(emptyList())
        private set
    var catalogUpdatedAt by mutableStateOf(0L)
        private set
    var catalogStale by mutableStateOf(true)
        private set
    var catalogError by mutableStateOf<String?>(null)
        private set
    var loadingModels by mutableStateOf(false)
        private set
    var priceFilter by mutableStateOf(ModelCatalog.PriceFilter.ALL)
    var modelQuery by mutableStateOf("")
    var modelsShown by mutableIntStateOf(30)

    var showGrid by mutableStateOf(stored.showGrid)
    var showWireframe by mutableStateOf(stored.showWireframe)
    var autoRotate by mutableStateOf(stored.autoRotate)
    var sculptMode by mutableStateOf(MeshOps.SculptMode.INFLATE)
    var sculptRadius by mutableStateOf(0.25f)
    var sculptStrength by mutableStateOf(0.05f)
    var sculptActive by mutableStateOf(false)
        private set

    // ------------------------------------------------------------------ historia

    private val undoStack = ArrayDeque<ByteArray>()
    private val redoStack = ArrayDeque<ByteArray>()

    var undoDepth by mutableIntStateOf(0)
        private set
    var redoDepth by mutableIntStateOf(0)
        private set
    var historyEntries by mutableStateOf<List<HistoryEntry>>(emptyList())
        private set

    // ------------------------------------------------------------------ chat

    var chatMessages by mutableStateOf<List<ChatStore.Message>>(emptyList())
        private set
    var chatInput by mutableStateOf("")
    var chatBusy by mutableStateOf(false)
        private set
    var chatStatus by mutableStateOf<String?>(null)
    var attachedImages by mutableStateOf<List<ChatMessage.ImageAttachment>>(emptyList())
    var pendingTurn by mutableStateOf<ChatEngine.Turn?>(null)
        private set

    private var chatJob: Job? = null
    private var exportJob: Job? = null

    private var viewport: ViewportView? = null

    init {
        active = this
        val autosaved = ObjectStore.autosaved()
        val initial = autosaved ?: SceneDocument("Sin título").also { it.materialFor() }
        doc = initial
        selectedId = initial.allNodes().firstOrNull()?.id ?: -1
        refreshChat()
        loadCatalogFromCache()
    }

    // ------------------------------------------------------------------ visor

    fun attach(view: ViewportView, frameAllOnAttach: Boolean = true) {
        viewport = view
        view.state.onSelect = { id ->
            selectedId = id
            if (id >= 0 && tab == Tab.OBJECTS) tab = Tab.EDIT
        }
        view.state.onTransform = { dirty = true; revision++ }
        view.onSculpt = { world -> sculptAt(world) }
        view.state.doc = doc
        syncViewport()
        if (frameAllOnAttach) view.frameAll()
    }

    fun detach() {
        viewport?.state?.onSelect = null
        viewport?.state?.onTransform = null
        viewport?.onSculpt = null
        viewport = null
    }

    fun syncViewport(frame: Boolean = false) {
        val v = viewport ?: return
        if (v.state.doc !== doc) v.setDocument(doc, frame)
        v.state.selectedId = selectedId
        v.state.showGrid = showGrid
        v.state.showWireframe = showWireframe
        v.state.autoRotate = autoRotate
        v.state.viewMode = viewMode
        v.state.playing = playing
        v.state.clipIndex = clipIndex
        v.state.animTime = animTime
        v.state.sculpting = sculptActive
        v.state.sculptRadius = sculptRadius
        v.state.sculptStrength = sculptStrength
        v.setAnimated(playing || autoRotate)
    }

    /** Activa el pincel: mientras esté encendido, el visor deja de orbitar con un dedo. */
    fun setSculpting(on: Boolean) {
        sculptActive = on
        viewport?.state?.sculpting = on
        viewport?.state?.sculptRadius = sculptRadius
        viewport?.state?.sculptStrength = sculptStrength
        viewport?.refresh()
        message = if (on) "Pincel activo: arrastra sobre el objeto" else null
    }

    fun updateSculptBrush(radius: Float? = null, strength: Float? = null) {
        if (radius != null) sculptRadius = radius
        if (strength != null) sculptStrength = strength
        viewport?.state?.sculptRadius = sculptRadius
        viewport?.state?.sculptStrength = sculptStrength
    }

    fun setBackground(color: FloatArray) {
        viewport?.state?.background = color
        viewport?.refresh()
    }

    fun applyViewMode(mode: ViewMode) {
        viewMode = mode
        viewport?.state?.viewMode = mode
        viewport?.refresh()
    }

    fun selectedNode(): SceneNode? = doc?.nodeById(selectedId)

    fun select(id: Int) {
        selectedId = id
        viewport?.state?.selectedId = id
        viewport?.refresh()
    }

    fun frameAll() = viewport?.frameAll() ?: Unit

    fun frameSelection() {
        val b = selectedNode()?.worldBounds() ?: run { frameAll(); return }
        viewport?.state?.camera?.frame(b, 1f)
        viewport?.refresh()
    }

    fun cameraPreset(preset: String) {
        val cam = viewport?.state?.camera ?: return
        when (preset) {
            "front" -> cam.setFront()
            "side" -> cam.setSide()
            "top" -> cam.setTop()
            "reset" -> {
                cam.setFront()
                cam.pitch = 22f
                cam.yaw = 38f
                frameAll()
            }
        }
        viewport?.refresh()
    }

    // ------------------------------------------------------------------ ajustes

    fun persistSettings() {
        stored.provider = providerId
        stored.setKey(currentProvider(), apiKey)
        stored.setModel(currentProvider(), modelId)
        stored.showGrid = showGrid
        stored.showWireframe = showWireframe
        stored.autoRotate = autoRotate
        ObjectStore.saveSettings()
    }

    fun currentProvider(): com.arena.modeler.ai.AiProvider =
        com.arena.modeler.ai.AiProvider.fromId(providerId)

    fun adapter(): AiProviderAdapter = ProviderRegistry.byId(providerId)

    fun adapterLabel(): String = adapter().label

    fun selectProvider(id: String) {
        if (providerId == id) return
        persistSettings()
        providerId = id
        val p = currentProvider()
        modelId = stored.modelFor(p)
        apiKey = stored.keyFor(p)
        connectionStatus = null
        modelQuery = ""
        priceFilter = ModelCatalog.PriceFilter.ALL
        loadCatalogFromCache()
    }

    fun updateKey(value: String) {
        apiKey = value.trim()
    }

    fun saveKey() {
        persistSettings()
        message = if (apiKey.isBlank()) "Clave borrada" else "Clave guardada cifrada en el teléfono"
        if (apiKey.isNotBlank()) refreshModels(silent = true)
    }

    fun checkConnection() {
        if (apiKey.isBlank()) {
            connectionStatus = "Escribe primero una API key"
            return
        }
        val adapter = adapter()
        val key = apiKey
        checkingKey = true
        connectionStatus = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { adapter.checkKey(key) } }
            checkingKey = false
            result.fold(
                onSuccess = { connectionStatus = it },
                onFailure = { e ->
                    connectionStatus = if (e is ProviderException) "Error: ${e.message}" else "Error: ${e.message}"
                }
            )
        }
    }

    // ------------------------------------------------------------------ catálogo

    private fun loadCatalogFromCache() {
        val snap = catalog.snapshot(providerId)
        catalogModels = snap.models
        catalogUpdatedAt = snap.updatedAt
        catalogStale = snap.stale
        catalogError = snap.error
        if (snap.models.isNotEmpty() && snap.models.none { it.id == modelId }) {
            modelId = snap.models.first().id
            persistSettings()
        }
    }

    fun refreshModels(silent: Boolean = false) {
        if (loadingModels) return
        val id = providerId
        if (apiKey.isBlank() && id == "google") {
            if (!silent) message = "Google necesita la API key para listar sus modelos"
            return
        }
        loadingModels = true
        if (!silent) catalogError = null
        val key = apiKey
        scope.launch {
            val snap = withContext(Dispatchers.IO) { catalog.refresh(id, key) }
            loadingModels = false
            if (id != providerId) return@launch
            catalogModels = snap.models
            catalogUpdatedAt = snap.updatedAt
            catalogStale = snap.stale
            catalogError = snap.error
            modelsShown = 30
            if (snap.models.isNotEmpty()) {
                if (snap.models.none { it.id == modelId }) modelId = snap.models.first().id
                if (!silent) message = "Catálogo actualizado: ${snap.models.size} modelos"
            } else if (!silent) {
                message = snap.error ?: "No se pudieron descargar los modelos"
            }
        }
    }

    fun visibleModels(): List<ModelInfo> = catalog.filter(catalogModels, priceFilter, modelQuery)

    fun freeCount(): Int = catalog.freeCount(catalogModels)

    fun paidCount(): Int = catalog.paidCount(catalogModels)

    fun unknownCount(): Int = catalog.unknownCount(catalogModels)

    fun catalogAgeLabel(): String = ModelCatalog.updatedLabel(catalogUpdatedAt)

    fun applyPriceFilter(filter: ModelCatalog.PriceFilter) {
        priceFilter = filter
        modelsShown = 30
    }

    fun chooseModel(id: String) {
        modelId = id
        persistSettings()
    }

    fun currentModelInfo(): ModelInfo? = catalogModels.firstOrNull { it.id == modelId }

    fun currentModelLabel(): String {
        val info = currentModelInfo()
        val base = info?.label ?: modelId
        if (base.isBlank()) return "ningún modelo elegido"
        val price = info?.priceLabel()
        return if (price != null) "$base · $price" else base
    }

    /** Mensaje único cuando falta el modelo, para no repetirlo en cada envío. */
    private fun requireModel(): Boolean {
        if (modelId.isNotBlank()) return true
        message = "Elige un modelo en la lista (botón «Modelo»)"
        showModelPicker = true
        return false
    }

    fun supportsImages(): Boolean = currentModelInfo()?.supportsImages ?: false

    // ------------------------------------------------------------------ historial

    private fun snapshot(): ByteArray? = doc?.let { runCatching { Gltf.writeGlb(it) }.getOrNull() }

    /** Registra el estado actual para poder deshacerlo. Se llama antes de cada cambio. */
    private fun pushUndo() {
        val bytes = snapshot() ?: return
        undoStack.addLast(bytes)
        while (undoStack.size > 20) undoStack.removeFirst()
        undoDepth = undoStack.size
        redoStack.clear()
        redoDepth = 0
    }

    fun undo() {
        val bytes = undoStack.removeLastOrNull() ?: return
        snapshot()?.let {
            redoStack.addLast(it)
            redoDepth = redoStack.size
        }
        undoDepth = undoStack.size
        restore(bytes, "Deshecho")
    }

    fun redo() {
        val bytes = redoStack.removeLastOrNull() ?: return
        snapshot()?.let {
            undoStack.addLast(it)
            undoDepth = undoStack.size
        }
        redoDepth = redoStack.size
        restore(bytes, "Rehecho")
    }

    private fun restore(bytes: ByteArray, label: String) {
        val name = doc?.name ?: "Modelo"
        val restored = runCatching { ModelFormats.read(bytes, "estado.glb") }.getOrNull() ?: return
        restored.name = name
        adopt(restored, frame = false)
        message = label
    }

    private fun log(text: String, detail: String = "") {
        historyEntries = (listOf(HistoryEntry(text, detail)) + historyEntries).take(60)
    }

    /**
     * Envoltorio de toda mutación: guarda el estado previo, ejecuta y registra. Si [block] falla,
     * la excepción se captura y se devuelve el mensaje para que la UI lo muestre.
     */
    private fun mutate(label: String, block: () -> String?): Boolean {
        pushUndo()
        return try {
            val detail = block() ?: ""
            dirty = true
            revision++
            log(label, detail)
            viewport?.refresh()
            true
        } catch (e: Exception) {
            message = "$label: ${e.message ?: "error"}"
            false
        }
    }

    // ------------------------------------------------------------------ escena

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

    fun newScene() {
        val d = SceneDocument("Sin título")
        d.materialFor()
        adopt(d, frame = true)
        undoStack.clear(); redoStack.clear(); undoDepth = 0; redoDepth = 0
        historyEntries = emptyList()
        log("Escena nueva")
    }

    fun addPrimitive(kind: Primitives.Kind) {
        mutate("Añadido ${kind.label}") {
            val d = doc ?: return@mutate null
            val target = viewport?.state?.camera?.target
            val node = d.newNode(d.nextNodeName(kind.label))
            node.setEditableMesh(EditableMesh.fromPrimitive(kind), smooth = kind != Primitives.Kind.CUBE)
            node.material = d.materialFor()
            node.position = V3(target?.x ?: 0f, 0f, target?.z ?: 0f)
            selectedId = node.id
            "${node.name}: ${node.mesh?.triangleCount ?: 0} triángulos"
        }
    }

    fun deleteSelected() {
        val node = selectedNode() ?: return
        mutate("Eliminado ${node.name}") {
            node.parent?.children?.remove(node)
            node.removeFromParent()
            for (clip in doc?.animations ?: emptyList()) clip.channels.removeAll { it.nodeId == node.id }
            selectedId = -1
            node.name
        }
    }

    fun duplicateSelected() {
        val source = selectedNode() ?: return
        mutate("Duplicado ${source.name}") {
            val d = doc ?: return@mutate null
            val node = d.newNode(d.nextNodeName(source.name.substringBefore('.')))
            node.mesh = source.mesh?.copy()
            node.material = source.material
            node.position = V3(source.position.x + 0.35f, source.position.y, source.position.z)
            node.rotation = source.rotation.copy()
            node.scale = source.scale.copy()
            selectedId = node.id
            node.name
        }
    }

    fun renameSelected(name: String) {
        val node = selectedNode() ?: return
        node.name = name.ifBlank { "Objeto" }
        dirty = true
        revision++
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
        dirty = true
        revision++
        viewport?.refresh()
    }

    fun setRotation(x: Float, y: Float, z: Float) {
        val node = selectedNode() ?: return
        node.rotation = Quat.euler(x, y, z)
        node.markDirty()
        dirty = true
        revision++
        viewport?.refresh()
    }

    fun setScale(x: Float, y: Float, z: Float) {
        val node = selectedNode() ?: return
        node.scale.set(x.coerceAtLeast(0.01f), y.coerceAtLeast(0.01f), z.coerceAtLeast(0.01f))
        node.markDirty()
        dirty = true
        revision++
        viewport?.refresh()
    }

    fun dropToFloor() {
        val node = selectedNode() ?: return
        val b = node.worldBounds() ?: return
        mutate("Al suelo: ${node.name}") {
            val parentY = node.parent?.worldMatrix()?.get(13) ?: 0f
            node.position.y -= (b[1] - parentY)
            node.markDirty()
            node.name
        }
    }

    /** Convierte una primitiva en malla editable (ya lo está, pero deja constancia y limpia). */
    fun makeEditable() {
        val node = selectedNode() ?: return
        val mesh = node.editableMesh() ?: run {
            message = "Ese objeto no tiene malla"
            return
        }
        mutate("Malla editable: ${node.name}") {
            node.setEditableMesh(mesh.weld(1e-5f))
            "${mesh.faceCount} caras"
        }
    }

    // ------------------------------------------------------------------ operaciones de malla

    /** Ejecuta una operación del motor de geometría sobre el objeto seleccionado. */
    fun applyMeshOp(label: String, op: (EditableMesh) -> EditableMesh) {
        val node = selectedNode() ?: run {
            message = "Selecciona un objeto primero"
            return
        }
        val original = node.editableMesh() ?: run {
            message = "Ese objeto no tiene geometría editable"
            return
        }
        mutate(label) {
            val result = op(original.copy())
            val validation = MeshOps.validate(result)
            if (!validation.ok) throw IllegalArgumentException(validation.problems.first())
            if (result.faces.isEmpty()) throw IllegalArgumentException("la operación dejó la malla vacía")
            node.setEditableMesh(result)
            "${result.faces.size} caras, ${result.triangleCount()} triángulos"
        }
    }

    fun extrude(distance: Float) = applyMeshOp("Extruido") { MeshOps.extrude(it, distance) }

    fun inset(amount: Float) = applyMeshOp("Inset") { MeshOps.inset(it, amount) }

    fun bevel(amount: Float, segments: Int = 1) = applyMeshOp("Bisel") { MeshOps.bevel(it, amount, segments) }

    fun subdivide(iterations: Int) = applyMeshOp("Subdividido") { MeshOps.subdivide(it, iterations) }

    fun smoothMesh(iterations: Int, strength: Float) =
        applyMeshOp("Suavizado") { MeshOps.smoothMesh(it, iterations, strength) }

    fun mirror(axis: MeshOps.Axis) = applyMeshOp("Simetría") { MeshOps.mirror(it, axis) }

    fun reduceMesh() = applyMeshOp("Reducido") { MeshOps.reduce(it) }

    fun repairMesh() = applyMeshOp("Reparado") { MeshOps.repair(it) }

    fun booleanWithSelectedOther(operation: String) {
        val current = selectedNode() ?: run {
            message = "Selecciona el primer objeto"
            return
        }
        val others = doc?.allNodes()?.filter { it.id != current.id && it.mesh != null } ?: emptyList()
        if (others.isEmpty()) {
            message = "Hace falta un segundo objeto para la booleana"
            return
        }
        val other = others.first()
        val executor = ToolExecutor(doc!!)
        val call = ToolCall(
            "boolean",
            org.json.JSONObject()
                .put("object", current.name)
                .put("other", other.name)
                .put("operation", operation)
        )
        pushUndo()
        val result = executor.execute(call)
        message = result.message
        if (result.ok) {
            dirty = true
            revision++
            selectedId = current.id
            log("Booleana", result.message)
            viewport?.refresh()
        }
    }

    private var lastSculptUndoAt = 0L

    /**
     * Pincel de esculpido: aplica una deformación local alrededor de un punto del mundo.
     *
     * Cada pincelada **no** guarda un estado de deshacer: un gesto continuo genera decenas de
     * eventos y serializar la escena entera en cada uno haría que el visor se trabe. Se guarda un
     * estado por gesto (máximo uno cada 1,2 s) y el resto de pinceladas se apilan sobre la malla.
     */
    fun sculptAt(worldPoint: FloatArray) {
        val node = selectedNode() ?: return
        val mesh = node.editableMesh() ?: return
        val inv = FloatArray(16)
        if (!com.arena.modeler.core.M4.invert(inv, node.worldMatrix())) return
        val local = com.arena.modeler.core.M4.transformPoint(inv, worldPoint[0], worldPoint[1], worldPoint[2])
        val now = System.currentTimeMillis()
        if (now - lastSculptUndoAt > 1200L) {
            pushUndo()
            lastSculptUndoAt = now
            log("Esculpido: ${node.name}", sculptMode.label)
        }
        try {
            val result = MeshOps.sculpt(
                mesh,
                floatArrayOf(local[0], local[1], local[2]),
                sculptRadius,
                sculptStrength,
                sculptMode
            )
            node.setEditableMesh(result)
            dirty = true
            revision++
            viewport?.refresh()
        } catch (e: Exception) {
            message = "Esculpido: ${e.message ?: "no se pudo aplicar"}"
        }
    }

    // ------------------------------------------------------------------ material

    fun setMaterialColor(color: FloatArray) {
        val node = selectedNode() ?: return
        mutate("Color de ${node.name}") {
            val mat = node.material ?: doc?.materialFor()?.also { node.material = it } ?: return@mutate null
            mat.baseColor = color
            node.name
        }
    }

    fun setMaterialProps(metallic: Float? = null, roughness: Float? = null) {
        val node = selectedNode() ?: return
        val mat = node.material ?: doc?.materialFor()?.also { node.material = it } ?: return
        if (metallic != null) mat.metallic = metallic.coerceIn(0f, 1f)
        if (roughness != null) mat.roughness = roughness.coerceIn(0.02f, 1f)
        dirty = true
        revision++
        viewport?.refresh()
    }

    fun setEmission(on: Boolean) {
        val node = selectedNode() ?: return
        val mat = node.material ?: doc?.materialFor()?.also { node.material = it } ?: return
        mat.emission = if (on) floatArrayOf(0.35f, 0.55f, 1f) else floatArrayOf(0f, 0f, 0f)
        dirty = true
        revision++
        viewport?.refresh()
    }

    // ------------------------------------------------------------------ animación

    fun currentClip(): AnimationClip? {
        val d = doc ?: return null
        if (clipIndex < 0 || clipIndex >= d.animations.size) return null
        return d.animations[clipIndex]
    }

    fun createTurntable() {
        val d = doc ?: return
        val node = selectedNode() ?: d.allNodes().firstOrNull() ?: run {
            message = "Añade algún objeto antes de animar"
            return
        }
        mutate("Giro automático") {
            synchronized(d) { AnimationClip.demo(d, node, 4f) }
            clipIndex = d.animations.size - 1
            node.name
        }
        togglePlay(true)
    }

    fun deleteClip() {
        val d = doc ?: return
        val clip = currentClip() ?: return
        mutate("Clip eliminado") {
            d.animations.remove(clip)
            clipIndex = 0
            clip.name
        }
    }

    fun keyframe(path: AnimPath) {
        val d = doc ?: return
        val node = selectedNode() ?: return
        val clip = currentClip() ?: AnimationClip("Animación").also {
            d.animations.add(it)
            clipIndex = d.animations.size - 1
        }
        pushUndo()
        synchronized(d) { clip.keyNode(node, animTime, path) }
        dirty = true
        revision++
        log("Clave añadida", "${node.name} · ${path.name.lowercase()} @ ${"%.2f".format(animTime)} s")
    }

    fun seekAnimation(t: Float) {
        val clip = currentClip() ?: return
        animTime = t.coerceIn(0f, clip.duration().coerceAtLeast(0.01f))
        clip.apply(doc ?: return, animTime)
        viewport?.refresh()
    }

    fun togglePlay(force: Boolean? = null) {
        val clip = currentClip() ?: run {
            message = "Primero crea una animación"
            return
        }
        val next = force ?: !playing
        if (next && !playing) viewport?.state?.captureRest()
        if (!next && playing) viewport?.state?.restoreRest()
        playing = next
        if (next) animTime = 0f
        viewport?.setAnimated(playing || autoRotate)
        if (clip.channels.isEmpty()) message = "Ese clip no tiene claves"
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

    // ------------------------------------------------------------------ archivos

    fun export(format: ExportFormat) {
        val d = doc ?: return
        if (exportJob?.isActive == true) return
        busyLabel = "Exportando ${format.extension.uppercase()}…"
        exportJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val export = ModelFormats.export(d, format)
                    ObjectStore.exportToDownloads(export)
                }
            }
            busyLabel = null
            result.fold(
                onSuccess = {
                    message = "Exportado y verificado: $it"
                    log("Exportado", it)
                },
                onFailure = { message = "No se pudo exportar: ${it.message}" }
            )
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
        exportJob = null
        busyLabel = null
        message = "Exportación cancelada"
    }

    fun importUri(uri: Uri) {
        val name = ObjectStore.displayName(uri)
        busyLabel = "Abriendo $name…"
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { ObjectStore.readUri(uri) } }
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
                    pushUndo()
                    adopt(imported, frame = true)
                    log("Importado", fileName)
                    message = "Importado: ${imported.totalTriangles()} triángulos, " +
                        "${imported.animations.size} animación(es)"
                },
                onFailure = { e ->
                    message = if (e is UnsupportedFormatException) e.message else "Error al abrir: ${e.message}"
                }
            )
        }
    }

    fun saveProject() {
        val d = doc ?: return
        busyLabel = "Guardando…"
        scope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { ObjectStore.saveProject(d) } }
            busyLabel = null
            info.fold(
                onSuccess = {
                    dirty = false
                    message = "Proyecto guardado: ${it.title}"
                    log("Guardado", it.title)
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
                    undoStack.clear(); redoStack.clear(); undoDepth = 0; redoDepth = 0
                    refreshChat()
                    message = "Proyecto abierto: ${info.title}"
                    log("Abierto", info.title)
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

    // ------------------------------------------------------------------ chat

    private fun refreshChat() {
        chatMessages = chatStore.messages(doc?.name ?: "proyecto")
    }

    fun attachImage(uri: Uri) {
        val name = ObjectStore.displayName(uri)
        scope.launch {
            val bytes = withContext(Dispatchers.IO) { runCatching { ObjectStore.readUri(uri) } }
            bytes.fold(
                onSuccess = { data ->
                    if (data.size > 4_000_000) {
                        message = "La imagen es demasiado grande (máx. 4 MB)"
                        return@fold
                    }
                    val mime = when (name.substringAfterLast('.', "").lowercase()) {
                        "png" -> "image/png"
                        "webp" -> "image/webp"
                        "gif" -> "image/gif"
                        else -> "image/jpeg"
                    }
                    attachedImages = attachedImages + ChatMessage.ImageAttachment(data, mime)
                    message = "Imagen adjunta (${data.size / 1024} KB)"
                },
                onFailure = { message = "No se pudo leer la imagen: ${it.message}" }
            )
        }
    }

    fun removeImage(index: Int) {
        attachedImages = attachedImages.filterIndexed { i, _ -> i != index }
    }

    fun clearChat() {
        chatStore.clear(doc?.name ?: "proyecto")
        chatMessages = emptyList()
        message = "Conversación borrada"
    }

    fun cancelChat() {
        if (!chatBusy) return
        chatJob?.cancel()
        chatBusy = false
        chatStatus = null
        busyLabel = null
        message = "Petición cancelada"
    }

    /** Escena resumida para el modelo: nunca la malla completa. */
    private fun sceneSummary(): String {
        val d = doc ?: return "Escena vacía"
        return runCatching { ToolExecutor(d).inspectScene() }.getOrDefault("Escena con ${d.allNodes().size} objetos")
    }

    fun sendChat(text: String = chatInput) {
        val prompt = text.trim()
        if (prompt.isEmpty()) {
            message = "Escribe qué quieres hacer"
            return
        }
        if (chatBusy) return
        if (apiKey.isBlank()) {
            message = "Falta la API key de ${adapter().label}"
            return
        }
        if (!requireModel()) return
        val images = attachedImages
        if (images.isNotEmpty() && !supportsImages()) {
            message = "El modelo actual no acepta imágenes. Elige uno con capacidad «imágenes»."
            return
        }
        val project = doc?.name ?: "proyecto"
        pushUndo()
        chatStore.append(project, ChatStore.Message(0, ChatStore.Message.Role.USER, prompt, System.currentTimeMillis(), emptyList(), null, images.size))
        refreshChat()
        chatInput = ""
        attachedImages = emptyList()

        val adapter = adapter()
        val key = apiKey
        val model = modelId
        val summary = sceneSummary()
        chatBusy = true
        chatStatus = "Pensando con $model…"
        chatJob = scope.launch {
            val history = ArrayList<ChatMessage>()
            for (m in chatStore.toProviderHistory(project, 10)) history.add(m)
            if (images.isNotEmpty()) {
                val last = history.lastOrNull()
                if (last != null && last.role == ChatMessage.Role.USER) {
                    history[history.size - 1] = ChatMessage(last.role, last.text, images)
                } else {
                    history.add(ChatMessage(ChatMessage.Role.USER, prompt, images))
                }
            }
            val outcome = withContext(Dispatchers.IO) {
                runCatching { ChatEngine.send(adapter, key, model, summary, history) }
            }
            chatBusy = false
            chatStatus = null
            outcome.fold(
                onSuccess = { turn ->
                    chatStore.append(
                        project,
                        ChatStore.Message(
                            0, ChatStore.Message.Role.ASSISTANT, turn.reply,
                            System.currentTimeMillis(),
                            emptyList(), null, 0
                        )
                    )
                    refreshChat()
                    if (turn.hasOperations) {
                        if (turn.needsConfirmation) {
                            pendingTurn = turn
                            chatStatus = "Hay cambios que necesitan confirmación"
                        } else {
                            applyTurn(turn)
                        }
                    }
                },
                onFailure = { e ->
                    val msg = when (e) {
                        is CancellationException -> "Petición cancelada"
                        else -> e.message ?: "La IA falló"
                    }
                    chatStore.append(
                        project,
                        ChatStore.Message(
                            0, ChatStore.Message.Role.ASSISTANT, msg,
                            System.currentTimeMillis(), emptyList(), msg, 0
                        )
                    )
                    refreshChat()
                    if (e !is CancellationException) message = msg
                }
            )
        }
    }

    fun applyPendingTurn() {
        val turn = pendingTurn ?: return
        pendingTurn = null
        applyTurn(turn)
    }

    fun discardPendingTurn() {
        pendingTurn = null
        chatStatus = null
        message = "Cambios descartados"
    }

    /** Ejecuta las operaciones del turno con validación y sin dejar la escena a medias. */
    private fun applyTurn(turn: ChatEngine.Turn) {
        val d = doc ?: return
        val project = d.name
        val executor = ToolExecutor(d) { format, name ->
            val copy = d
            if (name != null) copy.name = name
            val result = ModelFormats.export(copy, format)
            ObjectStore.exportToDownloads(result)
        }
        val lines = ArrayList<String>()
        var failures = 0
        for (call in turn.calls) {
            val result: ToolResult = executor.execute(call.call)
            if (result.ok) {
                lines.add("✓ ${call.call.tool}: ${result.message}")
                if (result.nodeId >= 0) selectedId = result.nodeId
            } else {
                failures++
                lines.add("✗ ${call.call.tool}: ${result.message}")
            }
            for (w in result.warnings) lines.add("  · $w")
        }
        dirty = true
        revision++
        if (lines.isNotEmpty()) {
            log("IA aplicó ${turn.calls.size} operaciones", lines.take(3).joinToString(" · "))
            chatStore.append(
                project,
                ChatStore.Message(
                    0, ChatStore.Message.Role.SYSTEM,
                    lines.joinToString("\n"),
                    System.currentTimeMillis(),
                    lines, null, 0
                )
            )
            refreshChat()
        }
        message = if (failures == 0) {
            "Aplicadas ${turn.calls.size} operaciones"
        } else {
            "Aplicadas ${turn.calls.size - failures} de ${turn.calls.size}; $failures fallaron"
        }
        viewport?.refresh()
    }

    // ------------------------------------------------------------------ compatible (generador antiguo)

    /** Genera un modelo completo desde cero con el esquema de partes (sigue disponible). */
    fun generateModel(idea: String) {
        if (chatBusy) return
        if (apiKey.isBlank()) {
            message = "Falta la API key de ${adapter().label}"
            return
        }
        if (!requireModel()) return
        val provider = currentProvider()
        val key = apiKey
        val model = modelId
        busyLabel = "Creando el modelo…"
        scope.launch {
            val result = runCatching { AiClient.generate(idea, provider, key, model, null) }
            busyLabel = null
            result.fold(
                onSuccess = { generated ->
                    pushUndo()
                    adopt(generated, frame = true)
                    log("Modelo generado", idea.take(60))
                    message = "Modelo creado: ${generated.allNodes().size} partes"
                },
                onFailure = { message = it.message ?: "La IA falló" }
            )
        }
    }

    // ------------------------------------------------------------------ ciclo de vida

    fun onPause() {
        persistSettings()
        if (playing) viewport?.state?.restoreRest()
        playing = false
        doc?.let { ObjectStore.autosave(it) }
    }

    fun dispose() {
        if (active === this) active = null
        chatJob?.cancel()
        exportJob?.cancel()
        scope.cancel()
    }

    fun secureStorageLabel(): String =
        if (SecureStore.hardwareBacked) "Claves cifradas con el Keystore del dispositivo"
        else "Aviso: este dispositivo no ofrece Keystore; las claves se guardan en el almacenamiento privado de la app"

    fun appVersionLabel(): String =
        "VoxelForge 3D · Android ${Build.VERSION.RELEASE}"

    companion object {
        var active: AppState? = null
            private set
    }
}
