package dev.argus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Top bar. Nessuna ombra e nessun colore proprio: stanno sul fondo `page`. Non aggiungono gli inset
// delle barre di sistema: li mette lo Scaffold dell'host (come per le schermate di oggi).
// =============================================================================

/**
 * Titolo grande delle schermate di primo livello (Automazioni, Log, Sistema): `headlineMedium` 28/36 · 700,
 * alto almeno 64 dp, margine 16. [actions] a destra (pulsanti icona 48 dp).
 */
@Composable
fun ArgusLargeTopBar(
    title: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .padding(start = ArgusSpacing.Large, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.headlineMedium,
        )
        actions()
    }
}

/**
 * Barra delle schermate di dettaglio: freccia indietro 48 dp + titolo breve («Dettaglio») in `onSurfaceVariant`,
 * mentre il titolo vero della regola sta nel corpo (`titleLarge`). Alta almeno 56 dp.
 *
 * @param backContentDescription testo per TalkBack della freccia («Indietro»).
 */
@Composable
fun ArgusDetailTopBar(
    title: String,
    onBack: () -> Unit,
    backContentDescription: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 56.dp)
            .padding(start = 4.dp, end = ArgusSpacing.Large),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ArgusIconButton(Icons.AutoMirrored.Rounded.ArrowBack, backContentDescription, onBack, tint = MaterialTheme.colorScheme.onSurface)
        Text(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.titleSmall,
        )
        actions()
    }
}

/**
 * Barra della chat: [leading] (logo), titolo 20/26 · 700 e sottotitolo 13/18 `onSurfaceVariant`, [actions] a destra.
 * Alta almeno 64 dp.
 */
@Composable
fun ArgusChatTopBar(
    title: String,
    subtitle: String?,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 64.dp)
            .padding(start = ArgusSpacing.Large, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
    ) {
        if (leading != null) leading()
        Column(Modifier.weight(1f)) {
            Text(
                title,
                modifier = Modifier.semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.W700),
            )
            if (subtitle != null) {
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        actions()
    }
}

@ArgusThemePreviews
@Composable
private fun TopBarsPreview() {
    ArgusPreviewSurface {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            ArgusLargeTopBar("Automazioni")
            ArgusDetailTopBar("Dettaglio", onBack = {}, backContentDescription = "Indietro")
            ArgusChatTopBar(
                title = "Argus",
                subtitle = "chat · compilatore di regole",
                leading = { Icon(Icons.Rounded.Shield, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                actions = { ArgusIconButton(Icons.Rounded.MoreVert, "Altro", onClick = {}) },
            )
            ArgusLargeTopBar("Log esecuzioni", actions = { ArgusIconButton(Icons.Rounded.MoreVert, "Altro", onClick = {}) })
        }
    }
}
