package dev.argus.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Segmenti e filtri (tavole «Automazioni», «Log», «Configura provider»).
//  - ArgusSegmentedFilter: gruppo di segmenti collegati per una scelta ESCLUSIVA che filtra una lista
//    (Tutte / In approvazione / ...). Il segmento scelto diventa pillola con spunta; gli altri hanno angoli 8,
//    tranne le due estremita' del gruppo che restano arrotondate.
//  - ArgusChoiceChip: scelta tra poche opzioni in un modulo (provider, ragionamento): pillola con contorno,
//    scelta = `secondaryContainer` con spunta.
// Movimento sobrio: colore e angoli con molla veloce (smorzamento 0,9 · rigidita' 1400); con «Rimuovi animazioni»
// le transizioni sono istantanee (le animazioni Compose seguono la scala di sistema).
// =============================================================================

private const val SPRING_DAMPING = 0.9f
private const val SPRING_STIFFNESS = 1400f

/** Angoli (inizio, fine) di un segmento: pillola per il selezionato e per le estremita' del gruppo, [innerRadius] altrove. */
internal fun segmentCornerRadii(
    index: Int,
    count: Int,
    selected: Boolean,
    pillRadius: Dp,
    innerRadius: Dp = 8.dp,
): Pair<Dp, Dp> {
    if (selected) return pillRadius to pillRadius
    val start = if (index <= 0) pillRadius else innerRadius
    val end = if (index >= count - 1) pillRadius else innerRadius
    return start to end
}

/**
 * Filtro a segmenti. Scorre in orizzontale se le opzioni non entrano (testo grande, schermi stretti).
 *
 * @param groupDescription descrizione del gruppo per TalkBack («Filtra per stato»).
 * @param label testo di ogni opzione, mai solo il colore.
 * @param segmentHeight 40 nelle liste, 36 nei moduli.
 */
@Composable
fun <T> ArgusSegmentedFilter(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    groupDescription: String,
    modifier: Modifier = Modifier,
    segmentHeight: Dp = 40.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(contentPadding)
            .selectableGroup()
            .semantics { contentDescription = groupDescription },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEachIndexed { index, option ->
            Segment(
                text = label(option),
                selected = option == selected,
                index = index,
                count = options.size,
                height = segmentHeight,
                onClick = { onSelect(option) },
            )
        }
    }
}

@Composable
private fun Segment(text: String, selected: Boolean, index: Int, count: Int, height: Dp, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) scheme.primary else ArgusTheme.surfaces.card2,
        spring(SPRING_DAMPING, SPRING_STIFFNESS),
        label = "segmentContainer",
    )
    val content by animateColorAsState(
        if (selected) scheme.onPrimary else scheme.onSurfaceVariant,
        spring(SPRING_DAMPING, SPRING_STIFFNESS),
        label = "segmentContent",
    )
    val (startTarget, endTarget) = segmentCornerRadii(index, count, selected, pillRadius = height / 2)
    val start by animateDpAsState(startTarget, spring(SPRING_DAMPING, SPRING_STIFFNESS), label = "segmentStart")
    val end by animateDpAsState(endTarget, spring(SPRING_DAMPING, SPRING_STIFFNESS), label = "segmentEnd")
    // L'area tattile e' 48 dp anche se il segmento ne disegna 36-40.
    Box(
        Modifier
            .minimumInteractiveComponentSize()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(height)
                .clip(RoundedCornerShape(topStart = start, bottomStart = start, topEnd = end, bottomEnd = end))
                .background(container)
                .padding(start = if (selected) 10.dp else 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Text(text, color = content, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.W600)
        }
    }
}

/**
 * Scelta in un modulo: pillola 36 dp con contorno `outline`; scelta = `secondaryContainer` con spunta.
 * Area tattile 48 dp. [role] `RadioButton` per scelta esclusiva, `Checkbox` per multipla.
 */
@Composable
fun ArgusChoiceChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: Role = Role.RadioButton,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        if (selected) scheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent,
        spring(SPRING_DAMPING, SPRING_STIFFNESS),
        label = "choiceContainer",
    )
    val content = when {
        !enabled -> ArgusTheme.surfaces.disabledContent
        selected -> scheme.onSecondaryContainer
        else -> scheme.onSurface
    }
    Box(
        modifier
            .minimumInteractiveComponentSize()
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(CircleShape)
                .background(container)
                .then(if (selected) Modifier else Modifier.border(1.dp, scheme.outline, CircleShape))
                .padding(start = if (selected) 8.dp else 14.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Text(
                text,
                color = content,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.W600 else FontWeight.W500,
            )
        }
    }
}

@ArgusThemePreviews
@Composable
private fun SegmentedFilterPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = listOf("Tutte", "In approvazione", "Armate", "Disattivate", "Da rivedere")
            ArgusSegmentedFilter(options, "Tutte", {}, { it }, groupDescription = "Filtra per stato")
            ArgusSegmentedFilter(options, "Armate", {}, { it }, groupDescription = "Filtra per stato")
            ArgusSegmentedFilter(options, "Da rivedere", {}, { it }, groupDescription = "Filtra per stato", segmentHeight = 36.dp)
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ArgusChoiceChip("Hermes", selected = false, onClick = {})
                ArgusChoiceChip("OpenRouter", selected = true, onClick = {})
                ArgusChoiceChip("OpenAI", selected = false, onClick = {}, enabled = false)
            }
        }
    }
}
