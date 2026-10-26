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
    /** Filled in only when the file is missing, to explain a likely branch mismatch. */
    val defaultBranch: String? = null,
)

/**
 * Two-way sync through a single JSON file in a git repository.
 *
 * Read → merge → write with optimistic concurrency. If another client wrote in
 * between, the host rejects the write (409 for GitHub/Gitea, 400 for GitLab/Gitee)
 * and the whole read/merge/write cycle is retried with the newer remote state, so a
 * concurrent commit never gets overwritten silently.
 */
class SyncEngine(
    private val store: GitContentStore,
    private val repository: TodoRepository,
    private val clock: WallClock,
    private val deviceId: String,
    private val maxAttempts: Int = 3,
) {

    suspend fun sync(rawConfig: SyncConfig, lastSyncAt: Long?): SyncReport {
        val config = rawConfig.normalized()
        config.validate()?.let { throw GitException(it, null, GitErrorKind.INVALID_CONFIG) }
        val local = repository.snapshot()
        var attempt = 0
        while (true) {
            attempt++
            val remoteFile = store.read(config)
            val remoteSnapshot = remoteFile?.content
                ?.takeIf { it.isNotBlank() }
                ?.let { decode(it).snapshot }
                ?: SyncSnapshot()
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
            } catch (e: GitException) {
                throw explainMissingBranch(config, e)
            } catch (e: CancellationException) {
                throw e
            }
        }
    }

    suspend fun testConnection(rawConfig: SyncConfig): ConnectionReport {
        val config = rawConfig.normalized()
        config.validate()?.let { throw GitException(it, null, GitErrorKind.INVALID_CONFIG) }
        val file = store.read(config)
        return ConnectionReport(
            fileExists = file != null,
            revision = file?.revision,
            defaultBranch = if (file == null) store.defaultBranch(config) else null,
        )
    }

    private fun decode(content: String): DecodedSyncDocument =
        try {
            SyncDocument.decode(content)
        } catch (e: Exception) {
            throw GitException("Remote file is not a valid Todo document: ${e.message}")
        }

    /**
     * A 404 on write usually means the configured branch does not exist (Gitee/GitHub
     * repos often default to `master`). Look up the real default branch and say so
     * instead of surfacing a bare "404 Not Found".
     */
    private suspend fun explainMissingBranch(config: SyncConfig, error: GitException): GitException {
        if (error.status != 404) return error
        val defaultBranch = store.defaultBranch(config)
        if (defaultBranch != null && defaultBranch != config.branch) {
            return GitException(
                "Branch \"${config.branch}\" not found. This repository's default branch is " +
                    "\"$defaultBranch\" — change the branch setting to \"$defaultBranch\".",
                error.status,
                GitErrorKind.NOT_FOUND,
            )
        }
        return error
    }

    private fun commitMessage(changes: Int, at: Long): String =
        "todo: sync $changes change(s) from $deviceId at $at"
}
