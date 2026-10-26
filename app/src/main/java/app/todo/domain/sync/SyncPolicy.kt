package app.todo.domain.sync

/**
 * When automatic sync is allowed to run.
 *
 * Kept as pure functions so the timing rules are unit tested instead of being buried
 * in the view model's coroutine code:
 *
 *  - opening the app should pull remote changes, but not hammer the server when the
 *    user switches back and forth between apps;
 *  - a local edit should always be pushed, but only after a short quiet period so
 *    typing a title does not fire one request per keystroke.
 */
object SyncPolicy {

    /** Foreground syncs are skipped when the last attempt was this recent. */
    const val FOREGROUND_MIN_INTERVAL_MS: Long = 60_000L

    /** Local edits are batched for this long before syncing. */
    const val LOCAL_CHANGE_DEBOUNCE_MS: Long = 3_000L

    fun shouldSyncOnForeground(now: Long, lastAttemptAt: Long): Boolean {
        if (lastAttemptAt <= 0L) return true
        return now - lastAttemptAt >= FOREGROUND_MIN_INTERVAL_MS
    }
}
