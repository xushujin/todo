package app.todo.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import app.todo.MainActivity
import app.todo.domain.model.ReminderChannels
import app.todo.domain.model.TodoItem

/**
 * Schedules the two reminder channels independently:
 *  - clock → [AlarmManager.setAlarmClock], so the system shows it as the next alarm
 *    and it survives Doze;
 *  - email → an exact alarm whose receiver sends the message.
 *
 * On Android 12+ the exact-alarm permission can be revoked by the user. When it is
 * missing we fall back to [AlarmManager.setAndAllowWhileIdle] and the UI says so,
 * instead of silently dropping the reminder.
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager: AlarmManager? = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager?.canScheduleExactAlarms() == true
        } else {
            true
        }

    fun schedule(item: TodoItem) {
        cancel(item.id)
        val remindAt = item.remindAt ?: return
        if (item.done || item.isDeleted) return
        if (remindAt <= System.currentTimeMillis()) return
        if (ReminderChannels.has(item.reminderChannels, ReminderChannels.CLOCK)) {
            scheduleClock(item, remindAt)
        }
        if (ReminderChannels.has(item.reminderChannels, ReminderChannels.EMAIL)) {
            scheduleEmail(item, remindAt)
        }
    }

    fun cancel(itemId: String) {
        val manager = alarmManager ?: return
        pendingIntent(itemId, ReminderChannels.CLOCK, PendingIntent.FLAG_NO_CREATE)?.let(manager::cancel)
        pendingIntent(itemId, ReminderChannels.EMAIL, PendingIntent.FLAG_NO_CREATE)?.let(manager::cancel)
    }

    fun rescheduleAll(items: List<TodoItem>) {
        items.forEach(::schedule)
    }

    private fun scheduleClock(item: TodoItem, remindAt: Long) {
        val manager = alarmManager ?: return
        val operation = pendingIntent(itemId = item.id, channel = ReminderChannels.CLOCK, flags = FLAG_UPDATE) ?: return
        val showIntent = PendingIntent.getActivity(
            context,
            item.id.hashCode(),
            Intent(context, MainActivity::class.java),
            FLAG_UPDATE,
        )
        if (canScheduleExactAlarms()) {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(remindAt, showIntent), operation)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, operation)
        }
    }

    private fun scheduleEmail(item: TodoItem, remindAt: Long) {
        val manager = alarmManager ?: return
        val operation = pendingIntent(itemId = item.id, channel = ReminderChannels.EMAIL, flags = FLAG_UPDATE) ?: return
        if (canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, operation)
        } else {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, remindAt, operation)
        }
    }

    private fun pendingIntent(itemId: String, channel: Int, flags: Int): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_REMIND
            putExtra(ReminderReceiver.EXTRA_TODO_ID, itemId)
            putExtra(ReminderReceiver.EXTRA_CHANNEL, channel)
        }
        val requestCode = when (channel) {
            ReminderChannels.CLOCK -> itemId.hashCode()
            else -> itemId.hashCode() xor 0x1F1F1F
        }
        return PendingIntent.getBroadcast(context, requestCode, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    private companion object {
        const val FLAG_UPDATE = PendingIntent.FLAG_UPDATE_CURRENT
    }
}
