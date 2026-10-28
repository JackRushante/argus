package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.argus.ui.R
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Attesa del Brain in chat (tavola «Chat · in attesa»). Nessuno streaming (spec §4): il tempo trascorso e'
// onesto e visibile. La barra va da 0 al timeout (65 s, 180 s per un server locale), la fascia
// tratteggiata dice «di solito 10-30 s», il riempimento avanza di un secondo alla volta e non va mai oltre
// l'asse. «Annulla» e' sempre a portata di mano.
// =============================================================================

/** Fascia in cui il Brain di solito risponde (secondi): la stessa che dice il testo «di solito 10-30 s». */
internal const val TYPICAL_WAIT_FROM_SEC = 10
internal const val TYPICAL_WAIT_TO_SEC = 30

/** Asse standard della barra quando il timeout non e' noto (il timeout di compilazione predefinito). */
internal const val DEFAULT_WAIT_AXIS_SEC = 65

/** Frazioni (0..1) della barra: riempimento, inizio e larghezza della fascia tipica. */
internal data class WaitBar(val progress: Float, val bandStart: Float, val bandWidth: Float, val axisSec: Int)

/** Calcola la barra per [elapsedSec] su un asse di [timeoutSec] (null = asse standard). Valori sempre in 0..1. */
internal fun waitBarOf(elapsedSec: Int, timeoutSec: Int?): WaitBar {
    val axis = (timeoutSec ?: DEFAULT_WAIT_AXIS_SEC).coerceAtLeast(TYPICAL_WAIT_TO_SEC)
    val bandStart = TYPICAL_WAIT_FROM_SEC.toFloat() / axis
    val bandEnd = TYPICAL_WAIT_TO_SEC.toFloat() / axis
    return WaitBar(
        progress = (elapsedSec.coerceAtLeast(0).toFloat() / axis).coerceIn(0f, 1f),
        bandStart = bandStart,
        bandWidth = bandEnd - bandStart,
        axisSec = axis,
    )
}

/**
 * Scheda «Argus sta pensando… (12 s)» con barra dei tempi e «Annulla». Sta nel flusso della conversazione,
 * dalla parte di Argus. TalkBack la legge come regione viva («Argus sta pensando, 12 secondi»).
 */
@Composable
fun ChatWaitCard(
    elapsedSec: Int,
    timeoutSec: Int?,
    expectedRangeLabel: String,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bar = waitBarOf(elapsedSec, timeoutSec)
    val description = stringResource(R.string.rda_lc_wait_cd, elapsedSec)
    Column(
        modifier = modifier
            .widthIn(max = 340.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp, bottomEnd = 22.dp, bottomStart = ArgusRadius.Xs))
            .background(ArgusTheme.surfaces.card)
            .padding(start = 14.dp, top = 14.dp, end = ArgusSpacing.Small, bottom = 6.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
    ) {
        Column(
            modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = description },
            verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
        ) {
            Row(
                modifier = Modifier.padding(end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
            ) {
                IconBadge(
                    icon = Icons.Rounded.HourglassTop,
                    container = MaterialTheme.colorScheme.secondaryContainer,
                    content = MaterialTheme.colorScheme.onSecondaryContainer,
                    size = 40.dp,
                    iconSize = 20.dp,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.latency_thinking, elapsedSec),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        expectedRangeLabel,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // La barra e i suoi numeri sono decorativi: il tempo e' gia' nel testo sopra.
            Column(
                modifier = Modifier.padding(end = 6.dp).clearAndSetSemantics {},
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WaitTrack(bar)
                WaitAxis(bar)
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ArgusButton(
                text = stringResource(R.string.action_cancel),
                onClick = onCancel,
                style = ButtonStyle.Text,
            )
        }
    }
}

@Composable
private fun WaitTrack(bar: WaitBar) {
    val scheme = MaterialTheme.colorScheme
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(ArgusTheme.surfaces.field),
    ) {
        // Fascia tipica: sfumata, sotto il riempimento.
        Box(
            Modifier
                .offset(x = maxWidth * bar.bandStart)
                .width(maxWidth * bar.bandWidth)
                .fillMaxHeight()
                .background(scheme.primary.copy(alpha = BAND_ALPHA)),
        )
        Box(
            Modifier
                .width(maxWidth * bar.progress)
                .fillMaxHeight()
                .clip(RoundedCornerShape(4.dp))
                .background(scheme.primary),
        )
    }
}

/** Opacita' della fascia tipica sulla barra (decorativa; il testo «10-30 s» la nomina). */
private const val BAND_ALPHA = 0.22f

@Composable
private fun WaitAxis(bar: WaitBar) {
    val label = MaterialTheme.typography.labelSmall.copy(
        fontWeight = FontWeight.Normal,
        letterSpacing = 0.sp,
    )
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(Modifier.fillMaxWidth().height(16.dp)) {
        Text(stringResource(R.string.rda_lc_wait_axis_start), color = color, style = label, modifier = Modifier.align(Alignment.CenterStart))
        Text(
            stringResource(R.string.rda_lc_wait_axis_band, TYPICAL_WAIT_FROM_SEC, TYPICAL_WAIT_TO_SEC),
            color = color,
            style = label,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.offset(x = maxWidth * bar.bandStart).width(maxWidth * bar.bandWidth),
        )
        Text(
            stringResource(R.string.rda_lc_wait_axis_end, bar.axisSec),
            color = color,
            style = label,
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

@ArgusThemePreviews
@Composable
private fun ChatWaitCardPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ChatWaitCard(elapsedSec = 12, timeoutSec = 65, expectedRangeLabel = "di solito 10-30 s", onCancel = {})
            ChatWaitCard(elapsedSec = 0, timeoutSec = null, expectedRangeLabel = "di solito 10-30 s", onCancel = {})
            // Server locale: asse piu' lungo, il riempimento non esce mai dalla barra.
            ChatWaitCard(elapsedSec = 200, timeoutSec = 180, expectedRangeLabel = "di solito 10-30 s", onCancel = {})
        }
    }
}
