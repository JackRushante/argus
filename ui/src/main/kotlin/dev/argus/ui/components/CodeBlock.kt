package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusCodeSmall
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Blocco di codice monospace (tavola «Blocco comando shell · ShellCommandBlock»).
// Invariante 6: i comandi shell sono sempre in monospace, INTEGRALI, mai troncati. Il blocco quindi:
//  - va a capo, anche dentro i token lunghi (nessuno scroll orizzontale nascosto);
//  - non ha maxLines, ellissi ne' «mostra di piu'»;
//  - usa JetBrains Mono senza ligature (`->` resta `->`).
// =============================================================================

/** Provenienza di un frammento di un comando: dato scritto nella regola o valore interpolato a runtime. */
enum class CodeTone {
    /** Testo della regola. */
    Plain,
    /** Variabile interpolata con un valore ESTERNO (non attendibile): evidenziata in ambra. */
    External,
    /** Variabile interpolata con un valore locale / attendibile: evidenziata sul fondo `field`. */
    Local,
}

/** Un frammento di un blocco di codice con il suo [tone]. */
data class CodePart(val text: String, val tone: CodeTone = CodeTone.Plain)

/**
 * Blocco di codice su `codeSurface`, raggio 12, testo `codeText`.
 *
 * @param label riga sopra il codice (es. «esegue comandi con privilegi shell (UID 2000)»), con [labelIcon].
 * @param borderColor contorno sottile opzionale (la tavola usa `attention` al 35% per i comandi shell).
 */
@Composable
fun CodeBlock(
    parts: List<CodePart>,
    modifier: Modifier = Modifier,
    label: String? = null,
    labelIcon: ImageVector? = null,
    labelColor: Color = ArgusTheme.semantic.attention,
    borderColor: Color? = null,
    style: TextStyle = ArgusCodeSmall,
) {
    val semantic = ArgusTheme.semantic
    val surfaces = ArgusTheme.surfaces
    val onSurface = MaterialTheme.colorScheme.onSurface
    val annotated: AnnotatedString = remember(parts, semantic, surfaces, onSurface) {
        buildAnnotatedString {
            parts.forEach { part ->
                when (part.tone) {
                    CodeTone.Plain -> append(part.text)
                    CodeTone.External -> withStyle(SpanStyle(background = semantic.pending.bg, color = semantic.pending.fg)) { append(part.text) }
                    CodeTone.Local -> withStyle(SpanStyle(background = surfaces.field, color = onSurface)) { append(part.text) }
                }
            }
        }
    }
    val shape = RoundedCornerShape(12.dp)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (label != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (labelIcon != null) Icon(labelIcon, contentDescription = null, tint = labelColor, modifier = Modifier.size(16.dp))
                Text(label, color = labelColor, style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(
            text = annotated,
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(surfaces.codeSurface)
                .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
                .padding(horizontal = ArgusSpacing.Medium, vertical = 10.dp),
            color = semantic.codeText,
            style = style,
            softWrap = true,
            overflow = TextOverflow.Visible,
        )
    }
}

/** Blocco di codice con un solo testo semplice (nessuna variabile interpolata). Vedi la versione a frammenti. */
@Composable
fun CodeBlock(
    text: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    labelIcon: ImageVector? = null,
    labelColor: Color = ArgusTheme.semantic.attention,
    borderColor: Color? = null,
    style: TextStyle = ArgusCodeSmall,
) {
    val parts = remember(text) { listOf(CodePart(text)) }
    CodeBlock(parts, modifier, label, labelIcon, labelColor, borderColor, style)
}

@ArgusThemePreviews
@Composable
private fun CodeBlockPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CodeBlock(
                text = "rsync -a --delete /sdcard/DCIM/Camera/ /mnt/nas/immich/foto/",
                label = "esegue comandi con privilegi shell (UID 2000)",
                labelIcon = Icons.Rounded.Terminal,
                borderColor = ArgusTheme.semantic.attention.copy(alpha = 0.35f),
            )
            CodeBlock(
                parts = listOf(
                    CodePart("am broadcast -a dev.argus.example.OTP_FORWARD --es code \""),
                    CodePart("{{otp}}", CodeTone.External),
                    CodePart("\" --es batteria \""),
                    CodePart("{{battery}}", CodeTone.Local),
                    CodePart("\" && log -t argus \"otp-inoltrato-con-un-token-molto-lungo-senza-spazi-che-deve-andare-a-capo\""),
                ),
                label = "esegue comandi con privilegi shell (UID 2000)",
                labelIcon = Icons.Rounded.Terminal,
                borderColor = ArgusTheme.semantic.attention.copy(alpha = 0.35f),
            )
        }
    }
}
