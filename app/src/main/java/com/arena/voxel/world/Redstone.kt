package com.arena.voxel.world

/**
 * Redstone engine.
 *
 * Model, as described by the public wiki:
 *  - 16 signal levels (0..15), power drops by 1 per cable block and by 1 extra when the signal
 *    has to climb a block.
 *  - sources emit 15: redstone block, lever/button on, redstone torch (unless inverted),
 *    a powered repeater/comparator output, and a wire feeding a component.
 *  - a redstone tick is 2 game ticks (0.1 s); repeaters add 1..4 redstone ticks of delay.
 *
 * Implementation: the wire stores its level in its own block id (`redstone_wire_N`), so the
 * mesher needs no per-block tint and the save file keeps the state for free. Recomputing a
 * network means a flood fill from the changed block, then a second pass that updates every
 * component touching the network. That is enough for realistic contraptions (doors, lamps,
 * clocks, counters) and stays cheap on a phone.
 */
object Redstone {

    private const val MAX_NETWORK = 2048

    /** Scheduled state changes: key, target block index, due time (nanoseconds). */
    private val pending = ArrayList<LongArray>()

    private fun keyOf(x: Int, y: Int, z: Int): Long = key(x, y, z)

    /** Delay of a repeater in redstone ticks (1..4); 1 for comparators and anything unknown. */
    fun repeaterDelay(id: String): Int {
        if (!id.startsWith("repeater")) return 1
        val d = id.substringAfterLast("_d", "").toIntOrNull() ?: 1
        return d.coerceIn(1, 4)
    }

    fun repeaterId(on: Boolean, delay: Int): String {
        val d = delay.coerceIn(1, 4)
        return if (on) "repeater_on_d$d" else "repeater_off_d$d"
    }

    private fun schedule(world: World, x: Int, y: Int, z: Int, blockId: String, seconds: Float) {
        val k = keyOf(x, y, z)
        pending.removeAll { it[0] == k }
        pending.add(
            longArrayOf(
                k, Blocks.index(blockId).toLong(),
                System.nanoTime() + (seconds * 1_000_000_000L).toLong()
            )
        )
    }

    /** Runs the delayed flips (repeater/comparator delays). Called every frame by [World.tick]. */
    fun tick(world: World) {
        if (pending.isEmpty()) return
        val now = System.nanoTime()
        var i = 0
        while (i < pending.size) {
            val entry = pending[i]
            if (entry[2] > now) {
                i++
                continue
            }
            pending.removeAt(i)
            val x = unkeyX(entry[0])
            val y = unkeyY(entry[0])
            val z = unkeyZ(entry[0])
            val current = Blocks.get(world.get(x, y, z)).id
            if (!current.startsWith("repeater") && !current.startsWith("comparator")) continue
            val target = Blocks.get(entry[1].toInt()).id
            if (current == target) continue
            world.setSilently(x, y, z, entry[1].toInt())
            recomputeBounded(world, x, y, z)
        }
    }

    /** Clears the delay queue (new world / world unloaded). */
    fun reset() = pending.clear()

    fun isWire(id: String): Boolean = id.startsWith("redstone_wire_")

    fun isComponent(id: String): Boolean =
        id.startsWith("lever") || id.startsWith("stone_button") ||
            id.startsWith("redstone_torch") || id.startsWith("repeater") ||
            id.startsWith("comparator") || id.startsWith("redstone_lamp") ||
            id.startsWith("piston") || id.startsWith("observer") || id == "redstone_block"

    /** True when a block being placed/removed can influence a circuit. */
    fun isRelevant(id: String): Boolean = isWire(id) || isComponent(id)

    /** Source strength of a block id, 0 when it emits nothing. */
    private fun sourcePower(id: String): Int = when {
        id == "redstone_block" -> 15
        id == "lever_on" -> 15
        id == "stone_button_on" -> 15
        id == "redstone_torch_on" -> 15
        id.startsWith("repeater_on") -> 15
        id == "comparator_on" -> 15
        else -> 0
    }

    private fun key(x: Int, y: Int, z: Int): Long =
        (x.toLong() and 0x3FFFFFF) or ((z.toLong() and 0x3FFFFFF) shl 26) or ((y.toLong() and 0x3FFL) shl 52)

    /**
     * Recomputes the circuit around (x, y, z).
     *
     * @return number of blocks whose state changed (for logging).
     */
    fun recompute(world: World, x: Int, y: Int, z: Int): Int {
        // ---- 1. collect the connected wire network (flood fill, 6-neighbour + diagonal rails)
        val wires = LinkedHashSet<Long>()
        val queue = ArrayDeque<IntArray>()
        val startId = Blocks.get(world.get(x, y, z)).id
        if (isWire(startId)) queue.add(intArrayOf(x, y, z))
        // A component directly next to the change seeds the search too.
        for (d in DIRECTIONS) {
            val nx = x + d[0]
            val ny = y + d[1]
            val nz = z + d[2]
            val id = Blocks.get(world.get(nx, ny, nz)).id
            if (isWire(id)) queue.add(intArrayOf(nx, ny, nz))
        }

        while (queue.isNotEmpty() && wires.size < MAX_NETWORK) {
            val cell = queue.removeFirst()
            val k = key(cell[0], cell[1], cell[2])
            if (!wires.add(k)) continue
            for (d in FLAT_DIRECTIONS) {
                for (dy in intArrayOf(0, 1, -1)) {
                    val nx = cell[0] + d[0]
                    val ny = cell[1] + dy
                    val nz = cell[2] + d[2]
                    // Climbing: the wire must be reachable (block above the current one is free).
                    if (dy == 1 && !world.isAir(cell[0], cell[1] + 1, cell[2])) continue
                    if (world.isAir(nx, ny, nz)) continue
                    val id = Blocks.get(world.get(nx, ny, nz)).id
                    if (isWire(id) && !wires.contains(key(nx, ny, nz))) {
                        queue.add(intArrayOf(nx, ny, nz))
                    }
                }
            }
        }

        var changed = 0

        // ---- 2. every external source touching the network
        val power = HashMap<Long, Int>()
        val sources = ArrayList<IntArray>()
        for (k in wires) {
            val wx = unkeyX(k)
            val wy = unkeyY(k)
            val wz = unkeyZ(k)
            for (d in DIRECTIONS) {
                val id = Blocks.get(world.get(wx + d[0], wy + d[1], wz + d[2])).id
                if (sourcePower(id) > 0) sources.add(intArrayOf(wx + d[0], wy + d[1], wz + d[2]))
            }
        }

        // ---- 3. multi-source BFS: 15 - distance, climbing costs one extra
        val bfs = ArrayDeque<IntArray>()
        for (s in sources) {
            if (power.getOrDefault(key(s[0], s[1], s[2]), -1) < 15) {
                power[key(s[0], s[1], s[2])] = 15
            }
            for (d in DIRECTIONS) {
                val nx = s[0] + d[0]
                val ny = s[1] + d[1]
                val nz = s[2] + d[2]
                bfs.add(intArrayOf(nx, ny, nz, 15))
            }
        }
        while (bfs.isNotEmpty()) {
            val cell = bfs.removeFirst()
            val px = cell[0]
            val py = cell[1]
            val pz = cell[2]
            var level = cell[3]
            val id = Blocks.get(world.get(px, py, pz)).id
            if (!isWire(id)) continue
            level -= 1
            if (level <= 0) continue
            val k = key(px, py, pz)
            if (power.getOrDefault(k, 0) >= level) continue
            power[k] = level
            for (d in FLAT_DIRECTIONS) {
                for (dy in intArrayOf(0, 1, -1)) {
                    val nx = px + d[0]
                    val ny = py + dy
                    val nz = pz + d[2]
                    if (dy == 1 && !world.isAir(px, py + 1, pz)) continue
                    if (dy == -1 && !world.isAir(px, py, pz)) continue
                    val nid = Blocks.get(world.get(nx, ny, nz)).id
                    if (!isWire(nid)) continue
                    bfs.add(intArrayOf(nx, ny, nz, if (dy == 0) level else level - 1))
                }
            }
        }

        // ---- 4. write the wire levels back as block ids
        for (k in wires) {
            val wx = unkeyX(k)
            val wy = unkeyY(k)
            val wz = unkeyZ(k)
            val level = (power[k] ?: 0).coerceIn(0, 15)
            val wanted = Blocks.index(Blocks.wireId(level))
            if (world.get(wx, wy, wz) != wanted) {
                world.setSilently(wx, wy, wz, wanted)
                changed++
            }
        }

        // ---- 5. update every component that touches the network
        val seenComponents = HashSet<Long>()
        fun updateAt(cx: Int, cy: Int, cz: Int) {
            val id = Blocks.get(world.get(cx, cy, cz)).id
            if (!isComponent(id)) return
            if (!seenComponents.add(key(cx, cy, cz))) return
            var input = 0
            for (d in DIRECTIONS) {
                val nid = Blocks.get(world.get(cx + d[0], cy + d[1], cz + d[2])).id
                if (isWire(nid)) input = maxOf(input, Blocks.wireLevel(nid))
                else input = maxOf(input, sourcePower(nid))
            }
            // Repeaters and comparators do not flip instantly: they schedule their new state
            // after 1..4 redstone ticks (0.1..0.4 s), which is what makes clocks possible.
            if (id.startsWith("repeater")) {
                val delay = repeaterDelay(id)
                val on = id.contains("_on")
                val wanted = input > 0
                if (wanted != on) {
                    schedule(world, cx, cy, cz, repeaterId(wanted, delay), delay * 0.1f)
                } else {
                    pending.removeAll { it[0] == keyOf(cx, cy, cz) }
                }
                return
            }
            if (id.startsWith("comparator")) {
                val on = id.contains("_on")
                val wanted = input > 0
                if (wanted != on) {
                    schedule(world, cx, cy, cz, if (wanted) "comparator_on" else "comparator_off", 0.1f)
                } else {
                    pending.removeAll { it[0] == keyOf(cx, cy, cz) }
                }
                return
            }
            val newId = when {
                id.startsWith("redstone_lamp") -> if (input > 0) "redstone_lamp_on" else "redstone_lamp_off"
                id.startsWith("piston") -> if (input > 0) "piston_extended" else "piston"
                id.startsWith("observer") -> if (input > 0) "observer_on" else "observer"
                // A redstone torch burns out when its supporting block is powered (inversion).
                id == "redstone_torch_on" && input > 0 -> "redstone_torch_off"
                id == "redstone_torch_off" && input == 0 -> "redstone_torch_on"
                else -> id
            }
            if (newId != id) {
                world.setSilently(cx, cy, cz, Blocks.index(newId))
                changed++
                // The change may seed another network (repeater chains, torch towers).
                if (newId != "redstone_torch_off" && newId != "repeater_off") {
                    changed += recomputeBounded(world, cx, cy, cz)
                }
            }
        }

        for (k in wires) {
            val wx = unkeyX(k)
            val wy = unkeyY(k)
            val wz = unkeyZ(k)
            for (d in DIRECTIONS) updateAt(wx + d[0], wy + d[1], wz + d[2])
        }
        // Components directly adjacent to the edited block.
        for (d in DIRECTIONS) updateAt(x + d[0], y + d[1], z + d[2])
        return changed
    }

    private var recursionGuard = 0

    private fun recomputeBounded(world: World, x: Int, y: Int, z: Int): Int {
        if (recursionGuard > 6) return 0
        recursionGuard++
        return try {
            recompute(world, x, y, z)
        } finally {
            recursionGuard--
        }
    }

    /** Called by the world after any block change. */
    fun onChanged(world: World, x: Int, y: Int, z: Int) {
        recursionGuard = 0
        val id = Blocks.get(world.get(x, y, z)).id
        var relevant = isRelevant(id)
        if (!relevant) {
            for (d in DIRECTIONS) {
                val nid = Blocks.get(world.get(x + d[0], y + d[1], z + d[2])).id
                if (isRelevant(nid)) {
                    relevant = true
                    break
                }
            }
        }
        if (!relevant) return
        val changed = recompute(world, x, y, z)
        if (changed > 0) {
            com.arena.voxel.core.Logs.info("REDSTONE", "Circuito actualizado: $changed bloques en $x/$y/$z")
        }
    }

    /** Toggles a component the player tapped (lever, button). */
    fun interact(world: World, x: Int, y: Int, z: Int): Boolean {
        val id = Blocks.get(world.get(x, y, z)).id
        // Tapping a repeater cycles its delay 1 -> 2 -> 3 -> 4 -> 1, like right-clicking it.
        if (id.startsWith("repeater")) {
            val delay = repeaterDelay(id)
            val next = delay % 4 + 1
            val on = id.contains("_on")
            world.set(x, y, z, Blocks.index(repeaterId(on, next)))
            com.arena.voxel.Game.chat("Retardo del repetidor: $next ticks (${next * 2} de juego)")
            return true
        }
        val (from, to) = when (id) {
            "lever_off" -> "lever_off" to "lever_on"
            "lever_on" -> "lever_on" to "lever_off"
            "stone_button_off" -> "stone_button_off" to "stone_button_on"
            "stone_button_on" -> "stone_button_on" to "stone_button_off"
            else -> return false
        }
        world.set(x, y, z, Blocks.index(to))
        com.arena.voxel.core.Logs.info("REDSTONE", "$from -> $to en $x/$y/$z")
        return true
    }

    private val DIRECTIONS = arrayOf(
        intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
        intArrayOf(0, 1, 0), intArrayOf(0, -1, 0),
        intArrayOf(0, 0, 1), intArrayOf(0, 0, -1)
    )

    private val FLAT_DIRECTIONS = arrayOf(
        intArrayOf(1, 0, 0), intArrayOf(-1, 0, 0),
        intArrayOf(0, 0, 1), intArrayOf(0, 0, -1)
    )

    private fun unkeyX(k: Long): Int = (k and 0x3FFFFFF).toInt().let { if (it and 0x2000000 != 0) it - 0x4000000 else it }
    private fun unkeyY(k: Long): Int = ((k ushr 52) and 0x3FF).toInt()
    private fun unkeyZ(k: Long): Int = ((k ushr 26) and 0x3FFFFFF).toInt().let { if (it and 0x2000000 != 0) it - 0x4000000 else it }
}
