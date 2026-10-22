package com.arena.voxel.api;

/** Read/write access to the voxel world. Coordinates are block coordinates. */
public interface WorldAPI {
    String getBlock(int x, int y, int z);

    void setBlock(int x, int y, int z, String id);

    boolean isSolid(int x, int y, int z);

    boolean isAir(int x, int y, int z);

    int getHighestBlockY(int x, int z);

    int getSeed();

    void fill(int x1, int y1, int z1, int x2, int y2, int z2, String id);

    /** Seconds since the world started. */
    float getTime();
}
