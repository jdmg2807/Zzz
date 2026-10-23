package com.arena.voxel.world

import com.arena.voxel.core.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.floor

class World(val seed: Long) {

    val gen = WorldGen(seed)
    val chunks = ConcurrentHashMap<Long, Chunk>()

    var time = 0f
        private set

    var dayTime = 0.28f

    /** Sets the time of day directly (0 = amanecer, 0.25 = mediodía, 0.5 = anochecer). */
    fun timeOfDay(value: Float) {
        dayTime = value - kotlin.math.floor(value)
    }

    fun keyOf(cx: Int, cz: Int): Long = keyOfStatic(cx, cz)

    fun tick(dt: Float) {
        time += dt
        dayTime += dt / 600f
        if (dayTime > 1f) dayTime -= 1f
        Fluids.tick(this, dt)
        Redstone.tick(this)
    }

    /** Marks a block and its neighbours for re-meshing (used by the fluid simulator). */
    fun markDirtyAround(x: Int, y: Int, z: Int) {
        val cx = x shr 4
        val cz = z shr 4
        chunk(cx, cz)?.dirty = true
        val lx = x and 15
        val lz = z and 15
        if (lx == 0) chunk(cx - 1, cz)?.dirty = true
        if (lx == 15) chunk(cx + 1, cz)?.dirty = true
        if (lz == 0) chunk(cx, cz - 1)?.dirty = true
        if (lz == 15) chunk(cx, cz + 1)?.dirty = true
    }

    fun chunk(cx: Int, cz: Int): Chunk? = chunks[keyOf(cx, cz)]

    fun getOrCreateChunk(cx: Int, cz: Int): Chunk {
        val key = keyOf(cx, cz)
        chunks[key]?.let { return it }
        val c = Chunk(cx, cz)
        chunks[key] = c
        if (!c.generated) gen.generate(c)
        return c
    }

    fun get(x: Int, y: Int, z: Int): Int {
        if (y < 0) return Blocks.index("bedrock")
        if (y >= HEIGHT) return 0
        val cx = x shr 4
        val cz = z shr 4
        val c = chunks[keyOf(cx, cz)] ?: return 0
        return c.get(x and 15, y, z and 15)
    }

    fun set(x: Int, y: Int, z: Int, blockIndex: Int): Boolean {
        if (y < 0 || y >= HEIGHT) return false
        val cx = x shr 4
        val cz = z shr 4
        val c = chunks[keyOf(cx, cz)] ?: return false
        val lx = x and 15
        val lz = z and 15
        c.set(lx, y, lz, blockIndex)
        c.dirty = true
        if (blockIndex != 0 && y > c.maxY) c.maxY = y
        // Light emitters changed: drop cached source lists around the edit.
        for (dx in -1..1) {
            for (dz in -1..1) {
                chunk(cx + dx, cz + dz)?.lightSources = null
            }
        }
        if (lx == 0) chunk(cx - 1, cz)?.dirty = true
        if (lx == 15) chunk(cx + 1, cz)?.dirty = true
        if (lz == 0) chunk(cx, cz - 1)?.dirty = true
        if (lz == 15) chunk(cx, cz + 1)?.dirty = true
        // Redstone and fluids react to every block change (cheap no-op for unrelated blocks).
        if (!silent) {
            Redstone.onChanged(this, x, y, z)
            Fluids.onBlockChanged(this, x, y, z)
        }
        return true
    }

    /** Sets a block without triggering redstone updates (used by the redstone engine itself). */
    fun setSilently(x: Int, y: Int, z: Int, blockIndex: Int): Boolean = withSilence { set(x, y, z, blockIndex) }

    private var silenceDepth = 0

    private inline fun <T> withSilence(block: () -> T): T {
        silenceDepth++
        return try {
            block()
        } finally {
            silenceDepth--
        }
    }

    private val silent: Boolean get() = silenceDepth > 0

    fun set(x: Int, y: Int, z: Int, id: String): Boolean = set(x, y, z, Blocks.index(id))

    fun isSolid(x: Int, y: Int, z: Int): Boolean = Blocks.isSolid(get(x, y, z))

    fun isOpaque(x: Int, y: Int, z: Int): Boolean = Blocks.isOpaque(get(x, y, z))

    fun isLiquid(x: Int, y: Int, z: Int): Boolean = Blocks.isLiquid(get(x, y, z))

    fun isAir(x: Int, y: Int, z: Int): Boolean = get(x, y, z) == 0

    fun highestSolidY(x: Int, z: Int): Int {
        val c = chunk(x shr 4, z shr 4)
        if (c == null) return gen.highestAt(x, z)
        for (y in (c.maxY).coerceAtMost(HEIGHT - 1) downTo 0) {
            if (Blocks.isSolid(c.get(x and 15, y, z and 15))) return y
        }
        return 0
    }

    fun fill(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, id: String) {
        val index = Blocks.index(id)
        for (y in minOf(y1, y2)..maxOf(y1, y2)) {
            for (z in minOf(z1, z2)..maxOf(z1, z2)) {
                for (x in minOf(x1, x2)..maxOf(x1, x2)) set(x, y, z, index)
            }
        }
    }

    fun save(
        dir: File,
        player: com.arena.voxel.player.Player,
        hotbar: List<String>,
        selected: Int,
        inventory: String = ""
    ) {
        try {
            dir.mkdirs()
            File(dir, "level.dat").outputStream().use { fos ->
                GZIPOutputStream(fos).use { gz ->
                    DataOutputStream(gz).use { out ->
                        out.writeInt(MAGIC4)
                        out.writeInt(HEIGHT)
                        out.writeLong(seed)
                        // Palette of block ids, so saves survive palette changes between versions.
                        val palette = Blocks.ids()
                        out.writeInt(palette.size)
                        for (id in palette) out.writeUTF(id)
                        out.writeFloat(dayTime)
                        out.writeDouble(player.x)
                        out.writeDouble(player.y)
                        out.writeDouble(player.z)
                        out.writeFloat(player.yaw)
                        out.writeFloat(player.pitch)
                        out.writeInt(selected)
                        out.writeInt(hotbar.size)
                        for (h in hotbar) out.writeUTF(h)
                        // v1.7: full player state (inventory, health, hunger) as one string.
                        out.writeUTF(
                            "$inventory#${player.health.toInt()},${player.food}," +
                                "${player.air},${player.experience.toInt()},${player.level}"
                        )
                        val list = chunks.values.filter { it.generated }
                        out.writeInt(list.size)
                        for (c in list) {
                            out.writeInt(c.cx)
                            out.writeInt(c.cz)
                            // v4: block ids are shorts, so the palette can hold thousands of blocks.
                            val bytes = java.nio.ByteBuffer.allocate(c.blocks.size * 2)
                                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                            bytes.asShortBuffer().put(c.blocks)
                            out.write(bytes.array())
                        }
                    }
                }
            }
            Log.i("Mundo guardado (${chunks.size} chunks)")
        } catch (t: Throwable) {
            Log.e("No se pudo guardar el mundo", t)
        }
    }

    fun chunkCoord(v: Double): Int = floor(v / SIZE).toInt()

    companion object {
        const val SIZE = 16
        /**
         * Tall columns: Minecraft y = -64 lands at 0, so a 256 block column covers
         * y = -64..191. Castles, towers and mountains of imported worlds no longer get cut off
         * (with 144 they were clipped above y = 79). Cost: 64 KB per chunk of block data.
         */
        const val HEIGHT = 256

        /** Minecraft y = 0 lands here (imported worlds occupy -64..191). */
        const val MC_Y_OFFSET = 64

        fun keyOfStatic(cx: Int, cz: Int): Long =
            (cx.toLong() shl 32) xor (cz.toLong() and 0xffffffffL)

        /** "VCX1": original (height 80). "VCX2": stores height. "VCX3": + player state. */
        private const val MAGIC = 0x56435831
        private const val MAGIC2 = 0x56435832
        private const val MAGIC3 = 0x56435833
        private const val MAGIC4 = 0x56435834

        class Loaded(
            val world: World,
            val player: com.arena.voxel.player.Player,
            val hotbar: List<String>,
            val selected: Int,
            val legacy: Boolean,
            val inventory: String = "",
            val health: Float = 20f,
            val food: Int = 20,
            val air: Int = 300,
            val experience: Int = 0,
            val level: Int = 0
        )

        /** Loads a saved world. Returns null when there is no save or it is corrupt. */
        fun loadFrom(dir: File): Loaded? {
            val f = File(dir, "level.dat")
            if (!f.isFile) return null
            return try {
                GZIPInputStream(f.inputStream()).use { gz ->
                    DataInputStream(gz).use { inp ->
                        val first = inp.readInt()
                        val v4 = first == MAGIC4
                        val v3 = first == MAGIC3 || v4
                        val modern = v3 || first == MAGIC2 || first == MAGIC
                        var savedHeight = 80
                        if (v3 || first == MAGIC2) savedHeight = inp.readInt()
                        val seed = if (modern) {
                            inp.readLong()
                        } else {
                            ((first.toLong() shl 32) or (inp.readInt().toLong() and 0xffffffffL))
                        }
                        var remap: IntArray? = null
                        if (modern) {
                            val paletteSize = inp.readInt()
                            val saved = ArrayList<String>(paletteSize)
                            for (i in 0 until paletteSize) saved.add(inp.readUTF())
                            // Map old palette index -> current palette index by name.
                            remap = IntArray(paletteSize)
                            for (i in 0 until paletteSize) {
                                remap[i] = Blocks.index(saved[i])
                            }
                        }
                        val world = World(seed)
                        val dt = inp.readFloat()
                        val px = inp.readDouble()
                        val py = inp.readDouble()
                        val pz = inp.readDouble()
                        val yaw = inp.readFloat()
                        val pitch = inp.readFloat()
                        val selected = inp.readInt()
                        val hotCount = inp.readInt()
                        val hotbar = ArrayList<String>()
                        for (i in 0 until hotCount) hotbar.add(inp.readUTF())
                        var invText = ""
                        var health = 20f
                        var food = 20
                        var air = 300
                        var xp = 0
                        var lvl = 0
                        if (v3) {
                            val packed = inp.readUTF()
                            val bar = packed.indexOf('#')
                            invText = if (bar >= 0) packed.substring(0, bar) else packed
                            if (bar >= 0) {
                                val nums = packed.substring(bar + 1).split(',')
                                health = nums.getOrNull(0)?.toFloatOrNull() ?: 20f
                                food = nums.getOrNull(1)?.toIntOrNull() ?: 20
                                air = nums.getOrNull(2)?.toIntOrNull() ?: 300
                                xp = nums.getOrNull(3)?.toIntOrNull() ?: 0
                                lvl = nums.getOrNull(4)?.toIntOrNull() ?: 0
                            }
                        }
                        val count = inp.readInt()
                        for (i in 0 until count) {
                            val cx = inp.readInt()
                            val cz = inp.readInt()
                            val c = Chunk(cx, cz)
                            if (savedHeight == HEIGHT) {
                                if (v4) {
                                    val rawBytes = ByteArray(c.blocks.size * 2)
                                    var read = 0
                                    while (read < rawBytes.size) {
                                        val r = inp.read(rawBytes, read, rawBytes.size - read)
                                        if (r <= 0) break
                                        read += r
                                    }
                                    java.nio.ByteBuffer.wrap(rawBytes)
                                        .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                                        .asShortBuffer()
                                        .get(c.blocks)
                                } else {
                                    val rawBytes = ByteArray(c.blocks.size)
                                    var read = 0
                                    while (read < rawBytes.size) {
                                        val r = inp.read(rawBytes, read, rawBytes.size - read)
                                        if (r <= 0) break
                                        read += r
                                    }
                                    for (i in rawBytes.indices) c.blocks[i] = (rawBytes[i].toInt() and 0xFF).toShort()
                                }
                            } else {
                                // Older saves are shorter: read them and place the data at the
                                // bottom of the (now taller) chunk.
                                val old = ByteArray(16 * 16 * savedHeight)
                                var read = 0
                                while (read < old.size) {
                                    val r = inp.read(old, read, old.size - read)
                                    if (r <= 0) break
                                    read += r
                                }
                                val copy = minOf(old.size, c.blocks.size)
                                for (i in 0 until copy) c.blocks[i] = (old[i].toInt() and 0xFF).toShort()
                            }
                            remap?.let { map ->
                                for (i in c.blocks.indices) {
                                    val old = c.blocks[i].toInt() and 0xFFFF
                                    val mapped = if (old < map.size) map[old] else 0
                                    c.blocks[i] = mapped.toShort()
                                }
                            }
                            c.generated = true
                            c.dirty = true
                            c.maxY = c.blocks.indexOfLast { it != 0.toShort() }.let { idx ->
                                if (idx < 0) 0 else idx / (16 * 16)
                            }
                            c.lightSources = null
                            world.chunks[world.keyOf(cx, cz)] = c
                        }
                        world.dayTime = dt
                        val p = com.arena.voxel.player.Player(px, py, pz)
                        p.yaw = yaw
                        p.pitch = pitch
                        p.health = health
                        p.food = food
                        p.air = air
                        p.experience = xp.toFloat()
                        p.level = lvl
                        Loaded(world, p, hotbar, selected, !modern, invText, health, food, air, xp, lvl)
                    }
                }
            } catch (t: Throwable) {
                Log.e("No se pudo cargar el mundo", t)
                null
            }
        }
    }
}
