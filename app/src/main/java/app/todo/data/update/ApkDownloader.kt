package app.todo.data.update

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads the release APK with progress, then verifies it.
 *
 * The transport used for the git APIs deliberately refuses redirects; release assets
 * always redirect (github.com -> release-assets.githubusercontent.com), so this is a
 * separate, download-only connection that follows them. The digest published in the
 * release metadata is checked before the file is handed to the installer.
 */
class ApkDownloader(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) {

    suspend fun download(
        url: String,
        target: File,
        expectedSize: Long,
        expectedSha256: String?,
        onProgress: (Float) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        val parsed = URL(url)
        if (parsed.protocol != "https") throw UpdateException("仅支持 HTTPS 下载")
        target.parentFile?.mkdirs()

        val connection = parsed.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = true
            connection.useCaches = false
            connection.setRequestProperty("User-Agent", UpdateChecker.USER_AGENT)

            val status = connection.responseCode
            if (status != 200) throw UpdateException("下载失败（HTTP $status）")

            val total = when {
                expectedSize > 0 -> expectedSize
                connection.contentLengthLong > 0 -> connection.contentLengthLong
                else -> -1L
            }
            var copied = 0L
            var lastPercent = -1

            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            val percent = ((copied * 100) / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }

            if (expectedSize > 0 && copied != expectedSize) {
                throw UpdateException("下载不完整（$copied / $expectedSize 字节）")
            }
            if (expectedSha256 != null) {
                val actual = sha256Of(target)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    throw UpdateException("安装包校验失败")
                }
            }
            onProgress(1f)
            target
        } catch (e: CancellationException) {
            target.delete()
            throw e
        } catch (e: Exception) {
            target.delete()
            throw if (e is UpdateException) e else UpdateException(e.message ?: "网络错误")
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}
