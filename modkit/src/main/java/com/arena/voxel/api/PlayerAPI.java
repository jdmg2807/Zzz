package com.arena.voxel.api;

/** Player state and small helpers. */
public interface PlayerAPI {
    double getX();

    double getY();

    double getZ();

    void teleport(double x, double y, double z);

    float getYaw();

    float getPitch();

    boolean isFlying();

    void setFlying(boolean flying);

    /** Sets a persistent message drawn as an overlay (empty string hides it). */
    void setHudText(String text);
}
