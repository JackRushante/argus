package dev.argus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.model.AutoBackupFrequencyUi
import dev.argus.ui.model.AutoBackupUi
import dev.argus.ui.preview.ArgusPreviewSurface
import dev.argus.ui.preview.ArgusThemePreviews
import dev.argus.ui.theme.ArgusSpacing
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Card «Backup automatico» (0.7.2) nella sezione backup di Sistema. Stateless: la cartella la sceglie
// il chiamante (selettore di cartelle del sistema), la passphrase si scrive nel foglio e non torna mai
// indietro (campi `remember`, mai `rememberSaveable`, sempre vuoti all'apertura).
// =============================================================================

/** Problema della passphrase nel foglio. La lunghezza si conta in caratteri visibili (code point). */
enum class PassphraseProblem { TOO_SHORT, MISMATCH }

object AutoBackupPassphrase {
    /** Uguale a `BackupKeyDerivation.MIN_PASSPHRASE_LENGTH` (modulo data), che la ricontrolla. */
    const val MIN_LENGTH = 8

    fun problem(passphrase: String, confirmation: String): PassphraseProblem? = when {
        passphrase.codePointCount(0, passphrase.length) < MIN_LENGTH -> PassphraseProblem.TOO_SHORT
        passphrase != confirmation -> PassphraseProblem.MISMATCH
        else -> null
    }
}

/** Gli eventi della card: uno per controllo. */
data class AutoBackupActions(
    val onSetEnabled: (Boolean) -> Unit = {},
    val onChooseFolder: () -> Unit = {},
    val onSetFrequency: (AutoBackupFrequencyUi) -> Unit = {},
    val onSetPassphrase: (String) -> Unit = {},
    val onRunNow: () -> Unit = {},
)

@Composable
fun AutoBackupCard(
    state: AutoBackupUi,
    actions: AutoBackupActions,
    modifier: Modifier = Modifier,
) {
    var showPassphraseSheet by rememberSaveable { mutableStateOf(false) }
    val busy = state.running || state.settingPassphrase
    val needsSetupReason = stringResource(R.string.backup_auto_needs_setup)
    ArgusCard(modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
            ) {
                Text(
                    stringResource(R.string.backup_auto_title),
                    modifier = Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                )
                ArgusSwitch(
                    checked = state.enabled,
                    onCheckedChange = actions.onSetEnabled,
                    contentDescription = stringResource(
                        if (state.enabled) R.string.backup_auto_switch_off_cd else R.string.backup_auto_switch_on_cd,
                    ),
                    // Si può sempre spegnere; accendere richiede cartella e passphrase.
                    enabled = state.enabled || state.ready,
                )
            }
            Text(
                stringResource(R.string.backup_auto_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!state.ready) ReasonText(needsSetupReason)

            SettingRow(
                label = stringResource(R.string.backup_auto_folder_label),
                value = state.folderLabel ?: stringResource(R.string.backup_auto_folder_none),
            ) {
                ArgusButton(
                    text = stringResource(
                        if (state.folderLabel == null) R.string.backup_auto_folder_choose else R.string.backup_auto_folder_change,
                    ),
                    onClick = actions.onChooseFolder,
                    style = ButtonStyle.Outlined,
                    enabled = !busy,
                )
            }

            Column(verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
                Text(
                    stringResource(R.string.backup_auto_frequency_label),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small)) {
                    ArgusChoiceChip(
                        text = stringResource(R.string.backup_auto_daily),
                        selected = state.frequency == AutoBackupFrequencyUi.DAILY,
                        onClick = { actions.onSetFrequency(AutoBackupFrequencyUi.DAILY) },
                    )
                    ArgusChoiceChip(
                        text = stringResource(R.string.backup_auto_weekly),
                        selected = state.frequency == AutoBackupFrequencyUi.WEEKLY,
                        onClick = { actions.onSetFrequency(AutoBackupFrequencyUi.WEEKLY) },
                    )
                }
            }

            SettingRow(
                label = stringResource(R.string.backup_auto_passphrase_label),
                value = stringResource(
                    if (state.passphraseSet) R.string.backup_auto_passphrase_set else R.string.backup_auto_passphrase_missing,
                ),
            ) {
                ArgusButton(
                    text = stringResource(
                        if (state.passphraseSet) R.string.backup_auto_passphrase_change else R.string.backup_auto_passphrase_add,
                    ),
                    onClick = { showPassphraseSheet = true },
                    style = ButtonStyle.Outlined,
                    enabled = !busy,
                )
            }

            when {
                state.settingPassphrase -> WorkingRow(stringResource(R.string.backup_auto_passphrase_working))
                state.running -> WorkingRow(stringResource(R.string.backup_auto_running))
                else -> LastRunText(state)
            }
            Text(
                stringResource(R.string.backup_auto_retention),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            ArgusButton(
                text = stringResource(R.string.backup_auto_run_now),
                onClick = actions.onRunNow,
                style = ButtonStyle.Tonal,
                enabled = state.ready && !busy,
                disabledReason = if (!state.ready) needsSetupReason else null,
                fillWidth = true,
            )
        }
    }
    if (showPassphraseSheet) {
        PassphraseSheet(
            changing = state.passphraseSet,
            onDismiss = { showPassphraseSheet = false },
            onSave = { passphrase ->
                showPassphraseSheet = false
                actions.onSetPassphrase(passphrase)
            },
        )
    }
}

@Composable
private fun SettingRow(label: String, value: String, action: @Composable () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Medium),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(ArgusSpacing.Unit)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
            Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
        }
        action()
    }
}

@Composable
private fun WorkingRow(text: String) {
    Row(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Text(text, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LastRunText(state: AutoBackupUi) {
    val text = state.lastRun ?: stringResource(R.string.backup_auto_never)
    if (state.lastRunFailed) {
        // Il fallimento è scritto e ha l'icona: il rosso non è l'unico segnale.
        Row(
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(ArgusSpacing.Small),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(Icons.Rounded.Error, contentDescription = null, tint = ArgusTheme.semantic.error.fg, modifier = Modifier.size(18.dp))
            Text(text, color = ArgusTheme.semantic.error.fg, style = MaterialTheme.typography.bodyMedium)
        }
    } else {
        Text(
            text,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PassphraseSheet(changing: Boolean, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    // Segreti: mai in SavedState, mai precompilati.
    var passphrase by remember { mutableStateOf("") }
    var confirmation by remember { mutableStateOf("") }
    var attempted by remember { mutableStateOf(false) }
    val problem = AutoBackupPassphrase.problem(passphrase, confirmation).takeIf { attempted }
    ArgusBottomSheet(onDismiss = onDismiss, title = stringResource(R.string.backup_passphrase_sheet_title)) {
        Text(
            stringResource(R.string.backup_passphrase_sheet_body),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (changing) {
            Text(
                stringResource(R.string.backup_passphrase_change_note),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        ArgusTextField(
            value = passphrase,
            onValueChange = { passphrase = it },
            label = stringResource(R.string.backup_passphrase_field),
            errorText = if (problem == PassphraseProblem.TOO_SHORT) stringResource(R.string.backup_passphrase_too_short) else null,
            supportingText = stringResource(R.string.backup_passphrase_too_short),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        ArgusTextField(
            value = confirmation,
            onValueChange = { confirmation = it },
            label = stringResource(R.string.backup_passphrase_confirm_field),
            errorText = if (problem == PassphraseProblem.MISMATCH) stringResource(R.string.backup_passphrase_mismatch) else null,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        ArgusActionRow(Modifier.padding(top = ArgusSpacing.Small)) {
            ArgusButton(stringResource(R.string.backup_passphrase_cancel), onDismiss, style = ButtonStyle.Text)
            ArgusButton(
                text = stringResource(R.string.backup_passphrase_save),
                onClick = {
                    attempted = true
                    if (AutoBackupPassphrase.problem(passphrase, confirmation) == null) {
                        val chosen = passphrase
                        passphrase = ""
                        confirmation = ""
                        onSave(chosen)
                    }
                },
            )
        }
    }
}

// =============================================================================
// Preview
// =============================================================================

@ArgusThemePreviews
@Composable
private fun AutoBackupNotSetUpPreview() = ArgusPreviewSurface {
    AutoBackupCard(AutoBackupUi(), AutoBackupActions(), Modifier.padding(16.dp))
}

@ArgusThemePreviews
@Composable
private fun AutoBackupOnPreview() = ArgusPreviewSurface {
    AutoBackupCard(
        AutoBackupUi(
            enabled = true,
            folderLabel = "Nextcloud/Argus",
            passphraseSet = true,
            lastRun = "Ultimo backup: ieri 03:10 · 12 regole",
        ),
        AutoBackupActions(),
        Modifier.padding(16.dp),
    )
}

@ArgusThemePreviews
@Composable
private fun AutoBackupFailedPreview() = ArgusPreviewSurface {
    AutoBackupCard(
        AutoBackupUi(
            enabled = true,
            folderLabel = "Nextcloud/Argus",
            frequency = AutoBackupFrequencyUi.WEEKLY,
            passphraseSet = true,
            lastRun = "Backup non riuscito (oggi 03:10): la cartella non è raggiungibile.",
            lastRunFailed = true,
        ),
        AutoBackupActions(),
        Modifier.padding(16.dp),
    )
}

@ArgusThemePreviews
@Composable
private fun AutoBackupRunningPreview() = ArgusPreviewSurface {
    AutoBackupCard(
        AutoBackupUi(enabled = true, folderLabel = "Backup", passphraseSet = true, running = true),
        AutoBackupActions(),
        Modifier.padding(16.dp),
    )
}
