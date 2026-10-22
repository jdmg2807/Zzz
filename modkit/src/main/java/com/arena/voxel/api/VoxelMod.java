package com.arena.voxel.api;

/**
 * Entry point of a VoxelCraft mod.
 *
 * <p>A mod is a plain <b>.jar</b> file placed in {@code Android/data/com.arena.voxel/files/mods/}
 * (also reachable from the in-game "Mods" screen). The jar must contain a
 * {@code voxelmod.json} descriptor at its root and at least one class implementing this
 * interface. The class is compiled against the same Java/Kotlin runtime that the game
 * uses (any modern Android version), so ordinary Java code works out of the box.</p>
 *
 * <pre>
 * {
 *   "id": "examplemod",
 *   "name": "Example Mod",
 *   "version": "1.0.0",
 *   "author": "you",
 *   "entrypoint": "com.example.ExampleMod"
 * }
 * </pre>
 */
public interface VoxelMod {

    /** Called once, right after the mod jar is loaded, before the world is generated. */
    default void onLoad(ModContext context) throws Exception {
    }

    /** Called when a world finished loading / generating. */
    default void onWorldReady() {
    }

    /** Called every frame. {@code dt} is the frame delta in seconds. */
    default void onTick(float dt) {
    }

    /** Called when any block is placed, including blocks placed by other mods. */
    default void onBlockPlaced(int x, int y, int z, String blockId) {
    }

    /** Called when the player breaks a block. */
    default void onBlockBroken(int x, int y, int z, String blockId) {
    }
}
