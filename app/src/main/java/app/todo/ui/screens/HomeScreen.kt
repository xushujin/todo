package app.todo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.todo.R
import app.todo.core.time.TimeText
import app.todo.domain.model.HomeMode
import app.todo.domain.model.ReminderChannels
import app.todo.domain.model.TodoItem
import app.todo.domain.model.TodoList
import app.todo.domain.model.TodoSort
import app.todo.ui.AutoSyncState
import app.todo.ui.TodoViewModel
import app.todo.ui.components.NeumorphicChip
import app.todo.ui.components.NeumorphicIconButton
import app.todo.ui.components.NeumorphicSurface
import app.todo.ui.components.PriorityDot
import app.todo.ui.components.ScreenHeader
import app.todo.ui.components.TodoIcon
import app.todo.ui.components.TodoIconKind
import app.todo.ui.components.priorityColor
import app.todo.ui.components.priorityLabel
import app.todo.ui.theme.LocalTodoPalette

@Composable
fun HomeScreen(
    vm: TodoViewModel,
    onNewTask: () -> Unit,
    onEditTask: (String) -> Unit,
    onOpenLists: () -> Unit,
    onOpenSync: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val palette = LocalTodoPalette.current
    val visible by vm.visibleItems.collectAsStateWithLifecycle()
    val allItems by vm.items.collectAsStateWithLifecycle()
    val lists by vm.lists.collectAsStateWithLifecycle()
    val filter by vm.filter.collectAsStateWithLifecycle()
    val sort by vm.settings.sort.collectAsStateWithLifecycle()
    val autoSync by vm.autoSyncState.collectAsStateWithLifecycle()
    val listsById = remember(lists) { lists.associateBy { it.id } }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                title = stringResource(R.string.home_title),
                subtitle = stringResource(
                    R.string.home_subtitle,
                    allItems.count { !it.done },
                    allItems.count { it.done },
                ) + when (autoSync) {
                    AutoSyncState.Syncing -> stringResource(R.string.home_sync_syncing)
                    is AutoSyncState.Failed -> stringResource(R.string.home_sync_failed)
                    AutoSyncState.Idle -> ""
                },
                actions = {
                    NeumorphicIconButton(
                        kind = TodoIconKind.LIST,
                        contentDescription = stringResource(R.string.nav_lists),
                        onClick = onOpenLists,
                    )
                    NeumorphicIconButton(
                        kind = TodoIconKind.CLOUD,
                        contentDescription = stringResource(R.string.nav_sync),
                        onClick = onOpenSync,
                    )
                    NeumorphicIconButton(
                        kind = TodoIconKind.SLIDERS,
                        contentDescription = stringResource(R.string.nav_settings),
                        onClick = onOpenSettings,
                    )
                },
            )

            FilterRow(
                filterMode = filter.mode,
                selectedListId = filter.listId,
                lists = lists,
                sort = sort,
                onMode = vm::setMode,
                onList = vm::setListFilter,
                onSort = vm::setSort,
            )

            if (visible.isEmpty()) {
                EmptyState(Modifier.weight(1f))
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(visible, key = { it.id }) { item ->
                        TodoRow(
                            item = item,
                            listName = listsById[item.listId]?.name.orEmpty(),
                            onToggle = { vm.toggleDone(item) },
                            onOpen = { onEditTask(item.id) },
                            onDelete = { vm.deleteItem(item) },
                            onTogglePin = { vm.togglePinned(item) },
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 24.dp, bottom = 28.dp),
        ) {
            NeumorphicIconButton(
                kind = TodoIconKind.PLUS,
                contentDescription = stringResource(R.string.editor_new),
                onClick = onNewTask,
                size = 62.dp,
                iconSize = 26.dp,
                accent = true,
            )
        }
    }
}

@Composable
private fun FilterRow(
    filterMode: HomeMode,
    selectedListId: String?,
    lists: List<TodoList>,
    sort: TodoSort,
    onMode: (HomeMode) -> Unit,
    onList: (String?) -> Unit,
    onSort: (TodoSort) -> Unit,
) {
    val palette = LocalTodoPalette.current
    val sortText = sortLabel(sort)
    val selectedList = lists.firstOrNull { it.id == selectedListId }
    var sortExpanded by remember { mutableStateOf(false) }
    var listExpanded by remember { mutableStateOf(false) }

    // A single scrolling row. Lists collapse into one dropdown, so the header stays
    // the same height whether the user has one list or twenty.
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            NeumorphicChip(
                label = stringResource(R.string.home_filter_all),
                selected = filterMode == HomeMode.ALL,
                onClick = { onMode(HomeMode.ALL) },
            )
        }
        item {
            NeumorphicChip(
                label = stringResource(R.string.home_filter_today),
                selected = filterMode == HomeMode.TODAY,
                onClick = { onMode(HomeMode.TODAY) },
            )
        }
        item {
            NeumorphicChip(
                label = stringResource(R.string.home_filter_urgent),
                selected = filterMode == HomeMode.URGENT,
                onClick = { onMode(HomeMode.URGENT) },
            )
        }
        item {
            Box {
                NeumorphicChip(
                    label = selectedList?.name ?: stringResource(R.string.nav_lists),
                    selected = selectedList != null,
                    onClick = { listExpanded = true },
                    dotColor = selectedList?.let { Color(it.colorArgb) },
                )
                DropdownMenu(
                    expanded = listExpanded,
                    onDismissRequest = { listExpanded = false },
                    containerColor = palette.surface,
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.home_filter_all_lists),
                                color = palette.textPrimary,
                            )
                        },
                        onClick = {
                            listExpanded = false
                            onList(null)
                        },
                    )
                    lists.forEach { list ->
                        DropdownMenuItem(
                            text = { Text(list.name, color = palette.textPrimary) },
                            onClick = {
                                listExpanded = false
                                onList(list.id)
                            },
                        )
                    }
                }
            }
        }
        item {
            Box {
                NeumorphicChip(
                    label = "${stringResource(R.string.home_sort)} · $sortText",
                    selected = false,
                    onClick = { sortExpanded = true },
                )
                DropdownMenu(
                    expanded = sortExpanded,
                    onDismissRequest = { sortExpanded = false },
                    containerColor = palette.surface,
                ) {
                    TodoSort.entries.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(sortLabel(option), color = palette.textPrimary) },
                            onClick = {
                                sortExpanded = false
                                onSort(option)
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun sortLabel(sort: TodoSort): String = stringResource(
    when (sort) {
        TodoSort.DUE -> R.string.home_sort_due
        TodoSort.PRIORITY -> R.string.home_sort_priority
        TodoSort.CREATED -> R.string.home_sort_created
    },
)

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    val palette = LocalTodoPalette.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        TodoIcon(
            kind = TodoIconKind.CLOUD,
            size = 72.dp,
            strokeWidth = 1.2.dp,
            tint = palette.shadowDark,
        )
        Spacer(Modifier.size(18.dp))
        Text(
            text = stringResource(R.string.home_empty_title),
            style = MaterialTheme.typography.titleLarge,
            color = palette.textPrimary,
        )
        Text(
            text = stringResource(R.string.home_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
        )
    }
}

@Composable
private fun TodoRow(
    item: TodoItem,
    listName: String,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val palette = LocalTodoPalette.current
    var menuOpen by remember { mutableStateOf(false) }
    val now = remember(item.updatedAt) { System.currentTimeMillis() }

    NeumorphicSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(20.dp),
        elevation = if (item.done) 2.dp else 5.dp,
        color = if (item.done) palette.surface.copy(alpha = 0.7f) else palette.surface,
        contentPadding = PaddingValues(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CheckCircle(checked = item.done, onClick = onToggle)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (item.pinned) {
                        TodoIcon(TodoIconKind.PIN, size = 13.dp, tint = palette.textSecondary, strokeWidth = 1.5.dp)
                    }
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (item.done) palette.textSecondary else palette.textPrimary,
                        textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        maxLines = 2,
                    )
                }
                MetaRow(item = item, listName = listName, now = now)
            }
            Box {
                NeumorphicIconButton(
                    kind = TodoIconKind.MORE,
                    contentDescription = stringResource(R.string.editor_delete),
                    onClick = { menuOpen = true },
                    size = 34.dp,
                    iconSize = 16.dp,
                )
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    containerColor = palette.surface,
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_pin), color = palette.textPrimary) },
                        onClick = {
                            menuOpen = false
                            onTogglePin()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.editor_delete), color = palette.danger) },
                        onClick = {
                            menuOpen = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaRow(item: TodoItem, listName: String, now: Long) {
    val palette = LocalTodoPalette.current
    val overdue = item.isOverdue(now)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PriorityDot(item.priority)
        Text(
            text = priorityLabel(item.priority),
            style = MaterialTheme.typography.labelSmall,
            color = priorityColor(item.priority),
        )
        if (listName.isNotBlank()) {
            Text("·", color = palette.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text(
                text = listName,
                style = MaterialTheme.typography.labelSmall,
                color = palette.textSecondary,
                maxLines = 1,
            )
        }
        if (item.dueAt != null) {
            Text("·", color = palette.textSecondary, style = MaterialTheme.typography.labelSmall)
            Text(
                text = dueLabel(item.dueAt, now),
                style = MaterialTheme.typography.labelSmall,
                color = if (overdue) palette.danger else palette.textSecondary,
            )
        }
        if (ReminderChannels.has(item.reminderChannels, ReminderChannels.CLOCK)) {
            TodoIcon(TodoIconKind.BELL, size = 13.dp, tint = palette.textSecondary, strokeWidth = 1.5.dp)
        }
        if (ReminderChannels.has(item.reminderChannels, ReminderChannels.EMAIL)) {
            TodoIcon(TodoIconKind.MAIL, size = 13.dp, tint = palette.textSecondary, strokeWidth = 1.5.dp)
        }
    }
}

@Composable
private fun dueLabel(dueAt: Long, now: Long): String {
    val days = TimeText.daysBetweenDays(now, dueAt)
    return when {
        days == 0L -> "${stringResource(R.string.home_today)} ${TimeText.formatTime(dueAt)}"
        days == 1L -> "${stringResource(R.string.home_tomorrow)} ${TimeText.formatTime(dueAt)}"
        days < 0L -> "${stringResource(R.string.home_overdue)} ${TimeText.formatDate(dueAt)}"
        else -> TimeText.formatDateTime(dueAt)
    }
}

@Composable
private fun CheckCircle(checked: Boolean, onClick: () -> Unit) {
    val palette = LocalTodoPalette.current
    NeumorphicSurface(
        modifier = Modifier
            .size(34.dp)
            .clickable(onClick = onClick),
        shape = CircleShape,
        elevation = 3.dp,
        color = if (checked) palette.accent else palette.surface,
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            TodoIcon(TodoIconKind.CHECK, size = 18.dp, tint = palette.onAccent, strokeWidth = 2.dp)
        } else {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(palette.surfaceSunken),
            )
        }
    }
}
