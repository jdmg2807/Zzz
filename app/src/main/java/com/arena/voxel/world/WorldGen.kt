package com.arena.voxel.world

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Generador de mundo por ruido, con la estructura del generador de Java Edition moderno
 * (continentalidad / erosión / crestas / ríos) descrita en la Minecraft Wiki.
 *
 * **Cómo se decide la altura.** Es el mismo esquema por capas que usa el juego desde la 1.18,
 * con los mismos nombres, aunque cada campo es una implementación propia de ruido Perlin:
 *
 *  1. *Continentalidad*: un ruido de muy baja frecuencia que dice si el punto es océano,
 *     plataforma, costa o interior. Es lo que hace que haya continentes de verdad en lugar de
 *     colinas repetidas por todas partes.
 *  2. *Erosión*: dice si el terreno es plano o quebrado. Solo donde la erosión es baja pueden
 *     crecer montañas.
 *  3. *Crestas* (peaks & valleys): ruido en valor absoluto (`1 − |n|`), que produce crestas
 *     afiladas en lugar de colinas redondas.
 *  4. *Ríos*: una banda estrecha alrededor de un ruido de baja frecuencia; donde cae la banda el
 *     terreno baja y se llena de agua.
 *
 * **Suelo.** Cada bioma tiene su regla de superficie (hierba + tierra, arena + arenisca,
 * nieve, grava en la costa, piedra desnuda en los picos), con 3-4 bloques de sedimento.
 *
 * **Cuevas.** Se combinan los tres tipos del juego: *cheese* (una cavidad grande), *spaghetti*
 * (dos campos que se cruzan → túnel) y *noodle* (frecuencia alta → pasadizos finos). Nunca se
 * carva a menos de 6 bloques bajo el lecho marino, para que el agua no se vacíe dentro.
 *
 * **Menos de cuarzo y más de juego**: las menas aparecen en bolsas de 2-3 bloques con la
 * distribución por altura del juego (carbón arriba, diamante y redstone abajo), y las
 * estructuras (ruinas, mazmorra, casa de aldea) se colocan por regiones deterministas para que
 * no dependan del orden en que se generen los chunks.
 *
 * Todo es determinista por coordenada: dos chunks cualesquiera, generados en cualquier orden,
 * encajan sin costuras.
 */
class WorldGen(val seed: Long) {

    companion object {
        /** Nivel del mar de Java Edition (y = 63). */
        const val SEA_LEVEL = 63
        const val CLOUD_Y = 192f

        /** Profundidad del lecho rocoso indestructible. */
        private const val BEDROCK_TOP = 3
    }

    // ------------------------------------------------------------------ ruido

    /**
     * Tabla de permutación de Perlin, mezclada a partir de la semilla. Es la implementación
     * clásica (Ken Perlin, dominio público): 256 valores mezclados y duplicados para evitar el
     * módulo en el bucle interno.
     */
    private val perm = IntArray(512)

    init {
        val rnd = java.util.Random(seed)
        val p = IntArray(256) { it }
        for (i in 255 downTo 1) {
            val j = rnd.nextInt(i + 1)
            val t = p[i]
            p[i] = p[j]
            p[j] = t
        }
        for (i in 0 until 512) perm[i] = p[i and 255]
    }

    private fun fade(t: Float): Float = t * t * t * (t * (t * 6f - 15f) + 10f)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    /** Gradiente 2D de Perlin; devuelve un valor en [-1, 1] por eje unitario. */
    private fun grad2(hash: Int, x: Float, y: Float): Float = when (hash and 7) {
        0 -> x + y
        1 -> -x + y
        2 -> x - y
        3 -> -x - y
        4 -> x
        5 -> -x
        6 -> y
        else -> -y
    }

    private fun grad3(hash: Int, x: Float, y: Float, z: Float): Float {
        val h = hash and 15
        val u = if (h < 8) x else y
        val v = if (h < 4) y else if (h == 12 || h == 14) x else z
        return (if (h and 1 == 0) u else -u) + (if (h and 2 == 0) v else -v)
    }

    /** Perlin 2D en el rango [-1, 1]. */
    fun noise2(x: Float, y: Float): Float {
        val xi = floor(x).toInt() and 255
        val yi = floor(y).toInt() and 255
        val xf = x - floor(x)
        val yf = y - floor(y)
        val u = fade(xf)
        val v = fade(yf)
        val aa = perm[perm[xi] + yi]
        val ab = perm[perm[xi] + yi + 1]
        val ba = perm[perm[xi + 1] + yi]
        val bb = perm[perm[xi + 1] + yi + 1]
        val x1 = lerp(grad2(aa, xf, yf), grad2(ba, xf - 1f, yf), u)
        val x2 = lerp(grad2(ab, xf, yf - 1f), grad2(bb, xf - 1f, yf - 1f), u)
        return lerp(x1, x2, v)
    }

    /** Perlin 3D en el rango [-1, 1]. */
    fun noise3(x: Float, y: Float, z: Float): Float {
        val xi = floor(x).toInt() and 255
        val yi = floor(y).toInt() and 255
        val zi = floor(z).toInt() and 255
        val xf = x - floor(x)
        val yf = y - floor(y)
        val zf = z - floor(z)
        val u = fade(xf)
        val v = fade(yf)
        val w = fade(zf)
        val a = perm[xi] + yi
        val aa = perm[a] + zi
        val ab = perm[a + 1] + zi
        val b = perm[xi + 1] + yi
        val ba = perm[b] + zi
        val bb = perm[b + 1] + zi
        val x1 = lerp(
            lerp(grad3(perm[aa], xf, yf, zf), grad3(perm[ba], xf - 1f, yf, zf), u),
            lerp(grad3(perm[ab], xf, yf - 1f, zf), grad3(perm[bb], xf - 1f, yf - 1f, zf), u),
            v
        )
        val x2 = lerp(
            lerp(grad3(perm[aa + 1], xf, yf, zf - 1f), grad3(perm[ba + 1], xf - 1f, yf, zf - 1f), u),
            lerp(grad3(perm[ab + 1], xf, yf - 1f, zf - 1f), grad3(perm[bb + 1], xf - 1f, yf - 1f, zf - 1f), u),
            v
        )
        return lerp(x1, x2, w)
    }

    /** Ruido fractal (suma de octavas) en [-1, 1]. */
    fun fbm(x: Float, z: Float, octaves: Int): Float {
        var total = 0f
        var amp = 0.5f
        var freq = 1f
        var norm = 0f
        for (i in 0 until octaves) {
            total += noise2(x * freq, z * freq) * amp
            norm += amp
            amp *= 0.5f
            freq *= 2.03f
        }
        return total / max(norm, 0.0001f)
    }

    /** Ruido de crestas: `1 − |n|` elevado, para picos afilados (0..1). */
    private fun ridged(x: Float, z: Float, octaves: Int): Float {
        var total = 0f
        var amp = 0.5f
        var freq = 1f
        var norm = 0f
        for (i in 0 until octaves) {
            val n = 1f - abs(noise2(x * freq, z * freq))
            total += n * n * amp
            norm += amp
            amp *= 0.5f
            freq *= 2.1f
        }
        return total / max(norm, 0.0001f)
    }

    // ------------------------------------------------------------------ hashes públicos

    /** Hash determinista público (0..Int.MAX_VALUE), usado por el spawn de animales. */
    fun hashAt(x: Int, z: Int): Int = (hash(x, z) * Int.MAX_VALUE).toInt()

    private fun hash(x: Int, z: Int): Float {
        var h = x * 374761393 + z * 668265263 + seed.toInt() * 1013904223
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return (h and 0x7fffffff) / 2147483647f
    }

    private fun hash3(x: Int, y: Int, z: Int): Float {
        var h = x * 374761393 + y * 1103515245 + z * 668265263 + seed.toInt() * 1013904223
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return (h and 0x7fffffff) / 2147483647f
    }

    // ------------------------------------------------------------------ clima y altura

    /**
     * Continentalidad normalizada: −1 océano profundo, 0 costa, +1 interior. Escala ~1/600 bloques.
     *
     * El ruido Perlin puro sale en ±0,35 (no en ±1), así que se estira ×2,8 y se centra en +0,05:
     * sin ese ajuste casi todo el mapa quedaba por debajo del nivel del mar (55 % de océano en la
     * prueba de distribución) porque los umbrales se comparaban con una escala que el ruido nunca
     * alcanzaba.
     */
    fun continentalness(wx: Int, wz: Int): Float =
        (fbm(wx * 0.0016f, wz * 0.0016f, 4) * 2.8f + 0.05f).coerceIn(-1f, 1f)

    /** Erosión normalizada: −1 terreno plano, +1 terreno quebrado. El ruido también se estira. */
    fun erosion(wx: Int, wz: Int): Float =
        (fbm(wx * 0.0032f + 137f, wz * 0.0032f - 71f, 3) * 2.6f).coerceIn(-1f, 1f)

    /**
     * 0 = frío, 1 = cálido, ya normalizado y sin el sesgo del ruido.
     *
     * Un Perlin crudo tiene media 0 pero este campo, muestreado, sale con media −0,34 (los ocho
     * gradientes de [grad2] no son perfectamente isótropos). Sin el desplazamiento de +0,17 el
     * clima daba mundos casi siempre nevados; con él la media queda en 0 y la desviación en 0,4,
     * que reparte los biomas como el juego.
     */
    fun temperature(wx: Int, wz: Int): Float =
        (fbm(wx * 0.0011f + 37f, wz * 0.0011f - 19f, 3) * 1.4f + 0.67f).coerceIn(0f, 1f)

    /** 0 = seco, 1 = húmedo; el mismo ajuste de sesgo (+0,07, media medida −0,14). */
    fun humidity(wx: Int, wz: Int): Float =
        (fbm(wx * 0.0014f - 71f, wz * 0.0014f + 53f, 3) * 1.4f + 0.57f).coerceIn(0f, 1f)

    /** Altura del terreno en [wx, wz], en bloques (0..World.HEIGHT-1). */
    fun heightAt(wx: Int, wz: Int): Int {
        val x = wx.toFloat()
        val z = wz.toFloat()
        val continent = continentalness(wx, wz)
        val ero = erosion(wx, wz)
        val peaks = ridged(x * 0.0060f, z * 0.0060f, 4)
        val detail = fbm(x * 0.021f + 5f, z * 0.021f - 9f, 3)

        // Altura base por continentalidad: océano profundo -> plataforma -> costa -> interior.
        var h = when {
            continent < -0.50f -> 16f + (continent + 1f) * 44f          // 16..38
            continent < -0.20f -> 38f + (continent + 0.50f) * 60f       // 38..56
            continent < 0.12f -> 56f + (continent + 0.20f) * 37f        // 56..68
            else -> 68f + (continent - 0.12f) * 20f                     // 68..86
        }
        h += detail * 2.5f

        // Montañas: solo tierra adentro (continentalidad alta) y donde la erosión es baja.
        val landMask = ((continent - 0.22f) / 0.45f).coerceIn(0f, 1f)
        val eroMask = ((0.30f - ero) / 0.85f).coerceIn(0f, 1f)
        val mountain = landMask * eroMask * max(0f, peaks - 0.30f)
        h += Math.pow(mountain.toDouble(), 1.4).toFloat() * 135f

        // Mesetas: erosión muy baja y continente alto dan una meseta plana.
        if (ero < -0.55f && continent > 0.35f) h = max(h, 92f + detail * 2f)

        // Ríos: banda estrecha alrededor de una isolínea del ruido.
        val riverField = abs(noise2(x * 0.0021f - 333f, z * 0.0021f + 771f))
        val riverWidth = 0.05f
        if (riverField < riverWidth) {
            val t = 1f - riverField / riverWidth
            h -= t * t * 13f
        }

        return h.toInt().coerceIn(2, World.HEIGHT - 16)
    }

    /** Altura de la superficie del agua en esa columna (nivel del mar o el terreno si es más alto). */
    fun surfaceY(wx: Int, wz: Int): Int = max(heightAt(wx, wz), SEA_LEVEL)

    /**
     * Bioma de la columna. El clima se corrige con la altura: cada 25 bloques por encima del
     * nivel del mar restan frío (como la temperatura real del juego).
     */
    fun biomeAt(wx: Int, wz: Int): Int {
        val h = heightAt(wx, wz)
        val continent = continentalness(wx, wz)
        // Océano y costa: el bioma visible es el de la orilla (la arena la pone la superficie).
        val altitude = (h - SEA_LEVEL) / 40f
        // Cada 40 bloques de altura restan 0,45 de temperatura: por eso hay nieve en los picos.
        val temp = (temperature(wx, wz) - 0.5f) * 2f - altitude * 0.45f
        val humid = (humidity(wx, wz) - 0.5f) * 2f

        if (h > SEA_LEVEL + 52) return if (temp < 0f) Biomes.SNOWY else Biomes.TAIGA
        if (continent < -0.30f) return Biomes.PLAINS          // fondo marino
        if (temp > 0.45f && humid < -0.15f) return Biomes.DESERT
        if (humid > 0.35f && temp > 0.20f) return Biomes.JUNGLE
        if (temp < -0.62f) return Biomes.SNOWY
        if (temp < -0.28f) return Biomes.TAIGA
        if (humid > 0.12f) return Biomes.FOREST
        return Biomes.PLAINS
    }

    /** Biomas de un chunk entero (lz * 16 + lx), cacheados por el mesher. */
    fun biomes(cx: Int, cz: Int): IntArray {
        val out = IntArray(256)
        for (lz in 0 until 16) for (lx in 0 until 16) {
            out[lz * 16 + lx] = biomeAt(cx * 16 + lx, cz * 16 + lz)
        }
        return out
    }

    fun highestAt(wx: Int, wz: Int): Int = min(heightAt(wx, wz), World.HEIGHT - 1)

    // ------------------------------------------------------------------ cuevas

    /**
     * Cuevas por campos de ruido 3D, como el juego:
     *
     *  * *cheese*: un campo de baja frecuencia; por encima del umbral se excava y quedan salas.
     *  * *spaghetti*: dos campos independientes; el túnel existe donde **ambos** están cerca de
     *    cero, que es la intersección de dos superficies → un tubo largo.
     *  * *noodle*: lo mismo a más frecuencia y umbral más estrecho → pasadizos raros y finos.
     *
     * Los tubos se hacen más anchos al acercarse al nivel del mar para que las cuevas tengan
     * salidas al exterior, como las simas del juego.
     */
    fun caveAt(wx: Int, y: Int, wz: Int): Boolean {
        if (y < BEDROCK_TOP) return false
        val fy = y.toFloat()
        val depthFactor = ((SEA_LEVEL - y) / 40f).coerceIn(0f, 1f)

        // Cheese: grandes cavidades, más probables en profundidad.
        val cheese = noise3(wx * 0.016f + 11f, fy * 0.030f - 7f, wz * 0.016f + 5f)
        if (cheese > 0.62f - depthFactor * 0.16f) return true

        // Spaghetti: dos campos casi nulos a la vez.
        val tunnelWidth = 0.045f + depthFactor * 0.035f
        val s1 = noise3(wx * 0.030f - 23f, fy * 0.055f + 31f, wz * 0.030f + 17f)
        if (abs(s1) < tunnelWidth) {
            val s2 = noise3(wx * 0.030f + 61f, fy * 0.055f - 13f, wz * 0.030f - 43f)
            if (abs(s2) < tunnelWidth) return true
        }

        // Noodle: solo cerca de la superficie y en la mitad profunda del mundo.
        if (y in 8 until SEA_LEVEL + 24) {
            val n1 = noise3(wx * 0.062f + 91f, fy * 0.085f + 7f, wz * 0.062f - 29f)
            if (abs(n1) < 0.016f) {
                val n2 = noise3(wx * 0.062f - 53f, fy * 0.085f - 61f, wz * 0.062f + 37f)
                if (abs(n2) < 0.016f) return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------ menas

    /**
     * Bolsas de mena: la celda de 4×4×4 tiene un 6 % de probabilidad de ser una vena y dentro solo
     * se llena la esfera central (la mitad de la celda), así que las menas ocupan ~3 % del volumen
     * de piedra, el orden de magnitud del juego. El tipo se elige por altura con la distribución
     * real (carbón hasta y 96, hierro hasta 52, oro hasta 30, diamante solo bajo y 14).
     */
    private fun oreAt(wx: Int, y: Int, wz: Int): String? {
        val cx = Math.floorDiv(wx, 4)
        val cy = Math.floorDiv(y, 4)
        val cz = Math.floorDiv(wz, 4)
        val roll = hash3(cx * 31 + 7, cy * 17 + 3, cz * 53 - 11)
        if (roll > 0.06f) return null
        val dx = Math.floorMod(wx, 4) - 1.5f
        val dy = Math.floorMod(y, 4) - 1.5f
        val dz = Math.floorMod(wz, 4) - 1.5f
        if (dx * dx + dy * dy + dz * dz > 3.4f) return null
        val pick = hash(cx * 7 + 1, cz * 13 + 5)
        return when {
            y < 14 && pick < 0.010f -> "diamond_ore"
            y < 18 && pick < 0.022f -> "redstone_ore"
            y < 30 && pick < 0.045f -> "gold_ore"
            y < 34 && pick < 0.070f -> "lapis_ore"
            y < 52 && pick < 0.120f -> "iron_ore"
            y < 56 && pick < 0.150f -> "copper_ore"
            y < 96 && pick < 0.260f -> "coal_ore"
            else -> null
        }
    }

    // ------------------------------------------------------------------ generación

    fun generate(chunk: Chunk) {
        if (chunk.generated) return
        val baseX = chunk.cx * 16
        val baseZ = chunk.cz * 16
        val H = World.HEIGHT

        val heights = IntArray(256)
        val biomes = IntArray(256)
        for (lz in 0 until 16) for (lx in 0 until 16) {
            heights[lz * 16 + lx] = heightAt(baseX + lx, baseZ + lz)
            biomes[lz * 16 + lx] = biomeAt(baseX + lx, baseZ + lz)
        }

        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                val wx = baseX + lx
                val wz = baseZ + lz
                val h = heights[lz * 16 + lx]
                val biome = biomes[lz * 16 + lx]
                val underwater = h < SEA_LEVEL
                val beach = !underwater && h <= SEA_LEVEL + 1
                val peak = h > SEA_LEVEL + 46
                val snowLine = h > SEA_LEVEL + 38

                for (y in 0..h) {
                    val block = when {
                        y == 0 -> "bedrock"
                        y <= BEDROCK_TOP && hash3(wx, y, wz) < (BEDROCK_TOP - y + 1) / 4f -> "bedrock"
                        y == h -> when {
                            peak -> "stone"
                            snowLine || biome == Biomes.SNOWY -> "snow"
                            biome == Biomes.DESERT || beach -> "sand"
                            underwater -> if (h < SEA_LEVEL - 12) "gravel" else "sand"
                            biome == Biomes.TAIGA -> "grass"
                            else -> "grass"
                        }
                        y >= h - 3 -> when {
                            peak -> "stone"
                            biome == Biomes.DESERT || beach -> "sandstone"
                            underwater && h < SEA_LEVEL - 12 -> "gravel"
                            biome == Biomes.SNOWY -> "dirt"
                            else -> "dirt"
                        }
                        y < 8 -> "deepslate"
                        else -> "stone"
                    }
                    chunk.set(lx, y, lz, Blocks.index(block))
                }

                // Menas dentro de la piedra (nunca en la superficie).
                for (y in BEDROCK_TOP + 1 until h - 1) {
                    if (y >= H) break
                    val current = chunk.get(lx, y, lz)
                    if (current != Blocks.index("stone") && current != Blocks.index("deepslate")) continue
                    val ore = oreAt(wx, y, wz) ?: continue
                    if (Blocks.index(ore) != Blocks.AIR) chunk.set(lx, y, lz, Blocks.index(ore))
                }

                // Cuevas, sin tocar el lecho marino para que el agua no se escape.
                val caveBottom = BEDROCK_TOP + 1
                val caveTop = if (underwater) h - 6 else h - 2
                for (y in caveBottom until caveTop) {
                    if (y >= H) break
                    if (!caveAt(wx, y, wz)) continue
                    chunk.set(lx, y, lz, if (y <= 9) Blocks.index("lava") else Blocks.AIR)
                }

                // Agua y hielo.
                for (y in (h + 1)..SEA_LEVEL) {
                    if (y >= H) break
                    chunk.set(lx, y, lz, Blocks.index("water"))
                }
                if (biome == Biomes.SNOWY && underwater && h + 1 <= SEA_LEVEL) {
                    chunk.set(lx, SEA_LEVEL, lz, Blocks.index("ice"))
                }
            }
        }

        // Vegetación, nieve y estructuras.
        decorate(chunk, heights, biomes)
        maybeRuins(chunk, heights)
        maybeDungeon(chunk, heights)
        maybeVillageHouse(chunk, heights)

        Mobs.spawnInChunk(chunk, this)

        var top = 0
        for (y in H - 1 downTo 0) {
            var any = false
            for (lz in 0 until 16) {
                for (lx in 0 until 16) {
                    if (chunk.get(lx, y, lz) != Blocks.AIR) {
                        any = true
                        break
                    }
                }
                if (any) break
            }
            if (any) {
                top = y
                break
            }
        }
        chunk.maxY = top
        chunk.generated = true
        chunk.dirty = true
    }

    // ------------------------------------------------------------------ decoración

    private fun decorate(chunk: Chunk, heights: IntArray, biomes: IntArray) {
        val baseX = chunk.cx * 16
        val baseZ = chunk.cz * 16

        // Árboles: se dejan 3 bloques de margen para que la copa quepa en el chunk.
        for (lz in 3..12) {
            for (lx in 3..12) {
                val wx = baseX + lx
                val wz = baseZ + lz
                val biome = biomes[lz * 16 + lx]
                val h = heights[lz * 16 + lx]
                if (h < SEA_LEVEL || h > SEA_LEVEL + 44) continue
                val density = when (biome) {
                    Biomes.FOREST -> 0.055f
                    Biomes.JUNGLE -> 0.060f
                    Biomes.TAIGA -> 0.040f
                    Biomes.SNOWY -> 0.014f
                    Biomes.PLAINS -> 0.006f
                    else -> 0f
                }
                if (density <= 0f) continue
                if (hash(wx * 17 + 3, wz * 31 - 7) > density) continue
                val groundBlock = chunk.get(lx, h, lz)
                if (groundBlock != Blocks.index("grass") && groundBlock != Blocks.index("dirt") &&
                    groundBlock != Blocks.index("snow")
                ) continue
                val roll = hash(wx * 7 + 11, wz * 13 + 5)
                when (biome) {
                    Biomes.SNOWY, Biomes.TAIGA -> spruce(chunk, lx, h, lz, wx, wz)
                    Biomes.JUNGLE -> if (roll < 0.45f) jungle(chunk, lx, h, lz, wx, wz)
                    else oak(chunk, lx, h, lz, wx, wz, roll > 0.6f)
                    Biomes.FOREST -> if (roll < 0.32f) birch(chunk, lx, h, lz, wx, wz)
                    else oak(chunk, lx, h, lz, wx, wz, roll > 0.75f)
                    else -> oak(chunk, lx, h, lz, wx, wz, false)
                }
            }
        }

        // Nieve sobre la hierba de los biomas fríos y plantas del resto.
        for (lz in 0 until 16) {
            for (lx in 0 until 16) {
                val wx = baseX + lx
                val wz = baseZ + lz
                val biome = biomes[lz * 16 + lx]
                val h = heights[lz * 16 + lx]
                if (h + 1 >= World.HEIGHT || h < SEA_LEVEL) continue
                val groundBlock = chunk.get(lx, h, lz)
                if (chunk.get(lx, h + 1, lz) != Blocks.AIR) continue
                val noise = hash(wx * 31 + 7, wz * 17 - 3)

                if (biome == Biomes.SNOWY && groundBlock == Blocks.index("grass")) {
                    chunk.set(lx, h + 1, lz, Blocks.index("snow"))
                    continue
                }

                if (groundBlock == Blocks.index("sand")) {
                    if (biome == Biomes.DESERT) {
                        if (noise < 0.010f) {
                            val height = 1 + (hash(wx, wz) * 3).toInt()
                            for (i in 1..height) chunk.set(lx, h + i, lz, Blocks.index("cactus"))
                            continue
                        }
                        if (noise < 0.030f) {
                            chunk.set(lx, h + 1, lz, Blocks.index("dead_bush"))
                            continue
                        }
                    }
                    val nearWater = chunk.get(lx - 1, h, lz) == Blocks.index("water") ||
                        chunk.get(lx + 1, h, lz) == Blocks.index("water") ||
                        chunk.get(lx, h, lz - 1) == Blocks.index("water") ||
                        chunk.get(lx, h, lz + 1) == Blocks.index("water")
                    if (nearWater && noise < 0.55f) {
                        val height = 1 + (hash(wx * 3, wz * 5) * 3).toInt()
                        for (i in 1..height) chunk.set(lx, h + i, lz, Blocks.index("sugar_cane"))
                    }
                    continue
                }

                if (groundBlock != Blocks.index("grass")) continue
                when {
                    noise < 0.20f -> chunk.set(lx, h + 1, lz, Blocks.index("tall_grass"))
                    noise < 0.215f -> chunk.set(lx, h + 1, lz, Blocks.index("poppy"))
                    noise < 0.230f -> chunk.set(lx, h + 1, lz, Blocks.index("dandelion"))
                }
            }
        }
    }

    /** Esfera de hojas con huecos, como las copas del juego. */
    private fun leafBlob(chunk: Chunk, cx: Int, cy: Int, cz: Int, radius: Int, leaf: String, wx: Int, wz: Int) {
        val leafIndex = Blocks.index(leaf)
        val r2 = radius * radius + radius
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    if (dx * dx + dz * dz + dy * dy > r2) continue
                    if (dx == 0 && dz == 0 && dy < 0) continue
                    val tx = cx + dx
                    val ty = cy + dy
                    val tz = cz + dz
                    if (tx in 0..15 && tz in 0..15 && ty in 0 until World.HEIGHT) {
                        if (chunk.get(tx, ty, tz) == Blocks.AIR && hash3(wx + dx, ty, wz + dz) > 0.08f) {
                            chunk.set(tx, ty, tz, leafIndex)
                        }
                    }
                }
            }
        }
    }

    private fun oak(chunk: Chunk, lx: Int, gy: Int, lz: Int, wx: Int, wz: Int, big: Boolean) {
        val logIndex = Blocks.index("log")
        val trunk = 4 + (hash(wx, wz) * 3).toInt() + if (big) 2 else 0
        for (i in 1..trunk) chunk.set(lx, gy + i, lz, logIndex)
        leafBlob(chunk, lx, gy + trunk, lz, if (big) 3 else 2, "leaves", wx, wz)
    }

    private fun birch(chunk: Chunk, lx: Int, gy: Int, lz: Int, wx: Int, wz: Int) {
        val logIndex = Blocks.index("birch_log")
        val trunk = 5 + (hash(wx, wz) * 3).toInt()
        for (i in 1..trunk) chunk.set(lx, gy + i, lz, logIndex)
        leafBlob(chunk, lx, gy + trunk, lz, 2, "birch_leaves", wx, wz)
    }

    private fun jungle(chunk: Chunk, lx: Int, gy: Int, lz: Int, wx: Int, wz: Int) {
        val logIndex = Blocks.index("log")
        val trunk = 7 + (hash(wx, wz) * 5).toInt()
        for (i in 1..trunk) chunk.set(lx, gy + i, lz, logIndex)
        leafBlob(chunk, lx, gy + trunk, lz, 3, "leaves", wx, wz)
        // Lianas colgando de la copa.
        for (i in (trunk - 2) downTo 1) {
            if (hash3(wx, gy + i, wz) > 0.85f) chunk.set(lx, gy + i, lz, Blocks.index("vine"))
        }
    }

    private fun spruce(chunk: Chunk, lx: Int, gy: Int, lz: Int, wx: Int, wz: Int) {
        val logIndex = Blocks.index("spruce_log")
        val leafIndex = Blocks.index("spruce_leaves")
        val trunk = 7 + (hash(wx, wz) * 5).toInt()
        for (i in 1..trunk) chunk.set(lx, gy + i, lz, logIndex)
        // Copa cónica por capas, de abajo arriba.
        var radius = 2
        var layer = 0
        for (y in 2 until trunk) {
            for (dx in -radius..radius) {
                for (dz in -radius..radius) {
                    if (dx * dx + dz * dz > radius * radius) continue
                    val tx = lx + dx
                    val tz = lz + dz
                    val ty = gy + y
                    if (tx in 0..15 && tz in 0..15 && ty in 0 until World.HEIGHT) {
                        if (chunk.get(tx, ty, tz) == Blocks.AIR) chunk.set(tx, ty, tz, leafIndex)
                    }
                }
            }
            layer++
            if (layer % 3 == 0) radius = if (radius > 0) radius - 1 else 0
        }
        chunk.set(lx, gy + trunk + 1, lz, leafIndex)
    }

    // ------------------------------------------------------------------ estructuras

    /** Ruinas: suelo de piedra y pilares rotos de 1-3 bloques, con una antorcha. */
    private fun maybeRuins(chunk: Chunk, heights: IntArray) {
        val region = 7
        val rx = Math.floorDiv(chunk.cx, region)
        val rz = Math.floorDiv(chunk.cz, region)
        if (hash(rx * 73 + 11, rz * 97 - 5) > 0.22f) return

        val lx = 4 + (hash(rx * 13 + 3, rz * 17 + 9) * 7).toInt()
        val lz = 4 + (hash(rx * 23 + 5, rz * 31 + 1) * 7).toInt()
        val ground = heights[lz * 16 + lx]
        if (ground <= SEA_LEVEL + 1) return
        val biome = biomeAt(chunk.cx * 16 + lx, chunk.cz * 16 + lz)
        if (biome == Biomes.SNOWY) return

        val cobble = Blocks.index("cobblestone")
        val planks = Blocks.index("planks")
        for (dx in -2..2) {
            for (dz in -2..2) {
                val tx = lx + dx
                val tz = lz + dz
                if (tx !in 0..15 || tz !in 0..15) continue
                val id = Blocks.get(chunk.get(tx, ground, tz)).id
                if (id != "grass" && id != "sand") continue
                if (hash(chunk.cx * 16 + tx + dx * 7, chunk.cz * 16 + tz + dz * 11) > 0.22f) {
                    chunk.set(tx, ground, tz, if (hash(tx * 3, tz * 5) > 0.6f) planks else cobble)
                }
            }
        }
        val corners = arrayOf(intArrayOf(-2, -2), intArrayOf(2, -2), intArrayOf(-2, 2), intArrayOf(2, 2))
        for ((index, c) in corners.withIndex()) {
            val tx = lx + c[0]
            val tz = lz + c[1]
            if (tx !in 0..15 || tz !in 0..15) continue
            val height = 1 + (hash(tx * 31, tz * 17) * 3).toInt()
            for (i in 1..height) {
                if (ground + i < World.HEIGHT) chunk.set(tx, ground + i, tz, cobble)
            }
            if (index == 0 && height >= 2) chunk.set(tx, ground + height + 1, tz, Blocks.index("torch"))
        }
    }

    /** Mazmorra: sala de 7×4×7 de roca musgosa con un spawner y dos cofres. */
    private fun maybeDungeon(chunk: Chunk, heights: IntArray) {
        val region = 9
        val rx = Math.floorDiv(chunk.cx, region)
        val rz = Math.floorDiv(chunk.cz, region)
        if (hash(rx * 131 + 7, rz * 71 + 3) > 0.30f) return
        val ground = heights[6 * 16 + 6]
        val cy = 12 + (hash(rx * 17 + 1, rz * 19 + 5) * 26).toInt()
        if (cy + 5 >= ground - 5) return

        val mossy = Blocks.index("mossy_cobblestone")
        val cobble = Blocks.index("cobblestone")
        for (dx in -3..3) {
            for (dz in -3..3) {
                for (dy in 0..3) {
                    val tx = 6 + dx
                    val tz = 6 + dz
                    if (tx !in 0..15 || tz !in 0..15) continue
                    val y = cy + dy
                    if (y < 2 || y >= World.HEIGHT - 1) continue
                    val edge = dx == -3 || dx == 3 || dz == -3 || dz == 3 || dy == 0 || dy == 3
                    if (edge) {
                        chunk.set(tx, y, tz, if (hash(tx * 3 + dy, tz * 5) > 0.5f) mossy else cobble)
                    } else {
                        chunk.set(tx, y, tz, Blocks.AIR)
                    }
                }
            }
        }
        chunk.set(6, cy + 1, 6, Blocks.index("trial_spawner"))
        chunk.set(4, cy + 1, 8, Blocks.index("chest"))
        chunk.set(8, cy + 1, 4, Blocks.index("chest"))
        chunk.set(4, cy + 2, 4, Blocks.index("torch"))
        chunk.set(8, cy + 2, 8, Blocks.index("torch"))
    }

    /** Casa de aldea: base de piedra, muros de tablón, postes de tronco y tejado. */
    private fun maybeVillageHouse(chunk: Chunk, heights: IntArray) {
        val region = 11
        val rx = Math.floorDiv(chunk.cx, region)
        val rz = Math.floorDiv(chunk.cz, region)
        if (hash(rx * 211 + 13, rz * 97 + 29) > 0.40f) return

        val lx = 5
        val lz = 5
        val ground = heights[5 * 16 + 5]
        if (ground <= SEA_LEVEL + 1 || ground + 8 >= World.HEIGHT) return
        val biome = biomeAt(chunk.cx * 16 + lx, chunk.cz * 16 + lz)
        if (biome == Biomes.DESERT) return

        val planks = Blocks.index("oak_planks")
        val log = Blocks.index("oak_log")
        val cobble = Blocks.index("cobblestone")
        val glass = Blocks.index("glass")
        val w = 5
        val d = 5

        // Cimiento y hueco interior.
        for (dx in -1..w) {
            for (dz in -1..d) {
                val tx = lx + dx
                val tz = lz + dz
                if (tx !in 0..15 || tz !in 0..15) continue
                chunk.set(tx, ground, tz, cobble)
                for (dy in 1..5) chunk.set(tx, ground + dy, tz, Blocks.AIR)
            }
        }
        // Muros con ventanas y puerta.
        for (dy in 1..3) {
            for (dx in 0 until w) {
                for (dz in 0 until d) {
                    val tx = lx + dx
                    val tz = lz + dz
                    if (tx !in 0..15 || tz !in 0..15) continue
                    val border = dx == 0 || dx == w - 1 || dz == 0 || dz == d - 1
                    if (!border) continue
                    val corner = (dx == 0 || dx == w - 1) && (dz == 0 || dz == d - 1)
                    if (dz == d - 1 && dx == w / 2 && dy <= 2) continue          // puerta
                    val window = dy == 2 && !corner && ((dx + dz) % 4 == 1)
                    chunk.set(tx, ground + dy, tz, if (corner) log else if (window) glass else planks)
                }
            }
        }
        // Tejado a dos aguas.
        for (dx in -1..w) {
            for (dz in -1..d) {
                val tx = lx + dx
                val tz = lz + dz
                if (tx !in 0..15 || tz !in 0..15) continue
                chunk.set(tx, ground + 4, tz, planks)
            }
        }
        for (dx in 0 until w) {
            chunk.set(lx + dx, ground + 5, lz + d / 2, planks)
        }
        // Interior amueblado.
        chunk.set(lx + 1, ground + 1, lz + 1, Blocks.index("crafting_table"))
        chunk.set(lx + 2, ground + 1, lz + 1, Blocks.index("furnace"))
        chunk.set(lx + 3, ground + 1, lz + 1, Blocks.index("chest"))
        chunk.set(lx + 2, ground + 2, lz + 2, Blocks.index("torch"))
    }

    // ------------------------------------------------------------------ spawn

    /**
     * Punto de aparición: busca tierra firme por encima del nivel del mar, cerca del origen y
     * prefiriendo llanura/bosque, como hace el juego con el spawn del mundo.
     */
    fun spawnPoint(): Triple<Double, Double, Double> {
        var bestX = 0
        var bestZ = 0
        var bestScore = -1e9f
        for (i in 0 until 160) {
            val ang = i * 0.61f
            val dist = 6 + i * 5f
            val x = (kotlin.math.cos(ang) * dist).toInt()
            val z = (kotlin.math.sin(ang) * dist).toInt()
            val h = heightAt(x, z)
            if (h <= SEA_LEVEL + 1) continue
            val biome = biomeAt(x, z)
            val biomeBonus = when (biome) {
                Biomes.PLAINS -> 8f
                Biomes.FOREST -> 5f
                Biomes.SNOWY -> -8f
                Biomes.DESERT -> -5f
                Biomes.JUNGLE -> -2f
                else -> 0f
            }
            val score = (h - SEA_LEVEL).toFloat() * 0.5f + biomeBonus - dist * 0.25f
            if (score > bestScore) {
                bestScore = score
                bestX = x
                bestZ = z
            }
        }
        val h = max(heightAt(bestX, bestZ), SEA_LEVEL + 2)
        return Triple(bestX + 0.5, h + 1.05, bestZ + 0.5)
    }
}
