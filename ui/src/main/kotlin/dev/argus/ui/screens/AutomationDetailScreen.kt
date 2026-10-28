package dev.argus.ui.screens

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material.icons.automirrored.rounded.ScheduleSend
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.safety.Severity
import dev.argus.ui.R
import dev.argus.ui.components.ApprovalAppPickerSheet
import dev.argus.ui.components.ApprovalRiskPanel
import dev.argus.ui.components.ApprovalRuleTimeline
import dev.argus.ui.components.ArgusActionFooter
import dev.argus.ui.components.ArgusButton
import dev.argus.ui.components.ArgusCard
import dev.argus.ui.components.ArgusCardGroup
import dev.argus.ui.components.ArgusDetailTopBar
import dev.argus.ui.components.BannerTone
import dev.argus.ui.components.ButtonStyle
import dev.argus.ui.components.ChipSize
import dev.argus.ui.components.CloudTag
import dev.argus.ui.components.DestructiveConfirmDialog
import dev.argus.ui.components.GenerativeTag
import dev.argus.ui.components.HealthBanner
import dev.argus.ui.components.IconBadge
import dev.argus.ui.components.ManualEntrySection
import dev.argus.ui.components.ModelDescriptionNote
import dev.argus.ui.components.SectionLabel
import dev.argus.ui.components.StatusBadgeChip
import dev.argus.ui.model.ActionRow
import dev.argus.ui.model.AppInfoUi
import dev.argus.ui.model.AppRef
import dev.argus.ui.model.AutomationDetailCallbacks
import dev.argus.ui.model.AutomationDetailState
import dev.argus.ui.model.DiagnosisFix
import dev.argus.ui.model.DiagnosisLevel
import dev.argus.ui.model.DiagnosisRow
import dev.argus.ui.model.EngineBanner
import dev.argus.ui.model.LogOutcome
import dev.argus.ui.model.LogRow
import dev.argus.ui.model.ProgramNode
import dev.argus.ui.model.RuleDiagnosisUi
import dev.argus.ui.model.RuleRender
import dev.argus.ui.model.RuleStatsUi
import dev.argus.ui.model.RuleStatsWindow
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.UiWarning
import dev.argus.ui.model.VarRow
import dev.argus.ui.model.VarSensitivity
import dev.argus.ui.preview.Fixtures
import dev.argus.ui.presentation.ATTENUATED_ROW_ALPHA
import dev.argus.ui.presentation.BudgetFormat
import dev.argus.ui.presentation.secondaryText
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.ArgusCodeSmall

// =============================================================================
// Schermo Dettaglio / Approvazione (redesign A, tavole «Approvazione · generativa», «Approvazione · P4 con
// shell (bloccata)» e «Dettaglio · armata»). È lo schermo dove gli invarianti di sicurezza pesano di più:
//  - riepilogo dei rischi e nota privacy SOPRA la piega, prima della regola, mai collassati (inv. 2 e 5);
//  - la regola si mostra da RuleRender, mai da `rationale` (inv. 4); la prosa del modello è un blocco
//    tratteggiato e attenuato, DOPO la regola;
//  - ERROR ⇒ canArm=false ⇒ «Arma» disabilitato col motivo scritto SOPRA i pulsanti (inv. 1-2);
//  - generativa ⇒ badge generativa + «esce verso il cloud», sempre in coppia (inv. 5);
//  - il comando shell è integrale, monospace, a capo (inv. 6); l'albero del programma è tutto espanso;
//  - «Arma» è l'unico pulsante pieno; Elimina è distruttivo e chiede conferma; «Esegui ora» resta visibile,
//    disabilitato col suo motivo (nota n7).
// Stateless: solo (state, callbacks). La navigazione è dell'host.
// =============================================================================

/** Banner salute da mostrare nel Dettaglio: i banner «Shizuku» valgono solo se la regola usa Shizuku. */
internal fun detailBannerToShow(state: AutomationDetailState): EngineBanner = when (state.banner) {
    EngineBanner.SHIZUKU_DOWN, EngineBanner.SHIZUKU_DEGRADED_AFTER_REBOOT ->
        if (state.usesShizuku) state.banner else EngineBanner.NONE
    // Il servizio AI irraggiungibile riguarda la chat, non il Dettaglio di una regola.
    EngineBanner.BRAIN_UNREACHABLE -> EngineBanner.NONE
    EngineBanner.BATTERY_NOT_EXEMPT, EngineBanner.NONE -> state.banner
}

/** «Esegui ora» ha la sua riga (abilitata o disabilitata col motivo) solo per una regola armata o disattivata. */
internal fun showRunNowRow(state: AutomationDetailState): Boolean =
    state.status == StatusBadge.ARMED || state.status == StatusBadge.DISABLED

/**
 * «Esegui ora» è abilitabile solo quando può davvero girare (B35): vale solo per il footer ARMED/DISABLED,
 * l'unico che lo ospita.
 */
internal fun showRunNowAction(state: AutomationDetailState): Boolean =
    state.canRunNow && showRunNowRow(state)

/** «Cambia app» si offre solo se il ViewModel lo consente e nessuna azione del footer è in corso. */
internal fun canChangeApp(state: AutomationDetailState): Boolean =
    state.canChangeApp && !state.actionInProgress && state.status != StatusBadge.NEEDS_REVIEW

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AutomationDetailScreen(
    state: AutomationDetailState,
    callbacks: AutomationDetailCallbacks,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmRunNow by remember { mutableStateOf(false) }
    // «Cambia app»: lo slot e il package che si stanno cambiando (null = foglio chiuso).
    var pickerSlot by rememberSaveable { mutableStateOf<Int?>(null) }
    var pickerPackage by rememberSaveable { mutableStateOf<String?>(null) }
    val changeApp: ((AppRef) -> Unit)? = if (canChangeApp(state)) {
        { ref ->
            pickerSlot = ref.slot
            pickerPackage = ref.packageName
            callbacks.onLoadAppChoices()
        }
    } else {
        null
    }
    Surface(color = ArgusTheme.surfaces.page, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ArgusDetailTopBar(
                title = if (state.status == StatusBadge.PENDING_APPROVAL) stringResource(R.string.detail_header_approval)
                else stringResource(R.string.detail_header_detail),
                onBack = callbacks::onBack,
                backContentDescription = stringResource(R.string.action_back),
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ArgusSpacing.Large),
                verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Large),
            ) {
                TitleAndBadges(state)

                // Nota n7: la salute del sistema che tocca QUESTA regola, tappabile verso Sistema.
                DetailHealthBanner(detailBannerToShow(state), callbacks::onBannerTap)

                // Invarianti 2 e 5: rischi, errori e nota privacy sopra la regola, mai collassati.
                ApprovalRiskPanel(
                    title = if (state.status == StatusBadge.PENDING_APPROVAL) stringResource(R.string.rda_ap_risks_title)
                    else stringResource(R.string.rda_ap_warnings_title),
                    warnings = state.warnings,
                )

                // Invariante 4: la verità visiva è SEMPRE RuleRender, resa dai tipi.
                ApprovalRuleTimeline(
                    rule = state.rule,
                    modifier = Modifier.padding(top = 6.dp),
                    geofenceLabel = state.geofencePreviewLabel,
                    estimate = state.estimatedLlmCallsPerDay,
                    appInfo = state.appInfo,
                    onChangeApp = changeApp,
                )

                // Probe positivo redatto: attesta il reader concreto senza mostrare il campione.
                if (state.verifiedStateReaders.isNotEmpty()) {
                    VerifiedReadersPanel(state.verifiedStateReaders)
                }

                // F8: regola manuale armata, scorciatoia Home e riquadro delle impostazioni rapide.
                state.manual?.let {
                    ManualEntrySection(
                        entry = it,
                        onAddToHome = callbacks::onAddToHome,
                        onUseInTile = callbacks::onUseInTile,
                    )
                }

                // Invariante 4: la prosa dell'LLM è un commento subordinato, dopo la regola.
                state.rationale?.let { ModelDescriptionNote(it) }

                if (state.recentRuns.isNotEmpty()) {
                    RecentRunsSection(state.recentRuns, callbacks::onOpenFullLog)
                }

                // F10: scatti, riuscite e fallite negli ultimi 7 e 30 giorni (solo regole salvate).
                state.stats?.let { RuleStatsSection(it) }

                // F2: solo per una regola salvata; chiusa di default, calcola alla prima apertura.
                if (state.diagnosisAvailable) {
                    DiagnosisSection(
                        diagnosis = state.diagnosis,
                        onDiagnose = callbacks::onDiagnose,
                        onOpenSystem = callbacks::onOpenSystem,
                    )
                }

                if (showRunNowRow(state)) {
                    RunNowCard(state, onRunNow = { if (state.canRunNow) confirmRunNow = true })
                }

                Spacer(Modifier.height(ArgusSpacing.Unit))
            }

            DetailFooter(
                state = state,
                callbacks = callbacks,
                onDelete = { confirmDelete = true },
            )
        }
    }
    val slot = pickerSlot
    if (slot != null) {
        ApprovalAppPickerSheet(
            choices = state.appChoices,
            currentPackage = pickerPackage,
            armedRule = state.status != StatusBadge.PENDING_APPROVAL,
            onPick = { choice ->
                pickerSlot = null
                callbacks.onChangeApp(slot, choice.packageName)
            },
            onDismiss = { pickerSlot = null },
        )
    }
    if (confirmDelete) {
        // B48: il pulsante che conferma l'eliminazione usa il colore di errore del tema.
        DestructiveConfirmDialog(
            title = stringResource(R.string.delete_dialog_title),
            body = stringResource(R.string.delete_dialog_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                confirmDelete = false
                callbacks.onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
    if (confirmRunNow) {
        AlertDialog(
            onDismissRequest = { confirmRunNow = false },
            title = { Text(stringResource(R.string.run_now_dialog_title)) },
            text = {
                Text(
                    stringResource(
                        if (state.manual != null) R.string.manual_run_now_body else R.string.run_now_dialog_body,
                    ),
                )
            },
            dismissButton = {
                TextButton(onClick = { confirmRunNow = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmRunNow = false
                        callbacks.onRunNow()
                    },
                ) { Text(stringResource(R.string.action_run_now)) }
            },
        )
    }
}

// -----------------------------------------------------------------------------
// Titolo, badge e banner salute
// -----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TitleAndBadges(state: AutomationDetailState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = ArgusSpacing.Unit)) {
        Text(
            state.name,
            modifier = Modifier.semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleLarge,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            StatusBadgeChip(state.status, size = ChipSize.Regular)
            // Invariante 5: generativa ⇒ badge generativa + «esce verso il cloud», sempre in coppia.
            if (state.rule.isGenerative) {
                GenerativeTag(size = ChipSize.Regular)
                CloudTag(size = ChipSize.Regular)
            }
        }
    }
}

/** Banner salute del Dettaglio: stessi testi della Lista, tappabile verso Sistema. */
@Composable
private fun DetailHealthBanner(banner: EngineBanner, onTap: () -> Unit) {
    val text = when (banner) {
        EngineBanner.SHIZUKU_DOWN -> stringResource(R.string.banner_shizuku_down)
        EngineBanner.SHIZUKU_DEGRADED_AFTER_REBOOT -> stringResource(R.string.banner_shizuku_degraded)
        EngineBanner.BATTERY_NOT_EXEMPT -> stringResource(R.string.banner_battery_not_exempt)
        EngineBanner.BRAIN_UNREACHABLE, EngineBanner.NONE -> return
    }
    HealthBanner(
        text = text,
        tone = BannerTone.Warning,
        icon = Icons.Rounded.WarningAmber,
        onClick = onTap,
        onClickLabel = stringResource(R.string.detail_diag_open_system),
    )
}

// -----------------------------------------------------------------------------
// Reader verificati
// -----------------------------------------------------------------------------

@Composable
private fun VerifiedReadersPanel(readers: List<String>) {
    val armed = ArgusTheme.semantic.armed
    ArgusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ArgusRadius.Lg),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = ArgusSpacing.Medium),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium), verticalAlignment = Alignment.Top) {
            IconBadge(Icons.Rounded.CheckCircle, armed.bg, armed.fg)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit)) {
                Text(
                    stringResource(R.string.detail_verified_readers),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                )
                readers.forEach { reader ->
                    Text(reader, color = MaterialTheme.colorScheme.onSurfaceVariant, style = ArgusCodeSmall)
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Ultime esecuzioni
// -----------------------------------------------------------------------------

@Composable
private fun RecentRunsSection(runs: List<LogRow>, onOpenFullLog: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SectionLabel(stringResource(R.string.detail_recent_runs), Modifier.padding(start = ArgusSpacing.Unit))
            ArgusButton(
                text = stringResource(R.string.detail_see_all),
                onClick = onOpenFullLog,
                style = ButtonStyle.Text,
            )
        }
        ArgusCardGroup(runs, outerRadius = 18.dp, innerRadius = 4.dp) { RunRow(it) }
    }
}

@Composable
private fun RunRow(row: LogRow) {
    val (icon, role) = runVisual(row)
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
            .heightIn(min = 56.dp)
            // B45: 0.6 portava il testo sotto 4,5:1; ATTENUATED_ROW_ALPHA lo tiene sopra.
            .alpha(if (attenuated) ATTENUATED_ROW_ALPHA else 1f)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
    ) {
        IconBadge(icon, role.first, role.second, size = 28.dp, iconSize = 16.dp)
        Text(
            row.summary,
            color = if (attenuated) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(row.timeLabel, color = MaterialTheme.colorScheme.secondaryText(), style = MaterialTheme.typography.labelMedium)
    }
}

/** Icona e coppia (sfondo, primo piano) dell'esito di una riga: il colore non è l'unico segnale. */
@Composable
private fun runVisual(row: LogRow): Pair<ImageVector, Pair<Color, Color>> {
    val s = ArgusTheme.semantic
    val neutral = ArgusTheme.surfaces.card2 to MaterialTheme.colorScheme.onSurfaceVariant
    return when (row.kind) {
        AuditKind.ERROR, AuditKind.BLOCKED_POLICY, AuditKind.VALIDATION_REJECTED, AuditKind.ARM_FAILED,
        AuditKind.SCHEDULING_FAILED, AuditKind.ENABLE_FAILED -> Icons.Rounded.Error to (s.error.bg to s.error.fg)
        AuditKind.SUPPRESSED_DUPLICATE, AuditKind.SUPPRESSED_COOLDOWN -> Icons.Rounded.Block to neutral
        AuditKind.SUPPRESSED_BUDGET -> Icons.Rounded.Block to (s.pending.bg to s.pending.fg)
        AuditKind.SUPPRESSED_NOT_ELIGIBLE -> Icons.Rounded.Block to neutral
        AuditKind.CONDITIONS_NOT_MET -> Icons.Rounded.FilterAltOff to neutral
        // Lifecycle (task #31-B): eventi informativi, non esiti di esecuzione.
        AuditKind.RULE_ARMED, AuditKind.RULE_ENABLED -> Icons.Rounded.CheckCircle to (s.armed.bg to s.armed.fg)
        AuditKind.RULE_DISABLED, AuditKind.RULE_DELETED -> Icons.Rounded.Block to neutral
        AuditKind.RULE_NEEDS_REVIEW -> Icons.Rounded.SyncProblem to (s.needsReview.bg to s.needsReview.fg)
        AuditKind.FIRED -> when (row.outcome) {
            LogOutcome.SUCCESS -> Icons.Rounded.CheckCircle to (s.armed.bg to s.armed.fg)
            LogOutcome.PARTIAL -> Icons.Rounded.CheckCircle to (s.pending.bg to s.pending.fg)
            LogOutcome.FAILED -> Icons.Rounded.Error to (s.error.bg to s.error.fg)
            LogOutcome.SUBMITTED ->
                Icons.AutoMirrored.Rounded.ScheduleSend to (MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.onPrimaryContainer)
            LogOutcome.DEFERRED -> Icons.AutoMirrored.Rounded.ScheduleSend to (s.cloud.bg to s.cloud.fg)
        }
    }
}

// -----------------------------------------------------------------------------
// Statistiche della regola (0.4.0, F10): ultimi 7 e 30 giorni
// -----------------------------------------------------------------------------

/** Giorni della finestra breve: la lunga arriva dallo stato ([RuleStatsUi.longWindowDays]). */
private const val SHORT_STATS_WINDOW_DAYS = 7

/**
 * Colonne da mostrare: la finestra breve e, se copre più giorni, quella lunga. Con il Log conservato
 * solo 7 giorni le due coinciderebbero: si mostra una colonna sola.
 */
internal fun statsColumns(stats: RuleStatsUi): List<Pair<Int, RuleStatsWindow>> =
    if (stats.longWindowDays > SHORT_STATS_WINDOW_DAYS) {
        listOf(SHORT_STATS_WINDOW_DAYS to stats.last7Days, stats.longWindowDays to stats.last30Days)
    } else {
        listOf(SHORT_STATS_WINDOW_DAYS to stats.last7Days)
    }

@Composable
internal fun RuleStatsSection(stats: RuleStatsUi) {
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit)) {
        SectionLabel(stringResource(R.string.ui40_stats_title), Modifier.padding(start = ArgusSpacing.Unit))
        ArgusCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = ArgusSpacing.Medium),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
                if (stats.isEmpty) {
                    Text(
                        stringResource(R.string.ui40_stats_none, stats.longWindowDays),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    val columns = statsColumns(stats)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Spacer(Modifier.weight(1f))
                        columns.forEach { (days, _) ->
                            Text(
                                stringResource(R.string.ui40_stats_days, days),
                                color = MaterialTheme.colorScheme.secondaryText(),
                                style = MaterialTheme.typography.labelSmall,
                                textAlign = TextAlign.End,
                                modifier = Modifier.widthIn(min = 72.dp),
                            )
                        }
                    }
                    StatsRow(stringResource(R.string.ui40_stats_fires), columns) { it.fires }
                    StatsRow(stringResource(R.string.ui40_stats_succeeded), columns) { it.succeeded }
                    StatsRow(stringResource(R.string.ui40_stats_failed), columns) { it.failed }
                }
                if (stats.longWindowDays < 30) {
                    Text(
                        stringResource(R.string.ui40_stats_retention_note, stats.longWindowDays),
                        color = MaterialTheme.colorScheme.secondaryText(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsRow(
    label: String,
    columns: List<Pair<Int, RuleStatsWindow>>,
    count: (RuleStatsWindow) -> Int,
) {
    // TalkBack legge una frase sola per riga («Scatti: 3 negli ultimi 7 giorni, 12 negli ultimi 30 giorni»).
    // `map` e' inline, `joinToString` no: le stringResource stanno nel map.
    val spoken = columns.map { (days, window) ->
        stringResource(
            R.string.ui40_stats_cell_cd,
            BudgetFormat.grouped(count(window).toLong()),
            stringResource(R.string.ui40_stats_days, days),
        )
    }.joinToString(", ")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = "$label: $spoken" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        columns.forEach { (_, window) ->
            Text(
                BudgetFormat.grouped(count(window).toLong()),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                textAlign = TextAlign.End,
                modifier = Modifier.widthIn(min = 72.dp),
            )
        }
    }
}

// -----------------------------------------------------------------------------
// «Perché non è scattata?» (0.3.6, F2): sezione chiusa di default
// -----------------------------------------------------------------------------

/**
 * Card con titolo e chevron. Al tocco si apre e chiede la diagnosi (una tantum); «Ricontrolla» la
 * rifà. Le righe arrivano già scritte nella lingua giusta: qui si disegnano soltanto.
 */
@Composable
internal fun DiagnosisSection(
    diagnosis: RuleDiagnosisUi?,
    onDiagnose: () -> Unit,
    onOpenSystem: () -> Unit,
    initiallyOpen: Boolean = false,
) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    ArgusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .clickable(
                    onClickLabel = stringResource(
                        if (open) R.string.detail_diag_hide else R.string.detail_diag_show,
                    ),
                    role = Role.Button,
                ) {
                    if (!open) onDiagnose()
                    open = !open
                }
                .padding(horizontal = 14.dp, vertical = ArgusSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
        ) {
            Text(
                stringResource(R.string.detail_diag_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp),
            )
        }
        if (open) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = ArgusSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
            ) {
                diagnosis?.rows?.forEach { DiagnosisRowView(it, onOpenSystem) }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    ArgusButton(
                        text = stringResource(R.string.detail_diag_recheck),
                        onClick = onDiagnose,
                        style = ButtonStyle.Text,
                    )
                    diagnosis?.let {
                        Text(
                            it.checkedAtLabel,
                            color = MaterialTheme.colorScheme.secondaryText(),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosisRowView(row: DiagnosisRow, onOpenSystem: () -> Unit) {
    val semantic = ArgusTheme.semantic
    val (icon, role, description) = when (row.level) {
        DiagnosisLevel.OK -> Triple(Icons.Rounded.CheckCircle, semantic.armed, stringResource(R.string.detail_diag_level_ok))
        DiagnosisLevel.WARN -> Triple(Icons.Rounded.WarningAmber, semantic.pending, stringResource(R.string.detail_diag_level_warn))
        DiagnosisLevel.INFO -> Triple(
            Icons.Rounded.Info,
            semantic.disabled,
            stringResource(R.string.detail_diag_level_info),
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
    ) {
        IconBadge(icon, role.bg, role.fg, size = 28.dp, iconSize = 16.dp, contentDescription = description)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                row.text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (row.fix == DiagnosisFix.OPEN_SYSTEM) {
                ArgusButton(
                    text = stringResource(R.string.detail_diag_open_system),
                    onClick = onOpenSystem,
                    style = ButtonStyle.Text,
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// «Esegui ora»: sempre visibile per una regola salvata, disabilitato col suo motivo
// -----------------------------------------------------------------------------

@Composable
private fun RunNowCard(state: AutomationDetailState, onRunNow: () -> Unit) {
    ArgusCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ArgusRadius.Lg),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = ArgusSpacing.Medium),
    ) {
        ArgusButton(
            text = stringResource(R.string.action_run_now),
            onClick = onRunNow,
            style = ButtonStyle.Outlined,
            icon = Icons.Rounded.PlayArrow,
            enabled = state.canRunNow && !state.actionInProgress,
            // Il motivo è testo visibile sotto il pulsante, mai un tooltip (regola 2 dei componenti).
            disabledReason = state.runNowBlockedReason ?: stringResource(R.string.rda_ap_run_now_fallback),
        )
    }
}

// -----------------------------------------------------------------------------
// Footer per stato
// -----------------------------------------------------------------------------

@Composable
private fun DetailFooter(
    state: AutomationDetailState,
    callbacks: AutomationDetailCallbacks,
    onDelete: () -> Unit,
) {
    when (state.status) {
        StatusBadge.PENDING_APPROVAL -> PendingFooter(state, callbacks)
        StatusBadge.ARMED, StatusBadge.DISABLED -> ReviewableFooter(state, callbacks, onDelete)
        StatusBadge.NEEDS_REVIEW -> NeedsReviewFooter(state, callbacks, onDelete)
    }
}

/**
 * In approvazione «Arma» è l'UNICO pulsante pieno (inv. 1). Con un ERROR (`canArm=false`) è disabilitato e il
 * motivo è scritto SOPRA i pulsanti, mai in un tooltip (inv. 2), con un motivo generico se l'host non ne dà uno.
 * Durante arma/rifiuta/elimina in corso (B26) il footer resta disabilitato.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PendingFooter(state: AutomationDetailState, callbacks: AutomationDetailCallbacks) {
    val reason = if (!state.canArm) {
        stringResource(
            R.string.detail_arm_blocked,
            state.armBlockedReason ?: stringResource(R.string.detail_not_armable),
        )
    } else {
        null
    }
    ArgusActionFooter(disabledReason = reason) {
        ArgusButton(
            text = stringResource(R.string.detail_arm),
            onClick = callbacks::onArm,
            icon = Icons.Rounded.Shield,
            fillWidth = true,
            enabled = state.canArm && !state.actionInProgress,
            minHeight = 52.dp,
        )
        // B46: le azioni secondarie vanno a capo invece di comprimersi con caratteri grandi.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
        ) {
            ArgusButton(
                text = stringResource(R.string.detail_reject),
                onClick = callbacks::onReject,
                style = ButtonStyle.Outlined,
                enabled = !state.actionInProgress,
                minHeight = 52.dp,
            )
            ArgusButton(
                text = stringResource(R.string.detail_edit),
                onClick = callbacks::onAskEdit,
                style = ButtonStyle.Outlined,
                icon = Icons.Rounded.Edit,
                enabled = !state.actionInProgress,
                minHeight = 52.dp,
            )
        }
    }
}

/**
 * ARMED/DISABLED: [Disattiva/Riattiva] (tonale, a tutta larghezza: nessun pulsante pieno), poi Elimina
 * (distruttivo, apre una conferma) e «Modifica in chat». §5.6: nessuna azione irreversibile è l'azione di default.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ReviewableFooter(
    state: AutomationDetailState,
    callbacks: AutomationDetailCallbacks,
    onDelete: () -> Unit,
) {
    val armed = state.status == StatusBadge.ARMED
    ArgusActionFooter {
        ArgusButton(
            text = if (armed) stringResource(R.string.detail_disable) else stringResource(R.string.detail_enable),
            onClick = { callbacks.onSetEnabled(state.status == StatusBadge.DISABLED) },
            style = ButtonStyle.Tonal,
            icon = if (armed) Icons.Rounded.PowerSettingsNew else Icons.Rounded.PlayArrow,
            fillWidth = true,
            enabled = !state.actionInProgress,
            minHeight = 52.dp,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
        ) {
            ArgusButton(
                text = stringResource(R.string.action_delete),
                onClick = onDelete,
                style = ButtonStyle.Destructive,
                icon = Icons.Rounded.Delete,
                enabled = !state.actionInProgress,
                minHeight = 52.dp,
            )
            ArgusButton(
                text = stringResource(R.string.detail_edit_in_chat),
                onClick = callbacks::onAskEdit,
                style = ButtonStyle.Outlined,
                icon = Icons.Rounded.ChatBubbleOutline,
                enabled = !state.actionInProgress,
                minHeight = 52.dp,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NeedsReviewFooter(
    state: AutomationDetailState,
    callbacks: AutomationDetailCallbacks,
    onDelete: () -> Unit,
) {
    ArgusActionFooter {
        ArgusButton(
            text = stringResource(R.string.detail_recreate_in_chat),
            onClick = callbacks::onAskEdit,
            icon = Icons.Rounded.ChatBubbleOutline,
            fillWidth = true,
            enabled = !state.actionInProgress,
            minHeight = 52.dp,
        )
        FlowRow {
            ArgusButton(
                text = stringResource(R.string.action_delete),
                onClick = onDelete,
                style = ButtonStyle.Destructive,
                icon = Icons.Rounded.Delete,
                enabled = !state.actionInProgress,
                minHeight = 52.dp,
            )
        }
    }
}

// =============================================================================
// Anteprime: una per stato, nei due temi (chiaro e scuro).
// =============================================================================

@Preview(name = "Chiaro", group = "Tema", showBackground = true, widthDp = 390, heightDp = 1100, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Scuro", group = "Tema", showBackground = true, widthDp = 390, heightDp = 1100, uiMode = Configuration.UI_MODE_NIGHT_YES)
private annotation class DetailThemePreviews

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

private val previewP4Shell = ActionRow(
    iconKey = "shell",
    label = "Esegui comando shell",
    detail = "Output in esito",
    isShell = true,
    shellCommand = "am broadcast -a dev.argus.example.OTP_FORWARD -n com.example.desktopbridge/.OtpReceiver --es code \"\${otp}\" --es origine sms --ei batteria \${batteria} && log -t argus \"otp inoltrato\"",
    isGenerative = false,
    requiresLiveConfirm = false,
)

private val previewP4Rule = RuleRender(
    triggerLine = "Quando: SMS ricevuto con testo che contiene \"codice\"",
    triggerIconKey = "phone",
    conditionLines = listOf("Solo tra le 07:00 e le 23:00 (Europe/Rome)"),
    actions = emptyList(),
    isGenerative = false,
    privacyNote = null,
    vars = listOf(
        VarRow(
            "otp", "testo", "esterno", "segreto", "SMS",
            sourceText = "campo testo · estrazione: [0-9]{6}", isExternal = true, sensitivity = VarSensitivity.SECRET,
        ),
        VarRow("batteria", "numero", "attendibile", "pubblico", "stato dispositivo", sourceText = "Livello batteria [numero]"),
        VarRow(
            "esito", "testo", "esterno", "privato", "output shell",
            sourceText = "output del comando", isExternal = true, sensitivity = VarSensitivity.PRIVATE,
        ),
    ),
    program = listOf(
        ProgramNode.Leaf(
            ActionRow("notify", "Copia negli appunti", "Testo: otp · cancellato dopo 60 s se invariato", false, null, false, false),
        ),
        ProgramNode.IfNode(
            title = "Se:",
            conditionLines = listOf("batteria > 15"),
            thenTitle = "Allora:",
            then = listOf(
                ProgramNode.WhileNode(
                    title = "Ripeti fino a 3 volte · 2000 ms tra le iterazioni",
                    conditionLines = listOf("esito ≠ \"ok\""),
                    body = listOf(ProgramNode.Leaf(previewP4Shell)),
                ),
            ),
            elseTitle = "Altrimenti:",
            orElse = listOf(
                ProgramNode.Leaf(
                    ActionRow("notify", "Mostra notifica", "«OTP copiato — inoltro saltato: batteria bassa»", false, null, false, false),
                ),
            ),
        ),
    ),
)

private val previewP4Detail = AutomationDetailState(
    id = "p4",
    name = "OTP da SMS · copia e inoltra",
    status = StatusBadge.PENDING_APPROVAL,
    rule = previewP4Rule,
    rationale = "Quando arriva un SMS con un codice, lo copio negli appunti e, se la batteria è sopra il 15%, lo inoltro al PC con un broadcast, riprovando fino a 3 volte.",
    warnings = listOf(
        UiWarning(
            Severity.ERROR,
            "shell_external_trigger",
            "Un comando shell non può partire da un SMS: il mittente è falsificabile. Usa un trigger con identità non falsificabile (orario, geofence, connettività) o una chat WhatsApp in whitelist.",
        ),
        UiWarning(
            Severity.WARNING,
            "interpolation_shell_context",
            "Il comando interpola otp, un dato esterno e segreto: con la policy Aggressive un SMS costruito ad arte può iniettare comandi.",
        ),
        UiWarning(
            Severity.WARNING,
            "capability_unavailable",
            "Il comando shell richiede Shizuku attivo: ora è spento, quindi l'inoltro non partirebbe.",
        ),
    ),
    canArm = false,
    armBlockedReason = "shell da trigger SMS vietata — il mittente è falsificabile (invariante di sicurezza)",
    estimatedLlmCallsPerDay = null,
    recentRuns = emptyList(),
    geofencePreviewLabel = null,
    usesShizuku = true,
)

private val previewAppsRule = RuleRender(
    triggerLine = "Quando: apri YouTube",
    triggerIconKey = "app_event",
    conditionLines = emptyList(),
    actions = emptyList(),
    isGenerative = false,
    privacyNote = null,
    triggerAppRef = AppRef(0, "com.youtube"),
    program = listOf(
        ProgramNode.Leaf(
            ActionRow("launch_app", "Apri app", null, false, null, false, false, appRef = AppRef(1, "com.android.chrome")),
        ),
    ),
)

private val previewAppsDetail = AutomationDetailState(
    id = "ap",
    name = "Apri Chrome quando apri YouTube",
    status = StatusBadge.PENDING_APPROVAL,
    rule = previewAppsRule,
    rationale = "Quando apri YouTube apro anche Chrome.",
    warnings = listOf(
        UiWarning(
            Severity.WARNING,
            "app_not_installed",
            "Un'app usata da questa regola non è installata su questo telefono: installala oppure cambia app. (com.youtube)",
        ),
    ),
    canArm = true,
    armBlockedReason = null,
    estimatedLlmCallsPerDay = null,
    recentRuns = emptyList(),
    geofencePreviewLabel = null,
    appInfo = mapOf(
        "com.youtube" to AppInfoUi(name = null, installed = false),
        "com.android.chrome" to AppInfoUi(name = "Chrome", installed = true),
    ),
    canChangeApp = true,
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
    canArm = false,
    armBlockedReason = null,
    estimatedLlmCallsPerDay = null,
    recentRuns = listOf(
        LogRow("r1", "ieri 18:32", "Casa · spegni Wi-Fi uscendo", AuditKind.FIRED, LogOutcome.SUCCESS, "Wi-Fi off · Bluetooth on", null),
        LogRow("r2", "mar 08:10", "Casa · spegni Wi-Fi uscendo", AuditKind.CONDITIONS_NOT_MET, LogOutcome.SUCCESS, "già fuori zona", null),
    ),
    geofencePreviewLabel = "Posizione: quella attuale al momento dell'attivazione",
    runNowBlockedReason = "Non ancora disponibile: manca un percorso sicuro per eseguire le azioni saltando trigger e condizioni.",
    verifiedStateReaders = listOf("builtin · location_available · BOOLEAN"),
    banner = EngineBanner.SHIZUKU_DOWN,
    usesShizuku = true,
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

@DetailThemePreviews
@Composable
private fun DetailPendingGenerativePreview() {
    ArgusTheme { AutomationDetailScreen(previewReplyDetail, NoopDetailCallbacks) }
}

@DetailThemePreviews
@Composable
private fun DetailPendingBlockedPreview() {
    ArgusTheme { AutomationDetailScreen(previewForbiddenShellDetail, NoopDetailCallbacks) }
}

@Preview(name = "P4 con shell · scuro", group = "Tema", showBackground = true, widthDp = 390, heightDp = 1900, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "P4 con shell · chiaro", group = "Tema", showBackground = true, widthDp = 390, heightDp = 1900, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun DetailPendingP4ShellPreview() {
    ArgusTheme { AutomationDetailScreen(previewP4Detail, NoopDetailCallbacks) }
}

@DetailThemePreviews
@Composable
private fun DetailPendingAppsPreview() {
    ArgusTheme { AutomationDetailScreen(previewAppsDetail, NoopDetailCallbacks) }
}

@DetailThemePreviews
@Composable
private fun DetailArmedPreview() {
    ArgusTheme { AutomationDetailScreen(previewArmedDetail, NoopDetailCallbacks) }
}

@DetailThemePreviews
@Composable
private fun DetailNeedsReviewPreview() {
    ArgusTheme { AutomationDetailScreen(previewNeedsReviewDetail, NoopDetailCallbacks) }
}

@DetailThemePreviews
@Composable
private fun DetailDiagnosisClosedPreview() {
    ArgusTheme { AutomationDetailScreen(Fixtures.detailTimeShizukuOff, NoopDetailCallbacks) }
}

@DetailThemePreviews
@Composable
private fun DetailArmedStatsPreview() {
    ArgusTheme {
        AutomationDetailScreen(
            previewArmedDetail.copy(
                banner = EngineBanner.NONE,
                canRunNow = true,
                runNowBlockedReason = null,
                stats = RuleStatsUi(
                    last7Days = RuleStatsWindow(fires = 3, succeeded = 2, failed = 1),
                    last30Days = RuleStatsWindow(fires = 12, succeeded = 10, failed = 2),
                ),
            ),
            NoopDetailCallbacks,
        )
    }
}

@Preview(name = "Dettaglio · approvazione · 320 dp, carattere x2", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 320, heightDp = 1400, fontScale = 2f)
@Composable
private fun DetailPendingLargeFontPreview() {
    ArgusTheme { AutomationDetailScreen(previewReplyDetail, NoopDetailCallbacks) }
}

@Preview(name = "Dettaglio · regola armata · 320 dp, carattere x2", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES, widthDp = 320, heightDp = 1400, fontScale = 2f)
@Composable
private fun DetailArmedLargeFontPreview() {
    ArgusTheme { AutomationDetailScreen(previewArmedDetail.copy(canRunNow = true), NoopDetailCallbacks) }
}

@Preview(name = "Perché non è scattata? · orario, Shizuku spento · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Perché non è scattata? · orario, Shizuku spento · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun DiagnosisShizukuOffPreview() {
    ArgusTheme {
        Surface(color = ArgusTheme.surfaces.page) {
            Box(Modifier.padding(16.dp)) {
                DiagnosisSection(
                    diagnosis = Fixtures.detailTimeShizukuOff.diagnosis,
                    onDiagnose = {},
                    onOpenSystem = {},
                    initiallyOpen = true,
                )
            }
        }
    }
}

@Preview(name = "Perché non è scattata? · geofence, posizione sconosciuta · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Perché non è scattata? · geofence, posizione sconosciuta · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun DiagnosisGeofenceUnknownPreview() {
    ArgusTheme {
        Surface(color = ArgusTheme.surfaces.page) {
            Box(Modifier.padding(16.dp)) {
                DiagnosisSection(
                    diagnosis = Fixtures.detailGeofenceUnknown.diagnosis,
                    onDiagnose = {},
                    onOpenSystem = {},
                    initiallyOpen = true,
                )
            }
        }
    }
}

@Preview(name = "Statistiche · nessuno scatto · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Statistiche · nessuno scatto · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun RuleStatsEmptyPreview() {
    ArgusTheme {
        Surface(color = ArgusTheme.surfaces.page) {
            Box(Modifier.padding(16.dp)) {
                RuleStatsSection(RuleStatsUi(RuleStatsWindow(0, 0, 0), RuleStatsWindow(0, 0, 0)))
            }
        }
    }
}

@Preview(name = "Statistiche · Log conservato 7 giorni · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Statistiche · Log conservato 7 giorni · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun RuleStatsShortRetentionPreview() {
    ArgusTheme {
        Surface(color = ArgusTheme.surfaces.page) {
            Box(Modifier.padding(16.dp)) {
                RuleStatsSection(
                    RuleStatsUi(
                        last7Days = RuleStatsWindow(4, 4, 0),
                        last30Days = RuleStatsWindow(4, 4, 0),
                        longWindowDays = 7,
                    ),
                )
            }
        }
    }
}
