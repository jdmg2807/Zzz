package com.arena.voxel

import android.content.Context
import com.arena.voxel.core.Ctx
import com.arena.voxel.core.Logs
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

    /** Player inventory (0..8 hotbar, 9..35 main), the single source of truth for held items. */
    val inventory = com.arena.voxel.items.Inventory(36)

    /** Creative mode: infinite blocks, flight allowed, no damage. */
    @Volatile var creative = true

    /** Currently open container screen: "inventory", "crafting_table", "furnace", "chest", or "". */
    @Volatile var openScreen = ""

    /** Position of the block entity whose screen is open (chest, furnace…), if any. */
    @Volatile var openScreenPos: Triple<Int, Int, Int>? = null

    /** Crafting grid (3x3, row-major) and its result, shared by the UI and the logic. */
    val craftingGrid = arrayOfNulls<com.arena.voxel.items.ItemStack>(9)
    @Volatile var craftingResult: com.arena.voxel.items.ItemStack? = null

    /** Touch input, written by the UI thread and read by the render thread. */
    val move = FloatArray(2)
    @Volatile var jump = false
    @Volatile var down = false
    @Volatile var sprint = false

    /** Touch press tracking: a quick tap places a block, holding mines it. */
    @Volatile var pressActive = false
    @Volatile var pressSeq = 0
    @Volatile var mining = false
    @Volatile var miningProgress = 0f

    var world: World? = null
    var player: Player? = null

    @Volatile var requestedSeed: Long = 0L
    @Volatile var pendingStart = false
        private set
    @Volatile var pendingLoad = false
        private set

    @Volatile var importing = false
        private set
    @Volatile var importStatus = ""
        private set
    @Volatile var importResult: String? = null

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
        if (inventory.isEmpty()) {
            // Java's creative default: an empty hotbar you fill from the creative inventory.
            // Survival worlds start with the tool kit instead (see giveStarterKit).
            val starter = listOf(
                "grass", "dirt", "stone", "cobblestone", "oak_planks", "log", "glass", "torch", "crafting_table"
            )
            starter.forEachIndexed { i, id -> inventory.set(i, com.arena.voxel.items.ItemStack(id, 64)) }
        }
        syncHotbar()
    }

    /** Called by [com.arena.voxel.imports.AutoImport] when the work is done. */
    fun reportImport(message: String) {
        importing = false
        importStatus = message
        importResult = message
        chat(message.lineSequence().firstOrNull() ?: "Importación terminada")
    }

    /**
     * Imports a Minecraft Java world (or, if the file is something else, tells the user what it
     * is instead of failing with "No encuentro level.dat").
     */
    fun importMinecraftWorld(uri: android.net.Uri, onDone: (String, Boolean) -> Unit) {
        importFile(uri, null) { message, success, _ -> onDone(message, success) }
    }

    /** Auto-detecting import: world, resource pack, shader pack or mod. */
    fun importFile(
        uri: android.net.Uri,
        hint: com.arena.voxel.imports.AutoImport.Kind?,
        onDone: (String, Boolean, com.arena.voxel.imports.AutoImport.Kind) -> Unit
    ) {
        if (importing) return
        importing = true
        importStatus = "Leyendo el archivo..."
        importResult = null
        com.arena.voxel.imports.AutoImport.run(uri, hint) { message, success, kind ->
            if (success && kind == com.arena.voxel.imports.AutoImport.Kind.WORLD) {
                chat("Mundo importado")
            }
            onDone(message, success, kind)
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
        pressActive = false
        mining = false
        miningProgress = 0f
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

    fun chatSnapshot(last: Int = 3): List<String> =
        synchronized(chatLines) { chatLines.toList().takeLast(last) }

    /** Mod commands, keyed by the lowercase command name. */
    private val commands = HashMap<String, com.arena.voxel.api.CommandHandler>()

    fun registerCommand(name: String, handler: com.arena.voxel.api.CommandHandler) {
        commands[name.lowercase().removePrefix("/")] = handler
    }

    /**
     * Runs a chat line. Built-in commands mirror a handful of vanilla ones, and mod commands are
     * dispatched to their handler; unknown commands report the usual "unknown command" error.
     */
    fun runCommand(line: String) {
        val text = line.trim().removePrefix("/")
        if (text.isEmpty()) return
        val parts = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val name = parts.first().lowercase()
        val args = parts.drop(1).toTypedArray()
        val p = player
        when (name) {
            "give" -> {
                val id = args.getOrNull(0)
                val count = args.getOrNull(1)?.toIntOrNull() ?: 1
                if (id == null || !com.arena.voxel.items.Items.exists(id)) {
                    chat("Uso: /give <objeto> [cantidad]")
                    return
                }
                give(id, count)
                chat("Se han dado $count × ${com.arena.voxel.items.Items.nameOf(id)}")
                return
            }
            "time" -> {
                val w = world
                if (w == null) { chat("No hay mundo cargado"); return }
                when (args.getOrNull(0)?.lowercase()) {
                    "day" -> w.timeOfDay(0.0f)
                    "night" -> w.timeOfDay(0.5f)
                    "noon" -> w.timeOfDay(0.25f)
                    "midnight" -> w.timeOfDay(0.75f)
                    null -> chat("Hora del mundo: ${"%.2f".format(w.dayTime)}")
                    else -> { chat("Uso: /time day|noon|night|midnight"); return }
                }
                chat("Hora del mundo: ${"%.2f".format(w.dayTime)}")
                return
            }
            "tp" -> {
                if (p == null || args.size < 3) { chat("Uso: /tp <x> <y> <z>"); return }
                p.x = args[0].toDoubleOrNull() ?: return
                p.y = args[1].toDoubleOrNull() ?: return
                p.z = args[2].toDoubleOrNull() ?: return
                p.vy = 0.0
                chat("Teletransportado a ${p.x.toInt()} ${p.y.toInt()} ${p.z.toInt()}")
                return
            }
            "heal" -> {
                if (p == null) { chat("No hay jugador"); return }
                p.health = p.maxHealth
                p.food = 20
                chat("Vida y hambre restauradas")
                return
            }
            "gamemode" -> {
                val mode = args.getOrNull(0)?.lowercase()
                when (mode) {
                    "creative", "creativo", "1" -> creative = true
                    "survival", "supervivencia", "0" -> creative = false
                    else -> { chat("Uso: /gamemode creative|survival"); return }
                }
                chat(if (creative) "Modo creativo" else "Modo supervivencia")
                return
            }
            "help" -> {
                val mods = commands.keys.sorted()
                chat("Comandos: /give /time /tp /heal /gamemode" + if (mods.isEmpty()) "" else " · mods: " + mods.joinToString(" "))
                return
            }
        }
        val handler = commands[name]
        if (handler == null) {
            chat("Comando desconocido: /$name")
            return
        }
        try {
            handler.run(modHostForCommands, args)?.let { chat(it) }
        } catch (t: Throwable) {
            Log.e("Error en el comando /$name", t)
            chat("El comando /$name falló: ${t.message}")
        }
    }

    /** Minimal context handed to mod commands. */
    private val modHostForCommands: com.arena.voxel.api.ModContext
        get() = com.arena.voxel.mods.ModLoader.commandContext()

    fun chat(message: String) {
        val line = message
        chatLines.add(line)
        while (chatLines.size > 7) chatLines.removeAt(0)
        Log.i(line)
    }

    /** Id of the held item (block or item), or "air". */
    fun selectedItem(): String = inventory.held()?.id ?: "air"

    /** Held block id, or "air" when holding a non-block item. */
    fun selectedBlock(): String {
        val id = selectedItem()
        return if (id != "air" && Blocks.index(id) != Blocks.AIR) id else "air"
    }

    /** Mirrors inventory slots 0..8 into the legacy hotbar list (renderer + old saves). */
    fun syncHotbar() {
        hotbar.clear()
        for (i in 0 until 9) {
            hotbar.add(inventory.get(i)?.id ?: "air")
        }
        selectedSlot = inventory.selected.coerceIn(0, 8)
    }

    /** Gives an item to the player; in creative it always succeeds. */
    fun give(id: String, count: Int = 1) {
        if (creative) {
            inventory.add(com.arena.voxel.items.ItemStack(id, count))
        } else {
            inventory.add(com.arena.voxel.items.ItemStack(id, count))
        }
        syncHotbar()
    }

    /** Recomputes the crafting result for the current grid. */
    fun updateCrafting() {
        val ids = craftingGrid.map { it?.id }
        craftingResult = com.arena.voxel.items.Recipes.craft(ids)
    }

    fun addHotbarBlock(id: String) {
        if (!hotbar.contains(id) && hotbar.size < 9) hotbar.add(id)
    }

    fun saveWorld() {
        synchronized(saveLock) {
            val w = world ?: return
            val p = player ?: return
            try {
                w.save(savesDir, p, hotbar, selectedSlot, inventory.serialize())
                com.arena.voxel.world.Containers.save(savesDir)
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
    var usePacks = true
    var useShaders = true
    var useMods = true

    /** 0 = automatic (Java formula), 1..4 = fixed GUI scale. */
    var guiScale = 0

    /** Sound effects on/off (synthesised, so they cost nothing to ship). */
    var sound = true

    /** Survival disables flight and consumes blocks when placing. */
    var survival = false

    fun load(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        sensitivity = p.getFloat("sens", 1.0f)
        renderDistance = p.getInt("distance", 4).coerceIn(2, 6)
        fov = p.getFloat("fov", 70f).coerceIn(50f, 100f)
        showHud = p.getBoolean("hud", true)
        usePacks = p.getBoolean("usePacks", true)
        useShaders = p.getBoolean("useShaders", true)
        useMods = p.getBoolean("useMods", true)
        guiScale = p.getInt("guiScale", 0).coerceIn(0, 4)
        survival = p.getBoolean("survival", false)
        sound = p.getBoolean("sound", true)
    }

    fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("sens", sensitivity)
            .putInt("distance", renderDistance)
            .putFloat("fov", fov)
            .putBoolean("hud", showHud)
            .putBoolean("usePacks", usePacks)
            .putBoolean("useShaders", useShaders)
            .putBoolean("useMods", useMods)
            .putInt("guiScale", guiScale)
            .putBoolean("survival", survival)
            .putBoolean("sound", sound)
            .apply()
    }
}
