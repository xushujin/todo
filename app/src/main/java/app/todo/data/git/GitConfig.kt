package app.todo.data.git

import app.todo.core.codec.Base64Codec

enum class GitProviderKind(val label: String, val defaultBaseUrl: String) {
    GITHUB("GitHub", "https://api.github.com"),

    // Gitee (码云) exposes a contents API shaped like GitHub's, but under /api/v5 and
    // with the token passed as a query parameter instead of an Authorization header.
    GITEE("Gitee", "https://gitee.com"),
    GITEA("Gitea / Forgejo", "https://git.example.com"),
    GITLAB("GitLab", "https://gitlab.com"),
    ;

    companion object {
        fun fromName(name: String?): GitProviderKind =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: GITHUB
    }
}

/** Coarse error classes so the UI can show a localized sentence instead of a raw dump. */
enum class GitErrorKind { AUTH, NOT_FOUND, CONFLICT, NETWORK, INVALID_CONFIG, UNKNOWN }

data class SyncConfig(
    val provider: GitProviderKind = GitProviderKind.GITHUB,
    val baseUrl: String = GitProviderKind.GITHUB.defaultBaseUrl,
    val owner: String = "",
    val repo: String = "",
    val branch: String = "main",
    val path: String = "data/todos.json",
    val token: String = "",
) {
    /**
     * Accepts what people actually paste into the "repository" field:
     *
     *  - `https://gitee.com/xushujin/todo-data.git` → owner `xushujin`, repo `todo-data`
     *  - `xushujin/todo-data`                       → owner `xushujin`, repo `todo-data`
     *  - `todo-data.git`                           → repo `todo-data` (owner unchanged)
     *
     * Without this the clone URL ends up URL-encoded inside the API path and every
     * request answers 404.
     */
    fun normalized(): SyncConfig {
        var ownerValue = owner.trim().trim('/')
        var repoValue = repo.trim().trim('/')

        val looksLikeUrl = repoValue.startsWith("http://", ignoreCase = true) ||
            repoValue.startsWith("https://", ignoreCase = true)
        if (looksLikeUrl) {
            val withoutScheme = repoValue.substringAfter("://")
            val pathPart = withoutScheme.substringBefore('?').substringBefore('#')
            val segments = pathPart.split('/').filter { it.isNotBlank() }
            val rest = segments.drop(1) // first segment is the host
            when {
                rest.size >= 2 -> {
                    ownerValue = rest.dropLast(1).joinToString("/")
                    repoValue = rest.last()
                }
                rest.size == 1 -> repoValue = rest.first()
            }
        } else if (repoValue.contains('/')) {
            val segments = repoValue.split('/').filter { it.isNotBlank() }
            if (segments.size >= 2) {
                ownerValue = segments.dropLast(1).joinToString("/")
                repoValue = segments.last()
            }
        }

        return copy(
            owner = ownerValue,
            repo = repoValue.removeSuffix(".git"),
            branch = branch.trim().ifBlank { "main" },
            path = path.trim().trimStart('/'),
            baseUrl = baseUrl.trim().trimEnd('/'),
            token = token.trim(),
        )
    }

    /** Returns a human readable problem, or null when the config is usable. */
    fun validate(): String? {
        if (!baseUrl.startsWith("https://")) return "baseUrl must start with https://"
        if (owner.isBlank()) return "owner is required"
        if (owner.contains("://")) return "owner must not be a URL"
        if (repo.isBlank()) return "repo is required"
        if (repo.contains("://") || repo.contains('/')) return "repo must be a repository name, not a URL"
        if (branch.isBlank()) return "branch is required"
        if (path.isBlank()) return "path is required"
        if (path.startsWith("/")) return "path must be relative"
        if (path.split('/').any { it == ".." }) return "path must not contain .."
        if (token.isBlank()) return "token is required"
        return null
    }

    val isConfigured: Boolean get() = validate() == null
}

data class RemoteFile(
    val content: String,
    /** Opaque revision token: blob sha for GitHub/Gitee/Gitea, last commit id for GitLab. */
    val revision: String,
)

open class GitException(
    message: String,
    val status: Int? = null,
    val kind: GitErrorKind = GitErrorKind.UNKNOWN,
) : Exception(message)

class GitAuthException(message: String) : GitException(message, 401, GitErrorKind.AUTH)

class GitConflictException(message: String) : GitException(message, 409, GitErrorKind.CONFLICT)

/** The supported hosts differ only in URL layout, auth header and revision token. */
interface GitContentStore {
    suspend fun read(config: SyncConfig): RemoteFile?

    suspend fun write(
        config: SyncConfig,
        content: String,
        expectedRevision: String?,
        message: String,
    ): RemoteFile

    /**
     * Default branch of the repository, used to explain "file not found" instead of
     * leaving the user with a bare 404. Returns null when it cannot be determined.
     */
    suspend fun defaultBranch(config: SyncConfig): String?
}

/**
 * Tokens must never reach the UI or a screenshot: Gitee puts `access_token` in the
 * query string, so every URL that ends up inside an error message goes through here.
 */
internal fun redactSecrets(text: String): String = text
    .replace(Regex("(access_token=)[^&\\s]*"), "$1***")
    .replace(Regex("(private_token=)[^&\\s]*"), "$1***")

internal object GitEncoding {
    fun encodeSegment(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun encodePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }

    fun base64(content: String): String = Base64Codec.encode(content.toByteArray(Charsets.UTF_8))
}
