package dev.argus.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BatterySaver
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Tessera salute (tavola «Tessera salute · HealthTile»): una riga di Sistema che dice se un permesso o un
// servizio e' a posto. Stato nel colore del contenitore dell'icona E nel testo/icona, non solo nel colore.
// =============================================================================

/** Esito di una tessera salute. */
enum class HealthTileLevel {
    /** A posto: icona verde. */
    Ok,
    /** Da correggere: ambra, con pulsante «Correggi». */
    Warn,
    /** Non applicabile / spento per scelta: grigio, eventuale pulsante opt-in («Attiva»). */
    Neutral,
}

/**
 * Tessera salute.
 *
 * @param onClick tocco sull'intera tessera (apre il pannello/permesso relativo); `null` = solo informativa
 *   (ne' tocco ne' pulsante).
 * @param actionLabel testo del pulsante a destra: «Correggi» per [HealthTileLevel.Warn], «Attiva» per le righe
 *   opt-in. Senza etichetta e con [HealthTileLevel.Ok]/[HealthTileLevel.Neutral] compare la spunta di stato.
 * @param okContentDescription descrizione TalkBack della spunta di [HealthTileLevel.Ok] («consentito»).
 */
@Composable
fun HealthTile(
    icon: ImageVector,
    title: String,
    subtitle: String,
    level: HealthTileLevel,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    actionLabel: String? = null,
    okContentDescription: String? = null,
) {
    val semantic = ArgusTheme.semantic
    val (container, glyph) = when (level) {
        HealthTileLevel.Ok -> semantic.armed.bg to semantic.armed.fg
        HealthTileLevel.Warn -> semantic.attentionContainer to semantic.attention
        HealthTileLevel.Neutral -> semantic.disabled.bg to semantic.disabled.fg
    }
    val tileColor = if (level == HealthTileLevel.Warn) semantic.pending.bg else ArgusTheme.surfaces.card
    val textColor = if (level == HealthTileLevel.Warn) semantic.onAttentionContainer else MaterialTheme.colorScheme.onSurface
    val subColor = if (level == HealthTileLevel.Warn) semantic.onAttentionContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = tileColor,
    ) {
        Row(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .defaultMinSize(minHeight = 56.dp)
                .padding(start = ArgusSpacing.Medium, end = ArgusSpacing.Small, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
        ) {
            IconBadge(icon, container, glyph, size = 36.dp, iconSize = 20.dp, shape = RoundedCornerShape(ArgusRadius.Md - 2.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = textColor, style = MaterialTheme.typography.titleSmall)
                Text(subtitle, color = subColor, style = MaterialTheme.typography.bodyMedium)
            }
            when {
                actionLabel != null && onClick != null -> ArgusButton(
                    text = actionLabel,
                    onClick = onClick,
                    style = if (level == HealthTileLevel.Warn) ButtonStyle.Tonal else ButtonStyle.Outlined,
                )
                level == HealthTileLevel.Ok -> Icon(
                    Icons.Rounded.CheckCircle,
                    contentDescription = okContentDescription,
                    tint = semantic.armed.fg,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
    }
}

@ArgusThemePreviews
@Composable
private fun HealthTilePreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            HealthTile(Icons.Rounded.Terminal, "Shizuku", "installato, non in esecuzione", HealthTileLevel.Warn, onClick = {}, actionLabel = "Correggi")
            HealthTile(Icons.Rounded.BatterySaver, "Batteria", "esclusione Android concessa", HealthTileLevel.Ok, onClick = {}, okContentDescription = "consentito")
            HealthTile(Icons.Rounded.Terminal, "Localizzazione in background", "non necessaria", HealthTileLevel.Neutral)
            HealthTile(Icons.Rounded.Terminal, "Accessibilita'", "non attiva", HealthTileLevel.Neutral, onClick = {}, actionLabel = "Attiva")
        }
    }
}
