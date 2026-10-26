package app.todo.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.todo.AppGraph
import app.todo.ui.screens.AboutScreen
import app.todo.ui.screens.EditorScreen
import app.todo.ui.screens.HomeScreen
import app.todo.ui.screens.ListsScreen
import app.todo.ui.screens.SettingsScreen
import app.todo.ui.screens.SyncScreen

sealed interface Screen {
    data object Home : Screen
    data object Lists : Screen
    data object Settings : Screen
    data object Sync : Screen
    data object About : Screen
    data class Editor(val todoId: String?) : Screen
}

/**
 * Navigation is a plain stack of string keys kept in `rememberSaveable`, so there is
 * no navigation library and rotation restores the exact screen.
 */
@Composable
fun AppRoot(graph: AppGraph, initialTodoId: String?) {
    val vm: TodoViewModel = viewModel(factory = TodoViewModel.factory(graph))
    var stack by rememberSaveable { mutableStateOf(HOME_KEY) }

    val keys = stack.split(KEY_SEPARATOR)
    val current = screenOf(keys.last())

    fun push(key: String) {
        stack = if (stack.isEmpty()) key else stack + KEY_SEPARATOR + key
    }

    fun pop() {
        stack = stack.substringBeforeLast(KEY_SEPARATOR, HOME_KEY)
    }

    // Foreground sync pulls remote changes on launch and whenever the app comes back.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> vm.onForeground()
                Lifecycle.Event.ON_STOP -> vm.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(initialTodoId) {
        if (initialTodoId != null && !stack.contains("editor:$initialTodoId")) {
            push("editor:$initialTodoId")
        }
    }

    BackHandler(enabled = keys.size > 1) { pop() }

    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
        when (current) {
            Screen.Home -> HomeScreen(
                vm = vm,
                onNewTask = { push(EDITOR_KEY) },
                onEditTask = { id -> push("editor:$id") },
                onOpenLists = { push(LISTS_KEY) },
                onOpenSync = { push(SYNC_KEY) },
                onOpenSettings = { push(SETTINGS_KEY) },
            )
            Screen.Lists -> ListsScreen(vm = vm, onBack = { pop() })
            Screen.Settings -> SettingsScreen(
                vm = vm,
                onBack = { pop() },
                onOpenAbout = { push(ABOUT_KEY) },
            )
            Screen.Sync -> SyncScreen(vm = vm, onBack = { pop() })
            Screen.About -> AboutScreen(onBack = { pop() })
            is Screen.Editor -> EditorScreen(
                vm = vm,
                todoId = current.todoId,
                onDone = { pop() },
            )
        }
    }
}

private const val KEY_SEPARATOR = "|"
private const val HOME_KEY = "home"
private const val LISTS_KEY = "lists"
private const val SETTINGS_KEY = "settings"
private const val SYNC_KEY = "sync"
private const val ABOUT_KEY = "about"
private const val EDITOR_KEY = "editor:"

private fun screenOf(key: String): Screen = when {
    key.startsWith(EDITOR_KEY) -> Screen.Editor(key.removePrefix(EDITOR_KEY).ifBlank { null })
    key == LISTS_KEY -> Screen.Lists
    key == SETTINGS_KEY -> Screen.Settings
    key == SYNC_KEY -> Screen.Sync
    key == ABOUT_KEY -> Screen.About
    else -> Screen.Home
}
