package dev.argus.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusCode
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// 0.8 · Modalità (tavola «Modalità»): il pannello della modalità attiva con i pulsanti per cambiarla e la
// riga di una variabile salvata col suo valore in monospace.
// =============================================================================

/**
 * Pannello della modalità attiva, sul fondo del riepilogo (`secondaryContainer`, angoli 28): intestazione, un
 * pulsante a pillola per modalità (quella attiva piena, con la spunta: il colore non è l'unico segnale) e la riga
 * dell'ultimo cambio. Scelta esclusiva, letta da TalkBack come gruppo di opzioni.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModeHeroPanel(
    label: String,
    modes: List<String>,
    active: String?,
    note: String,
    switchContentDescription: (String) -> String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ArgusRadius.Xxl),
        color = scheme.secondaryContainer,
        contentColor = scheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(ArgusSpacing.Large), verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium)) {
            Text(
                label.uppercase(),
                modifier = Modifier.semantics { heading() },
                color = scheme.onSecondaryContainer,
                style = MaterialTheme.typography.labelSmall,
            )
            if (modes.isNotEmpty()) {
                FlowRow(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
                ) {
                    modes.forEach { mode ->
                        ModeSwitchChip(
                            name = mode,
                            selected = active != null && mode.equals(active, ignoreCase = true),
                            contentDescription = switchContentDescription(mode),
                            onClick = { onSelect(mode) },
                        )
                    }
                }
            }
            Text(note, color = ArgusTheme.surfaces.heroSecondary, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Pulsante a pillola di una modalità: 48 dp, pieno `primary` con spunta se scelto, a contorno altrimenti. */
@Composable
fun ModeSwitchChip(
    name: String,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = modifier
            .heightIn(min = ArgusSpacing.TouchTarget)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        shape = CircleShape,
        color = if (selected) scheme.primary else scheme.secondaryContainer,
        contentColor = if (selected) scheme.onPrimary else scheme.onSecondaryContainer,
        border = if (selected) null else BorderStroke(1.dp, scheme.outline),
    ) {
        Row(
            Modifier.heightIn(min = ArgusSpacing.TouchTarget).padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.W700 else FontWeight.W600,
            )
        }
    }
}

/**
 * Riga di una variabile salvata: la chiave in monospace, il dettaglio (tipo, quando, chi) e il valore in una
 * pastiglia monospace a destra; un valore SECRET è mascherato e la pastiglia lo dice a TalkBack.
 */
@Composable
fun StoredValueRow(
    key: String,
    detail: String,
    value: String,
    masked: Boolean,
    hiddenLabel: String,
    onClickLabel: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
            .padding(horizontal = ArgusSpacing.Large, vertical = ArgusSpacing.Medium),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(key, color = MaterialTheme.colorScheme.onSurface, style = ArgusCode, fontWeight = FontWeight.W500)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        Surface(
            shape = RoundedCornerShape(ArgusRadius.Sm),
            color = ArgusTheme.surfaces.card2,
            modifier = if (masked) Modifier.semantics { contentDescription = hiddenLabel } else Modifier,
        ) {
            Text(
                if (masked) MASK else value,
                modifier = Modifier.padding(PaddingValues(horizontal = 10.dp, vertical = 6.dp)),
                color = MaterialTheme.colorScheme.onSurface,
                style = ArgusCode,
                maxLines = 3,
            )
        }
    }
}

private const val MASK = "••••••"

@ArgusThemePreviews
@Composable
private fun ModeControlsPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(ArgusSpacing.Large), verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Large)) {
            ModeHeroPanel(
                label = "Modalità attiva",
                modes = listOf("Casa", "Lavoro", "Notte", "Fuori"),
                active = "Casa",
                note = "Cambiata oggi alle 18:42 dalla regola «Arrivo a casa». Puoi cambiarla anche dal riquadro rapido e dal widget.",
                switchContentDescription = { "Passa alla modalità $it" },
                onSelect = {},
            )
            ArgusCard(contentPadding = PaddingValues(0.dp)) {
                StoredValueRow("caffe_oggi", "Numero · aggiornata oggi 08:10 da «Conta i caffè»", "2", false, "Valore nascosto", "Correggi", {})
                StoredValueRow("pin_cancello", "Testo · aggiornata ieri da te", "", true, "Valore nascosto", "Correggi", {})
            }
        }
    }
}
