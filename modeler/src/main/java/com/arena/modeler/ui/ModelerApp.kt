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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arena.modeler.render.ViewportView
import kotlinx.coroutines.delay

/**
 * The whole app: a full-bleed 3D viewport with a floating top bar, a bottom tab bar and one panel
 * per task (objects, transform, material, animation, AI, files).
 */
@Composable
fun ModelerApp() {
    val context = LocalContext.current
    val state = remember { AppState(context) }
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) state.importUri(uri)
    }

    DisposableEffect(Unit) {
        onDispose {
            state.detach()
            state.dispose()
        }
    }

    BackHandler(enabled = state.showSettings) { state.showSettings = false }
    BackHandler(enabled = !state.showSettings && state.panelOpen) { state.panelOpen = false }

    Box(Modifier.fillMaxSize().background(Palette.bg)) {
        AndroidView(
            factory = { ctx -> ViewportView(ctx).also { state.attach(it) } },
            modifier = Modifier.fillMaxSize(),
            update = { state.syncViewport() }
        )

        TopBar(state)

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
            if (state.panelOpen) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 190.dp, max = screenHeight * 0.52f)
                        .background(
                            Brush.verticalGradient(
                                listOf(Palette.panel.copy(alpha = 0.97f), Palette.panel)
                            )
                        )
                        .border(
                            width = 1.dp,
                            color = Palette.stroke,
                            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)
                        )
                ) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        PanelContent(
                            state = state,
                            onOpenKeyPage = { url ->
                                runCatching {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                    context.startActivity(intent)
                                }
                            },
                            onImport = { runCatching { importLauncher.launch(arrayOf("*/*")) } }
                        )
                        Spacer(Modifier.height(18.dp))
                    }
                }
            }
            TabBar(state)
        }

        state.message?.let { text ->
            MessageBanner(text) { state.message = null }
        }

        state.busyLabel?.let { label ->
            BusyOverlay(label)
        }

        if (state.showSettings) {
            SettingsSheet(state) { state.showSettings = false }
        }
    }
}

@Composable
private fun PanelContent(state: AppState, onOpenKeyPage: (String) -> Unit, onImport: () -> Unit) {
    when (state.tab) {
        Tab.OBJECTS -> ObjectsPanel(state)
        Tab.EDIT -> EditPanel(state)
        Tab.MATERIAL -> MaterialPanel(state)
        Tab.ANIMATE -> AnimatePanel(state)
        Tab.AI -> AiPanel(state, onOpenKeyPage)
        Tab.FILES -> FilesPanel(state, onImport)
    }
}

@Composable
private fun TopBar(state: AppState) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(12.dp)
            .background(Palette.panel.copy(alpha = 0.93f), RoundedCornerShape(14.dp))
            .border(1.dp, Palette.stroke, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text("VoxelForge 3D", color = Palette.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            val name = state.doc?.name ?: "—"
            Text(
                if (name.length > 24) name.take(24) + "…" else name,
                color = Palette.muted,
                fontSize = 11.sp
            )
        }
        SmallButton("Deshacer", enabled = state.undoDepth > 0) { state.undo() }
        Spacer(Modifier.width(6.dp))
        SmallButton("Guardar", enabled = state.doc != null) { state.saveProject() }
        Spacer(Modifier.width(6.dp))
        SmallButton("Ajustes") { state.showSettings = true }
    }
}

@Composable
private fun SmallButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier
            .background(
                if (enabled) Palette.panelHigh else Palette.panelHigh.copy(alpha = 0.4f),
                RoundedCornerShape(9.dp)
            )
            .border(1.dp, Palette.stroke, RoundedCornerShape(9.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Text(
            text,
            color = if (enabled) Palette.text else Palette.muted.copy(alpha = 0.5f),
            fontSize = 12.sp
        )
    }
}

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

@Composable
private fun MessageBanner(text: String, onDismiss: () -> Unit) {
    LaunchedEffect(text) {
        delay(4600)
        onDismiss()
    }
    Box(
        Modifier
            .fillMaxWidth()
            .padding(top = 84.dp, start = 14.dp, end = 14.dp),
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
private fun BusyOverlay(label: String) {
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
                modifier = Modifier.size(34.dp)
            )
            Spacer(Modifier.height(14.dp))
            Text(label, color = Palette.text, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SettingsSheet(state: AppState, onClose: () -> Unit) {
    val backgrounds = listOf(
        "Estudio" to floatArrayOf(0.07f, 0.08f, 0.10f),
        "Cielo" to floatArrayOf(0.10f, 0.14f, 0.22f),
        "Claro" to floatArrayOf(0.72f, 0.75f, 0.80f)
    )
    var selectedBg by remember { mutableStateOf(0) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xAA000000))
            .clickable { onClose() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .padding(18.dp)
                .fillMaxWidth()
                .background(Palette.panel, RoundedCornerShape(18.dp))
                .border(1.dp, Palette.stroke, RoundedCornerShape(18.dp))
                // Swallow taps on the card so they do not close the sheet.
                .clickable { }
        ) {
            Column(
                Modifier
                    .heightIn(max = 560.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(18.dp)
            ) {
                Text("Ajustes", color = Palette.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                SectionTitle("Cámara")
                RowWrap {
                    ActionButton("Frente", Modifier.weight(1f)) { state.cameraFront() }
                    ActionButton("Lado", Modifier.weight(1f)) { state.cameraSide() }
                    ActionButton("Arriba", Modifier.weight(1f)) { state.cameraTop() }
                }
                RowWrap {
                    ActionButton("Encuadrar todo", Modifier.weight(1f)) { state.frameAll() }
                    ActionButton("Encuadrar objeto", Modifier.weight(1f)) { state.frameSelection() }
                }
                SectionTitle("Fondo")
                RowWrap {
                    for ((index, pair) in backgrounds.withIndex()) {
                        ChipButton(
                            pair.first,
                            selected = selectedBg == index,
                            modifier = Modifier.weight(1f)
                        ) {
                            selectedBg = index
                            state.setBackground(pair.second)
                        }
                    }
                }
                SectionTitle("Inteligencia artificial")
                Text("Proveedor: ${state.provider.label}", color = Palette.text, fontSize = 13.sp)
                Text(
                    if (state.currentKey().isBlank()) "Sin API key configurada"
                    else "API key configurada (${state.currentKey().take(6)}…)",
                    color = if (state.currentKey().isBlank()) Palette.warn else Palette.good,
                    fontSize = 12.sp
                )
                RowWrap {
                    ActionButton("Configurar IA", Modifier.weight(1f), primary = true) {
                        state.tab = Tab.AI
                        state.panelOpen = true
                        onClose()
                    }
                }
                SectionTitle("Acerca de")
                Text(
                    "VoxelForge 3D · versión 1.0.0\n" +
                        "Modelado, animación e IA en el teléfono.\n" +
                        "Formatos: glTF/GLB, FBX ASCII, OBJ, STL, PLY.\n" +
                        "Nada se sube a ningún servidor salvo los textos que envíes a tu proveedor de IA.",
                    color = Palette.muted,
                    fontSize = 11.sp,
                    lineHeight = 16.sp
                )
                Spacer(Modifier.height(14.dp))
                ActionButton("Cerrar", Modifier.fillMaxWidth(), primary = true) { onClose() }
            }
        }
    }
}
