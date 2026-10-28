package dev.argus.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import dev.argus.ui.theme.ArgusMono
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.ui.model.AuthState
import dev.argus.ui.model.BgLocationState
import dev.argus.ui.model.BudgetUi
import dev.argus.ui.model.ContactRow
import dev.argus.ui.model.ProviderChoiceUi
import dev.argus.ui.model.SettingsCallbacks
import dev.argus.ui.model.SettingsState
import dev.argus.ui.model.ShizukuStatus
import dev.argus.ui.model.TransportUi
import dev.argus.ui.components.BridgeConfigurationDialog
import dev.argus.ui.components.BudgetLimitsDialog
import dev.argus.ui.components.ConfirmationState
import dev.argus.ui.components.DestructiveConfirmDialog
import dev.argus.ui.components.PauseSettingRow
import dev.argus.ui.components.ProviderConfigurationDialog
import dev.argus.ui.components.PrivilegesSection
import dev.argus.ui.components.RulesBackupSection
import dev.argus.ui.components.WarningBanner
import dev.argus.engine.safety.Severity
import dev.argus.ui.model.UiWarning
import dev.argus.ui.model.ProviderUsageUi
import dev.argus.ui.preview.Fixtures
import dev.argus.ui.presentation.BudgetFormat
import dev.argus.ui.presentation.secondaryText
import dev.argus.ui.R
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.LocalArgusSemantic

// =============================================================================
// Schermo Sistema / Settings (handoff §6.5, design §7.5).
// Sezioni: Salute permessi · Brain/transport · Whitelist contatti · Budget LLM,
// più "Ripeti configurazione" e footer versione.
// Vincoli resi qui:
//  - Ogni riga Salute è VERDE (ok) o porta una CTA "Correggi"; bg-location con
//    tinta ambra se non GRANTED (§6.5/§7.5).
//  - Indirizzo bridge in MONOSPACE (§2/§7.5); id contatti MASCHERATI in monospace.
//  - Nota whitelist VERBATIM: "…per conversationId, non per nome — spoofabile"
//    (invariante §6.5: il match è per id, spoofabile).
// Colori/typografia solo da token (tinte derivate per alpha). Stateless (state, callbacks).
// =============================================================================

@Composable
fun SettingsScreen(
    state: SettingsState,
    callbacks: SettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    var showBridgeEditor by remember { mutableStateOf(false) }
    var confirmPrivacyRevoke by remember { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                stringResource(R.string.settings_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 18.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            )

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                HealthSection(state, callbacks)
                PrivilegesSection(state.privileged, callbacks::onSetRootEnabled, callbacks::onGrantPermanent)
                PauseSettingRow(state.automationsPaused, callbacks::onSetAutomationsPaused)
                TransportSection(state.transport, state.providerChoices, callbacks) { showBridgeEditor = true }
                PrivacySection(state.privacyAccepted) { confirmPrivacyRevoke = true }
                WhitelistSection(state.whitelist, callbacks)
                BudgetSection(state.budget, callbacks)
                RulesBackupSection(
                    state.backup,
                    callbacks::onExportRules,
                    callbacks::onImportRules,
                    callbacks::onDismissBackupResult,
                    callbacks::onOpenImportedRules,
                )
                RerunRow(callbacks::onRerunOnboarding)
                VersionFooter(state.appVersionLabel)
                Spacer(Modifier.size(8.dp))
            }
        }
    }
    if (showBridgeEditor) {
        when (val transport = state.transport) {
            is TransportUi.CliBridge -> BridgeConfigurationDialog(
                initialUrl = transport.url,
                tokenConfigured = transport.tokenConfigured,
                onDismiss = { showBridgeEditor = false },
                onSave = callbacks::onSaveBridge,
            )
            is TransportUi.DirectProvider -> ProviderConfigurationDialog(
                provider = transport,
                onDismiss = { showBridgeEditor = false },
                onSave = { baseUrl, model, apiKey, reasoningEffort ->
                    callbacks.onSaveProviderConfig(
                        transport.providerId, baseUrl, model, apiKey, reasoningEffort,
                    )
                },
            )
        }
    }
    if (confirmPrivacyRevoke) {
        // B48: revocare il consenso blocca subito l'AI; il pulsante usa il colore di errore del tema.
        DestructiveConfirmDialog(
            title = stringResource(R.string.privacy_revoke_title),
            body = stringResource(R.string.privacy_revoke_body),
            confirmLabel = stringResource(R.string.privacy_revoke_confirm),
            onConfirm = {
                confirmPrivacyRevoke = false
                callbacks.onRevokePrivacy()
            },
            onDismiss = { confirmPrivacyRevoke = false },
        )
    }
}

// -----------------------------------------------------------------------------
// Sezione riutilizzabile: label uppercase + contenuto
// -----------------------------------------------------------------------------

@Composable
private fun SettingsSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        content()
    }
}

/** Card contenitore di sezione (surface/1, bordo tenue). */
@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

// -----------------------------------------------------------------------------
// Salute permessi
// -----------------------------------------------------------------------------

private enum class HealthLevel { OK, WARN, NEUTRAL }

@Composable
private fun HealthSection(state: SettingsState, callbacks: SettingsCallbacks) {
    val shizukuSub = when (state.shizuku) {
        ShizukuStatus.AUTHORIZED -> if (state.shizukuRunsAsRoot == true) {
            stringResource(R.string.settings_shizuku_ok_root)
        } else {
            stringResource(R.string.settings_shizuku_ok)
        }
        ShizukuStatus.NOT_INSTALLED -> stringResource(R.string.settings_shizuku_not_installed)
        ShizukuStatus.INSTALLED_NOT_RUNNING -> stringResource(R.string.settings_shizuku_not_running)
        ShizukuStatus.RUNNING_NOT_AUTHORIZED -> stringResource(R.string.settings_shizuku_not_authorized)
        ShizukuStatus.DEGRADED_AFTER_REBOOT -> stringResource(R.string.settings_shizuku_degraded)
        ShizukuStatus.UNSUPPORTED -> stringResource(R.string.settings_shizuku_unsupported)
    }
    val (locLevel, locSub) = when (state.backgroundLocation) {
        BgLocationState.GRANTED -> HealthLevel.OK to stringResource(R.string.settings_location_granted)
        BgLocationState.WHILE_IN_USE -> HealthLevel.WARN to stringResource(R.string.settings_location_while_in_use)
        BgLocationState.DENIED -> HealthLevel.WARN to stringResource(R.string.settings_location_denied)
        BgLocationState.NOT_NEEDED -> HealthLevel.NEUTRAL to stringResource(R.string.settings_location_not_needed)
    }

    SettingsSection(stringResource(R.string.settings_section_health)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HealthRow(
                Icons.Rounded.Terminal, stringResource(R.string.settings_shizuku_title), shizukuSub,
                if (state.shizuku == ShizukuStatus.AUTHORIZED) HealthLevel.OK else HealthLevel.WARN,
                onFix = callbacks::onOpenShizukuFix,
            )
            HealthRow(
                Icons.Rounded.BatterySaver, stringResource(R.string.settings_battery_title),
                if (state.batteryExempt) {
                    stringResource(R.string.settings_battery_ok)
                } else {
                    stringResource(R.string.settings_battery_warn)
                },
                if (state.batteryExempt) HealthLevel.OK else HealthLevel.WARN,
                onFix = callbacks::onOpenBatteryFix,
            )
            HealthRow(
                Icons.Rounded.Alarm, stringResource(R.string.settings_exact_alarms_title),
                if (state.exactAlarmsGranted) {
                    stringResource(R.string.settings_exact_alarms_ok)
                } else {
                    stringResource(R.string.settings_exact_alarms_warn)
                },
                if (state.exactAlarmsGranted) HealthLevel.OK else HealthLevel.WARN,
                onFix = callbacks::onOpenExactAlarmFix,
            )
            // 0.4.1: permesso speciale per le azioni luminosità e rotazione (Android lo concede solo da qui).
            HealthRow(
                Icons.Rounded.Settings, stringResource(R.string.act41_settings_write_title),
                if (state.writeSettingsGranted) {
                    stringResource(R.string.act41_settings_write_ok)
                } else {
                    stringResource(R.string.act41_settings_write_warn)
                },
                if (state.writeSettingsGranted) HealthLevel.OK else HealthLevel.WARN,
                onFix = callbacks::onOpenWriteSettingsFix,
            )
            // 0.5.1: accesso ai dati di utilizzo per le regole «app aperta/chiusa» (si concede solo da qui,
            // oppure con Shizuku/root da «Concedi i permessi permanenti»).
            HealthRow(
                Icons.Rounded.Apps, stringResource(R.string.app51_settings_usage_title),
                if (state.usageAccessGranted) {
                    stringResource(R.string.app51_settings_usage_ok)
                } else {
                    stringResource(R.string.app51_settings_usage_warn)
                },
                if (state.usageAccessGranted) HealthLevel.OK else HealthLevel.WARN,
                onFix = callbacks::onOpenUsageAccessFix,
            )
            // 0.5.1: servizio di Accessibilità, serve alle macro sullo schermo (si accende solo da lì).
            HealthRow(
                Icons.Rounded.TouchApp, stringResource(R.string.a11y51_settings_title),
                if (state.accessibilityGranted) {
                    stringResource(R.string.a11y51_settings_ok)
                } else {
                    stringResource(R.string.a11y51_settings_warn)
                },
                if (state.accessibilityGranted) HealthLevel.OK else HealthLevel.WARN,
                onFix = callbacks::onOpenAccessibilityFix,
            )
            HealthRow(
                Icons.Rounded.Notifications, stringResource(R.string.settings_notifications_title),
                if (state.notificationsGranted) stringResource(R.string.settings_notifications_ok) else stringResource(R.string.settings_notifications_warn),
                if (state.notificationsGranted) HealthLevel.OK else HealthLevel.WARN,
                onFix = callbacks::onOpenNotificationAccessFix,
            )
            HealthRow(
                Icons.Rounded.Notifications, stringResource(R.string.settings_listener_title),
                if (state.notificationListenerGranted) {
                    stringResource(R.string.settings_listener_ok)
                } else {
                    stringResource(R.string.settings_listener_warn)
                },
                if (state.notificationListenerGranted) HealthLevel.OK else HealthLevel.WARN,
                onFix = callbacks::onOpenNotificationListenerFix,
            )
            // Android 13+ con installazione da APK: l'accesso può essere bloccato da «Impostazione con
            // restrizioni»; la guida compare solo se l'accesso non è concesso.
            if (state.notificationListenerRestrictedHint) {
                Text(
                    stringResource(R.string.settings_listener_restricted_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 14.dp),
                )
            }
            HealthRow(
                Icons.Rounded.MyLocation, stringResource(R.string.settings_location_title), locSub, locLevel,
                onFix = callbacks::onOpenLocationFix,
            )
            // Telefonia (P2-2): opt-in, non un problema di salute — NEUTRAL finché non
            // concesso, il tap lancia direttamente la richiesta runtime.
            HealthRow(
                Icons.Rounded.Sms, stringResource(R.string.settings_sms_title),
                if (state.smsTriggerGranted) {
                    stringResource(R.string.settings_sms_on)
                } else {
                    stringResource(R.string.settings_sms_off)
                },
                if (state.smsTriggerGranted) HealthLevel.OK else HealthLevel.NEUTRAL,
                onFix = callbacks::onRequestSmsPermission,
                actionLabel = if (state.smsTriggerGranted) null else stringResource(R.string.settings_enable_action),
            )
            HealthRow(
                Icons.Rounded.Call, stringResource(R.string.settings_call_title),
                if (state.callTriggerGranted) {
                    stringResource(R.string.settings_call_on)
                } else {
                    stringResource(R.string.settings_call_off)
                },
                if (state.callTriggerGranted) HealthLevel.OK else HealthLevel.NEUTRAL,
                onFix = callbacks::onRequestCallPermissions,
                actionLabel = if (state.callTriggerGranted) null else stringResource(R.string.settings_enable_action),
            )
            HealthRow(
                Icons.Rounded.Bluetooth, stringResource(R.string.settings_bt_title),
                if (state.bluetoothTriggerGranted) {
                    stringResource(R.string.settings_bt_on)
                } else {
                    stringResource(R.string.settings_bt_off)
                },
                if (state.bluetoothTriggerGranted) HealthLevel.OK else HealthLevel.NEUTRAL,
                onFix = callbacks::onRequestBluetoothPermission,
                actionLabel = if (state.bluetoothTriggerGranted) null else stringResource(R.string.settings_enable_action),
            )
            if (state.connectivitySentinelActive) {
                // B50: solo informativa, non c'è una schermata da aprire: niente affordance cliccabile.
                HealthRow(
                    Icons.Rounded.Hub,
                    stringResource(R.string.settings_sentinel_title),
                    stringResource(R.string.settings_sentinel_active),
                    HealthLevel.OK,
                    onFix = null,
                )
            }
        }
    }
}

@Composable
private fun HealthRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    level: HealthLevel,
    /** Azione della riga. `null` = riga solo informativa: né tocco né pulsante (B50). */
    onFix: (() -> Unit)?,
    /** Azione esplicita per le righe opt-in (es. "Attiva"): senza, NEUTRAL/OK non sono
     *  cliccabili e onFix vive solo nel "Correggi" dello stato WARN. */
    actionLabel: String? = null,
) {
    val semantic = LocalArgusSemantic.current
    val warn = level == HealthLevel.WARN
    val tintBg = if (warn) semantic.pending.fg.copy(alpha = 0.06f) else Color.Transparent
    val borderColor = if (warn) semantic.pending.fg.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // Tutta la riga è tappabile, in QUALSIASI stato: apre il pannello/permesso
            // relativo (feedback Lorenzo: le righe verdi/grigie erano mute al tocco). Le righe
            // solo informative (onFix == null) non sembrano tappabili e non lo sono (B50).
            .then(if (onFix != null) Modifier.clickable { onFix() } else Modifier)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .background(tintBg)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (warn) semantic.pending.fg else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        when {
            onFix != null && level == HealthLevel.WARN ->
                OutlinedButton(onClick = onFix, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.settings_fix)) }
            onFix != null && actionLabel != null ->
                OutlinedButton(onClick = onFix, modifier = Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
            // B50: la descrizione per TalkBack è una stringa tradotta, non più «ok» in inglese.
            level == HealthLevel.OK ->
                Icon(Icons.Rounded.CheckCircle, contentDescription = stringResource(R.string.ui40_health_ok_cd), tint = semantic.armed.fg, modifier = Modifier.size(22.dp))
            else ->
                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

// -----------------------------------------------------------------------------
// Brain · transport
// -----------------------------------------------------------------------------

@Composable
private fun TransportSection(
    transport: TransportUi,
    providerChoices: List<ProviderChoiceUi>,
    callbacks: SettingsCallbacks,
    onEditTransport: () -> Unit,
) {
    val onEditBridge = onEditTransport
    val semantic = LocalArgusSemantic.current
    SettingsSection(stringResource(R.string.settings_section_transport)) {
        SectionCard {
            if (providerChoices.isNotEmpty()) {
                ProviderSelector(providerChoices, callbacks::onSelectProvider)
            }
            when (transport) {
                is TransportUi.CliBridge -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(stringResource(R.string.settings_clibridge_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        val (reachColor, reachText) = when (transport.reachable) {
                            true -> semantic.armed.fg to stringResource(R.string.settings_reachable)
                            false -> semantic.error.fg to stringResource(R.string.settings_unreachable)
                            null -> MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.settings_unverified)
                        }
                        Text(reachText, color = reachColor, style = MaterialTheme.typography.bodyMedium)
                    }
                    // Indirizzo bridge in monospace, tappabile → editor (host).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .clickable(onClick = onEditBridge)
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            transport.url,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = ArgusMono,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.settings_edit_bridge_cd), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        stringResource(
                            if (transport.tokenConfigured) R.string.bridge_token_configured
                            else R.string.bridge_token_missing,
                        ),
                        color = if (transport.tokenConfigured) semantic.armed.fg else semantic.error.fg,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            transport.lastLatencyLabel?.let { stringResource(R.string.settings_latency_last, it) }
                                ?: stringResource(R.string.settings_latency_none),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = callbacks::onTestConnection, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.settings_test_connection))
                        }
                    }
                }
                is TransportUi.DirectProvider -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(
                            "${transport.providerLabel} · ${transport.model ?: stringResource(R.string.settings_model_not_set)}",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        val (reachColor, reachText) = when (transport.reachable) {
                            true -> semantic.armed.fg to stringResource(R.string.settings_reachable)
                            false -> semantic.error.fg to stringResource(R.string.settings_unreachable)
                            null -> MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.settings_unverified)
                        }
                        Text(reachText, color = reachColor, style = MaterialTheme.typography.bodyMedium)
                    }
                    // Endpoint monospace, tappabile → editor (host).
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.background)
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp))
                            .clickable(onClick = onEditBridge)
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            transport.baseUrl,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = ArgusMono,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Rounded.Edit, contentDescription = stringResource(R.string.settings_edit_provider_cd, transport.providerLabel), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        if (transport.authState == AuthState.OK) stringResource(R.string.settings_key_configured) else stringResource(R.string.settings_key_missing),
                        color = if (transport.authState == AuthState.OK) semantic.armed.fg else semantic.error.fg,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (transport.unauthenticatedLoopback) {
                        WarningBanner(
                            UiWarning(
                                Severity.WARNING,
                                "unauthenticated_loopback",
                                stringResource(R.string.settings_unauthenticated_loopback),
                            ),
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            transport.lastLatencyLabel?.let { stringResource(R.string.settings_latency_last, it) }
                                ?: stringResource(R.string.settings_latency_none),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = callbacks::onTestConnection, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text(stringResource(R.string.settings_test_connection))
                        }
                    }
                }
            }
            // Alternativa futura sempre segnalata come roadmap (P3), non allarmistica.
            if (transport is TransportUi.CliBridge) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.settings_streaming_label),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    InArrivoChip()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProviderSelector(choices: List<ProviderChoiceUi>, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        choices.forEach { choice ->
            FilterChip(
                selected = choice.selected,
                onClick = { onSelect(choice.id) },
                label = { Text(choice.label) },
            )
        }
    }
}

@Composable
private fun InArrivoChip() {
    Text(
        stringResource(R.string.settings_coming_soon),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

@Composable
private fun PrivacySection(accepted: Boolean, onRevoke: () -> Unit) {
    SettingsSection(stringResource(R.string.settings_section_privacy)) {
        SectionCard {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Rounded.PrivacyTip,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    if (accepted) stringResource(R.string.settings_privacy_active) else stringResource(R.string.settings_privacy_missing),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(
                stringResource(R.string.privacy_section_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (accepted) {
                OutlinedButton(
                    onClick = onRevoke,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.privacy_revoke_action))
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Whitelist contatti
// -----------------------------------------------------------------------------

@Composable
private fun WhitelistSection(contacts: List<ContactRow>, callbacks: SettingsCallbacks) {
    // B48: rimuovere un contatto toglie il permesso di risposta automatica; prima si chiede conferma.
    var confirmRemove by remember { mutableStateOf(ConfirmationState<ContactRow>()) }
    confirmRemove.pending?.let { contact ->
        DestructiveConfirmDialog(
            title = stringResource(R.string.ui40_contact_remove_title),
            body = stringResource(R.string.ui40_contact_remove_body, contact.displayName),
            confirmLabel = stringResource(R.string.ui40_contact_remove_confirm),
            onConfirm = {
                confirmRemove = confirmRemove.confirm { callbacks.onRemoveContact(it.conversationId) }
            },
            onDismiss = { confirmRemove = confirmRemove.cancel() },
        )
    }
    SettingsSection(stringResource(R.string.settings_section_whitelist)) {
        SectionCard {
            // Nota VERBATIM (§6.5): il match è per conversationId, spoofabile.
            Text(
                stringResource(R.string.settings_whitelist_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            contacts.forEach { contact ->
                ContactRowItem(contact, onRemove = { confirmRemove = confirmRemove.ask(contact) })
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { callbacks.onAddContact() }
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 4.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                Text(stringResource(R.string.settings_add_contact), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun ContactRowItem(contact: ContactRow, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(11.dp),
    ) {
        // Avatar iniziale (nessuna PII oltre il nome scelto dall'utente).
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                contact.displayName.trim().take(1).uppercase(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(contact.displayName, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
            // id mascherato in monospace: non esporre il numero/handle completo.
            Text(
                maskConversationId(contact.conversationId),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = ArgusMono,
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable { onRemove() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.settings_remove_contact_cd, contact.displayName), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

/** Maschera l'id di conversazione: tiene lo schema/inizio e la coda, oscura il resto. */
private fun maskConversationId(id: String): String {
    if (id.length <= 10) return id
    return id.take(6) + "••••" + id.takeLast(4)
}

// -----------------------------------------------------------------------------
// Budget LLM
// -----------------------------------------------------------------------------

@Composable
private fun BudgetSection(budget: BudgetUi, callbacks: SettingsCallbacks) {
    val semantic = LocalArgusSemantic.current
    var showLimitsEditor by remember { mutableStateOf(false) }
    SettingsSection(stringResource(R.string.settings_section_budget)) {
        SectionCard {
            if (budget.softWarningActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(semantic.pending.fg.copy(alpha = 0.06f))
                        .border(1.dp, semantic.pending.fg.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        stringResource(R.string.settings_budget_soft),
                        color = semantic.pending.fg,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            BudgetCallsRow(stringResource(R.string.settings_budget_calls_hour), budget.usedHour, budget.limitHour)
            BudgetCallsRow(stringResource(R.string.settings_budget_calls_day), budget.usedDay, budget.limitDay)
            BudgetTokensRow(budget.tokensInMonth, budget.tokensOutMonth, budget.tokenLimitMonth)
            BudgetCostRow(budget.costMonthMicros, budget.costLimitMicros)
            if (budget.perProvider.isNotEmpty()) {
                budget.perProvider.forEach { provider ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            provider.providerLabel,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        // Metrica primaria: TOKEN input/output per finestra.
                        Text(
                            stringResource(
                                R.string.settings_budget_provider_tokens,
                                BudgetFormat.tokensLabel(provider.tokensInHour, provider.tokensOutHour),
                                BudgetFormat.tokensLabel(provider.tokensInDay, provider.tokensOutDay),
                                BudgetFormat.tokensLabel(provider.tokensInMonth, provider.tokensOutMonth),
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(R.string.settings_budget_provider_calls, provider.callsHour, provider.callsDay),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        provider.reasoningTokensMonth?.let { reasoningTokens ->
                            Text(
                                stringResource(R.string.settings_budget_provider_reasoning, BudgetFormat.grouped(reasoningTokens)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        provider.lastFinishReason?.let { finishReason ->
                            Text(
                                stringResource(R.string.settings_budget_provider_finish, finishReason),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        // Dollari SOLO per i provider a listino noto: mai per Hermes/OpenRouter/Custom.
                        if (provider.costTracked) {
                            Text(
                                stringResource(R.string.settings_budget_provider_cost, BudgetFormat.costLabel(provider.costMonthMicros)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
            Text(
                stringResource(R.string.settings_budget_footnote),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            OutlinedButton(
                onClick = { showLimitsEditor = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text(stringResource(R.string.settings_budget_edit_limits)) }
        }
    }
    if (showLimitsEditor) {
        BudgetLimitsDialog(
            initialMaxPerHour = budget.limitHour,
            initialMaxPerDay = budget.limitDay,
            initialMaxCostMonthMicros = budget.costLimitMicros,
            initialMaxTokensMonth = budget.tokenLimitMonth,
            onDismiss = { showLimitsEditor = false },
            onSave = { maxPerHour, maxPerDay, maxCostMonthMicros, maxTokensMonth ->
                callbacks.onSaveBudgetLimits(maxPerHour, maxPerDay, maxCostMonthMicros, maxTokensMonth)
            },
        )
    }
}

@Composable
private fun BudgetCallsRow(label: String, used: Long, limit: Int?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                BudgetFormat.callsLabel(used, limit),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = ArgusMono,
            )
        }
        if (limit != null && limit > 0) {
            LinearProgressIndicator(
                progress = { BudgetFormat.ratio(used, limit) },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.background,
            )
        }
    }
}

/** Token mese dei provider token-only (Hermes/OpenRouter/Custom): la loro metrica primaria. */
@Composable
private fun BudgetTokensRow(tokensIn: Long?, tokensOut: Long?, tokenLimitMonth: Long?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_budget_tokens_month),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                BudgetFormat.tokensLabel(tokensIn, tokensOut),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = ArgusMono,
            )
        }
        if (tokenLimitMonth != null && tokenLimitMonth > 0L) {
            val used = (tokensIn ?: 0L) + (tokensOut ?: 0L)
            LinearProgressIndicator(
                progress = { BudgetFormat.tokenRatio(used, tokenLimitMonth) },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.background,
            )
            Text(
                stringResource(R.string.settings_budget_limit, BudgetFormat.tokensCapLabel(used, tokenLimitMonth)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun BudgetCostRow(costMonthMicros: Long?, costLimitMicros: Long?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_budget_cost_month), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                BudgetFormat.costLabel(costMonthMicros),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = ArgusMono,
            )
        }
        if (costLimitMicros != null && costLimitMicros > 0L) {
            LinearProgressIndicator(
                progress = { BudgetFormat.costRatio(costMonthMicros, costLimitMicros) },
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.background,
            )
            Text(
                stringResource(R.string.settings_budget_limit, BudgetFormat.costLabel(costLimitMicros)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Ripeti configurazione + footer versione
// -----------------------------------------------------------------------------

@Composable
private fun RerunRow(onRerun: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .clickable { onRerun() }
            .heightIn(min = 48.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
        Text(stringResource(R.string.settings_rerun), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun VersionFooter(version: String) {
    Text(
        version,
        color = MaterialTheme.colorScheme.secondaryText(),
        style = MaterialTheme.typography.labelSmall,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
    )
}

// =============================================================================
// Preview — fixture inline (i Fixtures centralizzati arrivano in Unit F).
// =============================================================================

private val previewContacts = listOf(
    ContactRow(displayName = "Moglie", conversationId = "wa::393200000000::c1a9"),
)

private object NoopSettingsCallbacks : SettingsCallbacks {
    override fun onEditBridgeUrl(url: String) {}
    override fun onTestConnection() {}
    override fun onOpenShizukuFix() {}
    override fun onOpenBatteryFix() {}
    override fun onOpenExactAlarmFix() {}
    override fun onOpenNotificationAccessFix() {}
    override fun onOpenLocationFix() {}
    override fun onRemoveContact(conversationId: String) {}
    override fun onAddContact() {}
    override fun onBudgetChange(maxPerHour: Int) {}
    override fun onRevokePrivacy() {}
    override fun onRerunOnboarding() {}
}

private val previewProviderChoices = listOf(
    ProviderChoiceUi("hermes", "Hermes (self-hosted)", selected = true),
    ProviderChoiceUi("openai", "OpenAI", selected = false),
    ProviderChoiceUi("anthropic", "Anthropic", selected = false),
    ProviderChoiceUi("gemini", "Google Gemini", selected = false),
    ProviderChoiceUi("openrouter", "OpenRouter", selected = false),
    ProviderChoiceUi("custom_openai_compat", "Custom (OpenAI-compat)", selected = false),
)

private val previewAllGreen = SettingsState(
    transport = TransportUi.CliBridge(url = "https://hermes.example", reachable = true, lastLatencyLabel = "14 s · normale per Hermes"),
    shizuku = ShizukuStatus.AUTHORIZED,
    batteryExempt = true,
    notificationsGranted = true,
    notificationListenerGranted = true,
    backgroundLocation = BgLocationState.GRANTED,
    whitelist = previewContacts,
    budget = BudgetUi(
        usedHour = 3,
        limitHour = 20,
        usedDay = 11,
        limitDay = 100,
        costMonthMicros = 1_870_000,
        costLimitMicros = 5_000_000,
        tokensInMonth = 42_000,
        tokensOutMonth = 6_500,
        tokenLimitMonth = 1_000_000,
        perProvider = listOf(
            ProviderUsageUi(
                "openai", "OpenAI", callsHour = 3, callsDay = 11, costMonthMicros = 1_870_000,
                costTracked = true,
                tokensInHour = 4_200, tokensOutHour = 600,
                tokensInDay = 18_000, tokensOutDay = 2_400,
                tokensInMonth = 310_000, tokensOutMonth = 41_000,
            ),
            ProviderUsageUi(
                "hermes", "Hermes (self-hosted)", callsHour = 0, callsDay = 0, costMonthMicros = null,
                costTracked = false,
                tokensInMonth = 42_000, tokensOutMonth = 6_500,
            ),
        ),
        softWarningActive = false,
    ),
    privacyAccepted = true,
    appVersionLabel = "Argus v0.3.6 · F-Droid",
    providerChoices = previewProviderChoices,
)

private val previewDirectProvider = previewAllGreen.copy(
    transport = TransportUi.DirectProvider(
        providerId = "openai",
        providerLabel = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-6-luna",
        authState = AuthState.OK,
        reachable = true,
        lastLatencyLabel = "2.1 s",
        defaultModels = listOf("gpt-6-luna"),
    ),
    providerChoices = previewProviderChoices.map { it.copy(selected = it.id == "openai") },
)

private val previewDegraded = SettingsState(
    transport = TransportUi.CliBridge(url = "https://hermes.example", reachable = false, lastLatencyLabel = null),
    shizuku = ShizukuStatus.DEGRADED_AFTER_REBOOT,
    batteryExempt = false,
    notificationsGranted = true,
    notificationListenerGranted = true,
    backgroundLocation = BgLocationState.DENIED,
    whitelist = emptyList(),
    budget = BudgetUi(
        usedHour = 17,
        limitHour = 20,
        usedDay = 92,
        limitDay = 100,
        costMonthMicros = 4_500_000,
        costLimitMicros = 5_000_000,
        perProvider = listOf(
            ProviderUsageUi(
                "openai", "OpenAI", callsHour = 17, callsDay = 92, costMonthMicros = 4_500_000,
                costTracked = true,
                tokensInHour = 21_000, tokensOutHour = 3_100,
                tokensInDay = 96_000, tokensOutDay = 14_000,
                tokensInMonth = 1_450_000, tokensOutMonth = 210_000,
            ),
        ),
        softWarningActive = true,
    ),
    privacyAccepted = true,
    appVersionLabel = "Argus v0.3.6 · APK",
)

@Preview(name = "Sistema · tutto verde + 1 contatto · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 1100)

@Preview(name = "Sistema · tutto verde + 1 contatto · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, heightDp = 1100)
@Composable
private fun SettingsAllGreenPreview() {
    ArgusTheme { SettingsScreen(previewAllGreen, NoopSettingsCallbacks) }
}

@Preview(name = "Sistema · Shizuku degradato + batteria non-exempt (CTA) · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 1100)

@Preview(name = "Sistema · Shizuku degradato + batteria non-exempt (CTA) · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, heightDp = 1100)
@Composable
private fun SettingsDegradedPreview() {
    ArgusTheme { SettingsScreen(previewDegraded, NoopSettingsCallbacks) }
}

@Preview(name = "Sistema · Shizuku gira come root · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 1100)

@Preview(name = "Sistema · Shizuku gira come root · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, heightDp = 1100)
@Composable
private fun SettingsShizukuRootPreview() {
    ArgusTheme { SettingsScreen(Fixtures.settingsShizukuRoot, NoopSettingsCallbacks) }
}

@Preview(name = "Sistema · Shizuku troppo vecchio · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 1100)

@Preview(name = "Sistema · Shizuku troppo vecchio · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, heightDp = 1100)
@Composable
private fun SettingsShizukuUnsupportedPreview() {
    ArgusTheme { SettingsScreen(Fixtures.settingsShizukuUnsupported, NoopSettingsCallbacks) }
}

@Preview(name = "Sistema · provider diretto (OpenAI) · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 1100)

@Preview(name = "Sistema · provider diretto (OpenAI) · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, heightDp = 1100)
@Composable
private fun SettingsDirectProviderPreview() {
    ArgusTheme { SettingsScreen(previewDirectProvider, NoopSettingsCallbacks) }
}

@Preview(name = "Sistema · guida impostazioni con restrizioni (APK, Android 13+) · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 1100)

@Preview(name = "Sistema · guida impostazioni con restrizioni (APK, Android 13+) · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, heightDp = 1100)
@Composable
private fun SettingsRestrictedHintPreview() {
    ArgusTheme { SettingsScreen(Fixtures.settingsRestrictedHint, NoopSettingsCallbacks) }
}

@Preview(name = "Sistema · sveglie esatte non concesse (CTA) · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 1100)

@Preview(name = "Sistema · sveglie esatte non concesse (CTA) · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, heightDp = 1100)
@Composable
private fun SettingsExactAlarmsDeniedPreview() {
    ArgusTheme { SettingsScreen(Fixtures.settingsExactAlarmsDenied, NoopSettingsCallbacks) }
}

@Preview(name = "Sistema · modifica impostazioni di sistema non concessa (CTA) · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 1100)

@Preview(name = "Sistema · modifica impostazioni di sistema non concessa (CTA) · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, heightDp = 1100)
@Composable
private fun SettingsWriteSettingsDeniedPreview() {
    ArgusTheme { SettingsScreen(Fixtures.settingsWriteSettingsDenied, NoopSettingsCallbacks) }
}

@Preview(name = "Sistema · accesso ai dati di utilizzo non concesso (CTA) · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 1100)

@Preview(name = "Sistema · accesso ai dati di utilizzo non concesso (CTA) · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, heightDp = 1100)
@Composable
private fun SettingsUsageAccessDeniedPreview() {
    ArgusTheme { SettingsScreen(Fixtures.settingsUsageAccessDenied, NoopSettingsCallbacks) }
}

@Preview(name = "Sistema · Accessibilità spenta (CTA) · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, heightDp = 1100)

@Preview(name = "Sistema · Accessibilità spenta (CTA) · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO, heightDp = 1100)
@Composable
private fun SettingsAccessibilityOffPreview() {
    ArgusTheme { SettingsScreen(Fixtures.settingsAccessibilityOff, NoopSettingsCallbacks) }
}
