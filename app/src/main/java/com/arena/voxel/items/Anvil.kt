package com.arena.voxel.items

/**
 * Anvil rules from the Minecraft Wiki (`Anvil_mechanics`):
 *
 * * Combining two damaged items of the same type adds their remaining durability plus 12 % of
 *   the maximum (the "repair bonus"), capped at the maximum.
 * * The work penalty grows by 2 per prior repair and doubles: 0, 1, 3, 7, 15, 31 (capped at 39).
 * * Renaming costs 1 level (plus the penalty); repairing with a material costs 1 level per unit.
 *
 * Enchantments are not part of this engine yet, so the cost only accounts for durability, rename
 * and the sacrifice bonus of the second item (Java: +2 levels when the second item is a tool).
 */
object Anvil {

    /** Java durability per tool material; 0 for anything that does not wear out. */
    fun maxDamage(id: String): Int {
        val def = Items.def(id) ?: return 0
        if (def.toolType.isEmpty()) return 0
        return when (def.tier) {
            1 -> 59      // madera
            2 -> 131     // piedra
            3 -> 250     // hierro
            4 -> 1561    // diamante
            5 -> 2031    // netherita
            else -> 0
        }
    }

    fun isDamageable(id: String): Boolean = maxDamage(id) > 0

    /** Java's "prior work penalty" for an item that has been through the anvil N times. */
    private fun workPenalty(repairs: Int): Int = when {
        repairs <= 0 -> 0
        else -> minOf(1 shl (repairs - 1), 39)
    }

    /** Returns the resulting stack id, or null when the combination is not allowed. */
    fun combine(a: ItemStack?, b: ItemStack?, newName: String = ""): String? {
        if (a == null && b == null) return null
        val base = a ?: b ?: return null
        if (a == null || b == null) {
            // Rename only.
            return if (newName.isNotBlank()) base.id else null
        }
        if (a.id != b.id) return null
        if (!isDamageable(a.id)) {
            // Stacking two identical plain items is not an anvil operation.
            return null
        }
        val max = maxDamage(a.id)
        val left = (max - a.damage).coerceAtLeast(0)
        val right = (max - b.damage).coerceAtLeast(0)
        if (left >= max && right >= max && newName.isBlank()) return null
        return a.id
    }

    /** Level cost of the operation; 0 means the anvil rejects it. */
    fun cost(a: ItemStack?, b: ItemStack?, newName: String = ""): Int {
        if (a == null && b == null) return 0
        var cost = 0
        if (newName.isNotBlank() && newName != Items.nameOf((a ?: b)!!.id)) cost += 1
        if (a != null && b != null && a.id == b.id && isDamageable(a.id)) {
            val max = maxDamage(a.id)
            val left = (max - a.damage).coerceAtLeast(0)
            val right = (max - b.damage).coerceAtLeast(0)
            cost += 2
            if (left >= max && right >= max) return 0
        } else if (a != null && b != null) {
            return 0
        }
        return cost
    }

    /** Applies the operation in place and returns the new damage value. */
    fun applyDamage(a: ItemStack?, b: ItemStack?, newName: String): Int? {
        val base = a ?: return null
        if (b == null) return base.damage
        if (base.id != b.id || !isDamageable(base.id)) return null
        val max = maxDamage(base.id)
        val left = (max - base.damage).coerceAtLeast(0)
        val right = (max - b.damage).coerceAtLeast(0)
        val combined = (left + right + max * 12 / 100).coerceAtMost(max)
        return max - combined
    }
}
