package com.arena.modeler.core

/**
 * Base64 sin dependencias de Android, para que todo el módulo de E/S se pueda probar en la JVM.
 * Acepta y produce el alfabeto estándar; al decodificar ignora saltos de línea y espacios y
 * tolera que falte el relleno (`=`), que es lo que hacen algunos exportadores de glTF.
 */
object Text64 {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    private val DECODE = IntArray(128) { -1 }.also { table ->
        for (i in ALPHABET.indices) table[ALPHABET[i].code] = i
        table['-'.code] = 62   // variante URL-safe
        table['_'.code] = 63
    }

    fun encode(data: ByteArray): String {
        if (data.isEmpty()) return ""
        val sb = StringBuilder(((data.size + 2) / 3) * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xFF) shl 16) or
                ((data[i + 1].toInt() and 0xFF) shl 8) or
                (data[i + 2].toInt() and 0xFF)
            sb.append(ALPHABET[(n shr 18) and 63])
            sb.append(ALPHABET[(n shr 12) and 63])
            sb.append(ALPHABET[(n shr 6) and 63])
            sb.append(ALPHABET[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xFF) shl 16
                sb.append(ALPHABET[(n shr 18) and 63])
                sb.append(ALPHABET[(n shr 12) and 63])
                sb.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xFF) shl 16) or ((data[i + 1].toInt() and 0xFF) shl 8)
                sb.append(ALPHABET[(n shr 18) and 63])
                sb.append(ALPHABET[(n shr 12) and 63])
                sb.append(ALPHABET[(n shr 6) and 63])
                sb.append('=')
            }
        }
        return sb.toString()
    }

    /** Devuelve `null` si la cadena no es Base64 válido (en lugar de lanzar). */
    fun decodeOrNull(text: String): ByteArray? {
        val out = java.io.ByteArrayOutputStream(text.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (ch in text) {
            if (ch == '=' ) break
            if (ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t') continue
            val code = ch.code
            val value = if (code in 0 until 128) DECODE[code] else -1
            if (value < 0) return null
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
            }
        }
        return out.toByteArray()
    }

    fun decode(text: String): ByteArray =
        decodeOrNull(text) ?: throw IllegalArgumentException("Base64 inválido")
}
