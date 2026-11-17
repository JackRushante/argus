package dev.argus.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Nfc
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import dev.argus.ui.R
import dev.argus.ui.theme.ArgusTheme

/**
 * 0.8: richiesta mostrata mentre Argus legge il tag NFC di una regola che si sta armando. Non si chiude
 * toccando fuori: solo «Annulla» (o il tag) la chiude, così l'arm non resta appeso senza che l'utente lo sappia.
 */
@Composable
fun NfcTagPrompt(onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        icon = { Icon(Icons.Rounded.Nfc, contentDescription = null) },
        title = { Text(stringResource(R.string.nfc_read_title)) },
        text = { Text(stringResource(R.string.nfc_read_body)) },
        confirmButton = {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
        },
    )
}

@Preview
@Composable
private fun NfcTagPromptPreview() {
    ArgusTheme { NfcTagPrompt(onCancel = {}) }
}
