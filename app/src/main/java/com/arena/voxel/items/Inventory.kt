package com.arena.voxel.items

/**
 * Player inventory: 36 slots (0..8 hotbar, 9..35 main), exactly like Java's `PlayerInventory`.
 * Slots are nullable; `null` is an empty slot.
 */
class Inventory(val size: Int = 36) {

    val slots = arrayOfNulls<ItemStack>(size)

    /** Slot the player is holding (0..8). */
    var selected = 0

    fun get(i: Int): ItemStack? = slots.getOrNull(i)

    fun set(i: Int, stack: ItemStack?) {
        if (i in slots.indices) slots[i] = stack
    }

    fun isEmpty(): Boolean = slots.all { it == null || it.isEmpty }

    val hotbar: List<ItemStack?>
        get() = (0 until 9).map { slots.getOrNull(it) }

    /** Adds a stack, merging into existing stacks first (Java behaviour). Returns leftovers. */
    fun add(stack: ItemStack): Int {
        var remaining = stack.count
        // 1) merge into matching stacks
        for (i in slots.indices) {
            val s = slots[i] ?: continue
            if (s.id != stack.id || s.count >= Items.maxStack(s.id)) continue
            val space = Items.maxStack(s.id) - s.count
            val move = minOf(space, remaining)
            s.count += move
            remaining -= move
            if (remaining <= 0) return 0
        }
        // 2) fill empty slots
        for (i in slots.indices) {
            if (slots[i] != null) continue
            val move = minOf(Items.maxStack(stack.id), remaining)
            slots[i] = ItemStack(stack.id, move, stack.damage)
            remaining -= move
            if (remaining <= 0) return 0
        }
        return remaining
    }

    /** Removes one item from a slot. */
    fun shrink(i: Int, amount: Int = 1) {
        val s = slots.getOrNull(i) ?: return
        s.count -= amount
        if (s.count <= 0) slots[i] = null
    }

    fun count(id: String): Int = slots.filterNotNull().filter { it.id == id }.sumOf { it.count }

    fun remove(id: String, amount: Int): Boolean {
        if (count(id) < amount) return false
        var left = amount
        for (i in slots.indices) {
            val s = slots[i] ?: continue
            if (s.id != id) continue
            val take = minOf(s.count, left)
            s.count -= take
            left -= take
            if (s.count <= 0) slots[i] = null
            if (left <= 0) break
        }
        return true
    }

    /** Selected stack, or null. */
    fun held(): ItemStack? = slots.getOrNull(selected)

    /** Adds `count` of an item by id; returns the leftovers. */
    fun addItem(id: String, count: Int = 1): Int = add(ItemStack(id, count))

    /** Consumes from the held stack (placing a block, eating, shooting). */
    fun consumeHeld(amount: Int = 1): Boolean {
        val s = slots.getOrNull(selected) ?: return false
        s.count -= amount
        if (s.count <= 0) slots[selected] = null
        return true
    }

    /** Drops everything and returns the stacks (death, /clear). */
    fun drain(): List<ItemStack> {
        val out = ArrayList<ItemStack>()
        for (i in slots.indices) {
            val s = slots[i] ?: continue
            out.add(s)
            slots[i] = null
        }
        return out
    }

    /** Java's middle-click / pick block: move an existing stack to the hotbar. */
    fun pickToHotbar(id: String) {
        for (i in 0 until size) {
            val s = slots[i] ?: continue
            if (s.id != id) continue
            if (i in 0 until 9) {
                selected = i
                return
            }
            // Swap into the selected hotbar slot.
            val target = selected.coerceIn(0, 8)
            val swap = slots[target]
            slots[target] = s
            slots[i] = swap
            selected = target
            return
        }
    }

    /** Serialises to a compact string: `slot:id:count|...` (used by the save file). */
    fun serialize(): String = buildString {
        for (i in slots.indices) {
            val s = slots[i] ?: continue
            if (s.isEmpty) continue
            if (isNotEmpty()) append('|')
            append(i).append(':').append(s.id).append(':').append(s.count)
            if (s.damage != 0) append(':').append(s.damage)
        }
    }

    fun load(text: String) {
        slots.fill(null)
        if (text.isBlank()) return
        for (entry in text.split('|')) {
            val parts = entry.split(':')
            if (parts.size < 3) continue
            val index = parts[0].toIntOrNull() ?: continue
            val id = parts[1]
            val count = parts[2].toIntOrNull() ?: 1
            val damage = parts.getOrNull(3)?.toIntOrNull() ?: 0
            if (index in slots.indices) slots[index] = ItemStack(id, count, damage)
        }
    }

    /** Copilot-friendly default kit for a new world (survival-ish starting inventory). */
    fun giveStarterKit() {
        add(ItemStack("stone_pickaxe"))
        add(ItemStack("stone_axe"))
        add(ItemStack("stone_shovel"))
        add(ItemStack("wooden_sword"))
        add(ItemStack("torch", 32))
        add(ItemStack("crafting_table"))
        add(ItemStack("oak_planks", 32))
        add(ItemStack("stone", 32))
        add(ItemStack("bread", 5))
    }
}
