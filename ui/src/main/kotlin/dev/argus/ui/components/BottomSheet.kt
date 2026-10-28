package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Bottom sheet (tavola «Configura provider»). Fondo `sheet`, angoli 28 in alto, maniglia 32 x 4,
// scrim 64% (scuro) / 42% (chiaro), titolo 22/28 · 600. Il contenuto scorre se non entra (testo grande).
// =============================================================================

/**
 * Bottom sheet modale.
 *
 * @param title titolo del foglio (esposto a TalkBack come titolo).
 * @param content campi e controlli; mettere i pulsanti in fondo con [ArgusActionRow].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArgusBottomSheet(
    onDismiss: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = ArgusRadius.Xxl, topEnd = ArgusRadius.Xxl),
        containerColor = ArgusTheme.surfaces.sheet,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        scrimColor = ArgusTheme.surfaces.scrim,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(start = ArgusSpacing.Large, end = ArgusSpacing.Large, bottom = ArgusSpacing.Section),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                title,
                modifier = Modifier.padding(top = 2.dp).semantics { heading() },
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
            )
            content()
        }
    }
}

/** Maniglia del foglio: 32 x 4, `outline`. Decorativa. */
@Composable
private fun SheetHandle() {
    Box(Modifier.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(MaterialTheme.colorScheme.outline))
    }
}

/**
 * Fila di azioni in fondo a un foglio o a un pannello: allineate a destra, 8 dp tra loro, ordine
 * «annulla» (testo) poi «conferma» (pieno). Un solo pulsante pieno.
 */
@Composable
fun ArgusActionRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// Un ModalBottomSheet non si disegna in un'anteprima statica: qui si mostra il contenuto sul fondo `sheet`.
@ArgusThemePreviews
@Composable
private fun BottomSheetContentPreview() {
    ArgusPreviewSurface {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = ArgusRadius.Xxl, topEnd = ArgusRadius.Xxl))
                .background(ArgusTheme.surfaces.sheet)
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SheetHandle()
            Text("Configura OpenRouter", color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
            ArgusTextField("google/gemini-2.5-flash", {}, "Modello", monospace = true)
            ArgusActionRow {
                ArgusButton("Annulla", {}, style = ButtonStyle.Text)
                ArgusButton("Salva e verifica", {})
            }
        }
    }
}
