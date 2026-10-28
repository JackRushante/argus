package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusCode
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Campi di testo (tavola «Configura provider»). Etichetta SOPRA il campo (12 sp, 600), campo alto 52 dp,
// raggio 14, fondo `field`; in focus una linea di 2 dp `primary` in basso; in errore la linea e il messaggio
// sono `error`; di sola lettura / disabilitato fondo `card2` con lucchetto.
// L'etichetta sta dentro la decorazione del campo, quindi TalkBack la legge insieme al valore.
// Una chiave segreta non e' mai ripresa dal campo: il chiamante lo tiene vuoto (invariante 8 del design).
// =============================================================================

/**
 * Campo di testo Argus.
 *
 * @param monospace per endpoint, modelli, chiavi, identificatori: JetBrains Mono, distingue 0/O e 1/l/I.
 * @param errorText se non nullo il campo e' in errore e il messaggio sta sotto (sostituisce [supportingText]).
 * @param labelAccessory elemento accanto all'etichetta (es. il chip «Chiave configurata»).
 * @param trailing elemento a fine campo (es. mostra/nascondi). Con campo disabilitato o di sola lettura e
 *   senza [trailing] compare un lucchetto decorativo.
 * @param visualTransformation `PasswordVisualTransformation()` per le chiavi.
 */
@Composable
fun ArgusTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    errorText: String? = null,
    monospace: Boolean = false,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    singleLine: Boolean = true,
    labelAccessory: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val scheme = MaterialTheme.colorScheme
    val surfaces = ArgusTheme.surfaces
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val isError = errorText != null
    val locked = !enabled || readOnly
    val container = if (locked) surfaces.card2 else surfaces.field
    val textColor = if (locked) scheme.onSurfaceVariant else scheme.onSurface
    val indicator = when {
        isError -> ArgusTheme.semantic.error.fg
        focused && !locked -> scheme.primary
        else -> null
    }
    val textStyle = (if (monospace) ArgusCode else MaterialTheme.typography.bodyLarge).copy(color = textColor)
    val shape = RoundedCornerShape(ArgusRadius.Md)

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        cursorBrush = SolidColor(scheme.primary),
        singleLine = singleLine,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        interactionSource = interaction,
        decorationBox = { inner ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
                    Text(label, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    if (labelAccessory != null) labelAccessory()
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .clip(shape)
                        .background(container)
                        .drawBehind {
                            if (indicator != null) {
                                val h = 2.dp.toPx()
                                drawRect(indicator, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                            }
                        }
                        .padding(horizontal = ArgusSpacing.Large, vertical = ArgusSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
                ) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty() && placeholder != null) {
                            Text(placeholder, color = scheme.onSurfaceVariant, style = textStyle.copy(color = scheme.onSurfaceVariant))
                        }
                        inner()
                    }
                    when {
                        trailing != null -> trailing()
                        locked -> Icon(Icons.Rounded.Lock, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                val below = errorText ?: supportingText
                if (below != null) {
                    Row(
                        modifier = Modifier.padding(horizontal = ArgusSpacing.Unit),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (isError) {
                            Icon(Icons.Rounded.Error, contentDescription = null, tint = ArgusTheme.semantic.error.fg, modifier = Modifier.size(16.dp))
                        }
                        Text(
                            below,
                            color = if (isError) ArgusTheme.semantic.error.fg else scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
    )
}

@ArgusThemePreviews
@Composable
private fun FieldsPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ArgusTextField("google/gemini-2.5-flash", {}, "Modello", monospace = true)
            ArgusTextField("", {}, "Chiave API", placeholder = "sk-or-…", monospace = true,
                supportingText = "Lascia vuoto per conservare la chiave attuale.",
                labelAccessory = { StatusChip(ArgusTheme.semantic.armed, Icons.Rounded.Lock, "Chiave configurata") })
            ArgusTextField("https://openrouter.ai/api/v1", {}, "Endpoint", monospace = true, enabled = false)
            ArgusTextField("not-a-url", {}, "Endpoint", monospace = true, errorText = "Indirizzo non valido")
            ArgusTextField("Chiedi una regola", {}, "Messaggio")
        }
    }
}
