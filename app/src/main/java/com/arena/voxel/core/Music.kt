package com.arena.voxel.core

import android.media.MediaPlayer
import android.util.Log

/**
 * Background music.
 *
 * The soundtrack lives in `assets/music/*.wav` (synthesised from scratch by
 * `tools/gen_music.py`, so no Mojang audio is bundled) and is streamed straight from the APK with
 * [MediaPlayer]. A scene picks a random track, loops it and cross-fades by simply swapping
 * players, which is enough for a mobile game and costs no decoding work in the GL thread.
 */
object Music {

    private var player: MediaPlayer? = null
    private var currentScene = ""
    private var currentTrack = ""
    private var paused = false

    @Volatile var enabled = true

    private val menuTracks = listOf("menu_1", "menu_2")
    private val worldTracks = listOf("world_1", "world_2", "night_1")
    private val caveTracks = listOf("cave_1")

    /** Switches to the music of [scene] ("menu", "world", "cave"). Repeats are ignored. */
    fun scene(scene: String) {
        if (!enabled) {
            stop()
            return
        }
        if (scene == currentScene && player != null) return
        val list = when (scene) {
            "cave" -> caveTracks
            "world" -> worldTracks
            else -> menuTracks
        }
        play(list.random(), scene)
    }

    /** Deep-cave ambience: same scene name, but the picker prefers the cave track. */
    fun underground(deep: Boolean) {
        if (deep && currentScene != "cave") scene("cave")
        if (!deep && currentScene == "cave") {
            currentScene = ""
            scene("world")
        }
    }

    private fun play(track: String, scene: String) {
        stopInternal()
        try {
            val afd = Ctx.app.assets.openFd("music/$track.wav")
            val mp = MediaPlayer()
            mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
            afd.close()
            mp.isLooping = true
            mp.setVolume(0.55f, 0.55f)
            mp.setOnPreparedListener { if (!paused) it.start() }
            mp.prepareAsync()
            player = mp
            currentTrack = track
            currentScene = scene
        } catch (t: Throwable) {
            Log.w("VoxelCraft", "No se pudo reproducir $track: ${t.message}")
            player = null
            currentScene = ""
        }
    }

    private fun stopInternal() {
        player?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (t: Throwable) {
                // Already released / never started.
            }
            it.release()
        }
        player = null
    }

    fun pause() {
        paused = true
        player?.let { if (it.isPlaying) it.pause() }
    }

    fun resume() {
        paused = false
        player?.let {
            try {
                if (!it.isPlaying) it.start()
            } catch (t: Throwable) {
                // Nothing prepared yet.
            }
        }
    }

    fun stop() {
        stopInternal()
        currentScene = ""
        currentTrack = ""
    }

    fun trackName(): String = currentTrack
}
