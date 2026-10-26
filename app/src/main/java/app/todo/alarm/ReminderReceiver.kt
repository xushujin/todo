package app.todo.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.todo.TodoApp
import app.todo.domain.model.ReminderChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Delivers one reminder. `goAsync()` keeps the process alive while the email is
 * being sent over SMTP; if the process is killed in between the alarm is already
 * consumed, which is why the sync/email settings screen warns that email delivery
 * needs a network connection at the reminder time.
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND) return
        val todoId = intent.getStringExtra(EXTRA_TODO_ID) ?: return
        val channel = intent.getIntExtra(EXTRA_CHANNEL, ReminderChannels.CLOCK)
        val app = context.applicationContext as? TodoApp ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.graph.reminderDelivery.deliver(todoId, channel)
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_REMIND = "app.todo.action.REMIND"
        const val EXTRA_TODO_ID = "todoId"
        const val EXTRA_CHANNEL = "channel"
    }
}
