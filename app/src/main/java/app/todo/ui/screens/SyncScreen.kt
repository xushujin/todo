package app.todo.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.todo.R
import app.todo.core.time.TimeText
import app.todo.data.git.GitProviderKind
import app.todo.data.git.SyncConfig
import app.todo.ui.SyncOutcome
import app.todo.ui.TodoViewModel
import app.todo.ui.components.LabeledValueRow
import app.todo.ui.components.NeumorphicButton
import app.todo.ui.components.NeumorphicChip
import app.todo.ui.components.NeumorphicTextField
import app.todo.ui.components.ScreenHeader
import app.todo.ui.components.SectionCard
import app.todo.ui.theme.LocalTodoPalette

@Composable
fun SyncScreen(vm: TodoViewModel, onBack: () -> Unit) {
    val palette = LocalTodoPalette.current
    val saved by vm.settings.syncConfig.collectAsStateWithLifecycle()
    val lastSyncAt by vm.settings.lastSyncAt.collectAsStateWithLifecycle()
    val outcome by vm.syncOutcome.collectAsStateWithLifecycle()

    var provider by remember(saved) { mutableStateOf(saved?.provider ?: GitProviderKind.GITHUB) }
    var baseUrl by remember(saved) { mutableStateOf(saved?.baseUrl ?: GitProviderKind.GITHUB.defaultBaseUrl) }
    var owner by remember(saved) { mutableStateOf(saved?.owner.orEmpty()) }
    var repo by remember(saved) { mutableStateOf(saved?.repo.orEmpty()) }
    var branch by remember(saved) { mutableStateOf(saved?.branch ?: "main") }
    var path by remember(saved) { mutableStateOf(saved?.path ?: "data/todos.json") }
    var token by remember(saved) { mutableStateOf(saved?.token.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }

    val savedLabel = stringResource(R.string.settings_saved)
    val testOk = stringResource(R.string.sync_test_ok)
    val notConfigured = stringResource(R.string.sync_not_configured)
    val insecure = stringResource(R.string.sync_insecure_warning)

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.sync_title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = stringResource(R.string.sync_provider)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GitProviderKind.entries.forEach { option ->
                        NeumorphicChip(
                            label = option.label,
                            selected = provider == option,
                            onClick = {
                                if (baseUrl == provider.defaultBaseUrl || baseUrl.isBlank()) {
                                    baseUrl = option.defaultBaseUrl
                                }
                                provider = option
                            },
                        )
                    }
                }
                NeumorphicTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = stringResource(R.string.sync_base_url),
                    placeholder = stringResource(R.string.sync_base_url_hint),
                )
                NeumorphicTextField(owner, { owner = it }, stringResource(R.string.sync_owner))
                NeumorphicTextField(repo, { repo = it }, stringResource(R.string.sync_repo))
                NeumorphicTextField(branch, { branch = it }, stringResource(R.string.sync_branch))
                NeumorphicTextField(path, { path = it }, stringResource(R.string.sync_path))
                NeumorphicTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = stringResource(R.string.sync_token),
                    placeholder = stringResource(R.string.sync_token_hint),
                    visualTransformation = PasswordVisualTransformation(),
                )
            }

            val config = SyncConfig(
                provider = provider,
                baseUrl = baseUrl.trim(),
                owner = owner.trim(),
                repo = repo.trim(),
                branch = branch.trim(),
                path = path.trim(),
                token = token.trim(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeumorphicButton(
                    onClick = {
                        val problem = config.validate()
                        status = if (problem == null) {
                            vm.saveSyncSettings(config)
                            savedLabel
                        } else {
                            problem
                        }
                    },
                    accent = true,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.sync_save),
                        color = palette.onAccent,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                NeumorphicButton(
                    onClick = {
                        val problem = config.validate()
                        if (problem != null) {
                            status = problem
                        } else {
                            vm.saveSyncSettings(config)
                            status = null
                            vm.testSyncConnection { error ->
                                status = error ?: testOk
                            }
                        }
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.sync_test),
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.textPrimary,
                    )
                }
            }

            NeumorphicButton(
                onClick = {
                    if (config.validate() != null) {
                        status = notConfigured
                    } else if (!config.baseUrl.startsWith("https://")) {
                        status = insecure
                    } else {
                        vm.saveSyncSettings(config)
                        status = null
                        vm.syncNow()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                accent = true,
                contentPadding = PaddingValues(vertical = 16.dp, horizontal = 24.dp),
            ) {
                Text(
                    text = stringResource(R.string.sync_now),
                    color = palette.onAccent,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            SectionCard {
                LabeledValueRow(
                    label = stringResource(R.string.sync_last),
                    value = if (lastSyncAt <= 0L) {
                        stringResource(R.string.sync_never)
                    } else {
                        TimeText.formatDateTime(lastSyncAt)
                    },
                )
                when (val state = outcome) {
                    SyncOutcome.Idle -> Unit
                    SyncOutcome.Running -> Text(
                        text = stringResource(R.string.sync_running),
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    is SyncOutcome.Success -> {
                        Text(
                            text = if (state.pulled == 0 && state.pushed == 0) {
                                stringResource(R.string.sync_result_nochange)
                            } else {
                                stringResource(R.string.sync_result_ok, state.pulled, state.pushed, state.conflicts)
                            },
                            color = palette.success,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (state.conflicts > 0) {
                            Text(
                                text = stringResource(R.string.sync_conflicts, state.conflicts),
                                color = palette.warning,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    is SyncOutcome.Failure -> Text(
                        text = if (state.message == "not-configured") {
                            notConfigured
                        } else {
                            stringResource(R.string.sync_error, state.message)
                        },
                        color = palette.danger,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (status != null) {
                    Text(
                        text = status.orEmpty(),
                        color = palette.textSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}
