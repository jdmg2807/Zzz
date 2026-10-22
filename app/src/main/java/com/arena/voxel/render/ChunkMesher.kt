package com.arena.voxel.render

import com.arena.voxel.textures.Textures
import com.arena.voxel.world.Blocks
import com.arena.voxel.world.Chunk
import com.arena.voxel.world.World

/**
 * Converts a chunk into triangles. Only faces touching air / transparent blocks are emitted,
 * and water goes to a separate buffer so it can be blended after the opaque pass.
 */
object ChunkMesher {

    // 0:+X 1:-X 2:+Y 3:-Y 4:+Z 5:-Z
    private val DX = intArrayOf(1, -1, 0, 0, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1, 0, 0)
    private val DZ = intArrayOf(0, 0, 0, 0, 1, -1)

    private val OX = intArrayOf(1, 0, 0, 0, 1, 0)
    private val OY = intArrayOf(0, 0, 1, 0, 0, 0)
    private val OZ = intArrayOf(0, 1, 1, 0, 1, 0)

    private val UX = intArrayOf(0, 0, 1, 1, 0, 0)
    private val UY = intArrayOf(1, 1, 0, 0, 1, 1)
    private val UZ = intArrayOf(0, 0, 0, 0, 0, 0)

    private val VX = intArrayOf(0, 0, 0, 0, -1, 1)
    private val VY = intArrayOf(0, 0, 0, 0, 0, 0)
    private val VZ = intArrayOf(1, -1, -1, 1, 0, 0)

    private val SHADE = floatArrayOf(0.80f, 0.80f, 1.0f, 0.52f, 0.68f, 0.68f)
    private val VERTICAL_UV = booleanArrayOf(true, true, false, false, true, true)

    class Result(val opaque: FloatArray, val water: FloatArray)

    fun build(world: World, chunk: Chunk): Result {
        val opaque = FloatList(8192)
        val water = FloatList(1024)
        val baseX = chunk.cx * 16
        val baseZ = chunk.cz * 16

        for (y in 0 until World.HEIGHT) {
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    val b = chunk.get(x, y, z)
                    if (b == Blocks.AIR) continue
                    val block = Blocks.get(b)
                    val wx = baseX + x
                    val wz = baseZ + z
                    val target = if (block.liquid) water else opaque
                    for (f in 0..5) {
                        val nb = world.get(wx + DX[f], y + DY[f], wz + DZ[f])
                        if (Blocks.isOpaque(nb)) continue
                        if (block.liquid) {
                            if (nb == b) continue
                        } else if (!block.opaque && nb == b) {
                            continue
                        }
                        val tile = when (f) {
                            2 -> block.tileTop
                            3 -> block.tileBottom
                            else -> block.tileSide
                        }
                        addQuad(target, wx, y, wz, f, tile, SHADE[f], VERTICAL_UV[f])
                    }
                }
            }
        }
        return Result(opaque.toArray(), water.toArray())
    }

    private fun addQuad(out: FloatList, x: Int, y: Int, z: Int, face: Int, tile: Int, shade: Float, verticalUv: Boolean) {
        val col = tile % Textures.GRID
        val row = tile / Textures.GRID
        val ts = 1f / Textures.GRID
        val eps = 0.5f / Textures.ATLAS
        val u0 = col * ts + eps
        val u1 = (col + 1) * ts - eps
        val v0 = 1f - (row + 1) * ts + eps
        val v1 = 1f - row * ts - eps

        val ox = x + OX[face]
        val oy = y + OY[face]
        val oz = z + OZ[face]
        val ux = UX[face]
        val uy = UY[face]
        val uz = UZ[face]
        val vx = VX[face]
        val vy = VY[face]
        val vz = VZ[face]

        val c0x = ox.toFloat(); val c0y = oy.toFloat(); val c0z = oz.toFloat()
        val c1x = (ox + ux).toFloat(); val c1y = (oy + uy).toFloat(); val c1z = (oz + uz).toFloat()
        val c2x = (ox + ux + vx).toFloat(); val c2y = (oy + uy + vy).toFloat(); val c2z = (oz + uz + vz).toFloat()
        val c3x = (ox + vx).toFloat(); val c3y = (oy + vy).toFloat(); val c3z = (oz + vz).toFloat()

        if (verticalUv) {
            // U axis is vertical: texture "up" is +U.
            out.add(c0x, c0y, c0z, u0, v0, shade)
            out.add(c1x, c1y, c1z, u0, v1, shade)
            out.add(c2x, c2y, c2z, u1, v1, shade)
            out.add(c0x, c0y, c0z, u0, v0, shade)
            out.add(c2x, c2y, c2z, u1, v1, shade)
            out.add(c3x, c3y, c3z, u1, v0, shade)
        } else {
            // Top/bottom: U axis is horizontal.
            out.add(c0x, c0y, c0z, u0, v0, shade)
            out.add(c1x, c1y, c1z, u1, v0, shade)
            out.add(c2x, c2y, c2z, u1, v1, shade)
            out.add(c0x, c0y, c0z, u0, v0, shade)
            out.add(c2x, c2y, c2z, u1, v1, shade)
            out.add(c3x, c3y, c3z, u0, v1, shade)
        }
    }
}
