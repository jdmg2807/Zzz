package com.arena.voxel.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Minecraft Java Edition "GUI scale".
 *
 * Java never lays the GUI out in real pixels: it renders everything on a virtual screen measured
 * in *GUI pixels*, which is the window size divided by an integer scale factor. In automatic
 * mode the factor is the biggest integer that still fits the classic 320x240 GUI:
 *
 *     scale = clamp(min(width / 320, height / 240), 1, 4)
 *
 * A button in Java is exactly 200x20 GUI pixels, the font is 8 px tall with a 9 px line height,
 * the hotbar is 182x22, etc. Because the whole GUI shrinks uniformly, nothing can ever fall out
 * of the viewport, which is exactly the bug the old menu had: it stacked nine buttons in `dp`
 * (≈ 450 dp of content) inside a phone landscape viewport of ~360 dp, so the last buttons
 * (Ajustes, Cerrar) ended up below the fold and the fixed 360 dp column overflowed narrow screens.
 *
 * [Gui.px] converts GUI pixels to dp: 1 GUI px = (1 / scale) dp.
 */
class Gui(val scale: Int, val width: Int, val height: Int) {

    /** GUI pixels -> dp, for sizes, padding and offsets. */
    fun px(guiPixels: Number): Dp = (guiPixels.toFloat() / scale).dp

    /** GUI pixels -> dp as a raw float (Canvas drawing). */
    fun raw(guiPixels: Float): Float = guiPixels / scale

    /** Horizontal centre of the virtual screen in GUI pixels. */
    val centerX: Float get() = width / 2f

    /** Font size: [guiPixels] tall text that ignores the system font scale (Java style). */
    @Composable
    fun text(guiPixels: Float): TextUnit {
        val density = LocalDensity.current
        return with(density) { (guiPixels / scale).dp.toSp() }
    }
}

object GuiScale {

    const val MIN_WIDTH = 320
    const val MIN_HEIGHT = 240
    const val MAX_SCALE = 4

    /** Automatic scale, exactly the Java formula. */
    fun auto(widthDp: Float, heightDp: Float): Int {
        val byWidth = widthDp / MIN_WIDTH
        val byHeight = heightDp / MIN_HEIGHT
        return floor(min(byWidth, byHeight)).toInt().coerceIn(1, MAX_SCALE)
    }

    /** [forced] 0 = automatic; 1..4 = user choice (Ajustes → escala de interfaz). */
    fun compute(widthDp: Float, heightDp: Float, forced: Int = 0): Gui {
        val scale = if (forced in 1..MAX_SCALE) forced else auto(widthDp, heightDp)
        val virtualWidth = max(1, (widthDp * scale).toInt())
        val virtualHeight = max(1, (heightDp * scale).toInt())
        return Gui(scale, virtualWidth, virtualHeight)
    }
}

/**
 * Runs [content] inside the virtual GUI space. Everything inside must be measured with [Gui.px]
 * so the layout behaves like Java's on any screen: phone portrait, phone landscape, tablet.
 */
@Composable
fun GuiScreen(forcedScale: Int = 0, content: @Composable BoxScope.(Gui) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthDp = maxWidth.value
        val heightDp = maxHeight.value
        val gui = remember(widthDp, heightDp, forcedScale) { GuiScale.compute(widthDp, heightDp, forcedScale) }
        content(gui)
    }
}
