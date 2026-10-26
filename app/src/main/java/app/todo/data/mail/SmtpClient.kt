package app.todo.data.mail

import app.todo.core.codec.Base64Codec
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class SmtpSecurity { NONE, STARTTLS, TLS }

data class SmtpConfig(
    val host: String = "",
    val port: Int = 465,
    val security: SmtpSecurity = SmtpSecurity.TLS,
    val username: String = "",
    val password: String = "",
    val from: String = "",
    val to: String = "",
) {
    fun validate(): String? {
        if (host.isBlank()) return "SMTP host is required"
        if (port !in 1..65535) return "SMTP port is invalid"
        if (from.isBlank()) return "Sender address is required"
        if (to.isBlank()) return "Recipient address is required"
        if (username.isBlank() != password.isBlank()) return "Username and password must be set together"
        return null
    }

    val isConfigured: Boolean get() = validate() == null
}

class SmtpException(message: String) : Exception(message)

/** Seam that lets the SMTP conversation be tested against a local socket. */
interface SmtpSocketFactory {
    fun connect(host: String, port: Int, timeoutMs: Int): Socket
    fun upgradeToTls(socket: Socket, host: String, port: Int): Socket
}

object DefaultSmtpSocketFactory : SmtpSocketFactory {
    override fun connect(host: String, port: Int, timeoutMs: Int): Socket =
        Socket().apply {
            connect(InetSocketAddress(host, port), timeoutMs)
            soTimeout = timeoutMs
        }

    override fun upgradeToTls(socket: Socket, host: String, port: Int): Socket {
        val factory = SSLSocketFactory.getDefault() as SSLSocketFactory
        return factory.createSocket(socket, host, port, true).also { (it as SSLSocket).startHandshake() }
    }
}

/**
 * Minimal SMTP client (EHLO / STARTTLS / AUTH LOGIN / AUTH PLAIN / MAIL / RCPT /
 * DATA / QUIT). Written by hand so the app does not ship JavaMail, and because
 * email reminders only ever need "send one plain-text message".
 */
class SmtpClient(
    private val socketFactory: SmtpSocketFactory = DefaultSmtpSocketFactory,
    private val timeoutMs: Int = 20_000,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {

    suspend fun send(config: SmtpConfig, subject: String, body: String, date: java.util.Date = java.util.Date()): Unit =
        withContext(io) {
            config.validate()?.let { throw SmtpException(it) }
            var socket = socketFactory.connect(config.host, config.port, timeoutMs)
            try {
                if (config.security == SmtpSecurity.TLS) {
                    socket = socketFactory.upgradeToTls(socket, config.host, config.port)
                }
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))

                expect(reader, 220, stage = "greeting")
                val capabilities = ehlo(reader, writer)
                if (config.security == SmtpSecurity.STARTTLS) {
                    command(writer, "STARTTLS")
                    expect(reader, 220, stage = "STARTTLS")
                    socket = socketFactory.upgradeToTls(socket, config.host, config.port)
                    // A new socket means new streams: rebuild them and re-introduce ourselves.
                    val tlsReader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val tlsWriter = BufferedWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8))
                    ehlo(tlsReader, tlsWriter)
                    authenticate(config, capabilities, tlsReader, tlsWriter)
                    deliver(config, subject, body, date, tlsReader, tlsWriter)
                    return@withContext
                }
                authenticate(config, capabilities, reader, writer)
                deliver(config, subject, body, date, reader, writer)
            } finally {
                runCatching { socket.close() }
            }
        }

    private fun deliver(
        config: SmtpConfig,
        subject: String,
        body: String,
        date: java.util.Date,
        reader: BufferedReader,
        writer: BufferedWriter,
    ) {
        command(writer, "MAIL FROM:<${config.from}>")
        expect(reader, 250, stage = "MAIL FROM")
        command(writer, "RCPT TO:<${config.to}>")
        expect(reader, 250, 251, stage = "RCPT TO")
        command(writer, "DATA")
        expect(reader, 354, stage = "DATA")
        val message = MimeMessageBuilder.build(config.from, config.to, subject, body, date)
        writeRaw(writer, dotStuff(message) + CRLF + "." + CRLF)
        expect(reader, 250, stage = "message body")
        command(writer, "QUIT")
        // 221 is expected but some relays close the connection first; either is fine.
        runCatching { expect(reader, 221, stage = "QUIT") }
    }

    private fun authenticate(
        config: SmtpConfig,
        capabilities: Set<String>,
        reader: BufferedReader,
        writer: BufferedWriter,
    ) {
        if (config.username.isBlank()) return
        val authLine = capabilities.firstOrNull { it.startsWith("AUTH") }.orEmpty()
        val usePlain = authLine.contains("PLAIN", ignoreCase = true) &&
            !authLine.contains("LOGIN", ignoreCase = true)
        if (usePlain) {
            command(writer, "AUTH PLAIN ${Base64Codec.encode("\u0000${config.username}\u0000${config.password}")}")
            expect(reader, 235, stage = "AUTH PLAIN")
            return
        }
        command(writer, "AUTH LOGIN")
        expect(reader, 334, stage = "AUTH LOGIN")
        command(writer, Base64Codec.encode(config.username))
        expect(reader, 334, stage = "AUTH username")
        command(writer, Base64Codec.encode(config.password))
        expect(reader, 235, stage = "AUTH password")
    }

    private fun ehlo(reader: BufferedReader, writer: BufferedWriter): Set<String> {
        command(writer, "EHLO todo.android")
        val lines = readReply(reader, "EHLO")
        if (lines.first != 250) throw SmtpException("EHLO failed: ${lines.second.joinToString(" ")}")
        return lines.second.drop(1).map { it.trim() }.toSet()
    }

    private fun command(writer: BufferedWriter, line: String) {
        writeRaw(writer, line + CRLF)
    }

    private fun writeRaw(writer: BufferedWriter, text: String) {
        writer.write(text)
        writer.flush()
    }

    private fun expect(reader: BufferedReader, vararg codes: Int, stage: String) {
        val reply = readReply(reader, stage)
        if (reply.first !in codes) {
            throw SmtpException("$stage failed: ${reply.first} ${reply.second.joinToString(" ")}")
        }
    }

    private fun readReply(reader: BufferedReader, stage: String): Pair<Int, List<String>> {
        val lines = ArrayList<String>()
        var code = 0
        while (true) {
            val line = reader.readLine() ?: throw SmtpException("$stage failed: connection closed")
            if (line.length < 3) throw SmtpException("$stage failed: malformed reply '$line'")
            code = line.substring(0, 3).toIntOrNull()
                ?: throw SmtpException("$stage failed: malformed reply '$line'")
            lines += line
            if (line.length == 3 || line[3] != '-') break
        }
        return code to lines
    }

    private fun dotStuff(message: String): String =
        message.split(CRLF).joinToString(CRLF) { if (it.startsWith(".")) ".$it" else it }

    private companion object {
        const val CRLF = "\r\n"
    }
}
