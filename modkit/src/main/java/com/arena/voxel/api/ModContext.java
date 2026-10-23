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

    /**
     * Reads a file bundled inside the mod .jar (for example "assets/texture.png").
     * Returns null when the entry does not exist. This is needed because a DexClassLoader
     * does not expose jar resources through {@code getResourceAsStream}.
     */
    byte[] readResource(String path);

    /**
     * Registers a mod item. Items are added to the creative tabs and can be given with
     * {@code /give <id> [count]}.
     */
    void registerItem(ItemDef def);

    /**
     * Registers a chat command. The command is typed as {@code /name arg1 arg2} and the handler
     * returns the message to show (null for silence).
     */
    void registerCommand(String name, CommandHandler handler);
}
