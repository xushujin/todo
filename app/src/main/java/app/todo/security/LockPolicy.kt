package app.todo.security

/**
 * When the app should ask for biometrics again after being backgrounded.
 *
 * Kept as a pure function so the "grace period" behaviour is unit tested instead of
 * only living inside `MainActivity`'s lifecycle callbacks.
 */
object LockPolicy {

    /**
     * @param timeoutSeconds 0 = lock immediately, otherwise the grace period.
     * @param elapsedSinceBackgroundMs how long the app stayed in the background, or
     *   `null` when there is no baseline (first return after process start).
     */
    fun shouldLockOnReturn(timeoutSeconds: Int, elapsedSinceBackgroundMs: Long?): Boolean {
        if (elapsedSinceBackgroundMs == null) return true
        if (timeoutSeconds <= 0) return true
        return elapsedSinceBackgroundMs >= timeoutSeconds * 1_000L
    }
}
