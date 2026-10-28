package dev.argus.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Pulsanti (tavola «Footer di approvazione», «Configura provider»).
// Regola: UN solo pulsante pieno (Filled) per stato di una schermata. Le azioni distruttive hanno
// contorno e testo rossi e aprono sempre una conferma. Un pulsante disabilitato dice PERCHE':
// il motivo e' testo visibile, mai un tooltip.
// =============================================================================

/** Stile di un [ArgusButton]. */
enum class ButtonStyle {
    /** L'azione principale (pieno, `primary`). Uno solo per stato. */
    Filled,
    /** Azione importante ma non principale (`secondaryContainer`). */
    Tonal,
    /** Azione secondaria (contorno `outline`). */
    Outlined,
    /** Azione terziaria / annulla (solo testo `primary`). */
    Text,
    /** Azione distruttiva: contorno e testo `error`. Apre sempre una conferma. */
    Destructive,
}

@Composable
private fun buttonColors(style: ButtonStyle): ButtonColors {
    val scheme = MaterialTheme.colorScheme
    val surfaces = ArgusTheme.surfaces
    val semantic = ArgusTheme.semantic
    val none = Color.Transparent
    return when (style) {
        ButtonStyle.Filled -> ButtonDefaults.buttonColors(
            containerColor = scheme.primary, contentColor = scheme.onPrimary,
            disabledContainerColor = surfaces.disabledContainer, disabledContentColor = surfaces.disabledContent,
        )
        ButtonStyle.Tonal -> ButtonDefaults.buttonColors(
            containerColor = scheme.secondaryContainer, contentColor = scheme.onSecondaryContainer,
            disabledContainerColor = surfaces.disabledContainer, disabledContentColor = surfaces.disabledContent,
        )
        ButtonStyle.Outlined -> ButtonDefaults.buttonColors(
            containerColor = none, contentColor = scheme.onSurface,
            disabledContainerColor = none, disabledContentColor = surfaces.disabledContent,
        )
        ButtonStyle.Text -> ButtonDefaults.buttonColors(
            containerColor = none, contentColor = scheme.primary,
            disabledContainerColor = none, disabledContentColor = surfaces.disabledContent,
        )
        ButtonStyle.Destructive -> ButtonDefaults.buttonColors(
            containerColor = none, contentColor = semantic.error.fg,
            disabledContainerColor = none, disabledContentColor = surfaces.disabledContent,
        )
    }
}

@Composable
private fun buttonBorder(style: ButtonStyle, enabled: Boolean): BorderStroke? {
    val surfaces = ArgusTheme.surfaces
    return when (style) {
        ButtonStyle.Outlined ->
            BorderStroke(1.dp, if (enabled) MaterialTheme.colorScheme.outline else surfaces.disabledContainer)
        ButtonStyle.Destructive ->
            BorderStroke(1.5.dp, if (enabled) ArgusTheme.semantic.error.fg else surfaces.disabledContainer)
        ButtonStyle.Filled, ButtonStyle.Tonal, ButtonStyle.Text -> null
    }
}

/**
 * Pulsante Argus: pillola alta almeno 48 dp (52 nei footer), testo che va a capo con la scala del testo.
 *
 * @param disabledReason motivo per cui l'azione non e' disponibile. Se [enabled] e' `false` e il motivo c'e', viene
 *   scritto sotto il pulsante (visibile, non un tooltip) ed e' letto da TalkBack come stato del pulsante.
 *   Nei footer, dove il motivo sta SOPRA la fila di pulsanti, usare [ArgusActionFooter].
 * @param fillWidth se `true` il pulsante occupa tutta la larghezza disponibile.
 */
@Composable
fun ArgusButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: ButtonStyle = ButtonStyle.Filled,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    disabledReason: String? = null,
    fillWidth: Boolean = false,
    minHeight: Dp = ArgusSpacing.TouchTarget,
) {
    val emphatic = style == ButtonStyle.Filled || style == ButtonStyle.Tonal
    val showReason = !enabled && !disabledReason.isNullOrBlank()
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Button(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier
                .heightIn(min = minHeight)
                .then(if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                .then(
                    if (showReason) Modifier.semantics { stateDescription = disabledReason!! } else Modifier,
                ),
            shape = CircleShape,
            colors = buttonColors(style),
            border = buttonBorder(style, enabled),
            elevation = null,
            contentPadding = if (icon != null) PaddingValues(start = 14.dp, end = 18.dp) else PaddingValues(horizontal = 20.dp),
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (emphatic) FontWeight.W700 else FontWeight.W600,
                textAlign = TextAlign.Center,
            )
        }
        if (showReason) ReasonText(disabledReason!!, Modifier.padding(top = ArgusSpacing.Small, start = ArgusSpacing.Unit))
    }
}

/** Motivo di un'azione non disponibile: 13 sp, `onSurfaceVariant`, sempre visibile. */
@Composable
fun ReasonText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

/**
 * Pulsante icona con area tattile 48 x 48. [contentDescription] e' obbligatorio: un'icona senza testo
 * deve dire cosa fa.
 */
@Composable
fun ArgusIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = modifier.size(ArgusSpacing.TouchTarget)) {
        Icon(icon, contentDescription = contentDescription, tint = if (enabled) tint else ArgusTheme.surfaces.disabledContent)
    }
}

/**
 * Interruttore (solo armata / disattivata): binario 52 x 32, pollice 24 con segno di spunta se acceso,
 * 16 con contorno `outline` se spento. [contentDescription] dice l'azione ("Disattiva X" / "Riattiva X").
 */
@Composable
fun ArgusSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    contentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val scheme = MaterialTheme.colorScheme
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier.semantics { this.contentDescription = contentDescription },
        enabled = enabled,
        thumbContent = if (checked) {
            { Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
        } else null,
        colors = SwitchDefaults.colors(
            checkedThumbColor = scheme.onPrimary,
            checkedTrackColor = scheme.primary,
            checkedBorderColor = scheme.primary,
            checkedIconColor = scheme.primary,
            uncheckedThumbColor = scheme.outline,
            uncheckedTrackColor = ArgusTheme.surfaces.field,
            uncheckedBorderColor = scheme.outline,
        ),
    )
}

@ArgusThemePreviews
@Composable
private fun ButtonsPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            ArgusButton("Arma", {}, icon = Icons.Rounded.Shield, style = ButtonStyle.Filled)
            ArgusButton("Disattiva automazione", {}, style = ButtonStyle.Tonal, fillWidth = true)
            ArgusButton("Modifica in chat", {}, style = ButtonStyle.Outlined)
            ArgusButton("Annulla", {}, style = ButtonStyle.Text)
            ArgusButton("Elimina", {}, icon = Icons.Rounded.Delete, style = ButtonStyle.Destructive)
            ArgusButton(
                "Arma", {}, enabled = false, style = ButtonStyle.Filled,
                disabledReason = "Arma bloccato: tool 'shell.run' vietato nelle regole generative",
            )
            ArgusButton("Esegui ora", {}, enabled = false, style = ButtonStyle.Outlined, disabledReason = "Non ancora disponibile.")
            ArgusButton("Elimina", {}, enabled = false, style = ButtonStyle.Destructive)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                ArgusSwitch(checked = true, onCheckedChange = {}, contentDescription = "Disattiva")
                ArgusSwitch(checked = false, onCheckedChange = {}, contentDescription = "Riattiva")
                ArgusSwitch(checked = true, onCheckedChange = {}, contentDescription = "Disattiva", enabled = false)
            }
        }
    }
}
