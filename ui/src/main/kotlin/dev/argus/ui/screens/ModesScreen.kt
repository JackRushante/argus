package dev.argus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.components.ArgusActionRow
import dev.argus.ui.components.ArgusBottomSheet
import dev.argus.ui.components.ArgusButton
import dev.argus.ui.components.ArgusCard
import dev.argus.ui.components.ArgusChoiceChip
import dev.argus.ui.components.ArgusDetailTopBar
import dev.argus.ui.components.ArgusIconButton
import dev.argus.ui.components.ArgusListRow
import dev.argus.ui.components.ArgusTextField
import dev.argus.ui.components.ButtonStyle
import dev.argus.ui.components.DestructiveConfirmDialog
import dev.argus.ui.components.ModeHeroPanel
import dev.argus.ui.components.ReasonText
import dev.argus.ui.components.SectionLabel
import dev.argus.ui.components.StoredValueRow
import dev.argus.ui.components.groupPositionOf
import dev.argus.ui.components.groupShape
import dev.argus.ui.model.ModeRowUi
import dev.argus.ui.model.ModesCallbacks
import dev.argus.ui.model.ModesState
import dev.argus.ui.model.StoredValueRowUi
import dev.argus.ui.model.StoredValueTypeUi
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// 0.8 · Sistema › Modalità (tavola «Modalità», approvata). Modalità attiva coi pulsanti per cambiarla, quando
// e chi l'ha cambiata, l'elenco con quante regole la usano come condizione (aggiungi, rinomina, elimina) e le
// variabili salvate (tocca per correggere o eliminare). Stateless: (state, callbacks).
// =============================================================================

@Composable
fun ModesScreen(
    state: ModesState,
    callbacks: ModesCallbacks,
    modifier: Modifier = Modifier,
) {
    var addingMode by rememberSaveable { mutableStateOf(false) }
    var editingMode by rememberSaveable { mutableStateOf<String?>(null) }
    var editingValue by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(color = ArgusTheme.surfaces.page, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ArgusDetailTopBar(
                title = stringResource(R.string.settings_title),
                onBack = callbacks::onBack,
                backContentDescription = stringResource(R.string.action_back),
            )
            Column(
                Modifier.padding(start = ArgusSpacing.Large, end = ArgusSpacing.Large, bottom = ArgusSpacing.Section),
                verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Large),
            ) {
                Text(
                    stringResource(R.string.modes_title),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineMedium,
                )
                val switchHint = stringResource(R.string.modes_switch_hint)
                val noneActive = stringResource(R.string.modes_none_active)
                val switchCd = stringResource(R.string.modes_switch_cd)
                ModeHeroPanel(
                    label = stringResource(R.string.modes_active_label),
                    modes = state.modes.map { it.name },
                    active = state.activeMode,
                    note = listOfNotNull(if (state.activeMode == null) noneActive else null, state.lastChange, switchHint)
                        .joinToString(" "),
                    switchContentDescription = { switchCd.format(it) },
                    onSelect = callbacks::onSwitchMode,
                    modifier = Modifier.testTag("modes_hero"),
                )
                ModeList(state, onEdit = { editingMode = it }, onAdd = { addingMode = true })
                ValueList(state.values, onOpen = { editingValue = it })
                Text(
                    stringResource(R.string.modes_footer),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (addingMode) {
        ModeNameSheet(
            title = stringResource(R.string.modes_add_title),
            initial = "",
            reviewCount = 0,
            onDismiss = { addingMode = false },
            onSave = { name ->
                addingMode = false
                callbacks.onAddMode(name)
            },
        )
    }
    editingMode?.let { name ->
        val row = state.modes.firstOrNull { it.name == name }
        if (row == null) {
            editingMode = null
        } else {
            ModeEditSheet(
                row = row,
                onDismiss = { editingMode = null },
                onRename = { to ->
                    editingMode = null
                    callbacks.onRenameMode(row.name, to)
                },
                onDelete = {
                    editingMode = null
                    callbacks.onDeleteMode(row.name)
                },
            )
        }
    }
    editingValue?.let { key ->
        val row = state.values.firstOrNull { it.key == key }
        if (row == null) {
            editingValue = null
        } else {
            ValueEditSheet(
                row = row,
                onDismiss = { editingValue = null },
                onSave = { value ->
                    editingValue = null
                    callbacks.onEditValue(row.key, value)
                },
                onDelete = {
                    editingValue = null
                    callbacks.onDeleteValue(row.key)
                },
            )
        }
    }
}

@Composable
private fun ModeList(state: ModesState, onEdit: (String) -> Unit, onAdd: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
        SectionLabel(stringResource(R.string.modes_list_label), Modifier.padding(horizontal = ArgusSpacing.Unit))
        if (state.modes.isEmpty() && !state.loading) {
            Text(
                stringResource(R.string.modes_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = ArgusSpacing.Unit),
            )
        }
        val count = state.modes.size + 1
        Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.GroupGap)) {
            state.modes.forEachIndexed { index, mode ->
                ArgusCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = groupShape(groupPositionOf(index, count)),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    ArgusListRow(
                        title = mode.name,
                        subtitle = if (mode.conditionRules == 0) {
                            stringResource(R.string.modes_condition_rules_none)
                        } else {
                            pluralStringResource(R.plurals.modes_condition_rules, mode.conditionRules, mode.conditionRules)
                        },
                        trailing = {
                            ArgusIconButton(
                                icon = Icons.Rounded.Edit,
                                contentDescription = stringResource(R.string.modes_edit_cd, mode.name),
                                onClick = { onEdit(mode.name) },
                            )
                        },
                        contentPadding = PaddingValues(start = ArgusSpacing.Large, top = 10.dp, bottom = 10.dp),
                    )
                }
            }
            ArgusCard(
                modifier = Modifier.fillMaxWidth(),
                shape = groupShape(groupPositionOf(count - 1, count)),
                contentPadding = PaddingValues(horizontal = ArgusSpacing.Small, vertical = ArgusSpacing.Unit),
            ) {
                ArgusButton(
                    text = stringResource(R.string.modes_add),
                    onClick = onAdd,
                    style = ButtonStyle.Text,
                    icon = Icons.Rounded.Add,
                    enabled = state.canAddMode,
                    disabledReason = stringResource(R.string.modes_add_limit),
                    modifier = Modifier.testTag("modes_add"),
                )
            }
        }
    }
}

@Composable
private fun ValueList(values: List<StoredValueRowUi>, onOpen: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
        SectionLabel(stringResource(R.string.modes_values_label), Modifier.padding(horizontal = ArgusSpacing.Unit))
        if (values.isEmpty()) {
            Text(
                stringResource(R.string.modes_values_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = ArgusSpacing.Unit),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.GroupGap)) {
            values.forEachIndexed { index, value ->
                ArgusCard(
                    modifier = Modifier.fillMaxWidth(),
                    shape = groupShape(groupPositionOf(index, values.size)),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    StoredValueRow(
                        key = value.key,
                        detail = value.detail,
                        value = value.value,
                        masked = value.masked,
                        hiddenLabel = stringResource(R.string.modes_value_hidden),
                        onClickLabel = stringResource(R.string.modes_value_open_cd, value.key),
                        onClick = { onOpen(value.key) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ModeNameSheet(
    title: String,
    initial: String,
    reviewCount: Int,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    extra: @Composable () -> Unit = {},
) {
    var name by remember(initial) { mutableStateOf(initial) }
    ArgusBottomSheet(onDismiss = onDismiss, title = title) {
        ArgusTextField(
            value = name,
            onValueChange = { name = it.take(MAX_FIELD_CHARS) },
            label = stringResource(R.string.modes_name_label),
            supportingText = stringResource(R.string.modes_name_hint),
            modifier = Modifier.testTag("modes_name_field"),
        )
        if (reviewCount > 0) {
            ReasonText(pluralStringResource(R.plurals.modes_review_warning, reviewCount, reviewCount))
        }
        extra()
        ArgusActionRow {
            ArgusButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Text)
            ArgusButton(
                stringResource(R.string.action_save),
                onClick = { onSave(name.trim()) },
                enabled = name.isNotBlank(),
            )
        }
    }
}

@Composable
private fun ModeEditSheet(
    row: ModeRowUi,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    ModeNameSheet(
        title = stringResource(R.string.modes_edit_title),
        initial = row.name,
        reviewCount = row.usedByRules,
        onDismiss = onDismiss,
        onSave = { to -> if (to == row.name) onDismiss() else onRename(to) },
        extra = {
            ArgusButton(
                stringResource(R.string.modes_delete),
                onClick = { confirmDelete = true },
                style = ButtonStyle.Destructive,
            )
        },
    )
    if (confirmDelete) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.modes_delete_title, row.name),
            body = if (row.usedByRules == 0) {
                stringResource(R.string.modes_delete_unused)
            } else {
                pluralStringResource(R.plurals.modes_review_warning, row.usedByRules, row.usedByRules)
            },
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ValueEditSheet(
    row: StoredValueRowUi,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    // Un valore nascosto non rientra mai nel campo: si scrive solo il nuovo.
    var value by remember(row.key) { mutableStateOf(if (row.masked) "" else row.value) }
    var confirmDelete by remember { mutableStateOf(false) }
    ArgusBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.modes_value_title, row.key)) {
        Text(row.detail, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (row.type == StoredValueTypeUi.BOOLEAN) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
                ArgusChoiceChip(stringResource(R.string.modes_value_true), value == "true", { value = "true" })
                ArgusChoiceChip(stringResource(R.string.modes_value_false), value == "false", { value = "false" })
            }
        } else {
            ArgusTextField(
                value = value,
                onValueChange = { value = it.take(MAX_VALUE_CHARS) },
                label = stringResource(R.string.modes_value_label),
                monospace = true,
                singleLine = row.type == StoredValueTypeUi.NUMBER,
                supportingText = stringResource(
                    if (row.masked) R.string.modes_value_secret_hint else R.string.modes_value_clean_hint,
                ),
                keyboardOptions = if (row.type == StoredValueTypeUi.NUMBER) {
                    KeyboardOptions(keyboardType = KeyboardType.Decimal)
                } else {
                    KeyboardOptions.Default
                },
                modifier = Modifier.testTag("modes_value_field"),
            )
        }
        ArgusButton(
            stringResource(R.string.modes_value_delete),
            onClick = { confirmDelete = true },
            style = ButtonStyle.Destructive,
        )
        ArgusActionRow {
            ArgusButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Text)
            ArgusButton(
                stringResource(R.string.action_save),
                onClick = { onSave(value) },
                enabled = value.isNotEmpty(),
            )
        }
    }
    if (confirmDelete) {
        DestructiveConfirmDialog(
            title = stringResource(R.string.modes_value_delete_title, row.key),
            body = stringResource(R.string.modes_value_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            onConfirm = {
                confirmDelete = false
                onDelete()
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private const val MAX_FIELD_CHARS = 48
private const val MAX_VALUE_CHARS = 1_024

// --- Anteprime -----------------------------------------------------------------------------------

private object PreviewCallbacks : ModesCallbacks {
    override fun onBack() = Unit
    override fun onSwitchMode(name: String) = Unit
    override fun onAddMode(name: String) = Unit
    override fun onRenameMode(from: String, to: String) = Unit
    override fun onDeleteMode(name: String) = Unit
    override fun onEditValue(key: String, value: String) = Unit
    override fun onDeleteValue(key: String) = Unit
}

private val previewState = ModesState(
    loading = false,
    modes = listOf(
        ModeRowUi("Casa", conditionRules = 4, usedByRules = 5),
        ModeRowUi("Lavoro", conditionRules = 3, usedByRules = 3),
        ModeRowUi("Notte", conditionRules = 2, usedByRules = 2),
        ModeRowUi("Fuori", conditionRules = 0, usedByRules = 1),
    ),
    activeMode = "Casa",
    lastChange = "Cambiata oggi alle 18:42 dalla regola «Arrivo a casa».",
    values = listOf(
        StoredValueRowUi("caffe_oggi", StoredValueTypeUi.NUMBER, "2", false, "Numero · aggiornata oggi 08:10 da «Conta i caffè»"),
        StoredValueRowUi("ultima_palestra", StoredValueTypeUi.TEXT, "14/11", false, "Testo · aggiornata sabato da «Arrivo in palestra»"),
    ),
)

@ArgusThemePreviews
@Composable
private fun ModesScreenPreview() {
    ArgusPreviewSurface { ModesScreen(previewState, PreviewCallbacks) }
}

@ArgusThemePreviews
@Composable
private fun ModesScreenEmptyPreview() {
    ArgusPreviewSurface { ModesScreen(ModesState(loading = false), PreviewCallbacks) }
}

@ArgusThemePreviews
@Composable
private fun ModesScreenFullPreview() {
    ArgusPreviewSurface {
        ModesScreen(
            previewState.copy(
                modes = (1..12).map { ModeRowUi("Modalità $it", conditionRules = it % 3, usedByRules = it % 3) },
                activeMode = null,
                lastChange = null,
                values = previewState.values + StoredValueRowUi(
                    "pin_cancello",
                    StoredValueTypeUi.TEXT,
                    "",
                    true,
                    "Testo · aggiornata ieri da te",
                ),
                canAddMode = false,
            ),
            PreviewCallbacks,
        )
    }
}
