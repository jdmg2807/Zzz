package com.arena.voxel

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.arena.voxel.core.Ctx
import com.arena.voxel.ui.VoxelApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Ctx.init(this)
        com.arena.voxel.imports.UriFiles.init(this)
        Settings.load(this)
        com.arena.voxel.core.Sounds.init(this)
        com.arena.voxel.core.Sounds.enabled = Settings.sound
        com.arena.voxel.core.Music.enabled = Settings.music
        com.arena.voxel.core.Logs.startSession(
            "Inicio · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · " +
                "Android ${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})"
        )
        // Any crash is written to the log and exported before the app dies.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            com.arena.voxel.core.Logs.error("CRASH", "Excepción no controlada en ${thread.name}", throwable)
            com.arena.voxel.core.Logs.exportToDownloads(this)
            previous?.uncaughtException(thread, throwable)
        }
        Game.initHotbar()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            VoxelApp()
        }
    }

    override fun onResume() {
        super.onResume()
        com.arena.voxel.core.Music.resume()
    }

    override fun onPause() {
        super.onPause()
        com.arena.voxel.core.Music.pause()
        if (Game.world != null) Game.saveWorld()
        Settings.save(this)
        // Keep a copy of the log in Downloads after every session.
        com.arena.voxel.core.Logs.exportToDownloads(this)
    }
}
