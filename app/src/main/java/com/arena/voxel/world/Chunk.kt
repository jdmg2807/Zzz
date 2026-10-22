package com.arena.voxel.world

import com.arena.voxel.render.ChunkMesh

/** 16 x 16 x HEIGHT column of blocks, stored as palette indexes. */
class Chunk(val cx: Int, val cz: Int) {
    val blocks = ByteArray(16 * 16 * World.HEIGHT)

    var generated = false
    var dirty = true

    var mesh: ChunkMesh? = null
    var waterMesh: ChunkMesh? = null

    fun index(x: Int, y: Int, z: Int): Int = (y * 16 + z) * 16 + x

    fun get(x: Int, y: Int, z: Int): Int {
        if (x < 0 || x > 15 || z < 0 || z > 15 || y < 0 || y >= World.HEIGHT) return 0
        return blocks[index(x, y, z)].toInt() and 0xff
    }

    fun set(x: Int, y: Int, z: Int, value: Int) {
        if (x < 0 || x > 15 || z < 0 || z > 15 || y < 0 || y >= World.HEIGHT) return
        blocks[index(x, y, z)] = value.toByte()
    }
}
