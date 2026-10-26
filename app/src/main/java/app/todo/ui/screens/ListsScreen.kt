package app.todo.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.todo.R
import app.todo.domain.model.TodoList
import app.todo.ui.TodoViewModel
import app.todo.ui.components.ConfirmDialog
import app.todo.ui.components.NeumorphicButton
import app.todo.ui.components.NeumorphicIconButton
import app.todo.ui.components.NeumorphicSurface
import app.todo.ui.components.NeumorphicTextField
import app.todo.ui.components.ScreenHeader
import app.todo.ui.components.TodoIcon
import app.todo.ui.components.TodoIconKind
import app.todo.ui.theme.LocalTodoPalette

private val listColors = listOf(
    0xFF4C5BD4L,
    0xFF2F8F6BL,
    0xFFB4693AL,
    0xFFD1495BL,
    0xFFD98324L,
    0xFF7C6BD4L,
)

@Composable
fun ListsScreen(vm: TodoViewModel, onBack: () -> Unit) {
    val palette = LocalTodoPalette.current
    val lists by vm.lists.collectAsStateWithLifecycle()
    val items by vm.items.collectAsStateWithLifecycle()

    var editing by remember { mutableStateOf<TodoList?>(null) }
    var creating by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<TodoList?>(null) }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(
            title = stringResource(R.string.lists_title),
            onBack = onBack,
            actions = {
                NeumorphicIconButton(
                    kind = TodoIconKind.PLUS,
                    contentDescription = stringResource(R.string.lists_new),
                    onClick = { creating = true },
                )
            },
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            lists.forEach { list ->
                NeumorphicSurface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = list },
                    shape = RoundedCornerShape(20.dp),
                    elevation = 5.dp,
                    contentPadding = PaddingValues(16.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            Modifier
                                .size(14.dp)
                                .clip(CircleShape)
                                .background(Color(list.colorArgb)),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = list.name,
                                style = MaterialTheme.typography.bodyLarge,
                                color = palette.textPrimary,
                            )
                            Text(
                                text = stringResource(
                                    R.string.lists_count,
                                    items.count { it.listId == list.id },
                                ),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.textSecondary,
                            )
                        }
                        NeumorphicIconButton(
                            kind = TodoIconKind.TRASH,
                            contentDescription = stringResource(R.string.lists_delete),
                            onClick = { deleting = list },
                            size = 34.dp,
                            iconSize = 16.dp,
                        )
                    }
                }
            }
        }
    }

    if (creating || editing != null) {
        ListEditorDialog(
            initial = editing,
            onDismiss = {
                creating = false
                editing = null
            },
            onSave = { name, color ->
                vm.saveList(editing?.id, name, color)
                creating = false
                editing = null
            },
        )
    }

    deleting?.let { list ->
        ConfirmDialog(
            title = stringResource(R.string.lists_delete),
            message = stringResource(R.string.lists_delete_confirm, list.name),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                vm.deleteList(list)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun ListEditorDialog(
    initial: TodoList?,
    onDismiss: () -> Unit,
    onSave: (name: String, colorArgb: Long) -> Unit,
) {
    val palette = LocalTodoPalette.current
    var name by remember { mutableStateOf(initial?.name.orEmpty()) }
    var color by remember { mutableLongStateOf(initial?.colorArgb ?: listColors.first()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.textPrimary,
        title = {
            Text(stringResource(if (initial == null) R.string.lists_new else R.string.lists_title))
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                NeumorphicTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = stringResource(R.string.lists_name),
                )
                Text(
                    text = stringResource(R.string.lists_color),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listColors.forEach { candidate ->
                        Box(
                            modifier = Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color(candidate))
                                .clickable { color = candidate },
                            contentAlignment = Alignment.Center,
                        ) {
                            if (color == candidate) {
                                TodoIcon(TodoIconKind.CHECK, size = 16.dp, tint = Color.White, strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onSave(name, color) },
            ) { Text(stringResource(R.string.common_save), color = palette.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.common_cancel), color = palette.textSecondary)
            }
        },
    )
}
