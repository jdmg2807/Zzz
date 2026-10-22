package com.arena.voxel.render

import com.arena.voxel.textures.Textures
import com.arena.voxel.world.Blocks
import com.arena.voxel.world.Chunk
import com.arena.voxel.world.World

/**
 * Converts a chunk into triangles.
 *
 * Per vertex: position, atlas UV, face shade * ambient occlusion, sky light and block light.
 * Light is baked here (cheap approximation), so the shaders only have to combine it with the
 * day/night brightness.
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

    private val FACE_SHADE = floatArrayOf(0.80f, 0.80f, 1.0f, 0.52f, 0.68f, 0.68f)
    private val VERTICAL_UV = booleanArrayOf(true, true, false, false, true, true)
    private val AO_LEVEL = floatArrayOf(0.55f, 0.72f, 0.87f, 1.0f)

    /** Water surface sits 2/16 below a full block, like Minecraft. */
    private const val WATER_DROP = 0.12f

    private val cornerU = intArrayOf(0, 1, 1, 0)
    private val cornerV = intArrayOf(0, 0, 1, 1)

    class Result(val opaque: FloatArray, val water: FloatArray)

    private const val FLOATS_PER_VERTEX = 8

    fun build(world: World, chunk: Chunk, biomes: IntArray): Result {
        val opaque = FloatList(16384)
        val water = FloatList(2048)
        val baseX = chunk.cx * 16
        val baseZ = chunk.cz * 16
        val shades = FloatArray(4)

        // Column heightmap for sky light (highest opaque block).
        val surface = IntArray(256)
        for (z in 0 until 16) {
            for (x in 0 until 16) {
                var top = 0
                for (y in (chunk.maxY).coerceAtMost(World.HEIGHT - 1) downTo 0) {
                    if (Blocks.isOpaque(chunk.get(x, y, z))) {
                        top = y
                        break
                    }
                }
                surface[z * 16 + x] = top
            }
        }

        val sources = lightSourcesFor(world, chunk)

        val topY = (chunk.maxY + 1).coerceIn(1, World.HEIGHT)
        for (y in 0 until topY) {
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    val b = chunk.get(x, y, z)
                    if (b == Blocks.AIR) continue
                    val block = Blocks.get(b)
                    val wx = baseX + x
                    val wz = baseZ + z
                    val biome = biomes[z * 16 + x]

                    val surfaceY = surface[z * 16 + x]
                    val depth = surfaceY - y
                    val sky = if (depth <= 0) 1f else (1f - depth * 0.11f).coerceAtLeast(0.05f)
                    val blockLight = if (sources.isEmpty()) 0f else lightFrom(sources, wx, y, wz)

                    if (block.cross) {
                        val tint = if (block.tintTop) biome else 0
                        addCross(opaque, wx, y, wz, block.tileTop + tint, 0.96f, sky, blockLight)
                        continue
                    }

                    val liquid = block.liquid
                    val target = if (liquid) water else opaque
                    val aboveIsSame = world.get(wx, y + 1, wz) == b

                    for (f in 0..5) {
                        val nb = world.get(wx + DX[f], y + DY[f], wz + DZ[f])
                        if (Blocks.isOpaque(nb)) continue
                        if (liquid) {
                            if (nb == b) continue
                        } else if (!block.opaque && nb == b && block.inset <= 0f) {
                            continue
                        }

                        val tinted = when (f) {
                            2 -> block.tintTop
                            3 -> block.tintBottom
                            else -> block.tintSide
                        }
                        val baseTile = when (f) {
                            2 -> block.tileTop
                            3 -> block.tileBottom
                            else -> block.tileSide
                        }
                        val tile = baseTile + if (tinted) biome else 0

                        if (liquid) {
                            for (i in 0..3) shades[i] = FACE_SHADE[f]
                        } else {
                            for (i in 0..3) {
                                val ao = occlusion(world, wx, y, wz, f, cornerU[i], cornerV[i])
                                shades[i] = FACE_SHADE[f] * AO_LEVEL[ao]
                            }
                        }

                        val topOff = if (liquid && !aboveIsSame) WATER_DROP else 0f
                        addQuad(target, wx, y, wz, f, tile, shades, topOff, sky, blockLight, block.inset)
                    }
                }
            }
        }
        return Result(opaque.toArray(), water.toArray())
    }

    /** Light emitters of a chunk as x,y,z,level quads, cached on the chunk. */
    private fun scanSources(c: Chunk): IntArray {
        val out = ArrayList<Int>(32)
        val topY = (c.maxY + 1).coerceIn(1, World.HEIGHT)
        for (y in 0 until topY) {
            for (z in 0 until 16) {
                for (x in 0 until 16) {
                    val b = c.get(x, y, z)
                    if (b == Blocks.AIR) continue
                    val light = Blocks.get(b).light
                    if (light <= 0) continue
                    out.add(c.cx * 16 + x)
                    out.add(y)
                    out.add(c.cz * 16 + z)
                    out.add(light)
                    if (out.size >= 512) return out.toIntArray()
                }
            }
        }
        return out.toIntArray()
    }

    private fun lightSourcesFor(world: World, chunk: Chunk): IntArray {
        val out = ArrayList<Int>(64)
        for (dx in -1..1) {
            for (dz in -1..1) {
                val c = world.chunk(chunk.cx + dx, chunk.cz + dz) ?: continue
                val arr = c.lightSources ?: scanSources(c).also { c.lightSources = it }
                var i = 0
                while (i + 3 < arr.size) {
                    out.add(arr[i]); out.add(arr[i + 1]); out.add(arr[i + 2]); out.add(arr[i + 3])
                    i += 4
                    if (out.size >= 768) return out.toIntArray()
                }
            }
        }
        return out.toIntArray()
    }

    private fun lightFrom(sources: IntArray, x: Int, y: Int, z: Int): Float {
        var best = 0f
        var i = 0
        while (i + 3 < sources.size) {
            val dx = (sources[i] - x).toFloat()
            val dy = (sources[i + 1] - y).toFloat()
            val dz = (sources[i + 2] - z).toFloat()
            val level = sources[i + 3] / 15f
            val dist2 = dx * dx + dy * dy + dz * dz
            if (dist2 < 225f) {
                val dist = kotlin.math.sqrt(dist2)
                val v = (1f - dist / 15f) * level
                if (v > best) best = v
            }
            i += 4
        }
        return best
    }

    /** 0..3, where 0 is the most occluded corner. */
    private fun occlusion(world: World, bx: Int, by: Int, bz: Int, f: Int, du: Int, dv: Int): Int {
        val su = if (du == 1) 1 else -1
        val sv = if (dv == 1) 1 else -1
        val nx = DX[f]
        val ny = DY[f]
        val nz = DZ[f]

        fun solid(ax: Int, ay: Int, az: Int): Int =
            if (Blocks.isOpaque(world.get(bx + nx + ax, by + ny + ay, bz + nz + az))) 1 else 0

        val s1 = solid(UX[f] * su, UY[f] * su, UZ[f] * su)
        val s2 = solid(VX[f] * sv, VY[f] * sv, VZ[f] * sv)
        val c = solid(UX[f] * su + VX[f] * sv, UY[f] * su + VY[f] * sv, UZ[f] * su + VZ[f] * sv)
        return if (s1 == 1 && s2 == 1) 0 else 3 - (s1 + s2 + c)
    }

    // ------------------------------------------------------------------ geometry

    private fun addQuad(
        out: FloatList,
        x: Int, y: Int, z: Int,
        face: Int,
        tile: Int,
        shades: FloatArray,
        topOffset: Float,
        sky: Float,
        blockLight: Float,
        inset: Float = 0f
    ) {
        val px = floatArrayOf(
            (x + OX[face]).toFloat(),
            (x + OX[face] + UX[face]).toFloat(),
            (x + OX[face] + UX[face] + VX[face]).toFloat(),
            (x + OX[face] + VX[face]).toFloat()
        )
        val py = floatArrayOf(
            (y + OY[face]).toFloat(),
            (y + OY[face] + UY[face]).toFloat(),
            (y + OY[face] + UY[face] + VY[face]).toFloat(),
            (y + OY[face] + VY[face]).toFloat()
        )
        val pz = floatArrayOf(
            (z + OZ[face]).toFloat(),
            (z + OZ[face] + UZ[face]).toFloat(),
            (z + OZ[face] + UZ[face] + VZ[face]).toFloat(),
            (z + OZ[face] + VZ[face]).toFloat()
        )

        if (topOffset != 0f) {
            if (VERTICAL_UV[face]) {
                py[1] -= topOffset
                py[2] -= topOffset
            } else if (face == 2) {
                for (i in 0..3) py[i] -= topOffset
            }
        }

        // Minecraft-like inset boxes (cactus is 14/16 wide and shows its own sides).
        if (inset > 0f) {
            val low = inset
            val high = 1f - inset
            for (i in 0..3) {
                px[i] = x + low + (px[i] - x) * (high - low)
                pz[i] = z + low + (pz[i] - z) * (high - low)
            }
        }

        emit(out, px, py, pz, face, tile, shades, sky, blockLight)
    }

    private fun emit(
        out: FloatList,
        px: FloatArray, py: FloatArray, pz: FloatArray,
        face: Int, tile: Int, shades: FloatArray,
        sky: Float, blockLight: Float
    ) {
        val col = tile % Textures.GRID
        val row = tile / Textures.GRID
        val ts = 1f / Textures.GRID
        val eps = 0.5f / Textures.ATLAS
        val u0 = col * ts + eps
        val u1 = (col + 1) * ts - eps
        // Bitmap row 0 lives at v = 0, so tile rows grow downwards in v.
        val vTop = row * ts + eps
        val vBottom = (row + 1) * ts - eps

        val uu = FloatArray(4)
        val vv = FloatArray(4)
        if (VERTICAL_UV[face]) {
            uu[0] = u0; vv[0] = vBottom
            uu[1] = u0; vv[1] = vTop
            uu[2] = u1; vv[2] = vTop
            uu[3] = u1; vv[3] = vBottom
        } else {
            uu[0] = u0; vv[0] = vTop
            uu[1] = u1; vv[1] = vTop
            uu[2] = u1; vv[2] = vBottom
            uu[3] = u0; vv[3] = vBottom
        }

        if (shades[0] + shades[2] > shades[1] + shades[3]) {
            vertex(out, px, py, pz, uu, vv, shades, 0, sky, blockLight)
            vertex(out, px, py, pz, uu, vv, shades, 1, sky, blockLight)
            vertex(out, px, py, pz, uu, vv, shades, 2, sky, blockLight)
            vertex(out, px, py, pz, uu, vv, shades, 0, sky, blockLight)
            vertex(out, px, py, pz, uu, vv, shades, 2, sky, blockLight)
            vertex(out, px, py, pz, uu, vv, shades, 3, sky, blockLight)
        } else {
            vertex(out, px, py, pz, uu, vv, shades, 1, sky, blockLight)
            vertex(out, px, py, pz, uu, vv, shades, 2, sky, blockLight)
            vertex(out, px, py, pz, uu, vv, shades, 3, sky, blockLight)
            vertex(out, px, py, pz, uu, vv, shades, 1, sky, blockLight)
            vertex(out, px, py, pz, uu, vv, shades, 3, sky, blockLight)
            vertex(out, px, py, pz, uu, vv, shades, 0, sky, blockLight)
        }
    }

    private fun vertex(
        out: FloatList,
        px: FloatArray, py: FloatArray, pz: FloatArray,
        uu: FloatArray, vv: FloatArray,
        shades: FloatArray, i: Int,
        sky: Float, blockLight: Float
    ) {
        out.add(px[i], py[i], pz[i], uu[i], vv[i], shades[i], sky, blockLight)
    }

    private fun addCross(
        out: FloatList, x: Int, y: Int, z: Int, tile: Int, shade: Float, sky: Float, blockLight: Float
    ) {
        val col = tile % Textures.GRID
        val row = tile / Textures.GRID
        val ts = 1f / Textures.GRID
        val eps = 0.5f / Textures.ATLAS
        val u0 = col * ts + eps
        val u1 = (col + 1) * ts - eps
        val vTop = row * ts + eps
        val vBottom = (row + 1) * ts - eps

        val fx = x.toFloat()
        val fy = y.toFloat()
        val fz = z.toFloat()

        fun v(a: Float, b: Float, c: Float, u: Float, v2: Float) =
            out.add(a, b, c, u, v2, shade, sky, blockLight)

        fun plane(ax: Float, az: Float, bx: Float, bz: Float) {
            v(ax, fy, az, u0, vBottom); v(bx, fy, bz, u1, vBottom); v(bx, fy + 1f, bz, u1, vTop)
            v(ax, fy, az, u0, vBottom); v(bx, fy + 1f, bz, u1, vTop); v(ax, fy + 1f, az, u0, vTop)
            v(bx, fy, bz, u0, vBottom); v(ax, fy, az, u1, vBottom); v(ax, fy + 1f, az, u1, vTop)
            v(bx, fy, bz, u0, vBottom); v(ax, fy + 1f, az, u1, vTop); v(bx, fy + 1f, bz, u0, vTop)
        }

        plane(fx, fz, fx + 1f, fz + 1f)
        plane(fx + 1f, fz, fx, fz + 1f)
    }

    /**
     * Builds an animal model out of boxes (see [com.arena.voxel.world.Mobs.Part]).
     * Coordinates are in 1/16 block units.
     */
    fun buildModelMesh(
        parts: List<com.arena.voxel.world.Mobs.Part>,
        tileOf: (Int) -> Int
    ): FloatArray {
        val out = FloatList(6144)
        for (p in parts) {
            val tile = tileOf(p.color)
            if (tile < 0) continue
            val cube = buildCube(tile, tile, tile, 1f, 0f)
            val sx = p.x1 - p.x0
            val sy = p.y1 - p.y0
            val sz = p.z1 - p.z0
            var i = 0
            while (i + 7 < cube.size) {
                out.add(
                    p.x0 + cube[i] * sx,
                    p.y0 + cube[i + 1] * sy,
                    p.z0 + cube[i + 2] * sz,
                    cube[i + 3], cube[i + 4],
                    cube[i + 5] * p.shade,
                    cube[i + 6], cube[i + 7]
                )
                i += 8
            }
        }
        return out.toArray()
    }

    // ------------------------------------------------------------------ standalone cubes

    /**
     * Unit cube [0,1] with per-face tiles, used for the held block and the mining cracks.
     * Sky light is forced to [sky]; block light is used for the warm highlight.
     */
    fun buildCube(top: Int, side: Int, bottom: Int, sky: Float = 1f, blockLight: Float = 0f): FloatArray {
        val out = FloatList(512)
        val shades = FloatArray(4)
        for (f in 0..5) {
            val tile = when (f) {
                2 -> top
                3 -> bottom
                else -> side
            }
            for (i in 0..3) shades[i] = FACE_SHADE[f]
            val px = floatArrayOf(
                OX[f].toFloat(), (OX[f] + UX[f]).toFloat(), (OX[f] + UX[f] + VX[f]).toFloat(), (OX[f] + VX[f]).toFloat()
            )
            val py = floatArrayOf(
                OY[f].toFloat(), (OY[f] + UY[f]).toFloat(), (OY[f] + UY[f] + VY[f]).toFloat(), (OY[f] + VY[f]).toFloat()
            )
            val pz = floatArrayOf(
                OZ[f].toFloat(), (OZ[f] + UZ[f]).toFloat(), (OZ[f] + UZ[f] + VZ[f]).toFloat(), (OZ[f] + VZ[f]).toFloat()
            )
            emit(out, px, py, pz, f, tile, shades, sky, blockLight)
        }
        return out.toArray()
    }
}
