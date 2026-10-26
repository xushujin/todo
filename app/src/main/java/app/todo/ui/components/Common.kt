package app.todo.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.todo.R
import app.todo.core.time.TimeText
import app.todo.domain.model.Priority
import app.todo.ui.theme.LocalTodoPalette
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@Composable
fun NeumorphicTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    val palette = LocalTodoPalette.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        placeholder = placeholder?.let { text ->
            { Text(text, color = palette.textSecondary, style = MaterialTheme.typography.bodyMedium) }
        },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = palette.textPrimary,
            unfocusedTextColor = palette.textPrimary,
            focusedBorderColor = palette.accent,
            unfocusedBorderColor = palette.shadowDark.copy(alpha = 0.6f),
            focusedContainerColor = palette.surfaceSunken.copy(alpha = 0.35f),
            unfocusedContainerColor = palette.surfaceSunken.copy(alpha = 0.2f),
            cursorColor = palette.accent,
            focusedLabelColor = palette.accent,
            unfocusedLabelColor = palette.textSecondary,
        ),
    )
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val palette = LocalTodoPalette.current
    NeumorphicSurface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        elevation = 6.dp,
        contentPadding = PaddingValues(18.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (title != null) {
                Text(
                    text = title.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = palette.textSecondary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            content()
        }
    }
}

@Composable
fun LabeledValueRow(
    label: String,
    value: String,
    onClick: (() -> Unit)? = null,
    valueColor: Color = LocalTodoPalette.current.textPrimary,
) {
    val palette = LocalTodoPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
        Text(value, style = MaterialTheme.typography.bodyMedium, color = valueColor)
    }
}

@Composable
fun SwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val palette = LocalTodoPalette.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) palette.textPrimary else palette.textSecondary,
            )
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = palette.textSecondary)
            }
        }
        androidx.compose.material3.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                checkedThumbColor = palette.onAccent,
                checkedTrackColor = palette.accent,
                uncheckedThumbColor = palette.textSecondary,
                uncheckedTrackColor = palette.surfaceSunken,
            ),
        )
    }
}

@Composable
fun PriorityDot(priority: Priority, size: androidx.compose.ui.unit.Dp = 8.dp) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(priorityColor(priority)),
    )
}

@Composable
fun priorityColor(priority: Priority): Color {
    val palette = LocalTodoPalette.current
    return when (priority) {
        Priority.LOW -> palette.success
        Priority.NORMAL -> palette.accent
        Priority.HIGH -> palette.warning
        Priority.URGENT -> palette.danger
    }
}

@Composable
fun priorityLabel(priority: Priority): String = stringResource(
    when (priority) {
        Priority.LOW -> R.string.priority_low
        Priority.NORMAL -> R.string.priority_normal
        Priority.HIGH -> R.string.priority_high
        Priority.URGENT -> R.string.priority_urgent
    },
)

@Composable
fun <T> DropdownField(
    label: String,
    current: T,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val palette = LocalTodoPalette.current
    Box(modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(palette.surfaceSunken.copy(alpha = 0.35f))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(label, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
                Text(optionLabel(current), style = MaterialTheme.typography.bodyLarge, color = palette.textPrimary)
            }
            TodoIcon(TodoIconKind.MORE, tint = palette.textSecondary, size = 18.dp)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = palette.surface,
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option), color = palette.textPrimary) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalTodoPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textSecondary,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = palette.danger) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = palette.textSecondary) }
        },
    )
}

/** Date picker returning a local date. M3 works in UTC millis, so we convert here once. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoDatePickerDialog(
    initialMillis: Long?,
    onDismiss: () -> Unit,
    onPicked: (Long) -> Unit,
) {
    val initialDate = initialMillis?.let { TimeText.toLocalDateTime(it).toLocalDate() } ?: LocalDate.now()
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initialDate.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
    )
    val palette = LocalTodoPalette.current
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val selected = state.selectedDateMillis
                    if (selected != null) {
                        val date = Instant.ofEpochMilli(selected).atZone(ZoneOffset.UTC).toLocalDate()
                        onPicked(TimeText.toMillis(date.atTime(9, 0)))
                    }
                    onDismiss()
                },
            ) { Text(stringResource(R.string.editor_ok), color = palette.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_cancel), color = palette.textSecondary)
            }
        },
    ) {
        DatePicker(state = state, colors = androidx.compose.material3.DatePickerDefaults.colors(
            containerColor = palette.surface,
            titleContentColor = palette.textPrimary,
            headlineContentColor = palette.textPrimary,
            weekdayContentColor = palette.textSecondary,
            subheadContentColor = palette.textSecondary,
            navigationContentColor = palette.textPrimary,
            selectedDayContainerColor = palette.accent,
            selectedDayContentColor = palette.onAccent,
            todayDateBorderColor = palette.accent,
            todayContentColor = palette.accent,
        ))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodoTimePickerDialog(
    initialMillis: Long?,
    onDismiss: () -> Unit,
    onPicked: (hour: Int, minute: Int) -> Unit,
) {
    val initial = initialMillis?.let { TimeText.toLocalDateTime(it) }
    val state = rememberTimePickerState(
        initialHour = initial?.hour ?: 9,
        initialMinute = initial?.minute ?: 0,
        is24Hour = true,
    )
    val palette = LocalTodoPalette.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = palette.surface,
        title = { Text(stringResource(R.string.editor_pick_time), color = palette.textPrimary) },
        text = {
            Box(Modifier.heightIn(min = 280.dp)) {
                TimePicker(state = state, colors = androidx.compose.material3.TimePickerDefaults.colors(
                    clockDialColor = palette.surfaceSunken,
                    clockDialSelectedContentColor = palette.onAccent,
                    clockDialUnselectedContentColor = palette.textPrimary,
                    selectorColor = palette.accent,
                    timeSelectorSelectedContainerColor = palette.accent.copy(alpha = 0.2f),
                    timeSelectorSelectedContentColor = palette.accent,
                    timeSelectorUnselectedContainerColor = palette.surfaceSunken,
                    timeSelectorUnselectedContentColor = palette.textPrimary,
                ))
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onPicked(state.hour, state.minute)
                onDismiss()
            }) { Text(stringResource(R.string.editor_ok), color = palette.accent) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.editor_cancel), color = palette.textSecondary)
            }
        },
    )
}
