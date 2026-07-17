package dev.argus.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ScheduleSend
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.engine.runtime.AuditKind
import dev.argus.ui.components.EmptyState
import dev.argus.ui.components.CloudTag
import dev.argus.ui.components.GenerativeTag
import dev.argus.ui.model.ExecutionLogCallbacks
import dev.argus.ui.model.ExecutionLogState
import dev.argus.ui.model.LogOutcome
import dev.argus.ui.model.LogRow
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.LocalArgusSemantic

// =============================================================================
// Schermo Log esecuzioni (handoff §6.4, design §7.4).
// Comportamenti resi qui:
//  - TIMELINE raggruppata per giorno: header "Oggi"/"Ieri"/… dal prefisso di
//    `timeLabel` (§7.4).
//  - Icona esito per outcome/kind (verde/ambra/rosso/grigio) + summary colorato.
//  - SUPPRESSED_COOLDOWN / CONDITIONS_NOT_MET = rumore utile → riga ATTENUATA
//    (opacity ~.5), non evento (§6.4/§7.4).
//  - Righe con dettaglio espandibili: chevron ruota 180°, mostra i passi per-azione.
//  - Righe DEFERRED (E13): bottone "Invia ora" nel pannello espanso.
//  - `filterAutomationName` → header "Solo: <nome>" con "×" (onClearFilter).
// Stateless: (state, callbacks). L'espansione è pura UI-state effimera (remember),
// come ChatErrorBanner; onExpand(id) notifica comunque l'host. Il contratto §6.4
// non porta l'insieme delle righe aperte.
// =============================================================================

@Composable
fun ExecutionLogScreen(
    state: ExecutionLogState,
    callbacks: ExecutionLogCallbacks,
    modifier: Modifier = Modifier,
) {
    // Espansione locale per id (effimera): il contratto non porta gli id aperti.
    val expandedIds = remember { mutableStateMapOf<String, Boolean>() }

    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Log esecuzioni",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 18.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            )

            state.filterAutomationName?.let { name ->
                FilterHeader(name, callbacks::onClearFilter)
            }

            if (state.loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else if (state.entries.isEmpty()) {
                EmptyState(
                    icon = Icons.Rounded.History,
                    title = "Nessuna esecuzione",
                    body = "Qui compaiono gli scatti delle regole armate: successi, condizioni non soddisfatte ed errori.",
                )
            } else {
                // groupBy preserva l'ordine di prima comparsa delle chiavi (host: più recenti in cima).
                val grouped = state.entries.groupBy { dayKey(it.timeLabel) }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    grouped.forEach { (day, rows) ->
                        item(key = "hdr-$day") { DayHeader(day) }
                        rows.forEach { row ->
                            item(key = row.id) {
                                LogRowItem(
                                    row = row,
                                    expanded = expandedIds[row.id] == true,
                                    onToggle = {
                                        expandedIds[row.id] = !(expandedIds[row.id] ?: false)
                                        callbacks.onExpand(row.id)
                                    },
                                    onSendNow = { callbacks.onSendNow(row.id) },
                                    onOpenAutomation = {
                                        row.automationId?.let(callbacks::onOpenAutomation)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Header filtro + header giorno
// -----------------------------------------------------------------------------

/** "Solo: <nome>" con "×" — filtro attivo arrivando dal dettaglio (§6.4). */
@Composable
private fun FilterHeader(name: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(start = 13.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            "Solo: $name",
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable { onClear() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "Rimuovi filtro",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun DayHeader(day: String) {
    Text(
        day.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp, start = 2.dp),
    )
}

// -----------------------------------------------------------------------------
// Riga log
// -----------------------------------------------------------------------------

@Composable
private fun LogRowItem(
    row: LogRow,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSendNow: () -> Unit,
    onOpenAutomation: () -> Unit,
) {
    val (icon, color) = outcomeVisual(row)
    val isDeferred = row.kind == AuditKind.FIRED && row.outcome == LogOutcome.DEFERRED
    val expandable = row.expandedDetail != null || isDeferred || row.automationId != null
    // §6.4: soppressioni / condizioni-non-soddisfatte sono rumore → attenuate.
    val attenuated = row.kind in setOf(
        AuditKind.SUPPRESSED_DUPLICATE,
        AuditKind.SUPPRESSED_COOLDOWN,
        AuditKind.SUPPRESSED_NOT_ELIGIBLE,
        AuditKind.CONDITIONS_NOT_MET,
    )
    val chevronRotation by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(if (expandable) Modifier.clickable { onToggle() } else Modifier)
            .alpha(if (attenuated) 0.5f else 1f)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp).padding(top = 1.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    row.automationName,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
                if (row.isGenerative) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GenerativeTag()
                        CloudTag()
                    }
                }
                // Summary colorato per esito (§7.4): verde/ambra/rosso/grigio.
                Text(row.summary, color = color, style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                timePart(row.timeLabel),
                color = MaterialTheme.colorScheme.outline,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 1.dp),
            )
            if (expandable) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Comprimi" else "Espandi",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp).rotate(chevronRotation),
                )
            }
        }

        AnimatedVisibility(visible = expanded && expandable) {
            ExpandedDetail(row, isDeferred, onSendNow, onOpenAutomation)
        }
    }
}

@Composable
private fun ExpandedDetail(
    row: LogRow,
    isDeferred: Boolean,
    onSendNow: () -> Unit,
    onOpenAutomation: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 31.dp) // allinea sotto il testo (icona 20 + gap 11)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Passi per-azione (incl. esito lane generativa / DEFERRED) — tecnici → monospace (§4).
        row.expandedDetail?.forEach { step ->
            Text(
                step,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (isDeferred) {
            // E13: la risposta è pronta ma non più consegnabile in automatico → invio manuale.
            Button(
                onClick = onSendNow,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(start = 16.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                ),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Invia ora")
            }
        }
        if (row.automationId != null) {
            TextButton(
                onClick = onOpenAutomation,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Apri automazione")
            }
        }
    }
}

/** Icona + colore per esito (§7.4). Rispecchia `runVisual` del Dettaglio per coerenza. */
@Composable
private fun outcomeVisual(row: LogRow): Pair<ImageVector, Color> {
    val s = LocalArgusSemantic.current
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    return when (row.kind) {
        AuditKind.ERROR -> Icons.Rounded.Error to s.error.fg
        AuditKind.BLOCKED_POLICY -> Icons.Rounded.Error to s.error.fg
        AuditKind.VALIDATION_REJECTED,
        AuditKind.ARM_FAILED,
        AuditKind.SCHEDULING_FAILED,
        AuditKind.ENABLE_FAILED -> Icons.Rounded.Error to s.error.fg
        AuditKind.SUPPRESSED_DUPLICATE,
        AuditKind.SUPPRESSED_COOLDOWN -> Icons.Rounded.Block to faint
        AuditKind.SUPPRESSED_BUDGET -> Icons.Rounded.Block to s.pending.fg
        AuditKind.SUPPRESSED_NOT_ELIGIBLE -> Icons.Rounded.Block to faint
        AuditKind.CONDITIONS_NOT_MET -> Icons.Rounded.FilterAltOff to faint
        AuditKind.FIRED -> when (row.outcome) {
            LogOutcome.SUCCESS -> Icons.Rounded.CheckCircle to s.armed.fg
            LogOutcome.PARTIAL -> Icons.Rounded.CheckCircle to s.pending.fg
            LogOutcome.FAILED -> Icons.Rounded.Error to s.error.fg
            LogOutcome.SUBMITTED -> Icons.AutoMirrored.Rounded.ScheduleSend to MaterialTheme.colorScheme.primary
            LogOutcome.DEFERRED -> Icons.AutoMirrored.Rounded.ScheduleSend to s.cloud.fg
        }
    }
}

// -----------------------------------------------------------------------------
// Parsing giorno / ora dal timeLabel ("oggi 23:00" → "oggi" + "23:00")
// -----------------------------------------------------------------------------

private fun dayKey(timeLabel: String): String = timeLabel.substringBefore(' ', timeLabel).trim()

private fun timePart(timeLabel: String): String {
    val idx = timeLabel.indexOf(' ')
    return if (idx >= 0) timeLabel.substring(idx + 1).trim() else timeLabel
}

// =============================================================================
// Preview — fixture inline (i Fixtures centralizzati arrivano in Unit F).
// =============================================================================

private val previewEntries = listOf(
    LogRow(
        "l1", "oggi 23:00", "DND dopo le 23:00", AuditKind.FIRED, LogOutcome.SUCCESS,
        "Non disturbare attivato",
        listOf("SetDnd(total) → ok", "cooldown 6 h avviato"),
    ),
    LogRow(
        "l2", "oggi 19:14", "Rispondi a Moglie su WhatsApp", AuditKind.FIRED, LogOutcome.DEFERRED,
        "Risposta pronta — consegna manuale",
        listOf(
            "GenerateReply(Hermes) → ok (14 s)",
            "WhatsAppReply → non più rispondibile in automatico",
        ),
    ),
    LogRow(
        "l3", "oggi 18:32", "Casa · spegni Wi-Fi uscendo", AuditKind.FIRED, LogOutcome.SUCCESS,
        "2/2 azioni ok",
        listOf("SetWifi(off) → ok", "SetBluetooth(on) → ok"),
    ),
    LogRow(
        "l4", "oggi 12:05", "Backup foto notturno", AuditKind.FIRED, LogOutcome.PARTIAL,
        "1/2 azioni ok — 1 fallita", listOf("Sync(immich) → ok", "SetWifi(off) → errore permesso"),
    ),
    LogRow(
        "l5", "ieri 21:00", "DND dopo le 23:00", AuditKind.CONDITIONS_NOT_MET, LogOutcome.SUCCESS,
        "condizioni non soddisfatte · già in ritorno", null,
    ),
    LogRow(
        "l6", "ieri 20:05", "Casa · spegni Wi-Fi uscendo", AuditKind.SUPPRESSED_COOLDOWN, LogOutcome.SUCCESS,
        "soppressa (cooldown 60 s)", null,
    ),
    LogRow(
        "l7", "ieri 08:50", "Backup foto notturno", AuditKind.ERROR, LogOutcome.FAILED,
        "Shizuku non disponibile", listOf("shell.run → Shizuku binder assente"),
    ),
)

private object NoopLogCallbacks : ExecutionLogCallbacks {
    override fun onExpand(id: String) {}
    override fun onClearFilter() {}
    override fun onOpenAutomation(id: String) {}
}

@Preview(name = "Log · timeline (Oggi/Ieri, tutti gli esiti)", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 900)
@Composable
private fun LogTimelinePreview() {
    ArgusTheme {
        ExecutionLogScreen(
            ExecutionLogState(entries = previewEntries, filterAutomationName = null, loading = false),
            NoopLogCallbacks,
        )
    }
}

@Preview(name = "Log · riga espansa (passi per-azione)", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun LogExpandedPreview() {
    ArgusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                DayHeader("oggi")
                // Riga SUCCESS pre-espansa.
                LogRowItem(
                    previewEntries[2],
                    expanded = true,
                    onToggle = {},
                    onSendNow = {},
                    onOpenAutomation = {},
                )
                // Riga DEFERRED pre-espansa → mostra "Invia ora".
                LogRowItem(
                    previewEntries[1],
                    expanded = true,
                    onToggle = {},
                    onSendNow = {},
                    onOpenAutomation = {},
                )
            }
        }
    }
}

@Preview(name = "Log · filtrato per automazione", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun LogFilteredPreview() {
    ArgusTheme {
        ExecutionLogScreen(
            ExecutionLogState(
                entries = previewEntries.filter { it.automationName == "Casa · spegni Wi-Fi uscendo" },
                filterAutomationName = "Casa · spegni Wi-Fi uscendo",
                loading = false,
            ),
            NoopLogCallbacks,
        )
    }
}
