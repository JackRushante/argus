package dev.argus.brain

import dev.argus.engine.model.LocalNetworkPolicy
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Limiti condivisi del compile, pubblici perché li usa anche il ViewModel prima di toccare la rete. */
object CompileLimits {
    /** Tetto del prompt di compile completo (richiesta, modifica, chiarimenti, candidati app). */
    const val MAX_MESSAGE_CHARS = 32_768

    /** Tetto del campo di testo della chat: il resto del budget serve a modifica e chiarimenti. */
    const val MAX_INPUT_CHARS = 8_000
}

/**
 * Response timeout chosen by the user in Settings (issue #20). [AUTOMATIC] keeps the historical limits of
 * [CompileTimeoutPolicy]; any other value replaces them for every provider. [wireValue] is the on-disk
 * contract (a rename is a migration).
 */
enum class ResponseTimeout(val wireValue: String, val millis: Long?) {
    AUTOMATIC("auto", null),
    TWO_MINUTES("2m", 2 * 60_000L),
    FIVE_MINUTES("5m", 5 * 60_000L),
    TEN_MINUTES("10m", 10 * 60_000L),
    TWENTY_MINUTES("20m", 20 * 60_000L),
    ;

    /** Minutes shown in Settings; 0 = [AUTOMATIC]. */
    val minutes: Int get() = ((millis ?: 0L) / 60_000L).toInt()

    companion object {
        /** Missing or unknown values fall back to [AUTOMATIC]: never a crash, never a shorter limit. */
        fun fromWireValue(value: String?): ResponseTimeout =
            entries.firstOrNull { it.wireValue == value } ?: AUTOMATIC

        /** The choice with these [minutes] (0 = [AUTOMATIC]), or null for a value Settings does not offer. */
        fun fromMinutes(minutes: Int): ResponseTimeout? = entries.firstOrNull { it.minutes == minutes }
    }
}

/**
 * Timeout dell'intero compile per provider. Un modello locale (Ollama, llama.cpp) che carica i pesi
 * e valuta un prompt da ~12.000 token supera facilmente i 65 s del cloud; per gli altri provider il
 * limite resta quello storico. Una scelta dell'utente ([ResponseTimeout], #20) sostituisce entrambi: la
 * seguono il `withTimeout` della chat, la barra d'attesa e i timeout HTTP del client
 * ([DefaultTransportFactory.clientFor]), così nessun limite nascosto scatta prima.
 */
object CompileTimeoutPolicy {
    const val DEFAULT_MILLIS = 65_000L
    const val LOCAL_CUSTOM_MILLIS = 180_000L

    /**
     * Quanto il timeout HTTP del client eccede quello del compile: scade prima il compile, con il suo
     * messaggio, e non la chiamata HTTP con un errore di rete generico.
     */
    const val HTTP_MARGIN_MILLIS = 5_000L

    fun timeoutMillis(
        providerId: ProviderId,
        baseUrl: String?,
        override: ResponseTimeout = ResponseTimeout.AUTOMATIC,
    ): Long = override.millis ?: automaticMillis(providerId, baseUrl)

    fun timeoutMillis(config: ProviderConfig): Long =
        timeoutMillis(config.providerId, config.baseUrl, config.responseTimeout)

    /** Il limite storico, senza scelta dell'utente. */
    fun automaticMillis(providerId: ProviderId, baseUrl: String?): Long =
        if (providerId == ProviderId.CUSTOM_OPENAI_COMPAT && isLocalOrPrivateHost(baseUrl)) {
            LOCAL_CUSTOM_MILLIS
        } else {
            DEFAULT_MILLIS
        }

    /** Solo analisi sintattica dell'host ([LocalNetworkPolicy]): nessuna risoluzione DNS. */
    private fun isLocalOrPrivateHost(baseUrl: String?): Boolean {
        val host = baseUrl?.trim()?.toHttpUrlOrNull()?.host ?: return false
        return LocalNetworkPolicy.scopeOf(host) != LocalNetworkPolicy.HostScope.PUBLIC
    }
}
