package app.todo.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The lock grace period used to be "always lock on return", which made every quick
 * app switch throw away the user's unsaved draft. These tests pin the new rule.
 */
class LockPolicyTest {

    @Test
    fun locksImmediatelyWhenTimeoutIsZero() {
        assertTrue(LockPolicy.shouldLockOnReturn(timeoutSeconds = 0, elapsedSinceBackgroundMs = 0))
        assertTrue(LockPolicy.shouldLockOnReturn(timeoutSeconds = 0, elapsedSinceBackgroundMs = 1))
    }

    @Test
    fun keepsTheSessionInsideTheGracePeriod() {
        assertFalse(LockPolicy.shouldLockOnReturn(timeoutSeconds = 60, elapsedSinceBackgroundMs = 0))
        assertFalse(LockPolicy.shouldLockOnReturn(timeoutSeconds = 60, elapsedSinceBackgroundMs = 59_999))
        assertFalse(LockPolicy.shouldLockOnReturn(timeoutSeconds = 30, elapsedSinceBackgroundMs = 29_000))
    }

    @Test
    fun locksOnceTheGracePeriodIsReached() {
        assertTrue(LockPolicy.shouldLockOnReturn(timeoutSeconds = 60, elapsedSinceBackgroundMs = 60_000))
        assertTrue(LockPolicy.shouldLockOnReturn(timeoutSeconds = 60, elapsedSinceBackgroundMs = 600_000))
    }

    @Test
    fun locksWhenThereIsNoBackgroundBaseline() {
        // First return after process start: no timestamp to compare against.
        assertTrue(LockPolicy.shouldLockOnReturn(timeoutSeconds = 900, elapsedSinceBackgroundMs = null))
    }

    @Test
    fun defaultSettingIsOneMinute() {
        assertEquals(60, app.todo.data.prefs.SettingsStore.DEFAULT_LOCK_TIMEOUT_SECONDS)
    }
}
