package dev.argus.ui.screens

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
import androidx.compose.material.icons.rounded.Terminal
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
import androidx.compose.ui.text.font.FontFamily
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
import dev.argus.ui.components.ProviderConfigurationDialog
import dev.argus.ui.model.ProviderUsageUi
import dev.argus.ui.presentation.BudgetFormat
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
                "Sistema",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 18.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            )

            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                HealthSection(state, callbacks)
                TransportSection(state.transport, state.providerChoices, callbacks) { showBridgeEditor = true }
                PrivacySection(state.privacyAccepted) { confirmPrivacyRevoke = true }
                WhitelistSection(state.whitelist, callbacks)
                BudgetSection(state.budget, callbacks)
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
                onSave = { baseUrl, model, apiKey ->
                    callbacks.onSaveProviderConfig(transport.providerId, baseUrl, model, apiKey)
                },
            )
        }
    }
    if (confirmPrivacyRevoke) {
        AlertDialog(
            onDismissRequest = { confirmPrivacyRevoke = false },
            title = { Text(stringResource(R.string.privacy_revoke_title)) },
            text = { Text(stringResource(R.string.privacy_revoke_body)) },
            dismissButton = {
                TextButton(onClick = { confirmPrivacyRevoke = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmPrivacyRevoke = false
                        callbacks.onRevokePrivacy()
                    },
                ) { Text(stringResource(R.string.privacy_revoke_confirm)) }
            },
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
        ShizukuStatus.AUTHORIZED -> "attivo — privilegi shell disponibili"
        ShizukuStatus.NOT_INSTALLED -> "non installato"
        ShizukuStatus.INSTALLED_NOT_RUNNING -> "installato, non in esecuzione"
        ShizukuStatus.RUNNING_NOT_AUTHORIZED -> "attivo, Argus non autorizzato"
        ShizukuStatus.DEGRADED_AFTER_REBOOT -> "spento dopo il riavvio — azioni shell in coda"
    }
    val (locLevel, locSub) = when (state.backgroundLocation) {
        BgLocationState.GRANTED -> HealthLevel.OK to "sempre — i geofence sono affidabili"
        BgLocationState.WHILE_IN_USE -> HealthLevel.WARN to "solo mentre in uso — serve «Consenti sempre» per i geofence"
        BgLocationState.DENIED -> HealthLevel.WARN to "negata o non precisa — i geofence non funzionano"
        BgLocationState.NOT_NEEDED -> HealthLevel.NEUTRAL to "non necessaria — nessuna regola geofence; tocca per concederla in anticipo"
    }

    SettingsSection("SALUTE") {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HealthRow(
                Icons.Rounded.Terminal, "Shizuku", shizukuSub,
                if (state.shizuku == ShizukuStatus.AUTHORIZED) HealthLevel.OK else HealthLevel.WARN,
                onFix = callbacks::onOpenShizukuFix,
            )
            HealthRow(
                Icons.Rounded.BatterySaver, "Ottimizzazione batteria",
                if (state.batteryExempt) {
                    "esclusione Android concessa — i controlli attività in background/avvio automatico OxygenOS restano manuali"
                } else {
                    "attiva — concedi l'esclusione e verifica anche attività in background/avvio automatico in OxygenOS"
                },
                if (state.batteryExempt) HealthLevel.OK else HealthLevel.WARN,
                onFix = callbacks::onOpenBatteryFix,
            )
            HealthRow(
                Icons.Rounded.Notifications, "Notifiche Argus",
                if (state.notificationsGranted) "consentite" else "non consentite — esiti e avvisi non saranno visibili",
                if (state.notificationsGranted) HealthLevel.OK else HealthLevel.WARN,
                onFix = callbacks::onOpenNotificationAccessFix,
            )
            HealthRow(
                Icons.Rounded.Notifications, "Lettura notifiche",
                if (state.notificationListenerGranted) {
                    "attiva — trigger WhatsApp e risposte disponibili"
                } else {
                    "non attiva — trigger WhatsApp e risposte non armabili"
                },
                if (state.notificationListenerGranted) HealthLevel.OK else HealthLevel.WARN,
                onFix = callbacks::onOpenNotificationListenerFix,
            )
            HealthRow(
                Icons.Rounded.MyLocation, "Posizione in background", locSub, locLevel,
                onFix = callbacks::onOpenLocationFix,
            )
            // Telefonia (P2-2): opt-in, non un problema di salute — NEUTRAL finché non
            // concesso, il tap lancia direttamente la richiesta runtime.
            HealthRow(
                Icons.Rounded.Sms, "Trigger SMS",
                if (state.smsTriggerGranted) {
                    "attivi per SMS telefonici — chat RCS e MMS non emettono questo trigger"
                } else {
                    "non attivi — solo SMS telefonici, non chat RCS o MMS"
                },
                if (state.smsTriggerGranted) HealthLevel.OK else HealthLevel.NEUTRAL,
                onFix = callbacks::onRequestSmsPermission,
                actionLabel = if (state.smsTriggerGranted) null else "Attiva",
            )
            HealthRow(
                Icons.Rounded.Call, "Trigger chiamate",
                if (state.callTriggerGranted) {
                    "attivi — squillo e fine chiamata armabili"
                } else {
                    "non attivi — consenti per armare regole sulle chiamate"
                },
                if (state.callTriggerGranted) HealthLevel.OK else HealthLevel.NEUTRAL,
                onFix = callbacks::onRequestCallPermissions,
                actionLabel = if (state.callTriggerGranted) null else "Attiva",
            )
            HealthRow(
                Icons.Rounded.Bluetooth, "Trigger Bluetooth",
                if (state.bluetoothTriggerGranted) {
                    "attivi — connessione e disconnessione dei dispositivi sono armabili"
                } else {
                    "non attivi — consenti Dispositivi nelle vicinanze per armare le regole"
                },
                if (state.bluetoothTriggerGranted) HealthLevel.OK else HealthLevel.NEUTRAL,
                onFix = callbacks::onRequestBluetoothPermission,
                actionLabel = if (state.bluetoothTriggerGranted) null else "Attiva",
            )
            if (state.connectivitySentinelActive) {
                HealthRow(
                    Icons.Rounded.Hub,
                    "Sentinella connettività",
                    "attiva — monitora Wi-Fi e alimentazione per le regole armate",
                    HealthLevel.OK,
                    onFix = {},
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
    onFix: () -> Unit,
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
            // relativo (feedback Lorenzo: le righe verdi/grigie erano mute al tocco).
            .clickable { onFix() }
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
            level == HealthLevel.WARN ->
                OutlinedButton(onClick = onFix, modifier = Modifier.heightIn(min = 48.dp)) { Text("Correggi") }
            actionLabel != null ->
                OutlinedButton(onClick = onFix, modifier = Modifier.heightIn(min = 48.dp)) { Text(actionLabel) }
            level == HealthLevel.OK ->
                Icon(Icons.Rounded.CheckCircle, contentDescription = "ok", tint = semantic.armed.fg, modifier = Modifier.size(22.dp))
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
    SettingsSection("BRAIN · TRANSPORT") {
        SectionCard {
            if (providerChoices.isNotEmpty()) {
                ProviderSelector(providerChoices, callbacks::onSelectProvider)
            }
            when (transport) {
                is TransportUi.CliBridge -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text("CliBridge · Hermes", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        val (reachColor, reachText) = when (transport.reachable) {
                            true -> semantic.armed.fg to "raggiungibile"
                            false -> semantic.error.fg to "irraggiungibile"
                            null -> MaterialTheme.colorScheme.onSurfaceVariant to "non verificato"
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
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Rounded.Edit, contentDescription = "Modifica bridge Hermes", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
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
                            transport.lastLatencyLabel?.let { "Ultima latenza: $it" } ?: "Latenza non ancora misurata",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = callbacks::onTestConnection, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Test connessione")
                        }
                    }
                }
                is TransportUi.DirectProvider -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Icon(Icons.Rounded.Hub, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                        Text(
                            "${transport.providerLabel} · ${transport.model ?: "modello non impostato"}",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                        )
                        val (reachColor, reachText) = when (transport.reachable) {
                            true -> semantic.armed.fg to "raggiungibile"
                            false -> semantic.error.fg to "irraggiungibile"
                            null -> MaterialTheme.colorScheme.onSurfaceVariant to "non verificato"
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
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f),
                        )
                        Icon(Icons.Rounded.Edit, contentDescription = "Modifica ${transport.providerLabel}", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                    Text(
                        if (transport.authState == AuthState.OK) "Chiave configurata" else "Chiave mancante",
                        color = if (transport.authState == AuthState.OK) semantic.armed.fg else semantic.error.fg,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            transport.lastLatencyLabel?.let { "Ultima latenza: $it" } ?: "Latenza non ancora misurata",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = callbacks::onTestConnection, modifier = Modifier.heightIn(min = 48.dp)) {
                            Text("Test connessione")
                        }
                    }
                }
            }
            // Alternativa futura sempre segnalata come roadmap (P3), non allarmistica.
            if (transport is TransportUi.CliBridge) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Streaming (OpenAI-compat)",
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
        "in arrivo",
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
    SettingsSection("PRIVACY") {
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
                    if (accepted) "Consenso Hermes attivo" else "Consenso non accettato",
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
    SettingsSection("WHITELIST CONTATTI") {
        SectionCard {
            // Nota VERBATIM (§6.5): il match è per conversationId, spoofabile.
            Text(
                "Solo questi contatti possono ricevere risposte automatiche. Memorizzati per conversationId, non per nome — spoofabile.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            contacts.forEach { ContactRowItem(it, onRemove = { callbacks.onRemoveContact(it.conversationId) }) }
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
                Text("Aggiungi contatto", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
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
                fontFamily = FontFamily.Monospace,
            )
        }
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable { onRemove() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = "Rimuovi ${contact.displayName}", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
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
    SettingsSection("BUDGET LLM") {
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
                        "Budget quasi esaurito",
                        color = semantic.pending.fg,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
            BudgetCallsRow("Chiamate · ultima ora", budget.usedHour, budget.limitHour)
            BudgetCallsRow("Chiamate · oggi", budget.usedDay, budget.limitDay)
            BudgetCostRow(budget.costMonthMicros, budget.costLimitMicros)
            if (budget.perProvider.isNotEmpty()) {
                budget.perProvider.forEach { provider ->
                    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            provider.providerLabel,
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Text(
                            "ora ${provider.callsHour} · oggi ${provider.callsDay} · " +
                                "mese ${BudgetFormat.costLabel(provider.costMonthMicros)}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
            Text(
                "Costi stimati dal listino pubblico (stima indicativa, non fattura) · EUR a tasso fisso ≈",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            OutlinedButton(
                onClick = { showLimitsEditor = true },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Modifica limiti") }
        }
    }
    if (showLimitsEditor) {
        BudgetLimitsDialog(
            initialMaxPerHour = budget.limitHour,
            initialMaxPerDay = budget.limitDay,
            initialMaxCostMonthMicros = budget.costLimitMicros,
            onDismiss = { showLimitsEditor = false },
            onSave = { maxPerHour, maxPerDay, maxCostMonthMicros ->
                callbacks.onBudgetChange(maxPerHour)
                callbacks.onBudgetDayChange(maxPerDay)
                callbacks.onBudgetMonthlyCostChange(maxCostMonthMicros)
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
                fontFamily = FontFamily.Monospace,
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

@Composable
private fun BudgetCostRow(costMonthMicros: Long?, costLimitMicros: Long?) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Costo · mese corrente", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text(
                BudgetFormat.costLabel(costMonthMicros),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
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
                "Limite: ${BudgetFormat.costLabel(costLimitMicros)}",
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
        Text("Ripeti configurazione", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun VersionFooter(version: String) {
    Text(
        version,
        color = MaterialTheme.colorScheme.outline,
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
    transport = TransportUi.CliBridge(url = "https://hermes.tail04462d.ts.net", reachable = true, lastLatencyLabel = "14 s · normale per Hermes"),
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
        perProvider = listOf(
            ProviderUsageUi("openai", "OpenAI", callsHour = 3, callsDay = 11, costMonthMicros = 1_870_000),
            ProviderUsageUi("hermes", "Hermes (self-hosted)", callsHour = 0, callsDay = 0, costMonthMicros = null),
        ),
        softWarningActive = false,
    ),
    privacyAccepted = true,
    appVersionLabel = "Argus v0.1.0 · MVP (sideload)",
    providerChoices = previewProviderChoices,
)

private val previewDirectProvider = previewAllGreen.copy(
    transport = TransportUi.DirectProvider(
        providerId = "openai",
        providerLabel = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-5.5",
        authState = AuthState.OK,
        reachable = true,
        lastLatencyLabel = "2.1 s",
        defaultModels = listOf("gpt-5.5", "gpt-5-mini"),
    ),
    providerChoices = previewProviderChoices.map { it.copy(selected = it.id == "openai") },
)

private val previewDegraded = SettingsState(
    transport = TransportUi.CliBridge(url = "https://hermes.tail04462d.ts.net", reachable = false, lastLatencyLabel = null),
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
            ProviderUsageUi("openai", "OpenAI", callsHour = 17, callsDay = 92, costMonthMicros = 4_500_000),
        ),
        softWarningActive = true,
    ),
    privacyAccepted = true,
    appVersionLabel = "Argus v0.1.0 · MVP (sideload)",
)

@Preview(name = "Sistema · tutto verde + 1 contatto", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 1100)
@Composable
private fun SettingsAllGreenPreview() {
    ArgusTheme { SettingsScreen(previewAllGreen, NoopSettingsCallbacks) }
}

@Preview(name = "Sistema · Shizuku degradato + batteria non-exempt (CTA)", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 1100)
@Composable
private fun SettingsDegradedPreview() {
    ArgusTheme { SettingsScreen(previewDegraded, NoopSettingsCallbacks) }
}

@Preview(name = "Sistema · provider diretto (OpenAI)", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 1100)
@Composable
private fun SettingsDirectProviderPreview() {
    ArgusTheme { SettingsScreen(previewDirectProvider, NoopSettingsCallbacks) }
}
