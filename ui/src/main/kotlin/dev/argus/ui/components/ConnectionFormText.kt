package dev.argus.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.argus.ui.R
import dev.argus.ui.presentation.ConnectionFormValidation.SecretProblem
import dev.argus.ui.presentation.ConnectionFormValidation.UrlProblem

/** Testo dell'errore sotto il campo indirizzo dei dialoghi di connessione (B48). */
@Composable
internal fun urlProblemText(problem: UrlProblem): String = when (problem) {
    UrlProblem.EMPTY -> stringResource(R.string.ui40_url_error_empty)
    UrlProblem.NOT_HTTPS -> stringResource(R.string.ui40_url_error_not_https)
    UrlProblem.NOT_HTTPS_OR_LOOPBACK -> stringResource(R.string.ui40_url_error_not_https_or_local)
    UrlProblem.INVALID -> stringResource(R.string.ui40_url_error_invalid)
}

/**
 * Testo dell'errore sotto il campo token/chiave. [requiredText] è il suggerimento «obbligatorio» che il
 * dialogo mostra già quando il segreto non è ancora salvato.
 */
@Composable
internal fun secretProblemText(problem: SecretProblem, minLength: Int, requiredText: String): String =
    when (problem) {
        SecretProblem.MISSING -> requiredText
        SecretProblem.TOO_SHORT -> stringResource(R.string.ui40_secret_error_short, minLength)
        SecretProblem.TOO_LONG -> stringResource(
            R.string.ui40_secret_error_long,
            dev.argus.ui.presentation.ConnectionFormValidation.MAX_SECRET_LENGTH,
        )
        SecretProblem.INVALID_CHARACTERS -> stringResource(R.string.ui40_secret_error_chars)
    }
