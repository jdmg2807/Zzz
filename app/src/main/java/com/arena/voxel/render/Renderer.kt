package com.arena.voxel.render

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.arena.voxel.Game
import com.arena.voxel.Settings
import com.arena.voxel.core.Log
import com.arena.voxel.core.Logs
import com.arena.voxel.mods.ModLoader
import com.arena.voxel.packs.ResourcePacks
import com.arena.voxel.packs.ShaderSources
import com.arena.voxel.player.BlockHit
import com.arena.voxel.player.Player
import com.arena.voxel.player.Raycast
import com.arena.voxel.textures.Textures
import com.arena.voxel.world.Blocks
import com.arena.voxel.world.Chunk
import com.arena.voxel.world.Biomes
import com.arena.voxel.world.Mob
import com.arena.voxel.world.Mobs
import com.arena.voxel.world.World
import com.arena.voxel.world.WorldGen
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * OpenGL ES 3.0 renderer: sky dome, chunk geometry, transparent water, block selection
 * and an optional post-processing pass that shader packs can replace.
 */
class Renderer : GLSurfaceView.Renderer {

    private var width = 1
    private var height = 1

    private var terrainProg: ShaderProgram? = null
    private var waterProg: ShaderProgram? = null
    private var skyProg: ShaderProgram? = null
    private var postProg: ShaderProgram? = null
    private var lineProg: ShaderProgram? = null

    private var atlasTexture = 0
    private val target = RenderTarget()
    private var fullscreenVbo = 0
    private var lineVbo = 0
    private var particleProg: ShaderProgram? = null
    private var particleVbo = 0
    private var particleData = FloatArray(7 * 512)

    private var world: World? = null
    private var player: Player? = null
    private var shaderSources: Map<String, String> = emptyMap()

    private val failedShaders = ArrayList<String>()
    private var handMesh: ChunkMesh? = null
    private var handFor: String = ""
    private val crackMeshes = arrayOfNulls<ChunkMesh>(6)
    private var pressSeqSeen = -1
    private var pressTime = 0f
    private var mineProgress = 0f
    private var swing = 0f
    private var walkTime = 0f
    private var hostileTimer = 8f
    private var containerTimer = 0f
    private var fxTimer = 0f
    private var eatCooldown = 0f
    private var stepTimer = 0f
    private var lastHealth = 20f
    private val mobMeshes = arrayOfNulls<ChunkMesh>(Mobs.TYPES.size)
    private val itemMeshes = HashMap<String, ChunkMesh>()
    private val mobTiles = HashMap<Int, Int>()
    private val visibleMobs = ArrayList<Mob>()
    private var lastNanos = 0L
    private var fpsAccum = 0f
    private var fpsFrames = 0
    private var autosaveTimer = 0f

    private val look = FloatArray(2)
    private val drawList = ArrayList<Chunk>()
    private val lineData = FloatArray(12 * 2 * 3)

    private var initialized = false
    private var contextGen = 0
    private var lastContextGen = -1
    private val frustum = FloatArray(24)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.55f, 0.72f, 0.98f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)
        GLES20.glFrontFace(GLES20.GL_CCW)
        initialized = true
        lastNanos = 0L
        atlasTexture = 0
        contextGen++
        target.release()
        buildFullscreenQuad()
        buildLineCube()
        // A lost EGL context invalidates every VBO/texture/program. Rebuild them or the GPU
        // draws garbage (that was the source of the huge random triangles after pausing).
        lastContextGen = contextGen
        if (world != null) restoreGpuResources()
    }

    /** Recreates every GPU resource after an EGL context loss, re-meshing all chunks. */
    private fun restoreGpuResources() {
        try {
            compilePrograms()
            uploadAtlas()
            val w = world ?: return
            for (c in w.chunks.values) {
                c.mesh?.release()
                c.waterMesh?.release()
                c.mesh = null
                c.waterMesh = null
                c.dirty = true
            }
            handMesh?.release()
            handMesh = null
            handFor = ""
            for (i in crackMeshes.indices) {
                crackMeshes[i]?.release()
                crackMeshes[i] = null
            }
            for (m in itemMeshes.values) m.release()
            itemMeshes.clear()
            com.arena.voxel.world.Particles.clear()
            com.arena.voxel.world.ItemEntities.clear()
            Log.i("Recursos GL restaurados (${w.chunks.size} chunks a re-mallar)")
        } catch (t: Throwable) {
            Log.e("No se pudo restaurar el contexto GL", t)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = if (w > 0) w else 1
        height = if (h > 0) h else 1
        GLES20.glViewport(0, 0, width, height)
        target.release()
    }

    private fun buildFullscreenQuad() {
        val verts = floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        fullscreenVbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, fullscreenVbo)
        val buf = ByteBuffer.allocateDirect(verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(verts).position(0)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, verts.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun buildLineCube() {
        val e = 0.002f
        val min = -e
        val max = 1f + e
        val pts = ArrayList<Float>()
        fun line(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float) {
            pts.add(x1); pts.add(y1); pts.add(z1)
            pts.add(x2); pts.add(y2); pts.add(z2)
        }
        // bottom
        line(min, min, min, max, min, min)
        line(max, min, min, max, min, max)
        line(max, min, max, min, min, max)
        line(min, min, max, min, min, min)
        // top
        line(min, max, min, max, max, min)
        line(max, max, min, max, max, max)
        line(max, max, max, min, max, max)
        line(min, max, max, min, max, min)
        // verticals
        line(min, min, min, min, max, min)
        line(max, min, min, max, max, min)
        line(max, min, max, max, max, max)
        line(min, min, max, min, max, max)
        for (i in pts.indices) lineData[i] = pts[i]

        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        lineVbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, lineVbo)
        val buf = ByteBuffer.allocateDirect(lineData.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(lineData).position(0)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, lineData.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun compileWithFallback(name: String, baseVert: String, baseFrag: String): ShaderProgram? {
        val customVert = shaderSources["$name.vsh"]
        val customFrag = shaderSources["$name.fsh"]
        val vert = customVert ?: baseVert
        val frag = customFrag ?: baseFrag
        if (customVert == null && customFrag == null) {
            return try {
                ShaderProgram.create(name, baseVert, baseFrag)
            } catch (t: Throwable) {
                Log.e("Shader $name integrado falló", t)
                null
            }
        }
        return try {
            ShaderProgram.create(name, vert, frag)
        } catch (t: Throwable) {
            Log.e("Shader $name del pack falló, se usa el integrado", t)
            failedShaders.add(name)
            try {
                ShaderProgram.create(name, baseVert, baseFrag)
            } catch (t2: Throwable) {
                Log.e("Shader $name integrado falló", t2)
                null
            }
        }
    }

    private fun compilePrograms() {
        terrainProg = compileWithFallback("terrain", ShaderSources.TERRAIN_VSH, ShaderSources.TERRAIN_FSH)
        waterProg = compileWithFallback("water", ShaderSources.WATER_VSH, ShaderSources.WATER_FSH)
        skyProg = compileWithFallback("sky", ShaderSources.SKY_VSH, ShaderSources.SKY_FSH)
        postProg = compileWithFallback("post", ShaderSources.POST_VSH, ShaderSources.POST_FSH)
        lineProg = ShaderProgram.create("line", ShaderSources.LINE_VSH, ShaderSources.LINE_FSH)
        particleProg = ShaderProgram.create("particle", ShaderSources.PARTICLE_VSH, ShaderSources.PARTICLE_FSH)
        val pIds = IntArray(1)
        GLES20.glGenBuffers(1, pIds, 0)
        particleVbo = pIds[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, particleVbo)
        GLES20.glBufferData(
            GLES20.GL_ARRAY_BUFFER, particleData.size * 4, null, GLES20.GL_DYNAMIC_DRAW
        )
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        Gl.checkError("programas")
    }

    private fun uploadAtlas() {
        val bmp = Textures.buildAtlas()
        if (atlasTexture == 0) {
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            atlasTexture = ids[0]
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    private fun setupWorld(loadSave: Boolean) {
        val setupStart = System.nanoTime()
        Logs.section(if (loadSave) "CARGAR MUNDO" else "MUNDO NUEVO")
        try {
            // Drop geometry from a previous world (if any) before rebuilding everything.
            world?.let { old ->
                for (c in old.chunks.values) {
                    c.mesh?.release()
                    c.waterMesh?.release()
                }
            }
            world = null
            player = null
            drawList.clear()
            Blocks.reset()

            Game.setProgress(0.02f, "Cargando texturas...")
            Textures.prepareDefaults()
            Logs.info("SETUP", "Texturas base: ${Textures.count()} tiles")
            val packs = if (Settings.usePacks) {
                ResourcePacks.applyAll()
            } else {
                emptyList()
            }
            Game.packReport = when {
                !Settings.usePacks -> "Texturas desactivadas en ajustes"
                packs.isEmpty() -> "Sin packs (texturas integradas)"
                else -> packs.joinToString("\n")
            }

            Logs.info("SETUP", "Packs: ${if (packs.isEmpty()) "ninguno" else packs.joinToString()}")
            Logs.info("SETUP", "Shaders activados: ${Settings.useShaders} · mods: ${Settings.useMods}")

            Game.setProgress(0.06f, "Compilando shaders...")
            val (packName, sources) = if (Settings.useShaders) {
                ResourcePacks.loadShaderSources()
            } else {
                "integrado" to emptyMap()
            }
            shaderSources = sources
            failedShaders.clear()
            Game.shaderPackName = if (sources.isEmpty()) "integrado" else "$packName (${sources.size} shaders)"
            compilePrograms()

            Logs.info("SETUP", "Shader pack: ${Game.shaderPackName} (${sources.size} archivos personalizados)")
            Game.setProgress(0.12f, "Cargando mods...")
            Blocks.init()
            val report = if (Settings.useMods) ModLoader.loadAll() else "Mods desactivados en ajustes"
            Game.modReport = report
            Logs.info("SETUP", "Mods: ${report.replace("\n", " | ")}")

            // Solid tiles for the animal boxes (registered before the atlas is uploaded).
            mobTiles.clear()
            for (type in Mobs.TYPES) {
                for (color in type.colors) {
                    if (!mobTiles.containsKey(color)) {
                        mobTiles[color] = Textures.putSolid("mob_${Integer.toHexString(color)}", color)
                    }
                }
            }
            for (i in mobMeshes.indices) {
                mobMeshes[i]?.release()
                mobMeshes[i] = null
            }

            Logs.info("SETUP", "Atlas: ${Textures.count()} tiles (${Textures.ATLAS}x${Textures.ATLAS})")
            Game.setProgress(0.20f, "Generando atlas de texturas...")
            uploadAtlas()

            var w: World
            var p: Player
            if (loadSave) {
                val loaded = World.loadFrom(Game.savesDir)
                if (loaded != null) {
                    if (loaded.legacy) {
                        Game.chat("Partida de una versión anterior: si ves bloques raros, crea un mundo nuevo")
                    }
                    w = loaded.world
                    p = loaded.player
                    // v1.7: restore the full inventory; older saves only had a hotbar.
                    if (loaded.inventory.isNotBlank()) {
                        Game.inventory.load(loaded.inventory)
                    } else {
                        Game.inventory.slots.fill(null)
                        loaded.hotbar.forEachIndexed { i, id ->
                            if (i < 9 && id != "air") {
                                Game.inventory.set(i, com.arena.voxel.items.ItemStack(id, 64))
                            }
                        }
                    }
                    Game.inventory.selected = loaded.selected
                    Game.syncHotbar()
                    com.arena.voxel.world.Containers.clear()
                    com.arena.voxel.world.Containers.load(Game.savesDir)
                } else {
                    com.arena.voxel.world.Containers.clear()
                    Game.chat("No se encontró partida guardada; mundo nuevo")
                    val seed = System.currentTimeMillis()
                    w = World(seed)
                    val spawn = w.gen.spawnPoint()
                    p = Player(spawn.first, spawn.second, spawn.third)
                    Game.requestedSeed = seed
                }
            } else {
                val seed = Game.requestedSeed
                w = World(seed)
                val spawn = w.gen.spawnPoint()
                p = Player(spawn.first, spawn.second, spawn.third)
            }

            // Drop items that no longer exist (removed mods) and mirror the inventory.
            for (i in 0 until Game.inventory.size) {
                val stack = Game.inventory.get(i) ?: continue
                if (!com.arena.voxel.items.Items.exists(stack.id)) Game.inventory.set(i, null)
            }
            Game.creative = !com.arena.voxel.Settings.survival
            Game.initHotbar()
            Game.syncHotbar()
            if (!com.arena.voxel.Settings.survival && Game.inventory.isEmpty()) Game.inventory.giveStarterKit()
            Game.syncHotbar()

            world = w
            player = p
            Game.world = w
            Game.player = p
            Game.chunksReady = w.chunks.size

            // Make sure the spawn chunk exists and the player is not stuck inside a block.
            val pcx = floor(p.x / 16).toInt()
            val pcz = floor(p.z / 16).toInt()
            w.getOrCreateChunk(pcx, pcz)
            var guard = 0
            while (guard < 60 && (w.isSolid(floor(p.x).toInt(), floor(p.y).toInt(), floor(p.z).toInt()) ||
                    w.isSolid(floor(p.x).toInt(), floor(p.y + 1).toInt(), floor(p.z).toInt()))
            ) {
                p.y += 1.0
                guard++
            }

            Game.setProgress(0.28f, "Generando terreno...")
            generateInitialArea(w, pcx, pcz)

            if (Game.state == Game.State.MENU) {
                // The player left the loading screen: discard everything.
                for (c in w.chunks.values) {
                    c.mesh?.release()
                    c.waterMesh?.release()
                    c.mesh = null
                    c.waterMesh = null
                }
                Game.world = null
                Game.player = null
                world = null
                player = null
                return
            }

            val seconds = (System.nanoTime() - setupStart) / 1e9
            Logs.info(
                "SETUP",
                "Mundo listo en %.2f s · %d chunks iniciales · semilla %d · bloques %d"
                    .format(seconds, w.chunks.size, w.seed, Blocks.all().size)
            )
            Game.setProgress(1f, "Listo")
            Game.onWorldReady()
            ModLoader.worldReady()
            if (failedShaders.isNotEmpty()) {
                Game.chat(
                    "Shaders del pack no compatibles: ${failedShaders.joinToString(", ")} (se usa el integrado)"
                )
            }
            Game.chat("${ModLoader.count()} mod(s) activos · ${Textures.count()} texturas · ${Biomes.NAMES.size} biomas")
            if (packs.isNotEmpty()) Game.chat("Packs: ${packs.joinToString(", ")}")
            autosaveTimer = 0f
        } catch (t: Throwable) {
            Log.e("Fallo preparando el mundo", t)
            Game.chat("Error al crear el mundo: ${t.message}")
            Game.status = "Error: ${t.message}"
        }
    }

    private fun generateInitialArea(w: World, pcx: Int, pcz: Int) {
        val radius = 3
        var done = 0
        val total = (radius * 2 + 1) * (radius * 2 + 1)
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                val c = w.getOrCreateChunk(pcx + dx, pcz + dz)
                buildMesh(w, c)
                done++
                Game.setProgress(0.28f + 0.7f * done / total, "Generando terreno... $done/$total")
            }
        }
        Game.chunksReady = w.chunks.size
    }

    private fun buildMesh(w: World, c: Chunk) {
        val biomes = c.biomes ?: w.gen.biomes(c.cx, c.cz).also { c.biomes = it }
        val result = ChunkMesher.build(w, c, biomes)
        val mesh = c.mesh ?: ChunkMesh().also { c.mesh = it }
        mesh.upload(result.opaque)
        val water = c.waterMesh ?: ChunkMesh().also { c.waterMesh = it }
        water.upload(result.water)
        c.dirty = false
    }

    private fun markNeighboursDirty(w: World, cx: Int, cz: Int) {
        // Border faces depend on the neighbour chunk: when it appears (or disappears) the
        // already-built mesh must be rebuilt, otherwise holes and invisible faces show up.
        w.chunk(cx - 1, cz)?.dirty = true
        w.chunk(cx + 1, cz)?.dirty = true
        w.chunk(cx, cz - 1)?.dirty = true
        w.chunk(cx, cz + 1)?.dirty = true
    }

    private fun streamChunks(w: World) {
        val p = player ?: return
        val pcx = floor(p.x / 16).toInt()
        val pcz = floor(p.z / 16).toInt()
        val radius = Settings.renderDistance
        val start = System.nanoTime()

        // Freeing the GPU copies early keeps VRAM low, but block data is kept much longer so
        // builds do not vanish when the player walks away and comes back (only meshes are dropped).
        val iterator = w.chunks.values.iterator()
        while (iterator.hasNext()) {
            val c = iterator.next()
            val d = maxOf(abs(c.cx - pcx), abs(c.cz - pcz))
            if (d > radius + 6) {
                c.mesh?.release()
                c.waterMesh?.release()
                iterator.remove()
            } else if (d > radius + 2 && c.mesh != null) {
                c.mesh?.release()
                c.waterMesh?.release()
                c.mesh = null
                c.waterMesh = null
                c.dirty = true
            }
        }

        var generated = 0
        var meshed = 0
        // Nearest chunks first: generate and mesh inside a per-frame time budget so the frame
        // rate stays stable instead of stuttering on chunk creation.
        for (ring in 0..radius) {
            for (dx in -ring..ring) {
                for (dz in -ring..ring) {
                    if (maxOf(abs(dx), abs(dz)) != ring) continue
                    if (dx * dx + dz * dz > radius * radius + radius) continue
                    val cx = pcx + dx
                    val cz = pcz + dz
                    val existing = w.chunk(cx, cz)
                    if (existing == null) {
                        if (generated < 2) {
                            w.getOrCreateChunk(cx, cz)
                            markNeighboursDirty(w, cx, cz)
                            generated++
                        }
                    } else if (existing.dirty && meshed < 4) {
                        buildMesh(w, existing)
                        meshed++
                    }
                    if ((System.nanoTime() - start) > 6_000_000L) break
                }
            }
            if ((System.nanoTime() - start) > 6_000_000L) break
        }
        Game.chunksReady = w.chunks.size
    }

    // ------------------------------------------------------------------ frustum culling

    private fun updateFrustum(vp: FloatArray) {
        val m = vp
        // Clip = VP * v (column major). Plane = row3 +/- rowN, including row 3 (w).
        fun plane(index: Int, sign: Float, row: Int) {
            val a = m[row] * sign + m[3]
            val b = m[4 + row] * sign + m[7]
            val c = m[8 + row] * sign + m[11]
            val d = m[12 + row] * sign + m[15]
            val len = kotlin.math.sqrt(a * a + b * b + c * c)
            val k = if (len < 1e-6f) 1f else 1f / len
            frustum[index] = a * k
            frustum[index + 1] = b * k
            frustum[index + 2] = c * k
            frustum[index + 3] = d * k
        }
        // left, right, bottom, top, near, far
        plane(0, 1f, 0); plane(4, -1f, 0)
        plane(8, 1f, 1); plane(12, -1f, 1)
        plane(16, 1f, 2); plane(20, -1f, 2)
    }

    private fun chunkVisible(minX: Float, minY: Float, minZ: Float, maxX: Float, maxY: Float, maxZ: Float): Boolean {
        var i = 0
        while (i < 24) {
            val a = frustum[i]
            val b = frustum[i + 1]
            val c = frustum[i + 2]
            val d = frustum[i + 3]
            // Positive vertex test.
            val px = if (a >= 0f) maxX else minX
            val py = if (b >= 0f) maxY else minY
            val pz = if (c >= 0f) maxZ else minZ
            if (a * px + b * py + c * pz + d < 0f) return false
            i += 4
        }
        return true
    }

    private fun rebuildDrawList(w: World, vp: FloatArray) {
        drawList.clear()
        val p = player ?: return
        val pcx = floor(p.x / 16).toInt()
        val pcz = floor(p.z / 16).toInt()
        val radius = Settings.renderDistance + 1
        for (c in w.chunks.values) {
            if (maxOf(abs(c.cx - pcx), abs(c.cz - pcz)) > radius || c.dirty) continue
            val x0 = (c.cx * 16).toFloat()
            val z0 = (c.cz * 16).toFloat()
            if (!chunkVisible(x0, 0f, z0, x0 + 16f, World.HEIGHT.toFloat(), z0 + 16f)) continue
            drawList.add(c)
        }
        // Safety net: if the frustum maths ever rejects everything, draw the whole radius.
        if (drawList.isEmpty()) {
            for (c in w.chunks.values) {
                if (maxOf(abs(c.cx - pcx), abs(c.cz - pcz)) <= radius && !c.dirty) drawList.add(c)
            }
        }
    }

    private fun placeBlock(w: World, hit: BlockHit) {
        // Buckets: scoop a source or pour one out, like a right click in Java.
        val heldItem = Game.selectedItem()
        if (heldItem == "bucket" || heldItem == "water_bucket" || heldItem == "lava_bucket") {
            useBucket(w, hit, heldItem)
            return
        }
        // Holding a tool, a food or any other non-block item: nothing to place.
        if (Game.selectedBlock() == "air" && heldItem != "air") return

        // Storage blocks open their GUI on a tap with an empty hand, like a right click.
        if (Game.selectedBlock() == "air") {
            val id = Blocks.get(w.get(hit.x, hit.y, hit.z)).id
            if (id == "chest" || id == "furnace" || id == "furnace_lit" ||
                id == "crafting_table" || id == "anvil"
            ) {
                Game.openScreen = id
                Game.openScreenPos = Triple(hit.x, hit.y, hit.z)
                return
            }
        }
        // Levers and buttons react to the tap itself, like a right click in Java.
        if (com.arena.voxel.world.Redstone.interact(w, hit.x, hit.y, hit.z)) {
            Game.chat("Componente accionado")
            return
        }
        val x = hit.x + hit.nx
        val y = hit.y + hit.ny
        val z = hit.z + hit.nz
        if (y < 0 || y >= World.HEIGHT) return
        if (!w.isAir(x, y, z)) return
        val p = player ?: return
        val id = Game.selectedBlock()
        val blockIndex = Blocks.index(id)
        if (blockIndex == Blocks.AIR) {
            // Holding a tool, food or an item that is not a block: nothing to place.
            if (id == "air") return
            Game.chat("Estás sosteniendo ${com.arena.voxel.items.Items.nameOf(id)} (no es un bloque)")
            return
        }
        if (hitsPlayer(p, x, y, z)) {
            Game.chat("No puedes colocar el bloque encima de ti")
            return
        }
        val targetId = Blocks.get(w.get(hit.x, hit.y, hit.z)).id
        if (targetId == "crafting_table" && Game.openScreen.isEmpty() && Game.selectedBlock() == "air") {
            Game.openScreen = "crafting_table"
            return
        }
        if (w.set(x, y, z, blockIndex)) {
            com.arena.voxel.core.Sounds.play("place", 0.6f)
            if (!Game.creative) {
                // Survival consumes one of the held block, exactly like Java's `BlockItem`.
                val held = Game.inventory.held()
                if (held != null && held.id == id) {
                    Game.inventory.consumeHeld(1)
                    Game.syncHotbar()
                }
            }
            ModLoader.blockPlaced(x, y, z, id)
        }
    }

    /**
     * Bucket logic: an empty bucket picks up a fluid source, and a full one pours it into the
     * targeted face. Water and lava are both `liquid` blocks, so the same code covers both.
     */
    private fun useBucket(w: World, hit: BlockHit, held: String) {
        val targetId = Blocks.get(w.get(hit.x, hit.y, hit.z)).id
        val slot = Game.inventory.selected
        when {
            held == "bucket" -> {
                if (targetId != "water" && targetId != "lava") {
                    Game.chat("El cubo solo recoge agua o lava")
                    return
                }
                w.set(hit.x, hit.y, hit.z, Blocks.AIR)
                if (!Game.creative) Game.inventory.set(slot, com.arena.voxel.items.ItemStack("${targetId}_bucket"))
                com.arena.voxel.core.Sounds.play("splash", 0.5f)
                Game.chat("Cubo lleno de ${com.arena.voxel.items.Items.nameOf("${targetId}_bucket")}")
            }
            else -> {
                val fluid = if (held == "water_bucket") "water" else "lava"
                val x = hit.x + hit.nx
                val y = hit.y + hit.ny
                val z = hit.z + hit.nz
                if (!w.isAir(x, y, z)) return
                w.set(x, y, z, Blocks.index(fluid))
                if (fluid == "water") com.arena.voxel.world.Fluids.markSource(w, x, y, z)
                if (!Game.creative) {
                    Game.inventory.set(slot, com.arena.voxel.items.ItemStack("bucket"))
                }
                com.arena.voxel.core.Sounds.play("splash", 0.6f)
            }
        }
        Game.syncHotbar()
    }

    /**
     * Bites the held food. Returns true when the tap was consumed by eating (success or a
     * "not hungry" refusal), so the same tap does not also place a block.
     */
    private fun tryEat(): Boolean {
        val heldId = Game.selectedItem()
        val nutrition = com.arena.voxel.items.Items.food(heldId)
        if (nutrition <= 0) return false
        if (eatCooldown > 0f) return true
        val pl = player ?: return true
        if (!pl.eat(nutrition, com.arena.voxel.items.Items.saturationOf(heldId))) {
            Game.chat("No tienes hambre")
            return true
        }
        eatCooldown = 1.6f
        com.arena.voxel.core.Sounds.play("eat", 0.5f)
        Game.chat("Has comido ${com.arena.voxel.items.Items.nameOf(heldId)}")
        if (!Game.creative) {
            Game.inventory.consumeHeld(1)
            Game.syncHotbar()
        }
        return true
    }

    private fun breakBlock(w: World, hit: BlockHit) {
        val id = Blocks.get(w.get(hit.x, hit.y, hit.z)).id
        if (id == "bedrock") {
            Game.chat("La bedrock no se puede romper")
            return
        }
        val heldTool = Game.selectedItem()
        val harvest = Game.creative || com.arena.voxel.items.Items.canHarvest(id, heldTool)
        if (w.set(hit.x, hit.y, hit.z, 0)) {
            com.arena.voxel.world.Particles.blockBreak(w, hit.x, hit.y, hit.z, id)
            com.arena.voxel.core.Sounds.dig(id)
            if (!harvest && !Game.creative) {
                Game.chat("Necesitas una herramienta mejor para ${com.arena.voxel.items.Items.nameOf(id)}")
            }
            if (!Game.creative && harvest) {
                // Java drops the block's loot table entry, not the block itself.
                val drop = com.arena.voxel.items.Items.dropOf(id, heldTool)
                if (drop != null) {
                    com.arena.voxel.world.ItemEntities.spawn(
                        drop, 1,
                        hit.x + 0.5, hit.y + 0.35, hit.z + 0.5
                    )
                }
            }
            if (!Game.creative && harvest) {
                val xp = when (id) {
                    "coal_ore" -> 1
                    "diamond_ore", "emerald_ore" -> 4
                    "redstone_wire_0" -> 2
                    "iron_ore", "gold_ore" -> 0
                    "deepslate" -> 1
                    else -> 0
                }
                if (xp > 0) player?.addExperience(xp)
            }
            // Java only wears the tool when it is the correct one for the block.
            if (!Game.creative && com.arena.voxel.items.Items.toolType(heldTool) == Blocks.toolOf(id)) {
                wearTool()
            }
            ModLoader.blockBroken(hit.x, hit.y, hit.z, id)
        }
    }

    /** Applies one point of wear to the held tool and breaks it at its limit (Java durability). */
    private fun wearTool() {
        val stack = Game.inventory.held() ?: return
        val max = com.arena.voxel.items.Items.maxDamage(stack.id)
        if (max <= 0) return
        stack.damage += 1
        if (stack.damage >= max) {
            Game.inventory.set(Game.inventory.selected, null)
            Game.chat("${com.arena.voxel.items.Items.nameOf(stack.id)} se ha roto")
        }
        Game.syncHotbar()
    }

    private fun hitsPlayer(p: Player, bx: Int, by: Int, bz: Int): Boolean {
        val hw = Player.WIDTH / 2 + 0.05
        return bx + 1 > p.x - hw && bx < p.x + hw &&
            by + 1 > p.y && by < p.y + Player.HEIGHT &&
            bz + 1 > p.z - hw && bz < p.z + hw
    }

    override fun onDrawFrame(gl: GL10?) {
        if (!initialized) return
        val now = System.nanoTime()
        val dtRaw = if (lastNanos == 0L) 0f else (now - lastNanos) / 1_000_000_000f
        lastNanos = now
        val dt = dtRaw.coerceIn(0f, 0.06f)

        if (lastContextGen != contextGen) {
            lastContextGen = contextGen
            if (world != null) restoreGpuResources()
        }

        val mode = Game.consumePendingStart()
        if (mode != 0) setupWorld(mode == 2)

        // Leaving to the menu frees the world instead of keeping it invisible in VRAM.
        if (Game.state == Game.State.MENU && world != null) {
            releaseWorld()
            Game.world = null
            Game.player = null
        }

        val w = world
        val p = player
        if (w == null || p == null || Game.state == Game.State.MENU) {
            GLES20.glClearColor(0.07f, 0.08f, 0.12f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            return
        }

        // ------------------------------------------------------------------ update
        Game.takeLook(look)
        if (!Game.paused) {
            p.yaw -= look[0] * Settings.sensitivity * 0.16f
            p.pitch -= look[1] * Settings.sensitivity * 0.16f
            p.pitch = p.pitch.coerceIn(-89.5f, 89.5f)
            if (p.yaw > 360f) p.yaw -= 360f
            if (p.yaw < -360f) p.yaw += 360f

            val ascend = when {
                Game.jump -> 1f
                Game.down -> -1f
                else -> 0f
            }
            p.update(dt, w, Game.move[0], Game.move[1], ascend, Game.jump, Game.sprint)
            w.tick(dt)
            tickContainers(w, dt)
        }

        val forward = p.forward()
        val hit = Raycast.cast(
            w, p.x, p.eyeY(), p.z,
            forward[0].toDouble(), forward[1].toDouble(), forward[2].toDouble(), 5.0
        )
        Game.targetHit = hit

        if (!Game.paused) {
            when (Game.consumeAction()) {
                1 -> {
                    swing = 1f
                    // Eating does not need a target block, so it is checked before the raycast use.
                    if (!tryEat()) hit?.let { placeBlock(w, it) }
                }
                2 -> hit?.let { swing = 1f; breakBlock(w, it) }
            }
            if (attackCooldown > 0f) attackCooldown = (attackCooldown - dt * 20f).coerceAtLeast(0f)
            updateInteraction(dt, w, hit)
            if (Math.abs(Game.move[0]) + Math.abs(Game.move[1]) > 0.05f) walkTime += dt * 8f
            if (swing > 0f) swing = (swing - dt * 3.4f).coerceAtLeast(0f)
            ModLoader.tick(dt)
            Game.hudText = p.hudText
            autosaveTimer += dt
            if (autosaveTimer > 120f) {
                autosaveTimer = 0f
                Game.saveWorld()
            }
        } else {
            // Drop touches that happened while paused / in menus.
            Game.consumeAction()
        }
        Game.underwater = w.isLiquid(floor(p.x).toInt(), floor(p.eyeY()).toInt(), floor(p.z).toInt())

        fpsAccum += dtRaw
        fpsFrames++
        if (fpsAccum >= 0.5f) {
            Game.fps = (fpsFrames / fpsAccum).toInt()
            fpsAccum = 0f
            fpsFrames = 0
        }

        if (eatCooldown > 0f) eatCooldown -= dt
        // Damage feedback: hurt sound + red sparks whenever the health bar drops.
        if (p.health < lastHealth - 0.01f) {
            com.arena.voxel.core.Sounds.play("hurt", 0.7f)
            com.arena.voxel.world.Particles.hurt(p.x, p.y, p.z)
            if (lastHealth - p.health >= 3f) {
                com.arena.voxel.world.Particles.landing(p.x, p.y, p.z, 4)
            }
        }
        lastHealth = p.health
        // Footsteps: one every 0.42 s while walking (0.28 s sprinting), silent in the air.
        if (p.onGround && !Game.paused) {
            val speed = kotlin.math.hypot(p.vx, p.vz)
            if (speed > 0.02) {
                stepTimer -= dt * (if (Game.sprint) 1.5f else 1f)
                if (stepTimer <= 0f) {
                    stepTimer = 0.42f
                    val feet = Blocks.get(w.get(floor(p.x).toInt(), floor(p.y - 0.2).toInt(), floor(p.z).toInt())).id
                    com.arena.voxel.core.Sounds.dig(feet, 0.18f)
                }
            } else {
                stepTimer = 0.12f
            }
        }
        streamChunks(w)
        updateMobs(w, dt, p)
        com.arena.voxel.world.ItemEntities.update(dt, w, p)
        com.arena.voxel.world.Particles.update(dt, w)

        // ------------------------------------------------------------------ camera
        val aspect = width.toFloat() / height.toFloat()
        val proj = Mat4.perspective(Settings.fov, aspect, 0.08f, 420f)
        val view = Mat4.view(p.x.toFloat(), p.eyeY().toFloat(), p.z.toFloat(), p.yaw, p.pitch)
        val vp = Mat4.multiply(proj, view)
        updateFrustum(vp)
        rebuildDrawList(w, vp)

        val sunAngle = w.dayTime * Math.PI * 2.0
        val sunY = sin(sunAngle).toFloat()
        val sunDir = normalize(cos(sunAngle).toFloat() * 0.7f, sunY, 0.35f)
        val brightness = sunY.coerceIn(0f, 1f)
        val sunset = (1f - (abs(sunY) / 0.28f)).coerceIn(0f, 1f) * 0.75f

        val lightR = lerp(0.30f, 1.0f, brightness)
        val lightG = lerp(0.34f, 0.99f, brightness)
        val lightB = lerp(0.52f, 0.94f, brightness)

        var horizonR = lerp(0.05f, 0.72f, brightness)
        var horizonG = lerp(0.06f, 0.85f, brightness)
        var horizonB = lerp(0.14f, 1.0f, brightness)
        horizonR = lerp(horizonR, 1.0f, sunset)
        horizonG = lerp(horizonG, 0.45f, sunset)
        horizonB = lerp(horizonB, 0.22f, sunset)

        val zenithR = lerp(0.02f, 0.28f, brightness)
        val zenithG = lerp(0.03f, 0.52f, brightness)
        val zenithB = lerp(0.09f, 0.95f, brightness)

        val underwater = Game.underwater
        val fogR: Float
        val fogG: Float
        val fogB: Float
        val fogNear: Float
        val fogFar: Float
        if (underwater) {
            fogR = 0.08f; fogG = 0.22f; fogB = 0.42f
            fogNear = 0.5f
            fogFar = 22f
        } else {
            fogR = horizonR; fogG = horizonG; fogB = horizonB
            fogNear = Settings.renderDistance * 16f * 0.55f
            fogFar = (Settings.renderDistance * 16f - 4f).coerceAtLeast(32f)
        }

        val useTarget = postProg != null && target.ensure(width, height)
        if (useTarget) target.bind() else GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glClearColor(fogR, fogG, fogB, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // ------------------------------------------------------------------ sky
        val skyP = skyProg
        if (skyP != null) {
            val rotView = Mat4.view(0f, 0f, 0f, p.yaw, p.pitch)
            val invVP = Mat4.invert(Mat4.multiply(proj, rotView))
            if (invVP != null) {
                GLES20.glDepthMask(false)
                skyP.use()
                skyP.setMat4("uInvVP", invVP)
                skyP.set3f("uSunDir", sunDir[0], sunDir[1], sunDir[2])
                skyP.set3f("uZenith", zenithR, zenithG, zenithB)
                skyP.set3f("uHorizon", horizonR, horizonG, horizonB)
                skyP.set3f("uSunColor", 1f, 0.92f, 0.72f)
                skyP.set3f("uCam", p.x.toFloat(), p.eyeY().toFloat(), p.z.toFloat())
                skyP.set1f("uTime", w.time)
                skyP.set1f("uCloudY", WorldGen.CLOUD_Y)
                skyP.set1f("uCloudCover", 0.52f)
                skyP.set1f("uBrightness", brightness)
                drawFullscreen()
                GLES20.glDepthMask(true)
            }
        }

        // ------------------------------------------------------------------ opaque
        val terrain = terrainProg
        if (terrain != null) {
            terrain.use()
            terrain.setMat4("uMVP", vp)
            terrain.set3f("uCam", p.x.toFloat(), p.eyeY().toFloat(), p.z.toFloat())
            terrain.set3f("uFogColor", fogR, fogG, fogB)
            terrain.set2f("uFog", fogNear, fogFar)
            terrain.set3f("uLightColor", lightR, lightG, lightB)
            terrain.set1f("uDay", brightness)
            terrain.set1i("uTex", 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(true)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            for (c in drawList) c.mesh?.draw()
        }

        // ------------------------------------------------------------------ animals
        val mobProg = terrainProg
        if (mobProg != null && visibleMobs.isNotEmpty()) {
            mobProg.use()
            mobProg.set1i("uTex", 0)
            mobProg.set3f("uCam", p.x.toFloat(), p.eyeY().toFloat(), p.z.toFloat())
            mobProg.set3f("uFogColor", fogR, fogG, fogB)
            mobProg.set2f("uFog", fogNear, fogFar)
            mobProg.set3f("uLightColor", lightR, lightG, lightB)
            mobProg.set1f("uDay", brightness)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture)
            for (mob in visibleMobs) {
                val mesh = mobMesh(mob.type) ?: continue
                val bob = if (mob.moving) kotlin.math.abs(kotlin.math.sin(mob.walk)) * 0.035f else 0f
                val model = Mat4.multiply(
                    Mat4.translation(mob.x.toFloat(), (mob.y + bob).toFloat(), mob.z.toFloat()),
                    Mat4.multiply(
                        Mat4.rotationY(mob.yaw),
                        Mat4.scale(1f / 16f, 1f / 16f, 1f / 16f)
                    )
                )
                mobProg.setMat4("uMVP", Mat4.multiply(vp, model))
                mesh.draw()
            }
        }

        // ------------------------------------------------------------------ dropped items
        if (mobProg != null) {
            for (e in com.arena.voxel.world.ItemEntities.all()) {
                val mesh = itemMesh(e.id) ?: continue
                val bob = (kotlin.math.sin((e.age * 0.15f).toDouble()) * 0.04f).toFloat()
                val model = Mat4.multiply(
                    Mat4.translation(e.x.toFloat(), (e.y + 0.12 + bob).toFloat(), e.z.toFloat()),
                    Mat4.multiply(
                        Mat4.rotationY(e.spin),
                        Mat4.scale(1f / 16f, 1f / 16f, 1f / 16f)
                    )
                )
                mobProg.setMat4("uMVP", Mat4.multiply(vp, model))
                mesh.draw()
            }
        }

        // ------------------------------------------------------------------ particles
        drawParticles(vp, p)

        // ------------------------------------------------------------------ selection
        val line = lineProg
        if (line != null && hit != null) {
            line.use()
            val model = Mat4.multiply(vp, Mat4.translation(hit.x.toFloat(), hit.y.toFloat(), hit.z.toFloat()))
            line.setMat4("uMVP", model)
            line.set4f("uColor", 0f, 0f, 0f, 0.65f)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, lineVbo)
            GLES20.glEnableVertexAttribArray(0)
            GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 12, 0)
            GLES20.glLineWidth(2f)
            GLES20.glDrawArrays(GLES20.GL_LINES, 0, lineData.size / 3)
            GLES20.glDisableVertexAttribArray(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        }

        // ------------------------------------------------------------------ water
        val water = waterProg
        if (water != null) {
            water.use()
            water.setMat4("uMVP", vp)
            water.set3f("uCam", p.x.toFloat(), p.eyeY().toFloat(), p.z.toFloat())
            water.set3f("uFogColor", fogR, fogG, fogB)
            water.set2f("uFog", fogNear, fogFar)
            water.set3f("uLightColor", lightR, lightG, lightB)
            water.set1f("uDay", brightness)
            water.set1f("uTime", w.time)
            water.set3f("uSunDir", sunDir[0], sunDir[1], sunDir[2])
            water.set1i("uTex", 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glDepthMask(false)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            for (c in drawList) c.waterMesh?.draw()
            GLES20.glDepthMask(true)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
        }

        // ------------------------------------------------------------------ cracks + hand
        val terrainForFx = terrainProg
        if (terrainForFx != null) {
            terrainForFx.use()
            terrainForFx.set1i("uTex", 0)
            terrainForFx.set3f("uFogColor", fogR, fogG, fogB)
            terrainForFx.set2f("uFog", 1000f, 1001f)
            terrainForFx.set3f("uCam", 0f, 0f, 0f)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture)

            // Mining cracks on the targeted block (plus the occasional dust puff).
            if (Game.mining && mineProgress > 0.02f && !Game.paused) {
                fxTimer -= dt
                if (fxTimer <= 0f && hit != null) {
                    fxTimer = 0.12f
                    val target = Blocks.get(w.get(hit.x, hit.y, hit.z)).id
                    com.arena.voxel.world.Particles.mining(w, hit.x, hit.y, hit.z, target)
                }
                val stage = (mineProgress * 6f).toInt().coerceIn(0, 5)
                val mesh = crackMesh(stage)
                if (mesh != null && hit != null) {
                    val model = Mat4.multiply(
                        Mat4.multiply(vp, Mat4.translation(hit.x.toFloat(), hit.y.toFloat(), hit.z.toFloat())),
                        Mat4.multiply(
                            Mat4.translation(-0.002f, -0.002f, -0.002f),
                            Mat4.scale(1.004f, 1.004f, 1.004f)
                        )
                    )
                    terrainForFx.setMat4("uMVP", model)
                    terrainForFx.set3f("uLightColor", 1f, 1f, 1f)
                    terrainForFx.set1f("uDay", 1f)
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                    GLES20.glDepthMask(false)
                    GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
                    GLES20.glPolygonOffset(-2f, -2f)
                    GLES20.glDisable(GLES20.GL_CULL_FACE)
                    mesh.draw()
                    GLES20.glEnable(GLES20.GL_CULL_FACE)
                    GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
                    GLES20.glDepthMask(true)
                    GLES20.glDisable(GLES20.GL_BLEND)
                }
            }

            // Held block in first person view.
            val heldId = Game.selectedBlock()
            val heldBlock = Blocks.get(Blocks.index(heldId))
            if (heldBlock.id != "air") {
                if (handFor != heldId) {
                    handFor = heldId
                    val mesh = handMesh ?: ChunkMesh().also { handMesh = it }
                    mesh.upload(
                        ChunkMesher.buildCube(heldBlock.tileTop, heldBlock.tileSide, heldBlock.tileBottom)
                    )
                }
                val proj2 = Mat4.perspective(70f, aspect, 0.01f, 8f)
                val bobX = if (walkTime > 0f) kotlin.math.sin(walkTime) * 0.008f else 0f
                val bobY = if (walkTime > 0f) kotlin.math.abs(kotlin.math.cos(walkTime)) * -0.012f else 0f
                val swingCurve = kotlin.math.sin(swing * Math.PI.toFloat())
                val model = Mat4.multiply(
                    Mat4.multiply(
                        Mat4.multiply(
                            Mat4.translation(
                                0.30f + bobX + swingCurve * 0.10f,
                                -0.32f + bobY - swingCurve * 0.06f,
                                -0.62f
                            ),
                            Mat4.rotationY(36f - swingCurve * 55f)
                        ),
                        Mat4.multiply(Mat4.rotationX(10f + swingCurve * 32f), Mat4.scale(0.22f, 0.22f, 0.22f))
                    ),
                    Mat4.translation(-0.5f, -0.5f, -0.5f)
                )
                terrainForFx.setMat4("uMVP", Mat4.multiply(proj2, model))
                terrainForFx.set3f("uLightColor", lightR, lightG, lightB)
                terrainForFx.set1f("uDay", 1f)
                GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
                GLES20.glDisable(GLES20.GL_CULL_FACE)
                handMesh?.draw()
                GLES20.glEnable(GLES20.GL_CULL_FACE)
            }
        }

        // ------------------------------------------------------------------ post
        if (useTarget) {
            val post = postProg
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            if (post != null) {
                GLES20.glDisable(GLES20.GL_DEPTH_TEST)
                GLES20.glDepthMask(false)
                post.use()
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, target.colorTexture)
                post.set1i("uScene", 0)
                post.set1f("uTime", w.time)
                post.set1f("uUnderwater", if (underwater) 1f else 0f)
                post.set2f("uResolution", width.toFloat(), height.toFloat())
                drawFullscreen()
                GLES20.glDepthMask(true)
                GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            }
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
    }

    /** Uploads the live particles as one point-sprite batch and draws them additively off. */
    private fun drawParticles(vp: FloatArray, p: Player) {
        val prog = particleProg ?: return
        val visible = com.arena.voxel.world.Particles.near(p.x.toFloat(), p.z.toFloat(), 28f)
        if (visible.isEmpty()) return
        val needed = visible.size * 7
        if (needed > particleData.size) particleData = FloatArray(needed * 2)
        var i = 0
        for (particle in visible) {
            particleData[i++] = particle.x
            particleData[i++] = particle.y
            particleData[i++] = particle.z
            particleData[i++] = particle.r
            particleData[i++] = particle.g
            particleData[i++] = particle.b
            particleData[i++] = particle.a * (particle.life / 0.7f).coerceIn(0f, 1f)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, particleVbo)
        val buf = java.nio.ByteBuffer.allocateDirect(needed * 4)
            .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
        buf.put(particleData, 0, needed).position(0)
        GLES20.glBufferSubData(GLES20.GL_ARRAY_BUFFER, 0, needed * 4, buf)
        prog.use()
        prog.setMat4("uMVP", vp)
        prog.set1f("uSize", 90f)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 3, GLES20.GL_FLOAT, false, 28, 0)
        GLES20.glEnableVertexAttribArray(1)
        GLES20.glVertexAttribPointer(1, 4, GLES20.GL_FLOAT, false, 28, 12)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthMask(false)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, visible.size)
        GLES20.glDepthMask(true)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun drawFullscreen() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, fullscreenVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glDisableVertexAttribArray(2)
        GLES20.glDisableVertexAttribArray(3)
        GLES20.glDisableVertexAttribArray(4)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)
        GLES20.glDisableVertexAttribArray(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun normalize(x: Float, y: Float, z: Float): FloatArray {
        val len = kotlin.math.sqrt(x * x + y * y + z * z)
        if (len < 1e-5f) return floatArrayOf(0f, 1f, 0f)
        return floatArrayOf(x / len, y / len, z / len)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** One 4x4x4-pixel cube per item id, textured with the block's side tile. */
    private fun itemMesh(id: String): ChunkMesh? {
        itemMeshes[id]?.let { return it }
        val block = Blocks.get(Blocks.index(id))
        if (block.id != "air") {
            val part = Mobs.Part(-2f, -2f, -2f, 2f, 2f, 2f, block.tileSide, 1f)
            val mesh = ChunkMesh()
            mesh.upload(ChunkMesher.buildModelMesh(listOf(part)) { tile -> tile })
            itemMeshes[id] = mesh
            return mesh
        }
        // Non-block items (tools, food, materials) use a neutral chip tile so they stay visible.
        val tile = com.arena.voxel.textures.Textures.indexOf("item_generic")
        if (tile < 0) return null
        val part = Mobs.Part(-2f, -2f, -2f, 2f, 2f, 2f, tile, 1f)
        val mesh = ChunkMesh()
        mesh.upload(ChunkMesher.buildModelMesh(listOf(part)) { t -> t })
        itemMeshes[id] = mesh
        return mesh
    }

    private fun mobMesh(type: Int): ChunkMesh? {
        mobMeshes.getOrNull(type)?.let { return it }
        val info = Mobs.typeOf(type)
        val mesh = ChunkMesh()
        mesh.upload(ChunkMesher.buildModelMesh(info.parts) { color -> mobTiles[color] ?: -1 })
        mobMeshes[type] = mesh
        return mesh
    }

    /** Furnaces: 20 ticks per second, and the lit texture swaps with the burn state. */
    private fun tickContainers(w: World, dt: Float) {
        containerTimer += dt
        if (containerTimer < 0.05f) return
        containerTimer = 0f
        val open = Game.openScreenPos
        for (k in com.arena.voxel.world.Containers.furnaceKeys()) {
            val f = com.arena.voxel.world.Containers.furnaceOrNull(
                com.arena.voxel.world.Containers.unkeyX(k),
                com.arena.voxel.world.Containers.unkeyY(k),
                com.arena.voxel.world.Containers.unkeyZ(k)
            ) ?: continue
            val before = f.burnTime
            f.tick()
            val x = com.arena.voxel.world.Containers.unkeyX(k)
            val y = com.arena.voxel.world.Containers.unkeyY(k)
            val z = com.arena.voxel.world.Containers.unkeyZ(k)
            val wasBurning = before > 0
            val burning = f.isBurning
            if (wasBurning != burning) {
                val id = Blocks.get(w.get(x, y, z)).id
                when {
                    burning && id == "furnace" -> w.setSilently(x, y, z, Blocks.index("furnace_lit"))
                    !burning && id == "furnace_lit" -> w.setSilently(x, y, z, Blocks.index("furnace"))
                }
            }
            if (open == Triple(x, y, z)) Game.syncHotbar()
        }
    }

    private fun updateMobs(w: World, dt: Float, p: Player) {
        visibleMobs.clear()
        val pcx = floor(p.x / 16).toInt()
        val pcz = floor(p.z / 16).toInt()
        for (c in w.chunks.values) {
            val mobs = c.mobs ?: continue
            if (maxOf(abs(c.cx - pcx), abs(c.cz - pcz)) > 5) continue
            if (mobs.isEmpty()) continue
            mobs.removeAll { it.y < -4.0 }
            for (m in mobs) {
                val dx = m.x - p.x
                val dz = m.z - p.z
                if (dx * dx + dz * dz > 96.0 * 96.0) continue
                if (!Game.paused) m.update(dt, w, p)
                if (m.health > 0f) visibleMobs.add(m)
            }
            // Dead mobs are removed from the chunk list.
            mobs.removeAll { it.health <= 0f }
        }

        // Hostile spawning at night, around the player (Java spawns in dark spots nearby).
        if (!Game.paused && Game.creative) {
            // (creative keeps the world peaceful, like Java's peaceful-ish creative default)
        } else if (!Game.paused) {
            hostileTimer -= dt
            if (hostileTimer <= 0f) {
                hostileTimer = 3f
                val night = w.dayTime > 0.52f
                if (night) {
                    val n = Mobs.spawnHostiles(
                        w, w.gen,
                        kotlin.math.floor(p.x).toInt(), kotlin.math.floor(p.z).toInt(),
                        java.util.Random()
                    )
                    if (n > 0) com.arena.voxel.core.Logs.info("MOB", "Aparecieron $n mobs hostiles (noche)")
                }
            }
        }
    }


    // ------------------------------------------------------------------ combat (1.9+)

    /** Attack cooldown in ticks, from the held item's attack speed. */
    private var attackCooldown = 0f

    /** Damage multiplier from the 1.9 attack charge: 0.2 at zero charge, 1.0 when full. */
    private fun chargeMultiplier(): Float {
        val speed = com.arena.voxel.items.Items.attackSpeed(Game.selectedItem())
        val full = 20f / speed
        val charge = (1f - attackCooldown / full).coerceIn(0f, 1f)
        return 0.2f + charge * charge * 0.8f
    }

    /** Minecraft's breaking formula: hardness / tool speed, 30x slower without the right tool. */
    private fun mineSeconds(blockId: String): Float {
        val hardness = Blocks.hardnessOf(blockId)
        if (hardness < 0f) return Float.MAX_VALUE
        val held = Game.selectedItem()
        val toolType = com.arena.voxel.items.Items.toolType(held)
        val tier = com.arena.voxel.items.Items.tier(held)
        val matches = toolType.isNotEmpty() && toolType == Blocks.toolOf(blockId)
        val speed = if (matches) 1f + tier * 1.6f else 1f
        return (hardness * 1.5f / speed).coerceAtLeast(0.05f)
    }

    /** Entity ray test (AABB of the box models) within the 3 block attack reach. */
    private fun mobUnderCrosshair(w: World): Mob? {
        val p = player ?: return null
        val dir = p.forward()
        val eye = p.eyeY()
        var best: Mob? = null
        var bestT = 3.4
        for (m in visibleMobs) {
            if (m.health <= 0f) continue
            val info = Mobs.typeOf(m.type)
            val half = 0.45
            val h = info.eyeHeight.toDouble() + 0.3
            // Ray/AABB slab test.
            var tmin = 0.0
            var tmax = bestT
            var ok = true
            fun axis(o: Double, d: Double, lo: Double, hi: Double) {
                if (kotlin.math.abs(d) < 1e-6) {
                    if (o < lo || o > hi) ok = false
                    return
                }
                var t1 = (lo - o) / d
                var t2 = (hi - o) / d
                if (t1 > t2) { val tmp = t1; t1 = t2; t2 = tmp }
                if (t1 > tmin) tmin = t1
                if (t2 < tmax) tmax = t2
                if (tmin > tmax) ok = false
            }
            axis(p.x, dir[0].toDouble(), m.x - half, m.x + half)
            axis(eye, dir[1].toDouble(), m.y, m.y + h)
            axis(p.z, dir[2].toDouble(), m.z - half, m.z + half)
            if (ok && tmin in 0.0..bestT) {
                bestT = tmin
                best = m
            }
        }
        return best
    }

    /** Melee attack with the 1.9 cooldown, crit on a fall and sweep for swords. */
    private fun attackMob(mob: Mob) {
        val p = player ?: return
        val charge = chargeMultiplier()
        if (attackCooldown > 0.02f && charge < 0.2f) {
            Game.chat("Espera a que se recargue el ataque")
            return
        }
        val held = Game.selectedItem()
        val base = com.arena.voxel.items.Items.attackDamage(held) + 1f
        val crit = p.vy < 0 && !p.onGround
        var damage = base * charge * (if (crit) 1.5f else 1f)
        if (held.contains("sword") && charge > 0.9f && !crit) {
            // Sweeping edge: nearby mobs take a small hit too.
            for (other in visibleMobs) {
                if (other === mob || other.health <= 0f) continue
                val dx = other.x - mob.x
                val dz = other.z - mob.z
                if (dx * dx + dz * dz < 4.0) other.health -= 1f
            }
        }
        mob.health -= damage
        mob.vy = 5.2
        if (!Game.creative) wearTool()
        val speed = com.arena.voxel.items.Items.attackSpeed(held)
        attackCooldown = 20f / speed
        Game.chat(if (crit) "¡Golpe crítico! -${damage.toInt()}" else "Golpe -${damage.toInt()}")
        com.arena.voxel.world.Particles.hurt(mob.x, mob.y, mob.z)
        com.arena.voxel.core.Sounds.play("hurt", 0.55f, 1.2f)
        if (mob.health <= 0f) {
            Game.chat("${Mobs.typeOf(mob.type).name} derrotado")
            if (!Game.creative) {
                // Java loot tables: zombie → rotten flesh, skeleton → bone (+ arrows), creeper → gunpowder.
                when (Mobs.typeOf(mob.type).name.lowercase()) {
                    "zombi", "zombie" -> Game.give("rotten_flesh", 1)
                    "esqueleto", "skeleton" -> {
                        Game.give("bone", 1)
                        if (Math.random() < 0.5) Game.give("arrow", 2)
                    }
                    "creeper" -> Game.give("gunpowder", 1)
                    else -> Unit
                }
                val xp = when (Mobs.typeOf(mob.type).name.lowercase()) {
                    "creeper" -> 5
                    else -> 5
                }
                p.addExperience(xp)
            }
        }
    }

    private fun crackMesh(stage: Int): ChunkMesh? {
        crackMeshes[stage]?.let { return it }
        val tile = Textures.indexOf("crack_${stage + 1}")
        if (tile < 0) return null
        val mesh = ChunkMesh()
        mesh.upload(ChunkMesher.buildCube(tile, tile, tile))
        crackMeshes[stage] = mesh
        return mesh
    }

    /**
     * Touch semantics: a quick tap places the selected block, holding mines the targeted block
     * with progressive cracks (creative mining).
     */
    private fun updateInteraction(dt: Float, w: World, hit: BlockHit?) {
        if (Game.pressSeq != pressSeqSeen) {
            pressSeqSeen = Game.pressSeq
            pressTime = 0f
            mineProgress = 0f
        }
        if (Game.pressActive) {
            pressTime += dt
            if (pressTime > 0.20f) {
                Game.mining = true
                if (hit != null) {
                    val targetId = Blocks.get(w.get(hit.x, hit.y, hit.z)).id
                    mineProgress += dt / mineSeconds(targetId)
                    if (mineProgress >= 1f) {
                        breakBlock(w, hit)
                        mineProgress = 0f
                        swing = 1f
                    }
                } else {
                    mineProgress = 0f
                }
            }
        } else {
            if (pressTime > 0f) {
                if (pressTime <= 0.20f) {
                    // Java's attack: the crosshair checks entities before blocks.
                    val mob = mobUnderCrosshair(w)
                    if (mob != null) {
                        attackMob(mob)
                    } else {
                        hit?.let { placeBlock(w, it) }
                    }
                    swing = 1f
                }
                pressTime = 0f
                mineProgress = 0f
                Game.mining = false
            }
        }
        Game.miningProgress = mineProgress
    }

    /** Frees per-chunk buffers when the game screen is destroyed. */
    fun releaseWorld() {
        val w = world ?: return
        for (c in w.chunks.values) {
            c.mesh?.release()
            c.waterMesh?.release()
            c.mesh = null
            c.waterMesh = null
        }
        world = null
        player = null
        drawList.clear()
        handMesh?.release()
        handMesh = null
        handFor = ""
        for (i in crackMeshes.indices) {
            crackMeshes[i]?.release()
            crackMeshes[i] = null
        }
        for (i in mobMeshes.indices) {
            mobMeshes[i]?.release()
            mobMeshes[i] = null
        }
        visibleMobs.clear()
    }
}
