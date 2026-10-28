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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FilterAltOff
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pending
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
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
import dev.argus.ui.components.ArgusSegmentedFilter
import dev.argus.ui.components.ArgusSwitch
import dev.argus.ui.components.EmptyState
import dev.argus.ui.components.EngineBannerBar
import dev.argus.ui.components.GenerativeTag
import dev.argus.ui.components.GroupPosition
import dev.argus.ui.components.HeroCounter
import dev.argus.ui.components.HeroTile
import dev.argus.ui.components.HeroTone
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
import dev.argus.ui.model.ListSummary
import dev.argus.ui.model.NextFireSummary
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.StatusFilter
import dev.argus.ui.model.TodayErrorsSummary
import dev.argus.ui.model.iconFor
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Schermo Automazioni · lista (handoff §6.2, design §7.2; redesign A, tavola «Automazioni»).
// Dall'alto: titolo, banner salute (tappabile verso Sistema), riepilogo (contatori che filtrano, prossimo
// scatto, errori di oggi), filtro a segmenti e le regole in gruppi per stato.
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
}

internal fun listEmptyKind(rowCount: Int, filter: StatusFilter): ListEmptyKind = when {
    rowCount > 0 -> ListEmptyKind.NONE
    filter == StatusFilter.ALL -> ListEmptyKind.NO_RULES
    else -> ListEmptyKind.NO_MATCH_FOR_FILTER
}

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
    val entries = listEntriesOf(state.rows)
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
        item(key = "filter") {
            ArgusSegmentedFilter(
                options = FilterOrder,
                selected = state.filter,
                onSelect = callbacks::onFilter,
                label = { filterLabels.getValue(it) },
                groupDescription = stringResource(R.string.rda_lc_filter_group),
                modifier = Modifier.padding(top = if (state.summary != null) ArgusSpacing.Large else 0.dp, bottom = ArgusSpacing.Large),
            )
        }
        if (entries.isEmpty()) {
            item(key = "empty") {
                when (listEmptyKind(entries.size, state.filter)) {
                    // Filtro attivo senza risultati (B49): messaggio distinto + azione per toglierlo.
                    ListEmptyKind.NO_MATCH_FOR_FILTER -> EmptyState(
                        icon = Icons.Rounded.FilterAltOff,
                        title = stringResource(R.string.ui40_list_filter_empty_title),
                        body = stringResource(R.string.ui40_list_filter_empty_body),
                        ctaLabel = stringResource(R.string.ui40_list_filter_clear),
                        onCta = { callbacks.onFilter(StatusFilter.ALL) },
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
                )
            }
        }
    }
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
            subtitle = row.triggerSummary,
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
                        if (row.isGenerative) GenerativeTag()
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
                        modifier = Modifier.size(24.dp),
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
) = AutomationListState(rows = rows, filter = filter, banner = banner, loading = loading, paused = paused, summary = summary)

@ListScreenPreviews
@Composable
private fun ListMixedPreview() {
    ArgusTheme { AutomationListScreen(previewState(banner = EngineBanner.SHIZUKU_DOWN), NoopListCallbacks) }
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
