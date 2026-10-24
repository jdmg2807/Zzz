package com.arena.modeler.io

import com.arena.modeler.scene.SceneDocument
import java.util.Locale

/** A file produced by an export: the model itself plus any companions (the OBJ `.mtl`). */
class ExportResult(
    val fileName: String,
    val bytes: ByteArray,
    val companions: List<ExtraFile> = emptyList()
) {
    class ExtraFile(val fileName: String, val bytes: ByteArray)
}

/**
 * Every format the app can read and write, in one place. The rest of the app only talks to
 * [SceneDocument], so adding a format here makes it available everywhere (project save, AI import,
 * share sheet) without touching the UI.
 */
enum class ExportFormat(
    /** Text shown in the export sheet. */
    val label: String,
    val extension: String,
    val mime: String,
    /** Whether the format can carry an animation (only scene-graph formats can). */
    val animation: Boolean,
    val materials: Boolean,
    val note: String
) {
    GLB("glTF binario (.glb)", "glb", "model/gltf-binary", true, true,
        "Recomendado. Geometría, materiales PBR, esqueleto y animaciones en un solo archivo."),
    GLTF("glTF texto (.gltf)", "gltf", "model/gltf+json", true, true,
        "Igual que GLB pero en JSON legible, con los buffers incrustados en base64."),
    FBX("Autodesk FBX ASCII (.fbx)", "fbx", "application/octet-stream", true, true,
        "Lo abren Blender, Maya, 3ds Max y Unity. Con animación por curvas TRS."),
    OBJ("Wavefront OBJ (.obj)", "obj", "text/plain", false, true,
        "El más compatible para impresión 3D y programas antiguos. Sin animación; se guarda un .mtl."),
    STL_BINARY("STL binario (.stl)", "stl", "model/stl", false, false,
        "Solo la superficie, en binario compacto. Sin materiales ni animación (impresión 3D)."),
    STL_ASCII("STL texto (.stl)", "stl", "model/stl", false, false,
        "Solo la superficie, legible por cualquier programa de impresión 3D."),
    PLY_ASCII("Stanford PLY texto (.ply)", "ply", "application/octet-stream", false, true,
        "Geometría con colores por material. Sin animación."),
    PLY_BINARY("Stanford PLY binario (.ply)", "ply", "application/octet-stream", false, true,
        "Más liviano que el PLY de texto, conserva los colores.")
}

/** What a file looks like once sniffed, before actually parsing it. */
class FormatInfo(val format: String, val supported: Boolean, val detail: String)

/**
 * Reads and writes every supported format. Detection is by extension first and by magic bytes
 * second, because Android's document picker happily returns files called `modelo` with no
 * extension at all.
 */
object ModelFormats {

    /** Extensions offered in the import picker. */
    val importExtensions: List<String> =
        listOf("glb", "gltf", "fbx", "obj", "stl", "ply", "dae", "3ds", "blend", "3mf", "zip", "usdz")

    val importMimeTypes: Array<String> = arrayOf(
        "model/gltf-binary", "model/gltf+json", "model/obj", "model/stl", "model/ply",
        "application/octet-stream", "application/x-tgif", "text/plain", "*/*"
    )

    fun export(doc: SceneDocument, format: ExportFormat): ExportResult {
        val base = safeName(doc.name)
        return when (format) {
            ExportFormat.GLB -> ExportResult("$base.glb", Gltf.writeGlb(doc))
            ExportFormat.GLTF -> ExportResult("$base.gltf", Gltf.writeGltf(doc))
            ExportFormat.FBX -> ExportResult("$base.fbx", Fbx.write(doc))
            ExportFormat.OBJ -> {
                val obj = Obj.write(doc)
                val mtl = Obj.ObjSidecar.mtl.toByteArray(Charsets.UTF_8)
                ExportResult(
                    "$base.obj", obj,
                    listOf(ExportResult.ExtraFile(Obj.ObjSidecar.mtlName, mtl))
                )
            }
            ExportFormat.STL_BINARY -> ExportResult("$base.stl", Stl.writeBinary(doc))
            ExportFormat.STL_ASCII -> ExportResult("$base.stl", Stl.writeAscii(doc))
            ExportFormat.PLY_ASCII -> ExportResult("$base.ply", Ply.write(doc, ascii = true))
            ExportFormat.PLY_BINARY -> ExportResult("$base.ply", Ply.write(doc, ascii = false))
        }
    }

    /**
     * Parses [bytes] into a scene. Throws [UnsupportedFormatException] with a Spanish, actionable
     * message when the file is a format the app cannot decode.
     */
    fun read(bytes: ByteArray, fileName: String): SceneDocument {
        if (bytes.isEmpty()) throw UnsupportedFormatException("El archivo está vacío.")
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1)
        if (head.startsWith("glTF")) {
            return Gltf.read(bytes, fileName)
        }
        if (head.startsWith("Kaydara FBX Binary")) {
            throw UnsupportedFormatException(
                "Este FBX está en binario y no se puede abrir.\n" +
                    "En Blender usa Archivo > Exportar > FBX y activa «ASCII», o exporta a glTF/GLB."
            )
        }
        when (ext) {
            "glb" -> return Gltf.read(bytes, fileName)
            "gltf" -> return Gltf.read(bytes, fileName)
            "fbx" -> return Fbx.read(bytes, fileName)
            "obj" -> return Obj.read(bytes, fileName)
            "stl" -> return Stl.read(bytes, fileName)
            "ply" -> return Ply.read(bytes, fileName)
            "dae", "xml" -> throw UnsupportedFormatException(
                "COLLADA (.dae) todavía no está soportado. Exporta el modelo a glTF, FBX ASCII u OBJ."
            )
            "3ds" -> throw UnsupportedFormatException(
                "3D Studio (.3ds) es un formato binario antiguo sin soporte. Convierte a glTF u OBJ."
            )
            "blend" -> throw UnsupportedFormatException(
                ".blend es el archivo interno de Blender y no se puede leer. Exporta a glTF/GLB."
            )
            "3mf", "zip", "usdz" -> throw UnsupportedFormatException(
                "Los formatos empaquetados (.3mf, .zip, .usdz) no se abren directamente.\n" +
                    "Descomprime el modelo y usa glTF, OBJ o STL."
            )
        }
        // No (or wrong) extension: sniff the contents.
        val text = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
        val sniffed = sniff(bytes, text)
        if (sniffed != null) return sniffed
        throw UnsupportedFormatException("No se reconoce el formato del archivo «$fileName».")
    }

    /** Best-effort description of a file, used to warn the user before importing. */
    fun inspect(bytes: ByteArray, fileName: String): FormatInfo {
        val ext = fileName.substringAfterLast('.', "").lowercase(Locale.US)
        if (bytes.isEmpty()) return FormatInfo("vacío", false, "0 bytes")
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.ISO_8859_1)
        val text = String(bytes, 0, minOf(bytes.size, 8192), Charsets.UTF_8)
        if (head.startsWith("glTF")) return FormatInfo("glTF binario (GLB)", true, "${bytes.size / 1024} KB")
        if (head.startsWith("Kaydara FBX Binary")) return FormatInfo("FBX binario", false, "Convierte a FBX ASCII o glTF")
        if (text.contains("FBXHeaderExtension")) return FormatInfo("FBX ASCII", true, "${bytes.size / 1024} KB")
        if (text.trimStart().startsWith("{")) return FormatInfo("glTF texto", true, "${bytes.size / 1024} KB")
        if (head.startsWith("ply")) return FormatInfo("Stanford PLY", true, "${bytes.size / 1024} KB")
        if (text.contains("<COLLADA")) return FormatInfo("COLLADA", false, "Sin soporte: exporta a glTF")
        if (ext == "stl" || head.startsWith("solid")) {
            val binary = isBinaryStl(bytes)
            return FormatInfo(if (binary) "STL binario" else "STL texto", true, "${bytes.size / 1024} KB")
        }
        if (ext == "obj" || looksLikeObj(text)) return FormatInfo("Wavefront OBJ", true, "${bytes.size / 1024} KB")
        return FormatInfo(if (ext.isEmpty()) "desconocido" else ".$ext", false, "Sin lector para este formato")
    }

    private fun sniff(bytes: ByteArray, text: String): SceneDocument? = when {
        text.trimStart().startsWith("{") && text.contains("\"asset\"") -> Gltf.read(bytes, "modelo.gltf")
        text.contains("FBXHeaderExtension") -> Fbx.read(bytes, "modelo.fbx")
        text.contains("ply") && text.contains("element vertex") -> Ply.read(bytes, "modelo.ply")
        text.contains("facet normal") -> Stl.read(bytes, "modelo.stl")
        isBinaryStl(bytes) -> Stl.read(bytes, "modelo.stl")
        looksLikeObj(text) -> Obj.read(bytes, "modelo.obj")
        else -> null
    }

    private fun looksLikeObj(text: String): Boolean {
        var hasVertex = false
        var hasFace = false
        for (line in text.lineSequence()) {
            val t = line.trimStart()
            if (t.startsWith("v ")) hasVertex = true
            if (t.startsWith("f ")) hasFace = true
            if (hasVertex && hasFace) return true
        }
        return false
    }

    private fun isBinaryStl(bytes: ByteArray): Boolean {
        if (bytes.size < 84) return false
        val triangles = (bytes[80].toInt() and 0xFF) or
            ((bytes[81].toInt() and 0xFF) shl 8) or
            ((bytes[82].toInt() and 0xFF) shl 16) or
            ((bytes[83].toInt() and 0xFF) shl 24)
        return triangles >= 0 && 84 + triangles.toLong() * 50L == bytes.size.toLong()
    }

    /** File-system safe name, used for every exported file. */
    fun safeName(raw: String): String =
        raw.trim()
            .replace(Regex("[^A-Za-z0-9ÁÉÍÓÚáéíóúÑñÜü _.-]"), "")
            .replace(' ', '_')
            .replace(Regex("_+"), "_")
            .trim('_', '.', '-')
            .ifEmpty { "modelo" }
            .take(48)
}

/** File recognised but not decodable: the message is meant to be shown to the user as-is. */
class UnsupportedFormatException(message: String) : Exception(message)
