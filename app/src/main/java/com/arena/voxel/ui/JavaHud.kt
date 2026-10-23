package com.arena.voxel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arena.voxel.Game
import com.arena.voxel.Settings
import kotlin.math.roundToInt

/**
 * The Java HUD, measured in GUI pixels:
 *
 * ```
 * hotbar   182 x 22, 9 slots of 20 x 20, selection frame 24 x 24
 * hearts   10 x 9 px, 8 px apart, just above the hotbar on the left
 * hunger   10 x 9 px, mirrored on the right
 * XP bar   182 x 5 above the hotbar, level number in the middle
 * air      10 bubbles above the hearts while underwater
 * ```
 *
 * Every icon is drawn with rectangles/circles so the game ships no Mojang art.
 */
@Composable
fun JavaHud(gui: Gui, tick: Int, onOpenInventory: () -> Unit) {
    if (!Settings.showHud) return
    val player = Game.player
    val health = player?.health ?: 20f
    val food = player?.food ?: 20
    val air = player?.air ?: 300
    val level = player?.level ?: 0
    val experience = player?.experience ?: 0f
    // Force a recomposition whenever the UI clock ticks (the game state is not observable).
    var frame by remember { mutableIntStateOf(0) }
    frame = tick

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.align(Alignment.BottomCenter).offset(y = gui.px(-24f)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.width(gui.px(182f))) {
                if (!Game.creative) {
                    Row(Modifier.width(gui.px(90f))) {
                        for (i in 0 until 10) {
                            val value = health - i * 2f
                            Heart(gui, full = value >= 2f, half = value in 0.5f..1.99f)
                        }
                    }
                    Box(Modifier.weight(1f))
                    Row(Modifier.width(gui.px(90f))) {
                        // Hunger is mirrored: the first drumstick is the rightmost one.
                        for (i in 0 until 10) {
                            val value = food - i * 2
                            Hunger(gui, full = value >= 2, half = value == 1)
                        }
                    }
                }
            }
        }

        // Air bubbles: 10 bubbles, one per 30 air ticks, above the hearts.
        if (!Game.creative && air < 300) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = gui.px(-46f))
            ) {
                for (i in 0 until 10) {
                    Bubble(gui, full = air > 300 - (i + 1) * 30)
                }
            }
        }

        // XP bar 182x5 just above the hotbar.
        if (!Game.creative) {
            Canvas(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = gui.px(-27f))
                    .size(width = gui.px(182f), height = gui.px(5f))
            ) {
                drawRect(Color(0xFF000000), size = size)
                drawRect(Color(0xFF2A2A2A), topLeft = Offset(1f, 1f), size = Size(size.width - 2f, size.height - 2f))
                val needed = levelCost(level)
                val fraction = (experience / needed).coerceIn(0f, 1f)
                drawRect(
                    Color(0xFF7FE81C),
                    topLeft = Offset(1f, 1f),
                    size = Size((size.width - 2f) * fraction, size.height - 2f)
                )
            }
            if (level > 0) {
                Text(
                    level.toString(),
                    color = Color(0xFF7FE81C),
                    fontSize = gui.text(9f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = gui.px(-34f))
                )
            }
        }

        // ---------------- hotbar ------------------------------------------------------
        val slots = remember(tick) { (0 until 9).map { Game.inventory.get(it) } }
        val selected = remember(tick) { Game.inventory.selected }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .width(gui.px(182f))
                .height(gui.px(22f))
                .background(Color(0x88000000))
                .border(gui.px(1f), Color(0xFF8B8B8B))
                .pointerInput(tick) {
                    detectTapGestures { point ->
                        val slotW = size.width / 9f
                        val index = (point.x / slotW).toInt().coerceIn(0, 8)
                        Game.inventory.selected = index
                        Game.selectedSlot = index
                        Game.syncHotbar()
                    }
                }
        ) {
            Row(Modifier.fillMaxSize()) {
                for (i in 0 until 9) {
                    Box(
                        Modifier
                            .size(gui.px(20f))
                            .border(
                                gui.px(if (i == selected) 2f else 0f),
                                if (i == selected) Color(0xFFFFFFFF) else Color(0x00000000)
                            )
                            .background(if (i == selected) Color(0x33FFFFFF) else Color(0x00000000)),
                        contentAlignment = Alignment.Center
                    ) {
                        val stack = slots.getOrNull(i)
                        if (stack != null) {
                            ItemIcon(stack.id, gui, gui.px(16f))
                            if (stack.count > 1) {
                                Text(
                                    stack.count.toString(),
                                    color = Color.White,
                                    fontSize = gui.text(7f),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.align(Alignment.BottomEnd)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Inventory button, because a phone has no "E" key.
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = gui.px(-4f), y = gui.px(-30f))
                .size(gui.px(20f))
                .background(Color(0x99000000))
                .border(gui.px(1f), Color(0xFFAAAFB5))
                .pointerInput(Unit) { detectTapGestures { onOpenInventory() } },
            contentAlignment = Alignment.Center
        ) {
            Text("≡", color = Color(0xFFE8E8E8), fontSize = gui.text(10f), fontFamily = FontFamily.Monospace)
        }

        // Crosshair: a 9x9 plus, inverted against the world by drawing both white and dark bars.
        Canvas(
            Modifier
                .align(Alignment.Center)
                .size(gui.px(11f))
        ) {
            val w = size.width
            val h = size.height
            drawRect(Color(0xCC000000), topLeft = Offset(w * 0.42f, 0f), size = Size(w * 0.16f, h))
            drawRect(Color(0xCC000000), topLeft = Offset(0f, h * 0.42f), size = Size(w, h * 0.16f))
            drawRect(Color(0xFFFFFFFF), topLeft = Offset(w * 0.46f, h * 0.1f), size = Size(w * 0.08f, h * 0.8f))
            drawRect(Color(0xFFFFFFFF), topLeft = Offset(w * 0.1f, h * 0.46f), size = Size(w * 0.8f, h * 0.08f))
        }
    }
}

/** XP needed for the next level, following Java's curve. */
internal fun levelCost(level: Int): Float = when {
    level < 16 -> (7 + level * 2).toFloat()
    level < 31 -> (9 + level * 5).toFloat()
    else -> (9 + level * 9).toFloat()
}

@Composable
private fun Heart(gui: Gui, full: Boolean, half: Boolean) {
    Canvas(Modifier.size(gui.px(9f))) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF101010), size = size)
        // Container shape (dark heart), then the red fill.
        drawRect(Color(0xFF4A0E0E), topLeft = Offset(w * 0.1f, h * 0.2f), size = Size(w * 0.8f, h * 0.6f))
        if (full || half) {
            val fillW = if (half) w * 0.4f else w * 0.7f
            drawRect(Color(0xFFD42A2A), topLeft = Offset(w * 0.15f, h * 0.25f), size = Size(fillW, h * 0.5f))
            drawRect(Color(0xFF8A1010), topLeft = Offset(w * 0.2f, h * 0.7f), size = Size(w * 0.3f, h * 0.12f))
        }
    }
}

@Composable
private fun Hunger(gui: Gui, full: Boolean, half: Boolean) {
    Canvas(Modifier.size(gui.px(9f))) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF101010), size = size)
        if (full || half) {
            val meatW = if (half) w * 0.3f else w * 0.55f
            drawRect(Color(0xFFC77A3A), topLeft = Offset(w * 0.18f, h * 0.25f), size = Size(meatW, h * 0.5f))
            drawRect(Color(0xFFE8E0D0), topLeft = Offset(w * 0.72f, h * 0.36f), size = Size(w * 0.14f, h * 0.28f))
        }
    }
}

@Composable
private fun Bubble(gui: Gui, full: Boolean) {
    Canvas(Modifier.size(gui.px(9f))) {
        drawRect(Color(0x33000000), size = size)
        if (full) {
            drawCircle(Color(0xFF9FD8FF), radius = size.width * 0.34f, center = Offset(size.width * 0.5f, size.height * 0.5f))
            drawCircle(Color(0xFF1E5B8A), radius = size.width * 0.14f, center = Offset(size.width * 0.4f, size.height * 0.4f))
        }
    }
}

/** Debug overlay text used by the F3-style panel. */
internal fun debugValue(v: Float, digits: Int = 1): String {
    val scale = Math.pow(10.0, digits.toDouble()).toFloat()
    return ((v * scale).roundToInt() / scale).toString()
}

internal fun DrawScope.square(x: Float, y: Float, w: Float, color: Color) {
    drawRect(color, topLeft = Offset(x, y), size = Size(w, w))
}
