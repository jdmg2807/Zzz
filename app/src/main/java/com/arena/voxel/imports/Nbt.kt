package com.arena.voxel.imports

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

/**
 * Minimal NBT reader (the format Minecraft uses for level.dat and region chunks).
 * Big-endian, tag ids: 0 end, 1 byte, 2 short, 3 int, 4 long, 5 float, 6 double, 7 byte[],
 * 8 string, 9 list, 10 compound, 11 int[], 12 long[].
 */
object Nbt {

    sealed class Tag {
        class Compound(val map: Map<String, Tag>) : Tag() {
            operator fun get(key: String): Tag? = map[key]
            fun compound(key: String): Compound? = map[key] as? Compound
            fun int(key: String, def: Int = 0): Int = (map[key] as? IntTag)?.value ?: def
            fun long(key: String, def: Long = 0L): Long = (map[key] as? LongTag)?.value ?: def
            fun string(key: String, def: String = ""): String = (map[key] as? StringTag)?.value ?: def
            fun list(key: String): ListTag? = map[key] as? ListTag
            fun byteArray(key: String): ByteArray? = (map[key] as? ByteArrayTag)?.value
            fun longArray(key: String): LongArray? = (map[key] as? LongArrayTag)?.value
            fun intArray(key: String): IntArray? = (map[key] as? IntArrayTag)?.value
            fun byte(key: String, def: Int = 0): Int = (map[key] as? ByteTag)?.value?.toInt() ?: def
            fun bool(key: String): Boolean = byte(key) != 0
        }

        class ByteTag(val value: Byte) : Tag()
        class ShortTag(val value: Short) : Tag()
        class IntTag(val value: Int) : Tag()
        class LongTag(val value: Long) : Tag()
        class FloatTag(val value: Float) : Tag()
        class DoubleTag(val value: Double) : Tag()
        class ByteArrayTag(val value: ByteArray) : Tag()
        class StringTag(val value: String) : Tag()
        class ListTag(val items: List<Tag>, val elementType: Int) : Tag()
        class IntArrayTag(val value: IntArray) : Tag()
        class LongArrayTag(val value: LongArray) : Tag()
    }

    /** Parses a gzip-compressed NBT document (level.dat, or a chunk payload). */
    fun parseGzip(bytes: ByteArray): Tag.Compound? = try {
        GZIPInputStream(ByteArrayInputStream(bytes)).use { gz -> parse(DataInputStream(gz)) }
    } catch (t: Throwable) {
        null
    }

    fun parse(bytes: ByteArray): Tag.Compound? = try {
        parse(DataInputStream(ByteArrayInputStream(bytes)))
    } catch (t: Throwable) {
        null
    }

    fun parse(input: DataInputStream): Tag.Compound {
        val type = input.readUnsignedByte()
        if (type != 10) throw IllegalStateException("NBT raíz no es compound ($type)")
        input.readUTF()
        return readCompound(input)
    }

    private fun readCompound(input: DataInputStream): Tag.Compound {
        val map = LinkedHashMap<String, Tag>()
        while (true) {
            val type = input.readUnsignedByte()
            if (type == 0) break
            val name = input.readUTF()
            map[name] = readTag(input, type)
        }
        return Tag.Compound(map)
    }

    private fun readTag(input: DataInputStream, type: Int): Tag = when (type) {
        1 -> Tag.ByteTag(input.readByte())
        2 -> Tag.ShortTag(input.readShort())
        3 -> Tag.IntTag(input.readInt())
        4 -> Tag.LongTag(input.readLong())
        5 -> Tag.FloatTag(input.readFloat())
        6 -> Tag.DoubleTag(input.readDouble())
        7 -> {
            val len = input.readInt()
            val b = ByteArray(if (len < 0) 0 else len)
            input.readFully(b)
            Tag.ByteArrayTag(b)
        }
        8 -> Tag.StringTag(input.readUTF())
        9 -> {
            val elementType = input.readUnsignedByte()
            val len = input.readInt()
            val items = ArrayList<Tag>(if (len < 0) 0 else minOf(len, 4096))
            for (i in 0 until len) items.add(readTag(input, elementType))
            Tag.ListTag(items, elementType)
        }
        10 -> readCompound(input)
        11 -> {
            val len = input.readInt()
            val arr = IntArray(if (len < 0) 0 else len)
            for (i in 0 until len) arr[i] = input.readInt()
            Tag.IntArrayTag(arr)
        }
        12 -> {
            val len = input.readInt()
            val arr = LongArray(if (len < 0) 0 else len)
            for (i in 0 until len) arr[i] = input.readLong()
            Tag.LongArrayTag(arr)
        }
        else -> throw IllegalStateException("Tag NBT desconocido: $type")
    }
}
