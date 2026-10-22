package com.arena.voxel.world

/**
 * Axis-aligned boxes of a block, in 0..1 local coordinates.
 *
 * Minecraft is not a "full cube or nothing" game: slabs are 0.5 high, stairs are two boxes,
 * fences are a post plus rails, and chests/plants are not walkable at all. Collision *and*
 * meshing read from here, so a slab both looks and behaves like a slab.
 *
 * `boxes` is empty for blocks without collision (air, plants, torches, liquids) — exactly like
 * a `VoxelShape.emptyShape` in the game.
 */
class BlockShape(val boxes: List<Box>) {

    val isEmpty: Boolean get() = boxes.isEmpty()
    val isFullCube: Boolean get() = boxes.size == 1 && boxes[0].isFullCube

    class Box(
        val x0: Float, val y0: Float, val z0: Float,
        val x1: Float, val y1: Float, val z1: Float
    ) {
        val isFullCube: Boolean
            get() = x0 == 0f && y0 == 0f && z0 == 0f && x1 == 1f && y1 == 1f && z1 == 1f
    }

    companion object {
        private fun box(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) =
            Box(x0, y0, z0, x1, y1, z1)

        /** Full block (stone, dirt, planks...). */
        val FULL = BlockShape(listOf(box(0f, 0f, 0f, 1f, 1f, 1f)))

        /** Nothing to collide with (air, water, plants, torches). */
        val EMPTY = BlockShape(emptyList())

        /** Anvil: a full-height block inset by 1 px, like Java's `ANVIL` shape. */
        val ANVIL = BlockShape(listOf(box(0.0625f, 0f, 0.0625f, 0.9375f, 1f, 0.9375f)))

        /** Bottom slab: 0.5 high, like Java's `SlabBlock` with `half=bottom`. */
        val SLAB_BOTTOM = BlockShape(listOf(box(0f, 0f, 0f, 1f, 0.5f, 1f)))

        /** Top slab. */
        val SLAB_TOP = BlockShape(listOf(box(0f, 0.5f, 0f, 1f, 1f, 1f)))

        /**
         * Stairs facing north (ascending towards -Z), the vanilla shape: a bottom slab plus a
         * half-depth step on the back half.
         */
        val STAIRS_NORTH = BlockShape(
            listOf(
                box(0f, 0f, 0f, 1f, 0.5f, 1f),
                box(0f, 0.5f, 0.5f, 1f, 1f, 1f)
            )
        )
        val STAIRS_SOUTH = BlockShape(
            listOf(
                box(0f, 0f, 0f, 1f, 0.5f, 1f),
                box(0f, 0.5f, 0f, 1f, 1f, 0.5f)
            )
        )
        val STAIRS_WEST = BlockShape(
            listOf(
                box(0f, 0f, 0f, 1f, 0.5f, 1f),
                box(0.5f, 0.5f, 0f, 1f, 1f, 1f)
            )
        )
        val STAIRS_EAST = BlockShape(
            listOf(
                box(0f, 0f, 0f, 1f, 0.5f, 1f),
                box(0f, 0.5f, 0f, 0.5f, 1f, 1f)
            )
        )

        /** Fence: 4/16 post plus the two rails, collision height 1.5 like the game. */
        val FENCE = BlockShape(
            listOf(
                box(0.375f, 0f, 0.375f, 0.625f, 1.5f, 0.625f),
                box(0.375f, 0f, 0f, 0.625f, 1.5f, 1f),
                box(0f, 0f, 0.375f, 1f, 1.5f, 0.625f)
            )
        )

        /** Wall-mounted / thin blocks that can still be targeted but not walked on. */
        val POST = BlockShape(listOf(box(0.375f, 0f, 0.375f, 0.625f, 1f, 0.625f)))

        /** Slightly smaller than a full cube (cactus is 14/16 wide, 15/16 tall). */
        val CACTUS = BlockShape(listOf(box(1f / 16f, 0f, 1f / 16f, 15f / 16f, 15f / 16f, 15f / 16f)))

        /** Carpet / snow layer: 1/16 high (snow layer 0 = 2/16). */
        val LAYER = BlockShape(listOf(box(0f, 0f, 0f, 1f, 1f / 16f, 1f)))

        /** Special shape built from two given insets (used by the mesher for custom boxes). */
        fun inset(amount: Float): BlockShape =
            BlockShape(listOf(box(amount, 0f, amount, 1f - amount, 1f - amount, 1f - amount)))
    }
}
