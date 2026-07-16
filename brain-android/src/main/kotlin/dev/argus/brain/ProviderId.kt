package dev.argus.brain

/**
 * Identità del provider LLM sul wire. Introdotto in S1 perché [AgentTransport.providerId] lo richiede;
 * il catalogo statico dei metadati (prezzi, quirks) arriva in S2.
 */
enum class ProviderId(val wireName: String) {
    HERMES("hermes"),
    OPENAI("openai"),
    ANTHROPIC("anthropic"),
    GEMINI("gemini"),
    OPENROUTER("openrouter"),
    CUSTOM_OPENAI_COMPAT("custom_openai_compat");

    companion object {
        fun fromWireName(value: String): ProviderId? = entries.firstOrNull { it.wireName == value }
    }
}
