package dev.argus.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.ui.components.EmptyState
import dev.argus.ui.components.EngineBannerBar
import dev.argus.ui.components.GenerativeTag
import dev.argus.ui.components.StatusBadgeChip
import dev.argus.ui.model.AutomationListCallbacks
import dev.argus.ui.model.AutomationListState
import dev.argus.ui.model.AutomationRow
import dev.argus.ui.model.EngineBanner
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.model.StatusFilter
import dev.argus.ui.model.iconFor
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.LocalArgusSemantic

// =============================================================================
// Schermo Automazioni · lista (handoff §6.2, design §7.2).
// Invarianti resi qui:
//  - ORDINAMENTO FISSO: PENDING_APPROVAL → NEEDS_REVIEW → ARMED → DISABLED,
//    indipendente dall'ordine in cui arrivano le righe (§7.2).
//  - PENDING con tinta ambra tenue, NEEDS_REVIEW con tinta rossa tenue: derivate
//    per alpha dai token semantici (nessun colore hardcoded, §5.3).
//  - Toggle inline SOLO per ARMED/DISABLED; il tap sul toggle NON apre il
//    dettaglio (lo Switch consuma il pointer, §7.2 stopPropagation).
//  - §5.4 generativa ⇒ GenerativeTag ovunque appaia la riga.
// Stateless: solo (state, callbacks). `state.filter` = chip attivo; le righe
// arrivano già filtrate dall'host (ViewModel) — qui si ordina e si rende.
// Il banner salute è tappabile → Sistema (nav host-owned, nessun callback §6.2).
// =============================================================================

/** Ordinamento fisso di stato (§7.2). `sortedBy` è stabile: preserva l'ordine host a parità di stato. */
private val StatusRank: Map<StatusBadge, Int> = mapOf(
    StatusBadge.PENDING_APPROVAL to 0,
    StatusBadge.NEEDS_REVIEW to 1,
    StatusBadge.ARMED to 2,
    StatusBadge.DISABLED to 3,
)

/** Chip filtro in ordine di design (§7.2), con copy italiana. */
private val FilterChips: List<Pair<StatusFilter, String>> = listOf(
    StatusFilter.ALL to "Tutte",
    StatusFilter.PENDING to "In approvazione",
    StatusFilter.ARMED to "Armate",
    StatusFilter.DISABLED to "Disattivate",
    StatusFilter.NEEDS_REVIEW to "Da rivedere",
)

@Composable
fun AutomationListScreen(
    state: AutomationListState,
    callbacks: AutomationListCallbacks,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Text(
                "Automazioni",
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(start = 18.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
            )

            // Banner salute persistente e tappabile → Sistema (nav host-owned).
            EngineBannerBar(
                state.banner,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )

            FilterChipsRow(state.filter, callbacks::onFilter)

            val rows = state.rows.sortedBy { StatusRank[it.status] ?: Int.MAX_VALUE }
            if (rows.isEmpty()) {
                // Empty (§6.2): la CTA cambia tab → Chat; la navigazione è host-owned
                // (NavHost, Task 12), come le altre affordance di nav di questo modulo.
                EmptyState(
                    icon = Icons.Rounded.Bolt,
                    title = "Nessuna automazione",
                    body = "Chiedila in chat: descrivi cosa vuoi che Argus faccia e proporrà una regola da rivedere e armare.",
                    ctaLabel = "Vai in chat",
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(rows, key = { it.id }) { row ->
                        AutomationCard(
                            row = row,
                            onOpen = { callbacks.onOpen(row.id) },
                            onToggleEnabled = { enabled -> callbacks.onToggleEnabled(row.id, enabled) },
                        )
                    }
                }
            }
        }
    }
}

// -----------------------------------------------------------------------------
// Chip filtro
// -----------------------------------------------------------------------------

@Composable
private fun FilterChipsRow(active: StatusFilter, onFilter: (StatusFilter) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChips.forEach { (filter, label) ->
            val selected = active == filter
            FilterChip(
                selected = selected,
                onClick = { onFilter(filter) },
                label = { Text(label) },
                leadingIcon = if (selected) {
                    { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
                shape = RoundedCornerShape(20.dp),
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
                    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
                ),
                border = if (selected) {
                    null
                } else {
                    BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                },
            )
        }
    }
}

// -----------------------------------------------------------------------------
// Card riga automazione
// -----------------------------------------------------------------------------

/** Tinta di sfondo/bordo per stato: pending ambra tenue, needs-review rossa tenue (§7.2). */
@Composable
private fun rowTint(status: StatusBadge): Pair<Color, Color> {
    val semantic = LocalArgusSemantic.current
    return when (status) {
        StatusBadge.PENDING_APPROVAL ->
            semantic.pending.fg.copy(alpha = 0.06f) to semantic.pending.fg.copy(alpha = 0.35f)
        StatusBadge.NEEDS_REVIEW ->
            semantic.needsReview.fg.copy(alpha = 0.06f) to semantic.needsReview.fg.copy(alpha = 0.35f)
        else ->
            Color.Transparent to MaterialTheme.colorScheme.outlineVariant
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutomationCard(
    row: AutomationRow,
    onOpen: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    val semantic = LocalArgusSemantic.current
    val (tint, borderColor) = rowTint(row.status)
    val showToggle = row.status == StatusBadge.ARMED || row.status == StatusBadge.DISABLED

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .background(tint) // overlay tinta stato sopra la surface (derivata per alpha dai token)
            .border(1.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable { onOpen() } // tap card → dettaglio; lo Switch consuma il proprio tap (no propagazione)
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        // Riga 1: icona trigger + nome (+ pallino warning) + sommario · toggle a destra.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Icon(
                iconFor(row.triggerIconKey),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        row.name,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    if (row.hasWarnings) {
                        // Pallino ambra: la regola ha warning (conflitti/validator) — §7.2.
                        Box(Modifier.size(8.dp).clip(CircleShape).background(semantic.pending.fg))
                    }
                }
                Text(
                    row.triggerSummary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (showToggle) {
                // Toggle inline ARMED⇄DISABLED: onCheckedChange NON propaga al tap card.
                Switch(
                    checked = row.enabled,
                    onCheckedChange = onToggleEnabled,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }

        // Riga 2: badge stato + generativa (sx) · prossimo/ultimo scatto (dx).
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusBadgeChip(row.status)
                if (row.isGenerative) GenerativeTag()
            }
            if (row.nextFireLabel != null || row.lastFiredLabel != null) {
                Column(horizontalAlignment = Alignment.End) {
                    row.nextFireLabel?.let {
                        Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    }
                    row.lastFiredLabel?.let {
                        Text(it, color = MaterialTheme.colorScheme.outline, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

// =============================================================================
// Preview — fixture inline (i Fixtures centralizzati arrivano in Unit F).
// =============================================================================

private val previewRows = listOf(
    // Volutamente in ordine "sbagliato": lo screen deve riordinarle per stato.
    AutomationRow(
        id = "a1", name = "Casa · spegni Wi-Fi uscendo", triggerIconKey = "geofence",
        triggerSummary = "Geofence \"Casa\" · in uscita", status = StatusBadge.ARMED,
        enabled = true, isGenerative = false, hasWarnings = false,
        lastFiredLabel = "ieri 18:32", nextFireLabel = null,
    ),
    AutomationRow(
        id = "a2", name = "DND dopo le 23:00", triggerIconKey = "time",
        triggerSummary = "Ogni giorno 23:00", status = StatusBadge.ARMED,
        enabled = true, isGenerative = false, hasWarnings = false,
        lastFiredLabel = null, nextFireLabel = "stasera 23:00",
    ),
    AutomationRow(
        id = "a3", name = "Rispondi a Moglie su WhatsApp", triggerIconKey = "notification",
        triggerSummary = "Notifica WhatsApp · Moglie", status = StatusBadge.PENDING_APPROVAL,
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

private object NoopListCallbacks : AutomationListCallbacks {
    override fun onOpen(id: String) {}
    override fun onToggleEnabled(id: String, enabled: Boolean) {}
    override fun onFilter(f: StatusFilter) {}
}

@Preview(name = "Lista · mista (pending+armed+disabled+needs_review)", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun ListMixedPreview() {
    ArgusTheme {
        AutomationListScreen(
            AutomationListState(rows = previewRows, filter = StatusFilter.ALL, banner = EngineBanner.NONE, loading = false),
            NoopListCallbacks,
        )
    }
}

@Preview(name = "Lista · banner SHIZUKU_DOWN", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun ListBannerPreview() {
    ArgusTheme {
        AutomationListScreen(
            AutomationListState(rows = previewRows, filter = StatusFilter.PENDING, banner = EngineBanner.SHIZUKU_DOWN, loading = false),
            NoopListCallbacks,
        )
    }
}

@Preview(name = "Lista · empty", showBackground = true, backgroundColor = 0xFF0E1216, heightDp = 820)
@Composable
private fun ListEmptyPreview() {
    ArgusTheme {
        AutomationListScreen(
            AutomationListState(rows = emptyList(), filter = StatusFilter.ALL, banner = EngineBanner.NONE, loading = false),
            NoopListCallbacks,
        )
    }
}
