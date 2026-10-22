package com.arena.voxel.api;

/**
 * Description of a block that a mod can add. Colors are 0xRRGGBB.
 * If {@code textureName} is set and the mod registered a PNG under that name with
 * {@link ModContext#registerTexture(String, byte[])}, the texture is used on all faces
 * and the colors act as a fallback / average color.
 */
public final class BlockDef {
    public final String id;
    public int topColor = 0x7FB238;
    public int sideColor = 0x6A9630;
    public int bottomColor = 0x6A9630;
    public String textureName;
    public boolean solid = true;
    public boolean opaque = true;

    public BlockDef(String id) {
        this.id = id;
    }

    public BlockDef color(int top, int side, int bottom) {
        this.topColor = top;
        this.sideColor = side;
        this.bottomColor = bottom;
        return this;
    }

    public BlockDef texture(String name) {
        this.textureName = name;
        return this;
    }

    public BlockDef solid(boolean value) {
        this.solid = value;
        return this;
    }

    public BlockDef opaque(boolean value) {
        this.opaque = value;
        return this;
    }
}
