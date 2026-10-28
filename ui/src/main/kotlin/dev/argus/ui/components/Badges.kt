package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Pending
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.RolePair

// =============================================================================
// Chip di stato (tavola «Badge di stato»). Centralizzano colore + icona + testo per ruolo
// semantico: il colore non e' mai l'unico segnale. Nessun colore hardcoded: tutto da ArgusTheme.semantic.
// I chip non sono interattivi, quindi non hanno vincolo di area tattile 48 dp.
// =============================================================================

/** Altezza minima del chip: `Compact` (24) nelle righe, `Regular` (26) nelle intestazioni di dettaglio. */
enum class ChipSize(internal val minHeight: Dp, internal val iconStart: Dp, internal val gap: Dp) {
    Compact(24.dp, 7.dp, 4.dp),
    Regular(26.dp, 8.dp, 5.dp),
}

/**
 * Chip semantico generico: sfondo `role.bg`, icona 14 dp e testo `role.fg`, forma a pillola.
 * Cresce in altezza con la scala del testo. Per un ruolo di stato noto preferire [StatusBadgeChip],
 * [GenerativeTag], [CloudTag].
 */
@Composable
fun StatusChip(
    role: RolePair,
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    size: ChipSize = ChipSize.Compact,
) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = size.minHeight)
            .clip(CircleShape)
            .background(role.bg)
            .padding(start = size.iconStart, end = 10.dp, top = 4.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(size.gap),
    ) {
        Icon(icon, contentDescription = null, tint = role.fg, modifier = Modifier.size(14.dp))
        Text(text, color = role.fg, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Chip neutro con solo contorno («conferma live»): informazione di contorno, non uno stato.
 * Contorno `outline`, testo e icona `onSurfaceVariant`.
 */
@Composable
fun OutlineChip(
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
    size: ChipSize = ChipSize.Compact,
) {
    Row(
        modifier = modifier
            .defaultMinSize(minHeight = size.minHeight)
            .clip(CircleShape)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
            .padding(start = size.iconStart, end = 10.dp, top = 4.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(size.gap),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
    }
}

private data class BadgeStyle(val role: RolePair, val icon: ImageVector, val label: String)

@Composable
private fun statusStyle(status: StatusBadge): BadgeStyle {
    val s = ArgusTheme.semantic
    return when (status) {
        StatusBadge.ARMED -> BadgeStyle(s.armed, Icons.Rounded.Shield, stringResource(R.string.badge_status_armed))
        StatusBadge.PENDING_APPROVAL -> BadgeStyle(s.pending, Icons.Rounded.Pending, stringResource(R.string.badge_status_pending))
        StatusBadge.DISABLED -> BadgeStyle(s.disabled, Icons.Rounded.PauseCircle, stringResource(R.string.badge_status_disabled))
        StatusBadge.NEEDS_REVIEW -> BadgeStyle(s.needsReview, Icons.Rounded.SyncProblem, stringResource(R.string.badge_status_needs_review))
    }
}

/** Badge di stato coerente ovunque (lista, dettaglio, chat) — direttiva §5. */
@Composable
fun StatusBadgeChip(status: StatusBadge, modifier: Modifier = Modifier, size: ChipSize = ChipSize.Compact) {
    val st = statusStyle(status)
    StatusChip(st.role, st.icon, st.label, modifier, size)
}

/** Badge «generativa» (lilla) — accompagna SEMPRE una regola generativa (invariante §5.4). */
@Composable
fun GenerativeTag(modifier: Modifier = Modifier, size: ChipSize = ChipSize.Compact) {
    StatusChip(ArgusTheme.semantic.generative, Icons.Rounded.SmartToy, stringResource(R.string.badge_generative), modifier, size)
}

/** Badge privacy «esce verso il cloud» (azzurro-verde) — coppia obbligatoria di [GenerativeTag] (§5.4). */
@Composable
fun CloudTag(modifier: Modifier = Modifier, size: ChipSize = ChipSize.Compact) {
    StatusChip(ArgusTheme.semantic.cloud, Icons.Rounded.CloudUpload, stringResource(R.string.badge_cloud), modifier, size)
}

/** Chip neutro «conferma live» — azione del catalogo sempre-conferma (spec §10.2). */
@Composable
fun LiveConfirmTag(modifier: Modifier = Modifier, size: ChipSize = ChipSize.Compact) {
    OutlineChip(Icons.Rounded.TouchApp, stringResource(R.string.rule_live_confirm), modifier, size)
}

/** Etichetta di avviso («1 avviso»): ruolo `pending` con triangolo, per card bozza e dettaglio. */
@Composable
fun WarningCountChip(text: String, modifier: Modifier = Modifier, size: ChipSize = ChipSize.Compact) {
    StatusChip(ArgusTheme.semantic.pending, Icons.Rounded.WarningAmber, text, modifier, size)
}

@OptIn(ExperimentalLayoutApi::class)
@ArgusThemePreviews
@Composable
private fun BadgesPreview() {
    ArgusPreviewSurface {
        FlowRow(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusBadgeChip(StatusBadge.ARMED)
            StatusBadgeChip(StatusBadge.PENDING_APPROVAL)
            StatusBadgeChip(StatusBadge.DISABLED)
            StatusBadgeChip(StatusBadge.NEEDS_REVIEW)
            GenerativeTag()
            CloudTag()
            LiveConfirmTag()
            WarningCountChip("1 avviso")
            StatusBadgeChip(StatusBadge.ARMED, size = ChipSize.Regular)
            StatusBadgeChip(StatusBadge.NEEDS_REVIEW, size = ChipSize.Regular)
        }
    }
}
