package com.arena.voxel.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.voxel.BuildConfig
import com.arena.voxel.Settings
import kotlin.math.PI
import kotlin.math.sin

/**
 * Minecraft Java Edition main menu.
 *
 * Everything here is measured in **GUI pixels** (see [GuiScale]): a 200x20 button is literally
 * 200x20 GUI px, the same numbers the Java layout uses, so the result scales identically on any
 * screen and can never overflow the viewport. That is the actual fix for the old layout, which
 * stacked nine `dp`-sized buttons (≈450 dp) inside a ~360 dp landscape viewport and left
 * "Ajustes"/"Cerrar el juego" below the fold.
 */

val McTextShadow = Shadow(color = Color(0xFF3A3A3A), offset = Offset(1.5f, 2f), blurRadius = 0f)

/** Java's default button geometry. */
const val BUTTON_W = 200f
const val BUTTON_H = 20f
const val BUTTON_GAP = 4f

private val SPLASHES = listOf(
    "¡Con mods de Java!", "¡Texturas de Faithful!", "¡Antorchas!", "6 biomas",
    "¡Hecho en Kotlin!", "¡Cuevas nuevas!", "Pssst... ¡prueba el vuelo!",
    "100% sin código de Mojang", "¡Paridad 1.21 en progreso!", "¡Anvil importado!",
    "¡También importa tus packs!", "Prohibido mirar debajo del mundo"
)

/**
 * Panorama-like background: slowly rotating, layered blocky hills with a soft blur, exactly the
 * role the baked panorama cubemap plays in Java. Kept procedural so no Mojang art is bundled.
 */
@Composable
fun PanoramaBackground(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "panorama")
    val pan by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(120_000, easing = LinearEasing), RepeatMode.Restart),
        label = "pan"
    )
    val drift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(26_000, easing = LinearEasing), RepeatMode.Restart),
        label = "drift"
    )

    Canvas(modifier.fillMaxSize().blur(4.dp)) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.60f

        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF4E8BD6), Color(0xFF7FB2E8), Color(0xFFC3DDF3)),
                startY = 0f,
                endY = horizon
            ),
            size = Size(w, horizon)
        )

        // Sun and its halo.
        drawCircle(Color(0xFFFFF3C4), radius = h * 0.06f, center = Offset(w * 0.72f, h * 0.18f))
        drawCircle(Color(0x40FFF6D0), radius = h * 0.11f, center = Offset(w * 0.72f, h * 0.18f))

        // Clouds: flat bands drifting independently (Java's panorama clouds move as you rotate).
        for (i in 0 until 7) {
            val baseY = horizon * (0.10f + 0.075f * i)
            val cloudW = w * (0.14f + 0.035f * (i % 3))
            val speed = 0.25f + 0.12f * (i % 4)
            val x = ((drift * speed + i * 0.19f) % 1.4f - 0.2f) * w
            drawRect(
                color = Color(0xF2FFFFFF),
                topLeft = Offset(x, baseY),
                size = Size(cloudW, h * 0.018f)
            )
        }

        // Three parallax ranges of blocky hills (the panorama "rotates" as they slide).
        hills(horizon, w, h, 0.62f, pan * 0.35f, Color(0xFF6C93B8), 0.30f, 46)
        hills(horizon + h * 0.03f, w, h, 0.78f, pan * 0.65f + 0.31f, Color(0xFF567C6A), 0.24f, 34)
        hills(horizon + h * 0.07f, w, h, 0.92f, pan * 1.00f + 0.67f, Color(0xFF3F5F46), 0.18f, 26)

        // Ground.
        drawRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF6D9E4A), Color(0xFF37572B))),
            topLeft = Offset(0f, horizon + h * 0.10f),
            size = Size(w, h - (horizon + h * 0.10f))
        )

        // Darkening scrim so the title and buttons stay readable (Java uses a blurred panorama).
        drawRect(Color(0x66000000), size = Size(w, h))
    }
}

/** One blocky hill range. [phase] shifts the wave; [steps] is the horizontal block resolution. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.hills(
    baseY: Float,
    width: Float,
    height: Float,
    amplitude: Float,
    phase: Float,
    color: Color,
    alpha: Float,
    steps: Int
) {
    val step = width / steps
    val points = FloatArray(steps + 1)
    for (i in 0..steps) {
        val t = i.toFloat() / steps
        val wave = sin(2f * PI.toFloat() * (t * 2f + phase)) * 0.5f +
            sin(2f * PI.toFloat() * (t * 5f - phase * 1.7f)) * 0.25f +
            sin(2f * PI.toFloat() * (t * 11f + phase * 0.6f)) * 0.12f
        points[i] = (wave + 0.5f).coerceIn(0f, 1f) * height * amplitude + step * 0.6f
    }
    var x = 0f
    for (i in 0 until steps) {
        drawRect(
            color = color.copy(alpha = alpha),
            topLeft = Offset(x, baseY - points[i]),
            size = Size(step + 1f, points[i] + height)
        )
        x += step
    }
}

/**
 * Stone plate button, Bedrock style (the signature every screen shares: menu, pause, containers).
 */
@Composable
fun GuiButton(
    gui: Gui,
    text: String,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = gui.px(BUTTON_W),
    height: androidx.compose.ui.unit.Dp = gui.px(24f),
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    BedrockButton(
        gui = gui,
        label = text,
        modifier = modifier,
        width = width,
        height = height,
        enabled = enabled,
        fontSize = 8f,
        onClick = onClick
    )
}

/**
 * Bedrock Edition main menu.
 *
 * Bedrock's phone menu is not Java's flat grey column: the logo is a chunky 3D wordmark at the
 * top, the splash arrow is rotated next to it, and the buttons are stone plates with a light top
 * bevel over a blurred world panorama. The world list lives in the "Jugar" screen, exactly like
 * Bedrock's Play screen.
 */
@Composable
fun MainMenu(
    onSingleplayer: () -> Unit,
    onSettings: () -> Unit,
    onQuit: () -> Unit,
    onPlay: () -> Unit = {},
    onMods: () -> Unit = {},
    onPacks: () -> Unit = {},
    forcedScale: Int = Settings.guiScale
) {
    GuiScreen(forcedScale) { gui ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PanoramaBackground()

            val splash = remember { SPLASHES.random() }
            val pulse = rememberInfiniteTransition(label = "splash")
            val splashScale by pulse.animateFloat(
                initialValue = 0.94f,
                targetValue = 1.04f,
                animationSpec = infiniteRepeatable(tween(560, easing = LinearEasing), RepeatMode.Reverse),
                label = "splashPulse"
            )

            // ---- Wordmark: dark extrusion behind a light face (Bedrock's 3D logo) ----------
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = gui.px(gui.height * 0.10f)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box {
                    Text(
                        "VOXELCRAFT",
                        fontSize = gui.text(34f),
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        color = Color(0xFF2B2B2B),
                        maxLines = 1,
                        modifier = Modifier.offset(x = gui.px(1.6f), y = gui.px(2.2f))
                    )
                    Text(
                        "VOXELCRAFT",
                        fontSize = gui.text(34f),
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        color = Color(0xFF9E9E9E),
                        maxLines = 1,
                        modifier = Modifier.offset(x = gui.px(0.7f), y = gui.px(1f))
                    )
                    Text(
                        "VOXELCRAFT",
                        fontSize = gui.text(34f),
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.SansSerif,
                        color = Color(0xFFF4F4F4),
                        maxLines = 1
                    )
                }
                Box(
                    Modifier
                        .offset(x = gui.px(58f), y = gui.px(-2f))
                        .graphicsLayer {
                            rotationZ = -14f
                            scaleX = splashScale
                            scaleY = splashScale
                        }
                ) {
                    Text(
                        splash,
                        fontSize = gui.text(8f),
                        fontFamily = FontFamily.SansSerif,
                        fontWeight = FontWeight.Bold,
                        color = Bedrock.gold,
                        maxLines = 1,
                        style = TextStyle(shadow = Shadow(Color(0xFF3F3F00), Offset(2f, 2f), 0f))
                    )
                }
                Spacer(Modifier.height(gui.px(6f)))
                BedrockText(gui, "Edición Bedrock · Mecánica Java", size = 7.5f, color = Color(0xFFD8D8D8))
            }

            // ---- Button column, centred like the Bedrock menu -----------------------------
            Column(
                Modifier
                    .align(Alignment.Center)
                    .padding(top = gui.px(gui.height * 0.14f)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BedrockButton(gui, "Jugar", width = gui.px(210f), height = gui.px(28f), fontSize = 10f) {
                    onSingleplayer()
                }
                Spacer(Modifier.height(gui.px(6f)))
                BedrockButton(gui, "Ajustes", width = gui.px(210f), height = gui.px(22f)) { onSettings() }
                Spacer(Modifier.height(gui.px(5f)))
                Row {
                    BedrockButton(gui, "Mods", width = gui.px(102f), height = gui.px(22f)) { onMods() }
                    Spacer(Modifier.width(gui.px(6f)))
                    BedrockButton(gui, "Paquetes", width = gui.px(102f), height = gui.px(22f)) { onPacks() }
                }
                Spacer(Modifier.height(gui.px(5f)))
                BedrockButton(gui, "Salir del juego", width = gui.px(210f), height = gui.px(20f), fontSize = 7.5f) {
                    onQuit()
                }
            }

            // ---- Corner labels ------------------------------------------------------------
            Text(
                "VoxelCraft ${BuildConfig.VERSION_NAME}  ·  Java 1.21",
                color = Color(0xFFE8E8E8),
                fontSize = gui.text(7f),
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                style = TextStyle(shadow = McTextShadow),
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(gui.px(4f))
            )
            Text(
                "No es Minecraft oficial · sin assets de Mojang",
                color = Color(0xFFE8E8E8),
                fontSize = gui.text(7f),
                fontFamily = FontFamily.SansSerif,
                maxLines = 1,
                textAlign = TextAlign.End,
                style = TextStyle(shadow = McTextShadow),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(gui.px(4f))
            )
        }
    }
}
