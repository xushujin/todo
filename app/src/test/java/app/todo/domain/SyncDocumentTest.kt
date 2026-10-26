package app.todo.domain

import app.todo.domain.model.Priority
import app.todo.domain.model.ReminderChannels
import app.todo.domain.model.SyncSnapshot
import app.todo.domain.model.TodoItem
import app.todo.domain.model.TodoList
import app.todo.domain.sync.SyncDocument
import app.todo.domain.sync.SyncFormatException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncDocumentTest {

    private fun fixture(name: String): String =
        checkNotNull(javaClass.getResourceAsStream("/fixtures/$name")) { "missing fixture $name" }
            .use { it.readBytes().toString(Charsets.UTF_8) }

    private val list = TodoList(
        id = "inbox",
        name = "收件箱",
        colorArgb = 0xFF4C5BD4,
        sortOrder = 0,
        updatedAt = 1_000,
        deviceId = "dev-a",
        createdAt = 500,
        deletedAt = null,
    )

    private val item = TodoItem(
        id = "a1",
        listId = "inbox",
        title = "写周报",
        notes = "含本周指标",
        priority = Priority.HIGH,
        dueAt = 2_000,
        remindAt = 1_800,
        reminderChannels = ReminderChannels.CLOCK or ReminderChannels.EMAIL,
        pinned = true,
        done = false,
        doneAt = null,
        createdAt = 500,
        updatedAt = 1_000,
        deviceId = "dev-a",
        deletedAt = null,
    )

    @Test
    fun decodesDocumentWrittenByTheWebClient() {
        val decoded = SyncDocument.decode(fixture("todos-document.json"))

        assertEquals(1, decoded.schemaVersion)
        assertEquals("web", decoded.writer)
        assertEquals(1, decoded.snapshot.lists.size)
        assertEquals(2, decoded.snapshot.items.size)
        val phone = decoded.snapshot.items.first { it.id == "a2" }
        assertEquals("给妈妈打电话", phone.title)
        assertEquals(Priority.URGENT, phone.priority)
        assertEquals(ReminderChannels.CLOCK or ReminderChannels.EMAIL, phone.reminderChannels)
        assertTrue(phone.pinned)
        assertTrue(phone.done)
        assertEquals(4_283_485_140L, decoded.snapshot.lists.single().colorArgb)
    }

    @Test
    fun roundTripsThroughEncodeAndDecode() {
        val snapshot = SyncSnapshot(lists = listOf(list), items = listOf(item))
        val json = SyncDocument.encode(snapshot, writerDeviceId = "dev-a", generatedAt = 9_000)

        val decoded = SyncDocument.decode(json)

        assertEquals(snapshot.canonical(), decoded.snapshot)
        assertEquals(9_000L, decoded.generatedAt)
        assertEquals("dev-a", decoded.writer)
        assertTrue(json.contains("\"schemaVersion\": 1"))
        assertTrue(json.contains("\"priority\": \"high\""))
    }

    @Test
    fun rejectsNewerSchemaInsteadOfDroppingFields() {
        val json = """{"schemaVersion":2,"lists":[],"items":[]}"""
        val error = assertThrows(SyncFormatException::class.java) { SyncDocument.decode(json) }
        assertTrue(error.message.orEmpty().contains("schemaVersion 2"))
    }

    @Test
    fun rejectsStructurallyInvalidDocuments() {
        assertThrows(SyncFormatException::class.java) { SyncDocument.decode("[]") }
        assertThrows(SyncFormatException::class.java) { SyncDocument.decode("{}") }
        assertThrows(SyncFormatException::class.java) {
            SyncDocument.decode("""{"schemaVersion":1,"items":[{"title":"no id"}]}""")
        }
        assertThrows(SyncFormatException::class.java) { SyncDocument.decode("not json") }
    }

    @Test
    fun toleratesMissingOptionalFields() {
        val decoded = SyncDocument.decode(
            """{"schemaVersion":1,"items":[{"id":"x","updatedAt":7}]}""",
        )
        val decodedItem = decoded.snapshot.items.single()
        assertEquals("x", decodedItem.id)
        assertEquals(Priority.NORMAL, decodedItem.priority)
        assertEquals(0, decodedItem.reminderChannels)
        assertEquals(7L, decodedItem.createdAt)
        assertEquals(0L, decoded.generatedAt)
    }

    @Test
    fun encodingIsStableForEqualSnapshots() {
        val a = SyncDocument.encode(
            SyncSnapshot(items = listOf(item, item.copy(id = "a0"))),
            "dev-a",
            1,
        )
        val b = SyncDocument.encode(
            SyncSnapshot(items = listOf(item.copy(id = "a0"), item)),
            "dev-a",
            1,
        )
        assertEquals(a, b)
    }
}
