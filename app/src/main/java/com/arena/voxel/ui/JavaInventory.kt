package com.arena.voxel.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arena.voxel.Game
import com.arena.voxel.items.ItemStack
import com.arena.voxel.items.Items
import com.arena.voxel.items.Recipes

/**
 * Container screens with the vanilla geometry and the vanilla tap model.
 *
 * ```
 * inventory        176x166  2x2 grid at (98,18), result (154,28), player (8,84), hotbar (8,142)
 * crafting table   176x166  3x3 grid at (30,17), result (124,35), player (8,84), hotbar (8,142)
 * creative         195x136  tabs on top, 9x5 grid of every item (45 per page, paged)
 * ```
 *
 * Slots are 18x18 with a 16x16 icon, so a tap moves whole stacks exactly like Java's left click.
 */
@Composable
fun JavaContainerScreen(
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
    // Reading the refresh counter here keeps the slot contents in sync: the inventory is a plain
    // mutable object, so Compose only repaints when this state changes.
    val liveMode = remember(refresh, mode) { mode }

    fun touch() {
        refresh++
        Game.syncHotbar()
    }

    val wide = mode == ContainerMode.CREATIVE
    val panelW = if (wide) 195f else 176f
    val panelH = if (wide) 136f else 166f

    Box(Modifier.fillMaxSize().background(Color(0x99000000))) {
        // Tapping the background returns the held stack to the inventory.
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            detectTapGestures {
                cursor?.let { inv.add(it) }
                cursor = null
                touch()
            }
        })

        Column(
            Modifier.align(Alignment.Center).width(gui.px(panelW)).height(gui.px(panelH + 14f)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                when (mode) {
                    ContainerMode.INVENTORY -> if (Game.creative) "Creativo" else "Inventario"
                    ContainerMode.CRAFTING_TABLE -> "Mesa de trabajo"
                    ContainerMode.CREATIVE -> "Bloques"
                },
                color = Color(0xFF3A3A3A),
                fontSize = gui.text(8f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.height(gui.px(9f))
            )
            Box(
                Modifier
                    .width(gui.px(panelW))
                    .height(gui.px(panelH))
                    .background(Color(0xFFC6C6C6))
                    .border(gui.px(1f), Color(0xFF000000))
            ) {
                when (liveMode) {
                    ContainerMode.CREATIVE -> CreativeBody(gui, page, tab, cursor) { stack ->
                        cursor = stack
                        touch()
                    }
                    ContainerMode.CRAFTING_TABLE -> CraftingBody(gui, 3, cursor) { stack, changed ->
                        cursor = stack
                        if (changed) touch() else refresh++
                    }
                    ContainerMode.INVENTORY -> {
                        if (Game.creative) {
                            CreativeBody(gui, page, tab, cursor) { stack -> cursor = stack; touch() }
                        } else {
                            CraftingBody(gui, 2, cursor) { stack, changed ->
                                cursor = stack
                                if (changed) touch() else refresh++
                            }
                        }
                    }
                }
            }

            // Buttons under the panel: paging (creative) and close.
            Row(Modifier.padding(top = 3.dp)) {
                if (mode == ContainerMode.CREATIVE || (mode == ContainerMode.INVENTORY && Game.creative)) {
                    val pageCount = creativePages()
                    if (pageCount > 1) {
                        GuiButton(gui, "◀", width = gui.px(28f)) { page = (page - 1 + pageCount) % pageCount }
                        Text(
                            " ${page + 1}/$pageCount ",
                            color = Color(0xFFE0E0E0),
                            fontSize = gui.text(8f),
                            fontFamily = FontFamily.Monospace
                        )
                        GuiButton(gui, "▶", width = gui.px(28f)) { page = (page + 1) % pageCount }
                    }
                    val tabs = Items.creativeTabs()
                    GuiButton(gui, tabs[tab.coerceIn(0, tabs.size - 1)].first, width = gui.px(70f)) {
                        tab = (tab + 1) % tabs.size
                        page = 0
                    }
                }
                if (mode == ContainerMode.INVENTORY && !Game.creative) {
                    GuiButton(gui, "Mesa de trabajo", width = gui.px(90f)) {
                        cursor?.let { inv.add(it) }
                        cursor = null
                        onOpenCrafting()
                    }
                }
                GuiButton(gui, "Cerrar", width = gui.px(70f)) {
                    cursor?.let { inv.add(it) }
                    cursor = null
                    touch()
                    onClose()
                }
            }
        }

        // Cursor stack, pinned to the right side of the screen because touch has no pointer.
        cursor?.let { stack ->
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = gui.px(-10f))
                    .size(gui.px(20f))
                    .background(Color(0x88000000))
                    .border(gui.px(1f), Color(0xFFFFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                ItemIcon(stack.id, gui, gui.px(16f))
                if (stack.count > 1) {
                    Text(
                        stack.count.toString(),
                        color = Color.White,
                        fontSize = gui.text(7f),
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.align(Alignment.BottomEnd)
                    )
                }
            }
        }
    }
}

/** Number of creative pages (45 slots each). */
private fun creativePages(): Int = 1

/** One 18x18 Java slot: grey well, 1 px bevel and the item icon. */
@Composable
fun JavaSlot(
    gui: Gui,
    x: Float,
    y: Float,
    stack: ItemStack?,
    highlight: Boolean = false,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .offset(x = gui.px(x), y = gui.px(y))
            .size(gui.px(18f))
            .background(if (highlight) Color(0xFFE8E8E8) else Color(0xFF8B8B8B))
            .border(gui.px(1f), Color(0xFF373737))
            .pointerInput(stack?.id, stack?.count) { detectTapGestures { onClick() } },
        contentAlignment = Alignment.Center
    ) {
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

/** Player inventory: main 3x9 plus hotbar, at [left],[top]. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.PlayerGrid(
    gui: Gui,
    left: Float,
    top: Float,
    cursor: ItemStack?,
    onCursor: (ItemStack?, Boolean) -> Unit
) {
    val inv = Game.inventory
    for (row in 0 until 3) {
        for (col in 0 until 9) {
            val index = 9 + row * 9 + col
            JavaSlot(gui, left + col * 18f, top + row * 18f, inv.get(index)) {
                val result = clickSlot(inv, index, cursor)
                onCursor(result.first, result.second)
            }
        }
    }
    for (col in 0 until 9) {
        val index = col
        JavaSlot(gui, left + col * 18f, top + 58f, inv.get(index)) {
            val result = clickSlot(inv, index, cursor)
            onCursor(result.first, result.second)
        }
    }
}

/** Java's left-click rules on a slot: pick up, place, merge or swap. */
private fun clickSlot(inv: com.arena.voxel.items.Inventory, index: Int, cursor: ItemStack?): Pair<ItemStack?, Boolean> {
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

/** Crafting grid + result + the player inventory, laid out like the real GUI. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CraftingBody(
    gui: Gui,
    gridSize: Int,
    cursor: ItemStack?,
    onCursor: (ItemStack?, Boolean) -> Unit
) {
    val grid = Game.craftingGrid
    val gridLeft = if (gridSize == 3) 30f else 98f
    val gridTop = if (gridSize == 3) 17f else 18f
    val resultX = if (gridSize == 3) 124f else 154f
    val resultY = if (gridSize == 3) 35f else 28f

    fun recompute() {
        val ids = ArrayList<String?>()
        if (gridSize == 3) {
            for (i in 0 until 9) ids.add(grid[i]?.id)
        } else {
            // 2x2 uses the top-left of the same 3x3 array (Java keeps one grid per container).
            ids.add(grid[0]?.id); ids.add(grid[1]?.id)
            ids.add(grid[3]?.id); ids.add(grid[4]?.id)
        }
        Game.craftingResult = Recipes.craft(ids)
    }

    // Index of grid slot -> storage slot in Game.craftingGrid.
    fun storage(row: Int, col: Int): Int = row * 3 + col

    for (row in 0 until gridSize) {
        for (col in 0 until gridSize) {
            val store = storage(row, col)
            JavaSlot(gui, gridLeft + col * 18f, gridTop + row * 18f, grid[store]) {
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

    recompute()
    JavaSlot(gui, resultX, resultY, Game.craftingResult) {
        val result = Game.craftingResult ?: return@JavaSlot
        val held = cursor
        if (held != null && (held.id != result.id || held.count + result.count > Items.maxStack(result.id))) return@JavaSlot
        // Take the result and consume one of each ingredient, like Java.
        onCursor(if (held == null) result.copy() else { held.count += result.count; held }, true)
        for (i in 0 until 9) {
            val s = grid[i] ?: continue
            if (gridSize == 2 && i != 0 && i != 1 && i != 3 && i != 4) continue
            s.count -= 1
            if (s.count <= 0) grid[i] = null
        }
        recompute()
    }

    PlayerGrid(gui, 8f, 84f, cursor, onCursor)
}

/** Creative inventory: category tabs and a 9x5 page of items, tapping gives a stack. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CreativeBody(
    gui: Gui,
    page: Int,
    tab: Int,
    cursor: ItemStack?,
    onCursor: (ItemStack?) -> Unit
) {
    val tabs = Items.creativeTabs()
    val list = tabs[tab.coerceIn(0, tabs.size - 1)].second
    val start = page * 45
    for (i in 0 until 45) {
        val id = list.getOrNull(start + i) ?: continue
        val col = i % 9
        val row = i / 9
        JavaSlot(gui, 9f + col * 18f, 18f + row * 18f, ItemStack(id, 1)) {
            onCursor(ItemStack(id, if (Items.maxStack(id) > 1) 64 else 1))
        }
    }
    // A short hint on the right of the grid: the current tab name.
    Text(
        tabs[tab.coerceIn(0, tabs.size - 1)].first,
        color = Color(0xFF3A3A3A),
        fontSize = gui.text(7f),
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.offset(x = gui.px(9f), y = gui.px(4f))
    )
}
