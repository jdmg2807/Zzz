package com.arena.voxel.ui

import android.opengl.GLSurfaceView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.arena.voxel.Game
import com.arena.voxel.Settings
import com.arena.voxel.mods.ModLoader
import com.arena.voxel.render.Renderer
import com.arena.voxel.world.Blocks
import com.arena.voxel.textures.Textures
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun GameScreen(onExit: () -> Unit) {
    val context = LocalContext.current
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf(false) }
    var container by remember { mutableStateOf<ContainerMode?>(null) }
    var entity by remember { mutableStateOf<Triple<String, Triple<Int, Int, Int>>?>(null) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(120)
            tick++
        }
    }

    BackHandler {
        when {
            entity != null -> entity = null
            container != null -> container = null
            picker -> picker = false
            else -> paused = true
        }
    }

    // The renderer requests a screen (e.g. tapping a crafting table) through Game.openScreen.
    LaunchedEffect(tick) {
        when (Game.openScreen) {
            "crafting_table" -> {
                container = ContainerMode.CRAFTING_TABLE
                Game.openScreen = ""
            }
            "inventory" -> {
                container = ContainerMode.INVENTORY
                Game.openScreen = ""
            }
            "chest", "furnace", "anvil" -> {
                val pos = Game.openScreenPos
                if (pos != null) entity = Triple(Game.openScreen, pos)
                Game.openScreen = ""
            }
        }
    }

    LaunchedEffect(paused, picker, container, entity) {
        Game.paused = paused || picker || container != null || entity != null
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
            Game.exitToMenu()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    preserveEGLContextOnPause = true
                    setRenderer(Renderer())
                    renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                    setOnTouchListener { _, _ -> true }
                    glView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (Game.state == Game.State.LOADING) {
            LoadingOverlay(tick, onCancel = { Game.exitToMenu(); onExit() })
        } else {
            // Anything below this layer receives touches only if it is drawn after it.
            Box(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            Game.pressSeq++
                            Game.pressActive = true
                            var last = down.position
                            down.consume()
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (change.changedToUpIgnoreConsumed()) break
                                val delta = change.position - last
                                last = change.position
                                if (delta.x != 0f || delta.y != 0f) Game.addLook(delta.x, delta.y)
                                change.consume()
                            }
                            Game.pressActive = false
                        }
                    }
            )

            HudInfo(tick)
            Crosshair()
            GuiScreen(Settings.guiScale) { gui ->
                GameHud(gui, tick) {
                    // "E": inventory with the 2x2 crafting grid (creative has the tabs inside).
                    container = ContainerMode.INVENTORY
                }
            }
            Joystick(Modifier.align(Alignment.BottomStart).padding(start = 26.dp, bottom = 26.dp).size(150.dp))
            Controls(tick, onOpenPicker = { picker = true })

            Box(Modifier.align(Alignment.TopEnd).padding(12.dp)) {
                TapButton("II") { paused = true }
            }
        }

        if (picker) {
            BlockPicker(
                selectedSlot = Game.selectedSlot,
                onPick = { id ->
                    if (Game.selectedSlot in Game.hotbar.indices) {
                        Game.hotbar[Game.selectedSlot] = id
                    }
                    picker = false
                },
                onClose = { picker = false }
            )
        }

        container?.let { mode ->
            GuiScreen(Settings.guiScale) { gui ->
                ContainerScreen(gui, mode) { container = null }
            }
        }

        // Chest / furnace / anvil GUIs opened by tapping the block with an empty hand.
        entity?.let { (kind, pos) ->
            GuiScreen(Settings.guiScale) { gui ->
                if (kind == "anvil") {
                    AnvilScreen(gui) { entity = null }
                } else {
                    BlockEntityScreen(gui, kind, pos) { entity = null }
                }
            }
        }

        // Death screen (Java: "You died!" + respawn button).
        val pl = Game.player
        if (pl != null && pl.isDead()) {
            GuiScreen(Settings.guiScale) { gui ->
                Box(Modifier.fillMaxSize().background(Color(0xCC5A0000))) {
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "¡Has muerto!",
                            color = Color(0xFFFFFFFF),
                            fontSize = gui.text(16f),
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(gui.px(6f)))
                        Text(
                            "Puntuación: ${(pl.experience * 7).toInt()}",
                            color = Color(0xFFFFE0A0),
                            fontSize = gui.text(8f),
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(Modifier.height(gui.px(10f)))
                        GuiButton(gui, "Reaparecer") {
                            val w = Game.world
                            val spawn = w?.gen?.spawnPoint()
                            if (w != null && spawn != null) {
                                pl.respawn(w, spawn.first, spawn.second, spawn.third)
                            } else {
                                pl.health = pl.maxHealth
                            }
                            Game.chat("Has reaparecido")
                        }
                        Spacer(Modifier.height(gui.px(BUTTON_GAP)))
                        GuiButton(gui, "Salir al menú", width = gui.px(120f)) {
                            Game.exitToMenu()
                            onExit()
                        }
                    }
                }
            }
        }

        if (paused) {
            PauseOverlay(
                onResume = { paused = false },
                onExit = {
                    Game.exitToMenu()
                    onExit()
                }
            )
        }
    }
}

@Composable
private fun LoadingOverlay(tick: Int, onCancel: () -> Unit) {
    val progress = remember(tick) { Game.progress }
    val status = remember(tick) { Game.status }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF00C1016)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.width(360.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("VOXELCRAFT", fontSize = 30.sp, fontWeight = FontWeight.Black, color = Accent)
            Spacer(Modifier.height(6.dp))
            Text("Generando mundo", color = Color(0xFF9AA6B2), fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = Accent,
                trackColor = Color(0xFF20262F)
            )
            Spacer(Modifier.height(10.dp))
            Text(status, color = Color(0xFF7E8A9A), fontSize = 12.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onCancel) { Text("Volver al menú", color = Color(0xFF9AA6B2)) }
        }
    }
}

@Composable
private fun BoxScope.HudInfo(tick: Int) {
    val fps = remember(tick) { Game.fps }
    val chunks = remember(tick) { Game.chunksReady }
    val hit = remember(tick) { Game.targetHit }
    val player = Game.player
    val pos = remember(tick) {
        player?.let {
            "X ${it.x.roundToInt()}  Y ${it.y.roundToInt()}  Z ${it.z.roundToInt()}"
        } ?: ""
    }
    val blockName = remember(tick) {
        hit?.let { Blocks.get(Game.world?.get(it.x, it.y, it.z) ?: 0).id } ?: ""
    }
    val shaders = remember(tick) { Game.shaderPackName }
    val chat = remember(tick) { Game.chatSnapshot(3) }
    val hudText = remember(tick) { Game.hudText }

    Column(Modifier.padding(14.dp)) {
        Text("$fps FPS · $chunks chunks · ${ModLoader.count()} mods", color = Color(0xCCFFFFFF), fontSize = 11.sp)
        Text("$pos  ·  $shaders", color = Color(0xCCFFFFFF), fontSize = 11.sp)
        if (blockName.isNotEmpty()) {
            Text(blockName, color = Accent, fontSize = 11.sp)
        }
        if (hudText.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(hudText, color = Color(0xFF7CC242), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }

    Column(
        Modifier
            .align(Alignment.BottomStart)
            .padding(start = 16.dp, bottom = 186.dp)
    ) {
        chat.forEach { line ->
            Text(line, color = Color(0xCCFFFFFF), fontSize = 11.sp)
        }
    }
}

@Composable
private fun BoxScope.Crosshair() {
    Canvas(Modifier.align(Alignment.Center).size(22.dp)) {
        val c = center
        drawRect(Color(0xE6FFFFFF), topLeft = Offset(c.x - 1f, c.y - 8f), size = Size(2f, 16f))
        drawRect(Color(0xE6FFFFFF), topLeft = Offset(c.x - 8f, c.y - 1f), size = Size(16f, 2f))
    }
}

@Composable
private fun BoxScope.Hotbar(tick: Int) {
    val slots = remember(tick) { Game.hotbar.toList() }
    val selected = remember(tick) { Game.selectedSlot }
    if (!Settings.showHud) return
    Row(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 14.dp)
            .background(Color(0x55000000), RoundedCornerShape(10.dp))
            .padding(4.dp)
    ) {
        slots.forEachIndexed { index, id ->
            val icon = remember(id) { blockIcon(id, 64) }
            Box(
                Modifier
                    .padding(2.dp)
                    .size(46.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (index == selected) Color(0x66FFFFFF) else Color(0x40000000))
                    .border(
                        if (index == selected) 2.dp else 1.dp,
                        if (index == selected) Accent else Color(0x33FFFFFF),
                        RoundedCornerShape(7.dp)
                    )
                    .clickable { Game.selectedSlot = index },
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = id,
                        modifier = Modifier.size(36.dp),
                        filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                    )
                }
            }
        }
    }
}

@Composable
private fun Joystick(modifier: Modifier) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier.pointerInput(Unit) {
            val radius = minOf(size.width, size.height) * 0.34f
            detectDragGestures(
                onDragStart = { knob = Offset.Zero },
                onDragEnd = {
                    knob = Offset.Zero
                    Game.move[0] = 0f
                    Game.move[1] = 0f
                },
                onDragCancel = {
                    knob = Offset.Zero
                    Game.move[0] = 0f
                    Game.move[1] = 0f
                },
                onDrag = { change, drag ->
                    change.consume()
                    val next = knob + drag
                    val len = next.getDistance()
                    val clamped = if (len > radius && len > 0f) next * (radius / len) else next
                    knob = clamped
                    Game.move[0] = (clamped.x / radius).coerceIn(-1f, 1f)
                    Game.move[1] = (-clamped.y / radius).coerceIn(-1f, 1f)
                }
            )
        }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            drawCircle(Color(0x2EFFFFFF), radius = r, center = c)
            drawCircle(Color(0x66FFFFFF), radius = r, center = c, style = Stroke(width = 4f))
            drawCircle(Color(0x997CC242), radius = r * 0.34f, center = c + knob)
            drawCircle(Color(0xCCFFFFFF), radius = r * 0.34f, center = c + knob, style = Stroke(width = 3f))
        }
    }
}

@Composable
private fun BoxScope.Controls(tick: Int, onOpenPicker: () -> Unit) {
    val flying = remember(tick) { Game.player?.flying == true }
    Column(
        Modifier
            .align(Alignment.BottomEnd)
            .padding(end = 22.dp, bottom = 96.dp),
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TapButton("⛏") { Game.requestBreak() }
            TapButton("■") { Game.requestPlace() }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TapButton("▦", small = true, onClick = onOpenPicker)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HoldButton(
                "↥",
                onPress = { Game.jump = true },
                onRelease = { Game.jump = false }
            )
            TapButton(if (flying) "✈ ON" else "✈", small = true) {
                Game.player?.let { it.flying = !it.flying; it.vy = 0.0 }
            }
            HoldButton(
                "»",
                onPress = { Game.sprint = true },
                onRelease = { Game.sprint = false }
            )
        }
        if (flying) {
            HoldButton(
                "↧",
                onPress = { Game.down = true },
                onRelease = { Game.down = false }
            )
        }
    }
}

@Composable
private fun TapButton(label: String, small: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(if (small) 68.dp else 58.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x66000000))
            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(16.dp))
            .pointerInput(label) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = if (small) 13.sp else 22.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun HoldButton(label: String, onPress: () -> Unit, onRelease: () -> Unit) {
    Box(
        Modifier
            .size(58.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0x66000000))
            .border(1.dp, Color(0x55FFFFFF), RoundedCornerShape(16.dp))
            .pointerInput(label) {
                detectTapGestures(onPress = {
                    onPress()
                    tryAwaitRelease()
                    onRelease()
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun PauseOverlay(onResume: () -> Unit, onExit: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xD906080C))
            .clickable { }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent()
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.width(320.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("PAUSA", color = Color(0xFFF2F7EE), fontSize = 30.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(18.dp))
            MenuButton("Continuar", primary = true, modifier = Modifier.fillMaxWidth(), onClick = onResume)
            Spacer(Modifier.height(10.dp))
            MenuButton("Guardar mundo", modifier = Modifier.fillMaxWidth(), onClick = {
                Game.saveWorld()
                onResume()
            })
            Spacer(Modifier.height(10.dp))
            MenuButton("Salir al menú", modifier = Modifier.fillMaxWidth(), onClick = onExit)
        }
    }
}

@Composable
private fun BlockPicker(selectedSlot: Int, onPick: (String) -> Unit, onClose: () -> Unit) {
    val blocks = remember { Blocks.all().drop(1) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xE6080B10))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp, vertical = 18.dp)
                .pointerInput(Unit) { detectTapGestures { } }
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "BLOQUES",
                    color = Accent,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    "toca uno para ponerlo en la ranura ${selectedSlot + 1}",
                    color = Color(0xFF8A94A6),
                    fontSize = 11.sp
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("Cerrar", color = Accent) }
            }
            Spacer(Modifier.height(10.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(6),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(blocks.size) { index ->
                    val block = blocks[index]
                    val icon = remember(block.id) { blockIcon(block.id, 64) }
                    Column(
                        Modifier.clickable { onPick(block.id) },
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            Modifier
                                .size(52.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0x33000000))
                                .border(1.dp, Color(0x33FFFFFF), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            if (icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = block.id,
                                    modifier = Modifier.size(46.dp),
                                    filterQuality = androidx.compose.ui.graphics.FilterQuality.None
                                )
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Text(
                            block.id,
                            color = Color(0xFFCFD8E3),
                            fontSize = 9.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

/** Isometric icon for a block id, cached by the caller with remember(). */
private fun blockIcon(id: String, size: Int): androidx.compose.ui.graphics.ImageBitmap? {
    val block = Blocks.get(Blocks.index(id))
    if (block.id == "air") return null
    return try {
        Textures.blockIcon(block.tileTop, block.tileSide, size)?.asImageBitmap()
    } catch (t: Throwable) {
        null
    }
}
