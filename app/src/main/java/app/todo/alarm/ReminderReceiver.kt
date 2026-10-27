package app.todo.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import app.todo.TodoApp
import app.todo.domain.model.ReminderChannels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Delivers one reminder.
 *
 * `goAsync()` only keeps the *process* alive — it does not keep the CPU awake. The
 * wake lock the system holds for a broadcast is released the moment `onReceive()`
 * returns, so an SMTP send started from a coroutine could stall mid-flight once the
 * screen went off: a 10:55 reminder only left the outbox when the user turned the
 * screen on at 11:02. We therefore hold our own partial wake lock for the duration of
 * the delivery (with a timeout so a wedged socket can never pin the CPU forever).
 */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REMIND) return
        val todoId = intent.getStringExtra(EXTRA_TODO_ID) ?: return
        val channel = intent.getIntExtra(EXTRA_CHANNEL, ReminderChannels.CLOCK)
        val app = context.applicationContext as? TodoApp ?: return

        val wakeLock = context.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
            ?.apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }

        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                app.graph.reminderDelivery.deliver(todoId, channel)
            } finally {
                if (wakeLock?.isHeld == true) wakeLock.release()
                pendingResult.finish()
            }
        }
    }

    companion object {
        const val ACTION_REMIND = "app.todo.action.REMIND"
        const val EXTRA_TODO_ID = "todoId"
        const val EXTRA_CHANNEL = "channel"

        private const val WAKE_LOCK_TAG = "todo:reminder"
        private const val WAKE_LOCK_TIMEOUT_MS = 60_000L
    }
}
