package com.arena.voxel.api;

/**
 * A mod item: id, display name, stack size and optional food value.
 *
 * <p>Items live in the same registry as blocks, so a mod item can be given with
 * {@code /give <id>} and shows up in the creative tabs. Use
 * {@link ModContext#registerTexture(String, byte[])} first if you want a custom icon.</p>
 */
public class ItemDef {

    public final String id;
    public final String name;
    public final int maxStack;
    /** Food points restored when eaten; 0 means "not edible". */
    public final int food;
    /** Texture name registered with {@code registerTexture}, or null for the generic chip. */
    public final String texture;

    public ItemDef(String id, String name) {
        this(id, name, 64, 0, null);
    }

    public ItemDef(String id, String name, int maxStack, int food, String texture) {
        this.id = id;
        this.name = name;
        this.maxStack = maxStack;
        this.food = food;
        this.texture = texture;
    }
}
