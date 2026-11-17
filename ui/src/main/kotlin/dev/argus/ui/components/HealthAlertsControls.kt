package dev.argus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationImportant
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// «Avvisami quando una regola smette di funzionare» (0.7.2): riga di Sistema con interruttore, stesso
// disegno della riga «Pausa automazioni». Stateless; colori solo dai token.
// =============================================================================

@Composable
fun HealthAlertsSettingRow(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.health_alerts_setting_title)
    val scheme = MaterialTheme.colorScheme
    ArgusCard(modifier.fillMaxWidth(), contentPadding = PaddingValues(14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(
                Icons.Rounded.NotificationImportant,
                if (enabled) scheme.secondaryContainer else ArgusTheme.surfaces.card2,
                if (enabled) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                size = 40.dp,
                iconSize = 22.dp,
                shape = RoundedCornerShape(ArgusRadius.Md - 2.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, color = scheme.onSurface, style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(if (enabled) R.string.health_alerts_setting_on else R.string.health_alerts_setting_off),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            ArgusSwitch(checked = enabled, onCheckedChange = onEnabledChange, contentDescription = title)
        }
    }
}

@ArgusThemePreviews
@Composable
private fun HealthAlertsSettingRowPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            HealthAlertsSettingRow(enabled = true, onEnabledChange = {})
            HealthAlertsSettingRow(enabled = false, onEnabledChange = {})
        }
    }
}
