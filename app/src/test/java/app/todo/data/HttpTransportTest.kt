package app.todo.data

import app.todo.core.net.HttpException
import app.todo.core.net.HttpRequest
import app.todo.core.net.HttpResponse
import app.todo.core.net.HttpTransport
import app.todo.core.net.UrlConnectionTransport
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The real transport is exercised against a real HTTP server on loopback: request
 * line, headers, body, status codes and error streams all go through a socket.
 *
 * (A raw [ServerSocket] is used instead of `com.sun.net.httpserver` because the
 * Android unit-test classpath does not expose that JDK module.)
 */
class HttpTransportTest {

    private class FakeHttpServer : AutoCloseable {

        private val serverSocket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = serverSocket.localPort

        private val thread = Thread {
            while (!serverSocket.isClosed) {
                val socket = try {
                    serverSocket.accept()
                } catch (_: Exception) {
                    break
                }
                runCatching { handle(socket) }
            }
        }.apply {
            isDaemon = true
            start()
        }

        private fun handle(socket: Socket) {
            socket.use { client ->
              val input = client.getInputStream()
              val requestLine = readLine(input) ?: return
              var contentLength = 0
              while (true) {
                  val line = readLine(input) ?: break
                  if (line.isEmpty()) break
                  if (line.lowercase().startsWith("content-length:")) {
                      contentLength = line.substringAfter(':').trim().toIntOrNull() ?: 0
                  }
              }
              val body = ByteArray(contentLength)
              var read = 0
              while (read < contentLength) {
                  val count = input.read(body, read, contentLength - read)
                  if (count < 0) break
                  read += count
              }

              val parts = requestLine.split(' ')
              val method = parts.getOrElse(0) { "GET" }
              val path = parts.getOrElse(1) { "/" }
              val (status, headers, payload) = when (path) {
                  "/ok" -> Triple("200 OK", mapOf("X-Test" to "yes"), "hello".toByteArray())
                  "/echo" -> Triple("200 OK", emptyMap(), method.toByteArray() + body)
                  else -> Triple("404 Not Found", emptyMap(), ByteArray(0))
              }
              val head = buildString {
                  append("HTTP/1.1 ").append(status).append("\r\n")
                  headers.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                  append("Content-Length: ").append(payload.size).append("\r\n")
                  append("Connection: close\r\n\r\n")
              }
              client.getOutputStream().apply {
                  write(head.toByteArray(Charsets.US_ASCII))
                  write(payload)
                  flush()
              }
            }
        }

        private fun readLine(input: InputStream): String? {
            val buffer = StringBuilder()
            while (true) {
                val byte = input.read()
                if (byte == -1) return if (buffer.isEmpty()) null else buffer.toString()
                if (byte == '\n'.code) return buffer.toString().trimEnd('\r')
                buffer.append(byte.toChar())
            }
        }

        override fun close() {
            serverSocket.close()
        }
    }

    private fun withServer(block: (baseUrl: String) -> Unit) {
        FakeHttpServer().use { server -> block("http://127.0.0.1:${server.port}") }
    }

    @Test
    fun performsGetAndExposesStatusHeadersAndBody() = withServer { baseUrl ->
        val transport = UrlConnectionTransport(allowInsecureLoopback = true)
        val response = runBlocking { transport.execute(HttpRequest("GET", "$baseUrl/ok")) }

        assertEquals(200, response.status)
        assertEquals("hello", response.bodyText)
        assertEquals("yes", response.header("X-Test"))
    }

    @Test
    fun sendsMethodAndBody() = withServer { baseUrl ->
        val transport = UrlConnectionTransport(allowInsecureLoopback = true)
        val response = runBlocking {
            transport.execute(
                HttpRequest(
                    method = "PUT",
                    url = "$baseUrl/echo",
                    headers = mapOf("Content-Type" to "application/json"),
                    body = """{"a":1}""".toByteArray(),
                ),
            )
        }

        assertEquals(200, response.status)
        assertEquals("PUT{\"a\":1}", response.bodyText)
    }

    @Test
    fun returnsNotFoundWithoutThrowing() = withServer { baseUrl ->
        val transport = UrlConnectionTransport(allowInsecureLoopback = true)
        val response = runBlocking { transport.execute(HttpRequest("GET", "$baseUrl/missing")) }
        assertEquals(404, response.status)
        assertEquals(0, response.body.size)
    }

    @Test
    fun refusesPlainHttpToRemoteHosts() {
        val transport = UrlConnectionTransport()
        val error = assertThrows(HttpException::class.java) {
            runBlocking { transport.execute(HttpRequest("GET", "http://example.com/data")) }
        }
        assertTrue(error.message.orEmpty().contains("insecure"))
    }
}

/** Records every request so provider URL/body construction can be asserted. */
internal class RecordingTransport(
    private val responder: (HttpRequest) -> HttpResponse,
) : HttpTransport {

    val requests: MutableList<HttpRequest> = mutableListOf()

    override suspend fun execute(request: HttpRequest): HttpResponse {
        requests += request
        return responder(request)
    }
}
