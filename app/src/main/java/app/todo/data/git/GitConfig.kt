package app.todo.data.git

import app.todo.core.codec.Base64Codec

enum class GitProviderKind(val label: String, val defaultBaseUrl: String) {
    GITHUB("GitHub", "https://api.github.com"),
    GITEA("Gitea / Forgejo", "https://git.example.com"),
    GITLAB("GitLab", "https://gitlab.com"),
    ;

    companion object {
        fun fromName(name: String?): GitProviderKind =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: GITHUB
    }
}

data class SyncConfig(
    val provider: GitProviderKind = GitProviderKind.GITHUB,
    val baseUrl: String = GitProviderKind.GITHUB.defaultBaseUrl,
    val owner: String = "",
    val repo: String = "",
    val branch: String = "main",
    val path: String = "data/todos.json",
    val token: String = "",
) {
    /** Returns a human readable problem, or null when the config is usable. */
    fun validate(): String? {
        if (!baseUrl.startsWith("https://")) return "baseUrl must start with https://"
        if (owner.isBlank()) return "owner is required"
        if (repo.isBlank()) return "repo is required"
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
    /** Opaque revision token: blob sha for GitHub/Gitea, last commit id for GitLab. */
    val revision: String,
)

open class GitException(message: String, val status: Int? = null) : Exception(message)

class GitAuthException(message: String) : GitException(message, 401)

class GitConflictException(message: String) : GitException(message, 409)

/** The three supported hosts differ only in URL layout and revision token. */
interface GitContentStore {
    suspend fun read(config: SyncConfig): RemoteFile?

    suspend fun write(
        config: SyncConfig,
        content: String,
        expectedRevision: String?,
        message: String,
    ): RemoteFile
}

internal object GitEncoding {
    fun encodeSegment(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    fun encodePath(path: String): String =
        path.split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }

    fun base64(content: String): String = Base64Codec.encode(content.toByteArray(Charsets.UTF_8))
}
