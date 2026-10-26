package app.todo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.todo.R
import app.todo.core.time.TimeText
import app.todo.domain.model.Priority
import app.todo.domain.model.ReminderChannels
import app.todo.domain.repo.TodoDraft
import app.todo.ui.TodoViewModel
import app.todo.ui.components.ConfirmDialog
import app.todo.ui.components.DropdownField
import app.todo.ui.components.NeumorphicButton
import app.todo.ui.components.NeumorphicChip
import app.todo.ui.components.NeumorphicIconButton
import app.todo.ui.components.NeumorphicSurface
import app.todo.ui.components.NeumorphicTextField
import app.todo.ui.components.PriorityDot
import app.todo.ui.components.ScreenHeader
import app.todo.ui.components.SectionCard
import app.todo.ui.components.SwitchRow
import app.todo.ui.components.TodoDatePickerDialog
import app.todo.ui.components.TodoIcon
import app.todo.ui.components.TodoIconKind
import app.todo.ui.components.TodoTimePickerDialog
import app.todo.ui.components.priorityLabel
import app.todo.ui.theme.LocalTodoPalette

private enum class Picker { DUE_DATE, DUE_TIME, REMIND_DATE, REMIND_TIME }

@Composable
fun EditorScreen(
    vm: TodoViewModel,
    todoId: String?,
    onDone: () -> Unit,
) {
    val palette = LocalTodoPalette.current
    val lists by vm.lists.collectAsStateWithLifecycle()

    var title by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var listId by remember { mutableStateOf("") }
    var priority by remember { mutableStateOf(Priority.NORMAL) }
    var dueAt by remember { mutableStateOf<Long?>(null) }
    var remindAt by remember { mutableStateOf<Long?>(null) }
    var channels by remember { mutableIntStateOf(ReminderChannels.NONE) }
    var pinned by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    val titleRequired = stringResource(R.string.editor_required)
    val reminderNeedsTime = stringResource(R.string.editor_reminder_needs_time)
    val deleteLabel = stringResource(R.string.editor_delete)
    val listNames = remember(lists) { lists.associate { it.id to it.name } }

    LaunchedEffect(todoId) {
        if (todoId != null) {
            vm.loadItem(todoId)?.let { item ->
                title = item.title
                notes = item.notes
                listId = item.listId
                priority = item.priority
                dueAt = item.dueAt
                remindAt = item.remindAt
                channels = item.reminderChannels
                pinned = item.pinned
                done = item.done
            }
        }
    }

    LaunchedEffect(lists) {
        if (listId.isBlank()) listId = lists.firstOrNull()?.id.orEmpty()
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(if (todoId == null) R.string.editor_new else R.string.editor_edit),
            onBack = onDone,
            actions = {
                if (todoId != null) {
                    NeumorphicIconButton(
                        kind = TodoIconKind.TRASH,
                        contentDescription = deleteLabel,
                        onClick = { confirmDelete = true },
                    )
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            NeumorphicTextField(
                value = title,
                onValueChange = { title = it; error = null },
                label = stringResource(R.string.editor_title),
                placeholder = stringResource(R.string.editor_title_hint),
                singleLine = false,
                minLines = 1,
            )
            NeumorphicTextField(
                value = notes,
                onValueChange = { notes = it },
                label = stringResource(R.string.editor_notes),
                placeholder = stringResource(R.string.editor_notes_hint),
                singleLine = false,
                minLines = 3,
            )

            SectionCard(title = stringResource(R.string.editor_priority)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Priority.entries.forEach { option ->
                        NeumorphicChip(
                            label = priorityLabel(option),
                            selected = priority == option,
                            onClick = { priority = option },
                            dotColor = null,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PriorityDot(priority, size = 10.dp)
                    Text(
                        text = priorityLabel(priority),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                    )
                }
            }

            SectionCard(title = stringResource(R.string.editor_list)) {
                DropdownField(
                    label = stringResource(R.string.editor_list),
                    current = listId,
                    options = lists.map { it.id },
                    optionLabel = { id -> listNames[id] ?: id },
                    onSelect = { listId = it },
                )
            }

            SectionCard(title = stringResource(R.string.editor_due)) {
                DateTimeRow(
                    icon = TodoIconKind.CALENDAR,
                    label = stringResource(R.string.editor_due),
                    value = dueAt,
                    onPickDate = { picker = Picker.DUE_DATE },
                    onPickTime = { picker = Picker.DUE_TIME },
                    onClear = { dueAt = null },
                )
            }

            SectionCard(title = stringResource(R.string.editor_reminder)) {
                DateTimeRow(
                    icon = TodoIconKind.CLOCK,
                    label = stringResource(R.string.editor_reminder_time),
                    value = remindAt,
                    onPickDate = { picker = Picker.REMIND_DATE },
                    onPickTime = { picker = Picker.REMIND_TIME },
                    onClear = { remindAt = null; channels = ReminderChannels.NONE },
                )
                Text(
                    text = stringResource(R.string.editor_channels),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeumorphicChip(
                        label = stringResource(R.string.editor_channel_clock),
                        selected = ReminderChannels.has(channels, ReminderChannels.CLOCK),
                        onClick = {
                            channels = toggleChannel(channels, ReminderChannels.CLOCK)
                            if (channels != ReminderChannels.NONE && remindAt == null) {
                                remindAt = dueAt ?: (System.currentTimeMillis() + 60 * 60 * 1000)
                            }
                        },
                    )
                    NeumorphicChip(
                        label = stringResource(R.string.editor_channel_email),
                        selected = ReminderChannels.has(channels, ReminderChannels.EMAIL),
                        onClick = {
                            channels = toggleChannel(channels, ReminderChannels.EMAIL)
                            if (channels != ReminderChannels.NONE && remindAt == null) {
                                remindAt = dueAt ?: (System.currentTimeMillis() + 60 * 60 * 1000)
                            }
                        },
                    )
                }
            }

            SectionCard(title = stringResource(R.string.editor_pin)) {
                SwitchRow(
                    title = stringResource(R.string.editor_pin),
                    checked = pinned,
                    onCheckedChange = { pinned = it },
                )
                SwitchRow(
                    title = stringResource(R.string.home_show_done),
                    checked = done,
                    onCheckedChange = { done = it },
                )
            }

            if (error != null) {
                Text(text = error.orEmpty(), color = palette.danger, style = MaterialTheme.typography.bodyMedium)
            }

            NeumorphicButton(
                onClick = {
                    when {
                        title.isBlank() -> error = titleRequired
                        channels != ReminderChannels.NONE && remindAt == null -> error = reminderNeedsTime
                        else -> {
                            val draft = TodoDraft(
                                id = todoId,
                                listId = listId.ifBlank { lists.firstOrNull()?.id.orEmpty() },
                                title = title,
                                notes = notes,
                                priority = priority,
                                dueAt = dueAt,
                                remindAt = remindAt,
                                reminderChannels = channels,
                                pinned = pinned,
                                done = done,
                            )
                            vm.saveItem(draft) { onDone() }
                        }
                    }
                },
                accent = true,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 16.dp),
            ) {
                Text(
                    text = stringResource(R.string.editor_save),
                    color = palette.onAccent,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }

    when (picker) {
        Picker.DUE_DATE -> TodoDatePickerDialog(
            initialMillis = dueAt,
            onDismiss = { picker = null },
            onPicked = { dueAt = TimeText.withDate(dueAt, it) },
        )
        Picker.DUE_TIME -> TodoTimePickerDialog(
            initialMillis = dueAt,
            onDismiss = { picker = null },
            onPicked = { hour, minute -> dueAt = TimeText.withTime(dueAt, hour, minute) },
        )
        Picker.REMIND_DATE -> TodoDatePickerDialog(
            initialMillis = remindAt,
            onDismiss = { picker = null },
            onPicked = { remindAt = TimeText.withDate(remindAt, it) },
        )
        Picker.REMIND_TIME -> TodoTimePickerDialog(
            initialMillis = remindAt,
            onDismiss = { picker = null },
            onPicked = { hour, minute -> remindAt = TimeText.withTime(remindAt, hour, minute) },
        )
        null -> Unit
    }

    if (confirmDelete) {
        ConfirmDialog(
            title = deleteLabel,
            message = title,
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                confirmDelete = false
                vm.deleteItemById(todoId.orEmpty())
                onDone()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun DateTimeRow(
    icon: TodoIconKind,
    label: String,
    value: Long?,
    onPickDate: () -> Unit,
    onPickTime: () -> Unit,
    onClear: () -> Unit,
) {
    val palette = LocalTodoPalette.current
    NeumorphicSurface(
        shape = RoundedCornerShape(14.dp),
        elevation = 3.dp,
        color = palette.surfaceSunken.copy(alpha = 0.35f),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TodoIcon(icon, size = 18.dp, tint = palette.textSecondary)
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                Text(
                    text = value?.let { TimeText.formatDateTime(it) } ?: "—",
                    style = MaterialTheme.typography.bodyLarge,
                    color = palette.textPrimary,
                )
            }
            if (value != null) {
                NeumorphicIconButton(
                    kind = TodoIconKind.CLOSE,
                    contentDescription = stringResource(R.string.editor_clear),
                    onClick = onClear,
                    size = 30.dp,
                    iconSize = 14.dp,
                )
            }
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        NeumorphicButton(onClick = onPickDate, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.editor_pick_date), style = MaterialTheme.typography.labelMedium, color = palette.textPrimary)
        }
        NeumorphicButton(onClick = onPickTime, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)) {
            Text(stringResource(R.string.editor_pick_time), style = MaterialTheme.typography.labelMedium, color = palette.textPrimary)
        }
    }
}

private fun toggleChannel(current: Int, channel: Int): Int =
    if (ReminderChannels.has(current, channel)) current and channel.inv() else current or channel
