package app.todo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import app.todo.AppGraph
import app.todo.data.git.GitException
import app.todo.data.prefs.SettingsStore
import app.todo.domain.model.HomeFilter
import app.todo.domain.model.HomeMode
import app.todo.domain.model.TodoItem
import app.todo.domain.model.TodoList
import app.todo.domain.model.TodoQuery
import app.todo.domain.model.TodoSort
import app.todo.domain.repo.TodoDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface SyncOutcome {
    data object Idle : SyncOutcome
    data object Running : SyncOutcome
    data class Success(val pulled: Int, val pushed: Int, val conflicts: Int) : SyncOutcome
    data class Failure(val message: String) : SyncOutcome
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
        }
    }

    fun toggleDone(item: TodoItem) {
        viewModelScope.launch {
            val updated = repository.setDone(item.id, !item.done) ?: return@launch
            if (updated.done) graph.reminderScheduler.cancel(updated.id) else graph.reminderScheduler.schedule(updated)
        }
    }

    fun deleteItem(item: TodoItem) = deleteItemById(item.id)

    fun deleteItemById(id: String) {
        viewModelScope.launch {
            graph.reminderScheduler.cancel(id)
            repository.deleteItem(id)
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
        }
    }

    fun saveList(id: String?, name: String, colorArgb: Long) {
        viewModelScope.launch { repository.saveList(id, name, colorArgb) }
    }

    fun deleteList(list: TodoList) {
        viewModelScope.launch {
            items.value.filter { it.listId == list.id }.forEach { graph.reminderScheduler.cancel(it.id) }
            repository.deleteList(list.id)
        }
    }

    fun purgeDeleted() {
        viewModelScope.launch {
            repository.purgeDeleted(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000)
        }
    }

    fun saveEmailSettings(config: app.todo.data.mail.SmtpConfig) = settings.saveEmailConfig(config)

    fun saveSyncSettings(config: app.todo.data.git.SyncConfig) = settings.saveSyncConfig(config)

    fun sendTestEmail(onResult: (String?) -> Unit) {
        val config = settings.emailConfig.value
        if (config == null || !config.isConfigured) {
            onResult("incomplete")
            return
        }
        viewModelScope.launch {
            onResult(runCatching { graph.emailSender.sendTest(config) }.exceptionOrNull()?.message)
        }
    }

    fun testSyncConnection(onResult: (String?) -> Unit) {
        val config = settings.syncConfig.value
        if (config == null || !config.isConfigured) {
            onResult("not-configured")
            return
        }
        viewModelScope.launch {
            onResult(
                runCatching { graph.syncEngine(config).testConnection(config) }
                    .exceptionOrNull()
                    ?.let { (it as? GitException)?.message ?: it.message },
            )
        }
    }

    fun syncNow() {
        val config = settings.syncConfig.value
        if (config == null || !config.isConfigured) {
            _syncOutcome.value = SyncOutcome.Failure("not-configured")
            return
        }
        if (_syncOutcome.value is SyncOutcome.Running) return
        _syncOutcome.value = SyncOutcome.Running
        viewModelScope.launch {
            val result = runCatching { graph.syncEngine(config).sync(config, settings.lastSyncAt.value) }
            result.onSuccess { report ->
                settings.setLastSyncAt(report.syncedAt)
                _syncOutcome.value = SyncOutcome.Success(report.pulled, report.pushed, report.conflicts)
            }.onFailure { error ->
                _syncOutcome.value = SyncOutcome.Failure(
                    (error as? GitException)?.message ?: error.message ?: "unknown error",
                )
            }
        }
    }

    suspend fun loadItem(id: String): TodoItem? = repository.item(id)

    companion object {
        fun factory(graph: AppGraph): ViewModelProvider.Factory = viewModelFactory {
            initializer { TodoViewModel(graph) }
        }
    }
}
