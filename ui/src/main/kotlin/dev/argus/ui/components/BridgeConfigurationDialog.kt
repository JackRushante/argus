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
import dev.argus.ui.presentation.ConnectionFormValidation

/**
 * Editor locale: il bearer non viene mai letto, precompilato o rimesso nello stato di schermata.
 *
 * «Salva e verifica» controlla i dati PRIMA di chiudersi (B48): con un indirizzo o un token non validi
 * il dialogo resta aperto, il testo scritto resta com'è e l'errore compare sotto il campo. Si chiude
 * solo quando i dati passano il controllo (le stesse regole del salvataggio, vedi
 * [ConnectionFormValidation]).
 */
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
    // Gli errori compaiono dal primo tentativo di salvare, non mentre si scrive.
    var attempted by rememberSaveable { mutableStateOf(false) }
    val cleanUrl = url.trim()
    val cleanToken = token.trim()
    val problems = ConnectionFormValidation.bridgeForm(url, token, tokenConfigured)
    val urlProblem = problems.url.takeIf { attempted }
    val tokenProblem = problems.token.takeIf { attempted }

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
                    isError = urlProblem != null,
                    label = { Text(stringResource(R.string.bridge_url_label)) },
                    supportingText = urlProblem?.let { problem -> { Text(urlProblemText(problem)) } },
                )
                val tokenHint = stringResource(
                    if (tokenConfigured) R.string.bridge_token_keep_hint
                    else R.string.bridge_token_required_hint,
                )
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = tokenProblem != null,
                    visualTransformation = PasswordVisualTransformation(),
                    label = { Text(stringResource(R.string.bridge_token_label)) },
                    supportingText = {
                        Text(
                            if (tokenProblem != null) {
                                secretProblemText(
                                    tokenProblem,
                                    ConnectionFormValidation.MIN_SECRET_LENGTH,
                                    tokenHint,
                                )
                            } else {
                                tokenHint
                            },
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
                onClick = {
                    attempted = true
                    if (problems.accepted) {
                        onSave(cleanUrl, cleanToken.takeIf(String::isNotEmpty))
                        token = ""
                        onDismiss()
                    }
                },
            ) { Text(stringResource(R.string.action_save_and_test)) }
        },
    )
}
