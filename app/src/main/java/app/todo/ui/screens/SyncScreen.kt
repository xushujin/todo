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
import app.todo.data.git.GitErrorKind
import app.todo.data.git.GitProviderKind
import app.todo.data.git.SyncConfig
import app.todo.ui.ConnectionOutcome
import app.todo.ui.SyncOutcome
import app.todo.ui.TodoViewModel
import app.todo.ui.components.LabeledValueRow
import app.todo.ui.components.NeumorphicButton
import app.todo.ui.components.NeumorphicChip
import app.todo.ui.components.NeumorphicTextField
import app.todo.ui.components.ScreenHeader
import app.todo.ui.components.SectionCard
import app.todo.ui.theme.LocalTodoPalette

/**
 * One place for "what just happened": the newest action replaces the previous one, so a
 * successful connection test can never appear underneath a stale sync error.
 */
@Composable
private fun SyncFeedback(
    connection: ConnectionOutcome?,
    formMessage: String?,
    outcome: SyncOutcome,
    branch: String,
    path: String,
    onUseBranch: (String) -> Unit,
) {
    val palette = LocalTodoPalette.current

    if (formMessage != null) {
        Text(formMessage, color = palette.success, style = MaterialTheme.typography.bodyMedium)
    }

    when (val state = connection) {
        null -> Unit
        is ConnectionOutcome.Ok -> {
            if (state.fileExists) {
                Text(
                    text = stringResource(R.string.sync_test_ok),
                    color = palette.success,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text(
                    text = stringResource(R.string.sync_test_ok_file_missing, path, branch),
                    color = palette.warning,
                    style = MaterialTheme.typography.bodyMedium,
                )
                val defaultBranch = state.defaultBranch
                if (defaultBranch != null && defaultBranch != branch) {
                    Text(
                        text = stringResource(R.string.sync_branch_hint, defaultBranch, branch),
                        color = palette.warning,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    NeumorphicButton(
                        onClick = { onUseBranch(defaultBranch) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.sync_use_branch, defaultBranch),
                            style = MaterialTheme.typography.labelMedium,
                            color = palette.textPrimary,
                        )
                    }
                }
            }
        }
        is ConnectionOutcome.Failed -> {
            Text(
                text = errorTitle(state.kind),
                color = palette.danger,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.detail.isNotBlank()) {
                Text(
                    text = state.detail.take(240),
                    color = palette.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }

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
        is SyncOutcome.Failure -> {
            Text(
                text = errorTitle(state.kind),
                color = palette.danger,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (state.detail.isNotBlank()) {
                Text(
                    text = state.detail.take(240),
                    color = palette.textSecondary,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

@Composable
private fun errorTitle(kind: GitErrorKind): String = stringResource(
    when (kind) {
        GitErrorKind.AUTH -> R.string.sync_error_auth
        GitErrorKind.NOT_FOUND -> R.string.sync_error_not_found
        GitErrorKind.CONFLICT -> R.string.sync_error_conflict
        GitErrorKind.NETWORK -> R.string.sync_error_network
        GitErrorKind.INVALID_CONFIG -> R.string.sync_error_config
        GitErrorKind.UNKNOWN -> R.string.sync_error_unknown
    },
)

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
    var formMessage by remember { mutableStateOf<String?>(null) }
    var connection by remember { mutableStateOf<ConnectionOutcome?>(null) }

    val savedLabel = stringResource(R.string.settings_saved)
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

            // Accepts a pasted clone URL / "owner/repo" and turns it into the two
            // fields the API needs, instead of sending a URL-encoded path to the server.
            val config = SyncConfig(
                provider = provider,
                baseUrl = baseUrl,
                owner = owner,
                repo = repo,
                branch = branch,
                path = path,
                token = token,
            ).normalized()

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NeumorphicButton(
                    onClick = {
                        val problem = config.validate()
                        if (problem == null) {
                            vm.saveSyncSettings(config)
                            connection = null
                            vm.clearSyncOutcome()
                            formMessage = savedLabel
                        } else {
                            formMessage = null
                            connection = ConnectionOutcome.Failed(GitErrorKind.INVALID_CONFIG, problem)
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
                            formMessage = null
                            connection = ConnectionOutcome.Failed(GitErrorKind.INVALID_CONFIG, problem)
                        } else {
                            vm.saveSyncSettings(config)
                            formMessage = null
                            connection = null
                            vm.testSyncConnection { connection = it }
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
                    val problem = config.validate()
                    when {
                        problem != null -> {
                            formMessage = null
                            connection = ConnectionOutcome.Failed(GitErrorKind.INVALID_CONFIG, problem)
                        }
                        !config.baseUrl.startsWith("https://") -> {
                            formMessage = null
                            connection = ConnectionOutcome.Failed(GitErrorKind.INVALID_CONFIG, insecure)
                        }
                        else -> {
                            vm.saveSyncSettings(config)
                            formMessage = null
                            connection = null
                            vm.syncNow()
                        }
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
                SyncFeedback(
                    connection = connection,
                    formMessage = formMessage,
                    outcome = outcome,
                    branch = branch,
                    path = path,
                    onUseBranch = { newBranch ->
                        branch = newBranch
                        vm.saveSyncSettings(config.copy(branch = newBranch))
                        connection = null
                        vm.testSyncConnection { connection = it }
                    },
                )
                LabeledValueRow(
                    label = stringResource(R.string.sync_last),
                    value = if (lastSyncAt <= 0L) {
                        stringResource(R.string.sync_never)
                    } else {
                        TimeText.formatDateTime(lastSyncAt)
                    },
                )
            }
        }
    }
}
