package app.todo.alarm

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import app.todo.MainActivity
import app.todo.R
import app.todo.core.platform.AppPermissions
import app.todo.core.time.TimeText
import app.todo.domain.model.TodoItem

class Notifier(private val context: Context) {

    fun ensureChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_REMINDERS) != null) return
        val channel = NotificationChannel(
            CHANNEL_REMINDERS,
            context.getString(R.string.notification_channel_reminders),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_reminders_desc)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun canPostNotifications(): Boolean =
        context.checkSelfPermission(AppPermissions.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun notifyReminder(item: TodoItem, listName: String) {
        if (!canPostNotifications()) return
        ensureChannels()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val detail = buildString {
            append(listName)
            append(" · ")
            append(context.getString(priorityLabel(item.priority)))
            if (item.dueAt != null) {
                append(" · ")
                append(TimeText.formatDateTime(item.dueAt))
            }
            if (item.notes.isNotBlank()) {
                append("\n")
                append(item.notes)
            }
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_TODO_ID, item.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL_REMINDERS)
            .setSmallIcon(R.drawable.ic_stat_todo)
            .setContentTitle(item.title)
            .setContentText(detail.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(detail))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .build()
        manager.notify(item.id.hashCode(), notification)
    }

    private fun priorityLabel(priority: app.todo.domain.model.Priority): Int = when (priority) {
        app.todo.domain.model.Priority.LOW -> R.string.priority_low
        app.todo.domain.model.Priority.NORMAL -> R.string.priority_normal
        app.todo.domain.model.Priority.HIGH -> R.string.priority_high
        app.todo.domain.model.Priority.URGENT -> R.string.priority_urgent
    }

    companion object {
        const val CHANNEL_REMINDERS = "todo.reminders"
    }
}
