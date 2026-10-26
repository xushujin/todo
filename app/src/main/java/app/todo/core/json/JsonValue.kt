package app.todo.core.json

/**
 * Minimal immutable JSON tree.
 *
 * The app deliberately does not depend on a serialisation library: the only JSON
 * that crosses a process boundary is the sync document and the REST payloads of
 * the three supported git hosts, so a ~300 line reader/writer is cheaper than a
 * dependency and keeps the wire format fully under our control.
 *
 * Numbers keep their original lexeme ([Num.raw]) so 64-bit epoch milliseconds
 * never round-trip through a [Double].
 */
sealed class JsonValue {

    data class Obj(val fields: Map<String, JsonValue>) : JsonValue()

    data class Arr(val items: List<JsonValue>) : JsonValue()

    data class Str(val value: String) : JsonValue()

    data class Num(val raw: String) : JsonValue() {
        fun toLongOrNull(): Long? = raw.toLongOrNull() ?: raw.toDoubleOrNull()?.toLong()

        fun toDoubleOrNull(): Double? = raw.toDoubleOrNull()
    }

    data class Bool(val value: Boolean) : JsonValue()

    data object Null : JsonValue()

    // ---- accessors -------------------------------------------------------

    operator fun get(key: String): JsonValue? = (this as? Obj)?.fields?.get(key)

    fun obj(key: String): Obj? = this[key] as? Obj

    fun arr(key: String): Arr? = this[key] as? Arr

    fun str(key: String): String? = (this[key] as? Str)?.value

    fun long(key: String): Long? = (this[key] as? Num)?.toLongOrNull()

    fun int(key: String): Int? = long(key)?.let { if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else null }

    fun bool(key: String): Boolean? = (this[key] as? Bool)?.value

    /** True when the field exists and is not JSON null. */
    fun has(key: String): Boolean = this[key] != null && this[key] != Null

    fun toJson(pretty: Boolean = false): String = JsonWriter(pretty).write(this)

    companion object {
        fun of(value: String?): JsonValue = value?.let(::Str) ?: Null
        fun of(value: Long?): JsonValue = value?.let { Num(it.toString()) } ?: Null
        fun of(value: Int?): JsonValue = value?.let { Num(it.toString()) } ?: Null
        fun of(value: Boolean?): JsonValue = value?.let(::Bool) ?: Null

        fun obj(vararg fields: Pair<String, JsonValue>): Obj = Obj(linkedMapOf(*fields))

        fun obj(fields: Map<String, JsonValue>): Obj = Obj(LinkedHashMap(fields))

        fun arr(items: List<JsonValue>): Arr = Arr(items)

        fun arr(vararg items: JsonValue): Arr = Arr(items.toList())

        fun parse(text: String): JsonValue = JsonParser(text).parse()

        fun parseOrNull(text: String): JsonValue? = try {
            parse(text)
        } catch (_: JsonParseException) {
            null
        }
    }
}

class JsonParseException(message: String, val offset: Int) : Exception("$message (at offset $offset)")

private const val MAX_DEPTH = 64

private class JsonParser(private val text: String) {

    private var index = 0

    fun parse(): JsonValue {
        val value = parseValue(0)
        skipWhitespace()
        if (index < text.length) fail("Unexpected trailing content")
        return value
    }

    private fun parseValue(depth: Int): JsonValue {
        if (depth > MAX_DEPTH) fail("Nesting too deep")
        skipWhitespace()
        if (index >= text.length) fail("Unexpected end of input")
        return when (val c = text[index]) {
            '{' -> parseObject(depth)
            '[' -> parseArray(depth)
            '"' -> JsonValue.Str(parseString())
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            else -> if (c == '-' || c in '0'..'9') parseNumber() else fail("Unexpected character '$c'")
        }
    }

    private fun parseObject(depth: Int): JsonValue {
        expect('{')
        val fields = LinkedHashMap<String, JsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            index++
            return JsonValue.Obj(fields)
        }
        while (true) {
            skipWhitespace()
            if (peek() != '"') fail("Expected a string key")
            val key = parseString()
            skipWhitespace()
            expect(':')
            fields[key] = parseValue(depth + 1)
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                '}' -> {
                    index++
                    return JsonValue.Obj(fields)
                }
                else -> fail("Expected ',' or '}'")
            }
        }
    }

    private fun parseArray(depth: Int): JsonValue {
        expect('[')
        val items = ArrayList<JsonValue>()
        skipWhitespace()
        if (peek() == ']') {
            index++
            return JsonValue.Arr(items)
        }
        while (true) {
            items.add(parseValue(depth + 1))
            skipWhitespace()
            when (peek()) {
                ',' -> index++
                ']' -> {
                    index++
                    return JsonValue.Arr(items)
                }
                else -> fail("Expected ',' or ']'")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (index >= text.length) fail("Unterminated string")
            val c = text[index++]
            when {
                c == '"' -> return out.toString()
                c == '\\' -> {
                    if (index >= text.length) fail("Unterminated escape")
                    when (val esc = text[index++]) {
                        '"' -> out.append('"')
                        '\\' -> out.append('\\')
                        '/' -> out.append('/')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'n' -> out.append('\n')
                        'r' -> out.append('\r')
                        't' -> out.append('\t')
                        'u' -> out.append(parseUnicodeEscape())
                        else -> fail("Invalid escape '\\$esc'")
                    }
                }
                c.code < 0x20 -> fail("Control character in string")
                else -> out.append(c)
            }
        }
    }

    private fun parseUnicodeEscape(): Char {
        if (index + 4 > text.length) fail("Truncated \\u escape")
        var value = 0
        repeat(4) {
            val digit = Character.digit(text[index++], 16)
            if (digit < 0) fail("Invalid \\u escape")
            value = value * 16 + digit
        }
        return value.toChar()
    }

    private fun parseNumber(): JsonValue {
        val start = index
        if (peek() == '-') index++
        if (index >= text.length) fail("Truncated number")
        if (peek() == '0') {
            index++
        } else {
            if (peek() !in '1'..'9') fail("Invalid number")
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && text[index] == '.') {
            index++
            if (index >= text.length || text[index] !in '0'..'9') fail("Invalid fraction")
            while (index < text.length && text[index] in '0'..'9') index++
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index++
            if (index < text.length && (text[index] == '+' || text[index] == '-')) index++
            if (index >= text.length || text[index] !in '0'..'9') fail("Invalid exponent")
            while (index < text.length && text[index] in '0'..'9') index++
        }
        return JsonValue.Num(text.substring(start, index))
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        if (!text.startsWith(word, index)) fail("Invalid literal")
        index += word.length
        return value
    }

    private fun expect(c: Char) {
        if (index >= text.length || text[index] != c) fail("Expected '$c'")
        index++
    }

    private fun peek(): Char? = if (index < text.length) text[index] else null

    private fun skipWhitespace() {
        while (index < text.length) {
            when (text[index]) {
                ' ', '\t', '\n', '\r' -> index++
                else -> return
            }
        }
    }

    private fun fail(message: String): Nothing = throw JsonParseException(message, index)
}

private class JsonWriter(private val pretty: Boolean) {

    private val out = StringBuilder()

    fun write(value: JsonValue): String {
        writeValue(value, 0)
        return out.toString()
    }

    private fun writeValue(value: JsonValue, depth: Int) {
        when (value) {
            is JsonValue.Obj -> writeObject(value, depth)
            is JsonValue.Arr -> writeArray(value, depth)
            is JsonValue.Str -> writeString(value.value)
            is JsonValue.Num -> out.append(value.raw)
            is JsonValue.Bool -> out.append(if (value.value) "true" else "false")
            JsonValue.Null -> out.append("null")
        }
    }

    private fun writeObject(value: JsonValue.Obj, depth: Int) {
        if (value.fields.isEmpty()) {
            out.append("{}")
            return
        }
        out.append('{')
        var first = true
        for ((key, child) in value.fields) {
            if (!first) out.append(',')
            first = false
            newline(depth + 1)
            writeString(key)
            out.append(':')
            if (pretty) out.append(' ')
            writeValue(child, depth + 1)
        }
        newline(depth)
        out.append('}')
    }

    private fun writeArray(value: JsonValue.Arr, depth: Int) {
        if (value.items.isEmpty()) {
            out.append("[]")
            return
        }
        out.append('[')
        var first = true
        for (child in value.items) {
            if (!first) out.append(',')
            first = false
            newline(depth + 1)
            writeValue(child, depth + 1)
        }
        newline(depth)
        out.append(']')
    }

    private fun newline(depth: Int) {
        if (!pretty) return
        out.append('\n')
        repeat(depth) { out.append("  ") }
    }

    private fun writeString(value: String) {
        out.append('"')
        for (c in value) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                else -> if (c.code < 0x20) {
                    out.append("\\u")
                    out.append(c.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(c)
                }
            }
        }
        out.append('"')
    }
}
