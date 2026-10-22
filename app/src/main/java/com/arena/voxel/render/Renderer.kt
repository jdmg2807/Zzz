package com.arena.voxel.render

import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import com.arena.voxel.Game
import com.arena.voxel.Settings
import com.arena.voxel.core.Log
import com.arena.voxel.mods.ModLoader
import com.arena.voxel.packs.ResourcePacks
import com.arena.voxel.packs.ShaderSources
import com.arena.voxel.player.BlockHit
import com.arena.voxel.player.Player
import com.arena.voxel.player.Raycast
import com.arena.voxel.textures.Textures
import com.arena.voxel.world.Blocks
import com.arena.voxel.world.Chunk
import com.arena.voxel.world.World
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

    private var world: World? = null
    private var player: Player? = null
    private var shaderSources: Map<String, String> = emptyMap()

    private var lastNanos = 0L
    private var fpsAccum = 0f
    private var fpsFrames = 0
    private var autosaveTimer = 0f

    private val look = FloatArray(2)
    private val drawList = ArrayList<Chunk>()
    private val lineData = FloatArray(12 * 2 * 3)

    private var initialized = false

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
        buildFullscreenQuad()
        buildLineCube()
        // If the EGL context was lost while a world was loaded, rebuild GL resources.
        if (world != null) {
            try {
                compilePrograms()
                uploadAtlas()
            } catch (t: Throwable) {
                Log.e("No se pudo restaurar el contexto GL", t)
            }
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
        val vert = shaderSources["$name.vsh"] ?: baseVert
        val frag = shaderSources["$name.fsh"] ?: baseFrag
        return try {
            ShaderProgram.create(name, vert, frag)
        } catch (t: Throwable) {
            Log.e("Shader $name del pack falló, se usa el integrado", t)
            Game.chat("Shader $name del pack falló; usando el integrado")
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
            val packs = ResourcePacks.applyAll()
            Game.packReport = if (packs.isEmpty()) "Sin packs (texturas integradas)" else packs.joinToString("\n")

            Game.setProgress(0.06f, "Compilando shaders...")
            val (packName, sources) = ResourcePacks.loadShaderSources()
            shaderSources = sources
            Game.shaderPackName = if (sources.isEmpty()) "integrado" else "$packName (${sources.size} shaders)"
            compilePrograms()

            Game.setProgress(0.12f, "Cargando mods...")
            Blocks.init()
            val report = ModLoader.loadAll()
            Game.modReport = report

            Game.setProgress(0.20f, "Generando atlas de texturas...")
            uploadAtlas()
            val validSlots = Game.hotbar.filter { Blocks.index(it) != 0 }
            Game.hotbar.clear()
            Game.hotbar.addAll(validSlots)
            Game.initHotbar()

            var w: World
            var p: Player
            if (loadSave) {
                val loaded = World.loadFrom(Game.savesDir)
                if (loaded != null) {
                    w = loaded.world
                    p = loaded.player
                    Game.hotbar.clear()
                    Game.hotbar.addAll(loaded.hotbar)
                    Game.selectedSlot = loaded.selected
                    Game.initHotbar()
                } else {
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

            Game.setProgress(1f, "Listo")
            Game.onWorldReady()
            ModLoader.worldReady()
            Game.chat("${ModLoader.count()} mod(s) activos · ${Textures.count()} texturas")
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
        val result = ChunkMesher.build(w, c)
        val mesh = c.mesh ?: ChunkMesh().also { c.mesh = it }
        mesh.upload(result.opaque)
        val water = c.waterMesh ?: ChunkMesh().also { c.waterMesh = it }
        water.upload(result.water)
        c.dirty = false
    }

    private fun streamChunks(w: World) {
        val p = player ?: return
        val pcx = floor(p.x / 16).toInt()
        val pcz = floor(p.z / 16).toInt()
        val radius = Settings.renderDistance

        val iterator = w.chunks.values.iterator()
        while (iterator.hasNext()) {
            val c = iterator.next()
            val d = maxOf(abs(c.cx - pcx), abs(c.cz - pcz))
            if (d > radius + 2) {
                c.mesh?.release()
                c.waterMesh?.release()
                iterator.remove()
            }
        }

        var generated = 0
        var meshed = 0
        for (dx in -radius..radius) {
            for (dz in -radius..radius) {
                if (dx * dx + dz * dz > radius * radius + radius) continue
                val cx = pcx + dx
                val cz = pcz + dz
                val existing = w.chunk(cx, cz)
                if (existing == null) {
                    if (generated < 2) {
                        w.getOrCreateChunk(cx, cz)
                        generated++
                    }
                } else if (existing.dirty && meshed < 2) {
                    buildMesh(w, existing)
                    meshed++
                }
            }
        }
        Game.chunksReady = w.chunks.size
    }

    private fun rebuildDrawList(w: World) {
        drawList.clear()
        val p = player ?: return
        val pcx = floor(p.x / 16).toInt()
        val pcz = floor(p.z / 16).toInt()
        val radius = Settings.renderDistance + 1
        for (c in w.chunks.values) {
            if (maxOf(abs(c.cx - pcx), abs(c.cz - pcz)) <= radius && !c.dirty) drawList.add(c)
        }
    }

    private fun placeBlock(w: World, hit: BlockHit) {
        val x = hit.x + hit.nx
        val y = hit.y + hit.ny
        val z = hit.z + hit.nz
        if (y < 0 || y >= World.HEIGHT) return
        if (!w.isAir(x, y, z)) return
        val p = player ?: return
        val id = Game.selectedBlock()
        val blockIndex = Blocks.index(id)
        if (blockIndex == Blocks.AIR) {
            Game.chat("Ese bloque ya no existe (¿mod desinstalado?)")
            return
        }
        if (hitsPlayer(p, x, y, z)) {
            Game.chat("No puedes colocar el bloque encima de ti")
            return
        }
        if (w.set(x, y, z, blockIndex)) {
            ModLoader.blockPlaced(x, y, z, id)
        }
    }

    private fun breakBlock(w: World, hit: BlockHit) {
        val id = Blocks.get(w.get(hit.x, hit.y, hit.z)).id
        if (id == "bedrock") {
            Game.chat("La bedrock no se puede romper")
            return
        }
        if (w.set(hit.x, hit.y, hit.z, 0)) {
            ModLoader.blockBroken(hit.x, hit.y, hit.z, id)
        }
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

        val mode = Game.consumePendingStart()
        if (mode != 0) setupWorld(mode == 2)

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
            p.update(dt, w, Game.move[0], Game.move[1], ascend, Game.jump)
            w.tick(dt)
        }

        val forward = p.forward()
        val hit = Raycast.cast(
            w, p.x, p.eyeY(), p.z,
            forward[0].toDouble(), forward[1].toDouble(), forward[2].toDouble(), 6.0
        )
        Game.targetHit = hit

        if (!Game.paused) {
            when (Game.consumeAction()) {
                1 -> hit?.let { placeBlock(w, it) }
                2 -> hit?.let { breakBlock(w, it) }
            }
            ModLoader.tick(dt)
            Game.hudText = p.hudText
            autosaveTimer += dt
            if (autosaveTimer > 120f) {
                autosaveTimer = 0f
                Game.saveWorld()
            }
        }
        Game.underwater = w.isLiquid(floor(p.x).toInt(), floor(p.eyeY()).toInt(), floor(p.z).toInt())

        fpsAccum += dtRaw
        fpsFrames++
        if (fpsAccum >= 0.5f) {
            Game.fps = (fpsFrames / fpsAccum).toInt()
            fpsAccum = 0f
            fpsFrames = 0
        }

        streamChunks(w)
        rebuildDrawList(w)

        // ------------------------------------------------------------------ camera
        val aspect = width.toFloat() / height.toFloat()
        val proj = Mat4.perspective(Settings.fov, aspect, 0.08f, 420f)
        val view = Mat4.view(p.x.toFloat(), p.eyeY().toFloat(), p.z.toFloat(), p.yaw, p.pitch)
        val vp = Mat4.multiply(proj, view)

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
            terrain.set1i("uTex", 0)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, atlasTexture)
            GLES20.glEnable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(true)
            GLES20.glEnable(GLES20.GL_CULL_FACE)
            for (c in drawList) c.mesh?.draw()
        }

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
            water.set1f("uTime", w.time)
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

    private fun drawFullscreen() {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, fullscreenVbo)
        GLES20.glEnableVertexAttribArray(0)
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDisableVertexAttribArray(1)
        GLES20.glDisableVertexAttribArray(2)
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
    }
}
