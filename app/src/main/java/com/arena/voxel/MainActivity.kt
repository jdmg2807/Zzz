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
        Settings.load(this)
        Game.initHotbar()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            VoxelApp()
        }
    }

    override fun onPause() {
        super.onPause()
        if (Game.world != null) Game.saveWorld()
        Settings.save(this)
    }
}
