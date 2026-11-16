package dev.argus.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.components.ArgusActionRow
import dev.argus.ui.components.ArgusBottomSheet
import dev.argus.ui.components.ArgusButton
import dev.argus.ui.components.ArgusCard
import dev.argus.ui.components.ArgusDetailTopBar
import dev.argus.ui.components.ArgusIconButton
import dev.argus.ui.components.ArgusListRow
import dev.argus.ui.components.ArgusTextField
import dev.argus.ui.components.ButtonStyle
import dev.argus.ui.components.CodeBlock
import dev.argus.ui.components.CodePart
import dev.argus.ui.components.CodeTone
import dev.argus.ui.components.DestructiveConfirmDialog
import dev.argus.ui.components.IconBadge
import dev.argus.ui.components.SectionLabel
import dev.argus.ui.components.groupPositionOf
import dev.argus.ui.components.groupShape
import dev.argus.ui.model.SecretRowUi
import dev.argus.ui.model.SecretsCallbacks
import dev.argus.ui.model.SecretsState
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.presentation.SecretsText
import dev.argus.ui.theme.ArgusCode
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme
import java.time.ZoneId

// =============================================================================
// 0.8 · Sistema › Segreti (tavola approvata «Segreti»). Token e password che le regole usano per NOME: la
// schermata elenca i nomi, quante regole li usano e quando una regola li ha letti l'ultima volta. Il valore si
// scrive (aggiungi, sostituisci) e si cancella, mai si rilegge: il campo del valore vive solo in `remember`, mai
// in uno stato salvato. Stateless: (state, callbacks).
// =============================================================================

/** Which form is open: a new secret, or a new value for [name]. */
private sealed interface SecretForm {
    data object Add : SecretForm
    data class Replace(val name: String) : SecretForm
}

@Composable
fun SecretsScreen(
    state: SecretsState,
    callbacks: SecretsCallbacks,
    modifier: Modifier = Modifier,
    nowMillis: Long = System.currentTimeMillis(),
) {
    var addOpen by rememberSaveable { mutableStateOf(false) }
    var replaceName by rememberSaveable { mutableStateOf<String?>(null) }
    var deleteName by rememberSaveable { mutableStateOf<String?>(null) }

    Surface(color = ArgusTheme.surfaces.page, modifier = modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            ArgusDetailTopBar(
                title = stringResource(R.string.settings_title),
                onBack = callbacks::onBack,
                backContentDescription = stringResource(R.string.action_back),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(start = ArgusSpacing.Large, end = ArgusSpacing.Large, bottom = ArgusSpacing.Section),
                verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Large),
            ) {
                Text(
                    stringResource(R.string.secrets_title),
                    modifier = Modifier.semantics { heading() },
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.headlineMedium,
                )
                Text(
                    stringResource(R.string.secrets_intro),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (state.secrets.isNotEmpty()) {
                    SecretList(
                        rows = state.secrets,
                        nowMillis = nowMillis,
                        enabled = !state.busy,
                        onReplace = { replaceName = it },
                        onDelete = { deleteName = it },
                    )
                } else if (!state.loading) {
                    Text(
                        stringResource(R.string.secrets_empty),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                ArgusButton(
                    text = stringResource(R.string.secrets_add),
                    onClick = { addOpen = true },
                    icon = Icons.Rounded.Add,
                    enabled = !state.busy,
                    fillWidth = true,
                )
                HowToUseCard()
                Text(
                    stringResource(R.string.secrets_delete_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    val form: SecretForm? = when {
        replaceName != null -> SecretForm.Replace(replaceName!!)
        addOpen -> SecretForm.Add
        else -> null
    }
    if (form != null) {
        SecretFormSheet(
            form = form,
            existing = state.secrets.map { it.name },
            onDismiss = {
                addOpen = false
                replaceName = null
            },
            onSave = { name, value ->
                addOpen = false
                replaceName = null
                callbacks.onSave(name, value)
            },
        )
    }
    deleteName?.let { name ->
        DestructiveConfirmDialog(
            title = stringResource(R.string.secrets_delete_title, name),
            body = stringResource(R.string.secrets_delete_body),
            confirmLabel = stringResource(R.string.secrets_delete),
            onConfirm = {
                deleteName = null
                callbacks.onDelete(name)
            },
            onDismiss = { deleteName = null },
        )
    }
}

@Composable
private fun SecretList(
    rows: List<SecretRowUi>,
    nowMillis: Long,
    enabled: Boolean,
    onReplace: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
        SectionLabel(stringResource(R.string.secrets_saved_header, rows.size))
        Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.GroupGap)) {
            rows.forEachIndexed { index, row ->
                ArgusCard(
                    shape = groupShape(groupPositionOf(index, rows.size)),
                    contentPadding = PaddingValues(0.dp),
                ) {
                    SecretRow(row, nowMillis, enabled, onReplace, onDelete)
                }
            }
        }
    }
}

@Composable
private fun SecretRow(
    row: SecretRowUi,
    nowMillis: Long,
    enabled: Boolean,
    onReplace: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val unused = row.usedBy == 0
    val usage = when (row.usedBy) {
        0 -> stringResource(R.string.secrets_used_by_none)
        1 -> stringResource(R.string.secrets_used_by_one)
        else -> stringResource(R.string.secrets_used_by_many, row.usedBy)
    }
    val subtitle = if (unused) usage else usage + " · " + lastUseText(row.lastUsedAtMillis, nowMillis)
    val badge = if (unused) ArgusTheme.semantic.disabled else null
    ArgusListRow(
        title = row.name,
        subtitle = subtitle,
        titleColor = if (unused) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        leading = {
            IconBadge(
                icon = Icons.Rounded.Key,
                container = badge?.bg ?: MaterialTheme.colorScheme.primaryContainer,
                content = badge?.fg ?: MaterialTheme.colorScheme.onPrimaryContainer,
                size = ArgusSpacing.TouchTarget - ArgusSpacing.Small,
                iconSize = ArgusSpacing.Large + ArgusSpacing.Unit,
            )
        },
        trailing = {
            Box {
                ArgusIconButton(
                    icon = Icons.Rounded.MoreVert,
                    contentDescription = stringResource(R.string.secrets_more_cd, row.name),
                    onClick = { menuOpen = true },
                    enabled = enabled,
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.secrets_replace)) },
                        onClick = {
                            menuOpen = false
                            onReplace(row.name)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.secrets_delete), color = ArgusTheme.semantic.error.fg) },
                        onClick = {
                            menuOpen = false
                            onDelete(row.name)
                        },
                    )
                }
            }
        },
        contentPadding = PaddingValues(
            start = ArgusSpacing.Large,
            end = ArgusSpacing.Unit,
            top = ArgusSpacing.Medium,
            bottom = ArgusSpacing.Medium,
        ),
    )
}

@Composable
private fun lastUseText(lastUsedAtMillis: Long?, nowMillis: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return when (val use = SecretsText.lastUse(lastUsedAtMillis, nowMillis, ZoneId.systemDefault(), locale)) {
        SecretsText.LastUse.Never -> stringResource(R.string.secrets_last_never)
        is SecretsText.LastUse.Today -> stringResource(R.string.secrets_last_today, use.time)
        is SecretsText.LastUse.Yesterday -> stringResource(R.string.secrets_last_yesterday, use.time)
        is SecretsText.LastUse.On -> stringResource(R.string.secrets_last_on, use.date)
    }
}

/** «Come si usa»: cosa scrivere in chat e come appare in approvazione, mai il valore. */
@Composable
private fun HowToUseCard() {
    Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
        SectionLabel(stringResource(R.string.secrets_how_header))
        ArgusCard {
            Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium)) {
                Text(
                    stringResource(R.string.secrets_how_body),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    stringResource(R.string.secrets_how_review),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                CodeBlock(
                    parts = listOf(
                        CodePart("Authorization: Bearer "),
                        CodePart(stringResource(R.string.secrets_how_example_secret), CodeTone.External),
                    ),
                    style = ArgusCode,
                )
            }
        }
    }
}

@Composable
private fun SecretFormSheet(
    form: SecretForm,
    existing: List<String>,
    onDismiss: () -> Unit,
    onSave: (name: String, value: String) -> Unit,
) {
    val replacing = form as? SecretForm.Replace
    var name by rememberSaveable { mutableStateOf(replacing?.name.orEmpty()) }
    // Il valore non va MAI in SavedState o nello stato del ViewModel: solo `remember`, e svuotato alla chiusura.
    var value by remember { mutableStateOf("") }
    var attempted by rememberSaveable { mutableStateOf(false) }
    val cleanName = name.trim()
    val nameProblem = if (replacing != null) null else SecretsText.nameProblem(cleanName, existing)
    val valueValid = SecretsText.isValueValid(value)
    ArgusBottomSheet(
        onDismiss = onDismiss,
        title = if (replacing != null) {
            stringResource(R.string.secrets_replace_title, replacing.name)
        } else {
            stringResource(R.string.secrets_add_title)
        },
    ) {
        ArgusTextField(
            value = name,
            onValueChange = { name = it },
            label = stringResource(R.string.secrets_name_label),
            monospace = true,
            readOnly = replacing != null,
            supportingText = if (replacing == null) stringResource(R.string.secrets_name_hint) else null,
            errorText = when (nameProblem.takeIf { attempted }) {
                SecretsText.NameProblem.INVALID -> stringResource(R.string.secrets_name_invalid)
                SecretsText.NameProblem.TAKEN -> stringResource(R.string.secrets_name_taken)
                null -> null
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        ArgusTextField(
            value = value,
            onValueChange = { value = it },
            label = stringResource(R.string.secrets_value_label),
            monospace = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            supportingText = stringResource(R.string.secrets_value_hint),
            errorText = if (attempted && !valueValid) stringResource(R.string.secrets_value_invalid) else null,
        )
        ArgusActionRow {
            ArgusButton(stringResource(R.string.action_cancel), onDismiss, style = ButtonStyle.Text)
            ArgusButton(
                text = stringResource(R.string.secrets_save),
                onClick = {
                    attempted = true
                    if (nameProblem == null && valueValid) {
                        val toSave = value
                        value = ""
                        onSave(cleanName, toSave)
                    }
                },
            )
        }
        Spacer(Modifier.size(ArgusSpacing.Small))
    }
}

// -----------------------------------------------------------------------------
// Anteprime
// -----------------------------------------------------------------------------

private object NoopSecretsCallbacks : SecretsCallbacks {
    override fun onBack() = Unit
    override fun onSave(name: String, value: String) = Unit
    override fun onDelete(name: String) = Unit
}

private const val PREVIEW_NOW = 1_763_300_000_000L

@ArgusThemePreviews
@Composable
private fun SecretsScreenPreview() {
    ArgusTheme {
        SecretsScreen(
            SecretsState(
                secrets = listOf(
                    SecretRowUi("ha_token", usedBy = 2, lastUsedAtMillis = PREVIEW_NOW - 3_600_000L),
                    SecretRowUi("ntfy_casa", usedBy = 1, lastUsedAtMillis = null),
                    SecretRowUi("meteo_api", usedBy = 0, lastUsedAtMillis = null),
                ),
                loading = false,
            ),
            NoopSecretsCallbacks,
            nowMillis = PREVIEW_NOW,
        )
    }
}

@ArgusThemePreviews
@Composable
private fun SecretsScreenEmptyPreview() {
    ArgusTheme {
        SecretsScreen(SecretsState(loading = false), NoopSecretsCallbacks, nowMillis = PREVIEW_NOW)
    }
}
