package dev.argus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.presentation.RenderLine
import dev.argus.ui.presentation.RuleChangeKind
import dev.argus.ui.presentation.RuleChangeRow
import dev.argus.ui.presentation.RuleSection
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// «Cosa cambia» (0.7.2): sopra la regola in approvazione quando la bozza sostituisce una regola già
// approvata. Righe aggiunte, tolte o cambiate, rese dai tipi (`RuleRenderDiff`); ogni riga ha chip con
// icona e testo, il colore non è l'unico segnale. Un comando shell resta integrale in un `CodeBlock`
// (invariante 6). La regola intera resta sotto, invariata.
// =============================================================================

/** Intestazione di sezione di una riga: l'attesa dopo uno scatto appartiene al «quando». */
internal fun changeSectionLabel(section: RuleSection): Int = when (section) {
    RuleSection.TRIGGER, RuleSection.COOLDOWN -> R.string.rule_section_when
    RuleSection.CONDITIONS -> R.string.rule_section_only_if
    RuleSection.VARIABLES -> R.string.rule_section_variables
    RuleSection.ACTIONS -> R.string.rule_section_then
}

/**
 * @param rows le differenze; vuota = la bozza non cambia nulla di eseguibile, e la card lo dice.
 */
@Composable
fun RuleChangesCard(rows: List<RuleChangeRow>, modifier: Modifier = Modifier) {
    ArgusCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium)) {
            Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit)) {
                Text(
                    stringResource(R.string.diff_title),
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(R.string.diff_subtitle),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (rows.isEmpty()) {
                Text(
                    stringResource(R.string.diff_nothing),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                var lastLabel: Int? = null
                rows.forEachIndexed { index, row ->
                    val label = changeSectionLabel(row.section)
                    if (label != lastLabel) {
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        SectionLabel(stringResource(label))
                        lastLabel = label
                    }
                    ChangeRow(row)
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(row: RuleChangeRow) {
    val semantic = ArgusTheme.semantic
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        when (row.kind) {
            RuleChangeKind.ADDED -> StatusChip(semantic.armed, Icons.Rounded.Add, stringResource(R.string.diff_added))
            RuleChangeKind.REMOVED -> StatusChip(semantic.error, Icons.Rounded.Remove, stringResource(R.string.diff_removed))
            RuleChangeKind.CHANGED -> StatusChip(semantic.pending, Icons.Rounded.Edit, stringResource(R.string.diff_changed))
        }
        when (row.kind) {
            RuleChangeKind.CHANGED -> {
                row.old?.let { ChangeLine(stringResource(R.string.diff_before), it, current = false) }
                row.new?.let { ChangeLine(stringResource(R.string.diff_now), it, current = true) }
            }
            RuleChangeKind.ADDED -> row.new?.let { ChangeLine(null, it, current = true) }
            RuleChangeKind.REMOVED -> row.old?.let { ChangeLine(null, it, current = false, struck = true) }
        }
    }
}

@Composable
private fun ChangeLine(label: String?, line: RenderLine, current: Boolean, struck: Boolean = false) {
    val color = if (current) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.padding(start = (line.depth * 12).dp),
        verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit),
    ) {
        if (label != null) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        }
        Text(
            line.text,
            color = color,
            style = MaterialTheme.typography.bodyMedium.copy(
                textDecoration = if (struck) TextDecoration.LineThrough else TextDecoration.None,
            ),
        )
        // Invariante 6: il comando shell si mostra intero, in monospace, anche nel confronto.
        line.shellCommand?.let { CodeBlock(it) }
    }
}

@ArgusThemePreviews
@Composable
private fun RuleChangesCardPreview() {
    ArgusPreviewSurface {
        RuleChangesCard(
            rows = listOf(
                RuleChangeRow(
                    RuleSection.TRIGGER,
                    RuleChangeKind.CHANGED,
                    RenderLine("Quando: ogni giorno alle 08:00"),
                    RenderLine("Quando: ogni giorno alle 07:30"),
                ),
                RuleChangeRow(RuleSection.CONDITIONS, RuleChangeKind.REMOVED, RenderLine("Solo se il Wi-Fi è acceso"), null),
                RuleChangeRow(
                    RuleSection.ACTIONS,
                    RuleChangeKind.ADDED,
                    null,
                    RenderLine("Esegui comando", shellCommand = "settings put global zen_mode 1"),
                ),
            ),
            modifier = Modifier.padding(16.dp),
        )
    }
}

@ArgusThemePreviews
@Composable
private fun RuleChangesCardNothingPreview() {
    ArgusPreviewSurface {
        RuleChangesCard(rows = emptyList(), modifier = Modifier.padding(16.dp))
    }
}
