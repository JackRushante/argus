package dev.argus.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews

// =============================================================================
// Marchio di Argus (l'occhio dell'icona dell'app) per l'intestazione e lo stato vuoto della Chat
// (tavole «Chat · primo avvio» e «Chat»). E' artwork di marca, non un colore del tema: resta uguale in
// chiaro e scuro, come l'icona sul launcher. Decorativo: il titolo «Argus» accanto lo nomina.
// Disegnato sulla griglia 48 x 48 della tavola.
// =============================================================================

private val EyeFill = Color(0xFF0B2A52)
private val EyeStroke = Color(0xFF9ECAFF)
private val GraphAccent = Color(0xFFC9B8FF)

private const val EYE_PATH =
    "M4 23.5C10.4 13.8 16.9 9.5 24 9.5S37.6 13.8 44 23.5C38.3 30.6 31.8 36.2 24 40.5 16.2 36.2 9.7 30.6 4 23.5z"
private const val PUPIL_TAIL_PATH = "M20.6 30.4 19.4 35l5.2-3.6z"
private const val GRAPH_PATH = "M24 19.2 20.6 25.2H27.4z"

/** Il marchio a [size] dp. Decorativo (nessuna descrizione per TalkBack). */
@Composable
fun ChatBrandMark(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val eye = remember { PathParser().parsePathString(EYE_PATH).toPath() }
    val tail = remember { PathParser().parsePathString(PUPIL_TAIL_PATH).toPath() }
    val graph = remember { PathParser().parsePathString(GRAPH_PATH).toPath() }
    Canvas(modifier.size(size).clearAndSetSemantics {}) {
        scale(scale = this.size.width / 48f, pivot = Offset.Zero) {
            drawPath(eye, EyeFill)
            drawPath(eye, EyeStroke, style = Stroke(width = 3.2f, join = StrokeJoin.Round))
            drawCircle(EyeStroke, radius = 8.6f, center = Offset(24f, 23f), style = Stroke(width = 2.4f))
            drawPath(tail, EyeStroke)
            drawPath(graph, GraphAccent, style = Stroke(width = 1.5f, join = StrokeJoin.Round))
            listOf(Offset(24f, 19.2f), Offset(20.6f, 25.2f), Offset(27.4f, 25.2f)).forEach { node ->
                drawCircle(GraphAccent, radius = 2.1f, center = node)
            }
        }
    }
}

@ArgusThemePreviews
@Composable
private fun ChatBrandMarkPreview() {
    ArgusPreviewSurface {
        Row(
            Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ChatBrandMark(size = 36.dp)
            ChatBrandMark(size = 72.dp)
        }
    }
}
