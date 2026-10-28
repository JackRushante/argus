package dev.argus.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.model.PermanentGrantRow
import dev.argus.ui.model.PermanentGrantUi
import dev.argus.ui.model.PrivilegedBackendUi
import dev.argus.ui.model.PrivilegedUi
import dev.argus.ui.model.RootStateUi
import dev.argus.ui.theme.ArgusTheme
import dev.argus.ui.theme.LocalArgusSemantic

// =============================================================================
// Sistema · Privilegi (0.5.0): backend privilegiato, interruttore «Usa root (su)» e permessi
// permanenti («Shizuku una volta sola»). Stateless; colori solo dai token del tema.
// L'accesso alle notifiche NON è tra i permessi concessi da qui: lo dice il dialogo.
// =============================================================================

/** Le voci spuntate all'apertura del dialogo: tutte quelle ancora da concedere. */
fun defaultGrantSelection(grants: List<PermanentGrantRow>): Set<PermanentGrantUi> =
    grants.filter { !it.granted && it.grant !in EXPLICIT_ONLY }.mapTo(linkedSetOf()) { it.grant }

/**
 * Voci che il dialogo non spunta mai da sé (0.5.1): danno ad Argus il controllo dello schermo di altre
 * app, quindi servono una conferma esplicita, spuntando la casella a mano.
 */
private val EXPLICIT_ONLY: Set<PermanentGrantUi> = setOf(PermanentGrantUi.ACCESSIBILITY)

private fun grantTitle(grant: PermanentGrantUi): Int = when (grant) {
    PermanentGrantUi.WRITE_SECURE_SETTINGS -> R.string.priv50_grant_secure_title
    PermanentGrantUi.WRITE_SETTINGS -> R.string.priv50_grant_write_title
    PermanentGrantUi.USAGE_STATS -> R.string.priv50_grant_usage_title
    PermanentGrantUi.DND_ACCESS -> R.string.priv50_grant_dnd_title
    PermanentGrantUi.ACCESSIBILITY -> R.string.priv50_grant_accessibility_title
}

private fun grantBody(grant: PermanentGrantUi): Int = when (grant) {
    PermanentGrantUi.WRITE_SECURE_SETTINGS -> R.string.priv50_grant_secure_body
    PermanentGrantUi.WRITE_SETTINGS -> R.string.priv50_grant_write_body
    PermanentGrantUi.USAGE_STATS -> R.string.priv50_grant_usage_body
    PermanentGrantUi.DND_ACCESS -> R.string.priv50_grant_dnd_body
    PermanentGrantUi.ACCESSIBILITY -> R.string.priv50_grant_accessibility_body
}

private fun backendBody(backend: PrivilegedBackendUi): Int = when (backend) {
    PrivilegedBackendUi.NONE -> R.string.priv50_backend_none
    PrivilegedBackendUi.SHIZUKU -> R.string.priv50_backend_shizuku
    PrivilegedBackendUi.SHIZUKU_SHELL -> R.string.priv50_backend_shell
    PrivilegedBackendUi.SHIZUKU_ROOT -> R.string.priv50_backend_shizuku_root
    PrivilegedBackendUi.SUI -> R.string.priv50_backend_sui
    PrivilegedBackendUi.ROOT -> R.string.priv50_backend_root
}

private fun rootBody(state: RootStateUi): Int = when (state) {
    RootStateUi.OFF -> R.string.priv50_root_off
    RootStateUi.CHECKING -> R.string.priv50_root_checking
    RootStateUi.ACTIVE -> R.string.priv50_root_active
    RootStateUi.DENIED -> R.string.priv50_root_denied
    RootStateUi.NO_SU -> R.string.priv50_root_no_su
}

@Composable
fun PrivilegesSection(
    state: PrivilegedUi,
    onSetRootEnabled: (Boolean) -> Unit,
    onGrant: (Set<PermanentGrantUi>) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showGrantDialog by rememberSaveable { mutableStateOf(false) }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.priv50_section),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
        PrivilegeCard(
            icon = { tint -> Icon(Icons.Rounded.Terminal, null, tint = tint, modifier = Modifier.size(22.dp)) },
            title = stringResource(R.string.priv50_backend_title),
            body = stringResource(backendBody(state.backend)),
            warn = state.backend == PrivilegedBackendUi.NONE,
            trailing = null,
        )
        val rootTitle = stringResource(R.string.priv50_root_title)
        PrivilegeCard(
            icon = { tint -> Icon(Icons.Rounded.Security, null, tint = tint, modifier = Modifier.size(22.dp)) },
            title = rootTitle,
            body = stringResource(rootBody(state.rootState)),
            warn = state.rootState == RootStateUi.DENIED || state.rootState == RootStateUi.NO_SU,
            trailing = {
                Switch(
                    checked = state.rootEnabled,
                    onCheckedChange = onSetRootEnabled,
                    modifier = Modifier.semantics { contentDescription = rootTitle },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            },
        )
        if (state.grants.isNotEmpty()) {
            Text(
                stringResource(R.string.priv50_grants_title),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
            if (state.grants.any { !it.granted }) {
                Text(
                    stringResource(R.string.priv50_grants_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
            state.grants.forEach { row ->
                GrantStatusRow(row, backendAvailable = state.backend != PrivilegedBackendUi.NONE)
            }
            if (state.canGrant) {
                OutlinedButton(
                    onClick = { showGrantDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp),
                ) { Text(stringResource(R.string.priv50_grant_button)) }
            }
        }
    }
    if (showGrantDialog && state.canGrant) {
        PermanentGrantsDialog(
            grants = state.grants,
            onDismiss = { showGrantDialog = false },
            onConfirm = { selected ->
                showGrantDialog = false
                onGrant(selected)
            },
        )
    }
}

@Composable
private fun PrivilegeCard(
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    title: String,
    body: String,
    warn: Boolean,
    trailing: (@Composable () -> Unit)?,
) {
    val semantic = LocalArgusSemantic.current
    val tint = if (warn) semantic.pending.fg else MaterialTheme.colorScheme.primary
    val borderColor = if (warn) semantic.pending.fg.copy(alpha = 0.35f) else MaterialTheme.colorScheme.outlineVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .background(if (warn) semantic.pending.fg.copy(alpha = 0.06f) else androidx.compose.ui.graphics.Color.Transparent)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        icon(tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelLarge)
            Text(body, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        trailing?.invoke()
    }
}

@Composable
private fun GrantStatusRow(row: PermanentGrantRow, backendAvailable: Boolean) {
    val semantic = LocalArgusSemantic.current
    val via = when {
        row.granted -> null
        backendAvailable -> stringResource(R.string.priv50_via_button)
        else -> stringResource(R.string.priv50_via_need_backend)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 13.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(grantTitle(row.grant)),
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                stringResource(grantBody(row.grant)),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(if (row.granted) R.string.priv50_granted else R.string.priv50_not_granted) +
                    (via?.let { " · $it" } ?: ""),
                color = if (row.granted) semantic.armed.fg else semantic.pending.fg,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (row.granted) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = stringResource(R.string.ui40_health_ok_cd),
                tint = semantic.armed.fg,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Dialogo con l'elenco di ciò che verrà concesso e una casella per voce. */
@Composable
fun PermanentGrantsDialog(
    grants: List<PermanentGrantRow>,
    onDismiss: () -> Unit,
    onConfirm: (Set<PermanentGrantUi>) -> Unit,
) {
    val pending = grants.filter { !it.granted }
    var selected by remember { mutableStateOf(defaultGrantSelection(grants).map { it.id }.toSet()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.priv50_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(R.string.priv50_dialog_body), style = MaterialTheme.typography.bodyMedium)
                pending.forEach { row ->
                    val checked = row.grant.id in selected
                    val toggle = {
                        selected = if (checked) selected - row.grant.id else selected + row.grant.id
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clickable(onClick = toggle),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { toggle() })
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(grantTitle(row.grant)), style = MaterialTheme.typography.labelLarge)
                            Text(
                                stringResource(grantBody(row.grant)),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
                Text(
                    stringResource(R.string.priv50_dialog_listener_note),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            Button(
                enabled = selected.isNotEmpty(),
                onClick = {
                    onConfirm(pending.map { it.grant }.filter { it.id in selected }.toSet())
                },
            ) { Text(stringResource(R.string.priv50_dialog_confirm)) }
        },
    )
}

private val previewGrants = listOf(
    PermanentGrantRow(PermanentGrantUi.WRITE_SECURE_SETTINGS, false),
    PermanentGrantRow(PermanentGrantUi.WRITE_SETTINGS, true),
    PermanentGrantRow(PermanentGrantUi.USAGE_STATS, false),
    PermanentGrantRow(PermanentGrantUi.DND_ACCESS, false),
    PermanentGrantRow(PermanentGrantUi.ACCESSIBILITY, false),
)

@Preview(name = "Privilegi · nessun backend", showBackground = true, backgroundColor = 0xFF0E1216)
@Composable
private fun PrivilegesNonePreview() {
    ArgusTheme { PrivilegesSection(PrivilegedUi(grants = previewGrants), {}, {}) }
}

@Preview(name = "Privilegi · Shizuku root, da concedere", showBackground = true, backgroundColor = 0xFF0E1216)
@Composable
private fun PrivilegesShizukuPreview() {
    ArgusTheme {
        PrivilegesSection(
            PrivilegedUi(PrivilegedBackendUi.SHIZUKU_ROOT, RootStateUi.OFF, previewGrants),
            {},
            {},
        )
    }
}

@Preview(name = "Privilegi · Sui", showBackground = true, backgroundColor = 0xFF0E1216)
@Composable
private fun PrivilegesSuiPreview() {
    ArgusTheme {
        PrivilegesSection(PrivilegedUi(PrivilegedBackendUi.SUI, RootStateUi.OFF, previewGrants), {}, {})
    }
}

@Preview(name = "Privilegi · root su negato", showBackground = true, backgroundColor = 0xFF0E1216)
@Composable
private fun PrivilegesRootDeniedPreview() {
    ArgusTheme {
        PrivilegesSection(PrivilegedUi(PrivilegedBackendUi.NONE, RootStateUi.DENIED, previewGrants), {}, {})
    }
}

@Preview(name = "Privilegi · dialogo", showBackground = true, backgroundColor = 0xFF0E1216)
@Composable
private fun PermanentGrantsDialogPreview() {
    ArgusTheme { PermanentGrantsDialog(previewGrants, {}, {}) }
}
