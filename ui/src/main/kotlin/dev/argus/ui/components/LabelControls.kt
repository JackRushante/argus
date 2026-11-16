package dev.argus.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Ricerca ed etichette (0.7.2, tavola «Automazioni con ricerca ed etichette»).
//  - ArgusSearchField: pillola da 48 dp su `field`, lente a sinistra, «Cancella» a destra quando c'e' testo.
//  - LabelFilterChip: filtro per etichetta, pillola da 48 dp; scelta = `primary` pieno, altrimenti contorno.
//  - LabelGroupCard: intestazione del gruppo scelto su `secondaryContainer` (come il riepilogo), con
//    «Pausa gruppo» / «Riprendi gruppo» a contorno.
//  - LabelTag: etichetta di una regola nella riga (chip a contorno); in pausa l'icona lo dice.
//  - RemovableLabelChip: etichetta nel dettaglio con il suo «togli» da 48 dp.
// Colori, raggi e testo solo dai token del tema.
// =============================================================================

/** Campo di ricerca della lista: filtra mentre si scrive, niente invio. [clearDescription] per la «x». */
@Composable
fun ArgusSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    clearDescription: String,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = placeholder },
        textStyle = textStyle,
        cursorBrush = SolidColor(scheme.primary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = {}),
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = ArgusSpacing.TouchTarget)
                    .clip(CircleShape)
                    .background(ArgusTheme.surfaces.field)
                    .padding(start = ArgusSpacing.Large, end = ArgusSpacing.Unit),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text(placeholder, color = scheme.onSurfaceVariant, style = textStyle.copy(color = scheme.onSurfaceVariant))
                    }
                    inner()
                }
                if (value.isNotEmpty()) {
                    ArgusIconButton(
                        icon = Icons.Rounded.Close,
                        contentDescription = clearDescription,
                        onClick = { onValueChange("") },
                        tint = scheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

/**
 * Filtro per etichetta («Tutte · 9», «Casa · 4»): scelta esclusiva. [contentDescription] la legge TalkBack
 * («Casa, 4 regole, in pausa»); il simbolo di pausa non e' l'unico segnale.
 */
@Composable
fun LabelFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val content = if (selected) scheme.onPrimary else scheme.onSurface
    Row(
        modifier = modifier
            .heightIn(min = ArgusSpacing.TouchTarget)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(scheme.primary) else Modifier.border(1.dp, scheme.outline, CircleShape))
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { this.contentDescription = contentDescription }
            .padding(horizontal = ArgusSpacing.Large),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (paused) Icon(Icons.Rounded.PauseCircle, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
        Text(
            text,
            color = content,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.W700 else FontWeight.W600,
        )
    }
}

/**
 * Intestazione del gruppo scelto: «Etichetta «Casa»», «3 attive · 1 da approvare» e il pulsante della pausa.
 * È l'unico controllo della pausa di gruppo: niente pulsante pieno (la lista non ne ha).
 */
@Composable
fun LabelGroupCard(
    title: String,
    subtitle: String,
    paused: Boolean,
    pauseLabel: String,
    resumeLabel: String,
    onTogglePause: () -> Unit,
    modifier: Modifier = Modifier,
    pausedDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val surfaces = ArgusTheme.surfaces
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(ArgusRadius.Xxl))
            .background(scheme.secondaryContainer)
            .padding(start = ArgusSpacing.Large, end = ArgusSpacing.Small, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) {
                    if (pausedDescription != null) stateDescription = pausedDescription
                },
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, color = scheme.onSecondaryContainer, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, color = surfaces.heroSecondary, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = onTogglePause,
            modifier = Modifier.heightIn(min = ArgusSpacing.TouchTarget),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = scheme.onSecondaryContainer),
            border = BorderStroke(1.dp, surfaces.heroSecondary),
            elevation = null,
            contentPadding = PaddingValues(start = 14.dp, end = ArgusSpacing.Large),
        ) {
            Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(
                if (paused) resumeLabel else pauseLabel,
                modifier = Modifier.padding(start = ArgusSpacing.Small),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.W700,
            )
        }
    }
}

/** Etichetta di una regola nella riga della lista. In pausa: icona di pausa e [pausedDescription] per TalkBack. */
@Composable
fun LabelTag(
    text: String,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    pausedDescription: String? = null,
) {
    OutlineChip(
        icon = if (paused) Icons.Rounded.PauseCircle else Icons.AutoMirrored.Rounded.Label,
        text = text,
        modifier = modifier.then(
            if (paused && pausedDescription != null) Modifier.semantics { stateDescription = pausedDescription } else Modifier,
        ),
    )
}

/** Etichetta nel dettaglio con il tasto per toglierla (48 dp, descrizione «Togli l'etichetta Casa»). */
@Composable
fun RemovableLabelChip(
    text: String,
    removeDescription: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
    paused: Boolean = false,
    pausedDescription: String? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .border(1.dp, scheme.outline, CircleShape)
            .padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.semantics(mergeDescendants = true) {
                if (paused && pausedDescription != null) stateDescription = pausedDescription
            },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (paused) Icons.Rounded.PauseCircle else Icons.AutoMirrored.Rounded.Label,
                contentDescription = null,
                tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
            Text(text, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        Box(
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .selectable(selected = false, role = Role.Button, onClick = onRemove)
                .semantics { contentDescription = removeDescription },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Close, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@ArgusThemePreviews
@Composable
private fun LabelControlsPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(ArgusSpacing.Large), verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Large)) {
            var query by remember { mutableStateOf("") }
            ArgusSearchField(query, { query = it }, placeholder = "Cerca per nome, app o azione", clearDescription = "Cancella")
            ArgusSearchField("wifi", {}, placeholder = "Cerca per nome, app o azione", clearDescription = "Cancella")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small), verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
                LabelFilterChip("Tutte · 9", selected = false, onClick = {}, contentDescription = "Tutte, 9 regole")
                LabelFilterChip("Casa · 4", selected = true, onClick = {}, contentDescription = "Casa, 4 regole")
                LabelFilterChip("Auto · 2", selected = false, onClick = {}, contentDescription = "Auto, 2 regole", paused = true)
            }
            LabelGroupCard("Etichetta «Casa»", "3 attive · 1 da approvare", paused = false, "Pausa gruppo", "Riprendi gruppo", {})
            LabelGroupCard("Etichetta «Auto»", "In pausa · 2 attive", paused = true, "Pausa gruppo", "Riprendi gruppo", {})
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LabelTag("Casa")
                LabelTag("Auto", paused = true)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
                RemovableLabelChip("Casa", removeDescription = "Togli Casa", onRemove = {})
                RemovableLabelChip("Auto", removeDescription = "Togli Auto", onRemove = {}, paused = true)
            }
        }
    }
}
