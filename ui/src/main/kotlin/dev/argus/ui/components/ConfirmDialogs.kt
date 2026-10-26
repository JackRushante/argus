package dev.argus.ui.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.argus.ui.R

/**
 * Stato di una conferma distruttiva: «chiedi» memorizza ciò che si sta per cancellare, «conferma» lo
 * consegna UNA volta sola a chi esegue, «annulla» lo scarta senza eseguire niente. Puro e immutabile,
 * così la regola «l'annullamento non cancella» si prova senza Compose.
 */
data class ConfirmationState<T : Any>(val pending: T? = null) {
    val isOpen: Boolean get() = pending != null

    fun ask(item: T): ConfirmationState<T> = ConfirmationState(item)

    fun cancel(): ConfirmationState<T> = ConfirmationState()

    /** Esegue [action] con l'elemento in attesa (se c'è) e chiude la conferma. */
    fun confirm(action: (T) -> Unit): ConfirmationState<T> {
        pending?.let(action)
        return ConfirmationState()
    }
}

/** Colori del pulsante che conferma un'azione distruttiva: sempre il ruolo di errore del tema. */
@Composable
fun destructiveButtonColors(): ButtonColors = ButtonDefaults.buttonColors(
    containerColor = MaterialTheme.colorScheme.error,
    contentColor = MaterialTheme.colorScheme.onError,
)

/** Dialogo di conferma per eliminazioni e rimozioni: «Annulla» a sinistra, azione in colore di errore. */
@Composable
fun DestructiveConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        },
        confirmButton = {
            Button(onClick = onConfirm, colors = destructiveButtonColors()) { Text(confirmLabel) }
        },
    )
}
