package app.todo.core.codec

/**
 * Base64 (RFC 4648 §4) encoder/decoder.
 *
 * Written by hand instead of using [android.util.Base64] so that the git payload
 * encoding can be exercised by JVM unit tests, and instead of
 * [java.util.Base64] because that API needs API 26+ desugaring rules that are
 * easy to get wrong on Android.
 */
object Base64Codec {

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val PAD = '='

    private val encodeTable = ALPHABET.toCharArray()
    private val decodeTable = IntArray(128) { -1 }.also { table ->
        ALPHABET.forEachIndexed { index, c -> table[c.code] = index }
    }

    fun encode(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val out = StringBuilder((bytes.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < bytes.size) {
            val triple = (bytes[i].toInt() and 0xFF shl 16) or
                (bytes[i + 1].toInt() and 0xFF shl 8) or
                (bytes[i + 2].toInt() and 0xFF)
            out.append(encodeTable[triple ushr 18 and 0x3F])
            out.append(encodeTable[triple ushr 12 and 0x3F])
            out.append(encodeTable[triple ushr 6 and 0x3F])
            out.append(encodeTable[triple and 0x3F])
            i += 3
        }
        when (bytes.size - i) {
            1 -> {
                val chunk = (bytes[i].toInt() and 0xFF) shl 16
                out.append(encodeTable[chunk ushr 18 and 0x3F])
                out.append(encodeTable[chunk ushr 12 and 0x3F])
                out.append(PAD).append(PAD)
            }
            2 -> {
                val chunk = ((bytes[i].toInt() and 0xFF) shl 16) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
                out.append(encodeTable[chunk ushr 18 and 0x3F])
                out.append(encodeTable[chunk ushr 12 and 0x3F])
                out.append(encodeTable[chunk ushr 6 and 0x3F])
                out.append(PAD)
            }
        }
        return out.toString()
    }

    fun encode(text: String): String = encode(text.toByteArray(Charsets.UTF_8))

    /**
     * Decodes standard base64. Whitespace (including the newlines that the GitHub
     * contents API inserts every 60 characters) is ignored, missing padding is
     * tolerated, anything else is rejected.
     */
    fun decode(text: String): ByteArray {
        val clean = StringBuilder(text.length)
        for (c in text) {
            when {
                c == PAD -> {}
                c.isWhitespace() -> {}
                c.code < 128 && decodeTable[c.code] >= 0 -> clean.append(c)
                else -> throw IllegalArgumentException("Invalid base64 character '$c'")
            }
        }
        val length = clean.length
        if (length % 4 == 1) throw IllegalArgumentException("Invalid base64 length $length")
        val out = ByteArray(length / 4 * 3 + if (length % 4 == 0) 0 else (length % 4) - 1)
        var outIndex = 0
        var i = 0
        while (i + 3 < length) {
            val chunk = (decodeTable[clean[i].code] shl 18) or
                (decodeTable[clean[i + 1].code] shl 12) or
                (decodeTable[clean[i + 2].code] shl 6) or
                decodeTable[clean[i + 3].code]
            out[outIndex++] = (chunk ushr 16 and 0xFF).toByte()
            out[outIndex++] = (chunk ushr 8 and 0xFF).toByte()
            out[outIndex++] = (chunk and 0xFF).toByte()
            i += 4
        }
        when (length - i) {
            2 -> {
                val chunk = (decodeTable[clean[i].code] shl 18) or (decodeTable[clean[i + 1].code] shl 12)
                out[outIndex] = (chunk ushr 16 and 0xFF).toByte()
            }
            3 -> {
                val chunk = (decodeTable[clean[i].code] shl 18) or
                    (decodeTable[clean[i + 1].code] shl 12) or
                    (decodeTable[clean[i + 2].code] shl 6)
                out[outIndex++] = (chunk ushr 16 and 0xFF).toByte()
                out[outIndex] = (chunk ushr 8 and 0xFF).toByte()
            }
        }
        return out
    }

    fun decodeToString(text: String): String = decode(text).toString(Charsets.UTF_8)
}
