package dev.argus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.argus.ui.R
import dev.argus.ui.model.AuthState
import dev.argus.ui.model.TransportUi

/**
 * Editor di un provider diretto BYOK. Stessa disciplina di [BridgeConfigurationDialog]: la chiave
 * vive in `remember` (MAI `rememberSaveable` — niente segreti nel SavedState) e non viene mai
 * precompilata. `apiKey == null` in [onSave] conserva la chiave esistente.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProviderConfigurationDialog(
    provider: TransportUi.DirectProvider,
    onDismiss: () -> Unit,
    onSave: (baseUrl: String?, model: String?, apiKey: String?) -> Unit,
) {
    var baseUrl by rememberSaveable(provider.baseUrl) { mutableStateOf(provider.baseUrl) }
    var model by rememberSaveable(provider.model) { mutableStateOf(provider.model.orEmpty()) }
    // Non serializzare mai la chiave in SavedState/process-death storage.
    var key by remember { mutableStateOf("") }
    val cleanBaseUrl = baseUrl.trim()
    val cleanModel = model.trim()
    val cleanKey = key.trim()
    val keyConfigured = provider.authState == AuthState.OK
    val canSave = cleanModel.isNotEmpty() &&
        (keyConfigured || cleanKey.isNotEmpty()) &&
        (!provider.baseUrlEditable || cleanBaseUrl.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.provider_dialog_title, provider.providerLabel)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = provider.baseUrlEditable,
                    label = { Text(stringResource(R.string.provider_endpoint_label)) },
                )
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.provider_model_label)) },
                )
                if (provider.defaultModels.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        provider.defaultModels.forEach { suggestion ->
                            AssistChip(
                                onClick = { model = suggestion },
                                label = { Text(suggestion) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.provider_key_label)) },
                    placeholder = provider.apiKeyPrefixHint?.let { { Text(it) } },
                    supportingText = {
                        Text(
                            if (keyConfigured) stringResource(R.string.provider_key_keep_hint)
                            else stringResource(R.string.provider_key_required_hint),
                        )
                    },
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            Button(
                enabled = canSave,
                onClick = {
                    onSave(
                        cleanBaseUrl.takeIf { provider.baseUrlEditable },
                        cleanModel,
                        cleanKey.takeIf(String::isNotEmpty),
                    )
                    key = ""
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_save_and_test)) }
        },
    )
}
