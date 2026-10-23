package com.arena.voxel.world

import com.arena.voxel.core.Log
import com.arena.voxel.core.VanillaAssets
import org.json.JSONObject

/**
 * Propiedades reales de **todos** los bloques del juego, leídas de `assets/vanilla/blocks.json`.
 *
 * El archivo lo genera `tools/gen_blocks.py` a partir de `minecraft-data` (MIT, PrismarineJS),
 * que publica la tabla de bloques de Minecraft Java Edition. Antes cada bloque de [Blocks] tenía
 * la dureza, la luz y la herramienta escritas a mano, así que solo una minoría era correcta y
 * los bloques generados por [BlockGen] compartían valores genéricos. Con esta tabla:
 *
 *  * el tiempo de picado es el del juego (dureza × 1,5 s con la herramienta correcta, × 5 s sin
 *    ella), incluidos obsidiana (50 s), piedra (1,5 s), tierra (0,5 s) y antorcha (instantánea);
 *  * la luz que emite cada bloque es la real (antorcha 14, piedra luminosa 15, lava 15…);
 *  * la herramienta y el **nivel mínimo** para que el bloque suelte su drop son los del juego
 *    (hierro → piedra, diamante → hierro, obsidiana → diamante);
 *  * el tamaño de pila es el real (perlas, cubos, pociones a 1/16).
 *
 * Aplicar la tabla es una mutación de la paleta al arrancar: no hay cambios en el formato del
 * mundo ni en el guardado.
 */
object BlockData {

    /** Una entrada de la tabla. [tier] 0 = a mano, 1 madera, 2 piedra, 3 hierro, 4 diamante, 5 netherita. */
    class Info(
        val hardness: Float,
        val resistance: Float,
        val light: Int,
        val filterLight: Int,
        val stackSize: Int,
        val tool: String,
        val tier: Int,
        val transparent: Boolean
    )

    private val table = HashMap<String, Info>(1500)
    private var loaded = false
    private var attempts = 0

    fun info(id: String): Info? {
        if (!loaded) load()
        return table[id]
    }

    fun isLoaded(): Boolean = loaded

    /** Reads the table once. Safe to call from any thread; failures leave the hand-written values. */
    fun load(): Int {
        if (loaded) return table.size
        if (attempts >= 3) return 0
        attempts++
        try {
            val text = VanillaAssets.readText("blocks.json") ?: return 0
            val root = JSONObject(text)
            val keys = root.keys()
            while (keys.hasNext()) {
                val name = keys.next()
                val arr = root.optJSONArray(name) ?: continue
                if (arr.length() < 8) continue
                table[name] = Info(
                    hardness = arr.optDouble(0, 1.0).toFloat(),
                    resistance = arr.optDouble(1, 0.0).toFloat(),
                    light = arr.optInt(2, 0),
                    filterLight = arr.optInt(3, 15),
                    stackSize = arr.optInt(4, 64),
                    tool = arr.optString(5, ""),
                    tier = arr.optInt(6, 0),
                    transparent = arr.optInt(7, 0) == 1
                )
            }
        } catch (t: Throwable) {
            Log.w("No se pudo leer blocks.json: ${t.message}")
        }
        // Solo se marca como cargado si de verdad se leyó algo: si el contexto de Android aún
        // no estaba listo, la siguiente llamada vuelve a intentarlo.
        if (table.isNotEmpty()) {
            loaded = true
            Log.i("Propiedades de bloques de vanilla cargadas: ${table.size}")
        }
        return table.size
    }

    /**
     * Overrides the palette in place. Only touches blocks that exist in both sides, and never
     * makes a solid block non-solid or the other way round: geometry stays a decision of the
     * engine, this is about numbers.
     */
    fun applyTo(blocks: List<Block>): Int {
        load()
        if (table.isEmpty()) return 0
        var applied = 0
        for (block in blocks) {
            val info = table[block.id] ?: table[ALIASES[block.id]] ?: continue
            if (info.hardness != 0f || info.tier > 0) block.hardness = info.hardness
            block.resistance = info.resistance
            if (info.light > 0) block.light = info.light
            if (info.tool.isNotEmpty()) block.tool = info.tool
            block.harvestTier = info.tier
            if (info.stackSize in 1..64) block.maxStack = info.stackSize
            applied++
        }
        if (applied > 0) Log.i("Propiedades aplicadas a $applied bloques")
        return applied
    }

    /** Engine ids that differ from the vanilla id they represent. */
    private val ALIASES = mapOf(
        "grass" to "grass_block",
        "planks" to "oak_planks",
        "log" to "oak_log",
        "leaves" to "oak_leaves",
        "tall_grass" to "short_grass",
        "brick" to "bricks",
        "snow" to "snow_block"
    )
}
