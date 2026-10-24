package com.arena.modeler.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arena.modeler.geom.MeshOps
import com.arena.modeler.io.ExportFormat
import com.arena.modeler.scene.AnimPath
import com.arena.modeler.scene.Primitives
import com.arena.modeler.scene.SceneNode
import java.util.Locale

private val swatches: List<Pair<String, Color>> = listOf(
    "#E7E9EE" to Color(0xFFE7E9EE),
    "#B9BEC9" to Color(0xFFB9BEC9),
    "#7E8593" to Color(0xFF7E8593),
    "#3A3F4A" to Color(0xFF3A3F4A),
    "#E8892B" to Color(0xFFE8892B),
    "#E8C13B" to Color(0xFFE8C13B),
    "#57C06B" to Color(0xFF57C06B),
    "#3D9BE0" to Color(0xFF3D9BE0),
    "#4C63D2" to Color(0xFF4C63D2),
    "#9A5BD6" to Color(0xFF9A5BD6),
    "#D6559B" to Color(0xFFD6559B),
    "#D64C4C" to Color(0xFFD64C4C)
)

private fun <T> chunk(items: List<T>, size: Int): List<List<T>> = items.chunked(size)

// ---------------------------------------------------------------------------- escena

@Composable
fun ObjectsPanel(state: AppState) {
    val rev = state.revision
    val doc = state.doc ?: return
    SectionTitle("Jerarquía", "${doc.allNodes().count { it.mesh != null }} objetos con malla")
    if (doc.allNodes().isEmpty()) {
        Hint("Escena vacía. Crea geometría en la pestaña «Crear» o pídesela al chat de IA.")
    } else {
        key(rev) {
            for (node in doc.allNodes()) NodeRow(state, node)
        }
    }
    Divider()
    Text(
        "${doc.totalVertices()} vértices · ${doc.totalTriangles()} triángulos · " +
            "${doc.materials.size} materiales · ${doc.animations.size} animaciones",
        color = Palette.muted,
        fontSize = 11.sp
    )
    if (doc.totalTriangles() > 200_000) {
        Hint("La escena es pesada para un teléfono: reduce polígonos antes de seguir modelando.")
    }
    Divider()
    SectionTitle("Acciones")
    RowWrap {
        ActionButton("Duplicar", Modifier.weight(1f), enabled = state.selectedNode() != null) {
            state.duplicateSelected()
        }
        ActionButton("Eliminar", Modifier.weight(1f), enabled = state.selectedNode() != null) {
            state.deleteSelected()
        }
    }
    RowWrap {
        ActionButton("Enfocar", Modifier.weight(1f)) { state.frameSelection() }
        ActionButton("Al suelo", Modifier.weight(1f), enabled = state.selectedNode() != null) {
            state.dropToFloor()
        }
    }
}

@Composable
private fun NodeRow(state: AppState, node: SceneNode) {
    var depth = 0
    var p = node.parent
    while (p != null && p.parent != null) {
        depth++
        p = p.parent
    }
    val selected = state.selectedId == node.id
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = (depth * 12).dp, bottom = 4.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (selected) Palette.accentSoft else Palette.panelHigh.copy(alpha = 0.55f))
            .clickable { state.select(node.id) }
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .width(8.dp)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(if (node.mesh != null) Palette.accent else Palette.stroke)
        )
        Text(
            node.name,
            color = if (node.visible) Palette.text else Palette.muted,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp).weight(1f)
        )
        Text(
            node.mesh?.let { "${it.triangleCount} tri" } ?: "grupo",
            color = Palette.muted,
            fontSize = 10.sp,
            modifier = Modifier.padding(end = 8.dp)
        )
        Box(
            Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable { state.setVisible(node, !node.visible) }
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(if (node.visible) "ver" else "oculto", color = Palette.muted, fontSize = 11.sp)
        }
    }
}

// ---------------------------------------------------------------------------- crear

@Composable
fun CreatePanel(state: AppState) {
    SectionTitle("Primitivas", "se crean ya como mallas editables")
    for (row in chunk(Primitives.Kind.values().toList(), 3)) {
        RowWrap {
            for (kind in row) {
                ChipButton(kind.label, modifier = Modifier.weight(1f)) { state.addPrimitive(kind) }
            }
        }
    }
    Divider()
    SectionTitle("Geometría procedural por chat")
    Hint(
        "El chat puede encadenar herramientas para crear formas que no son primitivas: " +
            "booleanas, biseles, curvas y subdivisiones. Ejemplo: «una taza: cilindro hueco con asa " +
            "de tubo»."
    )
    ActionButton("Ir al chat de IA", Modifier.fillMaxWidth(), primary = true) {
        state.tab = Tab.CHAT
        state.panelOpen = true
    }
    Divider()
    SectionTitle("Escena")
    RowWrap {
        ActionButton("Nueva escena", Modifier.weight(1f)) { state.newScene() }
        ActionButton("Importar…", Modifier.weight(1f)) { state.tab = Tab.FILES }
    }
}

// ---------------------------------------------------------------------------- editar

@Composable
fun EditPanel(state: AppState) {
    val rev = state.revision
    val node = state.selectedNode()
    if (node == null) {
        SectionTitle("Editar")
        Hint("Toca un objeto en el visor o elígelo en «Escena».")
        return
    }
    var name by remember(node.id) { mutableStateOf(node.name) }
    TextField(
        value = name,
        onValueChange = { name = it; state.renameSelected(it) },
        singleLine = true,
        textStyle = androidx.compose.ui.text.TextStyle(color = Palette.text, fontSize = 14.sp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Palette.panelHigh,
            unfocusedContainerColor = Palette.panelHigh,
            focusedIndicatorColor = Palette.accent,
            unfocusedIndicatorColor = Palette.stroke,
            focusedTextColor = Palette.text,
            unfocusedTextColor = Palette.text
        ),
        modifier = Modifier.fillMaxWidth()
    )
    run {
        val euler = com.arena.modeler.io.Fbx.eulerFromQuat(node.rotation)
        SectionTitle("Posición", "metros")
        SliderRow("X", node.position.x, -5f..5f) { state.setPosition(it, node.position.y, node.position.z) }
        SliderRow("Y", node.position.y, -1f..6f) { state.setPosition(node.position.x, it, node.position.z) }
        SliderRow("Z", node.position.z, -5f..5f) { state.setPosition(node.position.x, node.position.y, it) }
        SectionTitle("Rotación", "grados")
        SliderRow("X", euler[0], -180f..180f) { state.setRotation(it, euler[1], euler[2]) }
        SliderRow("Y", euler[1], -180f..180f) { state.setRotation(euler[0], it, euler[2]) }
        SliderRow("Z", euler[2], -180f..180f) { state.setRotation(euler[0], euler[1], it) }
        SectionTitle("Escala")
        SliderRow("X", node.scale.x, 0.05f..5f) { state.setScale(it, node.scale.y, node.scale.z) }
        SliderRow("Y", node.scale.y, 0.05f..5f) { state.setScale(node.scale.x, it, node.scale.z) }
        SliderRow("Z", node.scale.z, 0.05f..5f) { state.setScale(node.scale.x, node.scale.y, it) }
    }
    Divider()
    SectionTitle("Herramientas de malla", "sobre el objeto seleccionado")
    key(rev, node.id) {
        MeshTools(state)
    }
    Divider()
    SculptTools(state)
}

@Composable
private fun MeshTools(state: AppState) {
    var amount by remember { mutableStateOf(0.1f) }
    SliderRow("Grosor", amount, 0.005f..0.5f) { amount = it }
    RowWrap {
        ActionButton("Extruir", Modifier.weight(1f)) { state.extrude(amount) }
        ActionButton("Inset", Modifier.weight(1f)) { state.inset(amount) }
        ActionButton("Bisel", Modifier.weight(1f)) { state.bevel(amount) }
    }
    RowWrap {
        ActionButton("Subdividir", Modifier.weight(1f)) { state.subdivide(1) }
        ActionButton("Suavizar", Modifier.weight(1f)) { state.smoothMesh(1, 0.5f) }
        ActionButton("Espejar", Modifier.weight(1f)) { state.mirror(MeshOps.Axis.X) }
    }
    RowWrap {
        ActionButton("Booleana unión", Modifier.weight(1f)) { state.booleanWithSelectedOther("union") }
        ActionButton("Booleana resta", Modifier.weight(1f)) { state.booleanWithSelectedOther("difference") }
    }
    RowWrap {
        ActionButton("Reducir polígonos", Modifier.weight(1f)) { state.reduceMesh() }
        ActionButton("Reparar", Modifier.weight(1f)) { state.repairMesh() }
    }
    Hint("Las booleanas usan el segundo objeto de la lista. El resultado es reversible con Deshacer.")
}

@Composable
private fun SculptTools(state: AppState) {
    SectionTitle("Esculpido", "arrastra en el visor")
    RowWrap {
        for (mode in MeshOps.SculptMode.values()) {
            ChipButton(mode.label, selected = state.sculptMode == mode, modifier = Modifier.weight(1f)) {
                state.sculptMode = mode
            }
        }
    }
    SliderRow("Radio", state.sculptRadius, 0.02f..1f) { state.sculptRadius = it }
    SliderRow("Fuerza", state.sculptStrength, -0.2f..0.2f) { state.sculptStrength = it }
    Hint(
        "El pincel se aplica desde la superficie hacia fuera (o hacia dentro con fuerza negativa). " +
            "Cada pincelada queda en el historial."
    )
}

// ---------------------------------------------------------------------------- material

@Composable
fun MaterialPanel(state: AppState) {
    val rev = state.revision
    val node = state.selectedNode()
    if (node == null) {
        SectionTitle("Material")
        Hint("Selecciona un objeto para editar su color y su acabado.")
        return
    }
    val mat = node.material
    key(rev, node.id) {
        SectionTitle("Color", node.name)
        for (row in chunk(swatches, 6)) {
            RowWrap(4) {
                for (pair in row) {
                    ColorSwatch(
                        color = pair.second,
                        selected = mat != null && colorHex(mat.baseColor) == pair.first
                    ) { state.setMaterialColor(hexToFloat(pair.first)) }
                }
            }
        }
        Divider()
        SectionTitle("Acabado")
        SliderRow("Metal", mat?.metallic ?: 0f, 0f..1f) { state.setMaterialProps(metallic = it) }
        SliderRow("Rugos.", mat?.roughness ?: 0.6f, 0.02f..1f) { state.setMaterialProps(roughness = it) }
        ToggleRow(
            "Emisión",
            mat?.emission?.let { it[0] + it[1] + it[2] > 0.01f } == true
        ) { state.setEmission(it) }
        Hint(
            "El color y el acabado se exportan a glTF/GLB, FBX y OBJ. Las texturas de imagen se " +
                "conservan al importar y exportar glTF, pero este visor aún no muestrea imágenes."
        )
    }
}

private fun colorHex(c: FloatArray): String = String.format(
    Locale.US,
    "#%02X%02X%02X",
    (c[0] * 255).toInt().coerceIn(0, 255),
    (c[1] * 255).toInt().coerceIn(0, 255),
    (c[2] * 255).toInt().coerceIn(0, 255)
)

private fun hexToFloat(hex: String): FloatArray {
    val v = hex.removePrefix("#").toLongOrNull(16) ?: return floatArrayOf(1f, 1f, 1f, 1f)
    return floatArrayOf(
        ((v shr 16) and 0xFF) / 255f,
        ((v shr 8) and 0xFF) / 255f,
        (v and 0xFF) / 255f,
        1f
    )
}

// ---------------------------------------------------------------------------- animar

@Composable
fun AnimatePanel(state: AppState) {
    val rev = state.revision
    val doc = state.doc ?: return
    val node = state.selectedNode()
    SectionTitle("Animación", "${doc.animations.size} clips")
    if (node != null) {
        Hint("Objeto seleccionado: ${node.name}")
    }
    RowWrap {
        ActionButton("Nuevo giro", Modifier.weight(1f)) { state.createTurntable() }
        ActionButton("Borrar clip", Modifier.weight(1f), enabled = state.currentClip() != null) {
            state.deleteClip()
        }
    }
    if (doc.animations.isEmpty()) {
        Hint("El giro automático crea un clip de 4 s con rotación y rebote, para animar un objeto desde cero.")
        return
    }
    SectionTitle("Clips")
    key(rev) {
        for (row in chunk(doc.animations, 2)) {
            RowWrap {
                for (clip in row) {
                    val index = doc.animations.indexOf(clip)
                    ChipButton(clip.name, selected = state.clipIndex == index, modifier = Modifier.weight(1f)) {
                        state.clipIndex = index
                        state.seekAnimation(0f)
                    }
                }
            }
        }
    }
    val clip = state.currentClip() ?: return
    val duration = clip.duration().coerceAtLeast(0.1f)
    SectionTitle("Reproducir", "duración ${"%.2f".format(duration)} s")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        ActionButton(if (state.playing) "Pausa" else "Reproducir") { state.togglePlay() }
        Box(Modifier.width(10.dp))
        Slider(
            value = state.animTime.coerceIn(0f, duration),
            onValueChange = { state.seekAnimation(it) },
            valueRange = 0f..duration,
            modifier = Modifier.weight(1f).height(32.dp),
            colors = SliderDefaults.colors(
                thumbColor = Palette.accent,
                activeTrackColor = Palette.accent,
                inactiveTrackColor = Palette.stroke
            )
        )
        Text(
            "%.2f".format(state.animTime),
            color = Palette.text,
            fontSize = 12.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
    SectionTitle("Añadir clave")
    RowWrap {
        ActionButton("Posición", Modifier.weight(1f)) { state.keyframe(AnimPath.TRANSLATION) }
        ActionButton("Rotación", Modifier.weight(1f)) { state.keyframe(AnimPath.ROTATION) }
        ActionButton("Escala", Modifier.weight(1f)) { state.keyframe(AnimPath.SCALE) }
    }
    Hint("Las claves se guardan en el clip actual y sobreviven al exportar a GLB, glTF o FBX.")
}

// ---------------------------------------------------------------------------- archivo

@Composable
fun FilesPanel(state: AppState, onImport: () -> Unit) {
    val rev = state.revision
    val doc = state.doc
    SectionTitle("Exportar", "se verifica antes de dar el visto bueno")
    for (row in chunk(ExportFormat.values().toList(), 2)) {
        RowWrap {
            for (format in row) {
                ChipButton(
                    format.label.substringBefore(" (").ifEmpty { format.label },
                    modifier = Modifier.weight(1f)
                ) { state.export(format) }
            }
        }
    }
    if (doc != null && doc.animations.isNotEmpty()) {
        Hint("Este modelo tiene ${doc.animations.size} animación(es): usa GLB o FBX para conservarlas.")
    }
    Hint(
        "Antes de confirmar, el archivo se vuelve a leer y se comprueban los triángulos: si algo " +
            "falla, la app lo dice y no afirma que se exportó."
    )
    Divider()
    SectionTitle("Importar")
    ActionButton("Abrir archivo del teléfono", Modifier.fillMaxWidth(), primary = true) { onImport() }
    Hint(
        "glTF/GLB, FBX ASCII, OBJ, STL y PLY. Los FBX binarios, .blend, .dae y .3ds no se abren: " +
            "conviértelos a glTF."
    )
    Divider()
    SectionTitle("Proyectos")
    RowWrap {
        ActionButton("Guardar proyecto", Modifier.weight(1f), primary = true) { state.saveProject() }
        ActionButton("Nueva escena", Modifier.weight(1f)) { state.newScene() }
    }
    val projects = state.projects()
    if (projects.isEmpty()) {
        Hint("Todavía no hay proyectos guardados.")
    } else {
        key(rev) {
            for (p in projects.take(8)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Palette.panelHigh.copy(alpha = 0.6f))
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(p.title, color = Palette.text, fontSize = 13.sp)
                        Text("${p.bytes / 1024} KB", color = Palette.muted, fontSize = 11.sp)
                    }
                    ActionButton("Abrir") { state.openProject(p) }
                    ActionButton("Borrar") { state.deleteProject(p) }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- historial

@Composable
fun HistoryPanel(state: AppState) {
    SectionTitle("Historial de cambios", "${state.historyEntries.size} entradas")
    RowWrap {
        ActionButton("Deshacer", Modifier.weight(1f), enabled = state.undoDepth > 0) { state.undo() }
        ActionButton("Rehacer", Modifier.weight(1f), enabled = state.redoDepth > 0) { state.redo() }
    }
    Hint("Deshacer guarda hasta 20 estados completos de la escena, incluidos los cambios hechos por la IA.")
    Divider()
    if (state.historyEntries.isEmpty()) {
        Hint("Aún no hay cambios registrados en esta sesión.")
        return
    }
    for (entry in state.historyEntries.take(30)) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Palette.panelHigh.copy(alpha = 0.5f))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(entry.text, color = Palette.text, fontSize = 12.sp)
            if (entry.detail.isNotBlank()) {
                Text(entry.detail, color = Palette.muted, fontSize = 10.sp, maxLines = 2)
            }
        }
    }
}
