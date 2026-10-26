package app.todo.data

import app.todo.core.codec.Base64Codec
import app.todo.data.mail.MimeMessageBuilder
import app.todo.data.mail.SmtpClient
import app.todo.data.mail.SmtpConfig
import app.todo.data.mail.SmtpException
import app.todo.data.mail.SmtpSecurity
import app.todo.data.mail.SmtpSocketFactory
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeMessageBuilderTest {

    @Test
    fun encodesNonAsciiSubjectAsRfc2047Word() {
        val message = MimeMessageBuilder.build("from@example.com", "to@example.com", "待办提醒", "正文", Date(0))
        val subjectLine = message.lineSequence().first { it.startsWith("Subject: ") }
        assertTrue(subjectLine, subjectLine.contains("=?UTF-8?B?"))
        val encoded = subjectLine.removePrefix("Subject: =?UTF-8?B?").removeSuffix("?=")
        assertEquals("待办提醒", Base64Codec.decodeToString(encoded))
    }

    @Test
    fun stripsHeaderInjectionAttempts() {
        val message = MimeMessageBuilder.build(
            from = "from@example.com",
            to = "to@example.com\r\nBcc: attacker@example.com",
            subject = "hello\r\nBcc: attacker@example.com",
            body = "body",
        )
        val headers = message.substringBefore("\r\n\r\n").split("\r\n")
        assertTrue("no header may start with Bcc", headers.none { it.startsWith("Bcc:") })
        assertEquals(7, headers.size)
        assertTrue(headers.any { it == "Subject: hello  Bcc: attacker@example.com" })
    }

    @Test
    fun bodyIsBase64WrappedAt76Characters() {
        val body = "任务".repeat(100)
        val message = MimeMessageBuilder.build("a@b.c", "d@e.f", "subject", body)
        val payload = message.substringAfter("\r\n\r\n")
        assertTrue(payload.split("\r\n").all { it.length <= 76 })
        assertEquals(body, Base64Codec.decodeToString(payload))
        assertTrue(message.contains("Content-Transfer-Encoding: base64"))
        assertTrue(message.contains("charset=UTF-8"))
    }

    @Test
    fun dateIsRfc5322Compatible() {
        val message = MimeMessageBuilder.build("a@b.c", "d@e.f", "s", "b", Date(0))
        val dateLine = message.lineSequence().first { it.startsWith("Date: ") }
        assertTrue(
            dateLine,
            Regex("""^Date: [A-Z][a-z]{2}, \d{2} [A-Z][a-z]{2} \d{4} \d{2}:\d{2}:\d{2} [+-]\d{4}$""")
                .matches(dateLine),
        )
    }
}

/**
 * The SMTP conversation is verified against a real socket speaking the protocol,
 * not against a mock of our own client.
 */
class SmtpClientTest {

    private class FakeSmtpServer(private val rejectAuth: Boolean = false) : AutoCloseable {

        private val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val finished = CountDownLatch(1)
        val commands: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val message = StringBuilder()

        val port: Int get() = serverSocket.localPort

        private val thread = Thread {
            try {
                serverSocket.accept().use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                    fun reply(line: String) {
                        writer.write(line + "\r\n")
                        writer.flush()
                    }

                    reply("220 fake ESMTP ready")
                    var dataMode = false
                    var authStep = 0
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (dataMode) {
                            if (line == ".") {
                                dataMode = false
                                reply("250 2.0.0 queued")
                            } else {
                                message.append(line).append("\r\n")
                            }
                            continue
                        }
                        commands += line
                        when {
                            line.startsWith("EHLO") -> {
                                reply("250-fake")
                                reply("250-AUTH LOGIN PLAIN")
                                reply("250 8BITMIME")
                            }
                            line.startsWith("AUTH LOGIN") -> {
                                authStep = 1
                                reply("334 VXNlcm5hbWU6")
                            }
                            line.startsWith("MAIL FROM") -> reply("250 2.1.0 OK")
                            line.startsWith("RCPT TO") -> reply("250 2.1.5 OK")
                            line == "DATA" -> {
                                dataMode = true
                                reply("354 End data with <CR><LF>.<CR><LF>")
                            }
                            line == "QUIT" -> {
                                reply("221 2.0.0 Bye")
                                break
                            }
                            authStep == 1 -> {
                                authStep = 2
                                reply("334 UGFzc3dvcmQ6")
                            }
                            authStep == 2 -> {
                                authStep = 3
                                if (rejectAuth) reply("535 5.7.8 Authentication credentials invalid") else reply("235 2.7.0 Accepted")
                            }
                            else -> reply("250 2.0.0 OK")
                        }
                    }
                }
            } catch (_: Exception) {
                // The test asserts on the recorded conversation.
            } finally {
                finished.countDown()
            }
        }.apply {
            isDaemon = true
            start()
        }

        fun await() {
            finished.await(5, TimeUnit.SECONDS)
        }

        override fun close() {
            serverSocket.close()
        }
    }

    private fun clientFor(server: FakeSmtpServer): SmtpClient {
        val factory = object : SmtpSocketFactory {
            override fun connect(host: String, port: Int, timeoutMs: Int): Socket =
                Socket("127.0.0.1", server.port).apply { soTimeout = timeoutMs }

            override fun upgradeToTls(socket: Socket, host: String, port: Int): Socket =
                throw UnsupportedOperationException("TLS is not part of this test")
        }
        return SmtpClient(socketFactory = factory, timeoutMs = 5_000)
    }

    private fun config(server: FakeSmtpServer, withAuth: Boolean = true) = SmtpConfig(
        host = "127.0.0.1",
        port = server.port,
        security = SmtpSecurity.NONE,
        username = if (withAuth) "user@example.com" else "",
        password = if (withAuth) "s3cret" else "",
        from = "from@example.com",
        to = "to@example.com",
    )

    @Test
    fun sendsMessageThroughARealSmtpConversation() {
        FakeSmtpServer().use { server ->
            runBlocking { clientFor(server).send(config(server), "待办提醒", "记得买牛奶", Date(0)) }
            server.await()

            assertTrue(server.commands.first().startsWith("EHLO "))
            assertTrue(server.commands.contains("AUTH LOGIN"))
            assertTrue(server.commands.contains(Base64Codec.encode("user@example.com")))
            assertTrue(server.commands.contains(Base64Codec.encode("s3cret")))
            assertTrue(server.commands.contains("MAIL FROM:<from@example.com>"))
            assertTrue(server.commands.contains("RCPT TO:<to@example.com>"))
            assertTrue(server.commands.contains("DATA"))
            assertTrue(server.commands.contains("QUIT"))

            val received = server.message.toString()
            assertTrue(received.contains("From: from@example.com"))
            assertTrue(received.contains("To: to@example.com"))
            assertTrue(received.contains("Subject: =?UTF-8?B?"))
            val payload = received.substringAfter("\r\n\r\n").trim()
            assertEquals("记得买牛奶", Base64Codec.decodeToString(payload))
        }
    }

    @Test
    fun skipsAuthenticationWhenNoCredentialsAreConfigured() {
        FakeSmtpServer().use { server ->
            runBlocking { clientFor(server).send(config(server, withAuth = false), "s", "b") }
            server.await()
            assertFalse(server.commands.any { it.startsWith("AUTH") })
            assertTrue(server.commands.contains("MAIL FROM:<from@example.com>"))
        }
    }

    @Test
    fun surfacesAuthenticationFailures() {
        FakeSmtpServer(rejectAuth = true).use { server ->
            val error = assertThrows(SmtpException::class.java) {
                runBlocking { clientFor(server).send(config(server), "s", "b") }
            }
            assertTrue(error.message.orEmpty().contains("AUTH"))
        }
    }

    @Test
    fun validatesConfigurationBeforeConnecting() {
        val error = assertThrows(SmtpException::class.java) {
            runBlocking { SmtpClient().send(SmtpConfig(host = ""), "s", "b") }
        }
        assertTrue(error.message.orEmpty().contains("host"))
    }
}
