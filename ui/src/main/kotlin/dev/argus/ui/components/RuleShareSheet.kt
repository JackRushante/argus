package dev.argus.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.model.RuleShareText
import dev.argus.ui.model.RuleShareUi
import dev.argus.ui.presentation.QrCodes
import dev.argus.ui.presentation.QrMatrix
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusLightColors
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Foglio «Condividi regola» (0.7.2, tavola «Condividi»). L'interruttore «Togli i dati personali» parte
// acceso: il testo condiviso è quello senza dati personali finché l'utente non lo spegne. Il codice QR,
// «Condividi come testo», «Salva file» e «Copia» usano sempre la versione scelta. Un solo pulsante pieno.
// Il codice QR resta scuro su chiaro anche nel tema scuro: le fotocamere leggono quello.
// =============================================================================

/** Lato del codice QR disegnato, margine compreso. */
private val QrSide = 208.dp

/** Margine chiaro attorno al codice, in moduli (lo standard ne chiede 4). */
private const val QR_QUIET_MODULES = 4

@Composable
fun RuleShareSheet(
    share: RuleShareUi,
    onDismiss: () -> Unit,
    onShareAsText: (stripPersonalData: Boolean) -> Unit,
    onSaveFile: (stripPersonalData: Boolean) -> Unit,
    onCopy: (stripPersonalData: Boolean) -> Unit,
) {
    var strip by rememberSaveable { mutableStateOf(true) }
    ArgusBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.share_title)) {
        RuleShareContent(share, strip, onStripChange = { strip = it }, onShareAsText, onSaveFile, onCopy)
    }
}

@Composable
private fun RuleShareContent(
    share: RuleShareUi,
    strip: Boolean,
    onStripChange: (Boolean) -> Unit,
    onShareAsText: (Boolean) -> Unit,
    onSaveFile: (Boolean) -> Unit,
    onCopy: (Boolean) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Large)) {
        Text(
            stringResource(R.string.share_subtitle),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )

        val stripLabel = stringResource(R.string.share_strip_label)
        ArgusCard(Modifier.fillMaxWidth(), contentPadding = PaddingValues(0.dp)) {
            ArgusListRow(
                title = stripLabel,
                subtitle = stringResource(R.string.share_strip_hint),
                trailing = { ArgusSwitch(strip, onStripChange, contentDescription = stripLabel) },
                contentPadding = PaddingValues(start = ArgusSpacing.Large, top = ArgusSpacing.Medium, end = ArgusSpacing.Medium, bottom = ArgusSpacing.Medium),
            )
        }
        if (!strip) {
            // Il testo integrale porta chat, numeri, luoghi e segreti: lo si dice prima di condividerlo.
            WarningNote(stringResource(R.string.share_strip_off_warning))
        }

        ShareQrCode(share.text(strip), Modifier.align(Alignment.CenterHorizontally))

        Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
            ArgusButton(
                text = stringResource(R.string.share_as_text),
                onClick = { onShareAsText(strip) },
                icon = Icons.Rounded.Share,
                fillWidth = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small), modifier = Modifier.fillMaxWidth()) {
                ArgusButton(
                    text = stringResource(R.string.share_save_file),
                    onClick = { onSaveFile(strip) },
                    style = ButtonStyle.Outlined,
                    fillWidth = true,
                    modifier = Modifier.weight(1f),
                )
                ArgusButton(
                    text = stringResource(R.string.share_copy),
                    onClick = { onCopy(strip) },
                    style = ButtonStyle.Outlined,
                    fillWidth = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Avviso con icona (il colore non è l'unico segnale), nel ruolo ambra «aspetta te». */
@Composable
private fun WarningNote(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small), verticalAlignment = Alignment.Top) {
        Icon(
            Icons.Rounded.PrivacyTip,
            contentDescription = null,
            tint = ArgusTheme.semantic.pending.fg,
            modifier = Modifier.size(18.dp),
        )
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
    }
}

/**
 * Il codice QR del testo, o una nota se il testo non ci entra. Scuro su chiaro in entrambi i temi (colori dello
 * schema chiaro di Argus), con il margine chiaro attorno.
 */
@Composable
private fun ShareQrCode(text: RuleShareText, modifier: Modifier = Modifier) {
    val matrix = remember(text.qrText) { QrCodes.encode(text.qrText) }
    if (matrix == null) {
        Text(
            stringResource(R.string.share_qr_too_long),
            modifier = modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        return
    }
    val qrDescription = stringResource(R.string.share_qr_cd)
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(ArgusRadius.Lg))
            .background(ArgusLightColors.surfaceContainerLowest)
            .padding(ArgusSpacing.Small),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit),
    ) {
        QrCanvas(matrix, QrSide, Modifier.semantics { contentDescription = qrDescription })
        Text(
            stringResource(R.string.share_qr_caption),
            modifier = Modifier.padding(bottom = ArgusSpacing.Small),
            color = ArgusLightColors.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun QrCanvas(matrix: QrMatrix, side: Dp, modifier: Modifier = Modifier) {
    val light = ArgusLightColors.surfaceContainerLowest
    val dark = ArgusLightColors.onSurface
    Canvas(modifier.size(side)) {
        val modules = matrix.size + 2 * QR_QUIET_MODULES
        val cell = size.minDimension / modules
        drawRect(light)
        for (y in 0 until matrix.size) {
            for (x in 0 until matrix.size) {
                if (matrix.isDark(x, y)) {
                    drawRect(
                        color = dark,
                        topLeft = Offset((x + QR_QUIET_MODULES) * cell, (y + QR_QUIET_MODULES) * cell),
                        // Un filo più grande della cella: niente righe chiare tra moduli vicini.
                        size = Size(cell + 0.5f, cell + 0.5f),
                    )
                }
            }
        }
    }
}

// Un ModalBottomSheet non si disegna in un'anteprima statica: qui si mostra il contenuto sul fondo `sheet`.
@ArgusThemePreviews
@Composable
private fun RuleShareContentPreview() {
    ArgusPreviewSurface {
        Column(
            Modifier
                .background(ArgusTheme.surfaces.sheet)
                .padding(ArgusSpacing.Large),
        ) {
            val text = RuleShareText(
                text = "{}",
                qrText = """{"format":"argus-rules","version":1,"rules":[{"enabled":true,"draft":{"name":"Batteria al 20%"}}]}""",
            )
            RuleShareContent(
                share = RuleShareUi("Batteria al 20%: risparmio", text, text, "argus-rule-batteria.json"),
                strip = true,
                onStripChange = {},
                onShareAsText = {},
                onSaveFile = {},
                onCopy = {},
            )
        }
    }
}

@ArgusThemePreviews
@Composable
private fun RuleShareContentTooLongPreview() {
    ArgusPreviewSurface {
        Column(Modifier.background(ArgusTheme.surfaces.sheet).padding(ArgusSpacing.Large)) {
            val text = RuleShareText(text = "{}", qrText = "x".repeat(3_000))
            RuleShareContent(
                share = RuleShareUi("Regola lunga", text, text, "argus-rule-regola-lunga.json"),
                strip = false,
                onStripChange = {},
                onShareAsText = {},
                onSaveFile = {},
                onCopy = {},
            )
        }
    }
}
