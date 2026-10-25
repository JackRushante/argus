package dev.argus.automation.vm

import dev.argus.automation.BridgeHealthResult
import dev.argus.brain.BridgeErrorKind
import dev.argus.ui.presentation.RenderLanguage

/**
 * Testo del test di connessione fallito (Sistema): dice COSA correggere invece di un generico
 * «non raggiungibile». Il messaggio del provider ([BridgeHealthResult.Unreachable.providerMessage]) è
 * già ripulito dal transport e compare solo qui: mai nei log, mai persistito.
 */
internal fun connectionFailureText(
    label: String,
    failure: BridgeHealthResult.Unreachable,
    baseUrl: String?,
    language: RenderLanguage,
): String {
    val status = failure.statusCode?.takeIf { it in 100..599 }
    val provider = failure.providerMessage?.takeIf { it.isNotBlank() }
    val providerSuffixEn = provider?.let { " Provider message: “$it”" }.orEmpty()
    val providerSuffixIt = provider?.let { " Messaggio del provider: «$it»" }.orEmpty()
    return when (failure.kind) {
        BridgeErrorKind.NETWORK -> language.pick(
            "$label is unreachable: network or DNS error.",
            "$label non raggiungibile: errore di rete o DNS.",
        )
        BridgeErrorKind.TIMEOUT -> language.pick(
            "$label did not answer in time (timeout).",
            "$label non ha risposto in tempo (timeout).",
        )
        BridgeErrorKind.CONFIGURATION -> language.pick(
            "$label is not set up: check the key, the model and the privacy notice.",
            "$label non è configurato: controlla chiave, modello e informativa privacy.",
        )
        BridgeErrorKind.PROTOCOL -> language.pick(
            "$label gave an unexpected reply: check the URL.",
            "$label ha dato una risposta inattesa: controlla l'URL.",
        )
        BridgeErrorKind.AUTH -> language.pick(
            "$label rejected the API key${status.inParens()}: check it.",
            "$label ha rifiutato la chiave API${status.inParens()}: controllala.",
        )
        BridgeErrorKind.BUDGET -> language.pick(
            "$label reports no credit or budget left${status.inParens()}.$providerSuffixEn",
            "$label segnala credito o budget esaurito${status.inParens()}.$providerSuffixIt",
        )
        BridgeErrorKind.RATE_LIMIT -> language.pick(
            "$label is limiting requests${status.inParens()}: try again shortly.",
            "$label sta limitando le richieste${status.inParens()}: riprova tra poco.",
        )
        BridgeErrorKind.HTTP -> httpFailureText(label, status, baseUrl, providerSuffixEn, providerSuffixIt, language)
    }
}

private fun httpFailureText(
    label: String,
    status: Int?,
    baseUrl: String?,
    providerSuffixEn: String,
    providerSuffixIt: String,
    language: RenderLanguage,
): String = when (status) {
    null -> language.pick("$label is unreachable (http).", "$label non raggiungibile (http).")
    401, 403 -> language.pick(
        "$label rejected the API key (HTTP $status): check it.",
        "$label ha rifiutato la chiave API (HTTP $status): controllala.",
    )
    402 -> language.pick(
        "$label reports no credit or budget left (HTTP 402).$providerSuffixEn",
        "$label segnala credito o budget esaurito (HTTP 402).$providerSuffixIt",
    )
    404 -> {
        val completionsEn = if (endsWithChatCompletions(baseUrl)) {
            " The address ends with /chat/completions: remove it, Argus adds it by itself."
        } else {
            ""
        }
        val completionsIt = if (endsWithChatCompletions(baseUrl)) {
            " L'indirizzo finisce con /chat/completions: toglilo, Argus lo aggiunge da solo."
        } else {
            ""
        }
        language.pick(
            "$label: endpoint not found (HTTP 404): check the URL.$completionsEn$providerSuffixEn",
            "$label: endpoint non trovato (HTTP 404): controlla l'URL.$completionsIt$providerSuffixIt",
        )
    }
    429 -> language.pick(
        "$label is limiting requests (HTTP 429): try again shortly.",
        "$label sta limitando le richieste (HTTP 429): riprova tra poco.",
    )
    in 400..499 -> language.pick(
        "$label rejected the request (HTTP $status).$providerSuffixEn",
        "$label ha rifiutato la richiesta (HTTP $status).$providerSuffixIt",
    )
    in 500..599 -> language.pick(
        "$label: service not available (HTTP $status). Try again later.",
        "$label: servizio non disponibile (HTTP $status). Riprova più tardi.",
    )
    else -> language.pick("$label is unreachable (HTTP $status).", "$label non raggiungibile (HTTP $status).")
}

private fun Int?.inParens(): String = if (this == null) "" else " (HTTP $this)"

/** true se l'URL base Custom finisce con `/chat/completions` (con o senza slash finale). */
internal fun endsWithChatCompletions(baseUrl: String?): Boolean =
    baseUrl?.let(::withoutChatCompletions) != null
