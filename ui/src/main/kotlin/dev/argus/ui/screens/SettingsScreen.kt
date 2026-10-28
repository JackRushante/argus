package dev.argus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.argus.engine.safety.Severity
import dev.argus.ui.R
import dev.argus.ui.components.ArgusButton
import dev.argus.ui.components.ArgusCard
import dev.argus.ui.components.ArgusIconButton
import dev.argus.ui.components.ArgusLargeTopBar
import dev.argus.ui.components.BudgetLimitsDialog
import dev.argus.ui.components.ButtonStyle
import dev.argus.ui.components.ConfirmationState
import dev.argus.ui.components.DestructiveConfirmDialog
import dev.argus.ui.components.HealthBanner
import dev.argus.ui.components.HealthTile
import dev.argus.ui.components.HealthTileLevel
import dev.argus.ui.components.BannerTone
import dev.argus.ui.components.IconBadge
import dev.argus.ui.components.OutlineChip
import dev.argus.ui.components.PauseSettingRow
import dev.argus.ui.components.PrivilegesSection
import dev.argus.ui.components.RulesBackupSection
import dev.argus.ui.components.SectionLabel
import dev.argus.ui.components.StatusChip
import dev.argus.ui.components.SystemBigValue
import dev.argus.ui.components.SystemDotChip
import dev.argus.ui.components.SystemHealthCompactTile
import dev.argus.ui.components.SystemMediumValue
import dev.argus.ui.components.SystemMeterCard
import dev.argus.ui.components.SystemNavItem
import dev.argus.ui.components.SystemSectionNav
import dev.argus.ui.components.TransportConfigurationSheet
import dev.argus.ui.components.WarningBanner
import dev.argus.ui.components.groupPositionOf
import dev.argus.ui.components.groupShape
import dev.argus.ui.model.AuthState
import dev.argus.ui.model.BgLocationState
import dev.argus.ui.model.BudgetUi
import dev.argus.ui.model.ContactRow
import dev.argus.ui.model.ProviderUsageUi
import dev.argus.ui.model.SettingsCallbacks
import dev.argus.ui.model.SettingsState
import dev.argus.ui.model.ShizukuStatus
import dev.argus.ui.model.TransportUi
import dev.argus.ui.model.UiWarning
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.preview.Fixtures
import dev.argus.ui.presentation.BudgetFormat
import dev.argus.ui.presentation.SystemHealthLayout
import dev.argus.ui.presentation.secondaryText
import dev.argus.ui.theme.ArgusCodeSmall
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme
import kotlinx.coroutines.launch

// =============================================================================
// Schermo Sistema / Settings (handoff §6.5, design §7.5; redesign A, tavole «Sistema» e «Configura provider»).
// Sezioni: Salute permessi · Privilegi · Pausa · Brain/transport · Privacy · Whitelist contatti · Budget LLM ·
// Backup, poi "Ripeti configurazione" e footer versione. Sotto il titolo, i chip «Vai a una sezione».
// Vincoli resi qui:
//  - Ogni tessera Salute e' VERDE (ok), GRIGIA (opt-in) o ambra con "Correggi"; i problemi vanno in cima e hanno
//    il conteggio «N da correggere» (anche sul chip della navigazione).
//  - Indirizzo bridge / endpoint / modello in MONOSPACE; id contatti MASCHERATI in monospace (§2/§7.5).
//  - La chiave API non compare MAI: si legge solo «Chiave configurata»; il campo del foglio e' sempre vuoto (inv. 7-8).
//  - «Revoca consenso» e' distruttiva e apre una conferma. Rimuovere un contatto idem.
//  - Nota whitelist VERBATIM: "…per conversationId, non per nome — spoofabile" (invariante §6.5).
//  - Un valore assente (token, costo) si scrive «n/d», mai 0 (invariante 8).
// Stateless: (state, callbacks). Colori e tipografia solo dai token del tema.
// =============================================================================

@Composable
fun SettingsScreen(
    state: SettingsState,
    callbacks: SettingsCallbacks,
    modifier: Modifier = Modifier,
) {
    var showTransportSheet by rememberSaveable { mutableStateOf(false) }
    var confirmPrivacyRevoke by remember { mutableStateOf(false) }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    // Posizione (in coordinate radice) del bordo alto del viewport e di ogni sezione: i chip «Vai a una sezione»
    // scorrono fin li'. Mappa semplice, non stato: serve solo al tocco, mai alla composizione.
    val viewportTop = remember { FloatArray(1) }
    val sectionTops = remember { HashMap<String, Float>() }
    val marginPx = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() }
    fun Modifier.sectionAnchor(id: String): Modifier =
        onGloballyPositioned { sectionTops[id] = it.positionInRoot().y }

    val healthItems = buildHealthItems(state, callbacks)
    val needsFix = healthItems.count { it is HealthItem.Tile && it.spec.level == HealthTileLevel.Warn }
    val navItems = listOf(
        SystemNavItem(
            id = SECTION_HEALTH,
            label = stringResource(R.string.rda_ls_nav_health),
            badge = needsFix.takeIf { it > 0 },
            badgeDescription = if (needsFix > 0) stringResource(R.string.rda_ls_nav_health_cd, needsFix) else null,
        ),
        SystemNavItem(SECTION_BRAIN, stringResource(R.string.rda_ls_nav_brain)),
        SystemNavItem(SECTION_PRIVACY, stringResource(R.string.rda_ls_nav_privacy)),
        SystemNavItem(SECTION_WHITELIST, stringResource(R.string.rda_ls_nav_whitelist)),
        SystemNavItem(SECTION_BUDGET, stringResource(R.string.rda_ls_nav_budget)),
    )

    Surface(color = ArgusTheme.surfaces.page, modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { viewportTop[0] = it.positionInRoot().y }
                .verticalScroll(scroll),
        ) {
            ArgusLargeTopBar(stringResource(R.string.settings_title))
            SystemSectionNav(
                items = navItems,
                groupDescription = stringResource(R.string.rda_ls_nav_group),
                modifier = Modifier.padding(top = ArgusSpacing.Unit),
                onSelect = { id ->
                    sectionTops[id]?.let { top ->
                        val target = (scroll.value + top - viewportTop[0] - marginPx).toInt().coerceAtLeast(0)
                        scope.launch { scroll.animateScrollTo(target) }
                    }
                },
            )

            Column(
                modifier = Modifier.padding(start = ArgusSpacing.Large, end = ArgusSpacing.Large, top = ArgusSpacing.Section),
                verticalArrangement = Arrangement.spacedBy(28.dp),
            ) {
                HealthSection(healthItems, needsFix, Modifier.sectionAnchor(SECTION_HEALTH))
                PrivilegesSection(state.privileged, callbacks::onSetRootEnabled, callbacks::onGrantPermanent)
                PauseSettingRow(state.automationsPaused, callbacks::onSetAutomationsPaused)
                BrainSection(
                    transport = state.transport,
                    onTest = callbacks::onTestConnection,
                    onConfigure = { showTransportSheet = true },
                    modifier = Modifier.sectionAnchor(SECTION_BRAIN),
                )
                PrivacySection(state.privacyAccepted, { confirmPrivacyRevoke = true }, Modifier.sectionAnchor(SECTION_PRIVACY))
                WhitelistSection(state.whitelist, callbacks, Modifier.sectionAnchor(SECTION_WHITELIST))
                BudgetSection(state.budget, callbacks, Modifier.sectionAnchor(SECTION_BUDGET))
                RulesBackupSection(
                    state.backup,
                    callbacks::onExportRules,
                    callbacks::onImportRules,
                    callbacks::onDismissBackupResult,
                    callbacks::onOpenImportedRules,
                )
                RerunRow(callbacks::onRerunOnboarding)
                VersionFooter(state.appVersionLabel)
                Spacer(Modifier.size(ArgusSpacing.Small))
            }
        }
    }
    if (showTransportSheet) {
        TransportConfigurationSheet(
            transport = state.transport,
            onDismiss = { showTransportSheet = false },
            onSaveBridge = callbacks::onSaveBridge,
            onSaveProvider = callbacks::onSaveProviderConfig,
            providerChoices = state.providerChoices,
            onSelectProvider = callbacks::onSelectProvider,
        )
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

private const val SECTION_HEALTH = "health"
private const val SECTION_BRAIN = "brain"
private const val SECTION_PRIVACY = "privacy"
private const val SECTION_WHITELIST = "whitelist"
private const val SECTION_BUDGET = "budget"

/** Gruppo di card con la forma del gruppo segmentato (angoli 24 in testa e in coda, 6 in mezzo, fessura 3). */
@Composable
private fun CardStack(parts: List<@Composable () -> Unit>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ArgusSpacing.GroupGap)) {
        parts.forEachIndexed { index, part ->
            ArgusCard(
                modifier = Modifier.fillMaxWidth(),
                shape = groupShape(groupPositionOf(index, parts.size)),
                contentPadding = PaddingValues(0.dp),
            ) { part() }
        }
    }
}

/** Intestazione di sezione con un margine di 4 dp, come nelle tavole. */
@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    SectionLabel(text, modifier.padding(horizontal = ArgusSpacing.Unit))
}

// -----------------------------------------------------------------------------
// Salute permessi
// -----------------------------------------------------------------------------

private class HealthSpec(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val title: String,
    val subtitle: String,
    val level: HealthTileLevel,
    /** Azione della tessera. `null` = riga solo informativa: ne' tocco ne' pulsante (B50). */
    val onFix: (() -> Unit)?,
    /** Etichetta dell'azione esplicita per le righe opt-in («Attiva»). */
    val actionLabel: String? = null,
) {
    val wide: Boolean get() = SystemHealthLayout.isWide(level == HealthTileLevel.Warn, subtitle)
}

private sealed interface HealthItem {
    class Tile(val spec: HealthSpec) : HealthItem
    class Note(val text: String) : HealthItem
}

@Composable
private fun buildHealthItems(state: SettingsState, callbacks: SettingsCallbacks): List<HealthItem> {
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
        BgLocationState.GRANTED -> HealthTileLevel.Ok to stringResource(R.string.settings_location_granted)
        BgLocationState.WHILE_IN_USE -> HealthTileLevel.Warn to stringResource(R.string.settings_location_while_in_use)
        BgLocationState.DENIED -> HealthTileLevel.Warn to stringResource(R.string.settings_location_denied)
        BgLocationState.NOT_NEEDED -> HealthTileLevel.Neutral to stringResource(R.string.settings_location_not_needed)
    }
    fun okOrWarn(ok: Boolean) = if (ok) HealthTileLevel.Ok else HealthTileLevel.Warn
    fun okOrOptIn(ok: Boolean) = if (ok) HealthTileLevel.Ok else HealthTileLevel.Neutral
    val enable = stringResource(R.string.settings_enable_action)

    val tiles = buildList {
        add(
            HealthSpec(
                Icons.Rounded.Terminal, stringResource(R.string.settings_shizuku_title), shizukuSub,
                okOrWarn(state.shizuku == ShizukuStatus.AUTHORIZED), callbacks::onOpenShizukuFix,
            ),
        )
        add(
            HealthSpec(
                Icons.Rounded.BatterySaver, stringResource(R.string.settings_battery_title),
                stringResource(if (state.batteryExempt) R.string.settings_battery_ok else R.string.settings_battery_warn),
                okOrWarn(state.batteryExempt), callbacks::onOpenBatteryFix,
            ),
        )
        add(
            HealthSpec(
                Icons.Rounded.Alarm, stringResource(R.string.settings_exact_alarms_title),
                stringResource(if (state.exactAlarmsGranted) R.string.settings_exact_alarms_ok else R.string.settings_exact_alarms_warn),
                okOrWarn(state.exactAlarmsGranted), callbacks::onOpenExactAlarmFix,
            ),
        )
        // 0.4.1: permesso speciale per le azioni luminosità e rotazione (Android lo concede solo da qui).
        add(
            HealthSpec(
                Icons.Rounded.Settings, stringResource(R.string.act41_settings_write_title),
                stringResource(if (state.writeSettingsGranted) R.string.act41_settings_write_ok else R.string.act41_settings_write_warn),
                okOrWarn(state.writeSettingsGranted), callbacks::onOpenWriteSettingsFix,
            ),
        )
        // 0.5.1: accesso ai dati di utilizzo per le regole «app aperta/chiusa» (si concede solo da qui,
        // oppure con Shizuku/root da «Concedi i permessi permanenti»).
        add(
            HealthSpec(
                Icons.Rounded.Apps, stringResource(R.string.app51_settings_usage_title),
                stringResource(if (state.usageAccessGranted) R.string.app51_settings_usage_ok else R.string.app51_settings_usage_warn),
                okOrWarn(state.usageAccessGranted), callbacks::onOpenUsageAccessFix,
            ),
        )
        // 0.5.1: servizio di Accessibilità, serve alle macro sullo schermo (si accende solo da lì).
        add(
            HealthSpec(
                Icons.Rounded.TouchApp, stringResource(R.string.a11y51_settings_title),
                stringResource(if (state.accessibilityGranted) R.string.a11y51_settings_ok else R.string.a11y51_settings_warn),
                okOrWarn(state.accessibilityGranted), callbacks::onOpenAccessibilityFix,
            ),
        )
        add(
            HealthSpec(
                Icons.Rounded.Notifications, stringResource(R.string.settings_notifications_title),
                stringResource(if (state.notificationsGranted) R.string.settings_notifications_ok else R.string.settings_notifications_warn),
                okOrWarn(state.notificationsGranted), callbacks::onOpenNotificationAccessFix,
            ),
        )
        add(
            HealthSpec(
                Icons.Rounded.Notifications, stringResource(R.string.settings_listener_title),
                stringResource(if (state.notificationListenerGranted) R.string.settings_listener_ok else R.string.settings_listener_warn),
                okOrWarn(state.notificationListenerGranted), callbacks::onOpenNotificationListenerFix,
            ),
        )
        add(
            HealthSpec(
                Icons.Rounded.MyLocation, stringResource(R.string.settings_location_title), locSub, locLevel,
                callbacks::onOpenLocationFix,
            ),
        )
        // Telefonia (P2-2): opt-in, non un problema di salute — grigia finché non concesso, il tocco lancia
        // direttamente la richiesta runtime.
        add(
            HealthSpec(
                Icons.Rounded.Sms, stringResource(R.string.settings_sms_title),
                stringResource(if (state.smsTriggerGranted) R.string.settings_sms_on else R.string.settings_sms_off),
                okOrOptIn(state.smsTriggerGranted), callbacks::onRequestSmsPermission,
                actionLabel = enable.takeIf { !state.smsTriggerGranted },
            ),
        )
        add(
            HealthSpec(
                Icons.Rounded.Call, stringResource(R.string.settings_call_title),
                stringResource(if (state.callTriggerGranted) R.string.settings_call_on else R.string.settings_call_off),
                okOrOptIn(state.callTriggerGranted), callbacks::onRequestCallPermissions,
                actionLabel = enable.takeIf { !state.callTriggerGranted },
            ),
        )
        add(
            HealthSpec(
                Icons.Rounded.Bluetooth, stringResource(R.string.settings_bt_title),
                stringResource(if (state.bluetoothTriggerGranted) R.string.settings_bt_on else R.string.settings_bt_off),
                okOrOptIn(state.bluetoothTriggerGranted), callbacks::onRequestBluetoothPermission,
                actionLabel = enable.takeIf { !state.bluetoothTriggerGranted },
            ),
        )
        if (state.connectivitySentinelActive) {
            // B50: solo informativa, non c'è una schermata da aprire: niente affordance cliccabile.
            add(
                HealthSpec(
                    Icons.Rounded.Hub, stringResource(R.string.settings_sentinel_title),
                    stringResource(R.string.settings_sentinel_active), HealthTileLevel.Ok, onFix = null,
                ),
            )
        }
    }
    // I problemi in cima, poi cio' che e' a posto, poi le opzioni spente; l'ordine di prima resta dentro ogni gruppo.
    val ordered = tiles.sortedBy {
        when (it.level) {
            HealthTileLevel.Warn -> 0
            HealthTileLevel.Ok -> 1
            HealthTileLevel.Neutral -> 2
        }
    }
    return buildList {
        ordered.forEach { spec ->
            add(HealthItem.Tile(spec))
            // Android 13+ con installazione da APK: l'accesso può essere bloccato da «Impostazione con
            // restrizioni»; la guida compare solo se l'accesso non è concesso, subito sotto la sua riga.
            if (state.notificationListenerRestrictedHint &&
                spec.title == stringResource(R.string.settings_listener_title)
            ) {
                add(HealthItem.Note(stringResource(R.string.settings_listener_restricted_hint)))
            }
        }
    }
}

@Composable
private fun HealthSection(items: List<HealthItem>, needsFix: Int, modifier: Modifier = Modifier) {
    val okDescription = stringResource(R.string.ui40_health_ok_cd)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = ArgusSpacing.Unit),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionLabel(stringResource(R.string.settings_section_health))
            if (needsFix > 0) {
                StatusChip(ArgusTheme.semantic.pending, Icons.Rounded.WarningAmber, stringResource(R.string.rda_ls_health_to_fix, needsFix))
            } else {
                StatusChip(ArgusTheme.semantic.armed, Icons.Rounded.CheckCircle, stringResource(R.string.rda_ls_health_all_ok))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
            SystemHealthLayout.rows(items) { item ->
                when (item) {
                    is HealthItem.Note -> true
                    is HealthItem.Tile -> item.spec.wide
                }
            }.forEach { row ->
                if (row.size == 2) {
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
                        row.forEach { item ->
                            HealthTileFor(item, okDescription, Modifier.weight(1f).fillMaxHeight(), compact = true)
                        }
                    }
                } else {
                    HealthTileFor(row.single(), okDescription, Modifier.fillMaxWidth(), compact = false)
                }
            }
        }
    }
}

@Composable
private fun HealthTileFor(item: HealthItem, okDescription: String, modifier: Modifier, compact: Boolean) {
    when (item) {
        is HealthItem.Note -> Text(
            item.text,
            modifier = modifier.padding(horizontal = 14.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        is HealthItem.Tile -> {
            val spec = item.spec
            // Un problema ha sempre la tessera larga col suo «Correggi»; il resto e' compatto in coppia, oppure largo
            // se il testo e' lungo o la tessera e' rimasta sola.
            val warnAction = if (spec.level == HealthTileLevel.Warn) stringResource(R.string.settings_fix) else null
            if (compact && spec.level != HealthTileLevel.Warn) {
                SystemHealthCompactTile(
                    icon = spec.icon, title = spec.title, subtitle = spec.subtitle, level = spec.level, modifier = modifier,
                    onClick = spec.onFix, actionLabel = spec.actionLabel, okContentDescription = okDescription,
                )
            } else {
                HealthTile(
                    icon = spec.icon, title = spec.title, subtitle = spec.subtitle, level = spec.level, modifier = modifier,
                    onClick = spec.onFix,
                    actionLabel = if (spec.onFix == null) null else (warnAction ?: spec.actionLabel),
                    okContentDescription = okDescription,
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Brain · transport
// -----------------------------------------------------------------------------

@Composable
private fun BrainSection(
    transport: TransportUi,
    onTest: () -> Unit,
    onConfigure: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = ArgusTheme.semantic
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val reachable = when (transport) {
        is TransportUi.CliBridge -> transport.reachable
        is TransportUi.DirectProvider -> transport.reachable
    }
    val latency = when (transport) {
        is TransportUi.CliBridge -> transport.lastLatencyLabel
        is TransportUi.DirectProvider -> transport.lastLatencyLabel
    }
    val title = when (transport) {
        is TransportUi.CliBridge -> stringResource(R.string.settings_clibridge_title)
        is TransportUi.DirectProvider -> transport.providerLabel
    }
    val endpoint = when (transport) {
        is TransportUi.CliBridge -> transport.url
        is TransportUi.DirectProvider -> transport.baseUrl
    }
    val endpointLabel = stringResource(
        if (transport is TransportUi.CliBridge) R.string.bridge_url_label else R.string.provider_endpoint_label,
    )
    val editDescription = when (transport) {
        is TransportUi.CliBridge -> stringResource(R.string.settings_edit_bridge_cd)
        is TransportUi.DirectProvider -> stringResource(R.string.settings_edit_provider_cd, transport.providerLabel)
    }
    val configureLabel = when (transport) {
        is TransportUi.CliBridge -> stringResource(R.string.bridge_dialog_title)
        is TransportUi.DirectProvider -> stringResource(R.string.provider_dialog_title, transport.providerLabel)
    }
    val modelNotSet = stringResource(R.string.settings_model_not_set)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.settings_section_transport))
        CardStack(
            buildList<@Composable () -> Unit> {
                // Intestazione: icona, nome (e modello), raggiungibilita'.
                add {
                    Row(
                        Modifier.fillMaxWidth().padding(ArgusSpacing.Large - 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
                    ) {
                        IconBadge(
                            Icons.Rounded.Hub,
                            MaterialTheme.colorScheme.tertiaryContainer,
                            MaterialTheme.colorScheme.onTertiaryContainer,
                            size = 44.dp, iconSize = 24.dp, shape = RoundedCornerShape(ArgusRadius.Md),
                        )
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                            if (transport is TransportUi.DirectProvider) {
                                Text(transport.model ?: modelNotSet, color = muted, style = ArgusCodeSmall)
                            }
                        }
                        when (reachable) {
                            true -> SystemDotChip(stringResource(R.string.settings_reachable), semantic.armed)
                            false -> SystemDotChip(stringResource(R.string.settings_unreachable), semantic.error)
                            null -> SystemDotChip(stringResource(R.string.settings_unverified), semantic.disabled)
                        }
                    }
                }
                // Indirizzo in monospace, con la matita che apre il foglio.
                add {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(endpointLabel, color = muted, style = MaterialTheme.typography.labelMedium)
                            Text(endpoint, color = MaterialTheme.colorScheme.onSurface, style = ArgusCodeSmall)
                        }
                        ArgusIconButton(Icons.Rounded.Edit, editDescription, onConfigure)
                    }
                }
                // Chiave o token: si legge solo se c'e', mai il suo valore.
                add {
                    val keyOk = when (transport) {
                        is TransportUi.CliBridge -> transport.tokenConfigured
                        is TransportUi.DirectProvider -> transport.authState == AuthState.OK
                    }
                    val keyText = when (transport) {
                        is TransportUi.CliBridge ->
                            stringResource(if (keyOk) R.string.bridge_token_configured else R.string.bridge_token_missing)
                        is TransportUi.DirectProvider ->
                            stringResource(if (keyOk) R.string.settings_key_configured else R.string.settings_key_missing)
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = ArgusSpacing.Medium),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
                    ) {
                        Icon(
                            if (keyOk) Icons.Rounded.CheckCircle else Icons.Rounded.Error,
                            contentDescription = null,
                            tint = if (keyOk) semantic.armed.fg else semantic.error.fg,
                            modifier = Modifier.size(20.dp),
                        )
                        Text(
                            keyText,
                            color = if (keyOk) semantic.armed.fg else semantic.error.fg,
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                if (transport is TransportUi.DirectProvider && transport.reasoningEffortEditable) {
                    add {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = ArgusSpacing.Medium),
                            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.provider_reasoning_label),
                                modifier = Modifier.weight(1f),
                                color = muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                reasoningLabel(transport.reasoningEffort),
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                }
                if (transport is TransportUi.DirectProvider && transport.unauthenticatedLoopback) {
                    add {
                        Box(Modifier.padding(ArgusSpacing.Small)) {
                            WarningBanner(
                                UiWarning(
                                    Severity.WARNING,
                                    "unauthenticated_loopback",
                                    stringResource(R.string.settings_unauthenticated_loopback),
                                ),
                            )
                        }
                    }
                }
                // Alternativa futura sempre segnalata come roadmap (P3), non allarmistica.
                if (transport is TransportUi.CliBridge) {
                    add {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = ArgusSpacing.Medium),
                            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.settings_streaming_label),
                                modifier = Modifier.weight(1f),
                                color = muted,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            OutlineChip(Icons.Rounded.Schedule, stringResource(R.string.settings_coming_soon))
                        }
                    }
                }
                // Ultima latenza + prova di connessione.
                add {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
                    ) {
                        Text(
                            latency?.let { stringResource(R.string.settings_latency_last, it) }
                                ?: stringResource(R.string.settings_latency_none),
                            modifier = Modifier.weight(1f),
                            color = muted,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        ArgusButton(stringResource(R.string.settings_test_connection), onTest, style = ButtonStyle.Outlined)
                    }
                }
            },
        )
        ArgusButton(
            text = configureLabel,
            onClick = onConfigure,
            style = ButtonStyle.Tonal,
            icon = Icons.Rounded.Tune,
            fillWidth = true,
        )
    }
}

@Composable
private fun reasoningLabel(effort: String): String = stringResource(
    when (effort) {
        "none" -> R.string.provider_reasoning_none
        "low" -> R.string.provider_reasoning_low
        "medium" -> R.string.provider_reasoning_medium
        "high" -> R.string.provider_reasoning_high
        else -> R.string.provider_reasoning_default
    },
)

// -----------------------------------------------------------------------------
// Privacy
// -----------------------------------------------------------------------------

@Composable
private fun PrivacySection(accepted: Boolean, onRevoke: () -> Unit, modifier: Modifier = Modifier) {
    val semantic = ArgusTheme.semantic
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.settings_section_privacy))
        ArgusCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium)) {
                    IconBadge(
                        Icons.Rounded.PrivacyTip,
                        if (accepted) semantic.armed.bg else semantic.pending.bg,
                        if (accepted) semantic.armed.fg else semantic.pending.fg,
                        size = 40.dp, iconSize = 22.dp, shape = RoundedCornerShape(ArgusRadius.Md - 2.dp),
                    )
                    Text(
                        stringResource(if (accepted) R.string.settings_privacy_active else R.string.settings_privacy_missing),
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
                    // Distruttiva: blocca subito l'AI. Apre sempre la conferma.
                    ArgusButton(
                        text = stringResource(R.string.privacy_revoke_action),
                        onClick = onRevoke,
                        style = ButtonStyle.Destructive,
                        icon = Icons.Rounded.Block,
                        fillWidth = true,
                    )
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Whitelist contatti
// -----------------------------------------------------------------------------

private sealed interface WhitelistEntry {
    data object Note : WhitelistEntry
    data class Contact(val row: ContactRow) : WhitelistEntry
    data object Add : WhitelistEntry
}

@Composable
private fun WhitelistSection(contacts: List<ContactRow>, callbacks: SettingsCallbacks, modifier: Modifier = Modifier) {
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
    val entries = listOf<WhitelistEntry>(WhitelistEntry.Note) + contacts.map { WhitelistEntry.Contact(it) } + WhitelistEntry.Add
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.settings_section_whitelist))
        CardStack(
            entries.map { entry ->
                @Composable {
                    when (entry) {
                        WhitelistEntry.Note -> Row(
                            Modifier.fillMaxWidth().padding(14.dp),
                            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
                            verticalAlignment = Alignment.Top,
                        ) {
                            Icon(
                                Icons.Rounded.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            // Nota VERBATIM (§6.5): il match è per conversationId, spoofabile.
                            Text(
                                stringResource(R.string.settings_whitelist_note),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        is WhitelistEntry.Contact -> ContactRowItem(entry.row, onRemove = { confirmRemove = confirmRemove.ask(entry.row) })
                        WhitelistEntry.Add -> Row(
                            Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 56.dp)
                                .clickable(role = Role.Button) { callbacks.onAddContact() }
                                .padding(horizontal = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                            Text(stringResource(R.string.settings_add_contact), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun ContactRowItem(contact: ContactRow, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
    ) {
        // Avatar iniziale (nessuna PII oltre il nome scelto dall'utente).
        Box(
            modifier = Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                contact.displayName.trim().take(1).uppercase(),
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.W700),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(contact.displayName, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
            // conversationId MASCHERATO in monospace: non esporre il numero/handle completo.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(ArgusTheme.surfaces.codeSurface)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    stringResource(R.string.rda_ls_whitelist_id),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                )
                Text(maskConversationId(contact.conversationId), color = ArgusTheme.semantic.codeText, style = ArgusCodeSmall)
            }
        }
        ArgusIconButton(Icons.Rounded.Close, stringResource(R.string.settings_remove_contact_cd, contact.displayName), onRemove)
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
private fun BudgetSection(budget: BudgetUi, callbacks: SettingsCallbacks, modifier: Modifier = Modifier) {
    var showLimitsEditor by remember { mutableStateOf(false) }
    val notAvailable = stringResource(R.string.rda_ls_not_available)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(stringResource(R.string.settings_section_budget))
        if (budget.softWarningActive) {
            HealthBanner(stringResource(R.string.settings_budget_soft), BannerTone.Warning, Icons.Rounded.WarningAmber)
        }
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
            CallsMeter(stringResource(R.string.settings_budget_calls_hour), budget.usedHour, budget.limitHour, Modifier.weight(1f).fillMaxHeight())
            CallsMeter(stringResource(R.string.settings_budget_calls_day), budget.usedDay, budget.limitDay, Modifier.weight(1f).fillMaxHeight())
        }
        val tokenLimit = budget.tokenLimitMonth?.takeIf { it > 0L }
        val tokensUsed = if (budget.tokensInMonth == null && budget.tokensOutMonth == null) {
            null
        } else {
            (budget.tokensInMonth ?: 0L) + (budget.tokensOutMonth ?: 0L)
        }
        SystemMeterCard(
            label = stringResource(R.string.settings_budget_tokens_month),
            modifier = Modifier.fillMaxWidth(),
            progress = tokenLimit?.let { BudgetFormat.tokenRatio(tokensUsed, it) },
            footnote = tokenLimit?.let { stringResource(R.string.settings_budget_limit, BudgetFormat.tokensCapLabel(tokensUsed, it)) },
        ) { SystemMediumValue(BudgetFormat.tokensLabel(budget.tokensInMonth, budget.tokensOutMonth)) }
        val costLimit = budget.costLimitMicros?.takeIf { it > 0L }
        SystemMeterCard(
            label = stringResource(R.string.settings_budget_cost_month),
            modifier = Modifier.fillMaxWidth(),
            progress = costLimit?.let { BudgetFormat.costRatio(budget.costMonthMicros, it) },
            footnote = costLimit?.let { stringResource(R.string.settings_budget_limit, BudgetFormat.costLabel(it)) },
        ) { SystemMediumValue(BudgetFormat.costLabel(budget.costMonthMicros)) }

        if (budget.perProvider.isNotEmpty()) {
            CardStack(
                budget.perProvider.map { provider ->
                    @Composable { ProviderUsageCard(provider, notAvailable) }
                },
            )
        }
        Text(
            stringResource(R.string.settings_budget_footnote),
            modifier = Modifier.padding(horizontal = ArgusSpacing.Unit),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.W400),
        )
        ArgusButton(
            text = stringResource(R.string.settings_budget_edit_limits),
            onClick = { showLimitsEditor = true },
            style = ButtonStyle.Outlined,
            fillWidth = true,
        )
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

/** Chiamate nella finestra: il numero grande, «/ limite» o «· illimitato», e la barra solo se c'e' un limite. */
@Composable
private fun CallsMeter(label: String, used: Long, limit: Int?, modifier: Modifier) {
    val value = BudgetFormat.grouped(used)
    val suffix = BudgetFormat.callsLabel(used, limit).removePrefix(value).trim()
    SystemMeterCard(
        label = label,
        modifier = modifier,
        progress = limit?.takeIf { it > 0 }?.let { BudgetFormat.ratio(used, it) },
    ) { SystemBigValue(value, suffix) }
}

/** Una riga del dettaglio per provider: chiamate, tabella dei token (ora/oggi/mese), ragionamento, motivo di fine, costo. */
@Composable
private fun ProviderUsageCard(provider: ProviderUsageUi, notAvailable: String) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
            Text(
                provider.providerLabel,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                stringResource(R.string.settings_budget_provider_calls, provider.callsHour, provider.callsDay),
                color = muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Metrica primaria: TOKEN input/output per finestra. Un valore assente e' «n/d», mai 0.
        Column {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                TableCell(stringResource(R.string.rda_ls_budget_row_tokens), Modifier.weight(1.1f), header = true, start = true)
                TableCell(stringResource(R.string.rda_ls_budget_col_hour), Modifier.weight(1f), header = true)
                TableCell(stringResource(R.string.rda_ls_budget_col_day), Modifier.weight(1f), header = true)
                TableCell(stringResource(R.string.rda_ls_budget_col_month), Modifier.weight(1f), header = true)
            }
            TokenRow(stringResource(R.string.rda_ls_budget_in), provider.tokensInHour, provider.tokensInDay, provider.tokensInMonth, notAvailable)
            TokenRow(stringResource(R.string.rda_ls_budget_out), provider.tokensOutHour, provider.tokensOutDay, provider.tokensOutMonth, notAvailable)
        }
        provider.reasoningTokensMonth?.let { reasoningTokens ->
            Text(
                stringResource(R.string.settings_budget_provider_reasoning, BudgetFormat.grouped(reasoningTokens)),
                color = muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        provider.lastFinishReason?.let { finishReason ->
            Text(
                stringResource(R.string.settings_budget_provider_finish, finishReason),
                color = muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        // Dollari SOLO per i provider a listino noto: mai per Hermes/OpenRouter/Custom.
        if (provider.costTracked) {
            Text(
                stringResource(R.string.settings_budget_provider_cost, BudgetFormat.costLabel(provider.costMonthMicros)),
                color = muted,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun TokenRow(label: String, hour: Long?, day: Long?, month: Long?, notAvailable: String) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            TableCell(label, Modifier.weight(1.1f), header = true, start = true)
            listOf(hour, day, month).forEach { value ->
                TableCell(
                    value?.let { BudgetFormat.grouped(it) } ?: notAvailable,
                    Modifier.weight(1f),
                    missing = value == null,
                )
            }
        }
    }
}

@Composable
private fun TableCell(
    text: String,
    modifier: Modifier,
    header: Boolean = false,
    start: Boolean = false,
    missing: Boolean = false,
) {
    Text(
        text,
        modifier = modifier,
        textAlign = if (start) TextAlign.Start else TextAlign.End,
        color = if (header || missing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
    )
}

// -----------------------------------------------------------------------------
// Ripeti configurazione + footer versione
// -----------------------------------------------------------------------------

@Composable
private fun RerunRow(onRerun: () -> Unit) {
    ArgusCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp), onClick = onRerun) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 56.dp)
                .padding(start = 14.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
        ) {
            Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Text(
                stringResource(R.string.settings_rerun),
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
            Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun VersionFooter(version: String) {
    Text(
        version,
        color = MaterialTheme.colorScheme.secondaryText(),
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.W400),
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

// =============================================================================
// Preview — un'anteprima per stato nei due temi. Nessuna chiave in nessuna anteprima.
// =============================================================================

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

@Composable
private fun SettingsPreview(state: SettingsState) {
    ArgusPreviewSurface { SettingsScreen(state, NoopSettingsCallbacks) }
}

private val previewTwoToFix = Fixtures.settingsAllGreen.copy(
    shizuku = ShizukuStatus.INSTALLED_NOT_RUNNING,
    backgroundLocation = BgLocationState.WHILE_IN_USE,
    whitelist = listOf(
        ContactRow("Moglie", "wa::393200000000::c1a9"),
        ContactRow("Fratello", "wa::393300000000::7be2"),
    ),
    connectivitySentinelActive = true,
    bluetoothTriggerGranted = true,
)

@ArgusThemePreviews
@Composable
private fun SettingsAllGreenPreview() = SettingsPreview(Fixtures.settingsAllGreen)

@ArgusThemePreviews
@Composable
private fun SettingsTwoToFixPreview() = SettingsPreview(previewTwoToFix)

@ArgusThemePreviews
@Composable
private fun SettingsDegradedPreview() = SettingsPreview(Fixtures.settingsDegraded)

@ArgusThemePreviews
@Composable
private fun SettingsShizukuRootPreview() = SettingsPreview(Fixtures.settingsShizukuRoot)

@ArgusThemePreviews
@Composable
private fun SettingsShizukuUnsupportedPreview() = SettingsPreview(Fixtures.settingsShizukuUnsupported)

@ArgusThemePreviews
@Composable
private fun SettingsDirectProviderPreview() = SettingsPreview(Fixtures.settingsDirectProvider)

@ArgusThemePreviews
@Composable
private fun SettingsOpenRouterNoKeyPreview() = SettingsPreview(
    Fixtures.settingsRestrictedHint.copy(
        transport = (Fixtures.settingsRestrictedHint.transport as TransportUi.DirectProvider).copy(
            authState = AuthState.NOT_CONFIGURED,
            reasoningEffortEditable = true,
            reasoningEffort = "high",
            unauthenticatedLoopback = true,
        ),
    ),
)

@ArgusThemePreviews
@Composable
private fun SettingsRestrictedHintPreview() = SettingsPreview(Fixtures.settingsRestrictedHint)

@ArgusThemePreviews
@Composable
private fun SettingsExactAlarmsDeniedPreview() = SettingsPreview(Fixtures.settingsExactAlarmsDenied)

@ArgusThemePreviews
@Composable
private fun SettingsWriteSettingsDeniedPreview() = SettingsPreview(Fixtures.settingsWriteSettingsDenied)

@ArgusThemePreviews
@Composable
private fun SettingsUsageAccessDeniedPreview() = SettingsPreview(Fixtures.settingsUsageAccessDenied)

@ArgusThemePreviews
@Composable
private fun SettingsAccessibilityOffPreview() = SettingsPreview(Fixtures.settingsAccessibilityOff)

/** Valori assenti: token «n/d» (non 0), costo «n/d», nessun limite = niente barra. */
@ArgusThemePreviews
@Composable
private fun SettingsBudgetMissingValuesPreview() = SettingsPreview(
    Fixtures.settingsAllGreen.copy(
        budget = BudgetUi(
            usedHour = 0, limitHour = null, usedDay = 4, limitDay = null,
            costMonthMicros = null, costLimitMicros = null,
            tokensInMonth = null, tokensOutMonth = null, tokenLimitMonth = null,
            perProvider = listOf(
                ProviderUsageUi("openrouter", "OpenRouter", callsHour = 0, callsDay = 4, costMonthMicros = null, tokensInMonth = 1_200),
            ),
        ),
    ),
)

@ArgusThemePreviews
@Composable
private fun SettingsPrivacyNotAcceptedPreview() = SettingsPreview(Fixtures.settingsAllGreen.copy(privacyAccepted = false))
