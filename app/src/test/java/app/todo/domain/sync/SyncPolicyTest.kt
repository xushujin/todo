package app.todo.domain.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto sync must feel automatic without turning every app switch into a request:
 * foreground syncs are throttled, local edits are debounced.
 */
class SyncPolicyTest {

    @Test
    fun theFirstForegroundSyncAlwaysRuns() {
        assertTrue(SyncPolicy.shouldSyncOnForeground(now = 1_000, lastAttemptAt = 0))
    }

    @Test
    fun switchingBackWithinAMinuteDoesNotSyncAgain() {
        assertFalse(SyncPolicy.shouldSyncOnForeground(now = 1_000 + 1, lastAttemptAt = 1_000))
        assertFalse(SyncPolicy.shouldSyncOnForeground(now = 1_000 + 59_999, lastAttemptAt = 1_000))
    }

    @Test
    fun theIntervalIsCountedFromTheLastAttemptNotTheLastSuccess() {
        // A failed attempt still counts: otherwise an offline device would retry on
        // every resume, which is exactly the battery drain this policy avoids.
        val lastAttempt = 5_000L
        assertFalse(SyncPolicy.shouldSyncOnForeground(now = lastAttempt + 30_000, lastAttemptAt = lastAttempt))
        assertTrue(SyncPolicy.shouldSyncOnForeground(now = lastAttempt + 60_000, lastAttemptAt = lastAttempt))
    }

    @Test
    fun localEditsAreBatchedForAShortQuietPeriod() {
        assertTrue(
            "debounce should be long enough to batch typing, short enough to feel instant",
            SyncPolicy.LOCAL_CHANGE_DEBOUNCE_MS in 1_000..10_000,
        )
    }
}
