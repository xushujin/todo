package app.todo.data.update

/**
 * One published release, reduced to what the in-app updater needs.
 *
 * [versionCode] uses the same scheme as the build (`major*10000 + minor*100 + patch`),
 * so "is this newer than what I run?" is one integer comparison instead of a second
 * version parser.
 */
data class UpdateInfo(
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val sha256: String?,
)

/** `1.2.3` (or `v1.2.3`) -> `10203`; null when the tag is not `X.Y.Z`. */
fun versionCodeOf(version: String): Int? {
    val parts = version.removePrefix("v").split(".")
    if (parts.size != 3) return null
    val major = parts[0].toIntOrNull() ?: return null
    val minor = parts[1].toIntOrNull() ?: return null
    val patch = parts[2].toIntOrNull() ?: return null
    if (major < 0 || minor < 0 || patch < 0) return null
    return major * 10_000 + minor * 100 + patch
}

class UpdateException(message: String) : Exception(message)
