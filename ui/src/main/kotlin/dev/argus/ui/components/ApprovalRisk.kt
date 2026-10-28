package dev.argus.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AppBlocking
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.argus.engine.safety.Severity
import dev.argus.ui.R
import dev.argus.ui.model.UiWarning
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusRadius
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.RolePair

// =============================================================================
// Riepilogo rischi dell'approvazione (tavole «Approvazione · generativa» e «Approvazione · P4 con shell»).
// Sta SEMPRE sopra la regola, mai collassato (invarianti 2 e 5): errori e avvisi di validazione, conflitti e
// nota privacy, uno per riga. Il contorno ambra e il conteggio dicono «leggi prima di armare»; un ERROR è
// scritto per intero con l'etichetta «Errore», così il colore non è l'unico segnale.
// =============================================================================

/** Quanti ERROR e quanti avvisi contiene l'elenco: i due numeri dei chip dell'intestazione. */
internal data class RiskCounts(val errors: Int, val warnings: Int)

internal fun riskCounts(warnings: List<UiWarning>): RiskCounts {
    val errors = warnings.count { it.severity == Severity.ERROR }
    return RiskCounts(errors = errors, warnings = warnings.size - errors)
}

/** Icona di una riga di rischio: l'errore ha la sua, poi privacy, app mancante e avviso generico. */
internal fun riskIcon(warning: UiWarning): ImageVector = when {
    warning.severity == Severity.ERROR -> Icons.Rounded.Error
    warning.code == "read_plus_reply" || warning.code.contains("privacy", ignoreCase = true) -> Icons.Rounded.PrivacyTip
    warning.code == "app_not_installed" -> Icons.Rounded.AppBlocking
    else -> Icons.Rounded.WarningAmber
}

/**
 * Pannello «Prima di armare» (bozza) o «Avvisi» (regola salvata). Senza voci non disegna nulla.
 *
 * @param title intestazione del pannello.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalRiskPanel(
    title: String,
    warnings: List<UiWarning>,
    modifier: Modifier = Modifier,
) {
    if (warnings.isEmpty()) return
    val counts = riskCounts(warnings)
    val semantic = ArgusTheme.semantic
    ArgusCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(ArgusRadius.Xl),
        border = BorderStroke(1.5.dp, semantic.attention.copy(alpha = 0.45f)),
        contentPadding = PaddingValues(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
            ) {
                SectionLabel(title, Modifier.weight(1f))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    if (counts.errors > 0) {
                        StatusChip(
                            semantic.error,
                            Icons.Rounded.Error,
                            if (counts.errors == 1) stringResource(R.string.rda_ap_errors_one)
                            else stringResource(R.string.rda_ap_errors_many, counts.errors),
                        )
                    }
                    if (counts.warnings > 0) {
                        WarningCountChip(
                            if (counts.warnings == 1) stringResource(R.string.rda_ap_warnings_one)
                            else stringResource(R.string.rda_ap_warnings_many, counts.warnings),
                        )
                    }
                }
            }
            warnings.forEachIndexed { index, warning ->
                if (index > 0) {
                    Box(
                        Modifier
                            .padding(start = 44.dp)
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant),
                    )
                }
                RiskRow(warning)
            }
        }
    }
}

@Composable
private fun RiskRow(warning: UiWarning) {
    val semantic = ArgusTheme.semantic
    val isError = warning.severity == Severity.ERROR
    val role: RolePair = if (isError) semantic.error else semantic.pending
    val text = if (isError) {
        val label = stringResource(R.string.rda_ap_error_label)
        buildAnnotatedString {
            withStyle(SpanStyle(fontWeight = FontWeight.W700)) { append(label) }
            append(" · ")
            append(warning.text)
        }
    } else {
        buildAnnotatedString { append(warning.text) }
    }
    Row(
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
        verticalAlignment = Alignment.Top,
    ) {
        IconBadge(riskIcon(warning), role.bg, role.fg)
        Text(
            text,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@ArgusThemePreviews
@Composable
private fun ApprovalRiskPanelPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ApprovalRiskPanel(
                title = "Prima di armare",
                warnings = listOf(
                    UiWarning(Severity.WARNING, "privacy_generative", "Il testo della notifica verrà inviato a Hermes e da lì al provider cloud per generare la risposta."),
                    UiWarning(Severity.WARNING, "read_plus_reply", "Questa regola legge dati dal dispositivo (testo della notifica) e li può inviare al mittente. Possibile esfiltrazione di contesto."),
                ),
            )
            ApprovalRiskPanel(
                title = "Prima di armare",
                warnings = listOf(
                    UiWarning(Severity.ERROR, "shell_external_trigger", "Un comando shell non può partire da un SMS: il mittente è falsificabile. Usa un trigger con identità non falsificabile (orario, geofence, connettività) o una chat WhatsApp in whitelist."),
                    UiWarning(Severity.WARNING, "interpolation_shell_context", "Il comando interpola otp, un dato esterno e segreto: con la policy Aggressive un SMS costruito ad arte può iniettare comandi."),
                    UiWarning(Severity.WARNING, "app_not_installed", "Un'app usata da questa regola non è installata su questo telefono: installala oppure cambia app. (com.youtube)"),
                ),
            )
        }
    }
}
