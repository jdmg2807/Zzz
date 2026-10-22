package com.arena.voxel.core

import android.content.Context
import android.util.Log as ALog
import java.io.File

/** Global application context and storage folders. */
object Ctx {
    lateinit var app: Context
        private set

    fun init(context: Context) {
        app = context.applicationContext
    }

    /** Base folder: external app folder when available (visible over USB / file managers). */
    val base: File
        get() {
            val ext = app.getExternalFilesDir(null)
            val dir = ext ?: app.filesDir
            if (!dir.exists()) dir.mkdirs()
            return dir
        }

    val modsDir: File get() = dir("mods")
    val packsDir: File get() = dir("resourcepacks")
    val shaderPacksDir: File get() = dir("shaderpacks")
    val savesDir: File get() = dir("saves")

    private fun dir(name: String): File {
        val d = File(base, name)
        if (!d.exists()) d.mkdirs()
        return d
    }

    val downloadsLogHint: String
        get() = "Descargas/VoxelCraft/voxelcraft-<fecha>.txt"

    fun relativePath(file: File): String =
        try {
            file.absolutePath.substring(base.absolutePath.length).trimStart('/')
        } catch (t: Throwable) {
            file.name
        }
}

/**
 * Shorthand used everywhere. Everything is mirrored into [Logs], which keeps the exported file
 * with the whole history of the session.
 */
object Log {
    private const val TAG = "VoxelCraft"

    fun i(msg: String) {
        ALog.i(TAG, msg)
        Logs.info(TAG, msg)
    }

    fun w(msg: String) {
        ALog.w(TAG, msg)
        Logs.warn(TAG, msg)
    }

    fun e(msg: String, t: Throwable? = null) {
        ALog.e(TAG, msg, t)
        Logs.error(TAG, msg, t)
    }

    fun tag(name: String, msg: String) {
        ALog.i(name, msg)
        Logs.info(name, msg)
    }

    fun tagError(name: String, msg: String, t: Throwable? = null) {
        ALog.e(name, msg, t)
        Logs.error(name, msg, t)
    }
}
