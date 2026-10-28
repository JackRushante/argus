package dev.argus.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.model.RulesBackupKind
import dev.argus.ui.model.RulesBackupStatus
import dev.argus.ui.model.RulesBackupUi
import dev.argus.ui.theme.ArgusTheme

// =============================================================================
// Sezione «Backup delle regole» di Sistema (0.4.0, F1). Stateless: l'unico stato locale è la
// casella «Togli posizioni e contatti», spenta di default, che viaggia con il tocco su Esporta.
// Il file lo sceglie il chiamante (app) con il Storage Access Framework; i testi dei risultati
// arrivano già scritti dal ViewModel nella lingua dell'utente.
// =============================================================================

@Composable
fun RulesBackupSection(
    state: RulesBackupUi,
    onExport: (stripPersonalData: Boolean) -> Unit,
    onImport: () -> Unit,
    onDismissResult: () -> Unit,
    onOpenImported: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var stripPersonalData by rememberSaveable { mutableStateOf(false) }
    val working = state.status is RulesBackupStatus.Working
    val shape = RoundedCornerShape(14.dp)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.backup_section),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, shape)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.backup_body),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .toggleable(
                        value = stripPersonalData,
                        enabled = !working,
                        role = Role.Checkbox,
                        onValueChange = { stripPersonalData = it },
                    ),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(checked = stripPersonalData, onCheckedChange = null, enabled = !working)
                Text(
                    stringResource(R.string.backup_strip_label),
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            if (stripPersonalData) {
                Text(
                    stringResource(R.string.backup_strip_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = { onExport(stripPersonalData) },
                    enabled = !working && state.exportableRules > 0,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.backup_export)) }
                OutlinedButton(
                    onClick = onImport,
                    enabled = !working,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.backup_import)) }
            }
            if (state.exportableRules == 0) {
                Text(
                    stringResource(R.string.backup_export_none),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                stringResource(R.string.backup_import_hint),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )

            BackupStatusRow(state.status, onDismissResult, onOpenImported)
        }
    }
}

@Composable
private fun BackupStatusRow(
    status: RulesBackupStatus,
    onDismiss: () -> Unit,
    onOpenImported: () -> Unit,
) {
    when (status) {
        RulesBackupStatus.Idle -> Unit
        is RulesBackupStatus.Working -> Row(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
                stringResource(
                    if (status.kind == RulesBackupKind.EXPORT) R.string.backup_working_export
                    else R.string.backup_working_import,
                ),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        is RulesBackupStatus.Done -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                status.summary,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (status.importedCount > 0) {
                    TextButton(onClick = onOpenImported, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.backup_open_rules))
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(stringResource(R.string.backup_dismiss))
                }
            }
        }
        is RulesBackupStatus.Failed -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite },
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                status.message,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(stringResource(R.string.backup_dismiss))
            }
        }
    }
}

// =============================================================================
// Preview
// =============================================================================

@Composable
private fun BackupPreview(state: RulesBackupUi) {
    ArgusTheme {
        RulesBackupSection(
            state = state,
            onExport = {},
            onImport = {},
            onDismissResult = {},
            onOpenImported = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(name = "Backup · pronto · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)

@Preview(name = "Backup · pronto · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun RulesBackupIdlePreview() {
    BackupPreview(RulesBackupUi(exportableRules = 7))
}

@Preview(name = "Backup · nessuna regola da esportare · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)

@Preview(name = "Backup · nessuna regola da esportare · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun RulesBackupNothingToExportPreview() {
    BackupPreview(RulesBackupUi(exportableRules = 0))
}

@Preview(name = "Backup · in corso · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)

@Preview(name = "Backup · in corso · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun RulesBackupWorkingPreview() {
    BackupPreview(
        RulesBackupUi(RulesBackupStatus.Working(RulesBackupKind.IMPORT), exportableRules = 7),
    )
}

@Preview(name = "Backup · import riuscito · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)

@Preview(name = "Backup · import riuscito · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun RulesBackupImportDonePreview() {
    BackupPreview(
        RulesBackupUi(
            RulesBackupStatus.Done(
                RulesBackupKind.IMPORT,
                summary = "5 regole importate da approvare; 1 scartata (regola non valida).",
                importedCount = 5,
            ),
            exportableRules = 7,
        ),
    )
}

@Preview(name = "Backup · export riuscito · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)

@Preview(name = "Backup · export riuscito · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun RulesBackupExportDonePreview() {
    BackupPreview(
        RulesBackupUi(
            RulesBackupStatus.Done(RulesBackupKind.EXPORT, summary = "7 regole esportate."),
            exportableRules = 7,
        ),
    )
}

@Preview(name = "Backup · errore · scuro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)

@Preview(name = "Backup · errore · chiaro", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_NO)
@Composable
private fun RulesBackupFailedPreview() {
    BackupPreview(
        RulesBackupUi(
            RulesBackupStatus.Failed(
                RulesBackupKind.IMPORT,
                message = "Il file non è un file di regole Argus valido.",
            ),
            exportableRules = 7,
        ),
    )
}
