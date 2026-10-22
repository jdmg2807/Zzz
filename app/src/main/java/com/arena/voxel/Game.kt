package com.arena.voxel

import android.content.Context
import com.arena.voxel.core.Ctx
import com.arena.voxel.core.Log
import com.arena.voxel.mods.ModLoader
import com.arena.voxel.player.BlockHit
import com.arena.voxel.player.Player
import com.arena.voxel.world.Blocks
import com.arena.voxel.world.World
import java.io.File
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/** Global game state shared between the OpenGL thread and the Compose UI. */
object Game {

    enum class State { MENU, LOADING, PLAYING }

    const val SAVE_NAME = "mundo1"

    @Volatile var state = State.MENU
        private set
    @Volatile var status = ""
    @Volatile var progress = 0f
    @Volatile var fps = 0

    @Volatile var targetHit: BlockHit? = null
    @Volatile var selectedSlot = 0
    @Volatile var hudText = ""
    @Volatile var underwater = false
    @Volatile var shaderPackName = "integrado"
    @Volatile var packReport = ""
    @Volatile var modReport = ""
    @Volatile var chunksReady = 0
    @Volatile var paused = false

    val hotbar = CopyOnWriteArrayList<String>()

    /** Touch input, written by the UI thread and read by the render thread. */
    val move = FloatArray(2)
    @Volatile var jump = false
    @Volatile var down = false

    var world: World? = null
    var player: Player? = null

    @Volatile var requestedSeed: Long = 0L
    @Volatile var pendingStart = false
        private set
    @Volatile var pendingLoad = false
        private set

    private var lookDX = 0f
    private var lookDY = 0f
    private val lookLock = Any()
    private val action = AtomicInteger(0)
    private val saveLock = Any()

    val chatLines: MutableList<String> = Collections.synchronizedList(ArrayList<String>())

    val savesDir: File get() = File(Ctx.savesDir, SAVE_NAME)
    val hasSave: Boolean get() = File(savesDir, "level.dat").isFile

    init {
        initHotbar()
    }

    fun initHotbar() {
        if (hotbar.isEmpty()) {
            hotbar.addAll(
                listOf("grass", "dirt", "stone", "cobblestone", "planks", "log", "leaves", "sand", "glass")
            )
        }
    }

    fun startNewWorld(seed: Long) {
        requestedSeed = seed
        pendingLoad = false
        pendingStart = true
        state = State.LOADING
        progress = 0f
        status = "Preparando mundo..."
        chatLines.clear()
        chat("Semilla: $seed")
    }

    fun startLoadedWorld() {
        pendingLoad = true
        pendingStart = true
        state = State.LOADING
        progress = 0f
        status = "Cargando mundo..."
        chatLines.clear()
    }

    fun onWorldReady() {
        state = State.PLAYING
        progress = 1f
    }

    fun exitToMenu() {
        paused = false
        pendingStart = false
        pendingLoad = false
        saveWorld()
        world = null
        player = null
        targetHit = null
        state = State.MENU
        status = ""
    }

    /** Called by the renderer once the GL context is alive. */
    fun consumePendingStart(): Int {
        if (!pendingStart) return 0
        pendingStart = false
        val mode = if (pendingLoad) 2 else 1
        pendingLoad = false
        return mode
    }

    fun setProgress(p: Float, text: String) {
        progress = p
        status = text
    }

    fun addLook(dx: Float, dy: Float) {
        synchronized(lookLock) {
            lookDX += dx
            lookDY += dy
        }
    }

    fun takeLook(out: FloatArray) {
        synchronized(lookLock) {
            out[0] = lookDX
            out[1] = lookDY
            lookDX = 0f
            lookDY = 0f
        }
    }

    fun requestPlace() {
        action.set(1)
    }

    fun requestBreak() {
        action.set(2)
    }

    fun consumeAction(): Int = action.getAndSet(0)

    fun chat(message: String) {
        val line = message
        chatLines.add(line)
        while (chatLines.size > 7) chatLines.removeAt(0)
        Log.i(line)
    }

    fun selectedBlock(): String = hotbar.getOrElse(selectedSlot) { "stone" }

    fun addHotbarBlock(id: String) {
        if (!hotbar.contains(id) && hotbar.size < 9) hotbar.add(id)
    }

    fun saveWorld() {
        synchronized(saveLock) {
            val w = world ?: return
            val p = player ?: return
            try {
                w.save(savesDir, p, hotbar, selectedSlot)
                chat("Mundo guardado")
            } catch (t: Throwable) {
                Log.e("Error guardando", t)
            }
        }
    }

    fun hasBlockPhysics(): Boolean = Blocks.all().isNotEmpty()
}

/** Persisted user settings. */
object Settings {
    private const val PREFS = "voxelcraft"
    var sensitivity = 1.0f
    var renderDistance = 4
    var fov = 70f
    var showHud = true

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sensitivity = p.getFloat("sens", 1.0f)
        renderDistance = p.getInt("distance", 4).coerceIn(2, 6)
        fov = p.getFloat("fov", 70f).coerceIn(50f, 100f)
        showHud = p.getBoolean("hud", true)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("sens", sensitivity)
            .putInt("distance", renderDistance)
            .putFloat("fov", fov)
            .putBoolean("hud", showHud)
            .apply()
    }
}
