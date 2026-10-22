package com.arena.voxel.player

import com.arena.voxel.world.World
import kotlin.math.abs
import kotlin.math.floor

data class BlockHit(val x: Int, val y: Int, val z: Int, val nx: Int, val ny: Int, val nz: Int)

/** Amanatides & Woo voxel traversal. */
object Raycast {

    /**
     * Everything except air and liquids can be targeted: torches, flowers, tall grass and sugar
     * cane used to be ignored because only solid blocks were tested, which made them impossible
     * to break.
     */
    private fun hitTest(world: World, x: Int, y: Int, z: Int): Boolean {
        val id = world.get(x, y, z)
        if (id == 0) return false
        val block = com.arena.voxel.world.Blocks.get(id)
        return !block.liquid
    }

    fun cast(world: World, ox: Double, oy: Double, oz: Double, dx: Double, dy: Double, dz: Double, maxDist: Double): BlockHit? {
        var x = floor(ox).toInt()
        var y = floor(oy).toInt()
        var z = floor(oz).toInt()

        val stepX = if (dx > 0) 1 else -1
        val stepY = if (dy > 0) 1 else -1
        val stepZ = if (dz > 0) 1 else -1

        val tDeltaX = if (abs(dx) < 1e-9) Double.MAX_VALUE else abs(1.0 / dx)
        val tDeltaY = if (abs(dy) < 1e-9) Double.MAX_VALUE else abs(1.0 / dy)
        val tDeltaZ = if (abs(dz) < 1e-9) Double.MAX_VALUE else abs(1.0 / dz)

        var tMaxX = if (abs(dx) < 1e-9) Double.MAX_VALUE else ((if (dx > 0) (x + 1 - ox) else (ox - x)) / abs(dx))
        var tMaxY = if (abs(dy) < 1e-9) Double.MAX_VALUE else ((if (dy > 0) (y + 1 - oy) else (oy - y)) / abs(dy))
        var tMaxZ = if (abs(dz) < 1e-9) Double.MAX_VALUE else ((if (dz > 0) (z + 1 - oz) else (oz - z)) / abs(dz))

        var nx = 0
        var ny = 0
        var nz = 0
        var t = 0.0

        if (hitTest(world, x, y, z)) {
            return BlockHit(x, y, z, 0, 1, 0)
        }

        while (t <= maxDist) {
            if (tMaxX < tMaxY && tMaxX < tMaxZ) {
                x += stepX
                t = tMaxX
                tMaxX += tDeltaX
                nx = -stepX
                ny = 0
                nz = 0
            } else if (tMaxY < tMaxZ) {
                y += stepY
                t = tMaxY
                tMaxY += tDeltaY
                nx = 0
                ny = -stepY
                nz = 0
            } else {
                z += stepZ
                t = tMaxZ
                tMaxZ += tDeltaZ
                nx = 0
                ny = 0
                nz = -stepZ
            }
            if (t > maxDist) break
            if (hitTest(world, x, y, z)) {
                return BlockHit(x, y, z, nx, ny, nz)
            }
        }
        return null
    }
}
