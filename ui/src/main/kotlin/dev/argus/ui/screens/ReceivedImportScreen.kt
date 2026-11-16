package dev.argus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.components.ArgusActionFooter
import dev.argus.ui.components.ArgusActionRow
import dev.argus.ui.components.ArgusButton
import dev.argus.ui.components.ArgusDetailTopBar
import dev.argus.ui.components.ButtonStyle
import dev.argus.ui.components.ImportPreviewCard
import dev.argus.ui.components.ProblemLine
import dev.argus.ui.model.ImportPreviewUi
import dev.argus.ui.model.ReceivedImportCallbacks
import dev.argus.ui.model.ReceivedImportState
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// 0.7.2 · «Importa in Argus»: ciò che un'altra app ha mandato ad Argus (testo condiviso o file aperto da un
// file manager). È una superficie d'attacco: mostra cosa è arrivato (quante regole, i nomi, la dimensione) e
// non fa NULLA finché l'utente non conferma qui. Confermare crea soltanto bozze da approvare (invariante 1).
// Stateless: (state, callbacks).
// =============================================================================

@Composable
fun ReceivedImportScreen(
    state: ReceivedImportState,
    callbacks: ReceivedImportCallbacks,
    modifier: Modifier = Modifier,
) {
    Surface(color = ArgusTheme.surfaces.page, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            ArgusDetailTopBar(
                title = stringResource(R.string.import_received_title),
                onBack = callbacks::onCancel,
                backContentDescription = stringResource(R.string.import_cancel),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ArgusSpacing.Large, vertical = ArgusSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Large),
            ) {
                Text(
                    stringResource(R.string.import_received_body),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (state) {
                    ReceivedImportState.Loading -> Busy(stringResource(R.string.import_received_loading))
                    is ReceivedImportState.Ready -> {
                        ImportPreviewCard(state.preview)
                        if (state.working) Busy(stringResource(R.string.import_received_working))
                    }
                    is ReceivedImportState.Rejected -> ProblemLine(state.message)
                    is ReceivedImportState.Done -> DoneLine(state.message)
                }
            }
            ArgusActionFooter {
                ArgusActionRow {
                    when (state) {
                        ReceivedImportState.Loading, is ReceivedImportState.Ready -> {
                            ArgusButton(stringResource(R.string.import_cancel), callbacks::onCancel, style = ButtonStyle.Text)
                            val ready = state as? ReceivedImportState.Ready
                            ArgusButton(
                                stringResource(R.string.import_confirm),
                                callbacks::onConfirm,
                                icon = Icons.Rounded.FileDownload,
                                enabled = ready != null && !ready.working && ready.preview.canImport,
                            )
                        }
                        is ReceivedImportState.Rejected ->
                            ArgusButton(stringResource(R.string.import_received_close), callbacks::onCancel)
                        is ReceivedImportState.Done -> {
                            ArgusButton(stringResource(R.string.import_received_close), callbacks::onCancel, style = ButtonStyle.Text)
                            if (state.importedCount > 0) {
                                ArgusButton(stringResource(R.string.import_received_open), callbacks::onOpenArgus)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Row(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun DoneLine(text: String) {
    Row(
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = ArgusTheme.semantic.armed.fg, modifier = Modifier.size(18.dp))
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

private object NoopReceivedCallbacks : ReceivedImportCallbacks {
    override fun onConfirm() {}
    override fun onCancel() {}
    override fun onOpenArgus() {}
}

private val previewReceived = ImportPreviewUi(
    title = "2 regole · 1,4 KB",
    ruleNames = listOf("Notte tranquilla", "Batteria al 20%: risparmio"),
)

@ArgusThemePreviews
@Composable
private fun ReceivedImportReadyPreview() {
    ArgusTheme { ReceivedImportScreen(ReceivedImportState.Ready(previewReceived), NoopReceivedCallbacks) }
}

@ArgusThemePreviews
@Composable
private fun ReceivedImportRejectedPreview() {
    ArgusTheme {
        ReceivedImportScreen(ReceivedImportState.Rejected("Il file è troppo grande (limite 2 MB)."), NoopReceivedCallbacks)
    }
}

@ArgusThemePreviews
@Composable
private fun ReceivedImportDonePreview() {
    ArgusTheme {
        ReceivedImportScreen(ReceivedImportState.Done("2 regole importate da approvare.", 2), NoopReceivedCallbacks)
    }
}
