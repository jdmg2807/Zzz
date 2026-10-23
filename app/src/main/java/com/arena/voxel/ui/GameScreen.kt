package com.arena.voxel.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.arena.voxel.Game
import com.arena.voxel.Settings
import com.arena.voxel.render.GameSurfaceView
import kotlinx.coroutines.delay

/**
 * In-game screen: the GL surface (which also owns the touch controls) plus the Bedrock HUD and
 * the Bedrock panels.
 *
 * Input split, so nothing steals anything:
 *
 *  - [GameSurfaceView] handles gameplay touch while `Game.uiBlocking` is false,
 *  - it returns `false` while a panel is open, which is what lets the Compose panels below be
 *    tapped (this is the piece the previous Java-style screen got wrong).
 */
@Composable
fun GameScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    var glView by remember { mutableStateOf<GameSurfaceView?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var chatting by remember { mutableStateOf(false) }
    var chatText by remember { mutableStateOf("") }
    var container by remember { mutableStateOf<ContainerMode?>(null) }
    var entity by remember { mutableStateOf<BlockEntityUi?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(120)
            tick++
        }
    }

    BackHandler {
        when {
            chatting -> chatting = false
            entity != null -> entity = null
            container != null -> container = null
            else -> paused = true
        }
    }

    // The renderer asks for a screen (tapping a crafting table, a chest...) through Game.openScreen.
    LaunchedEffect(tick) {
        when (Game.openScreen) {
            "crafting_table" -> {
                container = ContainerMode.CRAFTING_TABLE
                Game.openScreen = ""
            }
            "inventory" -> {
                container = if (Game.creative) ContainerMode.CREATIVE else ContainerMode.INVENTORY
                Game.openScreen = ""
            }
            "chest", "furnace", "anvil" -> {
                val pos = Game.openScreenPos
                if (pos != null) entity = BlockEntityUi(Game.openScreen, pos.first, pos.second, pos.third)
                Game.openScreen = ""
            }
        }
        // Requests coming from the touch controller (HUD buttons).
        when (Game.uiRequest) {
            "pause" -> {
                paused = true
                Game.uiRequest = ""
            }
            "inventory" -> {
                container = if (Game.creative) ContainerMode.CREATIVE else ContainerMode.INVENTORY
                Game.uiRequest = ""
            }
            "chat" -> {
                chatting = true
                Game.uiRequest = ""
            }
        }
    }

    LaunchedEffect(paused, container, entity, chatting) {
        val blocking = paused || container != null || entity != null || chatting
        Game.paused = blocking
        Game.uiBlocking = blocking
        if (blocking) {
            Game.move[0] = 0f
            Game.move[1] = 0f
            Game.pressActive = false
        }
    }

    val lifecycleOwner = context as? LifecycleOwner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> glView?.onPause()
                Lifecycle.Event.ON_RESUME -> glView?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner?.lifecycle?.addObserver(observer)
        onDispose {
            lifecycleOwner?.lifecycle?.removeObserver(observer)
            Game.uiBlocking = false
            Game.exitToMenu()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                GameSurfaceView(ctx).also { glView = it }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (Game.state == Game.State.LOADING) {
            LoadingOverlay(tick, onCancel = { Game.exitToMenu(); onExit() })
        } else {
            // Draw-only HUD: the view owns the gameplay touches.
            BedrockHud(tick)
        }

        container?.let { mode ->
            GuiScreen(Settings.guiScale) { gui ->
                BedrockInventoryScreen(
                    gui,
                    mode,
                    onClose = { container = null },
                    onOpenCrafting = { container = ContainerMode.CRAFTING_TABLE }
                )
            }
        }

        entity?.let { open ->
            GuiScreen(Settings.guiScale) { gui ->
                if (open.kind == "anvil") {
                    AnvilScreen(gui) { entity = null }
                } else {
                    BlockEntityScreen(gui, open.kind, open.pos) { entity = null }
                }
            }
        }

        if (chatting) {
            ChatPanel(
                text = chatText,
                onText = { chatText = it },
                onSend = {
                    val line = chatText.trim()
                    chatText = ""
                    chatting = false
                    if (line.isNotEmpty()) {
                        if (line.startsWith("/")) Game.runCommand(line) else Game.chat("<Jugador> $line")
                    }
                },
                onCancel = {
                    chatText = ""
                    chatting = false
                }
            )
        }

        // Death screen (Bedrock shows "You died!" over a red tint with a respawn plate).
        val pl = Game.player
        if (pl != null && pl.isDead() && !paused && container == null && entity == null) {
            GuiScreen(Settings.guiScale) { gui ->
                Box(Modifier.fillMaxSize().background(Color(0xCC5A0000))) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        BedrockText(gui, "¡HAS MUERTO!", size = 18f, bold = true)
                        Spacer(Modifier.height(gui.px(8f)))
                        BedrockButton(gui, "Reaparecer", width = gui.px(180f), height = gui.px(24f)) {
                            val w = Game.world
                            val spawn = w?.gen?.spawnPoint()
                            if (w != null && spawn != null) {
                                if (!Game.creative) com.arena.voxel.world.ItemEntities.dropInventory(pl)
                                pl.respawn(w, spawn.first, spawn.second, spawn.third)
                            } else {
                                pl.health = pl.maxHealth
                            }
                            Game.chat("Has reaparecido")
                        }
                        Spacer(Modifier.height(gui.px(6f)))
                        BedrockButton(gui, "Salir al menú", width = gui.px(180f), height = gui.px(20f)) {
                            Game.exitToMenu()
                            onExit()
                        }
                    }
                }
            }
        }

        if (paused) {
            PauseMenu(
                onResume = { paused = false },
                onExit = {
                    Game.exitToMenu()
                    onExit()
                }
            )
        }
    }
}

private data class BlockEntityUi(val kind: String, val x: Int, val y: Int, val z: Int) {
    val pos: Triple<Int, Int, Int> get() = Triple(x, y, z)
}

@Composable
private fun LoadingOverlay(tick: Int, onCancel: () -> Unit) {
    val progress = remember(tick) { Game.progress }
    val status = remember(tick) { Game.status }
    Box(
        Modifier.fillMaxSize().background(Color(0xF0101010)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.width(340.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("VOXELCRAFT", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Color(0xFFF4F4F4))
            Spacer(Modifier.height(6.dp))
            Text("Generando mundo", color = Color(0xFFB9B9B9), fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Color(0xFF8BD44C),
                trackColor = Color(0xFF2B2B2B)
            )
            Spacer(Modifier.height(10.dp))
            Text(status, color = Color(0xFF8A8A8A), fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            GuiScreen(Settings.guiScale) { gui ->
                BedrockButton(gui, "Cancelar", width = gui.px(120f), height = gui.px(20f)) { onCancel() }
            }
        }
    }
}

/** Bedrock chat: dark strip at the bottom with the input line and two plates. */
@Composable
private fun ChatPanel(text: String, onText: (String) -> Unit, onSend: () -> Unit, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x88000000))) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            BasicTextField(
                value = text,
                onValueChange = onText,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(
                    color = Color.White,
                    fontSize = 15.sp,
                    fontFamily = FontFamily.SansSerif
                ),
                cursorBrush = SolidColor(Color.White),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xE0121212))
                    .padding(10.dp)
            )
            Spacer(Modifier.height(8.dp))
            GuiScreen(Settings.guiScale) { gui ->
                Row {
                    BedrockButton(gui, "Enviar", width = gui.px(90f), height = gui.px(20f)) { onSend() }
                    Spacer(Modifier.width(gui.px(6f)))
                    BedrockButton(gui, "Cancelar", width = gui.px(90f), height = gui.px(20f)) { onCancel() }
                }
            }
        }
    }
}

/** Bedrock pause menu: dark plate, "Reanudar" first, then save and quit. */
@Composable
private fun PauseMenu(onResume: () -> Unit, onExit: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xC0000000)),
        contentAlignment = Alignment.Center
    ) {
        GuiScreen(Settings.guiScale) { gui ->
            Column(
                Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BedrockText(gui, "JUEGO EN PAUSA", size = 16f, bold = true)
                Spacer(Modifier.height(gui.px(10f)))
                BedrockButton(gui, "Continuar", width = gui.px(190f), height = gui.px(24f)) { onResume() }
                Spacer(Modifier.height(gui.px(5f)))
                BedrockButton(gui, "Guardar mundo", width = gui.px(190f), height = gui.px(22f)) {
                    Game.saveWorld()
                    onResume()
                }
                Spacer(Modifier.height(gui.px(5f)))
                BedrockButton(gui, "Salir al menú", width = gui.px(190f), height = gui.px(22f)) { onExit() }
            }
        }
    }
}
