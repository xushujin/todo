package app.todo.domain.sync

import app.todo.core.json.JsonParseException
import app.todo.core.json.JsonValue
import app.todo.domain.model.Priority
import app.todo.domain.model.ReminderChannels
import app.todo.domain.model.SyncSnapshot
import app.todo.domain.model.TodoItem
import app.todo.domain.model.TodoList

class SyncFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class DecodedSyncDocument(
    val snapshot: SyncSnapshot,
    val schemaVersion: Int,
    val generatedAt: Long,
    val writer: String?,
)

/**
 * Wire format of `data/todos.json` (the single file that syncs through git).
 *
 * Forward compatibility: readers reject a document whose `schemaVersion` is newer
 * than [SCHEMA_VERSION] instead of silently dropping fields they do not know.
 * Unknown fields *inside* a supported version are ignored.
 *
 * The same shape is produced by web/index.html, so both clients can edit the file.
 */
object SyncDocument {

    const val SCHEMA_VERSION = 1

    fun encode(snapshot: SyncSnapshot, writerDeviceId: String, generatedAt: Long): String {
        val canonical = snapshot.canonical()
        val root = JsonValue.obj(
            "schemaVersion" to JsonValue.of(SCHEMA_VERSION),
            "generatedAt" to JsonValue.of(generatedAt),
            "writer" to JsonValue.of(writerDeviceId),
            "lists" to JsonValue.arr(canonical.lists.map(::encodeList)),
            "items" to JsonValue.arr(canonical.items.map(::encodeItem)),
        )
        return root.toJson(pretty = true)
    }

    fun decode(text: String): DecodedSyncDocument {
        val root = try {
            JsonValue.parse(text)
        } catch (e: JsonParseException) {
            throw SyncFormatException("Invalid JSON: ${e.message}", e)
        }
        if (root !is JsonValue.Obj) throw SyncFormatException("Document root must be a JSON object")
        val version = root.int("schemaVersion")
            ?: throw SyncFormatException("Missing schemaVersion")
        if (version < 1) throw SyncFormatException("Unsupported schemaVersion $version")
        if (version > SCHEMA_VERSION) {
            throw SyncFormatException(
                "Document uses schemaVersion $version but this build supports up to $SCHEMA_VERSION",
            )
        }
        val lists = root.arr("lists")?.items.orEmpty().mapIndexed { index, value ->
            decodeList(value) ?: throw SyncFormatException("Invalid list at index $index")
        }
        val items = root.arr("items")?.items.orEmpty().mapIndexed { index, value ->
            decodeItem(value) ?: throw SyncFormatException("Invalid item at index $index")
        }
        return DecodedSyncDocument(
            snapshot = SyncSnapshot(lists = lists, items = items).canonical(),
            schemaVersion = version,
            generatedAt = root.long("generatedAt") ?: 0L,
            writer = root.str("writer"),
        )
    }

    private fun encodeList(list: TodoList): JsonValue = JsonValue.obj(
        "id" to JsonValue.of(list.id),
        "name" to JsonValue.of(list.name),
        "color" to JsonValue.of(list.colorArgb and 0xFFFFFFFFL),
        "sortOrder" to JsonValue.of(list.sortOrder),
        "createdAt" to JsonValue.of(list.createdAt),
        "updatedAt" to JsonValue.of(list.updatedAt),
        "deletedAt" to JsonValue.of(list.deletedAt),
        "deviceId" to JsonValue.of(list.deviceId),
    )

    private fun encodeItem(item: TodoItem): JsonValue = JsonValue.obj(
        "id" to JsonValue.of(item.id),
        "listId" to JsonValue.of(item.listId),
        "title" to JsonValue.of(item.title),
        "notes" to JsonValue.of(item.notes),
        "priority" to JsonValue.of(item.priority.wireName),
        "dueAt" to JsonValue.of(item.dueAt),
        "remindAt" to JsonValue.of(item.remindAt),
        "channels" to JsonValue.of(item.reminderChannels),
        "pinned" to JsonValue.of(item.pinned),
        "done" to JsonValue.of(item.done),
        "doneAt" to JsonValue.of(item.doneAt),
        "createdAt" to JsonValue.of(item.createdAt),
        "updatedAt" to JsonValue.of(item.updatedAt),
        "deletedAt" to JsonValue.of(item.deletedAt),
        "deviceId" to JsonValue.of(item.deviceId),
    )

    private fun decodeList(value: JsonValue): TodoList? {
        if (value !is JsonValue.Obj) return null
        val id = value.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val updatedAt = value.long("updatedAt") ?: return null
        return TodoList(
            id = id,
            name = value.str("name").orEmpty(),
            colorArgb = (value.long("color") ?: DEFAULT_LIST_COLOR) and 0xFFFFFFFFL,
            sortOrder = value.int("sortOrder") ?: 0,
            updatedAt = updatedAt,
            deviceId = value.str("deviceId").orEmpty(),
            createdAt = value.long("createdAt") ?: updatedAt,
            deletedAt = value.long("deletedAt"),
        )
    }

    private fun decodeItem(value: JsonValue): TodoItem? {
        if (value !is JsonValue.Obj) return null
        val id = value.str("id")?.takeIf { it.isNotBlank() } ?: return null
        val updatedAt = value.long("updatedAt") ?: return null
        return TodoItem(
            id = id,
            listId = value.str("listId").orEmpty(),
            title = value.str("title").orEmpty(),
            notes = value.str("notes").orEmpty(),
            priority = Priority.fromWire(value.str("priority")),
            dueAt = value.long("dueAt"),
            remindAt = value.long("remindAt"),
            reminderChannels = ReminderChannels.normalize(value.int("channels") ?: ReminderChannels.NONE),
            pinned = value.bool("pinned") ?: false,
            done = value.bool("done") ?: false,
            doneAt = value.long("doneAt"),
            createdAt = value.long("createdAt") ?: updatedAt,
            updatedAt = updatedAt,
            deviceId = value.str("deviceId").orEmpty(),
            deletedAt = value.long("deletedAt"),
        )
    }

    private const val DEFAULT_LIST_COLOR = 0xFF4C5BD4L
}
