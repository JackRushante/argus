package dev.argus.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ScheduleSend
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.safety.Severity
import dev.argus.ui.components.RuleCard
import dev.argus.ui.components.StatusBadgeChip
import dev.argus.ui.components.WarningBanner
import dev.argus.ui.components.CloudTag
import dev.argus.ui.components.GenerativeTag
import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.AutomationDetailCallbacks
import dev.argus.ui.model.AutomationDetailState
import dev.argus.ui.model.LogOutcome
import dev.argus.ui.model.LogRow
import dev.argus.ui.model.RuleRender
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.UiWarning
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.LocalArgusSemantic

// =============================================================================
// Schermo Dettaglio / Approvazione (handoff §6.3, design §7.3) — CARDINE.
// È lo schermo dove gli invarianti di sicurezza §5 sono più critici:
//  - warning SEMPRE sopra la fold, prima del RuleCard (§5.2);
//  - la regola si mostra da RuleRender (RuleCard), mai da `rationale` (§5.1);
//  - `rationale` reso come citazione subordinata "descrizione del modello";
//  - ERROR ⇒ canArm=false ⇒ bottone Arma disabilitato con motivo (§5.2);
//  - generativa ⇒ badge generativa + "esce verso il cloud" (§5.4);
//  - elimina/esegui-ora mai pre-focusati (§5.6).
// Stateless: solo (state, callbacks). La navigazione back e "overflow" è di
// competenza dell'host (NavHost, Task 12): il contratto non espone onBack.
// =============================================================================

private val SectionLabelSpacing = 6.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationDetailScreen(
    state: AutomationDetailState,
    callbacks: AutomationDetailCallbacks,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            DetailHeader(state.status, callbacks::onBack)

            // Corpo scrollabile: warning -> regola -> stima -> rationale -> runs.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Spacer(Modifier.height(2.dp))
                TitleAndBadges(state)

                // §5.2: i warning stanno SOPRA la fold, prima della regola, mai collassati.
                state.warnings.forEach { WarningBanner(it) }

                // §5.1: la verità visiva è SEMPRE RuleRender (QUANDO/SOLO SE/ALLORA).
                // showGenerativeHeader=false: la coppia generativa+cloud è già nella riga
                // badge dell'header (TitleAndBadges); qui va soppressa per non duplicarla.
                RuleCard(state.rule, compact = false, showGenerativeHeader = false)

                // Geofence: label al posto delle coordinate (§6.3, resolveCurrentLocation).
                state.geofencePreviewLabel?.let { GeofencePreviewRow(it) }

                // Solo generative: stima chiamate/giorno (spec §10.5).
                state.estimatedLlmCallsPerDay?.let { EstimateRow(it) }

                // §5.1: prosa LLM come commento subordinato, MAI fonte di verità.
                state.rationale?.let { RationaleQuote(it) }

                if (state.recentRuns.isNotEmpty()) {
                    RecentRunsSection(state.recentRuns, callbacks::onOpenFullLog)
                }

                Spacer(Modifier.height(4.dp))
            }

            DetailFooter(state, callbacks)
        }
    }
}

// -----------------------------------------------------------------------------
// Header + titolo/badge
// -----------------------------------------------------------------------------

@Composable
private fun DetailHeader(status: StatusBadge, onBack: () -> Unit) {
    // "Approvazione" in review (PENDING), "Dettaglio" in ispezione (design §7.3).
    val kind = if (status == StatusBadge.PENDING_APPROVAL) "Approvazione" else "Dettaglio"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Affordance back: onBack è additivo (default no-op nel contratto §6.3); l'host cabla il pop.
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).clickable { onBack() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Indietro",
                tint = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(24.dp),
            )
        }
        Text(kind, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TitleAndBadges(state: AutomationDetailState) {
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        Text(state.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusBadgeChip(state.status)
            // §5.4: generativa ⇒ badge generativa + "esce verso il cloud", sempre in coppia.
            if (state.rule.isGenerative) {
                GenerativeTag()
                CloudTag()
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Righe meta: geofence preview, stima generativa, rationale
// -----------------------------------------------------------------------------

@Composable
private fun GeofencePreviewRow(label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Rounded.Place, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Text(label, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EstimateRow(text: String) {
    val semantic = LocalArgusSemantic.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Icon(Icons.Rounded.Insights, contentDescription = null, tint = semantic.generative.fg, modifier = Modifier.size(19.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

/** §5.1: la spiegazione del modello è un commento subordinato (bordo sinistro, corsivo). */
@Composable
private fun RationaleQuote(text: String) {
    Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.outline),
        )
        Column(modifier = Modifier.padding(start = 13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "descrizione del modello",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
            )
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Ultime esecuzioni
// -----------------------------------------------------------------------------

@Composable
private fun RecentRunsSection(runs: List<LogRow>, onOpenFullLog: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(SectionLabelSpacing)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Ultime esecuzioni", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = onOpenFullLog, modifier = Modifier.heightIn(min = 48.dp)) { Text("Vedi tutte") }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.outlineVariant),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            runs.forEach { RunRow(it) }
        }
    }
}

@Composable
private fun RunRow(row: LogRow) {
    val (icon, color) = runVisual(row)
    // §6.4: soppressioni/condizioni-non-soddisfatte sono rumore utile -> attenuate.
    val attenuated = row.kind in setOf(
        AuditKind.SUPPRESSED_DUPLICATE,
        AuditKind.SUPPRESSED_COOLDOWN,
        AuditKind.SUPPRESSED_NOT_ELIGIBLE,
        AuditKind.CONDITIONS_NOT_MET,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .alpha(if (attenuated) 0.6f else 1f)
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(17.dp))
        Text(
            row.summary,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(row.timeLabel, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun runVisual(row: LogRow): Pair<ImageVector, Color> {
    val s = LocalArgusSemantic.current
    val faint = MaterialTheme.colorScheme.onSurfaceVariant
    return when (row.kind) {
        AuditKind.ERROR -> Icons.Rounded.Error to s.error.fg
        AuditKind.BLOCKED_POLICY -> Icons.Rounded.Error to s.error.fg
        AuditKind.SUPPRESSED_DUPLICATE,
        AuditKind.SUPPRESSED_COOLDOWN -> Icons.Rounded.Block to faint
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
// Footer per stato (design §7.3)
// -----------------------------------------------------------------------------

@Composable
private fun DetailFooter(state: AutomationDetailState, callbacks: AutomationDetailCallbacks) {
    Column {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Surface(color = MaterialTheme.colorScheme.background) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                when (state.status) {
                    StatusBadge.PENDING_APPROVAL -> PendingFooter(state, callbacks)
                    StatusBadge.ARMED, StatusBadge.DISABLED -> ReviewableFooter(callbacks)
                    StatusBadge.NEEDS_REVIEW -> NeedsReviewFooter(callbacks)
                }
            }
        }
    }
}

@Composable
private fun PendingFooter(state: AutomationDetailState, callbacks: AutomationDetailCallbacks) {
    val semantic = LocalArgusSemantic.current
    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
        // §5.2: quando l'arm è bloccato il motivo è SEMPRE visibile accanto al bottone
        // disabilitato — con fallback generico se l'host non ha valorizzato il motivo.
        if (!state.canArm) {
            val reason = state.armBlockedReason ?: "Regola non armabile"
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Rounded.Block, contentDescription = null, tint = semantic.error.fg, modifier = Modifier.size(16.dp))
                Text(
                    "Arma bloccato: $reason",
                    color = semantic.error.fg,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = callbacks::onReject, modifier = Modifier.heightIn(min = 48.dp)) { Text("Rifiuta") }
            OutlinedButton(onClick = callbacks::onAskEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("Modifica") }
            // Arma = unica azione filled/accent; abilitata SOLO se canArm (invariante §5.2).
            Button(
                onClick = callbacks::onArm,
                enabled = state.canArm,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(Icons.Rounded.Shield, contentDescription = null, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(6.dp))
                Text("Arma")
            }
        }
    }
}

/**
 * ARMED/DISABLED: [elimina] [Modifica in chat] [Esegui ora]. §5.6: né elimina né
 * esegui-ora sono pre-focusati/filled — restano outline, così nessuna azione
 * irreversibile è l'azione di default.
 */
@Composable
private fun ReviewableFooter(callbacks: AutomationDetailCallbacks) {
    val semantic = LocalArgusSemantic.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = callbacks::onDelete,
            modifier = Modifier.size(48.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = semantic.error.fg),
            border = androidx.compose.foundation.BorderStroke(1.dp, semantic.error.fg.copy(alpha = 0.55f)),
        ) {
            Icon(Icons.Rounded.Delete, contentDescription = "Elimina", modifier = Modifier.size(21.dp))
        }
        OutlinedButton(onClick = callbacks::onAskEdit, modifier = Modifier.heightIn(min = 48.dp)) { Text("Modifica in chat") }
        OutlinedButton(
            onClick = callbacks::onRunNow,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(19.dp))
            Spacer(Modifier.width(6.dp))
            Text("Esegui ora")
        }
    }
}

@Composable
private fun NeedsReviewFooter(callbacks: AutomationDetailCallbacks) {
    // "Ricrea in chat" full-width: la regola non è decodificabile, si ri-chiede in chat (onAskEdit).
    Button(
        onClick = callbacks::onAskEdit,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Text("Ricrea in chat")
    }
}

// =============================================================================
// Preview — fixture inline (i Fixtures centralizzati arrivano in Unit F).
// Basate sui 3 esempi del prototipo approvato (design §1a).
// =============================================================================

private val previewReplyRule = RuleRender(
    triggerLine = "Quando: notifica WhatsApp da \"Moglie\" (chat 1:1)",
    triggerIconKey = "notification",
    conditionLines = listOf("Solo tra le 18:00 e le 22:00 (Europe/Rome)"),
    actions = listOf(
        ActionRow(
            iconKey = "generative",
            label = "Rispondi con l'AI",
            detail = "Obiettivo: rispondere nel tono concordato.\nTool consentiti: whatsapp_reply\nDestinatario: vincolato al mittente (Moglie)",
            isShell = false,
            shellCommand = null,
            isGenerative = true,
            requiresLiveConfirm = false,
        ),
    ),
    isGenerative = true,
    privacyNote = "Il testo della notifica verrà inviato a Hermes e da lì al provider cloud per generare la risposta.",
)

private val previewReplyWarnings = listOf(
    UiWarning(
        Severity.WARNING,
        "privacy_generative",
        "Il testo della notifica verrà inviato a Hermes e da lì al provider cloud per generare la risposta.",
    ),
    UiWarning(
        Severity.WARNING,
        "read_plus_reply",
        "Questa regola legge dati dal dispositivo (testo della notifica) e li può inviare al mittente. Possibile esfiltrazione di contesto.",
    ),
)

private val previewReplyDetail = AutomationDetailState(
    id = "a3",
    name = "Rispondi a Moglie su WhatsApp",
    status = StatusBadge.PENDING_APPROVAL,
    rule = previewReplyRule,
    rationale = "Quando Moglie ti scrive su WhatsApp nella fascia serale, genero io una risposta nel tono che mi hai indicato e la invio.",
    warnings = previewReplyWarnings,
    canArm = true,
    armBlockedReason = null,
    estimatedLlmCallsPerDay = "≈ 5 chiamate/giorno · cooldown minimo 60 s",
    recentRuns = emptyList(),
    geofencePreviewLabel = null,
)

private val previewForbiddenShellRule = RuleRender(
    triggerLine = "Quando: ogni giorno alle 00:00 (Europe/Rome)",
    triggerIconKey = "time",
    conditionLines = emptyList(),
    actions = listOf(
        ActionRow(
            iconKey = "generative",
            label = "Decidi ed esegui con l'AI",
            detail = "Obiettivo: individuare i giochi e disinstallarli.\nTool richiesti: shell.run, app.install",
            isShell = false,
            shellCommand = null,
            isGenerative = true,
            requiresLiveConfirm = false,
        ),
    ),
    isGenerative = true,
    privacyNote = "Regola generativa con accesso alla shell.",
)

private val previewForbiddenShellDetail = AutomationDetailState(
    id = "a7",
    name = "Disinstalla giochi dopo mezzanotte",
    status = StatusBadge.PENDING_APPROVAL,
    rule = previewForbiddenShellRule,
    rationale = "A mezzanotte controllo quali app sono giochi e le rimuovo.",
    warnings = listOf(
        UiWarning(
            Severity.ERROR,
            "generative_forbidden_tool",
            "Una regola generativa non può usare shell.run né app.install: al fire-time l'LLM genererebbe comandi mai approvati.",
        ),
    ),
    canArm = false,
    armBlockedReason = "tool 'shell.run' e 'app.install' vietati nelle regole generative (invariante di sicurezza)",
    estimatedLlmCallsPerDay = "≈ 30 chiamate/giorno · alto rischio",
    recentRuns = emptyList(),
    geofencePreviewLabel = null,
)

private val previewGeofenceRule = RuleRender(
    triggerLine = "Quando: esci dalla posizione attuale (±50 m)",
    triggerIconKey = "geofence",
    conditionLines = emptyList(),
    actions = listOf(
        ActionRow("wifi_off", "Disattiva Wi-Fi", null, false, null, false, false),
        ActionRow("bluetooth", "Attiva Bluetooth", null, false, null, false, false),
    ),
    isGenerative = false,
    privacyNote = null,
)

private val previewArmedDetail = AutomationDetailState(
    id = "a1",
    name = "Casa · spegni Wi-Fi uscendo",
    status = StatusBadge.ARMED,
    rule = previewGeofenceRule,
    rationale = "Ho messo un geofence sulla tua posizione attuale (50 m); appena esci spengo il Wi-Fi e accendo il Bluetooth per l'auto.",
    warnings = listOf(
        UiWarning(
            Severity.WARNING,
            "geofence_radius",
            "Raggio 50 m sotto i 100 m consigliati: lo scatto in uscita può arrivare con 2-15 min di ritardo (batching a schermo spento).",
        ),
    ),
    canArm = true,
    armBlockedReason = null,
    estimatedLlmCallsPerDay = null,
    recentRuns = listOf(
        LogRow("r1", "ieri 18:32", "Casa · spegni Wi-Fi uscendo", AuditKind.FIRED, LogOutcome.SUCCESS, "Wi-Fi off · Bluetooth on", null),
        LogRow("r2", "mar 08:10", "Casa · spegni Wi-Fi uscendo", AuditKind.CONDITIONS_NOT_MET, LogOutcome.SUCCESS, "già fuori zona", null),
    ),
    geofencePreviewLabel = "Posizione: quella attuale al momento dell'attivazione",
)

private val previewNeedsReviewDetail = AutomationDetailState(
    id = "a6",
    name = "Promemoria farmaci",
    status = StatusBadge.NEEDS_REVIEW,
    rule = RuleRender(
        triggerLine = "Regola salvata con uno schema precedente",
        triggerIconKey = "time",
        conditionLines = emptyList(),
        actions = emptyList(),
        isGenerative = false,
        privacyNote = null,
    ),
    rationale = null,
    warnings = listOf(
        UiWarning(
            Severity.ERROR,
            "schema_migration",
            "La regola è stata creata con una versione precedente dello schema e non è più decodificabile automaticamente. Ri-chiedila in chat per ricrearla.",
        ),
    ),
    canArm = false,
    armBlockedReason = "schema incompatibile — ricrea la regola in chat",
    estimatedLlmCallsPerDay = null,
    recentRuns = emptyList(),
    geofencePreviewLabel = null,
)

private object NoopDetailCallbacks : AutomationDetailCallbacks {
    override fun onArm() {}
    override fun onReject() {}
    override fun onSetEnabled(enabled: Boolean) {}
    override fun onDelete() {}
    override fun onAskEdit() {}
    override fun onRunNow() {}
    override fun onOpenFullLog() {}
}

@Preview(name = "Detail · PENDING pulita (canArm)", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 900)
@Composable
private fun DetailPendingCleanPreview() {
    ArgusTheme { AutomationDetailScreen(previewReplyDetail, NoopDetailCallbacks) }
}

@Preview(name = "Detail · PENDING ERROR (arm bloccato)", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 900)
@Composable
private fun DetailPendingBlockedPreview() {
    ArgusTheme { AutomationDetailScreen(previewForbiddenShellDetail, NoopDetailCallbacks) }
}

@Preview(name = "Detail · ARMED", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 900)
@Composable
private fun DetailArmedPreview() {
    ArgusTheme { AutomationDetailScreen(previewArmedDetail, NoopDetailCallbacks) }
}

@Preview(name = "Detail · NEEDS_REVIEW", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 900)
@Composable
private fun DetailNeedsReviewPreview() {
    ArgusTheme { AutomationDetailScreen(previewNeedsReviewDetail, NoopDetailCallbacks) }
}
