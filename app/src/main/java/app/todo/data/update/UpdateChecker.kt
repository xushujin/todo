package app.todo.data.update

import app.todo.core.json.JsonValue
import app.todo.core.net.HttpRequest
import app.todo.core.net.HttpTransport

/**
 * Reads the newest published release from the GitHub Releases API.
 *
 * The project ships through GitHub Releases only, so this is the canonical source:
 * the tag carries the version, the asset carries the APK and its SHA-256 digest.
 */
class UpdateChecker(
    private val transport: HttpTransport,
    private val endpoint: String = DEFAULT_ENDPOINT,
) {

    /** Returns the newer release, or null when the installed build is already current. */
    suspend fun latest(currentVersionCode: Int): UpdateInfo? {
        val response = transport.execute(
            HttpRequest(
                method = "GET",
                url = endpoint,
                headers = mapOf(
                    "Accept" to "application/vnd.github+json",
                    // The GitHub API rejects requests without a User-Agent.
                    "User-Agent" to USER_AGENT,
                ),
            ),
        )
        if (response.status != 200) throw UpdateException("服务器返回 HTTP ${response.status}")
        val info = ReleaseParser.parse(response.bodyText)
            ?: throw UpdateException("无法解析版本信息")
        return info.takeIf { it.versionCode > currentVersionCode }
    }

    companion object {
        const val DEFAULT_ENDPOINT = "https://api.github.com/repos/xushujin/todo/releases/latest"
        const val USER_AGENT = "Todo-Android"
    }
}

/** Pure parsing, split out so the JVM tests never touch the network. */
object ReleaseParser {

    fun parse(json: String): UpdateInfo? {
        val root = JsonValue.parseOrNull(json) ?: return null
        val tag = root.str("tag_name") ?: return null
        val version = tag.removePrefix("v")
        val code = versionCodeOf(version) ?: return null

        val assets = root.arr("assets")?.items.orEmpty()
            .mapNotNull { it as? JsonValue.Obj }
            .filter { it.str("name")?.endsWith(".apk", ignoreCase = true) == true }
        // Prefer the asset named after this exact version, then any Todo-*.apk.
        val apk = assets.firstOrNull { it.str("name") == "Todo-$version.apk" }
            ?: assets.firstOrNull { it.str("name")?.startsWith("Todo-") == true }
            ?: assets.firstOrNull()
            ?: return null

        val url = apk.str("browser_download_url") ?: return null
        val size = apk.long("size") ?: 0L
        val digest = apk.str("digest")
            ?.removePrefix("sha256:")
            ?.lowercase()
            ?.takeIf { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }

        return UpdateInfo(
            versionName = version,
            versionCode = code,
            notes = root.str("body").orEmpty().trim(),
            apkUrl = url,
            apkSize = size,
            sha256 = digest,
        )
    }
}
