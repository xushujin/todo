package app.todo.domain.sync

import app.todo.domain.model.SyncRecord
import app.todo.domain.model.SyncSnapshot
import app.todo.domain.model.TodoItem
import app.todo.domain.model.TodoList

enum class RecordKind { LIST, ITEM }

enum class Winner { LOCAL, REMOTE }

data class MergeConflict(val id: String, val kind: RecordKind, val winner: Winner)

data class MergeResult(
    val merged: SyncSnapshot,
    /** Records where the remote version won and differed from the local one. */
    val pulledCount: Int,
    /** Records where the local version won and differed from the remote one. */
    val pushedCount: Int,
    val conflicts: List<MergeConflict>,
)

/**
 * Last-writer-wins merge over the whole snapshot.
 *
 * Rules (documented in docs/SYNC.md, verified by MergeTest):
 *  - a record that exists on one side only is taken as-is;
 *  - when both sides have it, the higher [SyncRecord.updatedAt] wins;
 *  - equal timestamps are broken by [SyncRecord.deviceId] (stable, deterministic);
 *  - deletions are ordinary updates (the tombstone carries a newer updatedAt), so a
 *    delete on one device beats an older edit on the other, and vice versa;
 *  - a conflict is reported when both sides changed the payload after the last
 *    successful sync; the winner is still decided by the same rule, so merge is
 *    commutative and idempotent.
 */
object SnapshotMerge {

    fun merge(
        local: SyncSnapshot,
        remote: SyncSnapshot,
        lastSyncAt: Long?,
    ): MergeResult {
        val lists = mergeRecords(
            local = local.lists,
            remote = remote.lists,
            lastSyncAt = lastSyncAt,
            kind = RecordKind.LIST,
            sameContent = TodoList::sameContentAs,
        )
        val items = mergeRecords(
            local = local.items,
            remote = remote.items,
            lastSyncAt = lastSyncAt,
            kind = RecordKind.ITEM,
            sameContent = TodoItem::sameContentAs,
        )
        return MergeResult(
            merged = SyncSnapshot(lists = lists.records, items = items.records).canonical(),
            pulledCount = lists.pulled + items.pulled,
            pushedCount = lists.pushed + items.pushed,
            conflicts = lists.conflicts + items.conflicts,
        )
    }

    private class Records<T>(
        val records: List<T>,
        val pulled: Int,
        val pushed: Int,
        val conflicts: List<MergeConflict>,
    )

    private fun <T : SyncRecord> mergeRecords(
        local: List<T>,
        remote: List<T>,
        lastSyncAt: Long?,
        kind: RecordKind,
        sameContent: (T, T) -> Boolean,
    ): Records<T> {
        val localById = local.associateBy { it.id }
        val remoteById = remote.associateBy { it.id }
        val merged = ArrayList<T>(localById.size + remoteById.size)
        val conflicts = ArrayList<MergeConflict>()
        var pulled = 0
        var pushed = 0

        for (id in localById.keys + remoteById.keys) {
            val localRecord = localById[id]
            val remoteRecord = remoteById[id]
            when {
                localRecord == null && remoteRecord != null -> {
                    merged += remoteRecord
                    pulled++
                }
                localRecord != null && remoteRecord == null -> {
                    merged += localRecord
                    pushed++
                }
                localRecord != null && remoteRecord != null -> {
                    val winnerIsLocal = localRecord.winsAgainst(remoteRecord)
                    if (sameContent(localRecord, remoteRecord)) {
                        merged += if (winnerIsLocal) localRecord else remoteRecord
                    } else {
                        if (winnerIsLocal) {
                            merged += localRecord
                            pushed++
                        } else {
                            merged += remoteRecord
                            pulled++
                        }
                        val bothChangedSinceSync = lastSyncAt != null &&
                            localRecord.updatedAt > lastSyncAt &&
                            remoteRecord.updatedAt > lastSyncAt
                        if (bothChangedSinceSync) {
                            conflicts += MergeConflict(
                                id = id,
                                kind = kind,
                                winner = if (winnerIsLocal) Winner.LOCAL else Winner.REMOTE,
                            )
                        }
                    }
                }
            }
        }
        return Records(merged, pulled, pushed, conflicts)
    }

    /** Higher updatedAt wins; ties are broken by deviceId so both sides agree. */
    private fun SyncRecord.winsAgainst(other: SyncRecord): Boolean {
        if (updatedAt != other.updatedAt) return updatedAt > other.updatedAt
        return deviceId >= other.deviceId
    }
}
