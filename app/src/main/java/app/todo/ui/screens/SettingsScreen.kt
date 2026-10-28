package app.todo.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import app.todo.core.platform.AppPermissions
import app.todo.security.BiometricGate
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.todo.BuildConfig
import app.todo.R
import app.todo.data.mail.SmtpConfig
import app.todo.data.mail.SmtpSecurity
import app.todo.domain.model.TodoSort
import app.todo.ui.MailErrorKind
import app.todo.ui.MailTestResult
import app.todo.ui.TodoViewModel
import app.todo.ui.UpdateState
import app.todo.ui.components.ChipFlow
import app.todo.ui.components.DropdownField
import app.todo.ui.components.HintBox
import app.todo.ui.components.LabeledValueRow
import app.todo.ui.components.NeumorphicButton
import app.todo.ui.components.NeumorphicChip
import app.todo.ui.components.NeumorphicTextField
import app.todo.ui.components.ScreenHeader
import app.todo.ui.components.SectionCard
import app.todo.ui.components.SwitchRow
import app.todo.ui.components.TodoIcon
import app.todo.ui.components.TodoIconKind
import app.todo.ui.theme.LocalTodoPalette
import app.todo.ui.theme.Palettes
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(
    vm: TodoViewModel,
    onBack: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val palette = LocalTodoPalette.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val themeId by vm.settings.themeId.collectAsStateWithLifecycle()
    val biometricEnabled by vm.settings.biometricEnabled.collectAsStateWithLifecycle()
    val lockTimeout by vm.settings.lockTimeoutSeconds.collectAsStateWithLifecycle()
    val showDone by vm.settings.showDone.collectAsStateWithLifecycle()
    val sort by vm.settings.sort.collectAsStateWithLifecycle()
    val emailConfig by vm.settings.emailConfig.collectAsStateWithLifecycle()
    val updateState by vm.updateState.collectAsStateWithLifecycle()

    var notificationsGranted by remember {
        mutableStateOf(
            context.checkSelfPermission(AppPermissions.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var exactAlarmsAllowed by remember { mutableStateOf(vm.canScheduleExactAlarms()) }
    val biometricAvailable = BiometricGate.availability(context) == BiometricGate.Availability.AVAILABLE

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsGranted = context.checkSelfPermission(AppPermissions.POST_NOTIFICATIONS) ==
                    PackageManager.PERMISSION_GRANTED
                exactAlarmsAllowed = vm.canScheduleExactAlarms()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> notificationsGranted = granted }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title = stringResource(R.string.settings_title), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SectionCard(title = stringResource(R.string.settings_appearance)) {
                Text(
                    text = stringResource(R.string.settings_theme),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                )
                ChipFlow {
                    Palettes.all.forEach { option ->
                        NeumorphicChip(
                            label = stringResource(option.labelRes),
                            selected = themeId == option.id,
                            onClick = { vm.setTheme(option.id) },
                        )
                    }
                }
                SwitchRow(
                    title = stringResource(R.string.home_show_done),
                    checked = showDone,
                    onCheckedChange = vm::setShowDone,
                )
                Text(
                    text = stringResource(R.string.home_sort),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                )
                ChipFlow {
                    TodoSort.entries.forEach { option ->
                        NeumorphicChip(
                            label = stringResource(
                                when (option) {
                                    TodoSort.DUE -> R.string.home_sort_due
                                    TodoSort.PRIORITY -> R.string.home_sort_priority
                                    TodoSort.CREATED -> R.string.home_sort_created
                                },
                            ),
                            selected = sort == option,
                            onClick = { vm.setSort(option) },
                        )
                    }
                }
            }

            SectionCard(title = stringResource(R.string.settings_security)) {
                SwitchRow(
                    title = stringResource(R.string.settings_biometric),
                    subtitle = stringResource(
                        if (biometricAvailable) R.string.settings_biometric_desc
                        else R.string.settings_biometric_unavailable,
                    ),
                    checked = biometricEnabled && biometricAvailable,
                    onCheckedChange = vm::setBiometric,
                    enabled = biometricAvailable,
                )
                if (biometricEnabled && biometricAvailable) {
                    DropdownField(
                        label = stringResource(R.string.settings_lock_timeout),
                        current = lockTimeout,
                        options = lockTimeoutOptions,
                        optionLabel = { seconds -> lockTimeoutLabel(seconds) },
                        onSelect = vm::setLockTimeout,
                    )
                    Text(
                        text = stringResource(R.string.settings_lock_timeout_desc),
                        style = MaterialTheme.typography.bodyMedium,
                        color = palette.textSecondary,
                    )
                }
            }

            SectionCard(title = stringResource(R.string.settings_notifications)) {
                LabeledValueRow(
                    label = stringResource(R.string.settings_notification_permission),
                    value = stringResource(
                        if (notificationsGranted) R.string.settings_notification_granted
                        else R.string.settings_notification_denied,
                    ),
                    onClick = {
                        if (!notificationsGranted) {
                            permissionLauncher.launch(AppPermissions.POST_NOTIFICATIONS)
                        }
                    },
                    valueColor = if (notificationsGranted) palette.success else palette.warning,
                )
                LabeledValueRow(
                    label = stringResource(R.string.settings_exact_alarm),
                    value = stringResource(
                        if (exactAlarmsAllowed) R.string.settings_exact_alarm_granted
                        else R.string.settings_exact_alarm_denied,
                    ),
                    onClick = {
                        if (!exactAlarmsAllowed) {
                            val intent = Intent(AppPermissions.REQUEST_SCHEDULE_EXACT_ALARM)
                                .setData("package:${context.packageName}".toUri())
                            runCatching { context.startActivity(intent) }
                        }
                    },
                    valueColor = if (exactAlarmsAllowed) palette.success else palette.warning,
                )
            }

            EmailSettingsSection(
                initial = emailConfig,
                onSave = vm::saveEmailSettings,
                onSendTest = vm::sendTestEmail,
            )

            SectionCard(title = stringResource(R.string.settings_data)) {
                NeumorphicButton(onClick = vm::purgeDeleted) {
                    Text(
                        text = stringResource(R.string.settings_purge),
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.textPrimary,
                    )
                }
            }

            SectionCard(title = stringResource(R.string.settings_update)) {
                LabeledValueRow(
                    label = stringResource(R.string.update_current_version),
                    value = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                )
                val status = when (val state = updateState) {
                    UpdateState.Idle -> null
                    UpdateState.Checking -> stringResource(R.string.update_checking)
                    UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                    is UpdateState.Available ->
                        stringResource(R.string.update_available, state.info.versionName)
                    is UpdateState.Downloading ->
                        stringResource(R.string.update_downloading, (state.progress * 100).roundToInt())
                    is UpdateState.Ready -> stringResource(R.string.update_ready)
                    is UpdateState.Failed -> stringResource(R.string.update_failed, state.message)
                }
                if (status != null) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = when (updateState) {
                            is UpdateState.Failed -> palette.danger
                            UpdateState.UpToDate, is UpdateState.Ready -> palette.success
                            else -> palette.textSecondary
                        },
                    )
                }
                val buttonLabel = when (val state = updateState) {
                    UpdateState.Checking -> stringResource(R.string.update_checking)
                    is UpdateState.Downloading ->
                        stringResource(R.string.update_downloading, (state.progress * 100).roundToInt())
                    is UpdateState.Available -> stringResource(R.string.update_download)
                    is UpdateState.Ready -> stringResource(R.string.update_install)
                    is UpdateState.Failed ->
                        if (state.info != null) stringResource(R.string.update_retry)
                        else stringResource(R.string.update_check)
                    UpdateState.Idle, UpdateState.UpToDate -> stringResource(R.string.update_check)
                }
                NeumorphicButton(
                    onClick = {
                        when (val state = updateState) {
                            is UpdateState.Available -> vm.downloadUpdate()
                            is UpdateState.Ready -> vm.installUpdate()
                            is UpdateState.Failed ->
                                if (state.info != null) vm.downloadUpdate() else vm.checkForUpdate()
                            else -> vm.checkForUpdate()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = updateState !is UpdateState.Checking &&
                        updateState !is UpdateState.Downloading,
                    accent = true,
                ) {
                    Text(
                        text = buttonLabel,
                        color = palette.onAccent,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            SectionCard {
                NeumorphicButton(onClick = onOpenAbout, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.settings_about),
                        style = MaterialTheme.typography.labelLarge,
                        color = palette.textPrimary,
                    )
                }
            }
        }
    }
}

private val lockTimeoutOptions = listOf(0, 30, 60, 300, 900)

@Composable
private fun mailErrorTitle(kind: MailErrorKind): String = stringResource(
    when (kind) {
        MailErrorKind.AUTH -> R.string.mail_error_auth
        MailErrorKind.CONFIG -> R.string.mail_error_config
        MailErrorKind.NETWORK -> R.string.mail_error_network
        MailErrorKind.UNKNOWN -> R.string.mail_error_unknown
    },
)

@Composable
private fun lockTimeoutLabel(seconds: Int): String = stringResource(
    when (seconds) {
        0 -> R.string.lock_timeout_immediate
        30 -> R.string.lock_timeout_30s
        60 -> R.string.lock_timeout_1m
        300 -> R.string.lock_timeout_5m
        else -> R.string.lock_timeout_15m
    },
)

@Composable
private fun EmailSettingsSection(
    initial: SmtpConfig?,
    onSave: (SmtpConfig) -> Unit,
    onSendTest: ((MailTestResult) -> Unit) -> Unit,
) {
    val palette = LocalTodoPalette.current
    var host by remember(initial) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(initial) { mutableStateOf((initial?.port ?: 465).toString()) }
    var security by remember(initial) { mutableStateOf(initial?.security ?: SmtpSecurity.TLS) }
    var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var from by remember(initial) { mutableStateOf(initial?.from.orEmpty()) }
    var to by remember(initial) { mutableStateOf(initial?.to.orEmpty()) }
    var result by remember { mutableStateOf<MailTestResult?>(null) }
    var testing by remember { mutableStateOf(false) }
    var justSaved by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // Flip the button label to "Saved" for a moment. A silent onSave() left the user
    // wondering whether the tap registered at all.
    LaunchedEffect(justSaved) {
        if (justSaved) {
            delay(1_500)
            justSaved = false
        }
    }

    val incomplete = stringResource(R.string.settings_email_incomplete)
    val sent = stringResource(R.string.settings_email_sent)

    SectionCard(title = stringResource(R.string.settings_email)) {
        Text(
            text = stringResource(R.string.settings_email_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
        )
        HintBox(text = stringResource(R.string.settings_email_hint))
        NeumorphicTextField(host, { host = it }, stringResource(R.string.settings_email_host))
        NeumorphicTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = stringResource(R.string.settings_email_port),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        ChipFlow {
            SmtpSecurity.entries.forEach { option ->
                NeumorphicChip(
                    label = option.name,
                    selected = security == option,
                    onClick = { security = option },
                )
            }
        }
        NeumorphicTextField(username, { username = it }, stringResource(R.string.settings_email_username))
        NeumorphicTextField(
            value = password,
            onValueChange = { password = it },
            label = stringResource(R.string.settings_email_password),
            visualTransformation = PasswordVisualTransformation(),
        )
        NeumorphicTextField(from, { from = it }, stringResource(R.string.settings_email_from))
        NeumorphicTextField(to, { to = it }, stringResource(R.string.settings_email_to))

        val config = SmtpConfig(
            host = host.trim(),
            port = port.toIntOrNull() ?: 465,
            security = security,
            username = username.trim(),
            password = password,
            from = from.trim(),
            to = to.trim(),
        )

        NeumorphicButton(
            onClick = {
                onSave(config)
                result = null
                justSaved = true
                Toast.makeText(context, R.string.settings_email_saved, Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.fillMaxWidth(),
            accent = true,
        ) {
            if (justSaved) {
                TodoIcon(
                    kind = TodoIconKind.CHECK,
                    size = 16.dp,
                    tint = palette.onAccent,
                    strokeWidth = 2.dp,
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = stringResource(
                    if (justSaved) R.string.settings_email_saved else R.string.settings_email_save,
                ),
                color = palette.onAccent,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        NeumorphicButton(
            onClick = {
                if (!config.isConfigured) {
                    result = MailTestResult.Failed(MailErrorKind.CONFIG, incomplete)
                    return@NeumorphicButton
                }
                onSave(config)
                testing = true
                result = null
                onSendTest { outcome ->
                    testing = false
                    result = outcome
                }
            },
            enabled = !testing,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_email_send_test),
                style = MaterialTheme.typography.labelMedium,
                color = palette.textPrimary,
            )
        }
        when (val outcome = result) {
            null -> Unit
            MailTestResult.Sent -> Text(
                text = sent,
                style = MaterialTheme.typography.bodyMedium,
                color = palette.success,
            )
            is MailTestResult.Failed -> {
                Text(
                    text = mailErrorTitle(outcome.kind),
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.danger,
                )
                if (outcome.detail.isNotBlank()) {
                    Text(
                        text = outcome.detail.take(240),
                        style = MaterialTheme.typography.labelSmall,
                        color = palette.textSecondary,
                    )
                }
            }
        }
    }
}
