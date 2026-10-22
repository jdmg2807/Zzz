package com.arena.voxel.ui

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.arena.voxel.Game
import com.arena.voxel.items.Inventory
import com.arena.voxel.items.ItemStack
import com.arena.voxel.items.Items
import com.arena.voxel.items.Recipes

/**
 * Container screens measured like Java's `AbstractContainerScreen`:
 * the inventory panel is 176x166 GUI px with 18x18 slots, the creative panel is 195x136 with
 * category tabs on top, and the frame is a 1 px dark border with a lighter inner line.
 */
enum class ContainerMode { INVENTORY, CRAFTING_TABLE, CREATIVE }

@Composable
fun ContainerScreen(
    gui: Gui,
    initialMode: ContainerMode,
    onClose: () -> Unit
) {
    var mode by remember { mutableStateOf(initialMode) }
    var cursor by remember { mutableStateOf<ItemStack?>(null) }
    var refresh by remember { mutableStateOf(0) }
    val inv = Game.inventory

    fun changed() {
        Game.syncHotbar()
        refresh++
    }

    Box(Modifier.fillMaxSize().background(Color(0xB0000000))) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } })

        val panelW = if (mode == ContainerMode.CREATIVE) 195f else 176f
        val panelH = if (mode == ContainerMode.CREATIVE) 136f else 166f

        Column(
            Modifier.align(Alignment.Center).width(gui.px(panelW)).height(gui.px(panelH + 20f)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ---- title bar ------------------------------------------------------------
            Text(
                when (mode) {
                    ContainerMode.INVENTORY -> if (Game.creative) "Inventario (creativo)" else "Inventario"
                    ContainerMode.CRAFTING_TABLE -> "Mesa de trabajo"
                    ContainerMode.CREATIVE -> "Bloques"
                },
                color = Color(0xFF404040),
                fontSize = gui.text(8f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = gui.px(1f))
            )

            Box(
                Modifier
                    .width(gui.px(panelW))
                    .height(gui.px(panelH))
                    .background(Color(0xFFC6C6C6))
                    .border(gui.px(1f), Color(0xFF000000))
            ) {
                when (mode) {
                    ContainerMode.CREATIVE -> CreativePanel(gui, cursor) { stack ->
                        cursor = stack
                        changed()
                    }
                    ContainerMode.CRAFTING_TABLE -> CraftingPanel(
                        gui, inv, 3, cursor, refresh
                    ) { newCursor ->
                        cursor = newCursor
                        changed()
                    }
                    ContainerMode.INVENTORY -> CraftingPanel(
                        gui, inv, 2, cursor, refresh
                    ) { newCursor ->
                        cursor = newCursor
                        changed()
                    }
                }
            }

            Spacer(Modifier.height(gui.px(3f)))
            Row {
                // Java has the recipe book and the creative tabs; here it is a mode switch.
                if (Game.creative) {
                    GuiButton(
                        gui,
                        if (mode == ContainerMode.CREATIVE) "Inventario" else "Bloques",
                        width = gui.px(70f)
                    ) { mode = if (mode == ContainerMode.CREATIVE) ContainerMode.INVENTORY else ContainerMode.CREATIVE }
                    Spacer(Modifier.width(gui.px(BUTTON_GAP)))
                }
                if (inv.count("crafting_table") > 0 || mode == ContainerMode.CRAFTING_TABLE) {
                    GuiButton(gui, "Mesa de trabajo", width = gui.px(90f)) {
                        mode = ContainerMode.CRAFTING_TABLE
                    }
                    Spacer(Modifier.width(gui.px(BUTTON_GAP)))
                }
                GuiButton(gui, "Cerrar", width = gui.px(80f)) {
                // Anything left on the cursor goes back to the inventory.
                    cursor?.let { inv.add(it) }
                    cursor = null
                    changed()
                    onClose()
                }
            }
        }

        // Cursor stack follows a fixed spot (Java draws it at the mouse; on touch the tap place
        // model is used, so it is shown next to the panel instead).
        cursor?.let { stack ->
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = gui.px(-8f))
                    .size(gui.px(20f))
                    .background(Color(0x88000000))
                    .border(gui.px(1f), Color(0xFFFFFFFF)),
                contentAlignment = Alignment.Center
            ) {
                ItemIcon(stack.id, gui, gui.px(16f))
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

/** Survival inventory / crafting table: crafting grid + result + 3x9 main + 1x9 hotbar. */
@Composable
private fun CraftingPanel(
    gui: Gui,
    inv: Inventory,
    gridSize: Int,
    cursor: ItemStack?,
    refresh: Int,
    onCursor: (ItemStack?) -> Unit
) {
    var grid by remember(gridSize) { mutableStateOf(arrayOfNulls<ItemStack>(gridSize * gridSize)) }
    var result by remember { mutableStateOf<ItemStack?>(null) }

    fun recompute() {
        result = Recipes.craft(grid.map { it?.id })
    }

    val slot = 18f
    val left = 8f
    val top = 8f

    Box(Modifier.fillMaxSize()) {
        // Crafting grid.
        for (r in 0 until gridSize) {
            for (c in 0 until gridSize) {
                val index = r * gridSize + c
                Slot(
                    gui,
                    x = left + c * slot,
                    y = top + r * slot,
                    stack = grid[index],
                    onClick = { current ->
                        val held = cursor
                        val existing = grid[index]
                        when {
                            held == null && existing != null -> {
                                onCursor(existing)
                                grid[index] = null
                            }
                            held != null && existing == null -> {
                                grid[index] = held
                                onCursor(null)
                            }
                            held != null && existing != null && held.id == existing.id -> {
                                val max = Items.maxStack(existing.id)
                                val move = minOf(max - existing.count, held.count)
                                existing.count += move
                                held.count -= move
                                if (held.count <= 0) onCursor(null) else onCursor(held)
                            }
                            else -> {
                                grid[index] = held
                                onCursor(existing)
                            }
                        }
                        recompute()
                    }
                )
            }
        }

        // Result slot.
        val resultX = left + gridSize * slot + 22f
        Slot(
            gui, x = resultX, y = top + (gridSize * slot) / 2f - slot / 2f, stack = result,
            onClick = {
                val res = result
                val held = cursor
                var take = false
                if (res != null) {
                    if (held == null) {
                        onCursor(res.copyStack())
                        take = true
                    } else if (held.id == res.id &&
                        held.count + res.count <= Items.maxStack(res.id)
                    ) {
                        held.count += res.count
                        take = true
                    }
                }
                if (!take) return@Slot
                // Consume one of each ingredient.
                for (i in grid.indices) {
                    val s = grid[i] ?: continue
                    s.count -= 1
                    if (s.count <= 0) grid[i] = null
                }
                recompute()
            }
        )

        // Main inventory 3x9.
        for (r in 0 until 3) {
            for (c in 0 until 9) {
                val index = 9 + r * 9 + c
                Slot(
                    gui, x = left + c * slot, y = top + gridSize * slot + 20f + r * slot,
                    stack = inv.get(index)
                ) { current -> transfer(inv, index, cursor, current, onCursor) }
            }
        }
        // Hotbar 1x9.
        for (c in 0 until 9) {
            Slot(
                gui, x = left + c * slot, y = top + gridSize * slot + 20f + 3 * slot + 6f,
                stack = inv.get(c)
            ) { current -> transfer(inv, c, cursor, current, onCursor) }
        }
    }
}

/** Shared click logic for the player inventory slots. */
private fun transfer(
    inv: Inventory,
    index: Int,
    cursor: ItemStack?,
    current: ItemStack?,
    onCursor: (ItemStack?) -> Unit
) {
    when {
        cursor == null && current != null -> {
            onCursor(current)
            inv.set(index, null)
        }
        cursor != null && current == null -> {
            inv.set(index, cursor)
            onCursor(null)
        }
        cursor != null && current != null && cursor.id == current.id -> {
            val max = Items.maxStack(current.id)
            val move = minOf(max - current.count, cursor.count)
            current.count += move
            cursor.count -= move
            if (cursor.count <= 0) onCursor(null) else onCursor(cursor)
        }
        cursor != null && current != null -> {
            inv.set(index, cursor)
            onCursor(current)
        }
    }
}

/** Creative inventory: category tabs on top + 9x5 grid of everything in the tab. */
@Composable
private fun CreativePanel(gui: Gui, cursor: ItemStack?, onGrab: (ItemStack?) -> Unit) {
    val tabs = remember { Items.creativeTabs() }
    var tab by remember { mutableStateOf(0) }
    var search by remember { mutableStateOf("") }
    val entries = remember(tab, search) {
        if (search.isBlank()) tabs[tab].second
        else Items.all().map { it.id }.filter { Items.nameOf(it).contains(search, ignoreCase = true) }.take(45)
    }

    Box(Modifier.fillMaxSize()) {
        // Tabs.
        Row(Modifier.padding(start = gui.px(4f), top = gui.px(2f))) {
            tabs.forEachIndexed { index, pair ->
                Box(
                    Modifier
                        .height(gui.px(14f))
                        .padding(end = gui.px(2f))
                        .background(if (index == tab) Color(0xFFE0E0E0) else Color(0xFF9A9A9A))
                        .border(gui.px(1f), Color(0xFF3A3A3A))
                        .pointerInput(index) { detectTapGestures { tab = index } }
                        .padding(horizontal = gui.px(2f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        pair.first.take(6),
                        color = Color(0xFF303030),
                        fontSize = gui.text(6f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        // Item grid 9x5.
        val slot = 18f
        for (i in 0 until 45) {
            val id = entries.getOrNull(i)
            val col = i % 9
            val row = i / 9
            Slot(
                gui,
                x = 8f + col * slot,
                y = 20f + row * slot,
                stack = id?.let { ItemStack(it, 1) }
            ) {
                if (id != null) {
                    val stack = ItemStack(id, Items.maxStack(id))
                    if (Game.inventory.add(stack) > 0) {
                        onGrab(ItemStack(id, stack.count))
                    }
                    Game.syncHotbar()
                }
            }
        }
    }
}

/** One 18x18 Java slot with a 1 px border; [onClick] receives the slot contents. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.Slot(
    gui: Gui,
    x: Float,
    y: Float,
    stack: ItemStack?,
    onClick: (ItemStack?) -> Unit
) {
    Box(
        Modifier
            .offset(x = gui.px(x), y = gui.px(y))
            .size(gui.px(18f))
            .background(Color(0xFF8B8B8B))
            .border(gui.px(1f), Color(0xFF373737))
            .pointerInput(stack?.id, stack?.count) { detectTapGestures { onClick(stack) } },
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
