package app.todo.alarm

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import app.todo.TodoApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Alarms do not survive a reboot, a package update or a manual clock change, so the
 * whole reminder set is re-armed from the database whenever the system tells us one
 * of those happened.
 *
 * Same wake-lock rule as [ReminderReceiver]: the database read + re-arm happens after
 * `onReceive()` returns, so it needs its own lock to finish while the device sleeps.
 */
// The intent-filter only lists protected system broadcasts (boot, package replace,
// time change), which no third-party app can send, so receiving them is safe.
@SuppressLint("UnsafeProtectedBroadcastReceiver")
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
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
                val upcoming = app.graph.repository.upcomingReminders()
                app.graph.reminderScheduler.rescheduleAll(upcoming)
            } finally {
                if (wakeLock?.isHeld == true) wakeLock.release()
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val WAKE_LOCK_TAG = "todo:boot-reschedule"
        const val WAKE_LOCK_TIMEOUT_MS = 30_000L
    }
}
