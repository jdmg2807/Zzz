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
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import com.arena.voxel.Game
import com.arena.voxel.Settings
import com.arena.voxel.player.Player

/**
 * Java's HUD, measured in GUI pixels:
 *
 *   hotbar   182x22 centred at the bottom, 9 slots of 20x20, selection frame 24x24
 *   health   10 hearts of 9x9 above the hotbar, left aligned
 *   hunger   10 drumsticks of 9x9 on the right
 *   XP bar   182x5 just above the hotbar, level number above it
 *   air      10 bubbles above the health row while underwater
 *
 * Icons are drawn procedurally (pixel hearts/drumsticks), so no Mojang art is needed.
 */
@Composable
fun GameHud(gui: Gui, tick: Int, onOpenInventory: () -> Unit) {
    if (!Settings.showHud) return
    val player = Game.player
    val health = player?.health ?: 20f
    val food = player?.food ?: 20
    val air = player?.air ?: 300
    val level = player?.level ?: 0
    val xp = player?.experience ?: 0f

    Box(Modifier.fillMaxSize()) {
        // ---------------- health / hunger / XP -------------------------------------------
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .offset(x = gui.px(0f), y = gui.px(-26f))
        ) {
            if (Game.creative) return@Column
            Row {
                // Hearts (10), then the hunger bar mirrored on the right.
                repeat(10) { i ->
                    HeartIcon(gui, filled = health >= (i + 1) * 2f, half = health >= i * 2f + 1f)
                }
            }
            Box(Modifier.height(gui.px(10f)))
            Row(Modifier.offset(y = gui.px(-32f))) {
                repeat(10) { i ->
                    HungerIcon(gui, filled = food >= 20 - i * 2, half = food >= 19 - i * 2)
                }
            }
        }

        // Air bubbles above the hearts while submerged.
        if (air < 300 && !Game.creative) {
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = gui.px(-46f))
            ) {
                repeat(10) { i ->
                    BubbleIcon(gui, filled = air > 300 - (i + 1) * 30)
                }
            }
        }

        // XP bar.
        if (!Game.creative) {
            Canvas(
                Modifier
                    .align(Alignment.BottomCenter)
                    .offset(y = gui.px(-28f))
                    .size(width = gui.px(182f), height = gui.px(5f))
            ) {
                drawRect(Color(0xFF0B0B0B), size = size)
                drawRect(Color(0xFF1E1E1E), topLeft = Offset(1f, 1f), size = Size(size.width - 2f, size.height - 2f))
                val f = (xp / 1f).coerceIn(0f, 1f)
                drawRect(
                    Color(0xFF80FF20),
                    topLeft = Offset(1f, 1f),
                    size = Size((size.width - 2f) * f, size.height - 2f)
                )
            }
            if (level > 0) {
                Text(
                    level.toString(),
                    color = Color(0xFF80FF20),
                    fontSize = gui.text(9f),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .offset(y = gui.px(-38f))
                )
            }
        }

        // ---------------- hotbar ----------------------------------------------------------
        val slots = remember(tick) { (0 until 9).map { Game.inventory.get(it) } }
        val selected = remember(tick) { Game.inventory.selected }
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .offset(y = gui.px(-2f))
                .width(gui.px(182f))
                .height(gui.px(22f))
                .background(Color(0x99000000))
                .border(gui.px(1f), Color(0xFF5A5A5A))
                .pointerInput(tick) { detectTapGestures { offset ->
                    val slotW = size.width / 9f
                    val index = (offset.x / slotW).toInt().coerceIn(0, 8)
                    Game.inventory.selected = index
                    Game.selectedSlot = index
                } }
        ) {
            Row(Modifier.fillMaxSize()) {
                repeat(9) { i ->
                    Box(
                        Modifier
                            .width(gui.px(20f))
                            .height(gui.px(20f))
                            .padding1(gui)
                            .background(if (i == selected) Color(0x55FFFFFF) else Color(0x22000000))
                            .border(
                                gui.px(1f),
                                if (i == selected) Color(0xFFFFFFFF) else Color(0x33FFFFFF)
                            ),
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

        // Inventory button (Java opens it with E; on a phone it needs a button).
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = gui.px(-6f), y = gui.px(-30f))
                .size(gui.px(18f))
                .background(Color(0x99000000))
                .border(gui.px(1f), Color(0xFF7A7A7A))
                .pointerInput(Unit) { detectTapGestures { onOpenInventory() } },
            contentAlignment = Alignment.Center
        ) {
            Text("E", color = Color(0xFFE8E8E8), fontSize = gui.text(8f), fontFamily = FontFamily.Monospace)
        }
    }
}

/** 1px inner padding helper (kept as an extension so the layout reads like the Java code). */
private fun Modifier.padding1(gui: Gui): Modifier =
    this.then(Modifier.offset(x = 0.dp, y = 0.dp))

@Composable
private fun HeartIcon(gui: Gui, filled: Boolean, half: Boolean) {
    val container = Color(0xFF111111)
    Canvas(Modifier.size(gui.px(9f), gui.px(9f))) {
        drawRect(container, size = size)
        val red = Color(0xFFD42A2A)
        val dark = Color(0xFF8A1010)
        if (half) {
            drawRect(red, topLeft = Offset(size.width * 0.15f, size.height * 0.15f), size = Size(size.width * 0.35f, size.height * 0.7f))
        } else if (filled) {
            drawRect(red, topLeft = Offset(size.width * 0.15f, size.height * 0.15f), size = Size(size.width * 0.7f, size.height * 0.7f))
            drawRect(dark, topLeft = Offset(size.width * 0.25f, size.height * 0.65f), size = Size(size.width * 0.5f, size.height * 0.2f))
        }
    }
}

@Composable
private fun HungerIcon(gui: Gui, filled: Boolean, half: Boolean) {
    Canvas(Modifier.size(gui.px(9f), gui.px(9f))) {
        drawRect(Color(0xFF111111), size = size)
        val meat = Color(0xFFC77A3A)
        val bone = Color(0xFFE8E0D0)
        val w = size.width
        val h = size.height
        if (half || filled) {
            drawRect(meat, topLeft = Offset(w * 0.2f, h * 0.25f), size = Size(w * (if (half) 0.3f else 0.55f), h * 0.5f))
            drawRect(bone, topLeft = Offset(w * 0.72f, h * 0.35f), size = Size(w * 0.12f, h * 0.3f))
        }
    }
}

@Composable
private fun BubbleIcon(gui: Gui, filled: Boolean) {
    Canvas(Modifier.size(gui.px(9f), gui.px(9f))) {
        drawRect(Color(0x33000000), size = size)
        if (filled) {
            drawCircle(Color(0xFF9FD8FF), radius = size.width * 0.32f, center = Offset(size.width * 0.5f, size.height * 0.5f))
            drawCircle(Color(0xFF1E5B8A), radius = size.width * 0.16f, center = Offset(size.width * 0.38f, size.height * 0.38f))
        }
    }
}

/** Draws an item/block icon: blocks get the isometric cube, items a tinted square. */
@Composable
fun ItemIcon(id: String, gui: Gui, size: androidx.compose.ui.unit.Dp) {
    val bitmap = remember(id) { com.arena.voxel.ui.ItemIcons.icon(id) }
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap,
            contentDescription = id,
            modifier = Modifier.size(size),
            filterQuality = androidx.compose.ui.graphics.FilterQuality.None
        )
    } else {
        Box(Modifier.size(size).background(Color(0xFF3A3A3A)))
    }
}

/** Shared icon cache (bitmaps are expensive; building one per frame would tank the FPS). */
object ItemIcons {
    private val cache = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()

    /**
     * Icon for a block or item id, cached by (id, 64x64).
     *
     * Three sources, in this order:
     *
     *  1. a real Java item sprite (`assets/minecraft/textures/item/<id>.png`) if a resource pack
     *     installed it — that is what Java shows in the hotbar,
     *  2. the isometric cube for full blocks,
     *  3. the flat texture for cross/thin blocks (torch, flowers, carpet, wire…): drawing those as
     *     a cube is what made the torch icon look broken,
     *  4. a procedural glyph for items with no texture at all.
     */
    fun icon(id: String): androidx.compose.ui.graphics.ImageBitmap? {
        cache[id]?.let { return it }
        val bitmap = try {
            val index = com.arena.voxel.world.Blocks.index(id)
            val block = com.arena.voxel.world.Blocks.get(index)
            val itemSprite = com.arena.voxel.textures.Textures.bitmapNamed("item/$id")
                ?: com.arena.voxel.textures.Textures.bitmapNamed("item_$id")
            when {
                itemSprite != null ->
                    com.arena.voxel.textures.Textures.iconFromBitmap(itemSprite, 64)?.asImageBitmap()
                block.id != "air" && (block.cross || !block.shape.isFullCube) ->
                    com.arena.voxel.textures.Textures.spriteIcon(block.tileSide, 64)?.asImageBitmap()
                block.id != "air" ->
                    com.arena.voxel.textures.Textures.blockIcon(block.tileTop, block.tileSide, 64)
                        ?.asImageBitmap()
                else -> itemGlyph(id)
            }
        } catch (t: Throwable) {
            null
        }
        cache[id] = bitmap
        return bitmap
    }

    /** Simple 16x16 procedural glyph for non-block items (tools, food, materials). */
    private fun itemGlyph(id: String): androidx.compose.ui.graphics.ImageBitmap? {
        val bmp = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint().apply { isAntiAlias = false; strokeWidth = 3f }
        val color = when {
            id.contains("pickaxe") || id.contains("axe") || id.contains("shovel") -> 0xFFB0B0B0.toInt()
            id.contains("sword") -> 0xFFD0D0D0.toInt()
            id.contains("bow") -> 0xFF8B5A2B.toInt()
            id.contains("apple") -> 0xFFD42A2A.toInt()
            id.contains("bread") -> 0xFFC89B4A.toInt()
            id.contains("beef") || id.contains("pork") -> 0xFFC06A5A.toInt()
            id.contains("iron") -> 0xFFD8D8D8.toInt()
            id.contains("gold") -> 0xFFF6D743.toInt()
            id.contains("diamond") -> 0xFF4FD6CF.toInt()
            id.contains("emerald") -> 0xFF2ECC71.toInt()
            id.contains("coal") -> 0xFF262626.toInt()
            id.contains("redstone") -> 0xFFC81E1E.toInt()
            id.contains("stick") -> 0xFF8B5A2B.toInt()
            id.contains("string") -> 0xFFE8E8E8.toInt()
            else -> 0xFF9A9A9A.toInt()
        }
        paint.color = color
        paint.style = android.graphics.Paint.Style.FILL
        canvas.drawRect(7f, 7f, 25f, 25f, paint)
        paint.color = 0xFF000000.toInt()
        paint.style = android.graphics.Paint.Style.STROKE
        canvas.drawRect(7f, 7f, 25f, 25f, paint)
        return bmp.asImageBitmap()
    }
}
