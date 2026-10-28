package dev.argus.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.LocalArgusSemantic

// =============================================================================
// «Pausa automazioni» (0.4.1, F21): banner nella lista e riga con interruttore in
// Impostazioni. Stateless; colori solo dai token (ambra = attenzione, come i banner salute).
// =============================================================================

/** Banner «Automazioni in pausa · Riprendi» in cima alla lista Automazioni. */
@Composable
fun PauseBanner(onResume: () -> Unit, modifier: Modifier = Modifier) {
    val role = LocalArgusSemantic.current.pending
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(role.bg)
            .heightIn(min = 48.dp)
            .padding(start = 14.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(Icons.Rounded.PauseCircle, contentDescription = null, tint = role.fg, modifier = Modifier.size(20.dp))
        Text(
            stringResource(R.string.pause_banner_text),
            color = role.fg,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onResume, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(stringResource(R.string.pause_banner_resume), color = role.fg)
        }
    }
}

/** Riga di Impostazioni con l'interruttore globale della pausa e la spiegazione dello stato corrente. */
@Composable
fun PauseSettingRow(
    paused: Boolean,
    onPausedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = stringResource(R.string.pause_setting_title)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(14.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            Icons.Rounded.PauseCircle,
            contentDescription = null,
            tint = if (paused) LocalArgusSemantic.current.pending.fg else MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
            Text(
                stringResource(if (paused) R.string.pause_setting_on else R.string.pause_setting_off),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Switch(
            checked = paused,
            onCheckedChange = onPausedChange,
            modifier = Modifier.semantics { contentDescription = title },
            colors = SwitchDefaults.colors(
                checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                checkedTrackColor = MaterialTheme.colorScheme.primary,
            ),
        )
    }
}

@Preview(name = "Pausa · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)

@Preview(name = "Pausa · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun PauseControlsPreview() {
    ArgusTheme {
        Surface(color = MaterialTheme.colorScheme.background) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PauseBanner(onResume = {})
                PauseSettingRow(paused = true, onPausedChange = {})
                PauseSettingRow(paused = false, onPausedChange = {})
            }
        }
    }
}
