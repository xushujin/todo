package app.todo.core.platform

/**
 * Permission and settings action names that only exist from a certain API level.
 *
 * Referencing `Manifest.permission.POST_NOTIFICATIONS` (API 33) or
 * `Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM` (API 31) from a minSdk 30 app is
 * safe at runtime — both are compile-time constants that get inlined — but lint
 * flags the field reference as `InlinedApi`. Using the literal keeps the intent
 * explicit and the build warning-free.
 */
object AppPermissions {
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"
    const val REQUEST_SCHEDULE_EXACT_ALARM = "android.settings.REQUEST_SCHEDULE_EXACT_ALARM"
}
