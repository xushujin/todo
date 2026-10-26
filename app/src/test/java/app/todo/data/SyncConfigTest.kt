package app.todo.data

import app.todo.data.git.GitProviderKind
import app.todo.data.git.SyncConfig
import app.todo.data.git.redactSecrets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncConfigTest {

    private val valid = SyncConfig(
        provider = GitProviderKind.GITHUB,
        baseUrl = "https://api.github.com",
        owner = "hatim",
        repo = "todo",
        branch = "main",
        path = "data/todos.json",
        token = "ghp_example",
    )

    @Test
    fun acceptsACompleteConfiguration() {
        assertNull(valid.validate())
        assertTrue(valid.isConfigured)
    }

    @Test
    fun requiresHttps() {
        val insecure = valid.copy(baseUrl = "http://git.example.com")
        assertNotNull(insecure.validate())
        assertTrue(insecure.validate().orEmpty().contains("https://"))
    }

    @Test
    fun requiresOwnerRepoAndToken() {
        assertNotNull(valid.copy(owner = " ").validate())
        assertNotNull(valid.copy(repo = "").validate())
        assertNotNull(valid.copy(token = "").validate())
        assertNotNull(valid.copy(branch = "").validate())
        assertNotNull(valid.copy(path = "").validate())
    }

    @Test
    fun rejectsAbsoluteAndTraversingPaths() {
        assertNotNull(valid.copy(path = "/data/todos.json").validate())
        assertNotNull(valid.copy(path = "data/../../todos.json").validate())
    }

    @Test
    fun providerDefaultsAreUsableBaseUrls() {
        GitProviderKind.entries.forEach { kind ->
            assertTrue(kind.defaultBaseUrl, kind.defaultBaseUrl.startsWith("https://"))
        }
        assertEquals(GitProviderKind.GITHUB, GitProviderKind.fromName("github"))
        assertEquals(GitProviderKind.GITLAB, GitProviderKind.fromName("GITLAB"))
        assertEquals(GitProviderKind.GITHUB, GitProviderKind.fromName("unknown"))
    }

    @Test
    fun normalizesACloneUrlPastedIntoTheRepoField() {
        val messy = valid.copy(
            owner = "xushujin/todo-data",
            repo = "https://gitee.com/xushujin/todo-data.git",
        )

        val fixed = messy.normalized()

        assertEquals("xushujin", fixed.owner)
        assertEquals("todo-data", fixed.repo)
        assertNull(fixed.validate())
    }

    @Test
    fun normalizesOwnerSlashRepoAndStripsGitSuffix() {
        val fromPath = valid.copy(owner = "", repo = "xushujin/todo-data").normalized()
        assertEquals("xushujin", fromPath.owner)
        assertEquals("todo-data", fromPath.repo)

        val withSuffix = valid.copy(owner = "xushujin", repo = "todo-data.git").normalized()
        assertEquals("xushujin", withSuffix.owner)
        assertEquals("todo-data", withSuffix.repo)
    }

    @Test
    fun normalizationKeepsGroupNamespaces() {
        val group = valid.copy(owner = "group/sub", repo = "todo").normalized()
        assertEquals("group/sub", group.owner)
        assertEquals("todo", group.repo)
    }

    @Test
    fun redactsAccessTokensFromErrorText() {
        val message = "POST https://gitee.com/api/v5/repos/a/b/contents/x.json?access_token=secret123 failed: 404"

        val redacted = redactSecrets(message)

        assertFalse(redacted.contains("secret123"))
        assertTrue(redacted.contains("access_token=***"))
    }

    @Test
    fun defaultConfigIsNotUsableUntilFilledIn() {
        val default = SyncConfig()
        assertFalse(default.isConfigured)
        assertNotNull(default.validate())
    }
}
