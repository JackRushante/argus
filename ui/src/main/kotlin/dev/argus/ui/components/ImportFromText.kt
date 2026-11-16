package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.model.ImportPreviewUi
import dev.argus.ui.model.TextImportPreview
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// 0.7.2 · Importare una regola da un testo. L'anteprima dice cosa entrerebbe (quante regole, i loro nomi, la
// dimensione, le voci scartate) PRIMA di confermare; i nomi vengono dal testo ricevuto e restano testo
// semplice. «Importa» è l'unico pulsante pieno ed è attivo solo con almeno una regola valida: ogni regola
// diventa una bozza da approvare, nessuna si arma da sola.
// =============================================================================

/** Anteprima di un import: titolo con conteggio e dimensione, nomi delle regole, voci scartate. */
@Composable
fun ImportPreviewCard(preview: ImportPreviewUi, modifier: Modifier = Modifier) {
    ArgusCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
            Text(preview.title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
            preview.ruleNames.forEach { name ->
                Text(
                    "• " + name.ifBlank { stringResource(R.string.import_unnamed) },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            preview.moreText?.let {
                Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            preview.discardedText?.let { ProblemLine(it) }
            Text(
                stringResource(R.string.backup_import_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Una riga di problema: icona e testo, il colore non è l'unico segnale. */
@Composable
internal fun ProblemLine(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.Error, contentDescription = null, tint = ArgusTheme.semantic.error.fg, modifier = Modifier.size(18.dp))
        Text(text, color = ArgusTheme.semantic.error.fg, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Stato dell'anteprima del testo incollato. */
@Composable
fun TextImportPreviewView(preview: TextImportPreview, modifier: Modifier = Modifier) {
    when (preview) {
        TextImportPreview.Empty -> Text(
            stringResource(R.string.import_text_empty),
            modifier = modifier,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        TextImportPreview.Checking -> Row(
            modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.import_checking),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is TextImportPreview.Ready -> ImportPreviewCard(preview.preview, modifier)
        is TextImportPreview.Invalid -> ProblemLine(preview.message, modifier)
    }
}

/**
 * Foglio «Importa da testo». Il testo resta nel foglio (mai salvato nello stato dell'Activity: può contenere
 * dati personali); a ogni cambio il chiamante ne prepara l'anteprima. [onPickFile] aggiunge «Scegli un file».
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportTextSheet(
    preview: TextImportPreview,
    onTextChange: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
    onPickFile: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf("") }
    ArgusBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.import_text_title)) {
        ImportTextContent(
            text = text,
            preview = preview,
            onTextChange = {
                text = it
                onTextChange(it)
            },
            onConfirm = { onConfirm(text) },
            onDismiss = onDismiss,
            onPickFile = onPickFile,
        )
    }
}

@Composable
private fun ImportTextContent(
    text: String,
    preview: TextImportPreview,
    onTextChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    onPickFile: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Large)) {
        Text(
            stringResource(R.string.import_text_hint),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        ArgusTextField(
            value = text,
            onValueChange = onTextChange,
            label = stringResource(R.string.import_text_label),
            monospace = true,
            singleLine = false,
            modifier = Modifier.heightIn(max = 220.dp),
        )
        TextImportPreviewView(preview)
        if (onPickFile != null) {
            ArgusButton(
                text = stringResource(R.string.import_pick_file),
                onClick = onPickFile,
                style = ButtonStyle.Outlined,
                icon = Icons.Rounded.FolderOpen,
                fillWidth = true,
            )
        }
        ArgusActionRow {
            ArgusButton(stringResource(R.string.import_cancel), onDismiss, style = ButtonStyle.Text)
            ArgusButton(
                stringResource(R.string.import_confirm),
                onConfirm,
                enabled = (preview as? TextImportPreview.Ready)?.preview?.canImport == true,
            )
        }
    }
}

@ArgusThemePreviews
@Composable
private fun ImportTextContentPreview() {
    ArgusPreviewSurface {
        Column(Modifier.background(ArgusTheme.surfaces.sheet).padding(ArgusSpacing.Large)) {
            ImportTextContent(
                text = """{"format":"argus-rules","version":1,"rules":[…]}""",
                preview = TextImportPreview.Ready(
                    ImportPreviewUi(
                        title = "2 regole · 1,4 KB",
                        ruleNames = listOf("Notte tranquilla", "Batteria al 20%: risparmio"),
                        discardedText = "1 voce verrà scartata (regola non valida).",
                    ),
                ),
                onTextChange = {},
                onConfirm = {},
                onDismiss = {},
                onPickFile = {},
            )
        }
    }
}

@ArgusThemePreviews
@Composable
private fun ImportTextContentInvalidPreview() {
    ArgusPreviewSurface {
        Column(Modifier.background(ArgusTheme.surfaces.sheet).padding(ArgusSpacing.Large)) {
            ImportTextContent(
                text = "ciao",
                preview = TextImportPreview.Invalid("Questo testo non è una regola Argus."),
                onTextChange = {},
                onConfirm = {},
                onDismiss = {},
                onPickFile = null,
            )
        }
    }
}
