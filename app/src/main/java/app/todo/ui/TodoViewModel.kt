package app.todo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.todo.AppGraph
import app.todo.BuildConfig
import app.todo.core.net.HttpException
import app.todo.data.git.GitErrorKind
import app.todo.data.git.GitException
import app.todo.data.mail.SmtpAuthException
import app.todo.data.mail.SmtpConfig
import app.todo.data.mail.SmtpException
import app.todo.data.prefs.SettingsStore
import app.todo.data.update.UpdateInfo
import app.todo.domain.model.HomeFilter
import app.todo.domain.model.HomeMode
import app.todo.domain.model.TodoItem
import app.todo.domain.model.TodoList
import app.todo.domain.model.TodoQuery
import app.todo.domain.model.TodoSort
import app.todo.domain.repo.TodoDraft
import app.todo.domain.sync.SyncPolicy
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.launch

sealed interface SyncOutcome {
    data object Idle : SyncOutcome
    data object Running : SyncOutcome
    data class Success(val pulled: Int, val pushed: Int, val conflicts: Int) : SyncOutcome
    data class Failure(val kind: GitErrorKind, val detail: String) : SyncOutcome
}

enum class MailErrorKind { AUTH, CONFIG, NETWORK, UNKNOWN }

sealed interface MailTestResult {
    data object Sent : MailTestResult
    data class Failed(val kind: MailErrorKind, val detail: String) : MailTestResult
}

sealed interface AutoSyncState {
    data object Idle : AutoSyncState
    data object Syncing : AutoSyncState
    data class Failed(val kind: GitErrorKind) : AutoSyncState
}

sealed interface ConnectionOutcome {
    data class Ok(val fileExists: Boolean, val defaultBranch: String?) : ConnectionOutcome
    data class Failed(val kind: GitErrorKind, val detail: String) : ConnectionOutcome
}

/** In-app updater state. [Failed.info] is kept so the user can retry a download. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Ready(val info: UpdateInfo) : UpdateState
    data class Failed(val message: String, val info: UpdateInfo? = null) : UpdateState
}

class TodoViewModel(private val graph: AppGraph) : ViewModel() {

    private val repository = graph.repository
    val settings: SettingsStore = graph.settings

    val lists: StateFlow<List<TodoList>> = repository.observeLists()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val items: StateFlow<List<TodoItem>> = repository.observeItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _filter = MutableStateFlow(HomeFilter())
    val filter: StateFlow<HomeFilter> = _filter.asStateFlow()

    val visibleItems: StateFlow<List<TodoItem>> = combine(
        items,
        _filter,
        settings.showDone,
        settings.sort,
    ) { items, filter, showDone, sort ->
        TodoQuery.visible(items, filter, showDone, sort, System.currentTimeMillis())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch { repository.ensureInbox(graph.inboxName()) }
    }

    private val _syncOutcome = MutableStateFlow<SyncOutcome>(SyncOutcome.Idle)
    val syncOutcome: StateFlow<SyncOutcome> = _syncOutcome.asStateFlow()

    private val _autoSyncState = MutableStateFlow<AutoSyncState>(AutoSyncState.Idle)
    val autoSyncState: StateFlow<AutoSyncState> = _autoSyncState.asStateFlow()

    private val _updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val updateState: StateFlow<UpdateState> = _updateState.asStateFlow()

    /** Separate from [updateState] so dismissing the dialog does not forget the release. */
    private val _updateDialogVisible = MutableStateFlow(false)
    val updateDialogVisible: StateFlow<Boolean> = _updateDialogVisible.asStateFlow()

    private var updateJob: Job? = null
    private var downloadedApk: File? = null

    /** Serialises manual and automatic syncs so two runs never interleave. */
    private val syncMutex = Mutex()
    private var debounceJob: Job? = null
    private var lastSyncAttemptAt = 0L
    private var foreground = false

    fun setMode(mode: HomeMode) {
        _filter.value = _filter.value.copy(mode = mode)
    }

    fun setListFilter(listId: String?) {
        _filter.value = _filter.value.copy(listId = listId)
    }

    fun setSort(sort: TodoSort) = settings.setSort(sort)

    fun setShowDone(show: Boolean) = settings.setShowDone(show)

    fun setTheme(id: String) = settings.setThemeId(id)

    fun setBiometric(enabled: Boolean) = settings.setBiometricEnabled(enabled)

    fun setLockTimeout(seconds: Int) = settings.setLockTimeoutSeconds(seconds)

    fun canScheduleExactAlarms(): Boolean = graph.reminderScheduler.canScheduleExactAlarms()

    fun saveItem(draft: TodoDraft, onSaved: (TodoItem) -> Unit = {}) {
        viewModelScope.launch {
            val item = repository.saveItem(draft)
            graph.reminderScheduler.schedule(item)
            onSaved(item)
            scheduleAutoSync()
        }
    }

    fun toggleDone(item: TodoItem) {
        viewModelScope.launch {
            val updated = repository.setDone(item.id, !item.done) ?: return@launch
            if (updated.done) graph.reminderScheduler.cancel(updated.id) else graph.reminderScheduler.schedule(updated)
            scheduleAutoSync()
        }
    }

    fun deleteItem(item: TodoItem) = deleteItemById(item.id)

    fun deleteItemById(id: String) {
        viewModelScope.launch {
            graph.reminderScheduler.cancel(id)
            repository.deleteItem(id)
            scheduleAutoSync()
        }
    }

    fun togglePinned(item: TodoItem) {
        viewModelScope.launch {
            val draft = TodoDraft(
                id = item.id,
                listId = item.listId,
                title = item.title,
                notes = item.notes,
                priority = item.priority,
                dueAt = item.dueAt,
                remindAt = item.remindAt,
                reminderChannels = item.reminderChannels,
                pinned = !item.pinned,
                done = item.done,
            )
            val saved = repository.saveItem(draft)
            graph.reminderScheduler.schedule(saved)
            scheduleAutoSync()
        }
    }

    fun saveList(id: String?, name: String, colorArgb: Long) {
        viewModelScope.launch {
            repository.saveList(id, name, colorArgb)
            scheduleAutoSync()
        }
    }

    fun deleteList(list: TodoList) {
        viewModelScope.launch {
            items.value.filter { it.listId == list.id }.forEach { graph.reminderScheduler.cancel(it.id) }
            repository.deleteList(list.id)
            scheduleAutoSync()
        }
    }

    fun purgeDeleted() {
        viewModelScope.launch {
            repository.purgeDeleted(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        }
    }

    fun saveEmailSettings(config: SmtpConfig) = settings.saveEmailConfig(config.normalized())

    fun saveSyncSettings(config: app.todo.data.git.SyncConfig) =
        settings.saveSyncConfig(config.normalized())

    fun clearSyncOutcome() {
        _syncOutcome.value = SyncOutcome.Idle
    }

    fun sendTestEmail(onResult: (MailTestResult) -> Unit) {
        val config = settings.emailConfig.value?.normalized()
        if (config == null || !config.isConfigured) {
            onResult(MailTestResult.Failed(MailErrorKind.CONFIG, config?.validate().orEmpty()))
            return
        }
        viewModelScope.launch {
            val result = runCatching { graph.emailSender.sendTest(config) }
            onResult(
                result.fold(
                    onSuccess = { MailTestResult.Sent },
                    onFailure = { MailTestResult.Failed(mailKindOf(it), it.message ?: "unknown error") },
                ),
            )
        }
    }

    private fun mailKindOf(error: Throwable): MailErrorKind = when {
        error is SmtpAuthException -> MailErrorKind.AUTH
        error is SmtpException -> MailErrorKind.CONFIG
        else -> MailErrorKind.NETWORK
    }

    fun testSyncConnection(onResult: (ConnectionOutcome) -> Unit) {
        val config = settings.syncConfig.value?.normalized()
        if (config == null || !config.isConfigured) {
            onResult(ConnectionOutcome.Failed(GitErrorKind.INVALID_CONFIG, config?.validate().orEmpty()))
            return
        }
        // A previous sync failure must not stay on screen above the new test result.
        _syncOutcome.value = SyncOutcome.Idle
        viewModelScope.launch {
            val result = runCatching { graph.syncEngine(config).testConnection(config) }
            onResult(
                result.fold(
                    onSuccess = { ConnectionOutcome.Ok(it.fileExists, it.defaultBranch) },
                    onFailure = { ConnectionOutcome.Failed(kindOf(it), it.message ?: "unknown error") },
                ),
            )
        }
    }

    private fun kindOf(error: Throwable): GitErrorKind = when (error) {
        is GitException -> error.kind
        is HttpException -> GitErrorKind.NETWORK
        else -> GitErrorKind.UNKNOWN
    }

    fun setAutoSync(enabled: Boolean) {
        settings.setAutoSyncEnabled(enabled)
        if (!enabled) {
            debounceJob?.cancel()
            _autoSyncState.value = AutoSyncState.Idle
        } else {
            onForeground()
        }
    }

    /** Called when the app becomes visible: pull remote changes, at most once a minute. */
    fun onForeground() {
        foreground = true
        maybeCheckForUpdate()
        if (!settings.autoSyncEnabled.value) return
        if (!SyncPolicy.shouldSyncOnForeground(System.currentTimeMillis(), lastSyncAttemptAt)) return
        viewModelScope.launch { runAutoSync() }
    }

    fun onBackground() {
        foreground = false
    }

    /**
     * Local edits are batched: a burst of changes produces one request, not one per
     * keystroke, and the sync only runs while the app is in front.
     */
    private fun scheduleAutoSync() {
        if (!settings.autoSyncEnabled.value) return
        debounceJob?.cancel()
        debounceJob = viewModelScope.launch {
            delay(SyncPolicy.LOCAL_CHANGE_DEBOUNCE_MS)
            runAutoSync()
        }
    }

    private suspend fun runAutoSync() {
        if (!foreground || !settings.autoSyncEnabled.value) return
        if (_autoSyncState.value == AutoSyncState.Syncing) return
        val config = settings.syncConfig.value?.normalized() ?: return
        if (!config.isConfigured) return

        _autoSyncState.value = AutoSyncState.Syncing
        val result = runCatching {
            syncMutex.withLock { graph.syncEngine(config).sync(config, settings.lastSyncAt.value) }
        }
        lastSyncAttemptAt = System.currentTimeMillis()
        result.onSuccess { report ->
            settings.setLastSyncAt(report.syncedAt)
            _autoSyncState.value = AutoSyncState.Idle
        }.onFailure { error ->
            _autoSyncState.value = AutoSyncState.Failed(kindOf(error))
        }
    }

    fun syncNow() {
        val config = settings.syncConfig.value?.normalized()
        if (config == null || !config.isConfigured) {
            _syncOutcome.value = SyncOutcome.Failure(GitErrorKind.INVALID_CONFIG, config?.validate().orEmpty())
            return
        }
        if (_syncOutcome.value is SyncOutcome.Running) return
        _syncOutcome.value = SyncOutcome.Running
        viewModelScope.launch {
            val result = runCatching {
                syncMutex.withLock { graph.syncEngine(config).sync(config, settings.lastSyncAt.value) }
            }
            lastSyncAttemptAt = System.currentTimeMillis()
            result.onSuccess { report ->
                settings.setLastSyncAt(report.syncedAt)
                _autoSyncState.value = AutoSyncState.Idle
                _syncOutcome.value = SyncOutcome.Success(report.pulled, report.pushed, report.conflicts)
            }.onFailure { error ->
                _syncOutcome.value = SyncOutcome.Failure(
                    kindOf(error),
                    (error as? GitException)?.message ?: error.message ?: "unknown error",
                )
            }
        }
    }

    suspend fun loadItem(id: String): TodoItem? = repository.item(id)

    // ---- in-app update ---------------------------------------------------

    /** Silent once-a-day check; a manual check surfaces failures instead of hiding them. */
    private fun maybeCheckForUpdate() {
        val elapsed = System.currentTimeMillis() - settings.lastUpdateCheckAt.value
        if (elapsed < UPDATE_CHECK_INTERVAL_MS) return
        checkForUpdate(manual = false)
    }

    fun checkForUpdate(manual: Boolean = true) {
        val state = _updateState.value
        if (state is UpdateState.Checking || state is UpdateState.Downloading) return
        _updateState.value = UpdateState.Checking
        viewModelScope.launch {
            val result = runCatching { graph.updateChecker.latest(BuildConfig.VERSION_CODE) }
            settings.setLastUpdateCheckAt(System.currentTimeMillis())
            result.onSuccess { info ->
                if (info == null) {
                    _updateState.value = UpdateState.UpToDate
                } else {
                    _updateState.value = UpdateState.Available(info)
                    _updateDialogVisible.value = true
                }
            }.onFailure { error ->
                _updateState.value = if (manual) {
                    UpdateState.Failed(error.message ?: "未知错误")
                } else {
                    UpdateState.Idle
                }
            }
        }
    }

    fun downloadUpdate() {
        val info = when (val state = _updateState.value) {
            is UpdateState.Available -> state.info
            is UpdateState.Ready -> state.info
            is UpdateState.Failed -> state.info ?: return
            else -> return
        }
        if (updateJob?.isActive == true) return
        _updateState.value = UpdateState.Downloading(info, 0f)
        _updateDialogVisible.value = true
        updateJob = viewModelScope.launch {
            val target = File(graph.updateDir(), "Todo-${info.versionName}.apk")
            val result = runCatching {
                graph.apkDownloader.download(
                    url = info.apkUrl,
                    target = target,
                    expectedSize = info.apkSize,
                    expectedSha256 = info.sha256,
                ) { progress ->
                    _updateState.value = UpdateState.Downloading(info, progress)
                }
            }
            result.onSuccess { file ->
                downloadedApk = file
                _updateState.value = UpdateState.Ready(info)
            }.onFailure { error ->
                downloadedApk = null
                _updateState.value = UpdateState.Failed(error.message ?: "下载失败", info)
            }
        }
    }

    fun installUpdate() {
        val info = (_updateState.value as? UpdateState.Ready)?.info ?: return
        val file = downloadedApk
        if (file == null || !file.exists()) {
            _updateState.value = UpdateState.Failed("安装包已不存在，请重新下载", info)
            return
        }
        if (!graph.apkInstaller.canInstallPackages()) {
            // The system settings page is the only way to grant this; the user comes
            // back and taps install again.
            graph.apkInstaller.openInstallPermissionSettings()
            return
        }
        runCatching { graph.apkInstaller.install(file) }
            .onSuccess { _updateDialogVisible.value = false }
            .onFailure { _updateState.value = UpdateState.Failed(it.message ?: "安装失败", info) }
    }

    fun dismissUpdate() {
        _updateDialogVisible.value = false
    }

    companion object {
        private const val UPDATE_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { TodoViewModel(graph) }
        }
    }
}
