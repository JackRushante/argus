package dev.argus.automation.vm

import java.util.Locale

/**
 * Testo copiato dal dialogo «Risposta grezza»: intestazione tecnica (versione, provider, modello, codice
 * d'errore del turno se c'è) e risposta del modello in un blocco recintato ```. Il recinto è più lungo di
 * qualunque sequenza di apici presente nella risposta, così una risposta con a sua volta blocchi di
 * codice non rompe il blocco. Le etichette restano in inglese: il testo è pensato per una segnalazione.
 */
internal fun rawResponseReport(
    appVersion: String?,
    installSource: String,
    provider: String,
    model: String?,
    errorCode: String?,
    raw: String,
): String {
    val longestBackticks = Regex("`+").findAll(raw).maxOfOrNull { it.value.length } ?: 0
    val fence = "`".repeat(maxOf(3, longestBackticks + 1))
    return buildString {
        append("App: Argus ").append(appVersion ?: "?").append(" (").append(installSource).append(")\n")
        append("Provider: ").append(provider).append('\n')
        append("Model: ").append(model ?: "-").append('\n')
        if (errorCode != null) append("Error code: ").append(errorCode).append('\n')
        append("Raw response:\n")
        append(fence).append('\n')
        append(raw.trimEnd()).append('\n')
        append(fence)
    }
}

private val CAMEL_BOUNDARY = Regex("(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
private val NOT_CODE_CHARS = Regex("[^a-z0-9_]+")

/**
 * Codice di un errore interno: il nome SEMPLICE della classe d'eccezione in snake_case
 * (`IllegalStateException` → `illegal_state_exception`), mai il suo messaggio.
 */
internal fun internalErrorCode(error: Throwable): String =
    error.javaClass.simpleName
        .replace(CAMEL_BOUNDARY, "_")
        .lowercase(Locale.ROOT)
        .replace(NOT_CODE_CHARS, "_")
        .trim('_')
        .take(64)
        .ifBlank { "unknown_error" }
