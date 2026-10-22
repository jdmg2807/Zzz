package com.arena.voxel.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import com.arena.voxel.BuildConfig
import com.arena.voxel.Game
import com.arena.voxel.mods.ModLoader
import kotlinx.coroutines.delay
import kotlin.math.sin

/**
 * Minecraft Java Edition flavoured widgets: stone buttons with hard borders, shadowed pixel-ish
 * text and a panorama-like sky background with blocky hills.
 */

val McTextShadow = Shadow(color = Color(0xFF3A3A3A), offset = Offset(1.5f, 2f), blurRadius = 0f)

@Composable
fun McButton(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var pressed by remember { mutableStateOf(false) }
    val base = when {
        !enabled -> Color(0xFF2E2E2E)
        pressed -> Color(0xFF4E4E4E)
        else -> Color(0xFF6E6E6E)
    }
    Box(
        modifier
            .height(42.dp)
            .background(
                Brush.verticalGradient(
                    listOf(base.copy(alpha = 0.98f), base.copy(alpha = 0.82f))
                )
            )
            .border(1.5.dp, Color(0xFF151515))
            .pointerInput(enabled) {
                detectTapGestures(
                    onPress = {
                        if (!enabled) return@detectTapGestures
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                    },
                    onTap = { if (enabled) onClick() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .align(Alignment.TopCenter)
                .background(Color(0x33FFFFFF))
        )
        Text(
            text,
            color = if (enabled) Color(0xFFF2F2F2) else Color(0xFF8C8C8C),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace,
            style = TextStyle(shadow = McTextShadow)
        )
    }
}

/** Sky + sun + blocky hills, used behind the menus. */
@Composable
fun McMenuBackground(modifier: Modifier = Modifier, seed: Float = 1.7f) {
    Canvas(modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.62f

        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color(0xFF5C9BE0), Color(0xFF9CC6EE), Color(0xFFCFE3F2)),
                startY = 0f,
                endY = horizon
            ),
            size = Size(w, horizon)
        )
        // Sun.
        drawCircle(Color(0xFFFFF3C4), radius = h * 0.075f, center = Offset(w * 0.78f, h * 0.16f))
        drawCircle(Color(0x40FFF6D0), radius = h * 0.12f, center = Offset(w * 0.78f, h * 0.16f))

        // Two ranges of blocky hills.
        hills(this, horizon, w, seed, color = Color(0xFF7FA9C9), heightScale = 0.16f, step = w / 44f)
        hills(this, horizon + h * 0.02f, w, seed * 2.3f, color = Color(0xFF5E8A5A), heightScale = 0.11f, step = w / 34f)

        // Ground.
        drawRect(
            brush = Brush.verticalGradient(listOf(Color(0xFF6D9E4A), Color(0xFF4A7033))),
            topLeft = Offset(0f, horizon + h * 0.03f),
            size = Size(w, h - (horizon + h * 0.03f))
        )
        // A few dirt blocks in the foreground for texture.
        val blockW = w / 28f
        var x = 0f
        var i = 0
        while (x < w) {
            if (i % 3 == 0) {
                drawRect(
                    color = Color(0xFF7A5433),
                    topLeft = Offset(x, horizon + h * 0.05f + (i % 5) * blockW * 0.4f),
                    size = Size(blockW, blockW)
                )
            }
            x += blockW
            i++
        }

        // Darkening so white text stays readable.
        drawRect(Color(0x55000000), size = Size(w, h))
    }
}

private fun hills(
    scope: DrawScope,
    baseY: Float,
    width: Float,
    seed: Float,
    color: Color,
    heightScale: Float,
    step: Float
) {
    var x = 0f
    var i = 0
    while (x < width) {
        val n = sin((i * 0.55f) + seed) * 0.6f + sin((i * 0.21f) + seed * 2f) * 0.4f
        val hh = (n * 0.5f + 0.5f) * scope.size.height * heightScale + step * 0.4f
        scope.drawRect(
            color = color,
            topLeft = Offset(x, baseY - hh),
            size = Size(step + 1f, hh)
        )
        x += step
        i++
    }
}

@Composable
fun MainMenu(
    onNewWorld: () -> Unit,
    onContinue: () -> Unit,
    onMods: () -> Unit,
    onPacks: () -> Unit,
    onSettings: () -> Unit,
    onPlay: () -> Unit = {}
) {
    val context = LocalContext.current
    val hasSave = remember { Game.hasSave }
    var importMessage by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    fun pickUri(uri: android.net.Uri?, hint: com.arena.voxel.imports.AutoImport.Kind?) {
        if (uri == null) return
        importing = true
        importMessage = null
        Game.importFile(uri, hint) { message, success, kind ->
            importing = false
            importMessage = message
            if (success && kind == com.arena.voxel.imports.AutoImport.Kind.WORLD) onPlay()
        }
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickUri(uri, null)
    }
    val texturePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickUri(uri, com.arena.voxel.imports.AutoImport.Kind.TEXTURES)
    }
    val shaderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickUri(uri, com.arena.voxel.imports.AutoImport.Kind.SHADERS)
    }
    val modPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        pickUri(uri, com.arena.voxel.imports.AutoImport.Kind.MOD)
    }
    // The import runs on a worker thread: keep the UI in sync with its state.
    LaunchedEffect(importing) {
        while (importing) {
            delay(300)
            if (!Game.importing && Game.importStatus.isNotEmpty()) {
                importing = false
                importMessage = Game.importStatus
            }
        }
    }
    val modCount = remember { ModLoader.scan().count { it.error == null } }
    val splashes = remember {
        listOf(
            "¡Con mods de Java!", "¡Texturas de Faithful!", "¡Antorchas!", "6 biomas",
            "¡Hecho en Kotlin!", "60 FPS*", "¡Cuevas nuevas!", "Pssst... ¡prueba el vuelo!"
        )
    }
    val splash = remember { splashes.random() }

    Box(Modifier.fillMaxSize()) {
        McMenuBackground()

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Spacer(Modifier.height(28.dp))
            Box(contentAlignment = Alignment.Center) {
                Text(
                    "VOXELCRAFT",
                    fontSize = 44.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    color = Color(0xFFEDEDED),
                    style = TextStyle(
                        shadow = Shadow(Color(0xFF1A1A1A), Offset(3f, 4f), 0f)
                    )
                )
            }
            Text(
                "Android · mods, shaders y texturas de Java",
                fontSize = 11.sp,
                color = Color(0xFFD8E4D0),
                fontFamily = FontFamily.Monospace,
                style = TextStyle(shadow = McTextShadow)
            )
            Spacer(Modifier.height(4.dp))
            Text(
                splash,
                fontSize = 13.sp,
                color = Color(0xFFFFF04A),
                fontFamily = FontFamily.Monospace,
                style = TextStyle(shadow = McTextShadow)
            )
            Spacer(Modifier.height(22.dp))

            Column(
                Modifier.width(360.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                McButton("Un jugador / mundo nuevo", Modifier.fillMaxWidth(), onClick = onNewWorld)
                McButton(
                    if (hasSave) "Continuar partida" else "Continuar (sin partida)",
                    Modifier.fillMaxWidth(),
                    enabled = hasSave,
                    onClick = onContinue
                )
                McButton("Importar mundo de Java", Modifier.fillMaxWidth(), enabled = !importing) {
                    filePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    McButton("Importar texturas", Modifier.weight(1f), enabled = !importing) {
                        texturePicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                    McButton("Importar shaders", Modifier.weight(1f), enabled = !importing) {
                        shaderPicker.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    McButton("Importar mod", Modifier.weight(1f), enabled = !importing) {
                        modPicker.launch(arrayOf("application/java-archive", "application/zip", "*/*"))
                    }
                    McButton("Mods  ($modCount)", Modifier.weight(1f), onClick = onMods)
                }
                McButton("Texturas / shaders instalados", Modifier.fillMaxWidth(), onClick = onPacks)
                McButton("Ajustes", Modifier.fillMaxWidth(), onClick = onSettings)
                McButton("Cerrar el juego", Modifier.fillMaxWidth(), enabled = false) { }
            }
            Spacer(Modifier.height(26.dp))
        }

        if (importing) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xC0080B10))
                    .pointerInput(Unit) { detectTapGestures { } },
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "Importando…",
                        color = Color(0xFFF2F2F2),
                        fontSize = 17.sp,
                        fontFamily = FontFamily.Monospace,
                        style = TextStyle(shadow = McTextShadow)
                    )
                    Text(
                        "El log se guardará en Descargas/VoxelCraft",
                        color = Color(0xFFB8C4B0),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        importMessage?.let { message ->
            AlertDialog(
                onDismissRequest = { importMessage = null },
                title = { Text("Importación") },
                text = {
                    Text(
                        message + "\n\nLog: Descargas/VoxelCraft (o Ajustes → Compartir log)",
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                },
                confirmButton = {
                    TextButton(onClick = { importMessage = null }) { Text("Entendido") }
                }
            )
        }

        Text(
            "VoxelCraft ${BuildConfig.VERSION_NAME}",
            color = Color(0xFFE8E8E8),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            style = TextStyle(shadow = McTextShadow),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
        )
        Text(
            "No es Minecraft oficial · sin assets de Mojang",
            color = Color(0xFFE8E8E8),
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            style = TextStyle(shadow = McTextShadow),
            textAlign = TextAlign.End,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
        )
    }
}
