package app.todo.alarm

import app.todo.data.mail.EmailReminderSender
import app.todo.data.prefs.SettingsStore
import app.todo.domain.model.ReminderChannels
import app.todo.domain.repo.TodoRepository

/**
 * Shared by the alarm receiver and the "send test email" button: one place decides
 * what a reminder actually does.
 */
class ReminderDelivery(
    private val repository: TodoRepository,
    private val notifier: Notifier,
    private val settings: SettingsStore,
    private val emailSender: EmailReminderSender,
) {

    suspend fun deliver(todoId: String, channel: Int) {
        val item = repository.item(todoId) ?: return
        if (item.isDeleted || item.done) return
        val listName = repository.list(item.listId)?.name.orEmpty()
        when {
            ReminderChannels.has(channel, ReminderChannels.CLOCK) -> notifier.notifyReminder(item, listName)
            ReminderChannels.has(channel, ReminderChannels.EMAIL) -> {
                val config = settings.emailConfig.value ?: return
                runCatching { emailSender.send(config, item, listName) }
            }
        }
    }
}
