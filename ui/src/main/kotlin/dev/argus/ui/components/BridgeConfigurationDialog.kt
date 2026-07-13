package dev.argus.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
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

/** Editor locale: il bearer non viene mai letto, precompilato o rimesso nello stato di schermata. */
@Composable
fun BridgeConfigurationDialog(
    initialUrl: String,
    tokenConfigured: Boolean,
    onDismiss: () -> Unit,
    onSave: (url: String, bearerToken: String?) -> Unit,
) {
    var url by rememberSaveable(initialUrl) { mutableStateOf(initialUrl) }
    // Non serializzare mai il bearer in SavedState/process-death storage.
    var token by remember { mutableStateOf("") }
    val cleanUrl = url.trim()
    val cleanToken = token.trim()
    val canSave = cleanUrl.isNotEmpty() && (tokenConfigured || cleanToken.isNotEmpty())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.bridge_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.bridge_url_label)) },
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.bridge_token_label)) },
                    supportingText = {
                        Text(
                            stringResource(
                                if (tokenConfigured) R.string.bridge_token_keep_hint
                                else R.string.bridge_token_required_hint,
                            ),
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
                    onSave(cleanUrl, cleanToken.takeIf(String::isNotEmpty))
                    token = ""
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_save_and_test)) }
        },
    )
}
