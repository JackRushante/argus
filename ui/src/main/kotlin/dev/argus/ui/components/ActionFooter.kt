package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Footer di azioni (tavola «Footer di approvazione · DetailFooter»). Fisso in fondo alla schermata, fondo `nav`,
// filetto in alto `outlineVariant`, 8 dp tra i pulsanti, 24 dp sotto (inset dei gesti).
// Regole: UN solo pulsante pieno per stato; il motivo di un'azione disabilitata sta SOPRA i pulsanti
// (invariante 2: «Arma» disabilitato con motivo visibile), mai in un tooltip.
// Non aggiunge gli inset delle barre di sistema: li mette lo Scaffold dell'host.
// =============================================================================

/**
 * Footer di azioni.
 *
 * @param disabledReason motivo dell'azione bloccata, scritto sopra i pulsanti. `null` quando tutto e' abilitato.
 * @param content i pulsanti, in colonna (di norma l'azione principale a tutta larghezza, poi una fila di secondarie).
 */
@Composable
fun ArgusActionFooter(
    modifier: Modifier = Modifier,
    disabledReason: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val divider = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(ArgusTheme.surfaces.nav)
            .padding(start = ArgusSpacing.Large, end = ArgusSpacing.Large, top = ArgusSpacing.Medium, bottom = ArgusSpacing.Section),
        verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
    ) {
        Row(Modifier.fillMaxWidth().height(1.dp).background(divider)) {}
        if (!disabledReason.isNullOrBlank()) ReasonText(disabledReason)
        content()
    }
}

@ArgusThemePreviews
@Composable
private fun ActionFooterPreview() {
    ArgusPreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ArgusActionFooter {
                ArgusButton("Arma", {}, icon = Icons.Rounded.Shield, fillWidth = true, minHeight = 52.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArgusButton("Rifiuta", {}, style = ButtonStyle.Destructive, icon = Icons.Rounded.Delete, minHeight = 52.dp)
                    ArgusButton("Modifica", {}, style = ButtonStyle.Outlined, icon = Icons.Rounded.Edit, minHeight = 52.dp)
                }
            }
            ArgusActionFooter(disabledReason = "Arma bloccato: tool 'shell.run' e 'app.install' vietati nelle regole generative (invariante di sicurezza)") {
                ArgusButton("Arma", {}, icon = Icons.Rounded.Shield, fillWidth = true, enabled = false, minHeight = 52.dp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArgusButton("Rifiuta", {}, style = ButtonStyle.Destructive, minHeight = 52.dp)
                    ArgusButton("Modifica", {}, style = ButtonStyle.Outlined, minHeight = 52.dp)
                }
            }
        }
    }
}
