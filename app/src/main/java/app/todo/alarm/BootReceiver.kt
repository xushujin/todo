package app.todo.alarm

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.todo.TodoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Alarms do not survive a reboot, a package update or a manual clock change, so the
 * whole reminder set is re-armed from the database whenever the system tells us one
 * of those happened.
 */
// The intent-filter only lists protected system broadcasts (boot, package replace,
// time change), which no third-party app can send, so receiving them is safe.
@SuppressLint("UnsafeProtectedBroadcastReceiver")
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as? TodoApp ?: return
        val pendingResult = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val upcoming = app.graph.repository.upcomingReminders()
                app.graph.reminderScheduler.rescheduleAll(upcoming)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
