package app.todo.domain.repo

import app.todo.core.ids.IdGenerator
import app.todo.core.time.WallClock
import app.todo.data.db.TodoDao
import app.todo.data.db.TodoDb
import app.todo.domain.model.Priority
import app.todo.domain.model.ReminderChannels
import app.todo.domain.model.SyncSnapshot
import app.todo.domain.model.TodoItem
import app.todo.domain.model.TodoList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Everything the UI may change about a task. Timestamps, ids and tombstones are
 * owned by the repository so that "create", "edit", "complete" and "delete" all
 * produce sync-compatible records through one code path.
 */
data class TodoDraft(
    val id: String? = null,
    val listId: String,
    val title: String,
    val notes: String = "",
    val priority: Priority = Priority.NORMAL,
    val dueAt: Long? = null,
    val remindAt: Long? = null,
    val reminderChannels: Int = ReminderChannels.NONE,
    val pinned: Boolean = false,
    val done: Boolean = false,
)

interface TodoRepository {
    fun observeLists(): Flow<List<TodoList>>
    fun observeItems(): Flow<List<TodoItem>>
    suspend fun item(id: String): TodoItem?
    suspend fun list(id: String): TodoList?
    suspend fun snapshot(): SyncSnapshot
    suspend fun saveItem(draft: TodoDraft): TodoItem
    suspend fun setDone(id: String, done: Boolean): TodoItem?
    suspend fun deleteItem(id: String)
    suspend fun saveList(id: String?, name: String, colorArgb: Long, sortOrder: Int = 0): TodoList
    suspend fun deleteList(id: String)
    suspend fun applySnapshot(snapshot: SyncSnapshot)
    suspend fun upcomingReminders(): List<TodoItem>
    suspend fun purgeDeleted(cutoff: Long): Int
    suspend fun ensureInbox(defaultName: String)
}

class SqliteTodoRepository(
    private val dao: TodoDao,
    private val clock: WallClock,
    private val ids: IdGenerator,
    private val deviceId: String,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : TodoRepository {

    private val revision = MutableStateFlow(0L)

    override fun observeLists(): Flow<List<TodoList>> =
        revision.map { dao.lists() }.flowOn(io).conflate()

    override fun observeItems(): Flow<List<TodoItem>> =
        revision.map { dao.items() }.flowOn(io).conflate()

    override suspend fun item(id: String): TodoItem? = withContext(io) { dao.item(id) }

    override suspend fun list(id: String): TodoList? = withContext(io) {
        dao.lists(includeDeleted = true).firstOrNull { it.id == id }
    }

    override suspend fun snapshot(): SyncSnapshot = withContext(io) {
        SyncSnapshot(lists = dao.lists(includeDeleted = true), items = dao.items(includeDeleted = true))
    }

    override suspend fun saveItem(draft: TodoDraft): TodoItem = withContext(io) {
        val now = clock.nowMillis()
        val existing = draft.id?.let { dao.item(it) }
        val item = if (existing == null) {
            TodoItem(
                id = draft.id ?: ids.newId(),
                listId = draft.listId,
                title = draft.title.trim(),
                notes = draft.notes,
                priority = draft.priority,
                dueAt = draft.dueAt,
                remindAt = draft.remindAt,
                reminderChannels = ReminderChannels.normalize(draft.reminderChannels),
                pinned = draft.pinned,
                done = draft.done,
                doneAt = if (draft.done) now else null,
                createdAt = now,
                updatedAt = now,
                deviceId = deviceId,
                deletedAt = null,
            )
        } else {
            existing.copy(
                listId = draft.listId,
                title = draft.title.trim(),
                notes = draft.notes,
                priority = draft.priority,
                dueAt = draft.dueAt,
                remindAt = draft.remindAt,
                reminderChannels = ReminderChannels.normalize(draft.reminderChannels),
                pinned = draft.pinned,
                done = draft.done,
                doneAt = when {
                    draft.done && !existing.done -> now
                    draft.done -> existing.doneAt
                    else -> null
                },
                updatedAt = now,
                deviceId = deviceId,
            )
        }
        dao.upsertItem(item)
        revision.update { it + 1 }
        item
    }

    override suspend fun setDone(id: String, done: Boolean): TodoItem? = withContext(io) {
        val existing = dao.item(id) ?: return@withContext null
        val now = clock.nowMillis()
        val updated = existing.copy(
            done = done,
            doneAt = if (done) now else null,
            updatedAt = now,
            deviceId = deviceId,
        )
        dao.upsertItem(updated)
        revision.update { it + 1 }
        updated
    }

    override suspend fun deleteItem(id: String) = withContext(io) {
        val existing = dao.item(id) ?: return@withContext
        val now = clock.nowMillis()
        dao.upsertItem(existing.copy(deletedAt = now, updatedAt = now, deviceId = deviceId))
        revision.update { it + 1 }
    }

    override suspend fun saveList(
        id: String?,
        name: String,
        colorArgb: Long,
        sortOrder: Int,
    ): TodoList = withContext(io) {
        val now = clock.nowMillis()
        val existing = id?.let { listId -> dao.lists(includeDeleted = true).firstOrNull { it.id == listId } }
        val list = existing?.copy(
            name = name.trim(),
            colorArgb = colorArgb,
            sortOrder = sortOrder,
            updatedAt = now,
            deviceId = deviceId,
        ) ?: TodoList(
            id = id ?: ids.newId(),
            name = name.trim(),
            colorArgb = colorArgb,
            sortOrder = sortOrder,
            updatedAt = now,
            deviceId = deviceId,
            createdAt = now,
        )
        dao.upsertList(list)
        revision.update { it + 1 }
        list
    }

    override suspend fun deleteList(id: String) = withContext(io) {
        val now = clock.nowMillis()
        val list = dao.lists(includeDeleted = true).firstOrNull { it.id == id } ?: return@withContext
        dao.upsertList(list.copy(deletedAt = now, updatedAt = now, deviceId = deviceId))
        dao.items(includeDeleted = true)
            .filter { it.listId == id && it.deletedAt == null }
            .forEach { item ->
                dao.upsertItem(item.copy(deletedAt = now, updatedAt = now, deviceId = deviceId))
            }
        revision.update { it + 1 }
    }

    override suspend fun applySnapshot(snapshot: SyncSnapshot) = withContext(io) {
        dao.replaceAll(snapshot)
        revision.update { it + 1 }
    }

    override suspend fun upcomingReminders(): List<TodoItem> = withContext(io) {
        dao.upcomingReminders(clock.nowMillis())
    }

    override suspend fun purgeDeleted(cutoff: Long): Int = withContext(io) {
        val removed = dao.purgeDeletedBefore(cutoff)
        revision.update { it + 1 }
        removed
    }

    override suspend fun ensureInbox(defaultName: String) = withContext(io) {
        if (dao.lists().isEmpty()) {
            val now = clock.nowMillis()
            dao.upsertList(
                TodoList(
                    id = TodoDb.INBOX_LIST_ID,
                    name = defaultName,
                    colorArgb = TodoDb.DEFAULT_LIST_COLOR,
                    sortOrder = 0,
                    updatedAt = now,
                    deviceId = deviceId,
                    createdAt = now,
                ),
            )
            revision.update { it + 1 }
        }
    }
}
