package dev.argus.ui.presentation

import java.util.Locale

/**
 * Controlli locali dei dialoghi di connessione («Salva e verifica»). Gemelli di quelli che il
 * salvataggio applica davvero (`normalizeBridgeBaseUrl`, `normalizeProviderBaseUrl`,
 * `ProviderPrefsSchema.validApiKey` in brain-android): il dialogo li esegue PRIMA di chiudersi, così
 * un indirizzo sbagliato resta sotto il campo con il suo errore e quello che l'utente ha scritto non
 * si perde. Il modulo ui non vede brain-android, quindi le regole sono ripetute qui e i test le
 * fissano; un cambio lato store va riportato in questo file.
 *
 * Puro e JVM: nessun dato dell'utente esce da qui, né finisce in un log.
 */
object ConnectionFormValidation {
    enum class UrlProblem {
        /** Campo vuoto. */
        EMPTY,

        /** Schema diverso da https (o assente). */
        NOT_HTTPS,

        /** Provider Custom: https, oppure http solo verso localhost / 127.0.0.1 / ::1. */
        NOT_HTTPS_OR_LOOPBACK,

        /** Indirizzo non interpretabile: host o porta sbagliati, utente, query o frammento. */
        INVALID,
    }

    enum class SecretProblem {
        /** Obbligatorio e vuoto. */
        MISSING,
        TOO_SHORT,
        TOO_LONG,

        /** Spazi, controlli o caratteri non ASCII stampabili. */
        INVALID_CHARACTERS,
    }

    /** Lunghezza minima del bearer Hermes e delle chiavi API dei provider (esclusi i Custom). */
    const val MIN_SECRET_LENGTH = 16

    /** Il provider Custom accetta chiavi anche di un solo carattere (endpoint locali). */
    const val MIN_CUSTOM_SECRET_LENGTH = 1
    const val MAX_SECRET_LENGTH = 4_096

    private val LOOPBACK_HOSTS = setOf("localhost", "127.0.0.1", "::1")

    /** Hermes: solo https. */
    fun bridgeUrl(raw: String): UrlProblem? = checkUrl(raw, cleartextLoopbackAllowed = false)

    /** Provider diretto: https; http soltanto verso un host loopback e solo se [cleartextLoopbackAllowed]. */
    fun providerUrl(raw: String, cleartextLoopbackAllowed: Boolean): UrlProblem? =
        checkUrl(raw, cleartextLoopbackAllowed)

    /**
     * Bearer o chiave API. Vuoto con [required] = [SecretProblem.MISSING]; vuoto e non richiesto = valido
     * (si conserva quello già salvato). Il segreto non entra mai in un messaggio d'errore.
     */
    fun secret(raw: String, required: Boolean, minLength: Int): SecretProblem? {
        val value = raw.trim()
        if (value.isEmpty()) return if (required) SecretProblem.MISSING else null
        if (value.any { it.code !in 0x21..0x7e }) return SecretProblem.INVALID_CHARACTERS
        if (value.length < minLength) return SecretProblem.TOO_SHORT
        if (value.length > MAX_SECRET_LENGTH) return SecretProblem.TOO_LONG
        return null
    }

    /** Errori del dialogo Hermes; [accepted] = si può salvare e chiudere. */
    data class BridgeProblems(val url: UrlProblem?, val token: SecretProblem?) {
        val accepted: Boolean get() = url == null && token == null
    }

    fun bridgeForm(url: String, token: String, tokenConfigured: Boolean): BridgeProblems =
        BridgeProblems(
            url = bridgeUrl(url),
            token = secret(token, required = !tokenConfigured, minLength = MIN_SECRET_LENGTH),
        )

    /** Errori del dialogo provider; [accepted] = si può salvare e chiudere. */
    data class ProviderProblems(
        val url: UrlProblem?,
        val modelMissing: Boolean,
        val key: SecretProblem?,
    ) {
        val accepted: Boolean get() = url == null && !modelMissing && key == null
    }

    fun providerForm(
        baseUrl: String,
        model: String,
        apiKey: String,
        baseUrlEditable: Boolean,
        cleartextLoopbackAllowed: Boolean,
        keyRequired: Boolean,
        keyConfigured: Boolean,
        minKeyLength: Int,
    ): ProviderProblems = ProviderProblems(
        url = if (baseUrlEditable) providerUrl(baseUrl, cleartextLoopbackAllowed) else null,
        modelMissing = model.isBlank(),
        key = secret(apiKey, required = keyRequired && !keyConfigured, minLength = minKeyLength),
    )

    private fun checkUrl(raw: String, cleartextLoopbackAllowed: Boolean): UrlProblem? {
        val text = raw.trim()
        if (text.isEmpty()) return UrlProblem.EMPTY
        val insecure = if (cleartextLoopbackAllowed) UrlProblem.NOT_HTTPS_OR_LOOPBACK else UrlProblem.NOT_HTTPS
        val separator = text.indexOf("://")
        if (separator <= 0) return insecure
        val scheme = text.substring(0, separator).lowercase(Locale.ROOT)
        val cleartext = scheme == "http"
        if (scheme != "https" && !cleartext) return insecure
        val rest = text.substring(separator + 3)
        // L'archivio rifiuta utente, query e frammento: meglio dirlo prima.
        if ('?' in rest || '#' in rest) return UrlProblem.INVALID
        val authority = rest.substringBefore('/')
        if ('@' in authority || authority.any { it.isWhitespace() || it.isISOControl() }) {
            return UrlProblem.INVALID
        }
        val host: String
        val port: String
        if (authority.startsWith("[")) {
            val close = authority.indexOf(']')
            if (close < 0) return UrlProblem.INVALID
            host = authority.substring(1, close)
            val after = authority.substring(close + 1)
            if (after.isNotEmpty() && !after.startsWith(":")) return UrlProblem.INVALID
            port = after.removePrefix(":")
        } else {
            val colon = authority.lastIndexOf(':')
            host = if (colon >= 0) authority.substring(0, colon) else authority
            port = if (colon >= 0) authority.substring(colon + 1) else ""
        }
        if (host.isEmpty()) return UrlProblem.INVALID
        if (port.isNotEmpty() && (port.length > 5 || !port.all(Char::isDigit) || port.toInt() !in 1..65_535)) {
            return UrlProblem.INVALID
        }
        if (cleartext) {
            // http è ammesso solo per il provider Custom e solo verso un host loopback esatto.
            if (!cleartextLoopbackAllowed || host.lowercase(Locale.ROOT) !in LOOPBACK_HOSTS) return insecure
        }
        return null
    }
}
