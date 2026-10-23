package com.arena.voxel.mods

import com.arena.voxel.Game
import com.arena.voxel.api.BlockDef
import com.arena.voxel.api.ChatAPI
import com.arena.voxel.api.CommandHandler
import com.arena.voxel.api.ItemDef
import com.arena.voxel.api.ModContext
import com.arena.voxel.api.PlayerAPI
import com.arena.voxel.api.WorldAPI
import com.arena.voxel.core.Log
import com.arena.voxel.textures.Textures
import com.arena.voxel.world.Blocks
import com.arena.voxel.world.World
import java.util.logging.Logger

/** Bridges mod API calls to the running game. */
class ModHost(private val descriptor: ModDescriptor) : ModContext {

    private val logger: Logger = Logger.getLogger("VoxelCraft.${descriptor.id}")

    private fun worldOrNull(): World? = Game.world

    private val worldApi = object : WorldAPI {
        override fun getBlock(x: Int, y: Int, z: Int): String {
            val w = worldOrNull() ?: return "air"
            return Blocks.get(w.get(x, y, z)).id
        }

        override fun setBlock(x: Int, y: Int, z: Int, id: String) {
            val w = worldOrNull() ?: return
            w.set(x, y, z, id)
        }

        override fun isSolid(x: Int, y: Int, z: Int): Boolean = worldOrNull()?.isSolid(x, y, z) ?: false

        override fun isAir(x: Int, y: Int, z: Int): Boolean = worldOrNull()?.isAir(x, y, z) ?: true

        override fun getHighestBlockY(x: Int, z: Int): Int = worldOrNull()?.highestSolidY(x, z) ?: 0

        override fun getSeed(): Int = worldOrNull()?.seed?.toInt() ?: 0

        override fun fill(x1: Int, y1: Int, z1: Int, x2: Int, y2: Int, z2: Int, id: String) {
            worldOrNull()?.fill(x1, y1, z1, x2, y2, z2, id)
        }

        override fun getTime(): Float = worldOrNull()?.time ?: 0f
    }

    private val playerApi = object : PlayerAPI {
        override fun getX(): Double = Game.player?.x ?: 0.0
        override fun getY(): Double = Game.player?.y ?: 0.0
        override fun getZ(): Double = Game.player?.z ?: 0.0

        override fun teleport(x: Double, y: Double, z: Double) {
            Game.player?.let {
                it.x = x
                it.y = y
                it.z = z
                it.vy = 0.0
            }
        }

        override fun getYaw(): Float = Game.player?.yaw ?: 0f
        override fun getPitch(): Float = Game.player?.pitch ?: 0f
        override fun isFlying(): Boolean = Game.player?.flying ?: false
        override fun setFlying(flying: Boolean) {
            Game.player?.flying = flying
        }

        override fun setHudText(text: String) {
            Game.hudText = text
        }
    }

    private val chatApi = object : ChatAPI {
        override fun broadcast(message: String) = Game.chat(message)
        override fun toast(message: String) = Game.chat(message)
    }

    override fun getModId(): String = descriptor.id

    override fun getGameVersion(): String = "1.0.0"

    override fun getLogger(): Logger = logger

    override fun world(): WorldAPI = worldApi

    override fun player(): PlayerAPI = playerApi

    override fun chat(): ChatAPI = chatApi

    override fun registerBlock(def: BlockDef) {
        Blocks.registerMod(def)
        Game.addHotbarBlock(def.id)
        Log.i("Mod ${descriptor.id} registró el bloque ${def.id}")
    }

    override fun registerItem(def: ItemDef) {
        com.arena.voxel.items.Items.registerMod(def)
        Log.i("Mod ${descriptor.id} registró el objeto ${def.id}")
    }

    override fun registerCommand(name: String, handler: CommandHandler) {
        com.arena.voxel.Game.registerCommand(name, handler)
        Log.i("Mod ${descriptor.id} registró el comando /$name")
    }

    override fun readResource(path: String): ByteArray? {
        return try {
            java.util.zip.ZipFile(descriptor.file).use { zip ->
                val entry = zip.getEntry(path.trimStart('/'))
                if (entry == null) null else zip.getInputStream(entry).use { it.readBytes() }
            }
        } catch (t: Throwable) {
            Log.e("No se pudo leer $path del mod ${descriptor.id}", t)
            null
        }
    }

    override fun registerTexture(name: String, pngBytes: ByteArray) {
        val bmp = Textures.decode(pngBytes) ?: throw IllegalArgumentException("PNG invalido para $name")
        Textures.put(name, bmp)
    }
}
