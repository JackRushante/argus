package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Riepilogo in testa alla lista (tavola «Automazioni», sezione «Riepilogo»).
// Pannello `secondaryContainer` (raggio 28) con una fila di contatori e una fila di riquadri.
// I contatori filtrano la lista (stesso callback dei filtri), i riquadri aprono l'oggetto indicato.
// Solo componente: i dati (conteggi, prossimo scatto, errori di oggi) li calcola chi lo usa.
// =============================================================================

/** Tinta di un numero o di un valore nel riepilogo; il colore lo risolve il componente dal tema. */
enum class HeroTone {
    /** Normale: `primary` (armate). */
    Primary,
    /** Aspetta te: `attention` (in attesa). */
    Attention,
    /** Da rivedere: arancio. */
    NeedsReview,
    /** Problema: rosso. */
    Error,
    /** Nessuna tinta: testo del pannello. */
    Neutral,
}

@Composable
private fun HeroTone.color(): Color = when (this) {
    HeroTone.Primary -> MaterialTheme.colorScheme.primary
    HeroTone.Attention -> ArgusTheme.semantic.attention
    HeroTone.NeedsReview -> ArgusTheme.semantic.needsReview.fg
    HeroTone.Error -> ArgusTheme.semantic.error.fg
    HeroTone.Neutral -> MaterialTheme.colorScheme.onSecondaryContainer
}

/**
 * Contatore del riepilogo: numero grande e etichetta. [contentDescription] per TalkBack, completo
 * («3 armate, mostra solo le armate»). Senza [onClick] il contatore e' solo informativo.
 */
data class HeroCounter(
    val value: String,
    val label: String,
    val tone: HeroTone,
    val contentDescription: String,
    val onClick: (() -> Unit)? = null,
)

/**
 * Riquadro del riepilogo: etichetta con icona, valore, dettaglio. [contentDescription] completo per TalkBack
 * («Prossimo scatto stasera alle 23:00, DND dopo le 23:00»).
 */
data class HeroTile(
    val icon: ImageVector,
    val label: String,
    val value: String,
    val detail: String?,
    val valueTone: HeroTone,
    val contentDescription: String,
    val onClick: (() -> Unit)? = null,
)

/**
 * Riepilogo. [counters] in una fila di larghezza uguale (min 56 dp l'uno), [tiles] in una seconda fila
 * (min 72 dp). Entrambe le liste possono essere vuote: la fila corrispondente non compare.
 */
@Composable
fun SummaryHero(
    counters: List<HeroCounter>,
    tiles: List<HeroTile>,
    modifier: Modifier = Modifier,
) {
    val surfaces = ArgusTheme.surfaces
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ArgusRadius.Xxl))
            .background(scheme.secondaryContainer)
            .padding(ArgusSpacing.Medium),
        verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
    ) {
        if (counters.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit)) {
                counters.forEach { counter ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .then(
                                if (counter.onClick != null) Modifier.clickable(role = Role.Button, onClick = counter.onClick)
                                else Modifier,
                            )
                            .defaultMinSize(minHeight = 56.dp)
                            .padding(horizontal = ArgusSpacing.Small, vertical = ArgusSpacing.Unit)
                            .semantics(mergeDescendants = true) { contentDescription = counter.contentDescription },
                        horizontalAlignment = Alignment.Start,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            counter.value,
                            color = counter.tone.color(),
                            style = MaterialTheme.typography.headlineSmall.copy(fontSize = 26.sp, lineHeight = 32.sp),
                        )
                        Text(
                            counter.label,
                            color = surfaces.heroSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.W500,
                        )
                    }
                }
            }
        }
        if (tiles.isNotEmpty()) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
                tiles.forEach { tile ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(ArgusRadius.Lg))
                            .background(surfaces.heroTile)
                            .then(
                                if (tile.onClick != null) Modifier.clickable(role = Role.Button, onClick = tile.onClick)
                                else Modifier,
                            )
                            .defaultMinSize(minHeight = 72.dp)
                            .padding(horizontal = ArgusSpacing.Medium, vertical = 10.dp)
                            .semantics(mergeDescendants = true) { contentDescription = tile.contentDescription },
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            Icon(tile.icon, contentDescription = null, tint = surfaces.heroSecondary, modifier = Modifier.size(14.dp))
                            Text(
                                tile.label,
                                color = surfaces.heroSecondary,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.W500,
                            )
                        }
                        Text(
                            tile.value,
                            color = tile.valueTone.color(),
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Start,
                        )
                        if (tile.detail != null) {
                            Text(tile.detail, color = surfaces.heroSecondary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.W400)
                        }
                    }
                }
            }
        }
    }
}

@ArgusThemePreviews
@Composable
private fun SummaryHeroPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SummaryHero(
                counters = listOf(
                    HeroCounter("3", "armate", HeroTone.Primary, "3 armate, mostra solo le armate", {}),
                    HeroCounter("2", "in attesa", HeroTone.Attention, "2 in attesa di approvazione, mostra solo queste", {}),
                    HeroCounter("1", "da rivedere", HeroTone.NeedsReview, "1 da rivedere, mostra solo queste", {}),
                ),
                tiles = listOf(
                    HeroTile(Icons.Rounded.Schedule, "Prossimo scatto", "stasera 23:00", "DND dopo le 23:00", HeroTone.Neutral,
                        "Prossimo scatto stasera alle 23:00, DND dopo le 23:00", {}),
                    HeroTile(Icons.Rounded.ErrorOutline, "Oggi", "1 errore", "Backup foto notturno · 12:05", HeroTone.Error,
                        "Oggi 1 errore: Backup foto notturno alle 12:05. Apri il log", {}),
                ),
            )
            SummaryHero(
                counters = listOf(
                    HeroCounter("0", "armate", HeroTone.Primary, "0 armate"),
                    HeroCounter("0", "in attesa", HeroTone.Attention, "0 in attesa"),
                    HeroCounter("0", "da rivedere", HeroTone.NeedsReview, "0 da rivedere"),
                ),
                tiles = emptyList(),
            )
        }
    }
}
