package dev.argus.engine.runtime

private val JOURNAL_CODE = Regex("^[a-z][a-z0-9_]{0,63}$")

/**
 * Codice d'errore di un'azione per il journal: il motivo dell'esecutore se è un codice chiuso
 * (`snake_case`, al massimo 64 caratteri, es. `http_status_404`), altrimenti `action_failed`.
 * Nessun testo libero (messaggi d'eccezione, dati dell'utente) entra mai nel journal.
 */
internal fun journalErrorCode(reason: String): String =
    reason.takeIf { JOURNAL_CODE.matches(it) } ?: "action_failed"
