package com.arena.voxel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import com.arena.voxel.Game
import com.arena.voxel.core.Ctx
import com.arena.voxel.imports.AutoImport
import kotlinx.coroutines.delay

/**
 * Pantalla **Mundos** con la estructura de Bedrock Edition.
 *
 * Bedrock no usa la lista con marco de Java: la pantalla tiene una barra superior con la flecha
 * de volver y el título, un panel oscuro central con las tarjetas de mundo (nombre arriba,
 * datos abajo y el botón de jugar a la derecha de cada tarjeta) y una barra inferior con las
 * acciones grandes. Esa es la distribución que se replica aquí, medida en píxeles de GUI con
 * [Gui] para que escale igual en cualquier teléfono.
 *
 * La lógica es la misma de antes (una única ranura de guardado, importación desde el selector de
 * archivos de Android y mensajes de resultado), solo cambia la presentación.
 */
@Composable
fun SingleplayerScreen(
    onBack: () -> Unit,
    onPlay: () -> Unit,
    forcedScale: Int = 0
) {
    GuiScreen(forcedScale) { gui ->
        var importing by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var showImport by remember { mutableStateOf(false) }
        var refresh by remember { mutableStateOf(0) }

        fun pick(uri: android.net.Uri?, hint: AutoImport.Kind?) {
            if (uri == null) return
            showImport = false
            importing = true
            message = null
            Game.importFile(uri, hint) { result, success, kind ->
                importing = false
                message = result
                refresh++
                if (success && kind == AutoImport.Kind.WORLD) onPlay()
            }
        }

        val anyFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pick(uri, null)
        }
        val worldPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pick(uri, AutoImport.Kind.WORLD)
        }
        val texturePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pick(uri, AutoImport.Kind.TEXTURES)
        }
        val shaderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pick(uri, AutoImport.Kind.SHADERS)
        }
        val modPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            pick(uri, AutoImport.Kind.MOD)
        }

        LaunchedEffect(importing) {
            while (importing) {
                delay(250)
                if (!Game.importing && Game.importStatus.isNotEmpty()) {
                    importing = false
                    message = Game.importStatus
                }
            }
        }

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PanoramaBackground()

            // ---- Barra superior de Bedrock -------------------------------------------
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000))))
                    .padding(horizontal = gui.px(6f), vertical = gui.px(5f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BedrockButton(gui, "◀", width = gui.px(20f), height = gui.px(18f), fontSize = 9f) { onBack() }
                Spacer(Modifier.width(gui.px(6f)))
                BedrockText(gui, "Mundos", size = 11f, bold = true)
                Spacer(Modifier.weight(1f))
                BedrockButton(gui, "Nuevo mundo", width = gui.px(78f), height = gui.px(18f), fontSize = 7.5f) {
                    Game.startNewWorld(System.currentTimeMillis())
                    onPlay()
                }
            }

            // ---- Panel central con la lista de mundos --------------------------------
            val worlds = remember(refresh) { WorldList.scan() }
            BedrockPanel(
                gui,
                Modifier
                    .align(Alignment.Center)
                    .padding(top = gui.px(14f))
                    .width(gui.px(300f))
                    .height(gui.px(132f))
            ) {
                Column(Modifier.fillMaxSize()) {
                    BedrockTitleBar(gui, "Selecciona un mundo")
                    if (worlds.isEmpty()) {
                        Column(
                            Modifier.fillMaxSize().padding(gui.px(10f)),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            BedrockText(gui, "Todavía no hay ningún mundo", size = 9f, bold = true)
                            Spacer(Modifier.height(gui.px(4f)))
                            BedrockText(
                                gui,
                                "Crea uno nuevo o importa un .zip de Minecraft Java",
                                size = 7f,
                                color = Bedrock.textDim
                            )
                        }
                    } else {
                        LazyColumn(
                            Modifier.fillMaxSize().padding(gui.px(4f)),
                            verticalArrangement = Arrangement.spacedBy(gui.px(3f))
                        ) {
                            items(worlds) { world ->
                                WorldCard(gui, world, enabled = Game.hasSave) {
                                    world.onSelect()
                                    onPlay()
                                }
                            }
                        }
                    }
                }
            }

            // ---- Barra inferior de acciones -----------------------------------------
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = gui.px(8f)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BedrockButton(
                    gui,
                    "Jugar",
                    width = gui.px(300f),
                    height = gui.px(26f),
                    fontSize = 10f,
                    enabled = Game.hasSave
                ) { onPlay() }
                Spacer(Modifier.height(gui.px(5f)))
                Row(horizontalArrangement = Arrangement.spacedBy(gui.px(5f))) {
                    BedrockButton(gui, "Importar de Java", width = gui.px(147f), height = gui.px(20f), fontSize = 7.5f) {
                        showImport = true
                    }
                    BedrockButton(gui, "Ajustes", width = gui.px(147f), height = gui.px(20f), fontSize = 7.5f) {
                        onBack()
                    }
                }
            }

            // ---- Modal de importación (panel Bedrock) --------------------------------
            if (showImport) {
                Box(
                    Modifier.fillMaxSize().background(Color(0x99000000))
                        .pointerInput(Unit) { detectTapGestures { showImport = false } }
                )
                BedrockPanel(
                    gui,
                    Modifier
                        .align(Alignment.Center)
                        .width(gui.px(226f))
                ) {
                    Column(Modifier.fillMaxWidth()) {
                        BedrockTitleBar(gui, "Importar de Java", onClose = { showImport = false })
                        Column(
                            Modifier.fillMaxWidth().padding(gui.px(8f)),
                            verticalArrangement = Arrangement.spacedBy(gui.px(5f))
                        ) {
                            BedrockButton(gui, "Mundo (.zip)", width = gui.px(210f), height = gui.px(20f), fontSize = 7.5f) {
                                showImport = false
                                worldPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            }
                            BedrockButton(gui, "Texturas (resource pack)", width = gui.px(210f), height = gui.px(20f), fontSize = 7.5f) {
                                showImport = false
                                texturePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            }
                            BedrockButton(gui, "Shaders", width = gui.px(210f), height = gui.px(20f), fontSize = 7.5f) {
                                showImport = false
                                shaderPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                            }
                            BedrockButton(gui, "Mod (.jar)", width = gui.px(210f), height = gui.px(20f), fontSize = 7.5f) {
                                showImport = false
                                modPicker.launch(arrayOf("application/java-archive", "application/zip", "*/*"))
                            }
                            BedrockButton(gui, "Cualquier archivo (detectar)", width = gui.px(210f), height = gui.px(20f), fontSize = 7.5f) {
                                showImport = false
                                anyFile.launch(arrayOf("*/*"))
                            }
                        }
                    }
                }
            }

            // ---- Progreso de importación --------------------------------------------
            if (importing) {
                BedrockPanel(gui, Modifier.align(Alignment.Center).width(gui.px(220f))) {
                    Column(Modifier.padding(gui.px(10f)), horizontalAlignment = Alignment.CenterHorizontally) {
                        BedrockText(gui, "Importando…", size = 9f, bold = true)
                        Spacer(Modifier.height(gui.px(4f)))
                        BedrockText(
                            gui,
                            "El log queda en Descargas/VoxelCraft",
                            size = 6.5f,
                            color = Bedrock.textDim
                        )
                    }
                }
            }

            val shown = message
            if (shown != null && !importing) {
                Box(
                    Modifier.fillMaxSize().background(Color(0x99000000))
                        .pointerInput(Unit) { detectTapGestures { message = null } }
                )
                BedrockPanel(gui, Modifier.align(Alignment.Center).width(gui.px(250f))) {
                    Column(Modifier.fillMaxWidth()) {
                        BedrockTitleBar(gui, "Importación", onClose = { message = null })
                        Column(Modifier.fillMaxWidth().padding(gui.px(8f))) {
                            Text(
                                shown,
                                color = Color(0xFFE8E8E8),
                                fontSize = gui.text(7f),
                                fontFamily = androidx.compose.ui.text.font.FontFamily.SansSerif
                            )
                            Spacer(Modifier.height(gui.px(8f)))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                BedrockButton(gui, "Entendido", width = gui.px(90f), height = gui.px(20f), fontSize = 7.5f) {
                                    message = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Tarjeta de mundo de Bedrock: nombre, datos y botón de jugar dentro de la misma fila. */
@Composable
private fun WorldCard(gui: Gui, world: WorldEntry, enabled: Boolean, onPlay: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(gui.px(34f))
            .background(if (world.selected) Brush.verticalGradient(listOf(Color(0xFF2E2E2E), Color(0xFF1C1C1C)))
            else Brush.verticalGradient(listOf(Color(0xFF232323), Color(0xFF161616))))
            .pointerInput(world.name) { detectTapGestures { world.onSelect() } }
            .padding(horizontal = gui.px(5f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Miniatura: bloque de hierba dibujado con dos rectángulos, como las tarjetas de Bedrock.
        Box(
            Modifier
                .width(gui.px(26f))
                .height(gui.px(26f))
                .background(Brush.verticalGradient(listOf(Color(0xFF6AA84F), Color(0xFF8B5A2B))))
        )
        Spacer(Modifier.width(gui.px(6f)))
        Column(Modifier.weight(1f)) {
            BedrockText(gui, world.name, size = 8f, bold = true)
            Spacer(Modifier.height(gui.px(2f)))
            BedrockText(gui, world.subtitle, size = 6f, color = Bedrock.textDim)
        }
        BedrockButton(gui, "Jugar", width = gui.px(52f), height = gui.px(20f), fontSize = 7f, enabled = enabled) {
            onPlay()
        }
    }
}

/** Una fila de la lista de mundos. */
class WorldEntry(
    val name: String,
    val subtitle: String,
    val selected: Boolean,
    val onSelect: () -> Unit
)

object WorldList {

    /** El motor tiene una sola ranura: la lista muestra esa ranura con sus datos de guardado. */
    fun scan(): List<WorldEntry> {
        if (!Game.hasSave) return emptyList()
        val dir = Game.savesDir
        val sizeMb = try {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024.0 * 1024.0)
        } catch (t: Throwable) {
            0.0
        }
        val modified = try {
            java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date(java.io.File(dir, "level.dat").lastModified()))
        } catch (t: Throwable) {
            "?"
        }
        return listOf(
            WorldEntry(
                name = "Mundo 1",
                subtitle = "%.1f MB · %s · %s".format(sizeMb, modified, Ctx.relativePath(dir)),
                selected = true,
                onSelect = {}
            )
        )
    }
}
