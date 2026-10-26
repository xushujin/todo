package app.todo.data.mail

import app.todo.core.time.TimeText
import app.todo.domain.model.TodoItem

/**
 * Turns a task into the plain-text email that the SMTP client sends. Kept separate
 * from [SmtpClient] so the message body can be unit tested without a socket.
 */
class EmailReminderSender(private val client: SmtpClient) {

    suspend fun send(config: SmtpConfig, item: TodoItem, listName: String) {
        client.send(
            config = config,
            subject = "[Todo] ${item.title}",
            body = composeBody(item, listName),
        )
    }

    suspend fun sendTest(config: SmtpConfig) {
        client.send(
            config = config,
            subject = "[Todo] test email",
            body = "This is a test message from Todo.\n\n" +
                "If you can read this, the SMTP settings work.\n",
        )
    }

    fun composeBody(item: TodoItem, listName: String, now: Long = System.currentTimeMillis()): String = buildString {
        appendLine("Task: ${item.title}")
        appendLine("List: $listName")
        appendLine("Urgency: ${item.priority.wireName}")
        item.dueAt?.let { appendLine("Due: ${TimeText.formatDateTime(it)}") }
        item.remindAt?.let { appendLine("Reminder: ${TimeText.formatDateTime(it)}") }
        if (item.notes.isNotBlank()) {
            appendLine()
            appendLine(item.notes)
        }
        appendLine()
        appendLine("--")
        appendLine("Sent by Todo at ${TimeText.formatDateTime(now)}")
    }
}
