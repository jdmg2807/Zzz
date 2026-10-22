package com.arena.voxel.api;

/** Chat / toast messages. */
public interface ChatAPI {
    void broadcast(String message);

    void toast(String message);
}
