package app.todo.data.git

import app.todo.core.json.JsonValue
import app.todo.core.net.HttpRequest
import app.todo.core.net.HttpResponse
import app.todo.core.net.HttpTransport

/**
 * GitHub and Gitea share the "contents API" shape: the file body is base64 inside
 * JSON, the revision token is the blob sha and a stale sha is rejected with 409.
 * One implementation therefore serves both hosts.
 */
class GitHubContentStore(
    private val transport: HttpTransport,
    private val kind: GitProviderKind,
) : GitContentStore {

    init {
        require(kind == GitProviderKind.GITHUB || kind == GitProviderKind.GITEA) {
            "GitHubContentStore only handles GITHUB and GITEA, got $kind"
        }
    }

    override suspend fun read(config: SyncConfig): RemoteFile? {
        val url = contentUrl(config)
        val response = transport.execute(HttpRequest("GET", url, headers(config)))
        return when (response.status) {
            200 -> parseContent(response.bodyText)
            404 -> null
            401, 403 -> throw GitAuthException(authMessage(response))
            else -> throw GitException("GET $url failed: ${describe(response)}", response.status)
        }
    }

    override suspend fun write(
        config: SyncConfig,
        content: String,
        expectedRevision: String?,
        message: String,
    ): RemoteFile {
        val url = contentUrl(config)
        val fields = linkedMapOf<String, JsonValue>(
            "message" to JsonValue.of(message),
            "content" to JsonValue.of(GitEncoding.base64(content)),
            "branch" to JsonValue.of(config.branch),
        )
        // The contents API expects the field to be absent when the file is new.
        if (expectedRevision != null) fields["sha"] = JsonValue.of(expectedRevision)
        val body = JsonValue.obj(fields).toJson()
        val response = transport.execute(
            HttpRequest(
                method = "PUT",
                url = url,
                headers = headers(config) + mapOf("Content-Type" to "application/json; charset=utf-8"),
                body = body.toByteArray(Charsets.UTF_8),
            ),
        )
        return when (response.status) {
            200, 201 -> {
                val revision = JsonValue.parseOrNull(response.bodyText)
                    ?.obj("content")
                    ?.str("sha")
                    ?: expectedRevision
                    ?: throw GitException("Write succeeded but no revision was returned", response.status)
                RemoteFile(content = content, revision = revision)
            }
            401, 403 -> throw GitAuthException(authMessage(response))
            409, 412, 422 -> throw GitConflictException("Remote changed meanwhile: ${describe(response)}")
            else -> throw GitException("PUT $url failed: ${describe(response)}", response.status)
        }
    }

    private fun contentUrl(config: SyncConfig): String {
        val root = config.baseUrl.trimEnd('/')
        val prefix = if (kind == GitProviderKind.GITHUB) "/repos" else "/api/v1/repos"
        val path = GitEncoding.encodePath(config.path)
        val branch = GitEncoding.encodeSegment(config.branch)
        return "$root$prefix/${GitEncoding.encodeSegment(config.owner)}/" +
            "${GitEncoding.encodeSegment(config.repo)}/contents/$path?ref=$branch"
    }

    private fun headers(config: SyncConfig): Map<String, String> = mapOf(
        "Accept" to if (kind == GitProviderKind.GITHUB) "application/vnd.github+json" else "application/json",
        "Authorization" to if (kind == GitProviderKind.GITHUB) "Bearer ${config.token}" else "token ${config.token}",
        "User-Agent" to USER_AGENT,
    )

    private fun parseContent(body: String): RemoteFile {
        val root = JsonValue.parseOrNull(body) ?: throw GitException("Unexpected response from $kind")
        val revision = root.str("sha") ?: throw GitException("Response is missing the blob sha")
        val encoding = root.str("encoding")
        val encoded = root.str("content").orEmpty()
        if (encoding == "none" || encoded.isEmpty()) {
            throw GitException(
                "File is too large for the contents API (limit 1 MB). Keep ${root.str("path") ?: "the file"} smaller.",
            )
        }
        return RemoteFile(content = app.todo.core.codec.Base64Codec.decodeToString(encoded), revision = revision)
    }

    private fun authMessage(response: HttpResponse): String =
        "Access denied (${response.status}). Check that the token can write repository contents."

    private fun describe(response: HttpResponse): String {
        val message = JsonValue.parseOrNull(response.bodyText)?.str("message")
        return if (message != null) "${response.status} $message" else response.status.toString()
    }

    private companion object {
        const val USER_AGENT = "Todo-Android/1.0 (+https://github.com/)"
    }
}
