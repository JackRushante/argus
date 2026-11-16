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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Pending
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.components.ArgusCard
import dev.argus.ui.components.ArgusLargeTopBar
import dev.argus.ui.components.ArgusListRow
import dev.argus.ui.components.ArgusSearchField
import dev.argus.ui.components.ArgusSegmentedFilter
import dev.argus.ui.components.ArgusSwitch
import dev.argus.ui.components.EmptyState
import dev.argus.ui.components.EngineBannerBar
import dev.argus.ui.components.GenerativeTag
import dev.argus.ui.components.GroupPosition
import dev.argus.ui.components.HeroCounter
import dev.argus.ui.components.HeroTile
import dev.argus.ui.components.HeroTone
import dev.argus.ui.components.LabelFilterChip
import dev.argus.ui.components.LabelGroupCard
import dev.argus.ui.components.LabelTag
import dev.argus.ui.components.PauseBanner
import dev.argus.ui.components.StatusBadgeChip
import dev.argus.ui.components.StatusChip
import dev.argus.ui.components.SummaryHero
import dev.argus.ui.components.TriggerContainer
import dev.argus.ui.components.TriggerShape
import dev.argus.ui.components.TriggerTone
import dev.argus.ui.components.WarningMark
import dev.argus.ui.components.groupPositionOf
import dev.argus.ui.components.groupShape
import dev.argus.ui.model.AutomationListCallbacks
import dev.argus.ui.model.AutomationListState
import dev.argus.ui.model.AutomationRow
import dev.argus.ui.model.EngineBanner
import dev.argus.ui.model.LabelChipUi
import dev.argus.ui.model.ListSummary
import dev.argus.ui.model.NextFireSummary
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.StatusFilter
import dev.argus.ui.model.TodayErrorsSummary
import dev.argus.ui.model.iconFor
import dev.argus.ui.presentation.RuleSearch
import dev.argus.engine.model.RuleLabels
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Schermo Automazioni · lista (handoff §6.2, design §7.2; redesign A, tavola «Automazioni»).
// Dall'alto: titolo, banner salute (tappabile verso Sistema), riepilogo (contatori che filtrano, prossimo
// scatto, errori di oggi), filtro a segmenti e le regole in gruppi per stato.
// 0.7.2 (tavola «Automazioni con ricerca ed etichette»): sotto il riepilogo la ricerca (in memoria, solo della
// schermata), i chip delle etichette («Tutte · N», poi una per etichetta) e, con un'etichetta scelta, la card del
// gruppo con «Pausa gruppo» / «Riprendi gruppo». Le righe arrivano già filtrate per stato ed etichetta.
// Invarianti resi qui:
//  - ORDINAMENTO FISSO: PENDING_APPROVAL → NEEDS_REVIEW → ARMED → DISABLED, indipendente dall'ordine in cui
//    arrivano le righe (§7.2). Un gruppo di card per stato.
//  - Il contenitore del trigger dice lo stato col colore (blu, ambra, arancio, grigio) e il tipo con la forma.
//  - Interruttore inline SOLO per ARMED/DISABLED; il tocco sull'interruttore NON apre il dettaglio.
//  - §5.4 generativa ⇒ chip «generativa» ovunque appaia la riga.
//  - Un valore assente nel riepilogo si scrive «n/d», mai 0 (invariante 8).
// Stateless: solo (state, callbacks). `state.filter` = segmento attivo; le righe arrivano già filtrate
// dall'host (ViewModel), i conteggi del riepilogo no: sono sull'elenco completo.
// =============================================================================

/** Ordinamento fisso di stato (§7.2). `sortedBy` è stabile: preserva l'ordine host a parità di stato. */
private val StatusRank: Map<StatusBadge, Int> = mapOf(
    StatusBadge.PENDING_APPROVAL to 0,
    StatusBadge.NEEDS_REVIEW to 1,
    StatusBadge.ARMED to 2,
    StatusBadge.DISABLED to 3,
)

/** Segmenti del filtro in ordine di design (§7.2); la label localizzata si risolve nel Composable. */
private val FilterOrder: List<StatusFilter> = listOf(
    StatusFilter.ALL,
    StatusFilter.PENDING,
    StatusFilter.ARMED,
    StatusFilter.DISABLED,
    StatusFilter.NEEDS_REVIEW,
)

/** Che cosa mostrare quando la lista non ha righe (B49). */
internal enum class ListEmptyKind {
    /** Ci sono righe: nessun vuoto. */
    NONE,

    /** Nessuna regola e nessun filtro: invito a chiederne una in chat. */
    NO_RULES,

    /** Un filtro è attivo e nessuna riga lo soddisfa: non è «tutto sparito», basta togliere il filtro. */
    NO_MATCH_FOR_FILTER,

    /** 0.7.2: ci sono righe, ma nessuna contiene ciò che si cerca. */
    NO_MATCH_FOR_SEARCH,
}

/**
 * [rowCount]: righe mostrate; [beforeSearch]: righe prima della ricerca (già filtrate per stato ed etichetta).
 * Senza righe dopo la ricerca ma con righe prima, è la ricerca a non trovare nulla.
 */
internal fun listEmptyKind(
    rowCount: Int,
    filter: StatusFilter,
    labelSelected: Boolean = false,
    beforeSearch: Int = rowCount,
): ListEmptyKind = when {
    rowCount > 0 -> ListEmptyKind.NONE
    beforeSearch > 0 -> ListEmptyKind.NO_MATCH_FOR_SEARCH
    filter != StatusFilter.ALL || labelSelected -> ListEmptyKind.NO_MATCH_FOR_FILTER
    else -> ListEmptyKind.NO_RULES
}

/** 0.7.2: le parti del sottotitolo del gruppo scelto («3 attive · 1 da approvare»). */
internal sealed interface GroupCountPart {
    data class Active(val count: Int) : GroupCountPart
    data class Waiting(val count: Int) : GroupCountPart
    data object NoneActive : GroupCountPart
}

internal fun groupCountParts(active: Int, waiting: Int): List<GroupCountPart> = buildList {
    if (active > 0) add(GroupCountPart.Active(active))
    if (waiting > 0) add(GroupCountPart.Waiting(waiting))
    if (isEmpty()) add(GroupCountPart.NoneActive)
}

/** 0.7.2: il chip dell'etichetta scelta, se c'è ancora (un'etichetta senza regole sparisce). */
internal fun selectedLabelChip(state: AutomationListState): LabelChipUi? =
    state.selectedLabel?.let { selected ->
        state.labels.firstOrNull { RuleLabels.key(it.label) == RuleLabels.key(selected) }
    }

/** 0.7.2: la riga mostra «trigger → azioni», come la tavola. */
internal fun rowSubtitle(row: AutomationRow): String =
    row.actionSummary?.takeIf { it.isNotBlank() }?.let { "${row.triggerSummary} → $it" } ?: row.triggerSummary

/**
 * Una riga sullo schermo: la regola, la sua posizione nel gruppo del suo stato (angoli della card) e lo
 * spazio sopra (0 per la prima, 12 tra un gruppo e l'altro, 3 dentro il gruppo).
 */
internal data class ListEntry(val row: AutomationRow, val position: GroupPosition, val topGap: Dp)

/**
 * Le righe in ordine fisso di stato, divise in gruppi (uno per stato presente): `groupBy` conserva l'ordine
 * di prima comparsa, che dopo `sortedBy` e' quello di [StatusRank].
 */
internal fun listEntriesOf(rows: List<AutomationRow>): List<ListEntry> {
    val groups = rows.sortedBy { StatusRank[it.status] ?: Int.MAX_VALUE }.groupBy { it.status }.values
    var isFirstGroup = true
    return buildList {
        groups.forEach { group ->
            group.forEachIndexed { index, row ->
                val gap = when {
                    isFirstGroup && index == 0 -> 0.dp
                    index == 0 -> ArgusSpacing.Medium
                    else -> ArgusSpacing.GroupGap
                }
                add(ListEntry(row, groupPositionOf(index, group.size), gap))
            }
            isFirstGroup = false
        }
    }
}

/**
 * Forma del contenitore del trigger dal tipo di trigger (tavola «Contenitori trigger espressivi»): cerchio per
 * l'orario, squircle per la notifica, goccia per il geofence, rombo per telefono/SMS, arco per la connettività,
 * biscotto per sensori e avvii immediati (schermo, batteria, audio, modalita', media, manuale, app).
 */
internal fun listTriggerShape(triggerIconKey: String): TriggerShape = when (triggerIconKey) {
    "time" -> TriggerShape.Circle
    "notification" -> TriggerShape.Squircle
    "geofence" -> TriggerShape.Drop
    "phone" -> TriggerShape.Rhombus
    "connectivity" -> TriggerShape.Arch
    else -> TriggerShape.Cookie
}

/** Il colore del contenitore dice lo stato: blu normale, ambra in attesa di te, arancio da rivedere, grigio disattivata. */
internal fun listTriggerTone(status: StatusBadge): TriggerTone = when (status) {
    StatusBadge.ARMED -> TriggerTone.Normal
    StatusBadge.PENDING_APPROVAL -> TriggerTone.Pending
    StatusBadge.NEEDS_REVIEW -> TriggerTone.NeedsReview
    StatusBadge.DISABLED -> TriggerTone.Disabled
}

/** Il filtro che un contatore del riepilogo applica. Un tocco imposta sempre il filtro: per tornare a tutte c'e' il segmento «Tutte». */
internal fun counterFilter(status: StatusBadge): StatusFilter = when (status) {
    StatusBadge.ARMED -> StatusFilter.ARMED
    StatusBadge.PENDING_APPROVAL -> StatusFilter.PENDING
    StatusBadge.NEEDS_REVIEW -> StatusFilter.NEEDS_REVIEW
    StatusBadge.DISABLED -> StatusFilter.DISABLED
}

/** Come il riquadro «Oggi» dice gli errori: ancora da leggere (n/d), nessuno, uno o piu'. */
internal enum class TodayKind { UNKNOWN, NONE, ONE, MANY }

internal fun todayKindOf(today: TodayErrorsSummary?): TodayKind = when {
    today == null -> TodayKind.UNKNOWN
    today.count <= 0 -> TodayKind.NONE
    today.count == 1 -> TodayKind.ONE
    else -> TodayKind.MANY
}

@Composable
fun AutomationListScreen(
    state: AutomationListState,
    callbacks: AutomationListCallbacks,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ArgusLargeTopBar(stringResource(R.string.list_title))

            // Banner salute persistente e tappabile → Sistema (nav host-owned via onBannerTap).
            EngineBannerBar(
                state.banner,
                modifier = Modifier.padding(horizontal = ArgusSpacing.Large, vertical = ArgusSpacing.Unit),
                onClick = callbacks::onBannerTap,
            )

            // F21: «Pausa automazioni» attiva. Il banner resta finché non si riprende.
            if (state.paused) {
                PauseBanner(
                    onResume = callbacks::onResumeAutomations,
                    modifier = Modifier.padding(horizontal = ArgusSpacing.Large, vertical = ArgusSpacing.Unit),
                )
            }

            if (state.loading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            } else {
                AutomationListBody(state, callbacks)
            }
        }
    }
}

@Composable
private fun AutomationListBody(state: AutomationListState, callbacks: AutomationListCallbacks) {
    // 0.7.2: la ricerca è solo della schermata (in memoria, sulle righe già filtrate per stato ed etichetta).
    var query by rememberSaveable { mutableStateOf("") }
    val shownRows = RuleSearch.filter(state.rows, query)
    val entries = listEntriesOf(shownRows)
    val selectedChip = selectedLabelChip(state)
    val pausedLabelKeys = state.labels.filter { it.paused }.mapTo(HashSet()) { RuleLabels.key(it.label) }
    // Senza regole non c'è niente da cercare né da etichettare: resta l'invito ad andare in chat.
    val showFinder = state.totalCount > 0 || state.rows.isNotEmpty()
    val filterLabels = mapOf(
        StatusFilter.ALL to stringResource(R.string.list_filter_all),
        StatusFilter.PENDING to stringResource(R.string.list_filter_pending),
        StatusFilter.ARMED to stringResource(R.string.list_filter_armed),
        StatusFilter.DISABLED to stringResource(R.string.list_filter_disabled),
        StatusFilter.NEEDS_REVIEW to stringResource(R.string.list_filter_needs_review),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = ArgusSpacing.Medium, bottom = ArgusSpacing.Large),
    ) {
        // Riepilogo: conteggi sull'elenco completo; compare solo se c'e' qualcosa da riassumere.
        state.summary?.let { summary ->
            item(key = "summary") {
                ListSummaryHero(
                    summary = summary,
                    callbacks = callbacks,
                    modifier = Modifier.padding(horizontal = ArgusSpacing.Large),
                )
            }
        }
        // 0.7.2 (tavola «Automazioni con ricerca ed etichette»): ricerca, etichette e gruppo scelto.
        if (showFinder) {
            item(key = "search") {
                ArgusSearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = stringResource(R.string.lbl_search_placeholder),
                    clearDescription = stringResource(R.string.lbl_search_clear),
                    modifier = Modifier.padding(
                        start = ArgusSpacing.Large,
                        end = ArgusSpacing.Large,
                        top = if (state.summary != null) ArgusSpacing.Large else 0.dp,
                    ),
                )
            }
            item(key = "labels") {
                LabelFilterRow(
                    state = state,
                    onSelect = callbacks::onSelectLabel,
                    modifier = Modifier.padding(start = ArgusSpacing.Large, end = ArgusSpacing.Large, top = ArgusSpacing.Large),
                )
            }
            selectedChip?.let { chip ->
                item(key = "label_group") {
                    SelectedLabelGroupCard(
                        chip = chip,
                        onSetPaused = { paused -> callbacks.onSetLabelPaused(chip.label, paused) },
                        modifier = Modifier.padding(start = ArgusSpacing.Large, end = ArgusSpacing.Large, top = ArgusSpacing.Large),
                    )
                }
            }
        }
        item(key = "filter") {
            ArgusSegmentedFilter(
                options = FilterOrder,
                selected = state.filter,
                onSelect = callbacks::onFilter,
                label = { filterLabels.getValue(it) },
                groupDescription = stringResource(R.string.rda_lc_filter_group),
                modifier = Modifier.padding(
                    top = if (state.summary != null || showFinder) ArgusSpacing.Large else 0.dp,
                    bottom = ArgusSpacing.Large,
                ),
            )
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                when (
                    listEmptyKind(
                        rowCount = entries.size,
                        filter = state.filter,
                        labelSelected = selectedChip != null,
                        beforeSearch = state.rows.size,
                    )
                ) {
                    // 0.7.2: la ricerca non trova nulla: lo dice e offre di cancellarla.
                    ListEmptyKind.NO_MATCH_FOR_SEARCH -> EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = stringResource(R.string.lbl_search_empty_title, query.trim()),
                        body = stringResource(R.string.lbl_search_empty_body),
                        ctaLabel = stringResource(R.string.lbl_search_clear),
                        onCta = { query = "" },
                    )
                    // Filtro attivo senza risultati (B49): messaggio distinto + azione per toglierlo.
                    ListEmptyKind.NO_MATCH_FOR_FILTER -> EmptyState(
                        icon = Icons.Rounded.FilterAltOff,
                        title = stringResource(R.string.ui40_list_filter_empty_title),
                        body = stringResource(R.string.ui40_list_filter_empty_body),
                        ctaLabel = stringResource(R.string.ui40_list_filter_clear),
                        onCta = {
                            callbacks.onFilter(StatusFilter.ALL)
                            callbacks.onSelectLabel(null)
                        },
                    )
                    // Empty (§6.2): la CTA cambia tab → Chat; la navigazione è host-owned
                    // (NavHost, Task 12) via onEmptyCta, come le altre affordance di nav.
                    else -> EmptyState(
                        icon = Icons.Rounded.Bolt,
                        title = stringResource(R.string.list_empty_title),
                        body = stringResource(R.string.list_empty_body),
                        ctaLabel = stringResource(R.string.list_empty_cta),
                        onCta = callbacks::onEmptyCta,
                    )
                }
            }
        } else {
            items(entries, key = { it.row.id }) { entry ->
                AutomationRuleCard(
                    entry = entry,
                    onOpen = { callbacks.onOpen(entry.row.id) },
                    // D5: il chip «modifica in attesa» apre la BOZZA, non la regola.
                    onOpenPendingEdit = { draftId -> callbacks.onOpen(draftId) },
                    onToggleEnabled = { enabled -> callbacks.onToggleEnabled(entry.row.id, enabled) },
                    modifier = Modifier.padding(start = ArgusSpacing.Large, end = ArgusSpacing.Large, top = entry.topGap),
                    pausedLabelKeys = pausedLabelKeys,
                )
            }
            // 0.7.2: dove si scelgono le etichette e che cosa (non) cambiano.
            item(key = "labels_note") {
                Text(
                    stringResource(R.string.lbl_list_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = ArgusSpacing.Large, end = ArgusSpacing.Large, top = ArgusSpacing.Large),
                )
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Etichette (0.7.2)
// -----------------------------------------------------------------------------

/** «Tutte · N», poi un chip per etichetta con il suo conteggio. Scelta esclusiva: toccare quella scelta torna a «Tutte». */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LabelFilterRow(state: AutomationListState, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    val selected = selectedLabelChip(state)
    val groupDescription = stringResource(R.string.lbl_filter_group)
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = groupDescription }
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
        verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
    ) {
        LabelFilterChip(
            text = stringResource(R.string.lbl_chip_all, state.totalCount),
            selected = selected == null,
            onClick = { onSelect(null) },
            contentDescription = stringResource(R.string.lbl_chip_all_cd, state.totalCount),
        )
        state.labels.forEach { chip ->
            val isSelected = selected?.label == chip.label
            LabelFilterChip(
                text = stringResource(R.string.lbl_chip_count, chip.label, chip.count),
                selected = isSelected,
                onClick = { onSelect(if (isSelected) null else chip.label) },
                contentDescription = if (chip.paused) {
                    stringResource(R.string.lbl_chip_paused_cd, chip.label, chip.count)
                } else {
                    stringResource(R.string.lbl_chip_cd, chip.label, chip.count)
                },
                paused = chip.paused,
            )
        }
    }
}

/** Intestazione del gruppo scelto: quante regole sono attive o aspettano, e la pausa del gruppo. */
@Composable
private fun SelectedLabelGroupCard(chip: LabelChipUi, onSetPaused: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val counts = groupCountParts(chip.activeCount, chip.waitingCount).map { part ->
        when (part) {
            is GroupCountPart.Active -> if (part.count == 1) {
                stringResource(R.string.lbl_group_active_one)
            } else {
                stringResource(R.string.lbl_group_active_many, part.count)
            }
            is GroupCountPart.Waiting -> stringResource(R.string.lbl_group_waiting, part.count)
            GroupCountPart.NoneActive -> stringResource(R.string.lbl_group_none_active)
        }
    }.joinToString(" · ")
    LabelGroupCard(
        title = stringResource(R.string.lbl_group_title, chip.label),
        subtitle = if (chip.paused) stringResource(R.string.lbl_group_paused_summary, counts) else counts,
        paused = chip.paused,
        pauseLabel = stringResource(R.string.lbl_group_pause),
        resumeLabel = stringResource(R.string.lbl_group_resume),
        onTogglePause = { onSetPaused(!chip.paused) },
        pausedDescription = if (chip.paused) stringResource(R.string.lbl_group_paused_state) else null,
        modifier = modifier,
    )
}

// -----------------------------------------------------------------------------
// Riepilogo in testa (nota n1)
// -----------------------------------------------------------------------------

/**
 * Riepilogo: tre contatori che filtrano la lista (armate, in attesa, da rivedere) e due riquadri — «Prossimo
 * scatto» apre la regola, «Oggi» apre il Log. I numeri vengono da [ListSummary], calcolato sull'elenco completo.
 */
@Composable
private fun ListSummaryHero(summary: ListSummary, callbacks: AutomationListCallbacks, modifier: Modifier = Modifier) {
    val counters = listOf(
        HeroCounter(
            value = summary.armedCount.toString(),
            label = stringResource(R.string.rda_lc_hero_armed),
            tone = HeroTone.Primary,
            contentDescription = stringResource(R.string.rda_lc_hero_armed_cd, summary.armedCount),
            onClick = { callbacks.onFilter(counterFilter(StatusBadge.ARMED)) },
        ),
        HeroCounter(
            value = summary.pendingCount.toString(),
            label = stringResource(R.string.rda_lc_hero_pending),
            tone = HeroTone.Attention,
            contentDescription = stringResource(R.string.rda_lc_hero_pending_cd, summary.pendingCount),
            onClick = { callbacks.onFilter(counterFilter(StatusBadge.PENDING_APPROVAL)) },
        ),
        HeroCounter(
            value = summary.needsReviewCount.toString(),
            label = stringResource(R.string.rda_lc_hero_review),
            tone = HeroTone.NeedsReview,
            contentDescription = stringResource(R.string.rda_lc_hero_review_cd, summary.needsReviewCount),
            onClick = { callbacks.onFilter(counterFilter(StatusBadge.NEEDS_REVIEW)) },
        ),
    )
    SummaryHero(
        counters = counters,
        tiles = listOf(nextFireTile(summary.nextFire, callbacks), todayTile(summary.todayErrors, callbacks)),
        modifier = modifier,
    )
}

@Composable
private fun nextFireTile(next: NextFireSummary?, callbacks: AutomationListCallbacks): HeroTile {
    val label = stringResource(R.string.rda_lc_tile_next)
    return if (next != null) {
        HeroTile(
            icon = Icons.Rounded.Schedule,
            label = label,
            value = next.label,
            detail = next.ruleName,
            valueTone = HeroTone.Neutral,
            contentDescription = stringResource(R.string.rda_lc_tile_next_cd, next.label, next.ruleName),
            onClick = { callbacks.onOpen(next.ruleId) },
        )
    } else {
        // Nessuna regola a orario armata: non e' un guasto e non c'e' niente da aprire.
        HeroTile(
            icon = Icons.Rounded.Schedule,
            label = label,
            value = stringResource(R.string.rda_lc_tile_next_none),
            detail = null,
            valueTone = HeroTone.Neutral,
            contentDescription = stringResource(R.string.rda_lc_tile_next_none_cd),
            onClick = null,
        )
    }
}

@Composable
private fun todayTile(today: TodayErrorsSummary?, callbacks: AutomationListCallbacks): HeroTile {
    val label = stringResource(R.string.rda_lc_tile_today)
    val kind = todayKindOf(today)
    val value = when (kind) {
        TodayKind.UNKNOWN -> stringResource(R.string.rda_lc_na)
        TodayKind.NONE -> stringResource(R.string.rda_lc_today_none)
        TodayKind.ONE -> stringResource(R.string.rda_lc_today_one)
        TodayKind.MANY -> stringResource(R.string.rda_lc_today_many, today?.count ?: 0)
    }
    // Ultimo errore: «Backup foto notturno · 12:05». Il nome e l'ora sono quelli del Log, mai il testo dei messaggi.
    val detail = today?.takeIf { it.count > 0 }?.let { errors ->
        listOfNotNull(errors.lastRuleName, errors.lastTimeLabel).joinToString(" · ").ifBlank { null }
    }
    val description = if (detail != null) {
        stringResource(R.string.rda_lc_tile_today_cd_detail, value, detail)
    } else {
        stringResource(R.string.rda_lc_tile_today_cd, value)
    }
    return HeroTile(
        icon = when (kind) {
            TodayKind.UNKNOWN -> Icons.Rounded.Info
            TodayKind.NONE -> Icons.Rounded.CheckCircle
            TodayKind.ONE, TodayKind.MANY -> Icons.Rounded.ErrorOutline
        },
        label = label,
        value = value,
        detail = detail,
        valueTone = if (kind == TodayKind.ONE || kind == TodayKind.MANY) HeroTone.Error else HeroTone.Neutral,
        contentDescription = description,
        onClick = callbacks::onOpenLog,
    )
}

// -----------------------------------------------------------------------------
// Riga regola
// -----------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutomationRuleCard(
    entry: ListEntry,
    onOpen: () -> Unit,
    onOpenPendingEdit: (String) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    /** 0.7.2: chiavi delle etichette in pausa, per segnare quale etichetta sospende la regola. */
    pausedLabelKeys: Set<String> = emptySet(),
) {
    val row = entry.row
    val showToggle = row.status == StatusBadge.ARMED || row.status == StatusBadge.DISABLED
    ArgusCard(
        modifier = modifier.fillMaxWidth(),
        shape = groupShape(entry.position),
        contentPadding = PaddingValues(0.dp),
    ) {
        ArgusListRow(
            title = row.name,
            subtitle = rowSubtitle(row),
            leading = {
                TriggerContainer(
                    icon = iconFor(row.triggerIconKey),
                    shape = listTriggerShape(row.triggerIconKey),
                    tone = listTriggerTone(row.status),
                )
            },
            // Avvisi della regola (conflitti/validator, §7.2). B50: non solo colore. Il triangolo ha una forma
            // propria e una descrizione per TalkBack («Ha avvisi»).
            titleSuffix = if (row.hasWarnings) {
                { WarningMark(stringResource(R.string.ui40_list_has_warnings)) }
            } else {
                null
            },
            meta = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = ArgusSpacing.Small),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
                ) {
                    FlowRow(
                        modifier = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        StatusBadgeChip(row.status)
                        row.pendingEditDraftId?.let { draftId ->
                            PendingEditChip(onClick = { onOpenPendingEdit(draftId) })
                        }
                        // 0.7.2: sospesa da un suo gruppo: non scatta, e lo dice con icona e testo.
                        if (row.groupPaused) {
                            val pausedDescription = stringResource(R.string.lbl_row_paused_cd)
                            StatusChip(
                                role = ArgusTheme.semantic.pending,
                                icon = Icons.Rounded.PauseCircle,
                                text = stringResource(R.string.lbl_row_paused),
                                modifier = Modifier.semantics { contentDescription = pausedDescription },
                            )
                        }
                        if (row.isGenerative) GenerativeTag()
                        val labelPaused = stringResource(R.string.lbl_label_paused_cd)
                        row.labels.forEach { label ->
                            LabelTag(label, paused = RuleLabels.key(label) in pausedLabelKeys, pausedDescription = labelPaused)
                        }
                    }
                    FireTimes(row)
                }
            },
            trailing = if (showToggle) {
                {
                    // Interruttore inline ARMED⇄DISABLED: ha il suo tocco, non apre il dettaglio.
                    val description = if (row.enabled) {
                        stringResource(R.string.list_toggle_off, row.name)
                    } else {
                        stringResource(R.string.list_toggle_on, row.name)
                    }
                    ArgusSwitch(
                        checked = row.enabled,
                        onCheckedChange = onToggleEnabled,
                        contentDescription = description,
                    )
                }
            } else {
                {
                    Icon(
                        Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        // Nella tavola la freccia sta 8 dp piu' in basso dell'interruttore.
                        modifier = Modifier.padding(top = 16.dp).size(24.dp),
                    )
                }
            },
            onClick = onOpen,
            onClickLabel = stringResource(R.string.rda_lc_row_open),
            trailingAtTop = true,
        )
    }
}

/** Prossimo e ultimo scatto, a destra dei chip. Ognuno ha icona e descrizione per TalkBack: l'icona porta il significato. */
@Composable
private fun FireTimes(row: AutomationRow) {
    if (row.nextFireLabel == null && row.lastFiredLabel == null) return
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        row.nextFireLabel?.let { label ->
            val description = stringResource(R.string.rda_lc_next_fire_cd, label)
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit),
            ) {
                Icon(Icons.Rounded.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                Text(
                    label,
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        row.lastFiredLabel?.let { label ->
            val description = stringResource(R.string.rda_lc_last_fire_cd, label)
            Row(
                modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit),
            ) {
                Icon(Icons.Rounded.History, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(
                    label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.W400,
                )
            }
        }
    }
}

/**
 * D5: «modifica in attesa». La regola armata continua a scattare mentre una sua modifica aspetta
 * l'approvazione; il tocco apre la bozza. Stesso chip dei badge di stato (ruolo `pending`), con area tattile 48 dp.
 */
@Composable
private fun PendingEditChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .clip(RoundedCornerShape(percent = 50))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        StatusChip(
            role = ArgusTheme.semantic.pending,
            icon = Icons.Rounded.Pending,
            text = stringResource(R.string.list_pending_edit_badge),
        )
    }
}

// =============================================================================
// Anteprime: ogni stato, tema chiaro e scuro (tavole «Automazioni · scuro / chiaro»).
// =============================================================================

@Preview(name = "Chiaro", group = "Tema", showBackground = true, widthDp = 390, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Preview(name = "Scuro", group = "Tema", showBackground = true, widthDp = 390, heightDp = 1400, uiMode = Configuration.UI_MODE_NIGHT_YES)
private annotation class ListScreenPreviews

private val previewRows = listOf(
    // Volutamente in ordine "sbagliato": lo screen deve riordinarle per stato.
    AutomationRow(
        id = "a1", name = "Casa · spegni Wi-Fi uscendo", triggerIconKey = "geofence",
        triggerSummary = "Geofence \"Casa\" · in uscita", status = StatusBadge.ARMED,
        enabled = true, isGenerative = false, hasWarnings = true,
        lastFiredLabel = "ieri 18:32", nextFireLabel = null,
    ),
    AutomationRow(
        id = "a2", name = "DND dopo le 23:00", triggerIconKey = "time",
        triggerSummary = "Ogni giorno 23:00", status = StatusBadge.ARMED,
        enabled = true, isGenerative = false, hasWarnings = false,
        lastFiredLabel = "oggi 23:00", nextFireLabel = "stasera 23:00",
    ),
    AutomationRow(
        id = "a5", name = "Backup foto notturno", triggerIconKey = "time",
        triggerSummary = "Ogni giorno 03:00 · shell", status = StatusBadge.ARMED,
        enabled = true, isGenerative = false, hasWarnings = false,
        lastFiredLabel = "oggi 03:00", nextFireLabel = "stanotte 03:00",
    ),
    AutomationRow(
        id = "a3", name = "Rispondi a Moglie su WhatsApp", triggerIconKey = "notification",
        triggerSummary = "Notifica WhatsApp · Moglie", status = StatusBadge.PENDING_APPROVAL,
        enabled = false, isGenerative = true, hasWarnings = true,
        lastFiredLabel = null, nextFireLabel = null,
    ),
    AutomationRow(
        id = "a7", name = "Disinstalla giochi dopo mezzanotte", triggerIconKey = "time",
        triggerSummary = "Ogni giorno 00:00 · generativa", status = StatusBadge.PENDING_APPROVAL,
        enabled = false, isGenerative = true, hasWarnings = true,
        lastFiredLabel = null, nextFireLabel = null,
    ),
    AutomationRow(
        id = "a6", name = "Promemoria farmaci", triggerIconKey = "time",
        triggerSummary = "Schema non compatibile", status = StatusBadge.NEEDS_REVIEW,
        enabled = false, isGenerative = false, hasWarnings = true,
        lastFiredLabel = null, nextFireLabel = null,
    ),
    AutomationRow(
        id = "a4", name = "Ufficio · silenzia entrando", triggerIconKey = "geofence",
        triggerSummary = "Geofence \"Ufficio\" · in entrata", status = StatusBadge.DISABLED,
        enabled = false, isGenerative = false, hasWarnings = false,
        lastFiredLabel = "mar 09:04", nextFireLabel = null,
    ),
)

private val previewSummary = ListSummary(
    armedCount = 3,
    pendingCount = 2,
    needsReviewCount = 1,
    nextFire = NextFireSummary("a2", "DND dopo le 23:00", "stasera 23:00", 0L),
    todayErrors = TodayErrorsSummary(count = 1, lastRuleName = "Backup foto notturno", lastTimeLabel = "12:05"),
)

private object NoopListCallbacks : AutomationListCallbacks {
    override fun onOpen(id: String) {}
    override fun onToggleEnabled(id: String, enabled: Boolean) {}
    override fun onFilter(f: StatusFilter) {}
}

private fun previewState(
    rows: List<AutomationRow> = previewRows,
    filter: StatusFilter = StatusFilter.ALL,
    banner: EngineBanner = EngineBanner.NONE,
    summary: ListSummary? = previewSummary,
    loading: Boolean = false,
    paused: Boolean = false,
) = AutomationListState(
    rows = rows, filter = filter, banner = banner, loading = loading, paused = paused, summary = summary, totalCount = rows.size,
)

@ListScreenPreviews
@Composable
private fun ListMixedPreview() {
    ArgusTheme { AutomationListScreen(previewState(banner = EngineBanner.SHIZUKU_DOWN), NoopListCallbacks) }
}

/** 0.7.2 (tavola «Automazioni con ricerca ed etichette»): etichetta «Casa» scelta, gruppo attivo. */
@ListScreenPreviews
@Composable
private fun ListLabelSelectedPreview() {
    val rows = listOf(
        AutomationRow(
            id = "h1", name = "Arrivo a casa", triggerIconKey = "connectivity", triggerSummary = "Wi-Fi «Casa» collegato",
            status = StatusBadge.ARMED, enabled = true, isGenerative = false, hasWarnings = false,
            lastFiredLabel = null, nextFireLabel = null, actionSummary = "Modalità Casa, Volume", labels = listOf("Casa"),
        ),
        AutomationRow(
            id = "h2", name = "Buonanotte", triggerIconKey = "time", triggerSummary = "Ogni giorno alle 23:00",
            status = StatusBadge.ARMED, enabled = true, isGenerative = false, hasWarnings = false,
            lastFiredLabel = null, nextFireLabel = "stasera 23:00", actionSummary = "Non disturbare", labels = listOf("Casa"),
        ),
        AutomationRow(
            id = "h4", name = "Esco di casa", triggerIconKey = "connectivity", triggerSummary = "Wi-Fi «Casa» scollegato",
            status = StatusBadge.PENDING_APPROVAL, enabled = false, isGenerative = false, hasWarnings = false,
            lastFiredLabel = null, nextFireLabel = null, actionSummary = "Modalità Fuori", labels = listOf("Casa", "Auto"),
        ),
    )
    ArgusTheme {
        AutomationListScreen(
            AutomationListState(
                rows = rows, filter = StatusFilter.ALL, banner = EngineBanner.NONE, loading = false,
                labels = listOf(
                    LabelChipUi("Casa", count = 3, activeCount = 2, waitingCount = 1, paused = false),
                    LabelChipUi("Lavoro", count = 3, activeCount = 3, waitingCount = 0, paused = false),
                    LabelChipUi("Auto", count = 2, activeCount = 1, waitingCount = 1, paused = false),
                ),
                totalCount = 9,
                selectedLabel = "Casa",
            ),
            NoopListCallbacks,
        )
    }
}

/** 0.7.2: gruppo in pausa: «Riprendi gruppo» e le righe segnate «In pausa». */
@ListScreenPreviews
@Composable
private fun ListLabelPausedPreview() {
    val rows = listOf(
        previewRows[0].copy(labels = listOf("Auto"), groupPaused = true),
        previewRows[3].copy(labels = listOf("Auto")),
    )
    ArgusTheme {
        AutomationListScreen(
            AutomationListState(
                rows = rows, filter = StatusFilter.ALL, banner = EngineBanner.NONE, loading = false,
                labels = listOf(LabelChipUi("Auto", count = 2, activeCount = 1, waitingCount = 1, paused = true)),
                totalCount = 7,
                selectedLabel = "Auto",
                summary = previewSummary,
            ),
            NoopListCallbacks,
        )
    }
}

@ListScreenPreviews
@Composable
private fun ListFilteredPreview() {
    // Filtro «In approvazione»: le righe sono solo quelle, i contatori restano sull'elenco completo.
    ArgusTheme {
        AutomationListScreen(
            previewState(rows = previewRows.filter { it.status == StatusBadge.PENDING_APPROVAL }, filter = StatusFilter.PENDING),
            NoopListCallbacks,
        )
    }
}

@ListScreenPreviews
@Composable
private fun ListPendingEditPausedPreview() {
    ArgusTheme {
        AutomationListScreen(
            previewState(
                rows = listOf(previewRows[1].copy(pendingEditDraftId = "d9"), previewRows[0], previewRows[6]),
                paused = true,
                summary = previewSummary.copy(todayErrors = TodayErrorsSummary(count = 0, lastRuleName = null, lastTimeLabel = null)),
            ),
            NoopListCallbacks,
        )
    }
}

@ListScreenPreviews
@Composable
private fun ListNoErrorsNoNextPreview() {
    // Niente da programmare e nessun errore: «Nessuno» e «Nessun errore», non zeri.
    ArgusTheme {
        AutomationListScreen(
            previewState(
                rows = listOf(previewRows[0], previewRows[6]),
                summary = ListSummary(
                    armedCount = 1, pendingCount = 0, needsReviewCount = 0, nextFire = null,
                    todayErrors = TodayErrorsSummary(count = 0, lastRuleName = null, lastTimeLabel = null),
                ),
            ),
            NoopListCallbacks,
        )
    }
}

@ListScreenPreviews
@Composable
private fun ListManyErrorsUnknownPreview() {
    ArgusTheme {
        AutomationListScreen(
            previewState(
                summary = previewSummary.copy(
                    todayErrors = TodayErrorsSummary(count = 4, lastRuleName = "Backup foto notturno", lastTimeLabel = "12:05"),
                ),
                banner = EngineBanner.BRAIN_UNREACHABLE,
            ),
            NoopListCallbacks,
        )
    }
}

@ListScreenPreviews
@Composable
private fun ListLogUnreadPreview() {
    // Log non letto: «n/d», mai 0.
    ArgusTheme { AutomationListScreen(previewState(summary = previewSummary.copy(todayErrors = null)), NoopListCallbacks) }
}

@ListScreenPreviews
@Composable
private fun ListFilterEmptyPreview() {
    ArgusTheme {
        AutomationListScreen(
            previewState(rows = emptyList(), filter = StatusFilter.NEEDS_REVIEW),
            NoopListCallbacks,
        )
    }
}

@ListScreenPreviews
@Composable
private fun ListEmptyPreview() {
    ArgusTheme { AutomationListScreen(previewState(rows = emptyList(), summary = null), NoopListCallbacks) }
}

@ListScreenPreviews
@Composable
private fun ListLoadingPreview() {
    ArgusTheme { AutomationListScreen(previewState(rows = emptyList(), summary = null, loading = true), NoopListCallbacks) }
}
