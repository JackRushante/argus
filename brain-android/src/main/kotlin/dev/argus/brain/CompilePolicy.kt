package dev.argus.brain

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Limiti condivisi del compile, pubblici perché li usa anche il ViewModel prima di toccare la rete. */
object CompileLimits {
    /** Tetto del prompt di compile completo (richiesta, modifica, chiarimenti, candidati app). */
    const val MAX_MESSAGE_CHARS = 32_768

    /** Tetto del campo di testo della chat: il resto del budget serve a modifica e chiarimenti. */
    const val MAX_INPUT_CHARS = 8_000
}

/**
 * Timeout dell'intero compile per provider. Un modello locale (Ollama, llama.cpp) che carica i pesi
 * e valuta un prompt da ~12.000 token supera facilmente i 65 s del cloud; per gli altri provider il
 * limite resta quello storico.
 */
object CompileTimeoutPolicy {
    const val DEFAULT_MILLIS = 65_000L
    const val LOCAL_CUSTOM_MILLIS = 180_000L

    /**
     * Quanto il timeout HTTP del client eccede quello del compile: scade prima il compile, con il suo
     * messaggio, e non la chiamata HTTP con un errore di rete generico.
     */
    const val HTTP_MARGIN_MILLIS = 5_000L

    fun timeoutMillis(providerId: ProviderId, baseUrl: String?): Long =
        if (providerId == ProviderId.CUSTOM_OPENAI_COMPAT && isLocalOrPrivateHost(baseUrl)) {
            LOCAL_CUSTOM_MILLIS
        } else {
            DEFAULT_MILLIS
        }

    /** Solo analisi sintattica dell'host: nessuna risoluzione DNS (può bloccare e uscire in rete). */
    private fun isLocalOrPrivateHost(baseUrl: String?): Boolean {
        val host = baseUrl?.trim()?.toHttpUrlOrNull()?.host?.lowercase() ?: return false
        return host == "localhost" ||
            host.endsWith(".localhost") ||
            host.endsWith(".local") ||
            isPrivateIpv4(host) ||
            isPrivateIpv6(host)
    }

    private fun isPrivateIpv4(host: String): Boolean {
        val match = IPV4.matchEntire(host) ?: return false
        val parts = match.groupValues.drop(1).map { it.toInt() }
        if (parts.any { it > 255 }) return false
        val (a, b) = parts
        return a == 127 ||
            a == 10 ||
            (a == 172 && b in 16..31) ||
            (a == 192 && b == 168) ||
            (a == 169 && b == 254) ||
            // CGNAT 100.64.0.0/10: gli indirizzi tailnet (Tailscale) di un LLM in rete privata.
            (a == 100 && b in 64..127)
    }

    private fun isPrivateIpv6(host: String): Boolean =
        ':' in host && (host == "::1" || host.startsWith("fc") || host.startsWith("fd") || host.startsWith("fe80"))

    private val IPV4 = Regex("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})")
}
