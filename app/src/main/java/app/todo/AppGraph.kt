package app.todo

import android.content.Context
import app.todo.alarm.Notifier
import app.todo.alarm.ReminderDelivery
import app.todo.alarm.ReminderScheduler
import app.todo.core.ids.UuidIdGenerator
import app.todo.core.net.UrlConnectionTransport
import app.todo.core.time.SystemWallClock
import app.todo.core.time.WallClock
import app.todo.data.db.TodoDao
import app.todo.data.db.TodoDb
import app.todo.data.git.GitContentStore
import app.todo.data.git.GitHubContentStore
import app.todo.data.git.GitLabContentStore
import app.todo.data.git.GitProviderKind
import app.todo.data.git.SyncConfig
import app.todo.data.git.SyncEngine
import app.todo.data.mail.EmailReminderSender
import app.todo.data.mail.SmtpClient
import app.todo.data.prefs.KeystoreSecretCipher
import app.todo.data.prefs.SettingsStore
import app.todo.domain.repo.SqliteTodoRepository
import app.todo.domain.repo.TodoRepository

/**
 * Hand-written dependency container. The app has no DI framework on purpose:
 * one class constructs every singleton in a fixed order, which is enough for an
 * app of this size and keeps the wiring visible.
 */
class AppGraph(context: Context) {

    private val appContext = context.applicationContext

    val clock: WallClock = SystemWallClock
    val settings: SettingsStore = SettingsStore(appContext, KeystoreSecretCipher())

    private val database: TodoDb = TodoDb(appContext)
    private val dao: TodoDao = TodoDao(database)

    val repository: TodoRepository = SqliteTodoRepository(
        dao = dao,
        clock = clock,
        ids = UuidIdGenerator,
        deviceId = settings.deviceId,
    )

    private val transport = UrlConnectionTransport()
    val smtpClient = SmtpClient()
    val emailSender = EmailReminderSender(smtpClient)

    val notifier = Notifier(appContext)
    val reminderScheduler = ReminderScheduler(appContext)
    val reminderDelivery = ReminderDelivery(repository, notifier, settings, emailSender)

    fun syncEngine(config: SyncConfig): SyncEngine = SyncEngine(
        store = contentStore(config.provider),
        repository = repository,
        clock = clock,
        deviceId = settings.deviceId,
    )

    private fun contentStore(kind: GitProviderKind): GitContentStore = when (kind) {
        GitProviderKind.GITHUB, GitProviderKind.GITEA -> GitHubContentStore(transport, kind)
        GitProviderKind.GITLAB -> GitLabContentStore(transport)
    }
}
