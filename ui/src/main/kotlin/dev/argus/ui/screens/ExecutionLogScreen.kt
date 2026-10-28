package dev.argus.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.argus.engine.runtime.AuditKind
import dev.argus.ui.R
import dev.argus.ui.components.ArgusActionRow
import dev.argus.ui.components.ArgusButton
import dev.argus.ui.components.ArgusCard
import dev.argus.ui.components.ArgusIconButton
import dev.argus.ui.components.ArgusLargeTopBar
import dev.argus.ui.components.ArgusSegmentedFilter
import dev.argus.ui.components.ButtonStyle
import dev.argus.ui.components.CloudTag
import dev.argus.ui.components.CodeBlock
import dev.argus.ui.components.EmptyState
import dev.argus.ui.components.GenerativeTag
import dev.argus.ui.components.GroupPosition
import dev.argus.ui.components.IconBadge
import dev.argus.ui.components.SectionLabel
import dev.argus.ui.components.groupPositionOf
import dev.argus.ui.components.groupShape
import dev.argus.ui.model.ExecutionLogCallbacks
import dev.argus.ui.model.ExecutionLogState
import dev.argus.ui.model.LogOutcome
import dev.argus.ui.model.LogRetentionOptions
import dev.argus.ui.model.LogRow
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.presentation.LogDayGrouping
import dev.argus.ui.presentation.LogFilter
import dev.argus.ui.presentation.RenderLanguage
import dev.argus.ui.presentation.orNotAvailable
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme
import java.time.ZoneId

// =============================================================================
// Schermo Log esecuzioni (handoff §6.4, design §7.4; redesign A, tavole «Log» e «Log · filtro»).
// Comportamenti resi qui:
//  - TIMELINE raggruppata per giorno: header "Oggi"/"Ieri"/… calcolati dal timestamp della riga (giorno
//    locale nel fuso del telefono, §7.4), non dal testo di `timeLabel`. Ogni giorno e' un gruppo di card
//    (angoli 22 in testa e in coda, 5 in mezzo).
//  - FILTRO PER ESITO (Tutti · Problemi · Generative · Ciclo di vita): locale alla schermata, sulle righe gia'
//    caricate; non tocca ViewModel ne' database. Si perde alla chiusura della schermata, non alla rotazione.
//  - Icona esito per outcome/kind (verde/ambra/rosso/grigio) + summary colorato. Il ciclo di vita ha il
//    contenitore quadrato arrotondato, le esecuzioni il cerchio.
//  - SUPPRESSED_COOLDOWN / CONDITIONS_NOT_MET = rumore utile → riga SILENZIOSA: testo e icona in
//    `onSurfaceVariant`, peso 500, mai trasparenza (§6.4/§7.4).
//  - Righe con dettaglio espandibili: chevron ruota 180°, mostra i passi per-azione in un blocco monospace
//    (tecnico, solo esiti e codici: mai il testo dei messaggi).
//  - Righe DEFERRED (E13): «Copia e apri WhatsApp» e «Segna come inviata» SEMPRE visibili sotto la riga.
//  - `filterAutomationName` → pillola "Solo: <nome>" con "×" (onClearFilter).
//  - Un valore assente si scrive «n/d», mai 0 o vuoto (invariante 8).
// Stateless: (state, callbacks). L'espansione e il filtro sono UI-state effimero (remember), come
// ChatErrorBanner; onExpand(id) notifica comunque l'host.
// =============================================================================

@Composable
fun ExecutionLogScreen(
    state: ExecutionLogState,
    callbacks: ExecutionLogCallbacks,
    modifier: Modifier = Modifier,
    initialFilter: LogFilter = LogFilter.ALL,
) {
    // Espansione locale per id (effimera): il contratto non porta gli id aperti.
    val expandedIds = remember { mutableStateMapOf<String, Boolean>() }
    // Menu aperto e conferma di «Svuota il log»: stato effimero della sola schermata.
    var menuOpen by remember { mutableStateOf(false) }
    var confirmingClear by remember { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf(initialFilter) }

    Surface(color = ArgusTheme.surfaces.page, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ArgusLargeTopBar(
                title = stringResource(R.string.log_title),
                actions = {
                    LogMenu(
                        retentionDays = state.retentionDays,
                        open = menuOpen,
                        onOpenChange = { menuOpen = it },
                        onSetRetention = callbacks::onSetRetention,
                        onAskClear = { confirmingClear = true },
                        onExport = callbacks::onExport,
                    )
                },
            )
            if (confirmingClear) {
                ClearConfirmation(
                    onCancel = { confirmingClear = false },
                    onConfirm = {
                        confirmingClear = false
                        callbacks.onClearLog()
                    },
                )
            }

            state.filterAutomationName?.let { name ->
                AutomationFilterPill(name, callbacks::onClearFilter)
            }

            val hasEntries = !state.loading && state.entries.isNotEmpty()
            if (hasEntries) {
                val filterLabels = LogFilter.entries.associateWith { filterLabel(it) }
                ArgusSegmentedFilter(
                    options = LogFilter.entries,
                    selected = filter,
                    onSelect = { filter = it },
                    label = { filterLabels.getValue(it) },
                    groupDescription = stringResource(R.string.rda_ls_log_filter_group),
                    modifier = Modifier.padding(top = ArgusSpacing.Unit),
                )
            }

            val shown = remember(state.entries, filter) { LogFilter.apply(filter, state.entries) }
            when {
                state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                state.entries.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.History,
                    title = stringResource(R.string.log_empty_title),
                    body = stringResource(R.string.log_empty_body),
                )
                shown.isEmpty() -> EmptyState(
                    icon = Icons.Rounded.FilterAltOff,
                    title = stringResource(R.string.rda_ls_log_filter_empty_title),
                    body = stringResource(R.string.rda_ls_log_filter_empty_body),
                    ctaLabel = stringResource(R.string.rda_ls_log_filter_clear),
                    onCta = { filter = LogFilter.ALL },
                )
                else -> {
                    // Giorni dal timestamp (B49); l'ordine di prima comparsa resta quello dell'host (più recenti in cima).
                    val groups = remember(shown) {
                        LogDayGrouping.group(shown, System.currentTimeMillis(), ZoneId.systemDefault(), RenderLanguage.system())
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = ArgusSpacing.Large, end = ArgusSpacing.Large, bottom = ArgusSpacing.Large),
                        verticalArrangement = Arrangement.spacedBy(ArgusSpacing.GroupGap),
                    ) {
                        groups.forEachIndexed { groupIndex, group ->
                            item(key = "hdr-${group.key}") {
                                SectionLabel(
                                    group.title,
                                    Modifier.padding(
                                        start = ArgusSpacing.Unit,
                                        top = if (groupIndex == 0 && state.filterAutomationName == null) 20.dp else 24.dp,
                                        bottom = 5.dp,
                                    ),
                                )
                            }
                            itemsIndexed(group.rows, key = { _, row -> row.id }) { index, row ->
                                LogRowCard(
                                    row = row,
                                    position = groupPositionOf(index, group.rows.size),
                                    expanded = expandedIds[row.id] == true,
                                    onToggle = {
                                        expandedIds[row.id] = !(expandedIds[row.id] ?: false)
                                        callbacks.onExpand(row.id)
                                    },
                                    onSendNow = { callbacks.onSendNow(row.id) },
                                    onMarkSent = { callbacks.onMarkSent(row.id) },
                                    onOpenAutomation = { row.automationId?.let(callbacks::onOpenAutomation) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun filterLabel(filter: LogFilter): String = stringResource(
    when (filter) {
        LogFilter.ALL -> R.string.rda_ls_log_filter_all
        LogFilter.PROBLEMS -> R.string.rda_ls_log_filter_problems
        LogFilter.GENERATIVE -> R.string.rda_ls_log_filter_generative
        LogFilter.LIFECYCLE -> R.string.rda_ls_log_filter_lifecycle
    },
)

// -----------------------------------------------------------------------------
// Menu in alto (conservazione, svuota, esporta) + conferma inline
// -----------------------------------------------------------------------------

@Composable
private fun LogMenu(
    retentionDays: Int,
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    onSetRetention: (Int) -> Unit,
    onAskClear: () -> Unit,
    onExport: () -> Unit,
) {
    Box {
        ArgusIconButton(Icons.Rounded.MoreVert, stringResource(R.string.log_menu_open), onClick = { onOpenChange(true) })
        DropdownMenu(expanded = open, onDismissRequest = { onOpenChange(false) }) {
            Text(
                stringResource(R.string.log_menu_retention_title),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
            LogRetentionOptions.DAYS.forEach { days ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.log_menu_retention_option, days)) },
                    trailingIcon = if (days == retentionDays) {
                        { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    } else {
                        null
                    },
                    onClick = {
                        onOpenChange(false)
                        onSetRetention(days)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(R.string.log_menu_clear)) },
                onClick = {
                    onOpenChange(false)
                    onAskClear()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.log_menu_export)) },
                onClick = {
                    onOpenChange(false)
                    onExport()
                },
            )
        }
    }
}

/** Conferma dentro la schermata (non un dialog di sistema): lo svuotamento non si puo' annullare. */
@Composable
private fun ClearConfirmation(onCancel: () -> Unit, onConfirm: () -> Unit) {
    ArgusCard(
        modifier = Modifier.fillMaxWidth().padding(horizontal = ArgusSpacing.Large, vertical = ArgusSpacing.Unit),
        shape = RoundedCornerShape(ArgusRadius.Lg),
        contentPadding = PaddingValues(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp),
    ) {
        Text(
            stringResource(R.string.log_clear_confirm),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        ArgusActionRow(Modifier.padding(top = ArgusSpacing.Small)) {
            ArgusButton(stringResource(R.string.log_clear_confirm_cancel), onCancel, style = ButtonStyle.Text)
            // Svuotare il log non si annulla: azione distruttiva, rossa, dopo la conferma.
            ArgusButton(stringResource(R.string.log_clear_confirm_action), onConfirm, style = ButtonStyle.Destructive)
        }
    }
}

// -----------------------------------------------------------------------------
// Pillola filtro per automazione
// -----------------------------------------------------------------------------

/** "Solo: <nome>" con "×" — filtro attivo arrivando dal dettaglio (§6.4). */
@Composable
private fun AutomationFilterPill(name: String, onClear: () -> Unit) {
    val content = MaterialTheme.colorScheme.onSecondaryContainer
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ArgusSpacing.Large, end = ArgusSpacing.Large, top = ArgusSpacing.Unit),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = content,
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(start = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Rounded.FilterAlt, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Text(
                stringResource(R.string.log_filter_only, name),
                color = content,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.weight(1f),
            )
            ArgusIconButton(Icons.Rounded.Close, stringResource(R.string.log_filter_remove), onClear, tint = content)
        }
    }
}

// -----------------------------------------------------------------------------
// Riga log
// -----------------------------------------------------------------------------

/** Icona, colori e peso di una riga: lo stato sta nel colore, nell'icona E nel testo (mai solo nel colore). */
private data class OutcomeStyle(
    val icon: ImageVector,
    val container: Color,
    val content: Color,
    /** Colore del riepilogo sotto il titolo. */
    val summary: Color,
    /** Rumore utile (soppressa, condizioni non soddisfatte): titolo `onSurfaceVariant`, peso 500. */
    val quiet: Boolean = false,
    /** Contenitore quadrato arrotondato (ciclo di vita) invece del cerchio (esecuzioni). */
    val squircle: Boolean = false,
)

@Composable
private fun outcomeStyle(row: LogRow): OutcomeStyle {
    val s = ArgusTheme.semantic
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val neutral = ArgusTheme.surfaces.card2
    return when (row.kind) {
        AuditKind.ERROR,
        AuditKind.BLOCKED_POLICY,
        AuditKind.VALIDATION_REJECTED,
        AuditKind.ARM_FAILED,
        AuditKind.SCHEDULING_FAILED,
        AuditKind.ENABLE_FAILED,
        -> OutcomeStyle(Icons.Rounded.Error, s.error.bg, s.error.fg, s.error.fg)
        AuditKind.SUPPRESSED_DUPLICATE,
        AuditKind.SUPPRESSED_COOLDOWN,
        AuditKind.SUPPRESSED_NOT_ELIGIBLE,
        -> OutcomeStyle(Icons.Rounded.Block, neutral, muted, muted, quiet = true)
        AuditKind.SUPPRESSED_BUDGET -> OutcomeStyle(Icons.Rounded.Block, s.pending.bg, s.pending.fg, s.pending.fg)
        AuditKind.CONDITIONS_NOT_MET -> OutcomeStyle(Icons.Rounded.FilterAltOff, neutral, muted, muted, quiet = true)
        // Ciclo di vita: eventi informativi, non esiti di esecuzione.
        AuditKind.RULE_ARMED,
        AuditKind.RULE_ENABLED,
        -> OutcomeStyle(Icons.Rounded.Shield, s.armed.bg, s.armed.fg, muted, squircle = true)
        AuditKind.RULE_DISABLED,
        AuditKind.RULE_DELETED,
        -> OutcomeStyle(Icons.Rounded.PauseCircle, s.disabled.bg, s.disabled.fg, muted, squircle = true)
        AuditKind.RULE_NEEDS_REVIEW ->
            OutcomeStyle(Icons.Rounded.SyncProblem, s.needsReview.bg, s.needsReview.fg, s.needsReview.fg, squircle = true)
        AuditKind.FIRED -> when (row.outcome) {
            LogOutcome.SUCCESS -> OutcomeStyle(Icons.Rounded.Check, s.armed.bg, s.armed.fg, s.armed.fg)
            LogOutcome.PARTIAL -> OutcomeStyle(Icons.Rounded.Contrast, s.pending.bg, s.pending.fg, s.pending.fg)
            LogOutcome.FAILED -> OutcomeStyle(Icons.Rounded.Error, s.error.bg, s.error.fg, s.error.fg)
            LogOutcome.SUBMITTED -> OutcomeStyle(
                Icons.Rounded.HourglassTop,
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
                MaterialTheme.colorScheme.primary,
            )
            LogOutcome.DEFERRED -> OutcomeStyle(Icons.AutoMirrored.Rounded.Send, s.pending.bg, s.pending.fg, s.pending.fg)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LogRowCard(
    row: LogRow,
    position: GroupPosition,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSendNow: () -> Unit,
    onMarkSent: () -> Unit,
    onOpenAutomation: () -> Unit,
    timeText: String = LogDayGrouping.timeLabel(row, ZoneId.systemDefault()),
) {
    val style = outcomeStyle(row)
    val isDeferred = row.kind == AuditKind.FIRED && row.outcome == LogOutcome.DEFERRED
    val expandable = row.expandedDetail != null || row.automationId != null || isDeferred
    val notAvailable = stringResource(R.string.rda_ls_not_available)
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    ArgusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = groupShape(position, outer = 22.dp, inner = 5.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (expandable) {
                        Modifier.clickable(
                            role = Role.Button,
                            onClickLabel = stringResource(if (expanded) R.string.log_collapse else R.string.log_expand),
                            onClick = onToggle,
                        )
                    } else {
                        Modifier
                    },
                )
                .padding(start = 12.dp, end = if (expandable) 10.dp else 40.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(
                icon = style.icon,
                container = style.container,
                content = style.content,
                size = 32.dp,
                iconSize = 18.dp,
                shape = if (style.squircle) RoundedCornerShape(ArgusRadius.Sm) else CircleShape,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        row.automationName.orNotAvailable(notAvailable),
                        modifier = Modifier.weight(1f),
                        color = if (style.quiet) muted else onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (style.quiet) FontWeight.W500 else FontWeight.W600,
                    )
                    Text(
                        timeText.orNotAvailable(notAvailable),
                        color = muted,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.W500,
                    )
                }
                Text(
                    row.summary.orNotAvailable(notAvailable),
                    color = style.summary,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (row.isGenerative) {
                    // Invariante 5: una riga generativa porta SEMPRE la coppia generativa + cloud.
                    FlowRow(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        GenerativeTag()
                        CloudTag()
                    }
                }
            }
            if (expandable) {
                Icon(
                    Icons.Rounded.ExpandMore,
                    contentDescription = null,
                    tint = muted,
                    modifier = Modifier.padding(top = 2.dp).size(20.dp).rotate(if (expanded) 180f else 0f),
                )
            }
        }

        if (isDeferred) {
            // E13: la risposta è pronta ma non più consegnabile in automatico → invio manuale.
            // «Copia e apri» NON segna niente: solo «Segna come inviata» chiude la risposta.
            FlowRow(
                modifier = Modifier.padding(start = 56.dp, end = 12.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                ArgusButton(
                    text = stringResource(R.string.log_copy_and_open),
                    onClick = onSendNow,
                    icon = Icons.AutoMirrored.Rounded.Send,
                )
                ArgusButton(
                    text = stringResource(R.string.log_mark_sent),
                    onClick = onMarkSent,
                    style = ButtonStyle.Text,
                )
            }
        }

        AnimatedVisibility(visible = expanded && expandable) {
            ExpandedDetail(row, onOpenAutomation)
        }
    }
}

@Composable
private fun ExpandedDetail(row: LogRow, onOpenAutomation: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 12.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Passi per-azione (incl. esito lane generativa / DEFERRED): tecnici → monospace, solo esiti e codici (§4).
        row.expandedDetail?.takeIf { it.isNotEmpty() }?.let { steps ->
            CodeBlock(
                text = steps.joinToString("\n"),
                label = stringResource(R.string.rda_ls_log_detail_label),
                labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (row.automationId != null) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onOpenAutomation)
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    stringResource(R.string.log_open_automation),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelLarge,
                )
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// =============================================================================
// Preview — fixture inline, un'anteprima per stato nei due temi.
// =============================================================================

private val previewEntries = listOf(
    LogRow(
        "l1", "oggi 23:00", "DND dopo le 23:00", AuditKind.FIRED, LogOutcome.SUCCESS,
        "Non disturbare attivato",
        listOf("1. Imposta Non disturbare → ok", "2. Imposta suoneria → ok"),
        automationId = "dnd",
    ),
    LogRow(
        "l8", "oggi 19:40", "Rispondi a Moglie su WhatsApp", AuditKind.FIRED, LogOutcome.SUBMITTED,
        "Generazione risposta in corso…",
        listOf("1. Generazione AI → accodata"),
        automationId = "reply", isGenerative = true,
    ),
    LogRow(
        "l2", "oggi 19:14", "Rispondi a Moglie su WhatsApp", AuditKind.FIRED, LogOutcome.DEFERRED,
        "Risposta pronta — consegna manuale",
        listOf("1. Generazione AI → ok", "2. Risposta WhatsApp → differita"),
        automationId = "reply", isGenerative = true,
    ),
    LogRow(
        "l3", "oggi 18:32", "Casa · spegni Wi-Fi uscendo", AuditKind.FIRED, LogOutcome.SUCCESS,
        "2/2 azioni riuscite",
        listOf("1. Imposta Wi-Fi → ok", "2. Cattura schermata → ok · Pictures/Argus/Argus-20261028-183205.png"),
        automationId = "home",
    ),
    LogRow(
        "l4", "oggi 12:05", "Backup foto notturno", AuditKind.FIRED, LogOutcome.PARTIAL,
        "1/2 azioni riuscite · 1 fallita",
        listOf("1. Richiesta HTTP → ok", "2. Imposta Wi-Fi → fallita · permesso mancante"),
        automationId = "backup",
    ),
    LogRow(
        "l5", "ieri 22:10", "Ufficio · silenzia entrando", AuditKind.RULE_DISABLED, LogOutcome.SUCCESS,
        "Regola disabilitata dall'utente", null, automationId = "office",
    ),
    LogRow(
        "l6", "ieri 21:00", "DND dopo le 23:00", AuditKind.CONDITIONS_NOT_MET, LogOutcome.SUCCESS,
        "condizioni non soddisfatte", null, automationId = "dnd",
    ),
    LogRow(
        "l7", "ieri 20:05", "Casa · spegni Wi-Fi uscendo", AuditKind.SUPPRESSED_COOLDOWN, LogOutcome.SUCCESS,
        "soppressa dal cooldown", null, automationId = "home",
    ),
    LogRow(
        "l9", "ieri 09:30", "Promemoria farmaci", AuditKind.RULE_NEEDS_REVIEW, LogOutcome.FAILED,
        "Regola da rivedere (schema_migration)", null, automationId = "meds",
    ),
    LogRow(
        "l10", "ieri 08:50", "Backup foto notturno", AuditKind.ERROR, LogOutcome.FAILED,
        "errore di esecuzione (Shizuku non è attivo)", listOf("1. Esegui comando shell → fallita · Shizuku non è attivo"),
        automationId = "backup",
    ),
)

private object NoopLogCallbacks : ExecutionLogCallbacks {
    override fun onExpand(id: String) {}
    override fun onClearFilter() {}
    override fun onOpenAutomation(id: String) {}
}

private fun previewState(
    entries: List<LogRow> = previewEntries,
    filterName: String? = null,
    loading: Boolean = false,
) = ExecutionLogState(entries = entries, filterAutomationName = filterName, loading = loading)

@ArgusThemePreviews
@Composable
private fun LogAllPreview() {
    ArgusPreviewSurface { ExecutionLogScreen(previewState(), NoopLogCallbacks) }
}

@ArgusThemePreviews
@Composable
private fun LogProblemsFilterPreview() {
    ArgusPreviewSurface { ExecutionLogScreen(previewState(), NoopLogCallbacks, initialFilter = LogFilter.PROBLEMS) }
}

@ArgusThemePreviews
@Composable
private fun LogGenerativeFilterPreview() {
    ArgusPreviewSurface { ExecutionLogScreen(previewState(), NoopLogCallbacks, initialFilter = LogFilter.GENERATIVE) }
}

@ArgusThemePreviews
@Composable
private fun LogLifecycleFilterPreview() {
    ArgusPreviewSurface { ExecutionLogScreen(previewState(), NoopLogCallbacks, initialFilter = LogFilter.LIFECYCLE) }
}

@ArgusThemePreviews
@Composable
private fun LogFilterWithoutResultsPreview() {
    ArgusPreviewSurface {
        ExecutionLogScreen(
            previewState(entries = previewEntries.filter { it.kind == AuditKind.FIRED && it.outcome == LogOutcome.SUCCESS }),
            NoopLogCallbacks,
            initialFilter = LogFilter.PROBLEMS,
        )
    }
}

@ArgusThemePreviews
@Composable
private fun LogFilteredByAutomationPreview() {
    ArgusPreviewSurface {
        ExecutionLogScreen(
            previewState(
                entries = previewEntries.filter { it.automationName == "Casa · spegni Wi-Fi uscendo" },
                filterName = "Casa · spegni Wi-Fi uscendo",
            ),
            NoopLogCallbacks,
        )
    }
}

@ArgusThemePreviews
@Composable
private fun LogEmptyPreview() {
    ArgusPreviewSurface { ExecutionLogScreen(previewState(entries = emptyList()), NoopLogCallbacks) }
}

@ArgusThemePreviews
@Composable
private fun LogLoadingPreview() {
    ArgusPreviewSurface { ExecutionLogScreen(previewState(entries = emptyList(), loading = true), NoopLogCallbacks) }
}

/** Righe espanse: passi per azione monospace (con il nome del file dello screenshot), consegna manuale visibile. */
@ArgusThemePreviews
@Composable
private fun LogExpandedRowsPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(ArgusSpacing.GroupGap)) {
            val rows = listOf(previewEntries[3], previewEntries[2], previewEntries[4])
            rows.forEachIndexed { index, row ->
                LogRowCard(
                    row = row,
                    position = groupPositionOf(index, rows.size),
                    expanded = true,
                    onToggle = {},
                    onSendNow = {},
                    onMarkSent = {},
                    onOpenAutomation = {},
                    timeText = "18:32",
                )
            }
        }
    }
}

/** Valore assente: nome, riepilogo e ora mancanti si scrivono «n/d», mai vuoti e mai 0. */
@ArgusThemePreviews
@Composable
private fun LogMissingValuesPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp)) {
            LogRowCard(
                row = LogRow("n1", "", "", AuditKind.FIRED, LogOutcome.FAILED, "", null),
                position = GroupPosition.Only,
                expanded = false,
                onToggle = {},
                onSendNow = {},
                onMarkSent = {},
                onOpenAutomation = {},
                timeText = "",
            )
        }
    }
}

@ArgusThemePreviews
@Composable
private fun LogClearConfirmationPreview() {
    ArgusPreviewSurface { ClearConfirmation(onCancel = {}, onConfirm = {}) }
}
