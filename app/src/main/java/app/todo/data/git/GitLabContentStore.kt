package app.todo.data.git

import app.todo.core.codec.Base64Codec
import app.todo.core.json.JsonValue
import app.todo.core.net.HttpRequest
import app.todo.core.net.HttpResponse
import app.todo.core.net.HttpTransport

/**
 * GitLab's repository files API. Differences from the contents API:
 *  - the project is addressed by its URL-encoded full path,
 *  - the revision token is `last_commit_id` instead of a blob sha,
 *  - create (POST) and update (PUT) are separate verbs,
 *  - the file content is returned base64 encoded.
 */
class GitLabContentStore(private val transport: HttpTransport) : GitContentStore {

    override suspend fun read(config: SyncConfig): RemoteFile? {
        val url = "${apiRoot(config)}/projects/${projectId(config)}/repository/files/" +
            "${GitEncoding.encodeSegment(config.path)}?ref=${GitEncoding.encodeSegment(config.branch)}"
        val response = transport.execute(HttpRequest("GET", url, headers(config)))
        return when (response.status) {
            200 -> {
                val root = JsonValue.parseOrNull(response.bodyText)
                    ?: throw GitException("Unexpected response from GitLab")
                val encoded = root.str("content") ?: throw GitException("Response is missing file content")
                val revision = root.str("last_commit_id")
                    ?: throw GitException("Response is missing last_commit_id")
                RemoteFile(content = Base64Codec.decodeToString(encoded), revision = revision)
            }
            404 -> null
            401, 403 -> throw GitAuthException("Access denied (${response.status}). Check the token scope.")
            else -> throw GitException("GET $url failed: ${describe(response)}", response.status)
        }
    }

    override suspend fun write(
        config: SyncConfig,
        content: String,
        expectedRevision: String?,
        message: String,
    ): RemoteFile {
        val url = "${apiRoot(config)}/projects/${projectId(config)}/repository/files/" +
            GitEncoding.encodeSegment(config.path)
        val fields = linkedMapOf<String, JsonValue>(
            "branch" to JsonValue.of(config.branch),
            "content" to JsonValue.of(GitEncoding.base64(content)),
            "commit_message" to JsonValue.of(message),
        )
        if (expectedRevision != null) {
            fields["last_commit_id"] = JsonValue.of(expectedRevision)
        }
        val method = if (expectedRevision == null) "POST" else "PUT"
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
                val root = JsonValue.parseOrNull(response.bodyText)
                val revision = root?.str("last_commit_id")
                    ?: root?.str("commit_id")
                    ?: expectedRevision
                    ?: throw GitException("Write succeeded but no revision was returned", response.status)
                RemoteFile(content = content, revision = revision)
            }
            400 -> throw GitConflictException("Remote changed meanwhile: ${describe(response)}")
            401, 403 -> throw GitAuthException("Access denied (${response.status}). Check the token scope.")
            else -> throw GitException("$method $url failed: ${describe(response)}", response.status)
        }
    }

    private fun apiRoot(config: SyncConfig): String = config.baseUrl.trimEnd('/') + "/api/v4"

    private fun projectId(config: SyncConfig): String =
        GitEncoding.encodeSegment("${config.owner}/${config.repo}")

    private fun headers(config: SyncConfig): Map<String, String> = mapOf(
        "PRIVATE-TOKEN" to config.token,
        "Accept" to "application/json",
        "User-Agent" to "Todo-Android/1.0",
    )

    private fun describe(response: HttpResponse): String {
        val root = JsonValue.parseOrNull(response.bodyText)
        val message = root?.str("message") ?: root?.str("error")
        return if (message != null) "${response.status} $message" else response.status.toString()
    }
}
