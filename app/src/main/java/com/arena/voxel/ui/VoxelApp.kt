package com.arena.voxel.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Surface
import com.arena.voxel.BuildConfig
import com.arena.voxel.Game
import com.arena.voxel.Settings
import com.arena.voxel.core.Ctx
import com.arena.voxel.mods.ModLoader
import com.arena.voxel.packs.ResourcePacks
import java.io.File
import kotlin.math.roundToInt

val BgDark = Color(0xFF0B0D12)
val Accent = Color(0xFF7CC242)
val AccentDark = Color(0xFF3C6B1F)
val PanelColor = Color(0xFF161A22)

@Composable
fun VoxelApp() {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            onPrimary = Color(0xFF0B1A05),
            background = BgDark,
            surface = PanelColor,
            onSurface = Color(0xFFE8EDF2)
        )
    ) {
        var screen by rememberSaveable { mutableStateOf("menu") }
        // Bedrock-style soundtrack: menu music outside the world, world music inside it.
        androidx.compose.runtime.LaunchedEffect(screen) {
            if (!Settings.music) {
                com.arena.voxel.core.Music.stop()
            } else if (screen == "game") {
                com.arena.voxel.core.Music.scene("world")
            } else {
                com.arena.voxel.core.Music.scene("menu")
            }
        }
        val context = LocalContext.current
        Surface(modifier = Modifier.fillMaxSize(), color = BgDark) {
            when (screen) {
                "menu" -> MainMenu(
                    onSingleplayer = { screen = "singleplayer" },
                    onSettings = { screen = "settings" },
                    onQuit = {
                        // Java's "Quit Game" closes the game; on Android that is finishing the Activity.
                        (context as? android.app.Activity)?.finish()
                    },
                    onPlay = { screen = "game" },
                    onMods = { screen = "mods" },
                    onPacks = { screen = "packs" },
                    forcedScale = Settings.guiScale
                )
                "singleplayer" -> SingleplayerScreen(
                    onBack = { screen = "menu" },
                    onPlay = {
                        if (!Game.hasSave) Game.startNewWorld(System.currentTimeMillis())
                        else Game.startLoadedWorld()
                        screen = "game"
                    },
                    forcedScale = Settings.guiScale
                )
                "mods" -> ModsScreen(onBack = { screen = "menu" })
                "packs" -> PacksScreen(onBack = { screen = "menu" })
                "settings" -> SettingsScreen(
                    onBack = { screen = "menu" },
                    onMods = { screen = "mods" },
                    onPacks = { screen = "packs" }
                )
                "game" -> GameScreen(onExit = { screen = "menu" })
            }
        }
    }
}

@Composable
internal fun ScreenTitle(title: String, subtitle: String = "") {
    Column(Modifier.fillMaxWidth()) {
        Text(title.uppercase(), color = Accent, fontSize = 22.sp, fontWeight = FontWeight.Black)
        if (subtitle.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = Color(0xFF8A94A6), fontSize = 12.sp)
        }
    }
}

@Composable
internal fun MenuButton(
    text: String,
    primary: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    if (primary) {
        Button(
            onClick = onClick,
            modifier = modifier.height(54.dp),
            shape = RoundedCornerShape(12.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color(0xFF0B1A05))
        ) {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(48.dp),
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0x33FFFFFF))
        ) {
            Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFDCE3EA))
        }
    }
}

@Composable
private fun ModsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val mods = remember(refresh) { ModLoader.scan() }
    // Java jars (Fabric/Forge/datapack) are described without loading any class.
    val javaMods = remember(refresh) {
        try {
            com.arena.voxel.mods.JavaModSupport.scanAll()
        } catch (t: Throwable) {
            emptyList()
        }
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = Imports.displayName(context, uri) ?: "mod.jar"
            context.contentResolver.openInputStream(uri)?.use { input ->
                ModLoader.importMod(input, if (name.endsWith(".jar")) name else "$name.jar")
            }
            refresh++
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Volver", color = Accent) }
            Spacer(Modifier.width(8.dp))
            ScreenTitle("Mods", "Coloca tus .jar de mods en ${Ctx.relativePath(Ctx.modsDir)}")
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { importer.launch(arrayOf("*/*")) }) { Text("Importar mod .jar") }
            OutlinedButton(onClick = { refresh++ }) { Text("Actualizar") }
        }
        Spacer(Modifier.height(12.dp))

        if (mods.isEmpty()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(12.dp))
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No hay mods instalados.\n\nCrea un .jar con un voxelmod.json y una clase que implemente com.arena.voxel.api.VoxelMod,\n" +
                        "o usa el mod de ejemplo que incluye el proyecto.",
                    color = Color(0xFF8A94A6),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(mods) { mod ->
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(PanelColor, RoundedCornerShape(10.dp))
                            .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
                            .padding(12.dp)
                    ) {
                        Text(mod.name + "  " + mod.version, color = Color(0xFFE8EDF2), fontWeight = FontWeight.Bold)
                        Text("id: ${mod.id} · por ${mod.author}", color = Color(0xFF7E8A9A), fontSize = 11.sp)
                        val modError = mod.error
                        if (modError != null) {
                            Text(modError, color = Color(0xFFE57373), fontSize = 11.sp)
                        } else {
                            Text("Se carga al entrar al mundo", color = Accent, fontSize = 11.sp)
                        }
                    }
                }
                if (javaMods.isNotEmpty()) {
                    item {
                        Text(
                            "MODS DE MINECRAFT JAVA",
                            color = Color(0xFF7E8A9A),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    items(javaMods) { mod ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .background(PanelColor, RoundedCornerShape(10.dp))
                                .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
                                .padding(12.dp)
                        ) {
                            Text(
                                "${mod.name}  ${mod.version}",
                                color = Color(0xFFE8EDF2),
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                "${mod.kind.name.lowercase()} · id: ${mod.id} · ${mod.authors}",
                                color = Color(0xFF7E8A9A),
                                fontSize = 11.sp
                            )
                            if (mod.description.isNotEmpty()) {
                                Text(mod.description, color = Color(0xFFB8C2CE), fontSize = 11.sp)
                            }
                            if (mod.note.isNotEmpty()) {
                                Text(
                                    mod.note,
                                    color = if (mod.runsCode) Accent else Color(0xFFFFCC80),
                                    fontSize = 11.sp
                                )
                            }
                            if (mod.entrypoints.isNotEmpty() && !mod.runsCode) {
                                Text(
                                    "Entrypoint: ${mod.entrypoints.first()}",
                                    color = Color(0xFF7E8A9A),
                                    fontSize = 10.sp
                                )
                            }
                        }
                    }
                }
                item {
                    Text(
                        "Los mods se cargan al entrar a un mundo. Si editas un jar, vuelve a entrar.",
                        color = Color(0xFF6B7A80),
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun PacksScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf("") }
    val packs = remember(refresh) { ResourcePacks.list() }
    val shaders = remember(refresh) { ResourcePacks.listShaderPacks() }

    val packImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = Imports.displayName(context, uri) ?: "pack.zip"
            message = Imports.copy(context, uri, Ctx.packsDir, name)
            refresh++
        }
    }
    val shaderImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val name = Imports.displayName(context, uri) ?: "shaders.zip"
            message = Imports.copy(context, uri, Ctx.shaderPacksDir, name)
            refresh++
        }
    }

    Column(Modifier.fillMaxSize().padding(20.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("‹ Volver", color = Accent) }
            Spacer(Modifier.width(8.dp))
            ScreenTitle("Texturas y shaders", "Compatible con resource packs de Minecraft Java (.zip)")
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { packImporter.launch(arrayOf("*/*")) }) { Text("Importar resource pack") }
            OutlinedButton(onClick = { shaderImporter.launch(arrayOf("*/*")) }) { Text("Importar shader pack") }
            OutlinedButton(onClick = { refresh++ }) { Text("Actualizar") }
        }
        if (message.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(message, color = Accent, fontSize = 11.sp)
        }
        Spacer(Modifier.height(12.dp))

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                // What ships inside the APK: the vanilla Java assets.
                Text(
                    "ASSETS DE VANILLA INCLUIDOS",
                    color = Color(0xFF7E8A9A),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    com.arena.voxel.core.VanillaAssets.summary() +
                        " · sonidos, modelos y nombres en español de Minecraft Java 1.21",
                    color = Color(0xFFB8C2CE),
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(8.dp))
            }
            item { Text("RESOURCE PACKS", color = Color(0xFF7E8A9A), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
            if (packs.isEmpty()) {
                item {
                    Text(
                        "Ninguno instalado. Pega tus .zip en ${Ctx.relativePath(Ctx.packsDir)} o impórtalos aquí.",
                        color = Color(0xFF8A94A6), fontSize = 12.sp
                    )
                }
            }
            items(packs) { pack ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(PanelColor, RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(pack.name, color = Color(0xFFE8EDF2), fontWeight = FontWeight.Bold)
                    Text(
                        "${pack.tiles} texturas · pack_format ${pack.format} · ${pack.shaderFiles} shaders",
                        color = Color(0xFF7E8A9A), fontSize = 11.sp
                    )
                    if (pack.description.isNotEmpty()) {
                        Text(pack.description, color = Color(0xFF9AA6B2), fontSize = 11.sp)
                    }
                    if (pack.javaShader) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Shader pack de Iris/OptiFine detectado: sus programas (gbuffers/composite/shadow) " +
                                "no son compatibles con este motor, así que solo se aplican sus texturas.",
                            color = Color(0xFFE5B567),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(10.dp))
                Text("SHADER PACKS", color = Color(0xFF7E8A9A), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
            if (shaders.isEmpty()) {
                item {
                    Text(
                        "Ninguno instalado. Acepta terrain/water/sky/post .vsh y .fsh en shaders/.",
                        color = Color(0xFF8A94A6), fontSize = 12.sp
                    )
                }
            }
            items(shaders) { pack ->
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(PanelColor, RoundedCornerShape(10.dp))
                        .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    Text(pack.name, color = Color(0xFFE8EDF2), fontWeight = FontWeight.Bold)
                    Text("${pack.shaderFiles} archivos de shader", color = Color(0xFF7E8A9A), fontSize = 11.sp)
                    if (pack.javaShader) {
                        Text(
                            "Pipeline de Iris/OptiFine: no compatible (se omite)",
                            color = Color(0xFFE5B567),
                            fontSize = 11.sp
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = {
                    message = "Shaders de ejemplo exportados a ${ResourcePacks.exportExampleShaderPack()}"
                    refresh++
                }) { Text("Exportar shaders de ejemplo") }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Los shaders integrados son terrain, water, sky y post (efecto de pantalla completa). " +
                        "Exporta el ejemplo y edítalo para crear el tuyo.",
                    color = Color(0xFF6B7A80),
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * Pantalla de **Ajustes** con el lenguaje visual de Bedrock.
 *
 * Bedrock agrupa las opciones en secciones con barra de título y usa deslizadores de piedra en
 * lugar de los de Material; los interruptores son placas "SÍ/NO". La lógica de guardado es la
 * misma: al volver se escriben los valores en [Settings] y se aplican sonido y música.
 */
@Composable
private fun SettingsScreen(onBack: () -> Unit, onMods: () -> Unit = {}, onPacks: () -> Unit = {}) {
    val context = LocalContext.current
    var sens by remember { mutableStateOf(Settings.sensitivity) }
    var distance by remember { mutableStateOf(Settings.renderDistance.toFloat()) }
    var fov by remember { mutableStateOf(Settings.fov) }
    var hud by remember { mutableStateOf(Settings.showHud) }
    var guiScale by remember { mutableStateOf(Settings.guiScale) }
    var usePacks by remember { mutableStateOf(Settings.usePacks) }
    var useShaders by remember { mutableStateOf(Settings.useShaders) }
    var useMods by remember { mutableStateOf(Settings.useMods) }
    var sound by remember { mutableStateOf(Settings.sound) }
    var music by remember { mutableStateOf(Settings.music) }
    var logMessage by remember { mutableStateOf("") }

    fun saveAndBack() {
        Settings.sensitivity = sens
        Settings.renderDistance = distance.roundToInt()
        Settings.fov = fov
        Settings.showHud = hud
        Settings.usePacks = usePacks
        Settings.useShaders = useShaders
        Settings.useMods = useMods
        Settings.guiScale = guiScale
        Settings.sound = sound
        Settings.music = music
        com.arena.voxel.core.Sounds.enabled = sound
        com.arena.voxel.core.Music.enabled = music
        if (music) com.arena.voxel.core.Music.scene("menu") else com.arena.voxel.core.Music.stop()
        Settings.save(context)
        onBack()
    }

    GuiScreen(Settings.guiScale) { gui ->
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            PanoramaBackground()

            // ---- Barra superior: flecha de volver + título (Bedrock) --------------------
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color(0x00000000))))
                    .padding(horizontal = gui.px(6f), vertical = gui.px(5f)),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BedrockButton(gui, "◀", width = gui.px(20f), height = gui.px(18f), fontSize = 9f) { saveAndBack() }
                Spacer(Modifier.width(gui.px(6f)))
                BedrockText(gui, "Ajustes", size = 11f, bold = true)
            }

            // ---- Panel de opciones -----------------------------------------------------
            BedrockPanel(
                gui,
                Modifier
                    .align(Alignment.Center)
                    .padding(top = gui.px(14f), bottom = gui.px(8f))
                    .width(gui.px(320f))
                    .height(gui.px(190f))
            ) {
                Column(Modifier.fillMaxSize()) {
                    BedrockTitleBar(gui, "Ajustes del juego")
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(gui.px(8f)),
                        verticalArrangement = Arrangement.spacedBy(gui.px(6f))
                    ) {
                        SectionLabel(gui, "CONTROLES Y CÁMARA")
                        BedrockSliderRow(
                            gui,
                            "Sensibilidad",
                            "${(sens * 100).roundToInt()} %",
                            sens,
                            0.2f..2.5f
                        ) { sens = it }
                        BedrockSliderRow(
                            gui,
                            "Campo de visión",
                            "${fov.roundToInt()}°",
                            fov,
                            50f..100f
                        ) { fov = it }
                        BedrockSliderRow(
                            gui,
                            "Distancia de render",
                            "${distance.roundToInt()} chunks",
                            distance,
                            2f..6f,
                            steps = 3
                        ) { distance = it }

                        SectionLabel(gui, "INTERFAZ")
                        BedrockSliderRow(
                            gui,
                            "Escala de interfaz",
                            if (guiScale == 0) "Automática" else "Nivel $guiScale",
                            guiScale.toFloat(),
                            0f..4f,
                            steps = 3
                        ) { guiScale = it.roundToInt() }
                        BedrockToggle(gui, "Mostrar HUD", hud, width = gui.px(304f)) { hud = it }

                        SectionLabel(gui, "SONIDO")
                        BedrockToggle(gui, "Efectos de sonido", sound, width = gui.px(304f)) { sound = it }
                        BedrockToggle(gui, "Música", music, width = gui.px(304f)) { music = it }

                        SectionLabel(gui, "CONTENIDO")
                        BedrockToggle(gui, "Resource packs (texturas)", usePacks, width = gui.px(304f)) { usePacks = it }
                        BedrockToggle(gui, "Shaders", useShaders, width = gui.px(304f)) { useShaders = it }
                        BedrockToggle(gui, "Mods", useMods, width = gui.px(304f)) { useMods = it }
                        Row(horizontalArrangement = Arrangement.spacedBy(gui.px(5f))) {
                            BedrockButton(gui, "Texturas y shaders", width = gui.px(149f), height = gui.px(20f), fontSize = 7.5f) {
                                onPacks()
                            }
                            BedrockButton(gui, "Mods instalados", width = gui.px(149f), height = gui.px(20f), fontSize = 7.5f) {
                                onMods()
                            }
                        }

                        SectionLabel(gui, "DIAGNÓSTICO")
                        Row(horizontalArrangement = Arrangement.spacedBy(gui.px(5f))) {
                            BedrockButton(gui, "Guardar log", width = gui.px(149f), height = gui.px(20f), fontSize = 7.5f) {
                                logMessage = com.arena.voxel.core.Logs.exportToDownloads(context)
                            }
                            BedrockButton(gui, "Compartir log", width = gui.px(149f), height = gui.px(20f), fontSize = 7.5f) {
                                logMessage = com.arena.voxel.core.Logs.share(context)
                            }
                        }
                        if (logMessage.isNotEmpty()) {
                            BedrockText(gui, logMessage, size = 6.5f, color = Bedrock.accent)
                        }
                        BedrockText(
                            gui,
                            "Carpeta del juego: ${Ctx.base.absolutePath}",
                            size = 6f,
                            color = Bedrock.textDim
                        )
                        BedrockText(
                            gui,
                            "Joystick para moverte, arrastra a la derecha para mirar, toca para colocar y mantén para romper.",
                            size = 6f,
                            color = Bedrock.textDim
                        )
                    }
                }
            }
        }
    }
}

/** Etiqueta de sección de los ajustes: verde y en mayúsculas, como en Bedrock. */
@Composable
private fun SectionLabel(gui: Gui, text: String) {
    BedrockText(gui, text, size = 7f, color = Bedrock.accent, bold = true)
}

/** Fila de ajuste con deslizador de Bedrock: título + valor a la derecha y la barra debajo. */
@Composable
private fun BedrockSliderRow(
    gui: Gui,
    title: String,
    value: String,
    current: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            BedrockText(gui, title, size = 7.5f)
            BedrockText(gui, value, size = 7.5f, color = Bedrock.accent)
        }
        BedrockSlider(
            gui = gui,
            value = current,
            valueRange = range,
            width = gui.px(304f),
            steps = steps,
            onChange = onChange
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color(0xFFDCE3EA), fontSize = 14.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingRow(title: String, value: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = Color(0xFFDCE3EA), fontSize = 14.sp)
            Text(value, color = Accent, fontSize = 13.sp)
        }
        content()
    }
}

object Imports {
    fun displayName(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
            }
        } catch (t: Throwable) {
            uri.lastPathSegment
        }
    }

    fun copy(context: Context, uri: Uri, dir: File, fileName: String): String {
        return try {
            dir.mkdirs()
            val target = File(dir, fileName)
            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            "Importado: $fileName"
        } catch (t: Throwable) {
            "No se pudo importar: ${t.message}"
        }
    }
}
