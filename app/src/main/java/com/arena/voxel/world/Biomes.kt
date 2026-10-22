package com.arena.voxel.world

/**
 * Biome palette. Vertex/index order is used by the mesher to pick pre-tinted atlas tiles,
 * so the constants must stay in sync with [com.arena.voxel.textures.Textures].
 */
object Biomes {
    const val PLAINS = 0
    const val FOREST = 1
    const val SNOWY = 2
    const val DESERT = 3
    const val TAIGA = 4
    const val JUNGLE = 5

    const val COUNT = 6

    val NAMES = arrayOf("llanura", "bosque", "nevado", "desierto", "taiga", "jungla")

    /** Grass / tall grass colours (Minecraft-like). */
    val GRASS = intArrayOf(0x91BD59, 0x79C05A, 0x80B497, 0xBFB755, 0x86B783, 0x59C93C)

    /** Leaf colours. */
    val FOLIAGE = intArrayOf(0x77AB2F, 0x59AE30, 0x60A17B, 0xAEBD56, 0x68A464, 0x30BB0B)

    /** Water colours. */
    val WATER = intArrayOf(0x3F76E4, 0x3C6BE0, 0x3D57D6, 0x32A598, 0x2E6FB8, 0x3FC6D8)

    fun isCold(biome: Int) = biome == SNOWY || biome == TAIGA

    fun isDry(biome: Int) = biome == DESERT
}
