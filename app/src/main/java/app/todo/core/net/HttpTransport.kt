package app.todo.core.net

/**
 * The smallest HTTP surface the sync layer needs. Keeping it behind an interface
 * means the git providers can be tested on the JVM against a real local HTTP
 * server, and against a fake that returns crafted 404/409 responses.
 */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
)

data class HttpResponse(
    val status: Int,
    val body: ByteArray,
    val headers: Map<String, String> = emptyMap(),
) {
    val bodyText: String get() = body.toString(Charsets.UTF_8)

    fun header(name: String): String? =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
}

interface HttpTransport {
    suspend fun execute(request: HttpRequest): HttpResponse
}

class HttpException(message: String, val status: Int? = null) : Exception(message)
