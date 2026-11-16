package dev.argus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import dev.argus.engine.model.RuleLabels
import dev.argus.ui.R
import dev.argus.ui.model.RuleLabelUi
import dev.argus.ui.model.RuleLabelsUi
import dev.argus.ui.presentation.LabelInputProblem
import dev.argus.ui.presentation.labelInputProblem
import dev.argus.ui.presentation.labelSuggestions
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusSpacing

// =============================================================================
// Etichette nel dettaglio di una regola salvata (0.7.2): riga «Etichette» con i chip, «togli» per ognuno e
// «Aggiungi etichetta», che apre un foglio con le etichette già in uso per prime e un campo per una nuova.
// Le etichette sono organizzazione: la nota lo dice, e nessun tocco qui chiede una nuova approvazione.
// =============================================================================

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RuleLabelsSection(
    labels: RuleLabelsUi,
    onAdd: () -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val full = labels.labels.size >= RuleLabels.MAX_PER_RULE
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
        SectionLabel(stringResource(R.string.lbl_detail_title), Modifier.padding(start = ArgusSpacing.Unit))
        ArgusCard(Modifier.fillMaxWidth()) {
            Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium)) {
                if (labels.labels.isEmpty()) {
                    Text(
                        stringResource(R.string.lbl_detail_none),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    val pausedDescription = stringResource(R.string.lbl_label_paused_cd)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
                        verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
                    ) {
                        labels.labels.forEach { label ->
                            RemovableLabelChip(
                                text = label.label,
                                removeDescription = stringResource(R.string.lbl_detail_remove_cd, label.label),
                                onRemove = { onRemove(label.label) },
                                paused = label.paused,
                                pausedDescription = pausedDescription,
                            )
                        }
                    }
                }
                ArgusButton(
                    text = stringResource(R.string.lbl_detail_add),
                    onClick = onAdd,
                    style = ButtonStyle.Text,
                    icon = Icons.Rounded.Add,
                    enabled = !full,
                    disabledReason = if (full) stringResource(R.string.lbl_detail_full) else null,
                )
                Text(
                    stringResource(R.string.lbl_detail_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Foglio «Aggiungi un'etichetta»: prima le etichette già in uso che la regola non ha (un tocco le aggiunge),
 * poi una nuova. Il campo dice subito perché un'etichetta non va (stesse regole del database).
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun AddLabelSheet(
    labels: RuleLabelsUi,
    onAdd: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val onRule = labels.labels.map { it.label }
    val suggestions = labelSuggestions(onRule, labels.existing)
    val problem = labelInputProblem(text, onRule, labels.existing)
    val canAdd = text.isNotBlank() && problem == null
    val problemText = when (problem) {
        LabelInputProblem.INVALID -> stringResource(R.string.lbl_sheet_invalid)
        LabelInputProblem.ALREADY_ON_RULE -> stringResource(R.string.lbl_sheet_already)
        LabelInputProblem.RULE_FULL -> stringResource(R.string.lbl_detail_full)
        LabelInputProblem.TOO_MANY_LABELS -> stringResource(R.string.lbl_sheet_too_many)
        null -> null
    }
    ArgusBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.lbl_sheet_title)) {
        if (suggestions.isNotEmpty()) {
            SectionLabel(stringResource(R.string.lbl_sheet_existing))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
                verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
            ) {
                suggestions.forEach { label ->
                    ArgusChoiceChip(text = label, selected = false, onClick = { onAdd(label) }, role = Role.Button)
                }
            }
        }
        ArgusTextField(
            value = text,
            onValueChange = { text = it },
            label = stringResource(R.string.lbl_sheet_new),
            placeholder = stringResource(R.string.lbl_sheet_placeholder),
            supportingText = stringResource(R.string.lbl_sheet_hint),
            errorText = problemText,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (canAdd) onAdd(text) }),
        )
        ArgusActionRow {
            ArgusButton(stringResource(R.string.action_cancel), onClick = onDismiss, style = ButtonStyle.Text)
            ArgusButton(stringResource(R.string.lbl_sheet_add), onClick = { onAdd(text) }, enabled = canAdd)
        }
    }
}

@ArgusThemePreviews
@Composable
private fun RuleLabelsSectionPreview() {
    ArgusPreviewSurface {
        Column(Modifier.padding(ArgusSpacing.Large), verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Large)) {
            RuleLabelsSection(
                RuleLabelsUi(
                    labels = listOf(RuleLabelUi("Casa", paused = false), RuleLabelUi("Auto", paused = true)),
                    existing = listOf("Casa", "Auto", "Lavoro"),
                ),
                onAdd = {},
                onRemove = {},
            )
            RuleLabelsSection(RuleLabelsUi(labels = emptyList(), existing = emptyList()), onAdd = {}, onRemove = {})
        }
    }
}
