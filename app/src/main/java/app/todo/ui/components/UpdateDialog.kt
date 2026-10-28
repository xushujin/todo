package app.todo.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.todo.R
import app.todo.ui.UpdateState
import app.todo.ui.theme.LocalTodoPalette
import kotlin.math.roundToInt

/**
 * Update prompt shared by the automatic check and the manual one in settings.
 *
 * The release notes come straight from the GitHub release body, so they can be long;
 * they scroll inside a capped box instead of pushing the buttons off screen.
 */
@Composable
fun UpdateDialog(
    state: UpdateState,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = LocalTodoPalette.current
    val info = when (state) {
        is UpdateState.Available -> state.info
        is UpdateState.Downloading -> state.info
        is UpdateState.Ready -> state.info
        else -> return
    }
    val percent = ((state as? UpdateState.Downloading)?.progress ?: 0f).times(100).roundToInt()

    AlertDialog(
        onDismissRequest = {
            // Back / outside tap must not hide a download that is still running.
            if (state !is UpdateState.Downloading) onDismiss()
        },
        containerColor = palette.surface,
        titleContentColor = palette.textPrimary,
        textContentColor = palette.textSecondary,
        title = {
            Text(
                text = stringResource(R.string.update_available, info.versionName),
                color = palette.textPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (info.notes.isNotBlank()) {
                    Text(
                        text = info.notes,
                        style = MaterialTheme.typography.bodySmall,
                        color = palette.textSecondary,
                        modifier = Modifier
                            .heightIn(max = 200.dp)
                            .verticalScroll(rememberScrollState()),
                    )
                }
                when (state) {
                    is UpdateState.Downloading -> {
                        LinearProgressIndicator(
                            progress = { state.progress },
                            modifier = Modifier.fillMaxWidth(),
                            color = palette.accent,
                            trackColor = palette.surfaceSunken,
                        )
                        Text(
                            text = stringResource(R.string.update_downloading, percent),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textSecondary,
                        )
                    }
                    is UpdateState.Ready -> Text(
                        text = stringResource(R.string.update_ready),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.success,
                    )
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (state) {
                is UpdateState.Available -> TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.update_download), color = palette.accent)
                }
                is UpdateState.Ready -> TextButton(onClick = onInstall) {
                    Text(stringResource(R.string.update_install), color = palette.accent)
                }
                is UpdateState.Downloading -> TextButton(onClick = {}, enabled = false) {
                    Text(
                        text = stringResource(R.string.update_downloading, percent),
                        color = palette.textSecondary,
                    )
                }
                else -> Unit
            }
        },
        dismissButton = {
            if (state !is UpdateState.Downloading) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_later), color = palette.textSecondary)
                }
            }
        },
    )
}
