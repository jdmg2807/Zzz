package com.arena.voxel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.arena.voxel.Game
import com.arena.voxel.core.Ctx
import com.arena.voxel.imports.AutoImport
import kotlinx.coroutines.delay

/**
 * "Seleccionar mundo" — the screen behind *Un jugador* in Java Edition.
 *
 * Java layout: title top-left, a framed world list in the middle, and the action buttons in a
 * 2-column grid at the bottom. The import actions live here because this engine has a single
 * save slot: importing replaces the slot and opens it, exactly like Java's "Play Selected World".
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

        Box(Modifier.fillMaxSize()) {
            PanoramaBackground()

            // ---- Title ---------------------------------------------------------------
            Text(
                "Seleccionar mundo",
                color = Color(0xFFF2F2F2),
                fontSize = gui.text(11f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                style = TextStyle(shadow = McTextShadow),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = gui.px(10f), top = gui.px(10f))
            )

            // ---- World list ----------------------------------------------------------
            val worlds = remember(refresh) { WorldList.scan() }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = gui.px(26f))
                    .width(gui.px(280f))
                    .height(gui.px(120f))
                    .background(Color(0x66000000))
                    .border(gui.px(1), Color(0xFF9A9A9A))
            ) {
                if (worlds.isEmpty()) {
                    Text(
                        "Sin partidas.\nCrea un mundo nuevo o importa uno de Java.",
                        color = Color(0xFFB8B8B8),
                        fontSize = gui.text(8f),
                        fontFamily = FontFamily.Monospace,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center).padding(gui.px(8f))
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize().padding(gui.px(3f))) {
                        items(worlds) { world ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .height(gui.px(28f))
                                    .background(if (world.selected) Color(0x33FFFFFF) else Color(0x22000000))
                                    .border(gui.px(1), Color(0x559A9A9A))
                                    .padding(horizontal = gui.px(4f))
                                    .pointerInput(world.name) { detectTapGestures { world.onSelect() } },
                                verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                            ) {
                                Text(
                                    world.name,
                                    color = Color(0xFFF2F2F2),
                                    fontSize = gui.text(8f),
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                                Text(
                                    world.subtitle,
                                    color = Color(0xFFA8A8A8),
                                    fontSize = gui.text(6.5f),
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1
                                )
                            }
                            Spacer(Modifier.height(gui.px(2f)))
                        }
                    }
                }
            }

            // ---- Bottom button grid (Java: two columns, 100 px each) ------------------
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = gui.px(10f)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row {
                    GuiButton(
                        gui, "Jugar mundo seleccionado",
                        width = gui.px(200f), enabled = Game.hasSave
                    ) { onPlay() }
                }
                Spacer(Modifier.height(gui.px(BUTTON_GAP)))
                Row {
                    GuiButton(gui, "Crear mundo nuevo", width = gui.px(98f)) {
                        Game.startNewWorld(System.currentTimeMillis())
                        onPlay()
                    }
                    Spacer(Modifier.width(gui.px(BUTTON_GAP)))
                    GuiButton(gui, "Importar de Java", width = gui.px(98f), enabled = !importing) {
                        showImport = true
                    }
                }
                Spacer(Modifier.height(gui.px(BUTTON_GAP)))
                Row {
                    GuiButton(gui, "Cancelar", width = gui.px(98f)) { onBack() }
                    Spacer(Modifier.width(gui.px(BUTTON_GAP)))
                    GuiButton(gui, "Ajustes...", width = gui.px(98f)) { onBack() }
                }
            }

            // ---- Import chooser (Java-style centred panel) ---------------------------
            if (showImport) {
                Box(
                    Modifier.fillMaxSize().background(Color(0x99000000))
                        .pointerInput(Unit) { detectTapGestures { showImport = false } }
                )
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .width(gui.px(210f))
                        .background(Color(0xFF2B2B2B))
                        .border(gui.px(2), Color(0xFF000000))
                        .padding(gui.px(8f)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Importar contenido de Java",
                        color = Color(0xFFF2F2F2),
                        fontSize = gui.text(8f),
                        fontFamily = FontFamily.Monospace,
                        style = TextStyle(shadow = McTextShadow)
                    )
                    Spacer(Modifier.height(gui.px(6f)))
                    GuiButton(gui, "Mundo (.zip)", width = gui.px(190f)) {
                        showImport = false
                        worldPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                    Spacer(Modifier.height(gui.px(BUTTON_GAP)))
                    GuiButton(gui, "Texturas (resource pack)", width = gui.px(190f)) {
                        showImport = false
                        texturePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                    Spacer(Modifier.height(gui.px(BUTTON_GAP)))
                    GuiButton(gui, "Shaders", width = gui.px(190f)) {
                        showImport = false
                        shaderPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                    Spacer(Modifier.height(gui.px(BUTTON_GAP)))
                    GuiButton(gui, "Mod (.jar)", width = gui.px(190f)) {
                        showImport = false
                        modPicker.launch(arrayOf("application/java-archive", "application/zip", "*/*"))
                    }
                    Spacer(Modifier.height(gui.px(BUTTON_GAP)))
                    GuiButton(gui, "Cualquier archivo (detectar)", width = gui.px(190f)) {
                        showImport = false
                        anyFile.launch(arrayOf("*/*"))
                    }
                    Spacer(Modifier.height(gui.px(BUTTON_GAP)))
                    GuiButton(gui, "Cancelar", width = gui.px(190f)) { showImport = false }
                }
            }

            // ---- Progress + result ---------------------------------------------------
            if (importing) {
                Text(
                    "Importando… el log se guarda en Descargas/VoxelCraft",
                    color = Color(0xFFF2F2F2),
                    fontSize = gui.text(7.5f),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.Center).padding(gui.px(4f))
                )
            }
            val shown = message
            if (shown != null && !importing) {
                Box(
                    Modifier.fillMaxSize().background(Color(0x99000000))
                        .pointerInput(Unit) { detectTapGestures { message = null } }
                )
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .width(gui.px(240f))
                        .background(Color(0xFF2B2B2B))
                        .border(gui.px(2), Color(0xFF000000))
                        .padding(gui.px(8f)),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        "Importación",
                        color = Color(0xFFFFE14A),
                        fontSize = gui.text(8f),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(gui.px(4f)))
                    Text(
                        shown,
                        color = Color(0xFFE8E8E8),
                        fontSize = gui.text(7f),
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(Modifier.height(gui.px(6f)))
                    GuiButton(gui, "Entendido", width = gui.px(120f)) { message = null }
                }
            }
        }
    }
}

/** One row of the world list. */
class WorldEntry(
    val name: String,
    val subtitle: String,
    val selected: Boolean,
    val onSelect: () -> Unit
)

object WorldList {

    /** The engine has a single slot, so the list shows it (or nothing) plus storage info. */
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
