package com.arena.voxel.imports

import android.net.Uri
import com.arena.voxel.core.Ctx
import com.arena.voxel.core.Log
import com.arena.voxel.core.Logs
import com.arena.voxel.world.Blocks
import com.arena.voxel.world.World
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * Imports a Minecraft Java Edition save (Anvil format) into VoxelCraft.
 *
 * Supported: 1.8 – 1.12 (Blocks byte arrays), 1.13 – 1.15 (packed Palettes, cross-long packing),
 * 1.16 – 1.17 (Palette + BlockStates, aligned packing) and 1.18+ (sections[].block_states).
 *
 * The importer converts a radius of chunks around spawn into our own save format, so it runs once
 * and afterwards the world loads like any other VoxelCraft world.
 */
object McWorldImporter {

    class Result(
        val success: Boolean,
        val worldName: String,
        val message: String,
        val chunks: Int = 0,
        val blocks: Int = 0,
        val skippedBlocks: Int = 0,
        val unknownBlocks: List<Pair<String, Int>> = emptyList(),
        val saveDir: File? = null
    )

    /** How many chunks around spawn are converted. */
    private const val RADIUS = 6

    private val stats = LinkedHashMap<String, Int>()

    fun importWorld(source: Uri?, copyDir: File, prepared: File? = null): Result {
        return try {
            if (source == null) return fail("No se seleccionó ningún archivo")
            Logs.section("IMPORTAR MUNDO")
            Logs.info("IMPORT", "Entrada: ${UriFiles.name(source)} (${UriFiles.size(source)} bytes)")

            val dir = prepareSource(source, copyDir, prepared)
                ?: return fail("No se pudo leer el archivo/carpeta del mundo")

            val levelFile = File(dir, "level.dat")
            if (!levelFile.isFile) {
                return fail("No encuentro level.dat. Selecciona el .zip del mundo, la carpeta del mundo o el level.dat.")
            }

            val level = Nbt.parseGzip(levelFile.readBytes())
            if (level == null) return fail("level.dat corrupto o no es un mundo de Minecraft Java")

            val data = level.compound("Data") ?: level
            val version = data.compound("Version")
            val versionName = version?.string("Name", "?") ?: "?"
            val dataVersion = data.int("DataVersion", 0)
            // Newer formats move tags around, so keys are logged once for diagnosis.
            Logs.info("IMPORT", "Claves de level.dat: ${data.map.keys.sorted().joinToString()}")
            val info = readLevelInfo(data)
            var spawnX = info.x
            var spawnY = info.y
            var spawnZ = info.z
            val seed = info.seed
            Logs.info(
                "IMPORT",
                "Mundo '${data.string("LevelName", dir.name)}' · versión $versionName (DataVersion $dataVersion) · " +
                    "spawn $spawnX/$spawnY/$spawnZ${if (info.found) "" else " (deducido)"} · semilla $seed"
            )

            val foundRegion = when {
                File(dir, "region").isDirectory -> File(dir, "region")
                dir.isDirectory && dir.listFiles()?.any { it.name.startsWith("r.") && it.name.endsWith(".mca") } == true -> dir
                else -> null
            }
            // Not a world: say what the file actually looks like so the right screen is used.
            val regionDir = foundRegion ?: return fail(describeWrongFile(dir))
            if (!info.found) {
                // 1.21+ saves may not carry SpawnX/SpawnZ: centre on the first real chunk.
                val guess = firstChunkCentre(regionDir)
                if (guess != null) {
                    spawnX = guess[0]
                    spawnZ = guess[1]
                    Logs.info("IMPORT", "Spawn deducido del primer chunk: $spawnX/$spawnZ")
                }
            }

            val chunks = HashMap<Long, ChunkData>()
            val scx = Math.floorDiv(spawnX, 16)
            val scz = Math.floorDiv(spawnZ, 16)
            var converted = 0
            var totalBlocks = 0
            var skipped = 0
            stats.clear()

            // The palette (and its textures) must exist before any block name is mapped.
            // Importing happens from the main menu, i.e. before the renderer ever builds a
            // world, and in v1.5.x that left the palette empty: every block was "sin
            // equivalente" and the imported world came out completely empty.
            Blocks.ensureReady()
            Logs.info("IMPORT", "Paleta lista: ${Blocks.all().size} bloques")

            for (dx in -RADIUS..RADIUS) {
                for (dz in -RADIUS..RADIUS) {
                    val cx = scx + dx
                    val cz = scz + dz
                    val chunk = readChunk(regionDir, cx, cz) ?: continue
                    chunks[World.keyOfStatic(cx, cz)] = chunk
                    val count = chunk.blocks.count { it != 0.toShort() }
                    totalBlocks += count
                    converted++
                }
            }

            if (converted == 0) {
                return fail("No se pudo leer ningún chunk (¿mundo vacío o formato no soportado?)")
            }

            Logs.info("IMPORT", "Chunks convertidos: $converted · bloques: $totalBlocks")

            // Build our world and write it as a normal VoxelCraft save.
            Blocks.ensureReady()
            val world = World(seed)
            for ((key, chunk) in chunks) {
                val cx = (key shr 32).toInt()
                val cz = key.toInt()
                val c = com.arena.voxel.world.Chunk(cx, cz)
                System.arraycopy(chunk.blocks, 0, c.blocks, 0, c.blocks.size)
                c.generated = true
                c.dirty = true
                c.maxY = chunk.maxY
                world.chunks[world.keyOf(cx, cz)] = c
            }

            // Import into the slot used by "Continuar partida" so it is playable right away.
            val saveDir = com.arena.voxel.Game.savesDir
            // Keep a backup of the previous world the first time we overwrite it.
            if (File(saveDir, "level.dat").isFile) {
                val backup = File(Ctx.savesDir, "mundo-anterior")
                backup.mkdirs()
                File(saveDir, "level.dat").copyTo(File(backup, "level.dat"), overwrite = true)
                Logs.info("IMPORT", "Partida anterior respaldada en ${Ctx.relativePath(backup)}")
            }
            saveDir.mkdirs()
            // Minecraft y + offset, then look for the first free spot above the imported terrain.
            var spawnTop = spawnY + World.MC_Y_OFFSET
            val spawnChunk = world.chunk(Math.floorDiv(spawnX, 16), Math.floorDiv(spawnZ, 16))
            if (spawnChunk != null) {
                val lx = Math.floorMod(spawnX, 16)
                val lz = Math.floorMod(spawnZ, 16)
                for (y in (World.HEIGHT - 2) downTo 1) {
                    if (spawnChunk.get(lx, y, lz) != Blocks.AIR) {
                        spawnTop = y
                        break
                    }
                }
            }
            var spawnPlayerY = (spawnTop + 1.2).coerceIn(2.0, (World.HEIGHT - 3).toDouble())
            // Never start inside a block: climb until the two blocks above are free.
            val pc = world.chunk(Math.floorDiv(spawnX, 16), Math.floorDiv(spawnZ, 16))
            if (pc != null) {
                val lx = Math.floorMod(spawnX, 16)
                val lz = Math.floorMod(spawnZ, 16)
                var guard = 0
                while (guard < 80) {
                    val feet = spawnPlayerY.toInt()
                    val head = feet + 1
                    if (feet + 2 >= World.HEIGHT) break
                    if (pc.get(lx, feet, lz) == Blocks.AIR && pc.get(lx, head, lz) == Blocks.AIR) break
                    spawnPlayerY += 1.0
                    guard++
                }
            }
            val player = com.arena.voxel.player.Player(
                spawnX + 0.5,
                spawnPlayerY,
                spawnZ + 0.5
            )
            Logs.info(
                "IMPORT",
                "Spawn convertido: x=$spawnX z=$spawnZ → y=${spawnPlayerY.toInt()} (MC y=$spawnY)"
            )
            world.save(saveDir, player, com.arena.voxel.Game.hotbar.toList(), com.arena.voxel.Game.selectedSlot)

            val unknown = stats.entries
                .filter { it.key.startsWith("?") }
                .sortedByDescending { it.value }
                .take(25)
                .map { it.key.removePrefix("?") to it.value }
            for ((name, count) in unknown) Logs.warn("IMPORT", "Bloque sin equivalente: $name ($count)")
            stats.entries.take(40).forEach { (name, count) ->
                if (!name.startsWith("?")) Logs.info("IMPORT", "Mapeado $name → $count")
            }

            val msg = buildString {
                append("Mundo importado: $converted chunks\n")
                append("Destino: ${Ctx.relativePath(saveDir)}\n")
                if (unknown.isNotEmpty()) {
                    append("Bloques sin equivalente (se ven como aire/cubo genérico): ")
                    append(unknown.take(6).joinToString { "${it.first}×${it.second}" })
                    if (unknown.size > 6) append("…")
                    append("\n")
                }
                append("Usa «Continuar partida» para jugarlo.")
            }
            Logs.info("IMPORT", "Importación completada: $msg")
            Result(true, dir.name, msg, converted, totalBlocks, skipped, unknown, saveDir)
        } catch (t: Throwable) {
            Log.e("Fallo importando mundo", t)
            fail("Error importando el mundo: ${t.message}. Revisa el log.")
        }
    }

    private fun fail(message: String): Result {
        Logs.error("IMPORT", message)
        return Result(false, "", message)
    }

    private class LevelInfo(val x: Int, val y: Int, val z: Int, val seed: Long, val found: Boolean)

    /**
     * Reads spawn + seed from level.dat across versions.
     *
     *  - 1.21.x and older: Data.SpawnX / SpawnY / SpawnZ (ints) and Data.RandomSeed.
     *  - newer snapshots move the spawn into a compound (Data.spawn.pos) or only keep the
     *    player position (Data.Player.Pos, a list of doubles).
     *  - the generator seed lives in Data.WorldGenSettings.seed (or lowercase variant).
     */
    private fun readLevelInfo(data: Nbt.Tag.Compound): LevelInfo {
        var x: Int? = (data["SpawnX"] as? Nbt.Tag.IntTag)?.value
        var y: Int? = (data["SpawnY"] as? Nbt.Tag.IntTag)?.value
        var z: Int? = (data["SpawnZ"] as? Nbt.Tag.IntTag)?.value

        if (x == null || z == null) {
            val spawn = data.compound("spawn") ?: data.compound("Spawn")
            if (spawn != null) {
                spawn.intArray("pos")?.let { pos ->
                    if (pos.size >= 3) {
                        x = pos[0]; y = pos[1]; z = pos[2]
                    }
                }
                if (x == null) {
                    if (spawn["x"] is Nbt.Tag.IntTag) x = spawn.int("x")
                    if (spawn["z"] is Nbt.Tag.IntTag) z = spawn.int("z")
                    if (spawn["y"] is Nbt.Tag.IntTag) y = spawn.int("y")
                }
            }
        }
        data.intArray("spawn_pos")?.let { pos ->
            if (x == null && pos.size >= 3) {
                x = pos[0]; y = pos[1]; z = pos[2]
            }
        }

        val player = data.compound("Player")
        if (player != null) {
            if (x == null) (player["SpawnX"] as? Nbt.Tag.IntTag)?.let { x = it.value }
            if (y == null) (player["SpawnY"] as? Nbt.Tag.IntTag)?.let { y = it.value }
            if (z == null) (player["SpawnZ"] as? Nbt.Tag.IntTag)?.let { z = it.value }
            if (x == null || z == null) {
                val pos = player.list("Pos")
                if (pos != null && pos.items.size >= 3) {
                    val px = (pos.items[0] as? Nbt.Tag.DoubleTag)?.value?.toInt()
                    val py = (pos.items[1] as? Nbt.Tag.DoubleTag)?.value?.toInt()
                    val pz = (pos.items[2] as? Nbt.Tag.DoubleTag)?.value?.toInt()
                    if (px != null && pz != null) {
                        x = px; y = py; z = pz
                    }
                }
            }
        }

        var seed = data.long("RandomSeed", 0L)
        if (seed == 0L) {
            val wgs = data.compound("WorldGenSettings") ?: data.compound("world_gen_settings")
            if (wgs != null) {
                seed = wgs.long("seed", 0L)
                if (seed == 0L) {
                    val dims = wgs.compound("dimensions")
                    val overworld = dims?.compound("minecraft:overworld")
                    val generator = overworld?.compound("generator")
                    if (generator != null) seed = generator.long("seed", 0L)
                }
            }
        }

        val found = x != null && z != null
        return LevelInfo(x ?: 0, y ?: 70, z ?: 0, seed, found)
    }

    /** Centre of the first stored chunk of the region folder, used when level.dat has no spawn. */
    private fun firstChunkCentre(regionDir: File): IntArray? {
        val regions = regionDir.listFiles { f -> f.isFile && f.name.startsWith("r.") && f.name.endsWith(".mca") }
            ?.sortedBy { it.name } ?: return null
        for (file in regions) {
            val centre = firstChunkInRegion(file)
            if (centre != null) return centre
        }
        return null
    }

    /** First non-empty chunk slot of one .mca file, as world coordinates. */
    private fun firstChunkInRegion(file: File): IntArray? {
        val header = try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 8192) return null
                val buf = ByteArray(4096)
                raf.seek(0)
                raf.readFully(buf)
                buf
            }
        } catch (t: Throwable) {
            return null
        }
        val parts = file.name.removePrefix("r.").removeSuffix(".mca").split('.')
        val rx = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val rz = parts.getOrNull(1)?.toIntOrNull() ?: 0
        for (i in 0 until 1024) {
            val offset = ((header[i * 4].toInt() and 0xff) shl 16) or
                ((header[i * 4 + 1].toInt() and 0xff) shl 8) or
                (header[i * 4 + 2].toInt() and 0xff)
            if (offset == 0) continue
            val cx = rx * 32 + (i % 32)
            val cz = rz * 32 + (i / 32)
            return intArrayOf(cx * 16 + 8, cz * 16 + 8)
        }
        return null
    }

    /** Explains what the picked file is when it has no level.dat / region folder. */
    private fun describeWrongFile(dir: File): String {
        val entries = dir.walkTopDown().take(4000).filter { it.isFile }.map {
            it.relativeTo(dir).invariantSeparatorsPath.lowercase()
        }.toList()
        val hasShaders = entries.any { it.contains("shaders/") && (it.endsWith(".vsh") || it.endsWith(".fsh")) }
        val hasTextures = entries.any { it.contains("textures/block/") && it.endsWith(".png") }
        val hasMod = entries.any { it.endsWith("voxelmod.json") } || entries.any { it.endsWith(".class") }
        val detected = when {
            hasMod -> "parece un MOD (.jar con voxelmod.json / clases)"
            hasTextures -> "parece un RESOURCE PACK de texturas"
            hasShaders -> "parece un SHADER PACK"
            entries.any { it.endsWith("level.dat") } -> "tiene level.dat pero no carpeta region/"
            else -> "no contiene level.dat ni archivos .mca de region"
        }
        return "No es un mundo de Minecraft Java: $detected.\n" +
            "Usa «Importar texturas», «Importar shaders» o «Importar mod» según corresponda."
    }

    /** Copies the selected uri (zip / level.dat / folder) into a work folder. */
    private fun prepareSource(source: Uri, copyDir: File, prepared: File? = null): File? {
        copyDir.mkdirs()
        // When the caller already copied the file (AutoImport classifies it first) reuse it
        // instead of writing the same 100+ MB world twice.
        val target = prepared ?: File(copyDir, UriFiles.name(source))
        if (prepared == null) {
            UriFiles.open(source)?.use { input ->
                target.outputStream().use { out -> input.copyTo(out) }
            } ?: return null
        }

        return when {
            target.isFile && target.name.endsWith(".zip", true) -> {
                val outDir = File(copyDir, target.nameWithoutExtension)
                unzip(target, outDir)
                // The world may be nested inside a folder inside the zip.
                val nested = outDir.walkTopDown().firstOrNull { it.name == "level.dat" }?.parentFile
                nested ?: outDir
            }
            target.isFile && target.name == "level.dat" -> {
                // Only level.dat was provided: look for a sibling region folder path.
                File(copyDir, "level.dat").writeBytes(target.readBytes())
                copyDir
            }
            else -> target.parentFile
        }
    }

    private fun unzip(zip: File, outDir: File) {
        outDir.mkdirs()
        java.util.zip.ZipFile(zip).use { zf ->
            for (entry in zf.entries()) {
                if (entry.isDirectory) continue
                val out = File(outDir, entry.name)
                // Zip-slip protection.
                if (!out.canonicalPath.startsWith(outDir.canonicalPath)) continue
                out.parentFile?.mkdirs()
                zf.getInputStream(entry).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
    }

    // ------------------------------------------------------------------ region reading

    private class ChunkData(val blocks: ShortArray, val maxY: Int)

    /** Section data holder used while decoding. */
    private class Section(val y: Int, val palette: List<String>, val packed: LongArray, val bits: Int, val legacyIds: ByteArray?)

    private fun readChunk(regionDir: File, cx: Int, cz: Int): ChunkData? {
        val regionX = Math.floorDiv(cx, 32)
        val regionZ = Math.floorDiv(cz, 32)
        val file = File(regionDir, "r.$regionX.$regionZ.mca")
        if (!file.isFile) return null
        try {
            java.io.RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 8192) return null
                val index = ((cx and 31) + (cz and 31) * 32) * 4
                raf.seek(index.toLong())
                val offset = ((raf.readUnsignedByte() shl 16) or (raf.readUnsignedByte() shl 8) or raf.readUnsignedByte())
                raf.readUnsignedByte() // sector count (unused)
                if (offset == 0) return null
                raf.seek(offset.toLong() * 4096L)
                val length = raf.readInt()
                if (length <= 0 || length > 20_000_000) return null
                val compression = raf.readUnsignedByte()
                val payload = ByteArray(length - 1)
                raf.readFully(payload)
                return decodeChunk(payload, compression, cx, cz)
            }
        } catch (t: Throwable) {
            Log.w("Chunk $cx/$cz no se pudo leer: ${t.message}")
            Logs.warn("IMPORT", "Chunk $cx/$cz ilegible: ${t.message}")
            return null
        }
    }

    private fun decodeChunk(payload: ByteArray, compression: Int, cx: Int, cz: Int): ChunkData? {
        val raw: ByteArray = when (compression) {
            1 -> GZIPInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
            2 -> InflaterInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
            3 -> payload
            else -> {
                // Some tools write zlib without the flag: try it anyway.
                try {
                    InflaterInputStream(ByteArrayInputStream(payload)).use { it.readBytes() }
                } catch (t: Throwable) {
                    return null
                }
            }
        }
        val nbt = Nbt.parse(DataInputStream(ByteArrayInputStream(raw))) ?: return null
        val blocks = ShortArray(16 * 16 * World.HEIGHT)

        val sections = ArrayList<Section>()
        // 1.18+: root.sections; older: root.Level.Sections
        val modernSections = nbt.list("sections")
        val legacySections = nbt.compound("Level")?.list("Sections")

        if (modernSections != null) {
            for (tag in modernSections.items) {
                val c = tag as? Nbt.Tag.Compound ?: continue
                val y = c.byte("Y", 0)
                val bs = c.compound("block_states")
                val paletteList = bs?.list("palette")
                val data = bs?.longArray("data")
                val palette = paletteList?.items?.map { entry ->
                    (entry as? Nbt.Tag.Compound)?.string("Name", "minecraft:air") ?: "minecraft:air"
                } ?: continue
                if (palette.isEmpty()) continue
                val bits = bitsFor(palette.size, min = 4)
                sections.add(Section(y, palette, data ?: LongArray(0), bits, null))
            }
        } else if (legacySections != null) {
            for (tag in legacySections.items) {
                val c = tag as? Nbt.Tag.Compound ?: continue
                val y = c.byte("Y", 0)
                val paletteList = c.list("Palette")
                if (paletteList != null) {
                    val palette = paletteList.items.map { entry ->
                        (entry as? Nbt.Tag.Compound)?.string("Name", "minecraft:air") ?: "minecraft:air"
                    }
                    val packed = c.longArray("BlockStates") ?: LongArray(0)
                    val bits = bitsFor(palette.size, min = 4)
                    sections.add(Section(y, palette, packed, bits, null))
                } else {
                    val ids = c.byteArray("Blocks") ?: continue
                    sections.add(Section(y, emptyList(), LongArray(0), 0, ids))
                }
            }
        } else {
            return null
        }

        var maxY = 0
        for (section in sections) {
            for (localY in 0 until 16) {
                val mcY = section.y * 16 + localY
                val targetY = mcY + World.MC_Y_OFFSET
                if (targetY < 0 || targetY >= World.HEIGHT) continue
                for (z in 0 until 16) {
                    for (x in 0 until 16) {
                        val blockId = if (section.legacyIds != null) {
                            val index = localY * 256 + z * 16 + x
                            val raw = section.legacyIds.getOrElse(index) { 0 }.toInt() and 0xff
                            legacyBlockName(raw)
                        } else {
                            val paletteIndex = packedValue(section, localY * 256 + z * 16 + x)
                            section.palette.getOrElse(paletteIndex) { "minecraft:air" }
                        }
                        val mapped = Blocks.fromMinecraft(blockId, stats)
                        if (mapped == Blocks.AIR) continue
                        blocks[(targetY * 16 + z) * 16 + x] = mapped.toShort()
                        if (targetY > maxY) maxY = targetY
                    }
                }
            }
        }
        return ChunkData(blocks, maxY)
    }

    private fun bitsFor(paletteSize: Int, min: Int): Int {
        var bits = if (paletteSize <= 1) 1 else 32 - Integer.numberOfLeadingZeros(paletteSize - 1)
        if (bits < min) bits = min
        return bits
    }

    /**
     * 1.16+ packs values without crossing long boundaries; 1.13–1.15 store a continuous bit
     * stream. Both are handled by checking whether the aligned index stays in range.
     */
    private fun packedValue(section: Section, index: Int): Int {
        if (section.packed.isEmpty()) return 0
        val bits = section.bits
        if (bits <= 0) return 0
        val perLong = 64 / bits
        val aligned = index / perLong
        if (aligned < section.packed.size) {
            val shift = (index % perLong) * bits
            val value = ((section.packed[aligned] ushr shift) and mask(bits)).toInt()
            if (value < section.palette.size) return value
        }
        // Continuous packing (1.13 - 1.15).
        val bitIndex = index * bits
        val longIndex = bitIndex ushr 6
        if (longIndex >= section.packed.size) return 0
        val startBit = bitIndex and 63
        var value = section.packed[longIndex] ushr startBit
        if (startBit + bits > 64 && longIndex + 1 < section.packed.size) {
            value = value or (section.packed[longIndex + 1] shl (64 - startBit))
        }
        val result = (value and mask(bits)).toInt()
        return if (result < section.palette.size) result else 0
    }

    private fun mask(bits: Int): Long = if (bits >= 64) -1L else (1L shl bits) - 1

    private fun legacyBlockName(id: Int): String = when (id) {
        0 -> "minecraft:air"
        1 -> "minecraft:stone"
        2 -> "minecraft:grass_block"
        3 -> "minecraft:dirt"
        4 -> "minecraft:cobblestone"
        5 -> "minecraft:oak_planks"
        7 -> "minecraft:bedrock"
        8, 9 -> "minecraft:water"
        10, 11 -> "minecraft:lava"
        12 -> "minecraft:sand"
        13 -> "minecraft:gravel"
        14 -> "minecraft:gold_ore"
        15 -> "minecraft:iron_ore"
        16 -> "minecraft:coal_ore"
        17 -> "minecraft:oak_log"
        18 -> "minecraft:oak_leaves"
        20 -> "minecraft:glass"
        24 -> "minecraft:sandstone"
        28 -> "minecraft:detector_rail"
        31 -> "minecraft:short_grass"
        35 -> "minecraft:white_wool"
        43, 44 -> "minecraft:stone"
        45 -> "minecraft:bricks"
        49 -> "minecraft:obsidian"
        50 -> "minecraft:torch"
        52 -> "minecraft:spawner"
        54 -> "minecraft:chest"
        56 -> "minecraft:diamond_ore"
        57 -> "minecraft:diamond_block"
        58 -> "minecraft:crafting_table"
        59 -> "minecraft:wheat"
        73, 74 -> "minecraft:redstone_ore"
        78 -> "minecraft:snow"
        79 -> "minecraft:ice"
        80 -> "minecraft:snow"
        81 -> "minecraft:cactus"
        82 -> "minecraft:clay"
        83 -> "minecraft:sugar_cane"
        87 -> "minecraft:netherrack"
        89 -> "minecraft:glowstone"
        98 -> "minecraft:stone_bricks"
        110 -> "minecraft:mycelium"
        111 -> "minecraft:lily_pad"
        112 -> "minecraft:nether_bricks"
        121 -> "minecraft:end_stone"
        129 -> "minecraft:emerald_ore"
        155 -> "minecraft:quartz_block"
        159 -> "minecraft:white_terracotta"
        168 -> "minecraft:prismarine"
        172 -> "minecraft:terracotta"
        174 -> "minecraft:packed_ice"
        179 -> "minecraft:red_sandstone"
        13 -> "minecraft:gravel"
        else -> "minecraft:unknown_$id"
    }

}

/** Small helpers to read a user-selected document (zip, level.dat...). */
object UriFiles {
    private var context: android.content.Context? = null

    fun init(ctx: android.content.Context) {
        context = ctx.applicationContext
    }

    fun name(uri: Uri): String {
        val ctx = context ?: return uri.lastPathSegment ?: "mundo.zip"
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) c.getString(idx) else uri.lastPathSegment
            } ?: uri.lastPathSegment ?: "mundo.zip"
        } catch (t: Throwable) {
            uri.lastPathSegment ?: "mundo.zip"
        }
    }

    fun size(uri: Uri): Long {
        val ctx = context ?: return -1
        return try {
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (idx >= 0 && c.moveToFirst()) c.getLong(idx) else -1L
            } ?: -1L
        } catch (t: Throwable) {
            -1L
        }
    }

    fun open(uri: Uri): java.io.InputStream? {
        val ctx = context ?: return null
        return try {
            ctx.contentResolver.openInputStream(uri)
        } catch (t: Throwable) {
            null
        }
    }
}
