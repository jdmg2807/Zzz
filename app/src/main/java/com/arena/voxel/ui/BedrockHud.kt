package com.arena.voxel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.arena.voxel.Game
import com.arena.voxel.Settings

/** dp -> sp, ignoring the system font scale (the HUD must not grow with it). */
@Composable
private fun hudFont(dp: Float): TextUnit =
    with(LocalDensity.current) { dp.dp.toSp() }

/**
 * Bedrock Edition touch HUD.
 *
 * Every position comes from [BedrockMetrics], the very same object
 * [com.arena.voxel.render.GameSurfaceView] uses for hit-testing, so the widget you see is exactly
 * the area you can touch.
 *
 * This composable is **draw-only** on purpose: the view owns gameplay touches (that is what fixes
 * the dead joystick), and interactive Compose widgets here would steal events from it.
 */
@Composable
fun BedrockHud(tick: Int) {
    if (!Settings.showHud) return
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val m = remember(maxWidth, maxHeight) { BedrockMetrics(maxWidth.value, maxHeight.value) }
        val hotbar = remember(tick) { (0 until 9).map { Game.inventory.get(it) } }
        val selected = remember(tick) { Game.inventory.selected }
        val player = Game.player
        val health = player?.health ?: 20f
        val food = player?.food ?: 20
        val air = player?.air ?: 300
        val flying = player?.flying == true

        // ------------------------------------------------------------------- status bars
        if (!Game.creative) {
            Row(
                Modifier
                    .offset(x = m.hotbarLeft.dp, y = (m.hotbarTop - m.slot * 0.30f).dp)
                    .size(width = m.hotbarW.dp, height = (m.slot * 0.32f).dp)
            ) {
                for (i in 0 until 10) {
                    val value = health - i * 2f
                    HeartIcon(m.slot * 0.30f, full = value >= 2f, half = value in 0.5f..1.99f)
                }
                Box(Modifier.weight(1f))
                for (i in 0 until 10) {
                    val value = food - i * 2
                    HungerIcon(m.slot * 0.30f, full = value >= 2, half = value == 1)
                }
            }
            if (air < 300) {
                Row(
                    Modifier.offset(x = m.hotbarLeft.dp, y = (m.hotbarTop - m.slot * 0.72f).dp)
                ) {
                    for (i in 0 until 10) {
                        BubbleIcon(m.slot * 0.24f, full = air > 300 - (i + 1) * 30)
                    }
                }
            }
        }

        // XP bar, right on top of the hotbar.
        if (!Game.creative) {
            val level = player?.level ?: 0
            val exp = player?.experience ?: 0f
            Canvas(
                Modifier
                    .offset(x = m.hotbarLeft.dp, y = (m.hotbarTop - m.slot * 0.10f).dp)
                    .size(width = m.hotbarW.dp, height = (m.slot * 0.08f).dp)
            ) {
                drawRect(Color(0xFF000000), size = size)
                val frac = (exp / levelCost(level)).coerceIn(0f, 1f)
                drawRect(Color(0xFF2E2E2E), topLeft = Offset(1f, 1f), size = Size(size.width - 2f, size.height - 2f))
                drawRect(Bedrock.xpBar, topLeft = Offset(1f, 1f), size = Size((size.width - 2f) * frac, size.height - 2f))
            }
            if (level > 0) {
                Box(
                    Modifier
                        .offset(x = m.hotbarLeft.dp, y = (m.hotbarTop - m.slot * 0.52f).dp)
                        .size(width = m.hotbarW.dp, height = (m.slot * 0.34f).dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        level.toString(),
                        color = Color(0xFF80FF20),
                        fontSize = hudFont(m.slot * 0.30f),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        style = TextStyle(shadow = Bedrock.shadow)
                    )
                }
            }
        }

        // Item/status line above the hotbar.
        val hudText = remember(tick) { Game.hudText }
        if (hudText.isNotEmpty()) {
            Box(
                Modifier
                    .offset(x = m.hotbarLeft.dp, y = (m.hotbarTop - m.slot * 0.92f).dp)
                    .size(width = m.hotbarW.dp, height = (m.slot * 0.5f).dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    hudText,
                    color = Color(0xFFFFD54F),
                    fontSize = hudFont(m.slot * 0.26f),
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(shadow = Bedrock.shadow)
                )
            }
        }

        // ----------------------------------------------------------------------- hotbar
        Row(Modifier.offset(x = m.hotbarLeft.dp, y = m.hotbarTop.dp)) {
            for (i in 0 until 9) {
                val stack = hotbar.getOrNull(i)
                val isSel = i == selected
                Box(
                    Modifier
                        .padding(m.slot.dp * 0.015f)
                        .size(m.slot.dp * 0.97f)
                        .background(if (isSel) Color(0x40FFFFFF) else Color(0x4D000000))
                        .border(
                            (if (isSel) m.slot * 0.08f else m.slot * 0.035f).dp,
                            if (isSel) Color(0xFFFFFFFF) else Color(0x66000000)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (stack != null) {
                        val icon = remember(stack.id) { ItemIcons.icon(stack.id) }
                        if (icon != null) {
                            Image(
                                bitmap = icon,
                                contentDescription = stack.id,
                                modifier = Modifier.size((m.slot * 0.66f).dp),
                                filterQuality = FilterQuality.None
                            )
                        }
                        if (stack.count > 1) {
                            Text(
                                stack.count.toString(),
                                color = Color.White,
                                fontSize = hudFont(m.slot * 0.24f),
                                fontFamily = FontFamily.SansSerif,
                                fontWeight = FontWeight.Bold,
                                style = TextStyle(shadow = Bedrock.shadow),
                                modifier = Modifier.align(Alignment.BottomEnd)
                            )
                        }
                    }
                }
            }
        }

        // "..." inventory button at the right end of the hotbar.
        Box(
            Modifier
                .offset(x = m.invLeft.dp, y = m.invTop.dp)
                .size(m.invSize.dp)
                .background(Color(0x66000000))
                .border((m.slot * 0.035f).dp, Color(0x66000000)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "···",
                color = Color.White,
                fontSize = hudFont(m.invSize * 0.34f),
                fontFamily = FontFamily.SansSerif,
                fontWeight = FontWeight.Bold,
                style = TextStyle(shadow = Bedrock.shadow)
            )
        }

        // ---------------------------------------------------------------- action buttons
        ActionDisc(m.jumpCx, m.jumpCy, m.action * 0.54f, "▲", Game.jump)
        ActionDisc(m.sneakCx, m.sneakCy, m.action * 0.44f, "▼", Game.sneak || Game.flyDown)
        ActionDisc(m.flyToggleCx, m.flyToggleCy, m.action * 0.38f, "✈", flying)
        if (flying) {
            ActionDisc(m.flyUpCx, m.flyUpCy, m.action * 0.44f, "⤒", Game.flyUp)
            ActionDisc(m.flyDownCx, m.flyDownCy, m.action * 0.44f, "⤓", Game.flyDown)
        }

        // ------------------------------------------------------------------- top buttons
        ActionDisc(m.chatCx, m.chatCy, m.small * 0.58f, "T")
        ActionDisc(m.pauseCx, m.pauseCy, m.small * 0.58f, "II")

        // ---------------------------------------------------------------------- joystick
        val joyX = if (Game.joyActive) m.w * Game.joyAnchorX else m.joyCx
        val joyY = if (Game.joyActive) m.h * Game.joyAnchorY else m.joyCy
        Canvas(
            Modifier
                .offset(x = (joyX - m.joyRadius).dp, y = (joyY - m.joyRadius).dp)
                .size((m.joyRadius * 2f).dp)
        ) {
            val r = size.minDimension / 2f
            val c = Offset(size.width / 2f, size.height / 2f)
            drawCircle(Color(0x33000000), radius = r, center = c)
            drawCircle(Color(0x55FFFFFF), radius = r, center = c, style = Stroke(width = r * 0.06f))
            val kx = (if (Game.joyActive) Game.joyKnobX else 0f) * (m.knobRadius / m.joyRadius) * r
            val ky = (if (Game.joyActive) Game.joyKnobY else 0f) * (m.knobRadius / m.joyRadius) * r
            val kr = m.knobRadius / m.joyRadius * r
            drawCircle(Color(0x77000000), radius = kr, center = Offset(c.x + kx, c.y + ky))
            drawCircle(Color(0xAAFFFFFF), radius = kr, center = Offset(c.x + kx, c.y + ky), style = Stroke(width = r * 0.05f))
        }

        Crosshair(m)

        // --------------------------------------------------------------------- chat lines
        val chat = remember(tick) { Game.chatSnapshot(4) }
        if (chat.isNotEmpty()) {
            Column(
                Modifier
                    .offset(x = 6.dp, y = (m.hotbarTop - m.slot * 2.4f).dp)
                    .size(width = (m.slot * 6.2f).dp, height = (m.slot * 1.8f).dp)
            ) {
                chat.forEach { line ->
                    Text(
                        line,
                        color = Color(0xFFFFFFFF),
                        fontSize = hudFont(m.slot * 0.22f),
                        fontFamily = FontFamily.SansSerif,
                        style = TextStyle(shadow = Bedrock.shadow)
                    )
                }
            }
        }
    }
}

/** Heart sprite (Bedrock draws ten of them above the hotbar). */
@Composable
private fun HeartIcon(scale: Float, full: Boolean, half: Boolean) {
    Canvas(Modifier.size((scale * 1.3f).dp).padding(1.dp)) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF1A0505), size = Size(w, h))
        drawRect(Color(0xFF5A1010), topLeft = Offset(w * 0.1f, h * 0.12f), size = Size(w * 0.8f, h * 0.76f))
        if (full || half) {
            val fillW = if (half) w * 0.34f else w * 0.66f
            drawRect(Color(0xFFE02A2A), topLeft = Offset(w * 0.16f, h * 0.18f), size = Size(fillW, h * 0.64f))
            drawRect(Color(0xFFFF7A7A), topLeft = Offset(w * 0.2f, h * 0.22f), size = Size(fillW * 0.45f, h * 0.18f))
        }
    }
}

/** Drumstick sprite, mirrored on the right of the hotbar. */
@Composable
private fun HungerIcon(scale: Float, full: Boolean, half: Boolean) {
    Canvas(Modifier.size((scale * 1.3f).dp).padding(1.dp)) {
        val w = size.width
        val h = size.height
        drawRect(Color(0xFF1A1206), size = Size(w, h))
        if (full || half) {
            val meatW = if (half) w * 0.3f else w * 0.52f
            drawRect(Color(0xFFB96A2A), topLeft = Offset(w * 0.16f, h * 0.22f), size = Size(meatW, h * 0.54f))
            drawRect(Color(0xFFF2E8D8), topLeft = Offset(w * 0.7f, h * 0.34f), size = Size(w * 0.16f, h * 0.3f))
        }
    }
}

/** Air bubble (only shown while the head is underwater). */
@Composable
private fun BubbleIcon(scale: Float, full: Boolean) {
    Canvas(Modifier.size((scale * 1.3f).dp).padding(1.dp)) {
        val w = size.width
        val h = size.height
        if (full) {
            drawCircle(Color(0xFF9FD8FF), radius = w * 0.42f, center = Offset(w * 0.5f, h * 0.5f))
            drawCircle(Color(0xFF2E6FA8), radius = w * 0.42f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(width = w * 0.1f))
        } else {
            drawCircle(Color(0x33FFFFFF), radius = w * 0.42f, center = Offset(w * 0.5f, h * 0.5f), style = Stroke(width = w * 0.1f))
        }
    }
}

@Composable
private fun ActionDisc(cx: Float, cy: Float, radius: Float, glyph: String, active: Boolean = false) {
    Canvas(
        Modifier
            .offset(x = (cx - radius).dp, y = (cy - radius).dp)
            .size((radius * 2f).dp)
    ) {
        val r = size.minDimension / 2f
        drawCircle(if (active) Color(0x88000000) else Color(0x4D000000), radius = r)
        drawCircle(Color(0xB3FFFFFF), radius = r, style = Stroke(width = r * 0.07f))
    }
    Box(
        Modifier
            .offset(x = (cx - radius).dp, y = (cy - radius).dp)
            .size((radius * 2f).dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            glyph,
            color = Color.White,
            fontSize = hudFont(radius * 0.85f),
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            style = TextStyle(shadow = Bedrock.shadow)
        )
    }
}

@Composable
private fun Crosshair(m: BedrockMetrics) {
    val s = m.slot * 0.40f
    Canvas(
        Modifier
            .offset(x = ((m.w - s) / 2f).dp, y = ((m.h - s) / 2f).dp)
            .size(s.dp)
    ) {
        val w = size.width
        val h = size.height
        val t = w * 0.13f
        drawRect(Color(0x88000000), topLeft = Offset(w * 0.43f - 1f, 0f), size = Size(t + 2f, h))
        drawRect(Color(0x88000000), topLeft = Offset(0f, h * 0.43f - 1f), size = Size(w, t + 2f))
        drawRect(Color(0xFFFFFFFF), topLeft = Offset(w * 0.43f, 0f), size = Size(t, h))
        drawRect(Color(0xFFFFFFFF), topLeft = Offset(0f, h * 0.43f), size = Size(w, t))
    }
}
