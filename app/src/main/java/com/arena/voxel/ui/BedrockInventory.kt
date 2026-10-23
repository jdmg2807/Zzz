package com.arena.voxel.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arena.voxel.Game
import com.arena.voxel.items.ItemStack
import com.arena.voxel.items.Items
import com.arena.voxel.items.Recipes

/**
 * Bedrock Edition inventory.
 *
 * Bedrock's phone inventory is a dark plate with a title bar, a 2x2 crafting corner with its
 * result, the player model on the right, the 3x9 storage grid and the hotbar row at the bottom,
 * separated by a groove. Creative mode swaps the crafting corner for a 9x5 item page with the
 * category tabs stacked on the left edge.
 *
 * Geometry below is in GUI pixels (see [Gui]), so it never overflows the screen.
 */
private const val INV_W = 178f
private const val INV_H = 168f
private const val SLOT = 18f

@Composable
fun BedrockInventoryScreen(
    gui: Gui,
    mode: ContainerMode,
    onClose: () -> Unit,
    onOpenCrafting: () -> Unit = {}
) {
    var cursor by remember { mutableStateOf<ItemStack?>(null) }
    var page by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var refresh by remember { mutableIntStateOf(0) }
    val inv = Game.inventory
    val creativeGrid = mode == ContainerMode.CREATIVE || (mode == ContainerMode.INVENTORY && Game.creative)
    val gridSize = if (mode == ContainerMode.CRAFTING_TABLE) 3 else 2

    fun touch() {
        refresh++
        Game.syncHotbar()
    }

    fun close() {
        cursor?.let { inv.add(it) }
        cursor = null
        touch()
        onClose()
    }

    Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
        // Background tap returns the held stack, like Bedrock dropping it back into the bag.
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures {
                    cursor?.let { inv.add(it) }
                    cursor = null
                    refresh++
                    Game.syncHotbar()
                }
            }
        )

        val panelW = if (creativeGrid) 196f else INV_W
        val panelH = if (creativeGrid) 152f else INV_H

        Column(
            Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BedrockPanel(gui, Modifier.width(gui.px(panelW)).height(gui.px(panelH + 20f))) {
                BedrockTitleBar(gui, titleFor(mode), onClose = { close() })

                Box(Modifier.offset(x = 0f.dp, y = gui.px(16f))) {
                    val key = refresh
                    if (creativeGrid) {
                        CreativePage(gui, key, page, tab, cursor, onCursor = { stack ->
                            cursor = stack
                            touch()
                        }, onPage = { page = it }, onTab = { tab = it })
                    } else {
                        SurvivalBody(
                            gui,
                            key,
                            gridSize,
                            cursor,
                            onCursor = { stack, changed ->
                                cursor = stack
                                if (changed) touch() else refresh++
                            }
                        )
                    }
                }
            }

            Spacer(Modifier.height(gui.px(4f)))
            Row {
                if (creativeGrid) {
                    BedrockButton(gui, "◀", width = gui.px(30f), height = gui.px(18f), fontSize = 7f) {
                        page = (page - 1 + 99) % 99
                    }
                    Spacer(Modifier.width(gui.px(4f)))
                    BedrockButton(gui, "▶", width = gui.px(30f), height = gui.px(18f), fontSize = 7f) {
                        page += 1
                    }
                    Spacer(Modifier.width(gui.px(8f)))
                } else if (mode == ContainerMode.INVENTORY) {
                    BedrockButton(gui, "Mesa de trabajo", width = gui.px(90f), height = gui.px(18f), fontSize = 7f) {
                        cursor?.let { inv.add(it) }
                        cursor = null
                        touch()
                        onOpenCrafting()
                    }
                    Spacer(Modifier.width(gui.px(6f)))
                }
                BedrockButton(gui, "Listo", width = gui.px(64f), height = gui.px(18f), fontSize = 7f) {
                    close()
                }
            }
        }

        // Held stack: pinned to the right edge so the thumb never hides it.
        cursor?.let { stack ->
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = gui.px(-8f))
                    .size(gui.px(22f))
                    .background(Color(0xCC000000))
                    .border(gui.px(1), Color(0xFFFFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                ItemIcon(stack.id, gui, gui.px(18f))
                if (stack.count > 1) {
                    Text(
                        stack.count.toString(),
                        color = Color.White,
                        fontSize = gui.text(6.5f),
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

private fun titleFor(mode: ContainerMode): String = when (mode) {
    ContainerMode.INVENTORY -> if (Game.creative) "CREATIVO" else "INVENTARIO"
    ContainerMode.CRAFTING_TABLE -> "MESA DE TRABAJO"
    ContainerMode.CREATIVE -> "CREATIVO"
}

/** Survival / crafting-table layout: crafting corner, player model, storage grid, hotbar. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SurvivalBody(
    gui: Gui,
    key: Int,
    gridSize: Int,
    cursor: ItemStack?,
    onCursor: (ItemStack?, Boolean) -> Unit
) {
    val grid = Game.craftingGrid
    val inv = Game.inventory
    val refreshed = key // read so the slots repaint

    fun storage(row: Int, col: Int): Int = row * 3 + col

    fun recompute() {
        val ids = ArrayList<String?>()
        if (gridSize == 3) {
            for (i in 0 until 9) ids.add(grid[i]?.id)
        } else {
            ids.add(grid[0]?.id)
            ids.add(grid[1]?.id)
            ids.add(grid[3]?.id)
            ids.add(grid[4]?.id)
        }
        Game.craftingResult = Recipes.craft(ids)
    }

    val gridLeft = if (gridSize == 3) 26f else 8f
    val gridTop = 6f
    val resultX = if (gridSize == 3) 112f else 60f
    val resultY = 15f

    // ---- crafting grid
    for (row in 0 until gridSize) {
        for (col in 0 until gridSize) {
            val store = storage(row, col)
            SlotAt(gui, gridLeft + col * SLOT, gridTop + row * SLOT, grid[store]) {
                val current = grid[store]
                val held = cursor
                when {
                    held == null && current != null -> {
                        grid[store] = null
                        onCursor(current, false)
                    }
                    held != null && current == null -> {
                        grid[store] = held
                        onCursor(null, false)
                    }
                    held != null && current != null && held.id == current.id -> {
                        val max = Items.maxStack(current.id)
                        val move = minOf(max - current.count, held.count)
                        current.count += move
                        held.count -= move
                        onCursor(if (held.count <= 0) null else held, false)
                    }
                    held != null && current != null -> {
                        grid[store] = held
                        onCursor(current, false)
                    }
                }
                recompute()
            }
        }
    }

    // ---- result arrow + slot
    Canvas(
        Modifier
            .offset(x = gui.px(resultX - 16f), y = gui.px(resultY + 5f))
            .size(gui.px(12f), gui.px(8f))
    ) {
        drawRect(Color(0xFFBDBDBD), topLeft = Offset(0f, size.height * 0.35f), size = Size(size.width * 0.6f, size.height * 0.3f))
        drawRect(Color(0xFFBDBDBD), topLeft = Offset(size.width * 0.55f, 0f), size = Size(size.width * 0.2f, size.height))
        drawRect(Color(0xFFBDBDBD), topLeft = Offset(size.width * 0.55f, 0f), size = Size(size.width * 0.45f, size.height))
    }
    recompute()
    SlotAt(gui, resultX, resultY, Game.craftingResult, big = true) {
        val result = Game.craftingResult ?: return@SlotAt
        val held = cursor
        if (held != null && (held.id != result.id || held.count + result.count > Items.maxStack(result.id))) return@SlotAt
        onCursor(if (held == null) result.copy() else { held.count += result.count; held }, true)
        for (i in 0 until 9) {
            val s = grid[i] ?: continue
            if (gridSize == 2 && i != 0 && i != 1 && i != 3 && i != 4) continue
            s.count -= 1
            if (s.count <= 0) grid[i] = null
        }
        recompute()
    }

    // ---- player model (Bedrock always shows the character next to the inventory)
    CharacterPreview(gui, 86f, 8f, refreshed)

    // ---- storage 3x9 + hotbar
    for (row in 0 until 3) {
        for (col in 0 until 9) {
            val index = 9 + row * 9 + col
            SlotAt(gui, 8f + col * SLOT, 66f + row * SLOT, inv.get(index)) {
                val result = clickSlot(inv, index, cursor)
                onCursor(result.first, result.second)
            }
        }
    }
    Canvas(
        Modifier
            .offset(x = gui.px(8f), y = gui.px(124f))
            .size(gui.px(162f), gui.px(1f))
    ) {
        drawRect(Color(0xFF000000), size = Size(size.width, size.height))
    }
    for (col in 0 until 9) {
        val index = col
        SlotAt(gui, 8f + col * SLOT, 130f, inv.get(index)) {
            val result = clickSlot(inv, index, cursor)
            onCursor(result.first, result.second)
        }
    }
}

/** Creative page: category tabs on the left, 9x5 item grid, tap to grab a stack. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CreativePage(
    gui: Gui,
    key: Int,
    page: Int,
    tab: Int,
    cursor: ItemStack?,
    onCursor: (ItemStack?) -> Unit,
    onPage: (Int) -> Unit,
    onTab: (Int) -> Unit
) {
    val tabs = remember { Items.creativeTabs() }
    val current = tabs.getOrNull(tab.coerceIn(0, tabs.size - 1)) ?: return
    val list = current.second
    val start = page * 45
    key.hashCode()

    // Category tabs (Bedrock stacks them on the left edge of the creative screen).
    val maxTabs = 7
    val shown = tabs.take(maxTabs)
    shown.forEachIndexed { i, entry ->
        val firstId = entry.second.firstOrNull()
        Box(
            Modifier
                .offset(x = gui.px(2f), y = gui.px(4f + i * (SLOT + 1f)))
                .size(gui.px(SLOT))
                .background(if (i == tab) Color(0xFF3E3E3E) else Color(0xFF161616))
                .border(gui.px(1), if (i == tab) Color(0xFFFFFFFF) else Color(0xFF000000))
                .pointerInput(i, tab) { detectTapGestures { onTab(i); onPage(0) } },
            contentAlignment = Alignment.Center
        ) {
            if (firstId != null) ItemIcon(firstId, gui, gui.px(14f))
        }
    }

    for (i in 0 until 45) {
        val id = list.getOrNull(start + i) ?: break
        val col = i % 9
        val row = i / 9
        SlotAt(gui, 26f + col * SLOT, 6f + row * SLOT, ItemStack(id, 1)) {
            onCursor(ItemStack(id, if (Items.maxStack(id) > 1) 64 else 1))
        }
    }

    BedrockText(
        gui,
        "${current.first}  ${page + 1}",
        size = 7f,
        color = Bedrock.textDim,
        modifier = Modifier.offset(x = gui.px(26f), y = gui.px(104f))
    )
}

/** One 18x18 Bedrock slot positioned in GUI pixels. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.SlotAt(
    gui: Gui,
    x: Float,
    y: Float,
    stack: ItemStack?,
    big: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .offset(x = gui.px(x), y = gui.px(y))
            .size(gui.px(if (big) SLOT + 4f else SLOT))
            .background(Bedrock.slot)
            .border(gui.px(if (big) 2f else 1f), if (big) Color(0xFFBFBFBF) else Bedrock.slotBorder)
            .pointerInput(stack?.id, stack?.count, big) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
        if (stack != null) {
            ItemIcon(stack.id, gui, gui.px(if (big) 18f else 15f))
            if (stack.count > 1) {
                Text(
                    stack.count.toString(),
                    color = Color.White,
                    fontSize = gui.text(6.5f),
                    fontFamily = FontFamily.SansSerif,
                    fontWeight = FontWeight.Bold,
                    style = TextStyle(shadow = Bedrock.shadow),
                    modifier = Modifier.align(Alignment.BottomEnd)
                )
            }
        }
    }
}

/**
 * Blocky character preview drawn with rectangles: Bedrock shows the player model in the
 * inventory, and this keeps the same silhouette without bundling any art.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CharacterPreview(gui: Gui, x: Float, y: Float, key: Int) {
    key.hashCode()
    Box(
        Modifier
            .offset(x = gui.px(x), y = gui.px(y))
            .size(gui.px(58f), gui.px(54f))
            .background(Color(0xFF0B0B0B))
            .border(gui.px(1), Bedrock.slotBorder)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val skin = Color(0xFFC79A6B)
            val shirt = Color(0xFF2FA8C8)
            val pants = Color(0xFF3B4F9E)
            val shoe = Color(0xFF3A3A3A)
            // head
            drawRect(skin, topLeft = Offset(w * 0.36f, h * 0.05f), size = Size(w * 0.28f, h * 0.2f))
            drawRect(Color(0xFF2B1B0E), topLeft = Offset(w * 0.36f, h * 0.05f), size = Size(w * 0.28f, h * 0.06f))
            // eyes
            drawRect(Color(0xFF252525), topLeft = Offset(w * 0.41f, h * 0.14f), size = Size(w * 0.05f, h * 0.04f))
            drawRect(Color(0xFF252525), topLeft = Offset(w * 0.54f, h * 0.14f), size = Size(w * 0.05f, h * 0.04f))
            // torso
            drawRect(shirt, topLeft = Offset(w * 0.34f, h * 0.27f), size = Size(w * 0.32f, h * 0.28f))
            // arms
            drawRect(skin, topLeft = Offset(w * 0.24f, h * 0.28f), size = Size(w * 0.09f, h * 0.26f))
            drawRect(skin, topLeft = Offset(w * 0.67f, h * 0.28f), size = Size(w * 0.09f, h * 0.26f))
            // legs
            drawRect(pants, topLeft = Offset(w * 0.35f, h * 0.55f), size = Size(w * 0.13f, h * 0.3f))
            drawRect(pants, topLeft = Offset(w * 0.52f, h * 0.55f), size = Size(w * 0.13f, h * 0.3f))
            drawRect(shoe, topLeft = Offset(w * 0.33f, h * 0.85f), size = Size(w * 0.16f, h * 0.08f))
            drawRect(shoe, topLeft = Offset(w * 0.51f, h * 0.85f), size = Size(w * 0.16f, h * 0.08f))
        }
    }
}

/** Bedrock's tap rules on a slot: pick up, put down, merge or swap. */
private fun clickSlot(
    inv: com.arena.voxel.items.Inventory,
    index: Int,
    cursor: ItemStack?
): Pair<ItemStack?, Boolean> {
    val existing = inv.get(index)
    return when {
        cursor == null && existing != null -> {
            inv.set(index, null)
            existing to true
        }
        cursor != null && existing == null -> {
            inv.set(index, cursor)
            null to true
        }
        cursor != null && existing != null && cursor.id == existing.id -> {
            val max = Items.maxStack(existing.id)
            val move = minOf(max - existing.count, cursor.count)
            existing.count += move
            cursor.count -= move
            if (cursor.count <= 0) null to true else cursor to true
        }
        cursor != null && existing != null -> {
            inv.set(index, cursor)
            existing to true
        }
        else -> cursor to false
    }
}
