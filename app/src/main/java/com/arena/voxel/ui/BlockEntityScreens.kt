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
import androidx.compose.runtime.LaunchedEffect
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
import com.arena.voxel.world.Containers
import kotlinx.coroutines.delay

/**
 * Chest and furnace screens, laid out with the vanilla 176-px-wide container geometry.
 *
 * Chest: 176x168, storage 3x9 at (8, 18), player inventory at (8, 85).
 * Furnace: 176x166, input (56, 17), fuel (56, 53), output (116, 31), player inventory (8, 84).
 */
@Composable
fun BlockEntityScreen(
    gui: Gui,
    kind: String,
    pos: Triple<Int, Int, Int>,
    onClose: () -> Unit
) {
    var cursor by remember { mutableStateOf<ItemStack?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    val inv = Game.inventory

    fun changed() {
        Game.syncHotbar()
        refresh++
    }

    // Furnaces tick on the server thread; repaint the progress bar a few times per second.
    if (kind != "chest") {
        LaunchedEffect(kind) {
            while (true) {
                delay(120)
                refresh++
            }
        }
    }

    val isChest = kind == "chest"
    val panelW = 176f
    val panelH = if (isChest) 168f else 166f

    Box(Modifier.fillMaxSize().background(Color(0xB0000000))) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } })

        Column(
            Modifier.align(Alignment.Center).width(gui.px(panelW)).height(gui.px(panelH + 20f)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (isChest) "Cofre" else "Horno",
                color = Color(0xFF404040),
                fontSize = gui.text(8f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.height(gui.px(10f))
            )
            Box(
                Modifier
                    .width(gui.px(panelW))
                    .height(gui.px(panelH))
                    .background(Color(0xFFC6C6C6))
                    .border(gui.px(1f), Color(0xFF000000))
            ) {
                if (isChest) {
                    val chest = remember(pos) { Containers.chestAt(pos.first, pos.second, pos.third) }
                    for (r in 0 until 3) {
                        for (c in 0 until 9) {
                            val i = r * 9 + c
                            Slot(gui, 8f + c * 18f, 18f + r * 18f, chest.get(i)) { current ->
                                transfer(chest, i, cursor, current) { cursor = it }
                                changed()
                            }
                        }
                    }
                    PlayerSlots(gui, inv, 8f, 85f, cursor) { cursor = it; changed() }
                } else {
                    val furnace = remember(pos) { Containers.furnaceAt(pos.first, pos.second, pos.third) }
                    FurnaceBody(gui, furnace, cursor, refresh) { cursor = it; changed() }
                    PlayerSlots(gui, inv, 8f, 84f, cursor) { cursor = it; changed() }
                }
            }

            Spacer(Modifier.height(gui.px(3f)))
            GuiButton(gui, "Cerrar", width = gui.px(80f)) {
                cursor?.let { inv.add(it) }
                cursor = null
                changed()
                onClose()
            }
        }

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

/** Three furnace slots plus the flame and progress arrow drawn from the tick counters. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FurnaceBody(
    gui: Gui,
    furnace: Containers.Furnace,
    cursor: ItemStack?,
    refresh: Int,
    onCursor: (ItemStack?) -> Unit
) {
    // Re-read the tick counters whenever the parent screen refreshes.
    val burning = remember(refresh) { furnace.isBurning }
    val progress = remember(refresh) { furnace.progress }

    Slot(gui, 56f, 17f, furnace.inventory.get(0)) { current ->
        transfer(furnace.inventory, 0, cursor, current, onCursor)
    }
    Slot(gui, 56f, 53f, furnace.inventory.get(1)) { current ->
        transfer(furnace.inventory, 1, cursor, current, onCursor)
    }
    Slot(gui, 116f, 31f, furnace.inventory.get(2)) { current ->
        transfer(furnace.inventory, 2, cursor, current, onCursor)
    }

    // Flame: 14x14 at (57, 37), drawn as a bar from the bottom up.
    Box(
        Modifier
            .offset(x = gui.px(57f), y = gui.px(37f))
            .width(gui.px(14f))
            .height(gui.px(14f))
            .background(Color(0xFF8B8B8B))
            .border(gui.px(1f), Color(0xFF373737))
    ) {
        val fraction = if (furnace.burnTotal > 0) furnace.burnTime / furnace.burnTotal.toFloat() else 0f
        if (burning && fraction > 0f) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .width(gui.px(12f))
                    .height(gui.px(12f * fraction.coerceIn(0f, 1f)))
                    .background(Color(0xFFE8A020))
            )
        }
    }

    // Progress arrow: 24x16 at (79, 35).
    Box(
        Modifier
            .offset(x = gui.px(79f), y = gui.px(35f))
            .width(gui.px(24f))
            .height(gui.px(16f))
            .background(Color(0xFF8B8B8B))
            .border(gui.px(1f), Color(0xFF373737))
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(gui.px(22f * progress))
                .height(gui.px(14f))
                .background(Color(0xFFF0F0F0))
        )
    }

    if (furnace.inventory.get(2) != null) {
        Text(
            "Listo",
            color = Color(0xFF3A6B2A),
            fontSize = gui.text(6f),
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.offset(x = gui.px(112f), y = gui.px(52f))
        )
    }
    // Labels echoing the Java slots so touch players know what goes where.
    Row(Modifier.offset(x = gui.px(6f), y = gui.px(6f))) {
        Text(
            "Entrada / Combustible",
            color = Color(0xFF505050),
            fontSize = gui.text(6f),
            fontFamily = FontFamily.Monospace
        )
    }
}

/** Anvil screen: two inputs, the result and the player inventory (Java: 176x166). */
@Composable
fun AnvilScreen(gui: Gui, onClose: () -> Unit) {
    var cursor by remember { mutableStateOf<ItemStack?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    var name by remember { mutableStateOf("") }
    val inv = Game.inventory
    val inputs = remember { arrayOfNulls<ItemStack>(2) }

    fun changed() {
        Game.syncHotbar()
        refresh++
    }

    val result: ItemStack? = remember(refresh) {
        com.arena.voxel.items.Anvil.combine(inputs[0], inputs[1], name)?.let { ItemStack(it, 1) }
    }
    // Level cost shown on the right of the GUI, exactly like the vanilla anvil.
    val cost = remember(refresh) { com.arena.voxel.items.Anvil.cost(inputs[0], inputs[1], name) }

    Box(Modifier.fillMaxSize().background(Color(0xB0000000))) {
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { } })
        Column(
            Modifier.align(Alignment.Center).width(gui.px(176f)).height(gui.px(188f)),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "Yunque",
                color = Color(0xFF404040),
                fontSize = gui.text(8f),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.height(gui.px(10f))
            )
            Box(
                Modifier
                    .width(gui.px(176f))
                    .height(gui.px(166f))
                    .background(Color(0xFFC6C6C6))
                    .border(gui.px(1f), Color(0xFF000000))
            ) {
                for (i in 0 until 2) {
                    Slot(gui, 27f + i * 31f, 47f, inputs[i]) { current ->
                        when {
                            cursor == null && current != null -> {
                                inputs[i] = null
                                cursor = current
                            }
                            cursor != null && current == null -> {
                                inputs[i] = cursor
                                cursor = null
                            }
                            cursor != null && current != null && cursor.id == current.id -> {
                                val max = com.arena.voxel.items.Items.maxStack(current.id)
                                val move = minOf(max - current.count, cursor.count)
                                current.count += move
                                cursor.count -= move
                                if (cursor.count <= 0) cursor = null
                            }
                            else -> {
                                inputs[i] = cursor
                                cursor = current
                            }
                        }
                        changed()
                    }
                }
                Slot(gui, 134f, 47f, result) {
                    val res = result ?: return@Slot
                    if (cursor == null) cursor = res
                    else if (cursor.id == res.id) cursor.count += res.count
                    else return@Slot
                    for (i in 0 until 2) inputs[i] = null
                    Game.player?.experience = (Game.player?.experience ?: 0f) - cost
                    changed()
                }
                Text(
                    if (cost > 0) "Nivel $cost" else "",
                    color = if (cost > 39) Color(0xFFAA0000) else Color(0xFF4B8B3B),
                    fontSize = gui.text(7f),
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.offset(x = gui.px(110f), y = gui.px(30f))
                )
                PlayerSlots(gui, inv, 8f, 84f, cursor) { cursor = it; changed() }
            }
            Text(
                "Toca dos objetos del mismo tipo para combinarlos o repararlos",
                color = Color(0xFFD0D0D0),
                fontSize = gui.text(6f),
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 2.dp)
            )
            Spacer(Modifier.height(gui.px(3f)))
            GuiButton(gui, "Cerrar", width = gui.px(80f)) {
                inputs.forEach { if (it != null) inv.add(it) }
                cursor?.let { inv.add(it) }
                cursor = null
                changed()
                onClose()
            }
        }
    }
}
