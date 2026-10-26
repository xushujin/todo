package app.todo.data

import app.todo.core.time.WallClock
import app.todo.data.git.GitConflictException
import app.todo.data.git.GitContentStore
import app.todo.data.git.GitException
import app.todo.data.git.GitProviderKind
import app.todo.data.git.RemoteFile
import app.todo.data.git.SyncConfig
import app.todo.data.git.SyncEngine
import app.todo.domain.model.Priority
import app.todo.domain.model.SyncSnapshot
import app.todo.domain.model.TodoItem
import app.todo.domain.model.TodoList
import app.todo.domain.repo.TodoDraft
import app.todo.domain.repo.TodoRepository
import app.todo.domain.sync.SyncDocument
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncEngineTest {

    private val config = SyncConfig(
        provider = GitProviderKind.GITHUB,
        baseUrl = "https://api.github.com",
        owner = "hatim",
        repo = "todo",
        branch = "main",
        path = "data/todos.json",
        token = "tok",
    )

    private fun item(id: String, updatedAt: Long, title: String = id) = TodoItem(
        id = id,
        listId = "inbox",
        title = title,
        notes = "",
        priority = Priority.NORMAL,
        dueAt = null,
        remindAt = null,
        reminderChannels = 0,
        pinned = false,
        done = false,
        doneAt = null,
        createdAt = 100,
        updatedAt = updatedAt,
        deviceId = "dev-a",
        deletedAt = null,
    )

    private val inbox = TodoList(
        id = "inbox",
        name = "Inbox",
        colorArgb = 0xFF4C5BD4,
        sortOrder = 0,
        updatedAt = 100,
        deviceId = "dev-a",
        createdAt = 100,
        deletedAt = null,
    )

    private class FakeRepository(initial: SyncSnapshot = SyncSnapshot()) : TodoRepository {
        var current: SyncSnapshot = initial
        override fun observeLists(): Flow<List<TodoList>> = MutableStateFlow(current.lists)
        override fun observeItems(): Flow<List<TodoItem>> = MutableStateFlow(current.items)
        override suspend fun item(id: String): TodoItem? = current.items.firstOrNull { it.id == id }
        override suspend fun list(id: String): TodoList? = current.lists.firstOrNull { it.id == id }
        override suspend fun snapshot(): SyncSnapshot = current
        override suspend fun saveItem(draft: TodoDraft): TodoItem = error("not used")
        override suspend fun setDone(id: String, done: Boolean): TodoItem? = error("not used")
        override suspend fun deleteItem(id: String) = Unit
        override suspend fun saveList(id: String?, name: String, colorArgb: Long, sortOrder: Int): TodoList =
            error("not used")
        override suspend fun deleteList(id: String) = Unit
        override suspend fun applySnapshot(snapshot: SyncSnapshot) {
            current = snapshot
        }
        override suspend fun upcomingReminders(): List<TodoItem> = emptyList()
        override suspend fun purgeDeleted(cutoff: Long): Int = 0
        override suspend fun ensureInbox() = Unit
    }

    private class FakeStore(var content: String? = null, var revision: String? = null) : GitContentStore {
        var writes = 0
        var attempts = 0
        var conflictOnceWith: String? = null
        var lastExpectedRevision: String? = null

        override suspend fun read(config: SyncConfig): RemoteFile? =
            content?.let { RemoteFile(it, revision ?: "rev-0") }

        override suspend fun write(
            config: SyncConfig,
            content: String,
            expectedRevision: String?,
            message: String,
        ): RemoteFile {
            attempts++
            lastExpectedRevision = expectedRevision
            val conflict = conflictOnceWith
            if (conflict != null) {
                conflictOnceWith = null
                this.content = conflict
                this.revision = "rev-conflict"
                throw GitConflictException("stale revision")
            }
            writes++
            this.content = content
            revision = "rev-$writes"
            return RemoteFile(content, revision.orEmpty())
        }
    }

    private fun engine(repository: TodoRepository, store: GitContentStore) = SyncEngine(
        store = store,
        repository = repository,
        clock = WallClock { 5_000 },
        deviceId = "dev-a",
    )

    @Test
    fun pushesLocalChangesWhenRemoteFileDoesNotExist() = runBlocking {
        val repository = FakeRepository(SyncSnapshot(items = listOf(item("a", 1_000))))
        val store = FakeStore()

        val report = engine(repository, store).sync(config, lastSyncAt = null)

        assertEquals(1, report.pushed)
        assertEquals(0, report.pulled)
        assertTrue(report.pushedToRemote)
        assertEquals("rev-1", report.revision)
        val remote = SyncDocument.decode(store.content.orEmpty())
        assertEquals(listOf("a"), remote.snapshot.items.map { it.id })
        assertEquals(repository.current, remote.snapshot)
        assertEquals(5_000L, report.syncedAt)
    }

    @Test
    fun pullsRemoteChangesAndWritesNothing() = runBlocking {
        val remoteDoc = SyncDocument.encode(
            SyncSnapshot(items = listOf(item("r", 2_000))),
            writerDeviceId = "dev-b",
            generatedAt = 100,
        )
        val repository = FakeRepository()
        val store = FakeStore(remoteDoc, "rev-1")

        val report = engine(repository, store).sync(config, lastSyncAt = null)

        assertEquals(1, report.pulled)
        assertEquals(0, report.pushed)
        assertEquals(0, store.writes)
        assertFalse(report.pushedToRemote)
        assertEquals(listOf("r"), repository.current.items.map { it.id })
    }

    @Test
    fun doesNothingWhenBothSidesAlreadyMatch() = runBlocking {
        val snapshot = SyncSnapshot(items = listOf(item("a", 1_000)))
        val remoteDoc = SyncDocument.encode(snapshot, "dev-a", 999)
        val repository = FakeRepository(snapshot)
        val store = FakeStore(remoteDoc, "rev-1")

        val report = engine(repository, store).sync(config, lastSyncAt = 900)

        assertEquals(0, store.writes)
        assertFalse(report.pushedToRemote)
        assertEquals(0, report.conflicts)
    }

    @Test
    fun retriesAndMergesWhenAnotherClientWroteFirst() = runBlocking {
        val local = SyncSnapshot(
            items = listOf(item("a", 2_000, title = "local"), item("local-only", 500)),
        )
        val initialRemote = SyncDocument.encode(
            SyncSnapshot(items = listOf(item("a", 1_000, title = "remote"))),
            "dev-b",
            100,
        )
        val newerRemote = SyncDocument.encode(
            SyncSnapshot(items = listOf(item("a", 3_000, title = "remote-new"))),
            "dev-b",
            200,
        )
        val repository = FakeRepository(local)
        val store = FakeStore(initialRemote, "rev-1").apply { conflictOnceWith = newerRemote }

        val report = engine(repository, store).sync(config, lastSyncAt = null)

        // First write is rejected, the retry re-reads, re-merges and writes again.
        assertEquals(2, store.attempts)
        assertEquals(1, store.writes)
        assertTrue(report.pushedToRemote)
        assertEquals(1, report.pulled)
        assertEquals(1, report.pushed)
        assertEquals("remote-new", repository.current.items.first { it.id == "a" }.title)
        assertTrue(repository.current.items.any { it.id == "local-only" })
    }

    @Test
    fun givesUpAfterRepeatedConflicts() = runBlocking {
        val repository = FakeRepository(SyncSnapshot(items = listOf(item("a", 2_000))))
        val store = object : GitContentStore {
            override suspend fun read(config: SyncConfig): RemoteFile? =
                RemoteFile(SyncDocument.encode(SyncSnapshot(), "dev-b", 1), "rev")
            override suspend fun write(
                config: SyncConfig,
                content: String,
                expectedRevision: String?,
                message: String,
            ): RemoteFile = throw GitConflictException("always stale")
        }
        assertThrows(GitConflictException::class.java) {
            runBlocking { engine(repository, store).sync(config, null) }
        }
        Unit
    }

    @Test
    fun rejectsDocumentsFromAFutureSchemaVersion() = runBlocking {
        val repository = FakeRepository()
        val store = FakeStore("""{"schemaVersion":9,"items":[]}""", "rev-1")

        val error = assertThrows(GitException::class.java) {
            runBlocking { engine(repository, store).sync(config, null) }
        }
        assertTrue(error.message.orEmpty().contains("schemaVersion"))
    }

    @Test
    fun refusesToRunWithAnInvalidConfiguration() = runBlocking {
        val repository = FakeRepository()
        val store = FakeStore()
        val error = assertThrows(GitException::class.java) {
            runBlocking { engine(repository, store).sync(config.copy(token = ""), null) }
        }
        assertTrue(error.message.orEmpty().contains("token"))
        assertEquals(0, store.attempts)
    }

    @Test
    fun testConnectionReportsWhetherTheFileExists() = runBlocking {
        val repository = FakeRepository()
        val store = FakeStore("""{"schemaVersion":1,"items":[]}""", "rev-9")

        val report = engine(repository, store).testConnection(config)

        assertTrue(report.fileExists)
        assertEquals("rev-9", report.revision)
    }
}
