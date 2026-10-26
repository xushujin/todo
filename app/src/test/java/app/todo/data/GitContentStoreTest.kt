package app.todo.data

import app.todo.core.codec.Base64Codec
import app.todo.core.json.JsonValue
import app.todo.core.net.HttpRequest
import app.todo.core.net.HttpResponse
import app.todo.data.git.GitAuthException
import app.todo.data.git.GitConflictException
import app.todo.data.git.ContentsApiStore
import app.todo.data.git.GitLabContentStore
import app.todo.data.git.GitProviderKind
import app.todo.data.git.SyncConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GitContentStoreTest {

    private val github = SyncConfig(
        provider = GitProviderKind.GITHUB,
        baseUrl = "https://api.github.com",
        owner = "hatim",
        repo = "todo",
        branch = "main",
        path = "data/todos.json",
        token = "tok",
    )

    private fun jsonBody(vararg fields: Pair<String, String>): String =
        fields.joinToString(prefix = "{", postfix = "}") { (key, value) -> "\"$key\":\"$value\"" }

    @Test
    fun githubReadsBase64ContentAndBlobSha() {
        val body = jsonBody(
            "sha" to "abc123",
            "encoding" to "base64",
            "content" to Base64Codec.encode("{\"schemaVersion\":1}").chunked(8).joinToString("\\n"),
        )
        val transport = RecordingTransport { HttpResponse(200, body.toByteArray()) }
        val store = ContentsApiStore(transport, GitProviderKind.GITHUB)

        val file = runBlocking { store.read(github) }

        assertEquals("{\"schemaVersion\":1}", file?.content)
        assertEquals("abc123", file?.revision)
        val request = transport.requests.single()
        assertEquals(
            "https://api.github.com/repos/hatim/todo/contents/data/todos.json?ref=main",
            request.url,
        )
        assertEquals("Bearer tok", request.headers["Authorization"])
        assertEquals("Todo-Android/1.0 (+https://github.com/)", request.headers["User-Agent"])
    }

    @Test
    fun giteaUsesTheApiV1Prefix() {
        val transport = RecordingTransport { HttpResponse(404, ByteArray(0)) }
        val store = ContentsApiStore(transport, GitProviderKind.GITEA)
        val config = github.copy(provider = GitProviderKind.GITEA, baseUrl = "https://git.example.com")

        val file = runBlocking { store.read(config) }

        assertNull(file)
        assertEquals(
            "https://git.example.com/api/v1/repos/hatim/todo/contents/data/todos.json?ref=main",
            transport.requests.single().url,
        )
        assertEquals("token tok", transport.requests.single().headers["Authorization"])
    }

    @Test
    fun githubWriteSendsBase64PayloadAndExpectedSha() {
        val transport = RecordingTransport { request ->
            if (request.method == "PUT") {
                HttpResponse(200, """{"content":{"sha":"newsha"}}""".toByteArray())
            } else {
                HttpResponse(404, ByteArray(0))
            }
        }
        val store = ContentsApiStore(transport, GitProviderKind.GITHUB)

        val written = runBlocking {
            store.write(github, "{\"a\":1}", expectedRevision = "oldsha", message = "todo: sync")
        }

        assertEquals("newsha", written.revision)
        val put = transport.requests.single()
        assertEquals("PUT", put.method)
        val payload = JsonValue.parse(put.body?.toString(Charsets.UTF_8).orEmpty())
        assertEquals("oldsha", payload.str("sha"))
        assertEquals("main", payload.str("branch"))
        assertEquals("{\"a\":1}", Base64Codec.decodeToString(payload.str("content").orEmpty()))
        assertEquals("application/json; charset=utf-8", put.headers["Content-Type"])
    }

    @Test
    fun githubWriteWithoutRevisionOmitsSha() {
        val transport = RecordingTransport {
            HttpResponse(201, """{"content":{"sha":"created"}}""".toByteArray())
        }
        val store = ContentsApiStore(transport, GitProviderKind.GITHUB)

        runBlocking { store.write(github, "x", expectedRevision = null, message = "create") }

        val payload = JsonValue.parse(transport.requests.single().body?.toString(Charsets.UTF_8).orEmpty())
        assertTrue(payload["sha"] == null || payload["sha"] == app.todo.core.json.JsonValue.Null)
    }

    @Test
    fun staleRevisionIsReportedAsConflict() {
        val transport = RecordingTransport { HttpResponse(409, """{"message":"sha does not match"}""".toByteArray()) }
        val store = ContentsApiStore(transport, GitProviderKind.GITHUB)
        assertThrows(GitConflictException::class.java) {
            runBlocking { store.write(github, "x", "old", "msg") }
        }
    }

    @Test
    fun unauthorizedIsReportedAsAuthError() {
        val transport = RecordingTransport { HttpResponse(401, """{"message":"Bad credentials"}""".toByteArray()) }
        val store = ContentsApiStore(transport, GitProviderKind.GITHUB)
        assertThrows(GitAuthException::class.java) { runBlocking { store.read(github) } }
    }

    @Test
    fun oversizedFileIsRejectedWithAClearMessage() {
        val transport = RecordingTransport {
            HttpResponse(200, """{"sha":"abc","encoding":"none","content":"","path":"data/todos.json"}""".toByteArray())
        }
        val store = ContentsApiStore(transport, GitProviderKind.GITHUB)
        val error = assertThrows(app.todo.data.git.GitException::class.java) {
            runBlocking { store.read(github) }
        }
        assertTrue(error.message.orEmpty().contains("1 MB"))
    }

    @Test
    fun gitlabReadsContentAndLastCommitId() {
        val transport = RecordingTransport {
            HttpResponse(
                200,
                """{"content":"${Base64Codec.encode("hello")}","last_commit_id":"commit-1"}""".toByteArray(),
            )
        }
        val store = GitLabContentStore(transport)
        val config = github.copy(provider = GitProviderKind.GITLAB, baseUrl = "https://gitlab.com", owner = "group/sub", repo = "todo")

        val file = runBlocking { store.read(config) }

        assertEquals("hello", file?.content)
        assertEquals("commit-1", file?.revision)
        assertEquals(
            "https://gitlab.com/api/v4/projects/group%2Fsub%2Ftodo/repository/files/data%2Ftodos.json?ref=main",
            transport.requests.single().url,
        )
        assertEquals("tok", transport.requests.single().headers["PRIVATE-TOKEN"])
    }

    @Test
    fun gitlabUsesPostForNewFilesAndPutForUpdates() {
        var calls = 0
        val transport = RecordingTransport { request ->
            calls++
            HttpResponse(if (request.method == "POST") 201 else 200, """{"last_commit_id":"c$calls"}""".toByteArray())
        }
        val store = GitLabContentStore(transport)
        val config = github.copy(provider = GitProviderKind.GITLAB, baseUrl = "https://gitlab.com")

        runBlocking { store.write(config, "one", expectedRevision = null, message = "create") }
        runBlocking { store.write(config, "two", expectedRevision = "c1", message = "update") }

        assertEquals(listOf("POST", "PUT"), transport.requests.map { it.method })
        val updateBody = JsonValue.parse(transport.requests[1].body?.toString(Charsets.UTF_8).orEmpty())
        assertEquals("c1", updateBody.str("last_commit_id"))
        assertEquals("two", Base64Codec.decodeToString(updateBody.str("content").orEmpty()))
    }

    @Test
    fun gitlabRejectsStaleCommitIdAsConflict() {
        val transport = RecordingTransport { HttpResponse(400, """{"message":"last_commit_id does not match"}""".toByteArray()) }
        val store = GitLabContentStore(transport)
        val config = github.copy(provider = GitProviderKind.GITLAB, baseUrl = "https://gitlab.com")
        assertThrows(GitConflictException::class.java) {
            runBlocking { store.write(config, "x", "stale", "msg") }
        }
    }

    @Test
    fun missingFileReadsAsNullOnBothProviders() {
        val transport = RecordingTransport { HttpResponse(404, ByteArray(0)) }
        val githubStore = ContentsApiStore(transport, GitProviderKind.GITHUB)
        val gitlabStore = GitLabContentStore(transport)
        assertNull(runBlocking { githubStore.read(github) })
        assertNull(runBlocking { gitlabStore.read(github.copy(baseUrl = "https://gitlab.com")) })
    }

    @Test
    fun giteeUsesApiV5AndQueryToken() {
        val body = """{"type":"file","path":"data/todos.json","sha":"blob1","encoding":"base64","content":"aGVsbG8="}"""
        val transport = RecordingTransport { HttpResponse(200, body.toByteArray()) }
        val store = ContentsApiStore(transport, GitProviderKind.GITEE)
        val config = github.copy(provider = GitProviderKind.GITEE, baseUrl = "https://gitee.com")

        val file = runBlocking { store.read(config) }

        assertEquals("hello", file?.content)
        assertEquals("blob1", file?.revision)
        assertEquals(
            "https://gitee.com/api/v5/repos/hatim/todo/contents/data/todos.json?ref=main&access_token=tok",
            transport.requests.single().url,
        )
        assertNull(transport.requests.single().headers["Authorization"])
    }

    @Test
    fun giteeAcceptsArrayResponseFromRead() {
        val body = """[{"type":"file","path":"data/todos.json","sha":"blob-array","encoding":"base64","content":"aGVsbG8="}]"""
        val transport = RecordingTransport { HttpResponse(200, body.toByteArray()) }
        val store = ContentsApiStore(transport, GitProviderKind.GITEE)
        val config = github.copy(provider = GitProviderKind.GITEE, baseUrl = "https://gitee.com")

        val file = runBlocking { store.read(config) }

        assertEquals("hello", file?.content)
        assertEquals("blob-array", file?.revision)
    }

    @Test
    fun giteeCreatesWithPostAndUpdatesWithPut() {
        var calls = 0
        val transport = RecordingTransport { request ->
            calls++
            HttpResponse(
                if (request.method == "POST") 201 else 200,
                """{"content":{"sha":"rev-$calls"},"commit":{"sha":"commit-$calls"}}""".toByteArray(),
            )
        }
        val store = ContentsApiStore(transport, GitProviderKind.GITEE)
        val config = github.copy(provider = GitProviderKind.GITEE, baseUrl = "https://gitee.com")

        val created = runBlocking { store.write(config, "one", expectedRevision = null, message = "create") }
        val updated = runBlocking { store.write(config, "two", expectedRevision = "rev-1", message = "update") }

        assertEquals("rev-1", created.revision)
        assertEquals("rev-2", updated.revision)
        assertEquals(listOf("POST", "PUT"), transport.requests.map { it.method })

        val createBody = JsonValue.parse(transport.requests[0].body?.toString(Charsets.UTF_8).orEmpty())
        assertEquals("tok", createBody.str("access_token"))
        assertTrue(createBody["sha"] == null)

        val updateBody = JsonValue.parse(transport.requests[1].body?.toString(Charsets.UTF_8).orEmpty())
        assertEquals("rev-1", updateBody.str("sha"))
        assertEquals("two", Base64Codec.decodeToString(updateBody.str("content").orEmpty()))
    }

    @Test
    fun giteeStaleShaIsReportedAsConflict() {
        val transport = RecordingTransport { HttpResponse(400, """{"message":"sha is not match"}""".toByteArray()) }
        val store = ContentsApiStore(transport, GitProviderKind.GITEE)
        val config = github.copy(provider = GitProviderKind.GITEE, baseUrl = "https://gitee.com")

        assertThrows(GitConflictException::class.java) {
            runBlocking { store.write(config, "x", expectedRevision = "stale", message = "update") }
        }
    }

    @Test
    fun directoryPathIsRejectedWithAClearMessage() {
        val body = """{"type":"dir","path":"data","sha":"tree1"}"""
        val transport = RecordingTransport { HttpResponse(200, body.toByteArray()) }
        val store = ContentsApiStore(transport, GitProviderKind.GITHUB)

        val error = assertThrows(app.todo.data.git.GitException::class.java) {
            runBlocking { store.read(github) }
        }
        assertTrue(error.message.orEmpty().contains("directory"))
    }

    @Test
    fun requestHeadersDoNotLeakAcrossProviders() {
        val transport = RecordingTransport { HttpResponse(404, ByteArray(0)) }
        runBlocking { ContentsApiStore(transport, GitProviderKind.GITHUB).read(github) }
        runBlocking { GitLabContentStore(transport).read(github.copy(baseUrl = "https://gitlab.com")) }
        assertTrue(transport.requests[0].headers.containsKey("Authorization"))
        assertTrue(transport.requests[1].headers.containsKey("PRIVATE-TOKEN"))
        assertTrue(!transport.requests[1].headers.containsKey("Authorization"))
    }
}
