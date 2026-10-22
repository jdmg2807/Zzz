package com.arena.voxel.core

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.DataOutputStream
import java.io.File
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import java.util.Random

/**
 * Procedural sound effects.
 *
 * Minecraft ships hundreds of OGG files; this engine has no audio assets, so every effect is
 * synthesised once at start-up into a 16-bit mono WAV and handed to [SoundPool], which mixes
 * them properly (several footsteps at once, overlapping hits, …).
 *
 * The recipes follow the shape of the real sounds:
 * * `dig_*`   — short filtered noise burst, brighter for stone, duller for dirt/grass/wood.
 * * `place`   — the same burst with a low thump on top.
 * * `step`    — very quiet, short burst.
 * * `hurt`    — a falling square-ish tone.
 * * `eat`     — three tiny chomps.
 * * `levelup` — two rising sine notes.
 * * `click`   — a 30 ms UI tick.
 */
object Sounds {

    private const val RATE = 22050

    private var pool: SoundPool? = null
    private var loaded = false
    private val ids = HashMap<String, Int>()
    private val rnd = Random(1234L)

    /** Master switch, wired to the settings screen. */
    @Volatile var enabled = true

    fun init(context: Context) {
        if (loaded) return
        loaded = true
        try {
            val dir = File(context.cacheDir, "sounds").apply { mkdirs() }
            val soundPool = SoundPool.Builder()
                .setMaxStreams(12)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                .build()
            pool = soundPool
            val samples = mapOf(
                "dig_stone" to noiseBurst(0.11f, 2400f, 0.55f),
                "dig_wood" to noiseBurst(0.12f, 900f, 0.5f),
                "dig_soft" to noiseBurst(0.10f, 500f, 0.4f),
                "place" to thump(),
                "step" to noiseBurst(0.06f, 700f, 0.22f),
                "hurt" to hurtTone(),
                "eat" to chomps(),
                "levelup" to levelUp(),
                "click" to click(),
                "splash" to noiseBurst(0.22f, 1500f, 0.35f)
            )
            for ((name, data) in samples) {
                val file = File(dir, "$name.wav")
                writeWav(file, data)
                ids[name] = soundPool.load(file.absolutePath, 1)
            }
            Log.i("Sonidos sintetizados: ${samples.size}")
        } catch (t: Throwable) {
            Log.w("No se pudo inicializar el audio: ${t.message}")
        }
    }

    /** Plays a sound with a random pitch, like Java's `playSound` with a volume/pitch spread. */
    fun play(name: String, volume: Float = 0.7f, pitch: Float = 1f) {
        if (!enabled) return
        val p = pool ?: return
        val id = ids[name] ?: return
        val rate = (pitch * (0.94f + rnd.nextFloat() * 0.12f)).coerceIn(0.5f, 2f)
        try {
            p.play(id, volume, volume, 1, 0, rate)
        } catch (_: Throwable) {
            // The pool may be shutting down while the activity finishes; nothing to do.
        }
    }

    /** Picks the right dig sound for a block, mirroring the material-based sound groups. */
    fun dig(blockId: String, volume: Float = 0.7f) {
        val name = when {
            blockId.contains("stone") || blockId.contains("ore") || blockId.contains("cobble") ||
                blockId.contains("deepslate") || blockId.contains("brick") || blockId.contains("tuff") ||
                blockId.contains("andesite") || blockId.contains("granite") || blockId.contains("diorite") ||
                blockId.contains("furnace") || blockId.contains("anvil") ->
                "dig_stone"
            blockId.contains("plank") || blockId.contains("log") || blockId.contains("wood") ||
                blockId.contains("chest") || blockId.contains("table") || blockId.contains("fence") ->
                "dig_wood"
            else -> "dig_soft"
        }
        play(name, volume)
    }

    fun release() {
        try {
            pool?.release()
        } catch (_: Throwable) {
        }
        pool = null
        loaded = false
        ids.clear()
    }

    // ------------------------------------------------------------------ synthesis

    private fun noiseBurst(seconds: Float, cutoff: Float, gain: Float): FloatArray {
        val n = (RATE * seconds).toInt().coerceAtLeast(64)
        val out = FloatArray(n)
        // One-pole low-pass over white noise; `cutoff` is expressed in Hz.
        val alpha = (2.0 * PI * cutoff / RATE).coerceIn(0.01, 1.0)
        var lp = 0.0
        val decay = 9.0 / n
        for (i in 0 until n) {
            val white = rnd.nextDouble() * 2.0 - 1.0
            lp += alpha * (white - lp)
            val env = exp(-decay * i) * (1.0 - exp(-40.0 * i / n))
            out[i] = (lp * env * gain).toFloat()
        }
        return out
    }

    private fun thump(): FloatArray {
        val n = (RATE * 0.16f).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val env = exp(-22.0 * t)
            val tone = sin(2.0 * PI * 140.0 * t) * 0.55
            val knock = (rnd.nextDouble() * 2.0 - 1.0) * exp(-60.0 * t) * 0.35
            out[i] = ((tone + knock) * env).toFloat()
        }
        return out
    }

    private fun hurtTone(): FloatArray {
        val n = (RATE * 0.26f).toInt()
        val out = FloatArray(n)
        var phase = 0.0
        for (i in 0 until n) {
            val t = i.toDouble() / n
            val freq = 420.0 - 220.0 * t
            phase += 2.0 * PI * freq / RATE
            val env = exp(-4.5 * t)
            // Square-ish wave for the "oof" character of the damage sound.
            val sq = if (sin(phase) >= 0) 0.35 else -0.35
            out[i] = (sq * env).toFloat()
        }
        return out
    }

    private fun chomps(): FloatArray {
        val burst = noiseBurst(0.055f, 1200f, 0.5f)
        val total = burst.size * 3 + burst.size
        val out = FloatArray(total)
        for (k in 0 until 3) {
            val offset = k * burst.size
            for (i in burst.indices) out[offset + i] = burst[i] * (if (k == 1) 0.7f else 1f)
        }
        return out
    }

    private fun levelUp(): FloatArray {
        val n = (RATE * 0.45f).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / RATE
            val f = if (t < 0.18) 660.0 else 990.0
            val env = exp(-3.0 * t)
            out[i] = (sin(2.0 * PI * f * t) * 0.35 * env).toFloat()
        }
        return out
    }

    private fun click(): FloatArray {
        val n = (RATE * 0.03f).toInt()
        val out = FloatArray(n)
        for (i in 0 until n) {
            val env = exp(-90.0 * i / n)
            out[i] = ((rnd.nextDouble() * 2.0 - 1.0) * 0.4 * env).toFloat()
        }
        return out
    }

    private fun writeWav(file: File, samples: FloatArray) {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            val dataSize = samples.size * 2
            out.writeBytes("RIFF")
            writeIntLE(out, 36 + dataSize)
            out.writeBytes("WAVEfmt ")
            writeIntLE(out, 16)
            writeShortLE(out, 1)              // PCM
            writeShortLE(out, 1)              // mono
            writeIntLE(out, RATE)
            writeIntLE(out, RATE * 2)         // byte rate
            writeShortLE(out, 2)              // block align
            writeShortLE(out, 16)             // bits per sample
            out.writeBytes("data")
            writeIntLE(out, dataSize)
            for (s in samples) {
                val v = (s.coerceIn(-1f, 1f) * 32767f).toInt()
                writeShortLE(out, v)
            }
        }
    }

    private fun writeIntLE(out: DataOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
        out.write((v shr 16) and 0xFF)
        out.write((v shr 24) and 0xFF)
    }

    private fun writeShortLE(out: DataOutputStream, v: Int) {
        out.write(v and 0xFF)
        out.write((v shr 8) and 0xFF)
    }
}
