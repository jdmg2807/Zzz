package com.arena.voxel.api;

import java.util.logging.Logger;

/** Everything a mod may touch. */
public interface ModContext {
    String getModId();

    String getGameVersion();

    Logger getLogger();

    WorldAPI world();

    PlayerAPI player();

    ChatAPI chat();

    /** Registers a new block id. Call from {@link VoxelMod#onLoad(ModContext)}. */
    void registerBlock(BlockDef def);

    /**
     * Registers a PNG texture (bytes of a .png file) under a name usable in
     * {@link BlockDef#texture(String)}. Call from {@link VoxelMod#onLoad(ModContext)}.
     */
    void registerTexture(String name, byte[] pngBytes);
}
