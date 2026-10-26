package app.todo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.todo.BuildConfig
import app.todo.R
import app.todo.ui.components.ScreenHeader
import app.todo.ui.components.SectionCard
import app.todo.ui.theme.LocalTodoPalette

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.about_title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = stringResource(R.string.about_title)) {
                InfoLine(stringResource(R.string.about_app), stringResource(R.string.app_name))
                InfoLine(
                    stringResource(R.string.about_version),
                    "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                InfoLine(stringResource(R.string.about_package), BuildConfig.APPLICATION_ID)
            }
            SectionCard(title = stringResource(R.string.about_stack)) {
                InfoLine(null, stringResource(R.string.about_stack_value))
            }
            SectionCard(title = stringResource(R.string.about_support)) {
                InfoLine(null, stringResource(R.string.about_support_value))
            }
            SectionCard(title = stringResource(R.string.about_permissions)) {
                InfoLine(null, stringResource(R.string.about_permissions_value))
            }
            SectionCard(title = stringResource(R.string.about_privacy)) {
                InfoLine(null, stringResource(R.string.about_privacy_value))
            }
            SectionCard(title = stringResource(R.string.about_licenses)) {
                InfoLine(null, stringResource(R.string.about_licenses_value))
            }
        }
    }
}

@Composable
private fun InfoLine(label: String?, value: String) {
    val palette = LocalTodoPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = palette.textSecondary)
        }
        Text(value, style = MaterialTheme.typography.bodyMedium, color = palette.textPrimary)
    }
}
