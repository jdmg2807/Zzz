package com.arena.voxel.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * Everything that happens in the game is written here: startup, world generation, texture packs,
 * shaders (including their compiler logs), mods and world imports.
 *
 * The log lives in `files/logs/voxelcraft.log` and can be exported to the phone's **Downloads**
 * folder (`Download/VoxelCraft/`) so it can be shared for debugging.
 */
object Logs {

    private const val MAX_BUFFER = 4000
    private const val MAX_FILE_BYTES = 1_500_000L

    private val buffer = ArrayDeque<String>()
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    @Volatile
    var sessionName: String = ""

    val logFile: File
        get() {
            val dir = File(Ctx.base, "logs")
            if (!dir.exists()) dir.mkdirs()
            return File(dir, "voxelcraft.log")
        }

    fun startSession(header: String = "") {
        sessionName = fileFormat.format(Date())
        line("INFO", "SESSION", "===== Nueva sesión $sessionName =====")
        if (header.isNotEmpty()) line("INFO", "SESSION", header)
    }

    fun info(tag: String, message: String) = line("INFO", tag, message)

    fun warn(tag: String, message: String) = line("WARN", tag, message)

    fun error(tag: String, message: String, throwable: Throwable? = null) =
        line("ERROR", tag, message, throwable)

    fun line(level: String, tag: String, message: String, throwable: Throwable? = null) {
        val text = buildString {
            append(timeFormat.format(Date()))
            append(' ')
            append(level.padEnd(5))
            append(" [")
            append(tag)
            append("] ")
            append(message)
            if (throwable != null) {
                append("\n")
                append(throwable.stackTraceToString())
            }
        }
        synchronized(lock) {
            buffer.addLast(text)
            while (buffer.size > MAX_BUFFER) buffer.removeFirst()
            try {
                val f = logFile
                if (f.length() > MAX_FILE_BYTES) {
                    val old = File(f.parentFile, "voxelcraft-old.log")
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
                f.appendText(text + "\n")
            } catch (t: Throwable) {
                // Never let logging break the game.
            }
        }
    }

    /** Pretty section header, easier to read when the log is shared. */
    fun section(title: String) {
        line("INFO", "----", "==== $title ====")
    }

    fun dump(): String = synchronized(lock) {
        buildString {
            append("VoxelCraft — registro de diagnóstico\n")
            append("Sesión: $sessionName\n")
            append("Dispositivo: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})\n")
            append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.joinToString()}\n")
            append("Carpeta del juego: ${Ctx.base.absolutePath}\n")
            append("----------------------------------------\n")
            for (l in buffer) {
                append(l)
                append('\n')
            }
        }
    }

    private fun exportName(): String = "voxelcraft-${sessionName.ifEmpty { fileFormat.format(Date()) }}.txt"

    /**
     * Copies the log into the public Downloads folder (Download/VoxelCraft/...).
     * Uses MediaStore on Android 10+ (no permission needed) and the legacy public folder on
     * older versions. Returns a human readable result message.
     */
    fun exportToDownloads(context: Context): String {
        val content = dump()
        val name = exportName()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(
                        MediaStore.MediaColumns.RELATIVE_PATH,
                        "${Environment.DIRECTORY_DOWNLOADS}/VoxelCraft"
                    )
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: return failLegacy(context, content, name)
                resolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray())
                } ?: return "No se pudo escribir el log en Descargas"
                info("LOGS", "Log exportado a Descargas/VoxelCraft/$name")
                "Log guardado en Descargas/VoxelCraft/$name"
            } else {
                failLegacy(context, content, name)
            }
        } catch (t: Throwable) {
            error("LOGS", "Fallo exportando a Descargas", t)
            // Always keep a copy inside the app folder.
            val fallback = File(logFile.parentFile, name)
            fallback.writeText(content)
            "No se pudo usar Descargas (${t.message}); copia guardada en ${Ctx.relativePath(fallback)}"
        }
    }

    private fun failLegacy(context: Context, content: String, name: String): String {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                "VoxelCraft"
            )
            if (!dir.exists()) dir.mkdirs()
            val out = File(dir, name)
            out.writeText(content)
            info("LOGS", "Log exportado (legacy) a ${out.absolutePath}")
            "Log guardado en Descargas/VoxelCraft/$name"
        } catch (t: Throwable) {
            error("LOGS", "Fallo exportando (legacy)", t)
            val fallback = File(logFile.parentFile, name)
            fallback.writeText(content)
            "Copia guardada en ${Ctx.relativePath(fallback)}"
        }
    }

    /** Shares the log through any app (WhatsApp, Telegram, Drive...). No permissions needed. */
    fun share(context: Context): String {
        return try {
            val out = File(logFile.parentFile, exportName())
            out.writeText(dump())
            val uri: Uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.logs",
                out
            )
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "VoxelCraft log")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Compartir log de VoxelCraft"))
            "Compartiendo ${out.name}"
        } catch (t: Throwable) {
            error("LOGS", "Fallo compartiendo el log", t)
            val msg = exportToDownloads(context)
            "No se pudo compartir (${t.message}). $msg"
        }
    }
}
