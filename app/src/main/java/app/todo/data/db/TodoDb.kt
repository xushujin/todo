package app.todo.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.todo.R

/**
 * Explicit, file-based schema migrations.
 *
 * Every `schema/NNNN_*.sql` resource is one schema version. A fresh database runs
 * all of them in order, an upgraded database runs only the ones it has not seen —
 * so there is a single definition of the final shape and no "create table" that
 * drifts away from the "alter table" path.
 *
 * There is no destructive fallback: [TodoDb.onDowngrade] throws instead of
 * recreating the database, and no migration file contains DROP/DELETE statements
 * (enforced by tools/verify_schema.py).
 */
object Schema {

    private val files: List<String> = listOf(
        "0001_init.sql",
        "0002_pinned.sql",
    )

    data class Migration(val version: Int, val file: String)

    val migrations: List<Migration> = files
        .map { file ->
            val version = file.substringBefore('_').toIntOrNull()
                ?: error("Migration file '$file' must start with a version number")
            Migration(version, file)
        }
        .sortedBy { it.version }

    val version: Int = migrations.lastOrNull()?.version ?: 0

    init {
        val versions = migrations.map { it.version }
        check(versions == versions.distinct().sorted()) { "Migration versions must be unique and sorted: $versions" }
        check(versions.firstOrNull() == 1) { "Migration chain must start at version 1, got $versions" }
    }

    /** SQL statements needed to move a database from [fromVersion] to [version]. */
    fun statementsFor(fromVersion: Int): List<String> =
        migrations
            .filter { it.version > fromVersion }
            .flatMap { statements(it.file) }

    fun statements(file: String): List<String> {
        val raw = readResource("/schema/$file")
        return raw
            .lineSequence()
            .filterNot { it.trimStart().startsWith("--") }
            .joinToString("\n")
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private fun readResource(path: String): String =
        Schema::class.java.getResourceAsStream(path)?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: error("Missing schema resource $path")
}

class TodoDb(private val context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    Schema.version,
) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        Schema.statementsFor(0).forEach(db::execSQL)
        seed(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        check(newVersion > oldVersion) { "onUpgrade called with $oldVersion -> $newVersion" }
        Schema.statementsFor(oldVersion).forEach(db::execSQL)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException(
            "Database v$oldVersion is newer than this build (v$newVersion). " +
                "Install the newer app version instead of downgrading.",
        )
    }

    private fun seed(db: SQLiteDatabase) {
        val now = System.currentTimeMillis()
        db.execSQL(
            "INSERT OR IGNORE INTO todo_lists (id, name, color_argb, sort_order, created_at, updated_at, deleted_at, device_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, NULL, ?)",
            arrayOf<Any>(INBOX_LIST_ID, context.getString(R.string.lists_inbox), DEFAULT_LIST_COLOR, 0, now, now, "seed"),
        )
    }

    companion object {
        const val DATABASE_NAME = "todo.db"
        const val INBOX_LIST_ID = "inbox"
        const val DEFAULT_LIST_COLOR = 0xFF4C5BD4L
    }
}
