package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Pending
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.model.StatusBadge
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.LocalArgusSemantic
import dev.argus.ui.theme.RolePair

// =============================================================================
// Badge / tag condivisi (handoff §8). Centralizzano colore+icona+testo per ruolo
// semantico: nessun colore hardcoded, tutto da LocalArgusSemantic (design §5.3).
// I badge non sono interattivi -> non hanno vincolo touch-target 48dp.
// =============================================================================

/** Pill semantica riusabile: bg/fg dal ruolo del tema, icona 14dp + testo. */
@Composable
private fun SemanticChip(
    role: RolePair,
    icon: ImageVector,
    text: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(role.bg)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = role.fg, modifier = Modifier.size(14.dp))
        Text(text, color = role.fg, style = MaterialTheme.typography.labelSmall)
    }
}

private data class BadgeStyle(val role: RolePair, val icon: ImageVector, val label: String)

@Composable
private fun statusStyle(status: StatusBadge): BadgeStyle {
    val s = LocalArgusSemantic.current
    return when (status) {
        StatusBadge.ARMED -> BadgeStyle(s.armed, Icons.Rounded.Shield, stringResource(R.string.badge_status_armed))
        StatusBadge.PENDING_APPROVAL -> BadgeStyle(s.pending, Icons.Rounded.Pending, stringResource(R.string.badge_status_pending))
        StatusBadge.DISABLED -> BadgeStyle(s.disabled, Icons.Rounded.PauseCircle, stringResource(R.string.badge_status_disabled))
        StatusBadge.NEEDS_REVIEW -> BadgeStyle(s.needsReview, Icons.Rounded.SyncProblem, stringResource(R.string.badge_status_needs_review))
    }
}

/** Badge di stato coerente ovunque (lista, dettaglio, chat) — direttiva §5. */
@Composable
fun StatusBadgeChip(status: StatusBadge, modifier: Modifier = Modifier) {
    val st = statusStyle(status)
    SemanticChip(st.role, st.icon, st.label, modifier)
}

/** Badge "generativa" (viola) — accompagna SEMPRE una regola generativa (invariante §5.4). */
@Composable
fun GenerativeTag(modifier: Modifier = Modifier) {
    SemanticChip(LocalArgusSemantic.current.generative, Icons.Rounded.SmartToy, stringResource(R.string.badge_generative), modifier)
}

/** Badge privacy "esce verso il cloud" (ambra) — coppia obbligatoria di GenerativeTag (§5.4). */
@Composable
fun CloudTag(modifier: Modifier = Modifier) {
    SemanticChip(LocalArgusSemantic.current.cloud, Icons.Rounded.CloudUpload, stringResource(R.string.badge_cloud), modifier)
}

@OptIn(ExperimentalLayoutApi::class)
@Preview(name = "Badges (dark)", showBackground = true, backgroundColor = 0xFF0E1216)
@Composable
private fun BadgesPreview() {
    ArgusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
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
            }
        }
    }
}
