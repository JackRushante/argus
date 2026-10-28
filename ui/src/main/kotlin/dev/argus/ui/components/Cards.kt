package dev.argus.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Card e gruppi di card (tavola «Forme e raggi» · gruppo segmentato: esterno 24, interno 6, gap 3).
// Una lista di righe e' un GRUPPO: angoli grandi in testa e in coda, piccoli in mezzo, 3 dp di fessura.
// =============================================================================

/** Posizione di un elemento dentro un gruppo di card. */
enum class GroupPosition { Only, First, Middle, Last }

/** Posizione dell'elemento [index] in un gruppo di [count] elementi. Un solo elemento = [GroupPosition.Only]. */
fun groupPositionOf(index: Int, count: Int): GroupPosition = when {
    count <= 1 -> GroupPosition.Only
    index <= 0 -> GroupPosition.First
    index >= count - 1 -> GroupPosition.Last
    else -> GroupPosition.Middle
}

/**
 * Forma dell'elemento in un gruppo: angoli [outer] sui lati esterni del gruppo, [inner] sui lati che
 * toccano un vicino. Default della tavola: esterno 24, interno 6. Il Log usa 22/5, le «ultime esecuzioni»
 * del dettaglio 18/4 (passare `outer`/`inner`).
 */
fun groupShape(
    position: GroupPosition,
    outer: Dp = ArgusRadius.Xl,
    inner: Dp = ArgusRadius.Xs,
): RoundedCornerShape = when (position) {
    GroupPosition.Only -> RoundedCornerShape(outer)
    GroupPosition.First -> RoundedCornerShape(topStart = outer, topEnd = outer, bottomStart = inner, bottomEnd = inner)
    GroupPosition.Middle -> RoundedCornerShape(inner)
    GroupPosition.Last -> RoundedCornerShape(topStart = inner, topEnd = inner, bottomStart = outer, bottomEnd = outer)
}

/**
 * Card: pannello su `ArgusTheme.surfaces.card` (o [containerColor]). Forma di default 24 (esterni dei gruppi,
 * pannelli); per la card bozza / il riepilogo usare `RoundedCornerShape(ArgusRadius.Xxl)`.
 * Con [onClick] diventa un'unica area tappabile (min 48 dp via contenuto); senza, e' statica.
 * Il padding di default e' 16 (pannelli); le righe di lista usano `PaddingValues(0.dp)` e padding proprio.
 */
@Composable
fun ArgusCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(ArgusRadius.Xl),
    containerColor: Color = ArgusTheme.surfaces.card,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(ArgusSpacing.Large),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = shape, color = containerColor, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    } else {
        Surface(modifier = modifier, shape = shape, color = containerColor, border = border) {
            Column(Modifier.padding(contentPadding), content = content)
        }
    }
}

/**
 * Gruppo di card: una [Column] con 3 dp di fessura in cui ogni elemento e' una [ArgusCard] la cui forma
 * dipende dalla posizione ([groupPositionOf] / [groupShape]). Il contenuto di ogni elemento gestisce il proprio
 * padding (di norma una [ArgusListRow]). Per le liste pigre (LazyColumn) usare direttamente
 * `ArgusCard(shape = groupShape(groupPositionOf(i, n)))` con `Arrangement.spacedBy(ArgusSpacing.GroupGap)`.
 */
@Composable
fun <T> ArgusCardGroup(
    items: List<T>,
    modifier: Modifier = Modifier,
    outerRadius: Dp = ArgusRadius.Xl,
    innerRadius: Dp = ArgusRadius.Xs,
    itemContent: @Composable ColumnScope.(item: T) -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ArgusSpacing.GroupGap)) {
        items.forEachIndexed { index, item ->
            ArgusCard(
                modifier = Modifier.fillMaxWidth(),
                shape = groupShape(groupPositionOf(index, items.size), outerRadius, innerRadius),
                contentPadding = PaddingValues(0.dp),
            ) { itemContent(item) }
        }
    }
}

@ArgusThemePreviews
@Composable
private fun CardsPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ArgusCard(Modifier.fillMaxWidth()) {
                Text("Card pannello", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text("Padding 16, angoli 24.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            ArgusCardGroup(listOf("Primo (24 / 6)", "Secondo (6 / 6)", "Terzo (6 / 24)")) { label ->
                Text(label, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
            ArgusCardGroup(listOf("Da solo (24)")) { label ->
                Text(label, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
