package app.todo.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import app.todo.core.ids.DeviceIdFactory
import app.todo.core.json.JsonValue
import app.todo.data.git.GitProviderKind
import app.todo.data.git.SyncConfig
import app.todo.data.mail.SmtpConfig
import app.todo.data.mail.SmtpSecurity
import app.todo.domain.model.TodoSort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single source of truth for every user setting.
 *
 * UI reads the exposed [StateFlow]s and calls the setters; background components
 * (alarm receiver, sync) read the same flows. There is no second copy of any
 * setting anywhere else in the app.
 *
 * Secrets (git token, SMTP password) are stored encrypted by [SecretCipher].
 */
class SettingsStore(context: Context, private val cipher: SecretCipher) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    val deviceId: String = prefs.getString(KEY_DEVICE_ID, null)
        ?: DeviceIdFactory.newDeviceId().also { prefs.edit { putString(KEY_DEVICE_ID, it) } }

    private val _themeId = MutableStateFlow(prefs.getString(KEY_THEME, DEFAULT_THEME) ?: DEFAULT_THEME)
    val themeId: StateFlow<String> = _themeId.asStateFlow()

    private val _sort = MutableStateFlow(
        prefs.getString(KEY_SORT, null)?.let { name ->
            TodoSort.entries.firstOrNull { it.name == name }
        } ?: TodoSort.DUE,
    )
    val sort: StateFlow<TodoSort> = _sort.asStateFlow()

    private val _showDone = MutableStateFlow(prefs.getBoolean(KEY_SHOW_DONE, false))
    val showDone: StateFlow<Boolean> = _showDone.asStateFlow()

    private val _biometricEnabled = MutableStateFlow(prefs.getBoolean(KEY_BIOMETRIC, false))
    val biometricEnabled: StateFlow<Boolean> = _biometricEnabled.asStateFlow()

    private val _autoSyncEnabled = MutableStateFlow(prefs.getBoolean(KEY_AUTO_SYNC, true))
    val autoSyncEnabled: StateFlow<Boolean> = _autoSyncEnabled.asStateFlow()

    private val _lockTimeoutSeconds =
        MutableStateFlow(prefs.getInt(KEY_LOCK_TIMEOUT, DEFAULT_LOCK_TIMEOUT_SECONDS))
    val lockTimeoutSeconds: StateFlow<Int> = _lockTimeoutSeconds.asStateFlow()

    private val _lastSyncAt = MutableStateFlow(prefs.getLong(KEY_LAST_SYNC, 0L))
    val lastSyncAt: StateFlow<Long> = _lastSyncAt.asStateFlow()

    private val _lastUpdateCheckAt = MutableStateFlow(prefs.getLong(KEY_LAST_UPDATE_CHECK, 0L))
    val lastUpdateCheckAt: StateFlow<Long> = _lastUpdateCheckAt.asStateFlow()

    private val _syncConfig = MutableStateFlow(loadSyncConfig())
    val syncConfig: StateFlow<SyncConfig?> = _syncConfig.asStateFlow()

    private val _emailConfig = MutableStateFlow(loadEmailConfig())
    val emailConfig: StateFlow<SmtpConfig?> = _emailConfig.asStateFlow()

    fun setThemeId(id: String) {
        prefs.edit { putString(KEY_THEME, id) }
        _themeId.value = id
    }

    fun setSort(sort: TodoSort) {
        prefs.edit { putString(KEY_SORT, sort.name) }
        _sort.value = sort
    }

    fun setShowDone(show: Boolean) {
        prefs.edit { putBoolean(KEY_SHOW_DONE, show) }
        _showDone.value = show
    }

    fun setBiometricEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_BIOMETRIC, enabled) }
        _biometricEnabled.value = enabled
    }

    fun setAutoSyncEnabled(enabled: Boolean) {
        prefs.edit { putBoolean(KEY_AUTO_SYNC, enabled) }
        _autoSyncEnabled.value = enabled
    }

    fun setLockTimeoutSeconds(seconds: Int) {
        prefs.edit { putInt(KEY_LOCK_TIMEOUT, seconds) }
        _lockTimeoutSeconds.value = seconds
    }

    fun setLastSyncAt(value: Long) {
        prefs.edit { putLong(KEY_LAST_SYNC, value) }
        _lastSyncAt.value = value
    }

    fun setLastUpdateCheckAt(value: Long) {
        prefs.edit { putLong(KEY_LAST_UPDATE_CHECK, value) }
        _lastUpdateCheckAt.value = value
    }

    fun saveSyncConfig(config: SyncConfig?) {
        if (config == null) {
            prefs.edit { remove(KEY_SYNC) }
            _syncConfig.value = null
            return
        }
        val json = JsonValue.obj(
            "provider" to JsonValue.of(config.provider.name),
            "baseUrl" to JsonValue.of(config.baseUrl),
            "owner" to JsonValue.of(config.owner),
            "repo" to JsonValue.of(config.repo),
            "branch" to JsonValue.of(config.branch),
            "path" to JsonValue.of(config.path),
            "token" to JsonValue.of(cipher.encrypt(config.token)),
        ).toJson()
        prefs.edit { putString(KEY_SYNC, json) }
        _syncConfig.value = config
    }

    fun saveEmailConfig(config: SmtpConfig?) {
        if (config == null) {
            prefs.edit { remove(KEY_EMAIL) }
            _emailConfig.value = null
            return
        }
        val json = JsonValue.obj(
            "host" to JsonValue.of(config.host),
            "port" to JsonValue.of(config.port),
            "security" to JsonValue.of(config.security.name),
            "username" to JsonValue.of(config.username),
            "password" to JsonValue.of(cipher.encrypt(config.password)),
            "from" to JsonValue.of(config.from),
            "to" to JsonValue.of(config.to),
        ).toJson()
        prefs.edit { putString(KEY_EMAIL, json) }
        _emailConfig.value = config
    }

    private fun loadSyncConfig(): SyncConfig? {
        val raw = prefs.getString(KEY_SYNC, null) ?: return null
        val root = JsonValue.parseOrNull(raw) as? JsonValue.Obj ?: return null
        return SyncConfig(
            provider = GitProviderKind.fromName(root.str("provider")),
            baseUrl = root.str("baseUrl").orEmpty(),
            owner = root.str("owner").orEmpty(),
            repo = root.str("repo").orEmpty(),
            branch = root.str("branch") ?: "main",
            path = root.str("path") ?: "data/todos.json",
            token = cipher.decryptOrNull(root.str("token")).orEmpty(),
        )
    }

    private fun loadEmailConfig(): SmtpConfig? {
        val raw = prefs.getString(KEY_EMAIL, null) ?: return null
        val root = JsonValue.parseOrNull(raw) as? JsonValue.Obj ?: return null
        return SmtpConfig(
            host = root.str("host").orEmpty(),
            port = root.int("port") ?: 465,
            security = SmtpSecurity.entries.firstOrNull { it.name == root.str("security") } ?: SmtpSecurity.TLS,
            username = root.str("username").orEmpty(),
            password = cipher.decryptOrNull(root.str("password")).orEmpty(),
            from = root.str("from").orEmpty(),
            to = root.str("to").orEmpty(),
        )
    }

    companion object {
        const val DEFAULT_THEME = "cloud"

        /** 0 = lock immediately; otherwise the grace period in seconds. */
        const val DEFAULT_LOCK_TIMEOUT_SECONDS = 60

        private const val FILE_NAME = "todo.settings"
        private const val KEY_DEVICE_ID = "deviceId"
        private const val KEY_THEME = "themeId"
        private const val KEY_SORT = "sort"
        private const val KEY_SHOW_DONE = "showDone"
        private const val KEY_BIOMETRIC = "biometricEnabled"
        private const val KEY_AUTO_SYNC = "autoSyncEnabled"
        private const val KEY_LOCK_TIMEOUT = "lockTimeoutSeconds"
        private const val KEY_LAST_SYNC = "lastSyncAt"
        private const val KEY_LAST_UPDATE_CHECK = "lastUpdateCheckAt"
        private const val KEY_SYNC = "syncConfig"
        private const val KEY_EMAIL = "emailConfig"
    }
}
