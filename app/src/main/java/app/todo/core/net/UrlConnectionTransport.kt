package app.todo.core.net

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [HttpTransport] on top of [HttpURLConnection] — part of the platform, so no
 * OkHttp dependency. Redirects are not followed on purpose: an authenticated API
 * request must not be replayed against a host we did not configure.
 */
class UrlConnectionTransport(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000,
    private val allowInsecureLoopback: Boolean = false,
) : HttpTransport {

    override suspend fun execute(request: HttpRequest): HttpResponse = withContext(Dispatchers.IO) {
        val url = URL(request.url)
        if (url.protocol != "https") {
            val loopback = url.host == "127.0.0.1" || url.host == "localhost" || url.host == "::1"
            if (!(allowInsecureLoopback && loopback)) {
                throw HttpException("Refusing insecure request to ${url.protocol}://${url.host}", null)
            }
        }
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = request.method
            connection.connectTimeout = connectTimeoutMs
            connection.readTimeout = readTimeoutMs
            connection.instanceFollowRedirects = false
            connection.useCaches = false
            request.headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val body = request.body
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            val headers = connection.headerFields
                .entries
                .filter { it.key != null }
                .associate { it.key to it.value.joinToString(", ") }
            HttpResponse(status = status, body = bytes, headers = headers)
        } catch (e: IOException) {
            throw HttpException(e.message ?: "Network error", null)
        } finally {
            connection.disconnect()
        }
    }
}
