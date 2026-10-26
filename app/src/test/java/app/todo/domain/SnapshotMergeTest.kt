package app.todo.domain

import app.todo.domain.model.Priority
import app.todo.domain.model.SyncSnapshot
import app.todo.domain.model.TodoItem
import app.todo.domain.model.TodoList
import app.todo.domain.sync.SnapshotMerge
import app.todo.domain.sync.Winner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SnapshotMergeTest {

    private fun item(
        id: String = "i1",
        updatedAt: Long = 1_000,
        deviceId: String = "dev-a",
        title: String = "task",
        done: Boolean = false,
        deletedAt: Long? = null,
    ) = TodoItem(
        id = id,
        listId = "inbox",
        title = title,
        notes = "",
        priority = Priority.NORMAL,
        dueAt = null,
        remindAt = null,
        reminderChannels = 0,
        pinned = false,
        done = done,
        doneAt = null,
        createdAt = 10,
        updatedAt = updatedAt,
        deviceId = deviceId,
        deletedAt = deletedAt,
    )

    private fun list(id: String = "inbox", updatedAt: Long = 1_000, name: String = "Inbox") = TodoList(
        id = id,
        name = name,
        colorArgb = 0xFF4C5BD4,
        sortOrder = 0,
        updatedAt = updatedAt,
        deviceId = "dev-a",
        createdAt = 10,
        deletedAt = null,
    )

    @Test
    fun takesRecordsThatExistOnOneSideOnly() {
        val local = SyncSnapshot(items = listOf(item(id = "local")))
        val remote = SyncSnapshot(items = listOf(item(id = "remote")))

        val result = SnapshotMerge.merge(local, remote, lastSyncAt = null)

        assertEquals(1, result.pulledCount)
        assertEquals(1, result.pushedCount)
        assertEquals(listOf("local", "remote"), result.merged.items.map { it.id })
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun newerTimestampWins() {
        val local = SyncSnapshot(items = listOf(item(title = "local", updatedAt = 2_000)))
        val remote = SyncSnapshot(items = listOf(item(title = "remote", updatedAt = 1_000)))

        val forward = SnapshotMerge.merge(local, remote, null)
        val backward = SnapshotMerge.merge(remote, local, null)

        assertEquals("local", forward.merged.items.single().title)
        assertEquals("local", backward.merged.items.single().title)
    }

    @Test
    fun equalTimestampsAreBrokenByDeviceIdDeterministically() {
        val deviceA = SyncSnapshot(items = listOf(item(title = "a", deviceId = "aaa", updatedAt = 500)))
        val deviceB = SyncSnapshot(items = listOf(item(title = "b", deviceId = "bbb", updatedAt = 500)))

        val ab = SnapshotMerge.merge(deviceA, deviceB, null)
        val ba = SnapshotMerge.merge(deviceB, deviceA, null)

        assertEquals("b", ab.merged.items.single().title)
        assertEquals("b", ba.merged.items.single().title)
    }

    @Test
    fun deletionIsJustAnUpdateAndCanWin() {
        val local = SyncSnapshot(items = listOf(item(title = "alive", updatedAt = 1_000)))
        val remote = SyncSnapshot(items = listOf(item(deletedAt = 2_000, updatedAt = 2_000)))

        val result = SnapshotMerge.merge(local, remote, null)

        assertEquals(2_000L, result.merged.items.single().deletedAt ?: -1L)
        assertTrue(result.merged.items.single().isDeleted)
    }

    @Test
    fun olderEditDoesNotResurrectDeletedTask() {
        val local = SyncSnapshot(items = listOf(item(title = "edited", updatedAt = 1_000)))
        val remote = SyncSnapshot(items = listOf(item(deletedAt = 1_500, updatedAt = 1_500)))

        val result = SnapshotMerge.merge(local, remote, null)

        assertEquals(1_500L, result.merged.items.single().updatedAt)
        assertTrue(result.merged.items.single().isDeleted)
    }

    @Test
    fun reportsConflictsOnlyWhenBothSidesChangedSinceLastSync() {
        val local = SyncSnapshot(items = listOf(item(title = "local", updatedAt = 3_000)))
        val remote = SyncSnapshot(items = listOf(item(title = "remote", updatedAt = 2_000)))

        val firstSync = SnapshotMerge.merge(local, remote, lastSyncAt = null)
        assertTrue("first sync must not report conflicts", firstSync.conflicts.isEmpty())

        val laterSync = SnapshotMerge.merge(local, remote, lastSyncAt = 1_000)
        assertEquals(1, laterSync.conflicts.size)
        assertEquals(Winner.LOCAL, laterSync.conflicts.single().winner)
    }

    @Test
    fun identicalRecordsAreNotCountedAsChanges() {
        val snapshot = SyncSnapshot(items = listOf(item()), lists = listOf(list()))
        val result = SnapshotMerge.merge(snapshot, snapshot, lastSyncAt = 100)

        assertEquals(0, result.pulledCount)
        assertEquals(0, result.pushedCount)
        assertTrue(result.conflicts.isEmpty())
    }

    @Test
    fun mergeIsIdempotentAndCommutative() {
        val local = SyncSnapshot(
            lists = listOf(list(updatedAt = 5_000, name = "Local")),
            items = listOf(item(title = "local", updatedAt = 4_000), item(id = "only-local", updatedAt = 1)),
        )
        val remote = SyncSnapshot(
            lists = listOf(list(updatedAt = 6_000, name = "Remote")),
            items = listOf(item(title = "remote", updatedAt = 3_000), item(id = "only-remote", updatedAt = 1)),
        )

        val once = SnapshotMerge.merge(local, remote, lastSyncAt = 100).merged
        val twice = SnapshotMerge.merge(once, once, lastSyncAt = 100)
        assertEquals(once, twice.merged)
        assertEquals(0, twice.pushedCount)
        assertEquals(0, twice.pulledCount)

        val forward = SnapshotMerge.merge(local, remote, null).merged
        val backward = SnapshotMerge.merge(remote, local, null).merged
        assertEquals(forward, backward)
    }

    @Test
    fun mergedSnapshotIsCanonicallyOrdered() {
        val local = SyncSnapshot(items = listOf(item(id = "b"), item(id = "a")))
        val remote = SyncSnapshot(items = listOf(item(id = "d"), item(id = "c")))

        val merged = SnapshotMerge.merge(local, remote, null).merged

        assertEquals(listOf("a", "b", "c", "d"), merged.items.map { it.id })
    }

    @Test
    fun nullDeletedAtMeansAlive() {
        val result = SnapshotMerge.merge(
            SyncSnapshot(items = listOf(item(updatedAt = 10))),
            SyncSnapshot(),
            null,
        )
        assertNull(result.merged.items.single().deletedAt)
    }
}
