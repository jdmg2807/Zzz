package com.arena.modeler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.modeler.ai.AiCatalog
import com.arena.modeler.ai.AiProvider
import com.arena.modeler.ai.ModelRegistry
import com.arena.modeler.io.ExportFormat
import com.arena.modeler.scene.AnimPath
import com.arena.modeler.scene.AnimationClip
import com.arena.modeler.scene.Primitives
import com.arena.modeler.scene.SceneNode

private val swatches: List<Pair<String, Color>> = listOf(
    "#E7E9EE" to Color(0xFFE7E9EE),
    "#B9BEC9" to Color(0xFFB9BEC9),
    "#7E8593" to Color(0xFF7E8593),
    "#3A3F4A" to Color(0xFF3A3F4A),
    "#E8892B" to Color(0xFFE8892B),
    "#E8C13B" to Color(0xFFE8C13B),
    "#57C06B" to Color(0xFF57C06B),
    "#3D9BE0" to Color(0xFF3D9BE0),
    "#4C63D2" to Color(0xFF4C63D2),
    "#9A5BD6" to Color(0xFF9A5BD6),
    "#D6559B" to Color(0xFFD6559B),
    "#D64C4C" to Color(0xFFD64C4C)
)

private fun <T> chunk(items: List<T>, size: Int): List<List<T>> = items.chunked(size)

// ---------------------------------------------------------------------------- objects

@Composable
fun ObjectsPanel(state: AppState) {
    val rev = state.revision
    val doc = state.doc ?: return
    SectionTitle("Añadir primitiva")
    for (row in chunk(Primitives.Kind.values().toList(), 3)) {
        RowWrap {
            for (kind in row) {
                ChipButton(kind.label, modifier = Modifier.weight(1f)) { state.addPrimitive(kind) }
            }
        }
    }
    Divider()
    SectionTitle("Jerarquía", "${doc.allNodes().size} objetos")
    if (doc.allNodes().isEmpty()) {
        Hint("La escena está vacía. Añade una primitiva o pide un modelo a la IA.")
        return
    }
    key(rev) {
        for (node in doc.allNodes()) {
            NodeRow(state, doc.root, node)
        }
    }
    Divider()
    Text(
        "${doc.totalVertices()} vértices · ${doc.totalTriangles()} triángulos · " +
            "${doc.materials.size} materiales · ${doc.animations.size} animaciones",
        color = Palette.muted,
        fontSize = 11.sp
    )
}

@Composable
private fun NodeRow(state: AppState, root: SceneNode, node: SceneNode) {
    var depth = 0
    var p = node.parent
    while (p != null && p !== root) {
        depth++
        p = p.parent
    }
    val selected = state.selectedId == node.id
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 12).dp, bottom = 4.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Palette.accentSoft else Palette.panelHigh.copy(alpha = 0.55f))
            .clickable { state.select(node.id) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(8.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (node.mesh != null) Palette.accent else Palette.stroke)
        )
        Text(
            node.name,
            color = if (node.visible) Palette.text else Palette.muted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(start = 8.dp).weight(1f)
        )
        Text(
            if (node.mesh != null) "${node.mesh!!.vertexCount} v" else "grupo",
            color = Palette.muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 8.dp)
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { state.setVisible(node, !node.visible) }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(if (node.visible) "ver" else "oculto", color = Palette.muted, fontSize = 11.sp)
        }
    }
}

// ---------------------------------------------------------------------------- edit

@Composable
fun EditPanel(state: AppState) {
    val rev = state.revision
    val node = state.selectedNode()
    if (node == null) {
        SectionTitle("Editar")
        Hint(
            "Toca un objeto en el visor o elígelo en la pestaña Objetos para moverlo, girarlo y " +
                "escalarlo."
        )
        return
    }
    var name by remember(node.id) { mutableStateOf(node.name) }
    TextField(
        value = name,
        onValueChange = { name = it; state.renameSelected(it) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 14.sp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Palette.panelHigh,
            unfocusedContainerColor = Palette.panelHigh,
            focusedIndicatorColor = Palette.accent,
            unfocusedIndicatorColor = Palette.stroke,
            focusedTextColor = Palette.text,
            unfocusedTextColor = Palette.text
        ),
        modifier = Modifier.fillMaxWidth()
    )
    run {
        val euler = com.arena.modeler.io.Fbx.eulerFromQuat(node.rotation)
        SectionTitle("Posición", "metros")
        SliderRow("X", node.position.x, -5f..5f) { state.setPosition(it, node.position.y, node.position.z) }
        SliderRow("Y", node.position.y, -1f..6f) { state.setPosition(node.position.x, it, node.position.z) }
        SliderRow("Z", node.position.z, -5f..5f) { state.setPosition(node.position.x, node.position.y, it) }
        SectionTitle("Rotación", "grados")
        SliderRow("X", euler[0], -180f..180f) { state.setRotation(it, euler[1], euler[2]) }
        SliderRow("Y", euler[1], -180f..180f) { state.setRotation(euler[0], it, euler[2]) }
        SliderRow("Z", euler[2], -180f..180f) { state.setRotation(euler[0], euler[1], it) }
        SectionTitle("Escala")
        SliderRow("X", node.scale.x, 0.05f..5f) { state.setScale(it, node.scale.y, node.scale.z) }
        SliderRow("Y", node.scale.y, 0.05f..5f) { state.setScale(node.scale.x, it, node.scale.z) }
        SliderRow("Z", node.scale.z, 0.05f..5f) { state.setScale(node.scale.x, node.scale.y, it) }
    }
    Divider()
    RowWrap {
        ActionButton("Duplicar", Modifier.weight(1f)) { state.duplicateSelected() }
        ActionButton("Eliminar", Modifier.weight(1f)) { state.deleteSelected() }
    }
    RowWrap {
        ActionButton("Enfocar", Modifier.weight(1f)) { state.frameSelection() }
        ActionButton("Al suelo", Modifier.weight(1f)) { state.dropToFloor() }
    }
    Hint(
        "Un dedo orbita, dos dedos desplazan, pellizcar acerca. Las flechas de colores mueven el " +
            "objeto en un solo eje."
    )
}

// ---------------------------------------------------------------------------- material

@Composable
fun MaterialPanel(state: AppState) {
    val rev = state.revision
    val node = state.selectedNode()
    if (node == null) {
        SectionTitle("Material")
        Hint("Selecciona un objeto para editar su color y su acabado.")
        return
    }
    val mat = node.material
    run {
        SectionTitle("Color", node.name)
        for (row in chunk(swatches, 6)) {
            RowWrap(4) {
                for (pair in row) {
                    ColorSwatch(
                        color = pair.second,
                        selected = mat != null && colorHex(mat.baseColor) == pair.first
                    ) { state.setMaterialColor(hexToFloat(pair.first)) }
                }
            }
        }
        Divider()
        SectionTitle("Acabado")
        SliderRow("Metal", mat?.metallic ?: 0f, 0f..1f) { state.setMaterialProps(metallic = it) }
        SliderRow("Rugos.", mat?.roughness ?: 0.6f, 0.02f..1f) { state.setMaterialProps(roughness = it) }
        ToggleRow(
            "Emisión (brilla)",
            mat?.emission?.let { it[0] + it[1] + it[2] > 0.01f } == true
        ) { state.setEmission(it) }
        Hint("Los materiales se exportan a glTF/GLB, FBX y OBJ (.mtl). PLY guarda solo el color.")
    }
}

private fun colorHex(c: FloatArray): String = String.format(
    "#%02X%02X%02X",
    (c[0] * 255).toInt().coerceIn(0, 255),
    (c[1] * 255).toInt().coerceIn(0, 255),
    (c[2] * 255).toInt().coerceIn(0, 255)
)

private fun hexToFloat(hex: String): FloatArray {
    val v = hex.removePrefix("#").toLongOrNull(16) ?: return floatArrayOf(1f, 1f, 1f, 1f)
    return floatArrayOf(
        ((v shr 16) and 0xFF) / 255f,
        ((v shr 8) and 0xFF) / 255f,
        (v and 0xFF) / 255f,
        1f
    )
}

// ---------------------------------------------------------------------------- animation

@Composable
fun AnimatePanel(state: AppState) {
    val rev = state.revision
    val doc = state.doc ?: return
    SectionTitle("Animación", "${doc.animations.size} clips")
    if (doc.animations.isEmpty()) {
        ActionButton("Crear giro automático (4 s)", Modifier.fillMaxWidth(), primary = true) {
            state.createTurntable()
        }
        Hint(
            "El giro usa el objeto seleccionado (o el primero de la escena). Después puedes añadir " +
                "claves manuales: selecciona un objeto, mueve el tiempo y pulsa «Posición», " +
                "«Rotación» o «Escala»."
        )
        return
    }
    key(rev) {
        for (row in chunk(doc.animations, 2)) {
            RowWrap {
                for (clip in row) {
                    val index = doc.animations.indexOf(clip)
                    ChipButton(
                        clip.name,
                        selected = state.clipIndex == index,
                        modifier = Modifier.weight(1f)
                    ) {
                        state.clipIndex = index
                        state.seekAnimation(0f)
                        state.syncViewport()
                    }
                }
            }
        }
    }
    val clip: AnimationClip = state.currentClip() ?: run {
        Hint("Ese clip ya no existe. Elige otro.")
        return
    }
    val duration = clip.duration().coerceAtLeast(0.1f)
    SectionTitle("Reproducir", "duración ${"%.2f".format(duration)} s")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ActionButton(if (state.playing) "Pausa" else "Reproducir") { state.togglePlay() }
        Box(Modifier.width(10.dp))
        Slider(
            value = state.animTime.coerceIn(0f, duration),
            onValueChange = { state.seekAnimation(it) },
            valueRange = 0f..duration,
            modifier = Modifier.weight(1f).height(32.dp),
            colors = SliderDefaults.colors(
                thumbColor = Palette.accent,
                activeTrackColor = Palette.accent,
                inactiveTrackColor = Palette.stroke
            )
        )
        Text(
            "%.2f".format(state.animTime),
            color = Palette.text,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
    SectionTitle("Añadir claves al objeto seleccionado")
    RowWrap {
        ActionButton("Posición", Modifier.weight(1f)) { state.keyframe(AnimPath.TRANSLATION) }
        ActionButton("Rotación", Modifier.weight(1f)) { state.keyframe(AnimPath.ROTATION) }
        ActionButton("Escala", Modifier.weight(1f)) { state.keyframe(AnimPath.SCALE) }
    }
    Divider()
    RowWrap {
        ActionButton("Borrar clip") { state.deleteClip() }
        ActionButton("Nuevo giro") { state.createTurntable() }
    }
    Hint(
        "La animación solo se conserva al exportar a glTF/GLB o FBX ASCII. OBJ, STL y PLY " +
            "guardan únicamente la malla."
    )
}

// ---------------------------------------------------------------------------- AI

@Composable
fun AiPanel(state: AppState, onOpenKeyPage: (String) -> Unit) {
    val provider = state.provider
    // First time the tab is opened, ask the provider for its live catalogue.
    androidx.compose.runtime.LaunchedEffect(provider) {
        if (state.modelStatus == null && !state.modelLoading) state.refreshModels(provider, silent = true)
    }
    SectionTitle("Proveedor de IA")
    RowWrap {
        for (p in AiProvider.all()) {
            ChipButton(p.label, selected = provider == p, modifier = Modifier.weight(1f)) {
                state.chooseProvider(p)
            }
        }
    }
    Hint(provider.keyHelp)
    val keyValue = if (provider == AiProvider.OPENROUTER) state.openRouterKey else state.googleKey
    var keyDraft by remember(provider, keyValue) { mutableStateOf(keyValue) }
    LabeledField(provider.keyTitle, keyDraft, password = true, placeholder = provider.keyHint) {
        keyDraft = it
    }
    RowWrap {
        ActionButton("Guardar clave", Modifier.weight(1f), primary = keyDraft.isNotBlank()) {
            state.setKey(keyDraft)
            state.message = if (keyDraft.isBlank()) "Clave borrada" else "Clave guardada en el teléfono"
        }
        ActionButton("Obtener clave", Modifier.weight(1f)) { onOpenKeyPage(provider.keyUrl) }
    }
    Divider()
    ModelPicker(state)
    Divider()
    SectionTitle("Describe el modelo")
    var prompt by remember { mutableStateOf(state.aiPrompt) }
    TextField(
        value = prompt,
        onValueChange = {
            prompt = it
            state.aiPrompt = it
        },
        minLines = 3,
        maxLines = 6,
        textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 13.sp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Palette.panelHigh,
            unfocusedContainerColor = Palette.panelHigh,
            focusedIndicatorColor = Palette.accent,
            unfocusedIndicatorColor = Palette.stroke,
            focusedTextColor = Palette.text,
            unfocusedTextColor = Palette.text
        ),
        modifier = Modifier.fillMaxWidth()
    )
    SectionTitle("Ideas")
    for (idea in AiCatalog.ideas) {
        RowWrap {
            ChipButton(idea, modifier = Modifier.fillMaxWidth()) {
                prompt = idea
                state.aiPrompt = idea
            }
        }
    }
    Divider()
    RowWrap {
        ActionButton("Generar modelo", Modifier.weight(1f), primary = true) {
            state.runAi(prompt, refine = false)
        }
        ActionButton("Modificar el actual", Modifier.weight(1f)) {
            state.runAi(prompt, refine = true)
        }
    }
    Hint(
        "La clave se guarda solo en este teléfono. El modelo se envía directamente al proveedor " +
            "elegido: OpenRouter o Google AI Studio."
    )
}

/**
 * Model chooser: every model the provider offers, with a free/paid filter, a search box and a
 * field for typing an id that is not in the list yet.
 */
@Composable
fun ModelPicker(state: AppState) {
    var custom by remember { mutableStateOf("") }
    val all = state.modelList
    SectionTitle(
        "Modelo",
        "${all.size} disponibles · ${state.freeModelCount()} gratis · ${state.paidModelCount()} de pago"
    )
    RowWrap {
        for (filter in ModelRegistry.PriceFilter.values()) {
            ChipButton(
                filter.label,
                selected = state.modelFilter == filter,
                modifier = Modifier.weight(1f)
            ) { state.applyPriceFilter(filter) }
        }
    }
    RowWrap {
        ActionButton(
            if (state.modelLoading) "Descargando lista…" else "Actualizar lista",
            Modifier.weight(1f),
            enabled = !state.modelLoading,
            primary = true
        ) { state.refreshModels() }
    }
    if (state.modelStatus != null) {
        Text(state.modelStatus ?: "", color = Palette.muted, fontSize = 11.sp)
    }
    LabeledField("Buscar modelo", state.modelQuery, placeholder = "gemini, claude, llama…") {
        state.modelQuery = it
    }

    val visible = state.visibleModels()
    val shown = visible.take(state.modelsShown)
    if (shown.isEmpty()) {
        Hint(
            if (all.isEmpty()) "Sin lista todavía. Pulsa «Actualizar lista»."
            else "Ningún modelo coincide con el filtro o la búsqueda."
        )
    } else {
        for (model in shown) {
            ModelRow(model, selected = state.currentModel() == model.id) { state.setModel(model.id) }
        }
        if (visible.size > state.modelsShown) {
            ActionButton(
                "Mostrar más (${visible.size - state.modelsShown})",
                Modifier.fillMaxWidth()
            ) { state.modelsShown += 24 }
        }
    }
    RowWrap {
        Box(Modifier.weight(1f)) {
            LabeledField("Otro modelo (id exacto)", custom, placeholder = "proveedor/modelo") { custom = it }
        }
    }
    ActionButton("Usar ese id", Modifier.fillMaxWidth(), enabled = custom.isNotBlank()) {
        state.setModel(custom.trim())
        state.message = "Modelo seleccionado: ${custom.trim()}"
    }
    Hint("Modelo actual: ${state.modelLabel(state.provider, state.currentModel())}")
    Hint(
        "El filtro «Gratis» usa los precios que publica el proveedor (OpenRouter) o el nivel " +
            "gratuito de AI Studio (Gemini Flash/Lite y Gemma). Los modelos de pago necesitan " +
            "saldo en la cuenta; la app nunca cobra nada."
    )
}

@Composable
private fun ModelRow(model: ModelRegistry.ModelEntry, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Palette.accentSoft else Palette.panelHigh.copy(alpha = 0.55f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                model.label,
                color = Palette.text,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                model.id + (if (model.context > 0) " · ${contextLabel(model.context)}" else ""),
                color = Palette.muted,
                fontSize = 10.sp
            )
        }
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(if (model.free) Palette.good.copy(alpha = 0.18f) else Palette.stroke)
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                if (model.free) "GRATIS" else "PAGO",
                color = if (model.free) Palette.good else Palette.muted,
                fontSize = 9.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

private fun contextLabel(tokens: Int): String = when {
    tokens >= 1_000_000 -> "${tokens / 1_000_000}M contexto"
    tokens >= 1_000 -> "${tokens / 1_000}K contexto"
    else -> "$tokens contexto"
}

// ---------------------------------------------------------------------------- files

@Composable
fun FilesPanel(state: AppState, onImport: () -> Unit) {
    val rev = state.revision
    val doc = state.doc
    SectionTitle("Exportar", "se guarda en Descargas/VoxelForge")
    for (row in chunk(ExportFormat.values().toList(), 2)) {
        RowWrap {
            for (format in row) {
                ChipButton(
                    format.label.substringBefore(" (").ifEmpty { format.label },
                    modifier = Modifier.weight(1f)
                ) { state.export(format) }
            }
        }
    }
    if (doc != null && doc.animations.isNotEmpty()) {
        Hint("Este modelo tiene ${doc.animations.size} animación(es): usa GLB o FBX para no perderlas.")
    }
    Divider()
    SectionTitle("Importar")
    ActionButton("Abrir archivo del teléfono", Modifier.fillMaxWidth(), primary = true) { onImport() }
    Hint(
        "Compatible: glTF/GLB, FBX ASCII, OBJ, STL y PLY. Los FBX binarios, .blend, .dae, .3ds y " +
            "los formatos empaquetados no se pueden abrir; expórtalos a glTF desde Blender."
    )
    Divider()
    SectionTitle("Proyectos guardados")
    RowWrap {
        ActionButton("Guardar proyecto", Modifier.weight(1f), primary = true) { state.saveProject() }
        ActionButton("Nuevo", Modifier.weight(1f)) { state.newScene() }
    }
    val projects = state.projects()
    if (projects.isEmpty()) {
        Hint("Todavía no hay proyectos guardados.")
    } else {
        key(rev) {
            for (p in projects.take(8)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Palette.panelHigh.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, color = Palette.text, fontSize = 13.sp)
                        Text("${p.bytes / 1024} KB", color = Palette.muted, fontSize = 11.sp)
                    }
                    ActionButton("Abrir") { state.openProject(p) }
                    ActionButton("Borrar") { state.deleteProject(p) }
                }
            }
        }
    }
}
