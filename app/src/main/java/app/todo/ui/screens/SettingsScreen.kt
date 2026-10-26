package app.todo.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import app.todo.R
import app.todo.data.mail.SmtpConfig
import app.todo.data.mail.SmtpSecurity
import app.todo.domain.model.TodoSort
import app.todo.ui.TodoViewModel
import app.todo.ui.components.DropdownField
import app.todo.ui.components.LabeledValueRow
import app.todo.ui.components.NeumorphicButton
import app.todo.ui.components.NeumorphicChip
import app.todo.ui.components.NeumorphicTextField
import app.todo.ui.components.ScreenHeader
import app.todo.ui.components.SectionCard
import app.todo.ui.components.SwitchRow
import app.todo.ui.theme.LocalTodoPalette
import app.todo.ui.theme.Palettes

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
                Palettes.all.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            NeumorphicChip(
                                label = stringResource(option.labelRes),
                                selected = themeId == option.id,
                                onClick = { vm.setTheme(option.id) },
                            )
                        }
                    }
                }
                SwitchRow(
                    title = stringResource(R.string.home_show_done),
                    checked = showDone,
                    onCheckedChange = vm::setShowDone,
                )
                Text(
                    text = "${stringResource(R.string.home_sort)}: ${
                        stringResource(
                            when (sort) {
                                TodoSort.DUE -> R.string.home_sort_due
                                TodoSort.PRIORITY -> R.string.home_sort_priority
                                TodoSort.CREATED -> R.string.home_sort_created
                            },
                        )
                    }",
                    style = MaterialTheme.typography.bodyMedium,
                    color = palette.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    onSendTest: ((String?) -> Unit) -> Unit,
) {
    val palette = LocalTodoPalette.current
    var host by remember(initial) { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember(initial) { mutableStateOf((initial?.port ?: 465).toString()) }
    var security by remember(initial) { mutableStateOf(initial?.security ?: SmtpSecurity.TLS) }
    var username by remember(initial) { mutableStateOf(initial?.username.orEmpty()) }
    var password by remember(initial) { mutableStateOf(initial?.password.orEmpty()) }
    var from by remember(initial) { mutableStateOf(initial?.from.orEmpty()) }
    var to by remember(initial) { mutableStateOf(initial?.to.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusOk by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }

    val incomplete = stringResource(R.string.settings_email_incomplete)
    val sent = stringResource(R.string.settings_email_sent)

    SectionCard(title = stringResource(R.string.settings_email)) {
        Text(
            text = stringResource(R.string.settings_email_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = palette.textSecondary,
        )
        NeumorphicTextField(host, { host = it }, stringResource(R.string.settings_email_host))
        NeumorphicTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit).take(5) },
            label = stringResource(R.string.settings_email_port),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                status = null
                statusOk = false
            },
            accent = true,
        ) {
            Text(
                text = stringResource(R.string.settings_email_save),
                color = palette.onAccent,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        NeumorphicButton(
            onClick = {
                if (!config.isConfigured) {
                    status = incomplete
                    return@NeumorphicButton
                }
                onSave(config)
                testing = true
                status = null
                statusOk = false
                onSendTest { error ->
                    testing = false
                    statusOk = error == null
                    status = error ?: sent
                }
            },
            enabled = !testing,
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_email_send_test),
                style = MaterialTheme.typography.labelMedium,
                color = palette.textPrimary,
            )
        }
        if (status != null) {
            Text(
                text = status.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = if (statusOk) palette.success else palette.danger,
            )
        }
    }
}
