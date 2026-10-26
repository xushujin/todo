package app.todo.data.git

import app.todo.core.time.WallClock
import app.todo.domain.model.SyncSnapshot
import app.todo.domain.repo.TodoRepository
import app.todo.domain.sync.DecodedSyncDocument
import app.todo.domain.sync.SnapshotMerge
import app.todo.domain.sync.SyncDocument
import kotlinx.coroutines.CancellationException

data class SyncReport(
    val pulled: Int,
    val pushed: Int,
    val conflicts: Int,
    val revision: String,
    val syncedAt: Long,
    val pushedToRemote: Boolean,
)

data class ConnectionReport(
    val fileExists: Boolean,
    val revision: String?,
)

/**
 * Two-way sync through a single JSON file in a git repository.
 *
 * Read → merge → write with optimistic concurrency. If another client wrote in
 * between, the host rejects the write (409 for GitHub/Gitea, 400 for GitLab) and
 * the whole read/merge/write cycle is retried with the newer remote state, so a
 * concurrent commit never gets overwritten silently.
 */
class SyncEngine(
    private val store: GitContentStore,
    private val repository: TodoRepository,
    private val clock: WallClock,
    private val deviceId: String,
    private val maxAttempts: Int = 3,
) {

    suspend fun sync(config: SyncConfig, lastSyncAt: Long?): SyncReport {
        config.validate()?.let { throw GitException(it) }
        val local = repository.snapshot()
        var attempt = 0
        while (true) {
            attempt++
            val remoteFile = store.read(config)
            val remoteSnapshot = remoteFile?.let { decode(it.content).snapshot } ?: SyncSnapshot()
            val merge = SnapshotMerge.merge(local, remoteSnapshot, lastSyncAt)
            val syncedAt = clock.nowMillis()
            val alreadySynced = merge.merged == remoteSnapshot

            if (alreadySynced) {
                repository.applySnapshot(merge.merged)
                return SyncReport(
                    pulled = merge.pulledCount,
                    pushed = 0,
                    conflicts = merge.conflicts.size,
                    revision = remoteFile?.revision.orEmpty(),
                    syncedAt = syncedAt,
                    pushedToRemote = false,
                )
            }

            val payload = SyncDocument.encode(merge.merged, deviceId, syncedAt)
            try {
                val written = store.write(
                    config = config,
                    content = payload,
                    expectedRevision = remoteFile?.revision,
                    message = commitMessage(merge.pushedCount, syncedAt),
                )
                repository.applySnapshot(merge.merged)
                return SyncReport(
                    pulled = merge.pulledCount,
                    pushed = merge.pushedCount,
                    conflicts = merge.conflicts.size,
                    revision = written.revision,
                    syncedAt = syncedAt,
                    pushedToRemote = true,
                )
            } catch (e: GitConflictException) {
                if (attempt >= maxAttempts) throw e
                // Fall through: the loop re-reads the newer remote state and merges again.
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    suspend fun testConnection(config: SyncConfig): ConnectionReport {
        config.validate()?.let { throw GitException(it) }
        val file = store.read(config)
        return ConnectionReport(fileExists = file != null, revision = file?.revision)
    }

    private fun decode(content: String): DecodedSyncDocument =
        try {
            SyncDocument.decode(content)
        } catch (e: Exception) {
            throw GitException("Remote file is not a valid Todo document: ${e.message}")
        }

    private fun commitMessage(changes: Int, at: Long): String =
        "todo: sync $changes change(s) from $deviceId at $at"
}
