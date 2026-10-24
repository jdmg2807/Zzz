package com.arena.modeler.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arena.modeler.ai.AiProvider
import com.arena.modeler.ai.ChatStore
import com.arena.modeler.ai.ModelCatalog
import com.arena.modeler.render.ViewMode
import com.arena.modeler.render.ViewportView
import kotlinx.coroutines.delay

/**
 * Pantalla única: visor 3D a pantalla completa, barra superior flotante, superposiciones del visor,
 * panel inferior con pestañas y hoja de chat.
 */
@Composable
fun ModelerApp() {
    val context = LocalContext.current
    val state = remember { AppState(context) }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) state.importUri(uri) }

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> if (uri != null) state.attachImage(uri) }

    DisposableEffect(Unit) {
        onDispose {
            state.detach()
            state.dispose()
        }
    }

    BackHandler(enabled = state.showModelPicker) { state.showModelPicker = false }
    BackHandler(enabled = state.showSettings) { state.showSettings = false }
    BackHandler(enabled = !state.showSettings && !state.showModelPicker && state.panelOpen) {
        state.panelOpen = false
    }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        AndroidView(
            factory = { ctx -> ViewportView(ctx).also { state.attach(it) } },
            modifier = Modifier.fillMaxSize(),
            update = { state.syncViewport() }
        )

        ViewportOverlay(state)

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding()) {
            if (state.panelOpen) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 210.dp, max = screenHeight * 0.60f)
                        .background(
                            Brush.verticalGradient(
                                listOf(Palette.panel.copy(alpha = 0.98f), Palette.panel)
                            )
                        )
                        .border(1.dp, Palette.stroke, RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                ) {
                    if (state.tab == Tab.CHAT) {
                        ChatPanel(state, Modifier.weight(1f), onAttach = {
                            runCatching { imageLauncher.launch(arrayOf("image/*")) }
                        })
                    } else {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState())
                        ) {
                            PanelContent(
                                state = state,
                                onImport = {
                                    runCatching { importLauncher.launch(arrayOf("*/*")) }
                                }
                            )
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
            }
            TabBar(state)
        }

        TopBar(state)

        state.message?.let { MessageBanner(it) { state.message = null } }

        state.busyLabel?.let { BusyOverlay(it, onCancel = { state.cancelExport() }) }

        if (state.showSettings) SettingsSheet(state) { state.showSettings = false }
        if (state.showModelPicker) ModelPicker(state) { state.showModelPicker = false }

        state.pendingTurn?.let { turn ->
            ConfirmDialog(
                title = "La IA quiere cambiar la escena",
                body = turn.calls.joinToString("\n") { "· ${it.call.tool}${if (it.note.isNotBlank()) ": ${it.note}" else ""}" },
                onConfirm = { state.applyPendingTurn() },
                onDismiss = { state.discardPendingTurn() }
            )
        }
    }
}

@Composable
private fun PanelContent(state: AppState, onImport: () -> Unit) {
    when (state.tab) {
        Tab.OBJECTS -> ObjectsPanel(state)
        Tab.CREATE -> CreatePanel(state)
        Tab.EDIT -> EditPanel(state)
        Tab.MATERIAL -> MaterialPanel(state)
        Tab.ANIMATE -> AnimatePanel(state)
        Tab.FILES -> FilesPanel(state, onImport)
        Tab.HISTORY -> HistoryPanel(state)
        Tab.CHAT -> Unit
    }
}

// ---------------------------------------------------------------------------- barra superior

@Composable
private fun TopBar(state: AppState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(10.dp)
            .background(Palette.panel.copy(alpha = 0.94f), RoundedCornerShape(14.dp))
            .border(1.dp, Palette.stroke, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(26.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.accentSoft)
                .border(1.dp, Palette.accent, RoundedCornerShape(8.dp))
        )
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text("VoxelForge 3D", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            val name = state.doc?.name ?: "—"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (name.length > 22) name.take(22) + "…" else name,
                    color = Palette.muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.dirty) {
                    Text(" · sin guardar", color = Palette.warn, fontSize = 11.sp)
                }
            }
        }
        SmallButton("↶", enabled = state.undoDepth > 0) { state.undo() }
        Spacer(Modifier.width(4.dp))
        SmallButton("↷", enabled = state.redoDepth > 0) { state.redo() }
        Spacer(Modifier.width(4.dp))
        SmallButton("Guardar") { state.saveProject() }
        Spacer(Modifier.width(4.dp))
        SmallButton("Ajustes") { state.showSettings = true }
    }
}

@Composable
private fun SmallButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(9.dp))
            .background(if (enabled) Palette.panelHigh else Palette.panelHigh.copy(alpha = 0.4f))
            .border(1.dp, Palette.stroke, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 9.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            color = if (enabled) Palette.text else Palette.muted.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
    }
}

// ---------------------------------------------------------------------------- superposición del visor

@Composable
private fun ViewportOverlay(state: AppState) {
    Column(
        Modifier
            .padding(top = 78.dp, end = 10.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (mode in ViewMode.values()) {
                ChipButton(mode.label, selected = state.viewMode == mode) { state.setViewMode(mode) }
            }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ChipButton("Frente") { state.cameraPreset("front") }
            ChipButton("Lado") { state.cameraPreset("side") }
            ChipButton("Arriba") { state.cameraPreset("top") }
            ChipButton("Todo") { state.frameAll() }
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            ChipButton(
                if (state.sculptActive) "Pincel: sí" else "Pincel",
                selected = state.sculptActive
            ) { state.setSculpting(!state.sculptActive) }
            if (state.sculptActive) {
                for (mode in com.arena.modeler.geom.MeshOps.SculptMode.values()) {
                    ChipButton(mode.label, selected = state.sculptMode == mode) {
                        state.sculptMode = mode
                    }
                }
            }
            ChipButton(if (state.autoRotate) "Giro: sí" else "Giro", selected = state.autoRotate) {
                state.enableAutoRotate(!state.autoRotate)
            }
        }
    }
}

// ---------------------------------------------------------------------------- pestañas

@Composable
private fun TabBar(state: AppState) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Palette.bg)
            .padding(horizontal = 8.dp, vertical = 6.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        for (tab in Tab.values()) {
            ChipButton(tab.label, selected = state.tab == tab && state.panelOpen) {
                if (state.tab == tab && state.panelOpen) {
                    state.panelOpen = false
                } else {
                    state.tab = tab
                    state.panelOpen = true
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- chat

@Composable
private fun ChatPanel(state: AppState, modifier: Modifier = Modifier, onAttach: () -> Unit) {
    val listState = rememberLazyListState()
    val messages = state.chatMessages
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Chat de modelado", color = Palette.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${state.adapterLabel()} · ${state.currentModelLabel()}",
                    color = Palette.muted,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            SmallButton("Modelo") { state.showModelPicker = true }
            Spacer(Modifier.width(4.dp))
            SmallButton("Limpiar") { state.clearChat() }
        }
        if (state.catalogStale || state.catalogError != null) {
            Hint(
                "El catálogo no está al día (${state.catalogAgeLabel()}). " +
                    (state.catalogError?.takeIf { it.isNotBlank() }?.let { "$it. " } ?: "") +
                    "Los precios mostrados pueden haber cambiado."
            )
        }
        Spacer(Modifier.height(4.dp))
        if (messages.isEmpty()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Hint("Pide lo que quieras modelar. La IA trabaja con herramientas sobre tu escena, no ejecuta código.")
                Hint("«Crea una taza con asa» · «Bisela las aristas y aplica un material azul» · «Reduce a la mitad los polígonos»")
                Hint("También puedes adjuntar una foto de referencia si el modelo acepta imágenes.")
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(messages, key = { it.id }) { m -> ChatBubble(m) }
            }
        }
        state.chatStatus?.let {
            Text(it, color = Palette.accent, fontSize = 11.sp, modifier = Modifier.padding(vertical = 3.dp))
        }
        if (state.attachedImages.isNotEmpty()) {
            Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((i, img) in state.attachedImages.withIndex()) {
                    ChipButton("imagen ${i + 1} (${img.bytes.size / 1024} KB)") { state.removeImage(i) }
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            TextField(
                value = state.chatInput,
                onValueChange = { state.chatInput = it },
                placeholder = { Text("¿Qué modelamos?", color = Palette.muted.copy(alpha = 0.7f), fontSize = 13.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 13.sp),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Palette.panelHigh,
                    unfocusedContainerColor = Palette.panelHigh,
                    focusedIndicatorColor = Palette.stroke,
                    unfocusedIndicatorColor = Palette.stroke,
                    focusedTextColor = Palette.text,
                    unfocusedTextColor = Palette.text,
                    cursorColor = Palette.accent
                ),
                maxLines = 4,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            Column {
                if (state.chatBusy) {
                    ActionButton("Parar", primary = true) { state.cancelChat() }
                } else {
                    ActionButton("Enviar", primary = true) { state.sendChat() }
                }
                Spacer(Modifier.height(4.dp))
                SmallButton("Imagen", enabled = state.supportsImages() || state.attachedImages.isEmpty()) { onAttach() }
            }
        }
        if (state.chatMessages.isEmpty() && state.apiKey.isBlank()) {
            Hint("Falta la API key: configúrala en Ajustes para usar el chat.")
        }
    }
}

@Composable
private fun ChatBubble(m: ChatStore.Message) {
    val isUser = m.role == ChatStore.Message.Role.USER
    val isSystem = m.role == ChatStore.Message.Role.SYSTEM
    val bg = when {
        isUser -> Palette.accentSoft
        isSystem -> Palette.panelHigh.copy(alpha = 0.75f)
        else -> Palette.panelHigh
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            when {
                isUser -> "Tú"
                isSystem -> "Operaciones"
                else -> "Asistente"
            },
            color = if (m.error != null) Palette.bad else Palette.muted,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            m.text,
            color = if (m.error != null) Palette.warn else Palette.text,
            fontSize = 13.sp,
            lineHeight = 18.sp
        )
        if (m.imagesAttached > 0) {
            Text("${m.imagesAttached} imagen(es) adjunta(s)", color = Palette.muted, fontSize = 10.sp)
        }
    }
}

// ---------------------------------------------------------------------------- avisos

@Composable
private fun MessageBanner(text: String, onDismiss: () -> Unit) {
    LaunchedEffect(text) {
        delay(4600)
        onDismiss()
    }
    Box(
        Modifier.fillMaxWidth().padding(top = 76.dp, start = 14.dp, end = 14.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            Modifier
                .background(Color(0xF21E222B), RoundedCornerShape(12.dp))
                .border(1.dp, Palette.stroke, RoundedCornerShape(12.dp))
                .clickable { onDismiss() }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Text(text, color = Palette.text, fontSize = 12.sp, lineHeight = 16.sp)
        }
    }
}

@Composable
private fun BusyOverlay(label: String, onCancel: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color(0x66000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier
                .background(Palette.panel, RoundedCornerShape(16.dp))
                .border(1.dp, Palette.stroke, RoundedCornerShape(16.dp))
                .padding(horizontal = 26.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator(
                color = Palette.accent,
                strokeWidth = 3.dp,
                modifier = Modifier.size(32.dp)
            )
            Spacer(Modifier.height(12.dp))
            Text(label, color = Palette.text, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            ActionButton("Cancelar") { onCancel() }
        }
    }
}

@Composable
private fun ConfirmDialog(title: String, body: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    DialogScaffold(onDismiss) {
        Text(title, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(body, color = Palette.muted, fontSize = 12.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(12.dp))
        Hint("Las operaciones destructivas se pueden deshacer, pero conviene revisarlas antes.")
        RowWrap {
            ActionButton("Aplicar", Modifier.weight(1f), primary = true) { onConfirm() }
            ActionButton("Descartar", Modifier.weight(1f)) { onDismiss() }
        }
    }
}

@Composable
private fun DialogScaffold(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .padding(18.dp)
                .fillMaxWidth()
                .background(Palette.panel, RoundedCornerShape(18.dp))
                .border(1.dp, Palette.stroke, RoundedCornerShape(18.dp))
                .clickable { }
        ) {
            Column(
                Modifier
                    .heightIn(max = 600.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp),
                content = content
            )
        }
    }
}

// ---------------------------------------------------------------------------- ajustes

@Composable
private fun SettingsSheet(state: AppState, onClose: () -> Unit) {
    val context = LocalContext.current
    val backgrounds = listOf(
        "Estudio" to floatArrayOf(0.07f, 0.08f, 0.10f),
        "Cielo" to floatArrayOf(0.10f, 0.14f, 0.22f),
        "Claro" to floatArrayOf(0.72f, 0.75f, 0.80f)
    )
    var selectedBg by remember { mutableStateOf(0) }
    DialogScaffold(onClose) {
        Text("Ajustes", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)

        SectionTitle("Inteligencia artificial", state.adapterLabel())
        RowWrap {
            for (provider in AiProvider.values()) {
                ChipButton(
                    provider.label,
                    selected = state.providerId == provider.id,
                    modifier = Modifier.weight(1f)
                ) { state.selectProvider(provider.id) }
            }
        }
        Hint(
            "La clave se guarda cifrada en el teléfono y solo se envía al proveedor seleccionado. " +
                "La app no incluye claves propias."
        )
        Spacer(Modifier.height(6.dp))
        LabeledField(
            label = "API key · ${state.adapterLabel()}",
            value = state.apiKey,
            password = !state.keyVisible,
            placeholder = state.adapter().keyHint,
            onValueChange = { state.updateKey(it) }
        )
        RowWrap {
            ActionButton(if (state.keyVisible) "Ocultar" else "Ver", Modifier.weight(1f)) {
                state.keyVisible = !state.keyVisible
            }
            ActionButton("Guardar clave", Modifier.weight(1f), primary = true) { state.saveKey() }
        }
        RowWrap {
            ActionButton(
                if (state.checkingKey) "Comprobando…" else "Comprobar conexión",
                Modifier.weight(1f),
                enabled = !state.checkingKey
            ) { state.checkConnection() }
            ActionButton("Abrir página de claves", Modifier.weight(1f)) {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(state.adapter().keyUrl)))
                }
            }
        }
        state.connectionStatus?.let {
            Text(it, color = if (it.startsWith("Error")) Palette.bad else Palette.good, fontSize = 12.sp)
        }
        Hint(state.adapter().keyHelp)
        Hint(state.secureStorageLabel())

        Divider()
        SectionTitle("Modelo", state.catalogAgeLabel())
        Text(state.currentModelLabel(), color = Palette.text, fontSize = 12.sp)
        RowWrap {
            ActionButton("Elegir modelo", Modifier.weight(1f), primary = true) {
                state.showModelPicker = true
            }
            ActionButton(if (state.loadingModels) "Actualizando…" else "Actualizar lista", Modifier.weight(1f)) {
                state.refreshModels()
            }
        }
        if (state.catalogStale) {
            Hint("Los datos del catálogo están desactualizados o nunca se descargaron.")
        }

        Divider()
        SectionTitle("Visor")
        ToggleRow("Cuadrícula", state.showGrid) {
            state.showGrid = it
            state.syncViewport()
            state.persistSettings()
        }
        ToggleRow("Malla (wireframe)", state.showWireframe) {
            state.showWireframe = it
            state.syncViewport()
            state.persistSettings()
        }
        ToggleRow("Girar solo", state.autoRotate) { state.enableAutoRotate(it) }
        RowWrap {
            ActionButton("Encuadrar todo", Modifier.weight(1f)) { state.frameAll() }
            ActionButton("Encuadrar objeto", Modifier.weight(1f)) { state.frameSelection() }
        }
        SectionTitle("Fondo")
        RowWrap {
            for ((index, pair) in backgrounds.withIndex()) {
                ChipButton(pair.first, selected = selectedBg == index, modifier = Modifier.weight(1f)) {
                    selectedBg = index
                    state.setBackground(pair.second)
                }
            }
        }

        SectionTitle("Acerca de")
        Text(
            "VoxelForge 3D · 1.1.0\n" +
                state.appVersionLabel() + "\n" +
                "Modelado, animación e IA en el teléfono.\n" +
                "Formatos: GLB, glTF, FBX ASCII, OBJ, STL y PLY.\n" +
                "Nada sale del teléfono salvo los textos e imágenes que envíes al proveedor de IA que elijas.",
            color = Palette.muted,
            fontSize = 11.sp,
            lineHeight = 16.sp
        )
        Spacer(Modifier.height(14.dp))
        ActionButton("Cerrar", Modifier.fillMaxWidth(), primary = true) { onClose() }
    }
}

// ---------------------------------------------------------------------------- selector de modelos

@Composable
private fun ModelPicker(state: AppState, onClose: () -> Unit) {
    DialogScaffold(onClose) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Modelos", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(
                    "${state.adapterLabel()} · ${state.catalogAgeLabel()}",
                    color = Palette.muted,
                    fontSize = 11.sp
                )
            }
            SmallButton(if (state.loadingModels) "…" else "Actualizar") { state.refreshModels() }
        }
        if (state.catalogStale) {
            Text(
                "Catálogo desactualizado" + (state.catalogError?.let { ": $it" } ?: ""),
                color = Palette.warn,
                fontSize = 11.sp,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        Spacer(Modifier.height(6.dp))
        LabeledField(
            label = "Buscar por nombre o identificador",
            value = state.modelQuery,
            placeholder = "gemini, claude, llama…",
            onValueChange = { state.modelQuery = it }
        )
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            for (filter in ModelCatalog.PriceFilter.values()) {
                val count = when (filter) {
                    ModelCatalog.PriceFilter.ALL -> state.catalogModels.size
                    ModelCatalog.PriceFilter.FREE -> state.freeCount()
                    ModelCatalog.PriceFilter.PAID -> state.paidCount()
                    ModelCatalog.PriceFilter.UNKNOWN -> state.unknownCount()
                }
                ChipButton("${filter.label} ($count)", selected = state.priceFilter == filter) {
                    state.setPriceFilter(filter)
                }
            }
        }
        if (state.catalogModels.isEmpty()) {
            Hint(
                "No hay modelos en el catálogo. Pulsa «Actualizar»: la lista se descarga del " +
                    "proveedor y se guarda para poder usarla sin conexión."
            )
        }
        val visible = state.visibleModels()
        Hint(
            "${visible.size} de ${state.catalogModels.size} modelos · " +
                "${state.freeCount()} gratis · ${state.paidCount()} de pago · " +
                "${state.unknownCount()} con precio desconocido"
        )
        for (model in visible.take(state.modelsShown)) {
            ModelRow(model, selected = model.id == state.modelId) {
                state.chooseModel(model.id)
                onClose()
            }
        }
        if (visible.size > state.modelsShown) {
            Spacer(Modifier.height(6.dp))
            ActionButton("Mostrar más (${visible.size - state.modelsShown})", Modifier.fillMaxWidth()) {
                state.modelsShown += 30
            }
        }
        Spacer(Modifier.height(10.dp))
        ActionButton("Cerrar", Modifier.fillMaxWidth()) { onClose() }
    }
}

@Composable
private fun ModelRow(
    model: com.arena.modeler.ai.providers.ModelInfo,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Palette.accentSoft else Palette.panelHigh.copy(alpha = 0.6f))
            .border(
                1.dp,
                if (selected) Palette.accent else Palette.stroke,
                RoundedCornerShape(9.dp)
            )
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                model.label,
                color = Palette.text,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Text(model.priceLabel(), color = Palette.muted, fontSize = 10.sp)
        }
        Text(model.id, color = Palette.muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val extras = ArrayList<String>()
        extras.add(model.capabilitiesLabel())
        model.contextWindow?.let { extras.add("contexto ${(it / 1000)}k") }
        model.limitsNote?.let { extras.add(it) }
        Text(
            extras.filter { it.isNotBlank() }.joinToString(" · "),
            color = Palette.muted.copy(alpha = 0.85f),
            fontSize = 10.sp,
            lineHeight = 13.sp
        )
    }
}

// ---------------------------------------------------------------------------- utilidades

@Composable
private fun CenteredHint(text: String) {
    Box(Modifier.fillMaxWidth().padding(18.dp), contentAlignment = Alignment.Center) {
        Text(text, color = Palette.muted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}
