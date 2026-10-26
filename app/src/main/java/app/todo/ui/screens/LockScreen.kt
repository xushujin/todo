package app.todo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.todo.R
import app.todo.ui.components.NeumorphicButton
import app.todo.ui.components.NeumorphicSurface
import app.todo.ui.components.TodoIcon
import app.todo.ui.components.TodoIconKind
import app.todo.ui.theme.LocalTodoPalette

@Composable
fun LockScreen(
    errorMessage: String?,
    onUnlock: () -> Unit,
) {
    val palette = LocalTodoPalette.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        contentAlignment = Alignment.Center,
    ) {
        NeumorphicSurface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            elevation = 12.dp,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(28.dp),
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TodoIcon(
                    kind = TodoIconKind.FINGERPRINT,
                    contentDescription = null,
                    size = 64.dp,
                    strokeWidth = 1.4.dp,
                    tint = palette.accent,
                )
                Text(
                    text = stringResource(R.string.lock_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = palette.textPrimary,
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = stringResource(R.string.lock_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                    textAlign = TextAlign.Center,
                )
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.danger,
                        textAlign = TextAlign.Center,
                    )
                }
                NeumorphicButton(
                    onClick = onUnlock,
                    accent = true,
                    modifier = Modifier.width(180.dp),
                ) {
                    Text(
                        text = stringResource(R.string.lock_action),
                        color = palette.onAccent,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        }
    }
}
