package app.todo.data.mail

import app.todo.core.codec.Base64Codec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * RFC 5322 / MIME text message builder.
 *
 * Header values are sanitised against CR/LF injection, the subject is RFC 2047
 * encoded when it is not plain ASCII (Chinese subjects), and the body is base64
 * with CRLF line endings so SMTP dot-stuffing can never corrupt it.
 */
object MimeMessageBuilder {

    fun build(
        from: String,
        to: String,
        subject: String,
        body: String,
        date: Date = Date(),
    ): String {
        val safeFrom = sanitizeAddress(from)
        val safeTo = sanitizeAddress(to)
        val safeSubject = sanitizeHeader(subject)
        val headers = buildString {
            append("From: ").append(safeFrom).append(CRLF)
            append("To: ").append(safeTo).append(CRLF)
            append("Subject: ").append(encodeHeaderValue(safeSubject)).append(CRLF)
            append("Date: ").append(formatDate(date)).append(CRLF)
            append("MIME-Version: 1.0").append(CRLF)
            append("Content-Type: text/plain; charset=UTF-8").append(CRLF)
            append("Content-Transfer-Encoding: base64").append(CRLF)
            append(CRLF)
        }
        return headers + wrapBase64(Base64Codec.encode(body.toByteArray(Charsets.UTF_8)))
    }

    fun formatDate(date: Date): String =
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(date)

    fun encodeHeaderValue(value: String): String =
        if (value.all { it.code in 32..126 }) value
        else "=?UTF-8?B?${Base64Codec.encode(value.toByteArray(Charsets.UTF_8))}?="

    private fun wrapBase64(encoded: String): String =
        encoded.chunked(76).joinToString(CRLF)

    private fun sanitizeAddress(value: String): String = sanitizeHeader(value).trim()

    private fun sanitizeHeader(value: String): String = value.replace("\r", " ").replace("\n", " ")

    private const val CRLF = "\r\n"
}
