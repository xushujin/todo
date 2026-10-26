package app.todo.data.db

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import app.todo.domain.model.Priority
import app.todo.domain.model.SyncSnapshot
import app.todo.domain.model.TodoItem
import app.todo.domain.model.TodoList

/** Thin, synchronous SQLite mapper. Coroutine dispatching lives in the repository. */
class TodoDao(private val db: TodoDb) {

    fun lists(includeDeleted: Boolean = false): List<TodoList> = readable { db ->
        val where = if (includeDeleted) null else "deleted_at IS NULL"
        db.query("todo_lists", null, where, null, null, null, "sort_order ASC, name ASC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toList())
            }
        }
    }

    fun items(includeDeleted: Boolean = false): List<TodoItem> = readable { db ->
        val where = if (includeDeleted) null else "deleted_at IS NULL"
        db.query("todo_items", null, where, null, null, null, "created_at DESC").use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toItem())
            }
        }
    }

    fun item(id: String): TodoItem? = readable { db ->
        db.query("todo_items", null, "id = ?", arrayOf(id), null, null, null).use { cursor ->
            if (cursor.moveToNext()) cursor.toItem() else null
        }
    }

    fun upcomingReminders(now: Long): List<TodoItem> = readable { db ->
        db.query(
            "todo_items",
            null,
            "deleted_at IS NULL AND done = 0 AND remind_at IS NOT NULL AND remind_at > ?",
            arrayOf(now.toString()),
            null,
            null,
            "remind_at ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toItem())
            }
        }
    }

    fun upsertList(list: TodoList) = writable { db ->
        db.insertWithOnConflict("todo_lists", null, list.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun upsertItem(item: TodoItem) = writable { db ->
        db.insertWithOnConflict("todo_items", null, item.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun replaceAll(snapshot: SyncSnapshot) = writable { db ->
        db.beginTransaction()
        try {
            snapshot.lists.forEach { list ->
                db.insertWithOnConflict("todo_lists", null, list.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
            snapshot.items.forEach { item ->
                db.insertWithOnConflict("todo_items", null, item.toValues(), SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun purgeDeletedBefore(cutoff: Long): Int = writable { db ->
        val items = db.delete("todo_items", "deleted_at IS NOT NULL AND deleted_at < ?", arrayOf(cutoff.toString()))
        val lists = db.delete("todo_lists", "deleted_at IS NOT NULL AND deleted_at < ?", arrayOf(cutoff.toString()))
        items + lists
    }

    private fun <T> readable(block: (SQLiteDatabase) -> T): T = block(db.readableDatabase)

    private fun <T> writable(block: (SQLiteDatabase) -> T): T = block(db.writableDatabase)

    private fun Cursor.toList(): TodoList = TodoList(
        id = string("id"),
        name = string("name"),
        colorArgb = long("color_argb"),
        sortOrder = int("sort_order"),
        updatedAt = long("updated_at"),
        deviceId = string("device_id"),
        createdAt = long("created_at"),
        deletedAt = nullableLong("deleted_at"),
    )

    private fun Cursor.toItem(): TodoItem = TodoItem(
        id = string("id"),
        listId = string("list_id"),
        title = string("title"),
        notes = string("notes"),
        priority = Priority.fromWeight(int("priority")),
        dueAt = nullableLong("due_at"),
        remindAt = nullableLong("remind_at"),
        reminderChannels = int("reminder_channels"),
        pinned = int("pinned") != 0,
        done = int("done") != 0,
        doneAt = nullableLong("done_at"),
        createdAt = long("created_at"),
        updatedAt = long("updated_at"),
        deviceId = string("device_id"),
        deletedAt = nullableLong("deleted_at"),
    )

    private fun TodoList.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("name", name)
        put("color_argb", colorArgb)
        put("sort_order", sortOrder)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("deleted_at", deletedAt)
        put("device_id", deviceId)
    }

    private fun TodoItem.toValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("list_id", listId)
        put("title", title)
        put("notes", notes)
        put("priority", priority.weight)
        put("due_at", dueAt)
        put("remind_at", remindAt)
        put("reminder_channels", reminderChannels)
        put("pinned", if (pinned) 1 else 0)
        put("done", if (done) 1 else 0)
        put("done_at", doneAt)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("deleted_at", deletedAt)
        put("device_id", deviceId)
    }

    private fun Cursor.column(name: String): Int = getColumnIndexOrThrow(name)

    private fun Cursor.string(name: String): String = getString(column(name))

    private fun Cursor.long(name: String): Long = getLong(column(name))

    private fun Cursor.int(name: String): Int = getInt(column(name))

    private fun Cursor.nullableLong(name: String): Long? {
        val index = column(name)
        return if (isNull(index)) null else getLong(index)
    }
}
