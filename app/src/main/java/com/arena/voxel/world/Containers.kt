package com.arena.voxel.world

import com.arena.voxel.items.Inventory
import com.arena.voxel.items.ItemStack
import com.arena.voxel.items.Items
import com.arena.voxel.items.Recipes
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Block entities with storage: chests (27 + the player's 36) and furnaces (input, fuel, output).
 *
 * Java keeps these in the chunk's `block_entities`; here they live in a map keyed by world
 * position and are written to `containers.dat` next to the world, so the contents survive a
 * save/load exactly like the real thing.
 */
object Containers {

    private val chests = HashMap<Long, Inventory>()
    private val furnaces = HashMap<Long, Furnace>()

    fun key(x: Int, y: Int, z: Int): Long =
        (x.toLong() and 0x3FFFFFF) or ((z.toLong() and 0x3FFFFFF) shl 26) or
            ((y.toLong() and 0x3FFF) shl 54)

    fun unkeyX(k: Long): Int = (k and 0x3FFFFFF).toInt().let { if (it and 0x2000000 != 0) it - 0x4000000 else it }
    fun unkeyY(k: Long): Int = ((k ushr 54) and 0x3FFF).toInt()
    fun unkeyZ(k: Long): Int = ((k ushr 26) and 0x3FFFFFF).toInt().let { if (it and 0x2000000 != 0) it - 0x4000000 else it }

    fun chestAt(x: Int, y: Int, z: Int): Inventory =
        chests.getOrPut(key(x, y, z)) { Inventory(27) }

    fun furnaceAt(x: Int, y: Int, z: Int): Furnace =
        furnaces.getOrPut(key(x, y, z)) { Furnace() }

    fun chestOrNull(x: Int, y: Int, z: Int): Inventory? = chests[key(x, y, z)]

    fun furnaceOrNull(x: Int, y: Int, z: Int): Furnace? = furnaces[key(x, y, z)]

    /** Snapshot of the furnace positions currently loaded (safe against concurrent edits). */
    fun furnaceKeys(): List<Long> = furnaces.keys.toList()

    fun clear() {
        chests.clear()
        furnaces.clear()
    }

    /**
     * A furnace, ticking at 20 Hz like Java: 200 ticks (10 s) per item, fuel from the table in
     * [Recipes.FUEL], experience awarded to the player who takes the result (handled by the UI).
     */
    class Furnace {
        /** 0 = input, 1 = fuel, 2 = output. */
        val inventory = Inventory(3)
        var burnTime = 0
        var burnTotal = 0
        var cookTime = 0

        val isBurning: Boolean get() = burnTime > 0
        val progress: Float get() = (cookTime / 200f).coerceIn(0f, 1f)

        /** @return true when something changed (the UI refreshes on it). */
        fun tick(): Boolean {
            var changed = false
            val input = inventory.get(0)
            val fuel = inventory.get(1)
            val output = inventory.get(2)
            val recipe = input?.let { Recipes.smelt(it.id) }

            if (burnTime > 0) {
                burnTime--
                changed = true
            }

            val canSmelt = recipe != null &&
                (output == null || (output.id == recipe.first && output.count < Items.maxStack(recipe.first)))

            if (burnTime == 0 && canSmelt && fuel != null) {
                val burn = Recipes.FUEL[fuel.id] ?: 0
                if (burn > 0) {
                    burnTime = burn
                    burnTotal = burn
                    inventory.shrink(1, 1)
                    changed = true
                }
            }

            if (burnTime > 0 && canSmelt) {
                cookTime++
                changed = true
                if (cookTime >= 200) {
                    cookTime = 0
                    val result = recipe ?: return changed
                    if (output == null) {
                        inventory.set(2, ItemStack(result.first, 1))
                    } else {
                        output.count += 1
                    }
                    input?.let { inventory.shrink(0, 1) }
                }
            } else if (cookTime > 0) {
                cookTime = (cookTime - 2).coerceAtLeast(0)
                changed = true
            }
            return changed
        }
    }

    // ------------------------------------------------------------------ persistence

    fun save(dir: File) {
        try {
            dir.mkdirs()
            File(dir, "containers.dat").outputStream().use { fos ->
                GZIPOutputStream(fos).use { gz ->
                    DataOutputStream(gz).use { out ->
                        out.writeInt(MAGIC)
                        out.writeInt(chests.size)
                        for ((k, inv) in chests) {
                            out.writeLong(k)
                            out.writeUTF(inv.serialize())
                        }
                        out.writeInt(furnaces.size)
                        for ((k, f) in furnaces) {
                            out.writeLong(k)
                            out.writeUTF(f.inventory.serialize())
                            out.writeInt(f.burnTime)
                            out.writeInt(f.burnTotal)
                            out.writeInt(f.cookTime)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            com.arena.voxel.core.Log.w("No se pudieron guardar los contenedores: ${t.message}")
        }
    }

    fun load(dir: File) {
        val f = File(dir, "containers.dat")
        if (!f.isFile) return
        try {
            GZIPInputStream(f.inputStream()).use { gz ->
                DataInputStream(gz).use { inp ->
                    if (inp.readInt() != MAGIC) return
                    val chestCount = inp.readInt()
                    repeat(chestCount) {
                        val k = inp.readLong()
                        val inv = Inventory(27)
                        inv.load(inp.readUTF())
                        chests[k] = inv
                    }
                    val furnaceCount = inp.readInt()
                    repeat(furnaceCount) {
                        val k = inp.readLong()
                        val furnace = Furnace()
                        furnace.inventory.load(inp.readUTF())
                        furnace.burnTime = inp.readInt()
                        furnace.burnTotal = inp.readInt()
                        furnace.cookTime = inp.readInt()
                        furnaces[k] = furnace
                    }
                }
            }
        } catch (t: Throwable) {
            com.arena.voxel.core.Log.w("No se pudieron cargar los contenedores: ${t.message}")
        }
    }

    private const val MAGIC = 0x56434331 // "VCC1"
}
