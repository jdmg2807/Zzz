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
        private set

    fun keyOf(cx: Int, cz: Int): Long = (cx.toLong() shl 32) xor (cz.toLong() and 0xffffffffL)

    fun tick(dt: Float) {
        time += dt
        dayTime += dt / 600f
        if (dayTime > 1f) dayTime -= 1f
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
        return true
    }

    fun set(x: Int, y: Int, z: Int, id: String): Boolean = set(x, y, z, Blocks.index(id))

    fun isSolid(x: Int, y: Int, z: Int): Boolean = Blocks.isSolid(get(x, y, z))

    fun isOpaque(x: Int, y: Int, z: Int): Boolean = Blocks.isOpaque(get(x, y, z))

    fun isLiquid(x: Int, y: Int, z: Int): Boolean = Blocks.isLiquid(get(x, y, z))

    fun isAir(x: Int, y: Int, z: Int): Boolean = get(x, y, z) == 0

    fun highestSolidY(x: Int, z: Int): Int {
        val c = chunk(x shr 4, z shr 4)
        if (c == null) return gen.highestAt(x, z)
        for (y in HEIGHT - 1 downTo 0) {
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

    fun save(dir: File, player: com.arena.voxel.player.Player, hotbar: List<String>, selected: Int) {
        try {
            dir.mkdirs()
            File(dir, "level.dat").outputStream().use { fos ->
                GZIPOutputStream(fos).use { gz ->
                    DataOutputStream(gz).use { out ->
                        out.writeInt(MAGIC)
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
                        val list = chunks.values.filter { it.generated }
                        out.writeInt(list.size)
                        for (c in list) {
                            out.writeInt(c.cx)
                            out.writeInt(c.cz)
                            out.write(c.blocks)
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
        const val HEIGHT = 80

        /** "VCX1": level.dat with an embedded block palette. */
        private const val MAGIC = 0x56435831

        class Loaded(
            val world: World,
            val player: com.arena.voxel.player.Player,
            val hotbar: List<String>,
            val selected: Int,
            val legacy: Boolean
        )

        /** Loads a saved world. Returns null when there is no save or it is corrupt. */
        fun loadFrom(dir: File): Loaded? {
            val f = File(dir, "level.dat")
            if (!f.isFile) return null
            return try {
                GZIPInputStream(f.inputStream()).use { gz ->
                    DataInputStream(gz).use { inp ->
                        val first = inp.readInt()
                        val modern = first == MAGIC
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
                        val count = inp.readInt()
                        for (i in 0 until count) {
                            val cx = inp.readInt()
                            val cz = inp.readInt()
                            val c = Chunk(cx, cz)
                            var read = 0
                            while (read < c.blocks.size) {
                                val r = inp.read(c.blocks, read, c.blocks.size - read)
                                if (r <= 0) break
                                read += r
                            }
                            remap?.let { map ->
                                for (i in c.blocks.indices) {
                                    val old = c.blocks[i].toInt() and 0xff
                                    val mapped = if (old < map.size) map[old] else 0
                                    c.blocks[i] = mapped.toByte()
                                }
                            }
                            c.generated = true
                            c.dirty = true
                            world.chunks[world.keyOf(cx, cz)] = c
                        }
                        world.dayTime = dt
                        val p = com.arena.voxel.player.Player(px, py, pz)
                        p.yaw = yaw
                        p.pitch = pitch
                        Loaded(world, p, hotbar, selected, !modern)
                    }
                }
            } catch (t: Throwable) {
                Log.e("No se pudo cargar el mundo", t)
                null
            }
        }
    }
}
