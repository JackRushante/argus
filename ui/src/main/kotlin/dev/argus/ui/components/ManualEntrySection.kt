package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddToHomeScreen
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Dashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.model.ManualEntryUi
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.LocalArgusSemantic

// =============================================================================
// Avvio manuale (0.4.1, F8): le porte d'ingresso di una regola `manual` armata che il
// Dettaglio offre oltre a «Esegui ora» (che sta nel footer). Stateless.
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManualEntrySection(
    entry: ManualEntryUi,
    onAddToHome: () -> Unit,
    onUseInTile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val semantic = LocalArgusSemantic.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(R.string.manual_section_title),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(
            stringResource(R.string.manual_section_body),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (entry.paused) {
            Text(
                stringResource(R.string.manual_paused_note),
                color = semantic.pending.fg,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entry.canPinShortcut) {
                OutlinedButton(onClick = onAddToHome, modifier = Modifier.heightIn(min = 48.dp)) {
                    Icon(Icons.Rounded.AddToHomeScreen, contentDescription = null, modifier = Modifier.size(19.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.manual_add_home))
                }
            }
            OutlinedButton(
                onClick = onUseInTile,
                enabled = !entry.isTileRule,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(
                    if (entry.isTileRule) Icons.Rounded.CheckCircle else Icons.Rounded.Dashboard,
                    contentDescription = null,
                    modifier = Modifier.size(19.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(
                        if (entry.isTileRule) R.string.manual_tile_selected else R.string.manual_use_tile,
                    ),
                )
            }
        }
        if (entry.isTileRule) {
            Text(
                stringResource(R.string.manual_tile_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Preview(name = "Avvio manuale (dark)", showBackground = true, backgroundColor = 0xFF0E1216)
@Composable
private fun ManualEntrySectionPreview() {
    ArgusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                ManualEntrySection(ManualEntryUi(), onAddToHome = {}, onUseInTile = {})
                ManualEntrySection(
                    ManualEntryUi(canPinShortcut = false, isTileRule = true, paused = true),
                    onAddToHome = {},
                    onUseInTile = {},
                )
            }
        }
    }
}
