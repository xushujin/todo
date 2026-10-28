package app.todo.data

import app.todo.data.update.ReleaseParser
import app.todo.data.update.versionCodeOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun versionCodeMatchesTheBuildScheme() {
        assertEquals(10000, versionCodeOf("1.0.0"))
        assertEquals(10003, versionCodeOf("v1.0.3"))
        assertEquals(10100, versionCodeOf("1.1.0"))
        assertEquals(10203, versionCodeOf("1.2.3"))
    }

    @Test
    fun rejectsNonSemverTags() {
        assertNull(versionCodeOf("main"))
        assertNull(versionCodeOf("1.1"))
        assertNull(versionCodeOf("1.1.0-beta"))
        assertNull(versionCodeOf("v1.x.0"))
        assertNull(versionCodeOf("-1.0.0"))
    }

    @Test
    fun parsesLatestReleaseAndPrefersTheVersionedAsset() {
        val info = requireNotNull(ReleaseParser.parse(releaseJson))
        assertEquals("1.1.0", info.versionName)
        assertEquals(10100, info.versionCode)
        assertEquals("https://example.test/Todo-1.1.0.apk", info.apkUrl)
        assertEquals(3_172_414L, info.apkSize)
        assertEquals(
            "3acb5826a528b4572c1be262dff82fcf9edc1f53749fe564daac713e82046c74",
            info.sha256,
        )
        assertTrue(info.notes.contains("update"))
    }

    @Test
    fun ignoresReleasesWithoutAnApkAsset() {
        val json = """{"tag_name":"v1.1.0","body":"","assets":[{"name":"notes.txt"}]}"""
        assertNull(ReleaseParser.parse(json))
    }

    @Test
    fun toleratesMissingDigestAndSize() {
        val json = """
            {"tag_name":"v1.1.0","assets":[{"name":"Todo-1.1.0.apk",
            "browser_download_url":"https://example.test/a.apk"}]}
        """.trimIndent()
        val info = requireNotNull(ReleaseParser.parse(json))
        assertNull(info.sha256)
        assertEquals(0L, info.apkSize)
    }

    @Test
    fun rejectsMalformedJson() {
        assertNull(ReleaseParser.parse("{not json"))
    }

    private val releaseJson = """
        {
          "tag_name": "v1.1.0",
          "body": "In-app update support",
          "assets": [
            {"name": "Todo-1.1.0.apk.sha256", "size": 81,
             "browser_download_url": "https://example.test/Todo-1.1.0.apk.sha256"},
            {"name": "Todo-1.1.0.apk", "size": 3172414,
             "digest": "sha256:3acb5826a528b4572c1be262dff82fcf9edc1f53749fe564daac713e82046c74",
             "browser_download_url": "https://example.test/Todo-1.1.0.apk"}
          ]
        }
    """.trimIndent()
}
