package app.todo.data.git

import app.todo.core.codec.Base64Codec
import app.todo.core.json.JsonValue
import app.todo.core.net.HttpRequest
import app.todo.core.net.HttpResponse
import app.todo.core.net.HttpTransport

/**
 * The "contents API" family: GitHub, Gitee and Gitea.
 *
 * They share the same shape — file body base64 inside JSON, the blob `sha` as the
 * revision token, a stale sha rejected as a conflict — and differ only in:
 *
 * | | GitHub | Gitee | Gitea |
 * | --- | --- | --- | --- |
 * | base path | `/repos` | `/api/v5/repos` | `/api/v1/repos` |
 * | auth | `Authorization: Bearer` | `access_token` query param | `Authorization: token` |
 * | create | `PUT` | `POST` | `PUT` |
 * | update | `PUT` | `PUT` + required `sha` | `PUT` |
 *
 * Gitee's read endpoint is documented as returning an array, so the parser accepts
 * both a single object and an array and picks the entry matching the file path.
 */
class ContentsApiStore(
    private val transport: HttpTransport,
    private val kind: GitProviderKind,
) : GitContentStore {

    init {
        require(kind != GitProviderKind.GITLAB) {
            "ContentsApiStore handles GitHub, Gitee and Gitea; GitLab uses GitLabContentStore"
        }
    }

    override suspend fun read(config: SyncConfig): RemoteFile? {
        val url = contentUrl(config, withRef = true)
        val response = transport.execute(HttpRequest("GET", url, headers(config)))
        return when (response.status) {
            200 -> parseContentOrEmpty(response.bodyText, config)
            404 -> null
            401, 403 -> throw GitAuthException(authMessage(response))
            else -> throw GitException(
                "GET ${redactSecrets(url)} failed: ${describe(response)}",
                response.status,
                kindFor(response.status),
            )
        }
    }

    override suspend fun write(
        config: SyncConfig,
        content: String,
        expectedRevision: String?,
        message: String,
    ): RemoteFile {
        val url = contentUrl(config, withRef = false)
        val fields = linkedMapOf<String, JsonValue>(
            "message" to JsonValue.of(message),
            "content" to JsonValue.of(GitEncoding.base64(content)),
            "branch" to JsonValue.of(config.branch),
        )
        // The field must be absent when the file is new; Gitee requires it on update.
        if (expectedRevision != null) fields["sha"] = JsonValue.of(expectedRevision)
        // Gitee reads the token from the query string, but its POST/PUT parameters are
        // documented as form fields — sending both keeps either binding happy.
        if (kind == GitProviderKind.GITEE) fields["access_token"] = JsonValue.of(config.token)

        val method = if (kind == GitProviderKind.GITEE && expectedRevision == null) "POST" else "PUT"
        val response = transport.execute(
            HttpRequest(
                method = method,
                url = url,
                headers = headers(config) + mapOf("Content-Type" to "application/json; charset=utf-8"),
                body = JsonValue.obj(fields).toJson().toByteArray(Charsets.UTF_8),
            ),
        )
        return when (response.status) {
            200, 201 -> {
                val revision = JsonValue.parseOrNull(response.bodyText)
                    ?.obj("content")
                    ?.str("sha")
                    ?: expectedRevision
                    // Gitee's create response is not fully specified; read back if needed.
                    ?: read(config)?.revision
                    ?: throw GitException("Write succeeded but no revision was returned", response.status)
                RemoteFile(content = content, revision = revision)
            }
            401, 403 -> throw GitAuthException(authMessage(response))
            409, 412, 422 -> throw GitConflictException("Remote changed meanwhile: ${describe(response)}")
            // Gitee reports a stale sha as 400 with a message; only treat it as a conflict
            // when a revision was expected (a malformed create should still surface).
            400 -> if (expectedRevision != null) {
                throw GitConflictException("Remote changed meanwhile: ${describe(response)}")
            } else {
                throw GitException(
                    "$method ${redactSecrets(url)} failed: ${describe(response)}",
                    response.status,
                    kindFor(response.status),
                )
            }
            else -> throw GitException(
                "$method ${redactSecrets(url)} failed: ${describe(response)}",
                response.status,
                kindFor(response.status),
            )
        }
    }

    override suspend fun defaultBranch(config: SyncConfig): String? {
        val response = transport.execute(HttpRequest("GET", repoUrl(config), headers(config)))
        if (response.status != 200) return null
        return JsonValue.parseOrNull(response.bodyText)?.str("default_branch")
    }

    private fun repoUrl(config: SyncConfig): String =
        "${config.baseUrl.trimEnd('/')}${repoPrefix()}/${GitEncoding.encodeSegment(config.owner)}/" +
            GitEncoding.encodeSegment(config.repo)

    private fun repoPrefix(): String = when (kind) {
        GitProviderKind.GITHUB -> "/repos"
        GitProviderKind.GITEE -> "/api/v5/repos"
        GitProviderKind.GITEA -> "/api/v1/repos"
        GitProviderKind.GITLAB -> error("GitLab uses a different URL layout")
    }

    private fun contentUrl(
        config: SyncConfig,
        withRef: Boolean,
        pathOverride: String = config.path,
    ): String {
        val root = config.baseUrl.trimEnd('/')
        val prefix = repoPrefix()
        val path = GitEncoding.encodePath(pathOverride)
        val base = "$root$prefix/${GitEncoding.encodeSegment(config.owner)}/" +
            "${GitEncoding.encodeSegment(config.repo)}/contents/$path"
        val query = buildList {
            if (withRef) add("ref=${GitEncoding.encodeSegment(config.branch)}")
            if (kind == GitProviderKind.GITEE) {
                add("access_token=${GitEncoding.encodeSegment(config.token)}")
            }
        }
        return if (query.isEmpty()) base else "$base?${query.joinToString("&")}"
    }

    private fun headers(config: SyncConfig): Map<String, String> = when (kind) {
        GitProviderKind.GITHUB -> mapOf(
            "Accept" to "application/vnd.github+json",
            "Authorization" to "Bearer ${config.token}",
            "User-Agent" to USER_AGENT,
        )
        GitProviderKind.GITEE -> mapOf(
            "Accept" to "application/json",
            "User-Agent" to USER_AGENT,
        )
        else -> mapOf(
            "Accept" to "application/json",
            "Authorization" to "token ${config.token}",
            "User-Agent" to USER_AGENT,
        )
    }

    /**
     * Gitee answers `200` with an empty body for a 0-byte file, which is not JSON at
     * all. The blob sha is only available from the parent directory listing, so use
     * that as a fallback instead of failing with "unexpected response".
     */
    private suspend fun parseContentOrEmpty(body: String, config: SyncConfig): RemoteFile {
        val parsed = JsonValue.parseOrNull(body)
        // Gitee has been observed answering either an empty body or `[]` for a 0-byte
        // file; both mean "the file exists but carries no content".
        val emptyResponse = body.isBlank() || (parsed is JsonValue.Arr && parsed.items.isEmpty())
        if (!emptyResponse) return parseContent(body, config.path)

        val name = config.path.substringAfterLast('/')
        val parent = config.path.substringBeforeLast('/', "")
        val listingUrl = contentUrl(config, withRef = true, pathOverride = parent)
        val listing = transport.execute(HttpRequest("GET", listingUrl, headers(config)))
        val entries = if (listing.status == 200) {
            (JsonValue.parseOrNull(listing.bodyText) as? JsonValue.Arr)?.items.orEmpty()
        } else {
            emptyList()
        }
        val sha = entries
            .filterIsInstance<JsonValue.Obj>()
            .firstOrNull { it.str("name") == name || it.str("path") == config.path }
            ?.str("sha")
            ?: throw GitException(
                "The remote file is empty and its revision could not be read. " +
                    "Open $name once in the repository web UI, then retry.",
            )
        return RemoteFile(content = "", revision = sha)
    }

    private fun parseContent(body: String, expectedPath: String): RemoteFile {
        val root = JsonValue.parseOrNull(body) ?: throw GitException("Unexpected response from $kind")
        val entry = when (root) {
            is JsonValue.Obj -> root
            is JsonValue.Arr -> root.items
                .filterIsInstance<JsonValue.Obj>()
                .firstOrNull { it.str("path") == expectedPath }
                ?: root.items.filterIsInstance<JsonValue.Obj>().firstOrNull()
            else -> null
        } ?: throw GitException("Unexpected response from $kind")

        if (entry.str("type") == "dir") {
            throw GitException("Configured path points to a directory, not a file: $expectedPath")
        }
        val revision = entry.str("sha") ?: throw GitException("Response is missing the blob sha")
        val encoded = entry.str("content")
        // A freshly created 0-byte file is a valid (empty) document, not an error.
        // `size` must be present and zero; an absent size plus "encoding":"none" means
        // the contents API refused to inline a large file.
        if (entry.long("size") == 0L && encoded.isNullOrEmpty()) {
            return RemoteFile(content = "", revision = revision)
        }
        if (entry.str("encoding") == "none" || encoded.isNullOrEmpty()) {
            throw GitException(
                "File is too large for the contents API (limit 1 MB). Keep " +
                    (entry.str("path") ?: expectedPath) + " smaller.",
            )
        }
        return RemoteFile(content = Base64Codec.decodeToString(encoded), revision = revision)
    }

    private fun kindFor(status: Int): GitErrorKind = when (status) {
        404 -> GitErrorKind.NOT_FOUND
        409, 412, 422 -> GitErrorKind.CONFLICT
        else -> GitErrorKind.UNKNOWN
    }

    private fun authMessage(response: HttpResponse): String =
        "Access denied (${response.status}). Check that the token can write repository contents."

    private fun describe(response: HttpResponse): String {
        val root = JsonValue.parseOrNull(response.bodyText)
        val message = root?.str("message") ?: root?.str("error")
        return if (message != null) "${response.status} $message" else response.status.toString()
    }

    private companion object {
        const val USER_AGENT = "Todo-Android/1.0 (+https://github.com/)"
    }
}
