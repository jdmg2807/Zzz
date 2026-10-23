package com.arena.voxel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arena.voxel.items.ItemStack

/**
 * Bedrock Edition visual language.
 *
 * Bedrock does not use Java's flat grey 200x20 rectangles: its buttons are stone-like plates with
 * a light top bevel and a dark bottom bevel, its panels are dark (almost black) translucent
 * surfaces with a thin light edge, and every label is white with a hard drop shadow. This file
 * holds those primitives so every screen (menu, pause, inventory, containers) shares them.
 *
 * Everything is measured in GUI pixels through [Gui], so the layout is identical on any screen.
 */
object Bedrock {

    /** Panel surface: dark, slightly translucent so the world shows through. */
    val panel = Color(0xF0121212)
    val panelLight = Color(0xF01E1E1E)
    val panelBorder = Color(0xFF4C4C4C)
    val panelBorderDark = Color(0xFF000000)

    /** Stone button plate. */
    val btnTop = Color(0xFFA0A0A0)
    val btnBottom = Color(0xFF6B6B6B)
    val btnPressedTop = Color(0xFF6F6F6F)
    val btnPressedBottom = Color(0xFF4A4A4A)
    val btnDisabled = Color(0xFF3A3A3A)
    val btnBorder = Color(0xFF1B1B1B)
    val btnEdgeLight = Color(0x66FFFFFF)
    val btnEdgeDark = Color(0x66000000)

    /** Slot well (inventory). */
    val slot = Color(0xFF2B2B2B)
    val slotBorder = Color(0xFF101010)
    val slotHighlight = Color(0xFFFFFFFF)

    /** Text. */
    val text = Color(0xFFFFFFFF)
    val textDim = Color(0xFFB9B9B9)
    val accent = Color(0xFF8BD44C)
    val gold = Color(0xFFFFE100)
    val xpBar = Color(0xFF80FF20)

    val shadow = Shadow(color = Color(0xFF000000), offset = Offset(1.5f, 2f), blurRadius = 0f)
}

/** White Bedrock label with the hard drop shadow. */
@Composable
fun BedrockText(
    gui: Gui,
    value: String,
    size: Float = 8f,
    color: Color = Bedrock.text,
    bold: Boolean = false,
    modifier: Modifier = Modifier
) {
    Text(
        text = value,
        color = color,
        fontSize = gui.text(size),
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontFamily = FontFamily.SansSerif,
        maxLines = 1,
        style = TextStyle(shadow = Bedrock.shadow),
        modifier = modifier
    )
}

/**
 * The Bedrock button: a stone plate with a 1 px dark outline, a light top bevel, a dark bottom
 * bevel and a white shadowed caption. Pressing it darkens the plate (no ripple).
 */
@Composable
fun BedrockButton(
    gui: Gui,
    label: String,
    modifier: Modifier = Modifier,
    width: Dp = gui.px(200f),
    height: Dp = gui.px(24f),
    enabled: Boolean = true,
    fontSize: Float = 8f,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val top = when {
        !enabled -> Bedrock.btnDisabled
        pressed -> Bedrock.btnPressedTop
        else -> Bedrock.btnTop
    }
    val bottom = when {
        !enabled -> Bedrock.btnDisabled
        pressed -> Bedrock.btnPressedBottom
        else -> Bedrock.btnBottom
    }
    Box(
        modifier
            .width(width)
            .height(height)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .border(gui.px(1), Bedrock.btnBorder)
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { if (enabled) onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        // Bevels: one light row on top, one dark row at the bottom.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(width)
                .height(gui.px(1))
                .background(if (enabled) Bedrock.btnEdgeLight else Color.Transparent)
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .width(width)
                .height(gui.px(1))
                .background(if (enabled) Bedrock.btnEdgeDark else Color.Transparent)
        )
        Text(
            text = label,
            color = if (enabled) Bedrock.text else Bedrock.textDim,
            fontSize = gui.text(fontSize),
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            style = TextStyle(shadow = Bedrock.shadow)
        )
    }
}

/** Dark Bedrock container: outer black edge, 1 px light border, dark surface. */
@Composable
fun BedrockPanel(
    gui: Gui,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier
            .background(Bedrock.panel)
            .border(gui.px(2), Bedrock.panelBorderDark)
            .border(gui.px(1), Bedrock.panelBorder),
        content = content
    )
}

/** Panel title bar: centred caption on a slightly lighter strip, like the Bedrock screens. */
@Composable
fun BedrockTitleBar(gui: Gui, title: String, onClose: (() -> Unit)? = null) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(gui.px(16f))
            .background(Bedrock.panelLight),
        contentAlignment = Alignment.Center
    ) {
        BedrockText(gui, title, size = 8f, bold = true)
        onClose?.let { close ->
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = gui.px(3f))
                    .size(gui.px(12f))
                    .background(Color(0xFF8E1B1B))
                    .border(gui.px(1), Color(0xFF000000))
                    .pointerInput(Unit) { detectTapGestures { close() } },
                contentAlignment = Alignment.Center
            ) {
                BedrockText(gui, "X", size = 8f, bold = true)
            }
        }
    }
}

/** One inventory slot: dark well, item icon and stack count. */
@Composable
fun BedrockSlot(
    gui: Gui,
    stack: ItemStack?,
    size: Dp = gui.px(20f),
    selected: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(size)
            .background(Bedrock.slot)
            .border(gui.px(if (selected) 2f else 1f), if (selected) Bedrock.slotHighlight else Bedrock.slotBorder)
            .pointerInput(stack?.id, stack?.count, selected) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        if (stack != null) {
            ItemIcon(stack.id, gui, size * 0.8f)
            if (stack.count > 1) {
                Text(
                    text = stack.count.toString(),
                    color = Color.White,
                    fontSize = gui.text(6.5f),
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(shadow = Bedrock.shadow),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = gui.px(1f))
                )
            }
        }
    }
}

/** Horizontal tab, Bedrock style: the selected one is lighter and taller. */
@Composable
fun BedrockTab(gui: Gui, label: String, selected: Boolean, width: Dp = gui.px(52f), onClick: () -> Unit) {
    Box(
        Modifier
            .width(width)
            .height(gui.px(if (selected) 15f else 13f))
            .background(if (selected) Bedrock.panelLight else Color(0xFF0A0A0A))
            .border(gui.px(1), if (selected) Bedrock.panelBorder else Bedrock.slotBorder)
            .pointerInput(selected) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        BedrockText(gui, label, size = 7f, color = if (selected) Bedrock.text else Bedrock.textDim)
    }
}

/** Bedrock toggle: label on the left, ON/OFF plate on the right. */
@Composable
fun BedrockToggle(gui: Gui, label: String, value: Boolean, width: Dp = gui.px(200f), onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .width(width)
            .height(gui.px(20f))
            .background(Color(0x66000000))
            .border(gui.px(1), Bedrock.slotBorder)
            .padding(horizontal = gui.px(4f)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BedrockText(gui, label, size = 7.5f)
        Spacer(Modifier.weight(1f))
        BedrockButton(
            gui,
            if (value) "SÍ" else "NO",
            width = gui.px(34f),
            height = gui.px(16f),
            fontSize = 7f
        ) { onChange(!value) }
    }
}

/** Small round action button used by the touch HUD (jump, sneak, fly...). */
@Composable
fun BedrockRoundButton(
    gui: Gui,
    glyph: String,
    diameter: Dp,
    active: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val base = when {
        !enabled -> Color(0x22FFFFFF)
        active -> Color(0x88FFFFFF)
        else -> Color(0x55000000)
    }
    Box(
        Modifier
            .size(diameter)
            .background(base, androidx.compose.foundation.shape.CircleShape)
            .border(gui.px(1.2f), if (enabled) Color(0x99FFFFFF) else Color(0x33FFFFFF), androidx.compose.foundation.shape.CircleShape)
            .pointerInput(enabled) { detectTapGestures { if (enabled) onClick() } },
        contentAlignment = Alignment.Center
    ) {
        Text(
            glyph,
            color = if (enabled) Color.White else Color(0x66FFFFFF),
            fontSize = gui.text(13f),
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.Bold,
            style = TextStyle(shadow = Bedrock.shadow)
        )
    }
}

/** Drop shadow strip behind HUD text, so white labels stay readable over snow or the sun. */
@Composable
fun HudShadowStrip(gui: Gui, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier.background(Color(0x66000000)),
        content = content
    )
}

/** Thin separator used between the main grid and the hotbar row of the inventory. */
@Composable
fun BedrockSeparator(gui: Gui, width: Dp) {
    Canvas(Modifier.width(width).height(gui.px(2f))) {
        drawRect(Color(0xFF000000), size = Size(size.width, size.height))
        drawRect(
            Color(0x33FFFFFF),
            topLeft = Offset(0f, 0f),
            size = Size(size.width, size.height * 0.5f)
        )
    }
}

/**
 * Bedrock-style slider: a dark well with a green fill and a stone handle.
 *
 * Bedrock has no Material slider: the track is 2 px high, the filled part is the accent green and
 * the handle is a small stone plate. [steps] > 0 snaps the value to that many intermediate steps
 * (0 = continuous), which is how Bedrock's "Render distance" widget behaves.
 */
@Composable
fun BedrockSlider(
    gui: Gui,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    width: Dp = gui.px(220f),
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    val span = (valueRange.endInclusive - valueRange.start).coerceAtLeast(0.0001f)
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)

    fun snap(raw: Float): Float {
        if (steps <= 0) return raw.coerceIn(valueRange.start, valueRange.endInclusive)
        val step = span / (steps + 1)
        val snapped = valueRange.start + Math.round((raw - valueRange.start) / step) * step
        return snapped.coerceIn(valueRange.start, valueRange.endInclusive)
    }

    Box(
        Modifier
            .width(width)
            .height(gui.px(18f))
            .pointerInput(valueRange, steps) {
                detectDragGestures(
                    onDragStart = { offset ->
                        val t = (offset.x / size.width).coerceIn(0f, 1f)
                        onChange(snap(valueRange.start + span * t))
                    }
                ) { change, _ ->
                    val t = (change.position.x / size.width).coerceIn(0f, 1f)
                    onChange(snap(valueRange.start + span * t))
                }
            }
            .pointerInput(valueRange, steps) {
                detectTapGestures { offset ->
                    val t = (offset.x / size.width).coerceIn(0f, 1f)
                    onChange(snap(valueRange.start + span * t))
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Canvas(Modifier.fillMaxWidth().height(gui.px(18f))) {
            val cy = size.height / 2f
            val h = gui.px(3f).toPx().coerceAtLeast(2f)
            drawRect(Color(0xFF101010), topLeft = Offset(0f, cy - h / 2f), size = Size(size.width, h))
            drawRect(
                Color(0xFF8BD44C),
                topLeft = Offset(0f, cy - h / 2f),
                size = Size(size.width * fraction, h)
            )
        }
        val handle = gui.px(10f)
        Box(
            Modifier
                .offset(x = width * fraction - handle / 2)
                .size(handle)
                .background(Brush.verticalGradient(listOf(Bedrock.btnTop, Bedrock.btnBottom)))
                .border(gui.px(1), Bedrock.btnBorder)
        )
    }
}
