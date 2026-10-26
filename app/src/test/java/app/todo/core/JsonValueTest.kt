package app.todo.core

import app.todo.core.codec.Base64Codec
import app.todo.core.json.JsonParseException
import app.todo.core.json.JsonValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonValueTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    @Test
    fun parsesRealGithubContentsResponse() {
        val root = JsonValue.parse(fixture("github-contents-response.json"))
        assertEquals("todos.json", root.str("name"))
        assertEquals("3b18e512dba79e4c8300dd08aeb37f8e728b8dad", root.str("sha"))
        assertEquals("base64", root.str("encoding"))
        assertNotNull(root.obj("_links"))

        val encoded = checkNotNull(root.str("content"))
        assertTrue("GitHub wraps base64 every 60 chars", encoded.contains("\n"))
        val bytes = Base64Codec.decode(encoded)
        assertEquals("size must match the decoded payload", bytes.size.toLong(), root.long("size"))
        val document = bytes.toString(Charsets.UTF_8)
        assertEquals(fixture("todos-document.json").trim(), document.trim())
    }

    @Test
    fun parsesRealGitlabFileResponse() {
        val root = JsonValue.parse(fixture("gitlab-file-response.json"))
        assertEquals("data/todos.json", root.str("file_path"))
        assertEquals("main", root.str("ref"))
        val revision = root.str("last_commit_id")
        assertEquals("0f1e2d3c4b5a69788796a5b4c3d2e1f0a9b8c7d6", revision)
        val decoded = Base64Codec.decodeToString(checkNotNull(root.str("content")))
        assertTrue(decoded.contains("\"schemaVersion\": 1"))
    }

    @Test
    fun keepsLongPrecision() {
        val value = JsonValue.parse("""{"t":1759999999123,"big":9223372036854775807}""")
        assertEquals(1759999999123L, value.long("t") ?: -1L)
        assertEquals(Long.MAX_VALUE, value.long("big") ?: -1L)
        assertEquals(1759999999123L, value.long("t")?.toDouble()?.toLong())
    }

    @Test
    fun handlesEscapesAndUnicode() {
        val value = JsonValue.parse("""{"a":"line\nbreak\t\"q\" \\ \u4f60\u597d","b":"\uD83D\uDE00"}""")
        assertEquals("line\nbreak\t\"q\" \\ 你好", value.str("a"))
        assertEquals("😀", value.str("b"))
    }

    @Test
    fun roundTripsThroughWriter() {
        val original = JsonValue.obj(
            "name" to JsonValue.of("待办"),
            "count" to JsonValue.of(3L),
            "done" to JsonValue.of(true),
            "none" to JsonValue.Null,
            "list" to JsonValue.arr(JsonValue.of("a"), JsonValue.of("b")),
        )
        val text = original.toJson(pretty = true)
        assertEquals(original, JsonValue.parse(text))
        assertEquals(original, JsonValue.parse(original.toJson()))
    }

    @Test
    fun rejectsMalformedDocuments() {
        assertThrows(JsonParseException::class.java) { JsonValue.parse("""{"a":1,}""") }
        assertThrows(JsonParseException::class.java) { JsonValue.parse("""{"a":1} extra""") }
        assertThrows(JsonParseException::class.java) { JsonValue.parse("""{"a":01}""") }
        assertThrows(JsonParseException::class.java) { JsonValue.parse("""{"a":"unterminated}""") }
        assertThrows(JsonParseException::class.java) { JsonValue.parse("") }
        assertNull(JsonValue.parseOrNull("{oops}"))
    }

    @Test
    fun missingAndNullFieldsAreDistinguishable() {
        val value = JsonValue.parse("""{"present":null,"text":"x"}""")
        assertTrue(value.has("text"))
        assertTrue(!value.has("present"))
        assertNull(value.str("present"))
        assertNull(value.str("absent"))
    }

    @Test
    fun base64MatchesRfc4648Vectors() {
        assertEquals("", Base64Codec.encode(ByteArray(0)))
        assertEquals("TWFu", Base64Codec.encode("Man".toByteArray()))
        assertEquals("TWE=", Base64Codec.encode("Ma".toByteArray()))
        assertEquals("TQ==", Base64Codec.encode("M".toByteArray()))
        assertEquals("Man", Base64Codec.decodeToString("TWFu"))
        assertEquals("sure.", Base64Codec.decodeToString("c3VyZS4="))
    }

    @Test
    fun base64IgnoresLineBreaksAndRoundTripsUnicode() {
        val text = "待办事项 / todo ✓"
        val encoded = Base64Codec.encode(text)
        val wrapped = encoded.chunked(8).joinToString("\n")
        assertEquals(text, Base64Codec.decodeToString(wrapped))
        assertThrows(IllegalArgumentException::class.java) { Base64Codec.decode("not base64!") }
    }
}
