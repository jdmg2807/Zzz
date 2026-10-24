package com.arena.modeler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.arena.modeler.ai.ObjectStore
import com.arena.modeler.ui.AppState
import com.arena.modeler.ui.ModelerApp

/**
 * VoxelForge 3D: modelling, animation and AI-assisted generation, entirely on the phone.
 * The whole UI is one Compose screen ([ModelerApp]); this activity only wires storage and
 * lifecycle.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ObjectStore.init(this)
        setContent {
            Surface(color = Color(0xFF0D0F14), modifier = Modifier.fillMaxSize().background(Color(0xFF0D0F14))) {
                ModelerApp()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        // Autosave so a process death never loses the scene.
        AppState.active?.onPause()
    }
}
