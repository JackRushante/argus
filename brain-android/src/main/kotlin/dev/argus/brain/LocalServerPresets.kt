package dev.argus.brain

/**
 * Preset per i server locali OpenAI-compatibili (0.5.1, F4), offerti nel dialogo del provider Custom.
 * Tutti sul loopback del telefono: la regola sul cleartext non cambia, `http` resta ammesso solo per un host
 * loopback esatto (`localhost`, `127.0.0.1`, `::1`), qui e nel transport. Nessun prompt compatto: i modelli
 * troppo piccoli non scrivono regole valide e non valgono lo sforzo (decisione con Lorenzo).
 *
 * Un preset compila solo l'indirizzo; il modello lo sceglie l'utente. Il timeout del compile più lungo per i
 * server locali è in [CompileTimeoutPolicy] (180 s invece di 65 s).
 */
object LocalServerPresets {
    /** Contesto minimo che il prompt di compile richiede al server (token). Lo ricorda la nota del dialogo. */
    const val MIN_CONTEXT_TOKENS = 16_000

    data class Preset(
        /** Identificatore stabile, usato dalla UI per scegliere la nota specifica. */
        val id: String,
        val displayName: String,
        val baseUrl: String,
    )

    val all: List<Preset> = listOf(
        Preset(id = "ollama", displayName = "Ollama", baseUrl = "http://127.0.0.1:11434/v1"),
        Preset(id = "lmstudio", displayName = "LM Studio", baseUrl = "http://127.0.0.1:1234/v1"),
        Preset(id = "llamacpp", displayName = "llama.cpp", baseUrl = "http://127.0.0.1:8080/v1"),
    )

    /** Il preset il cui indirizzo coincide con [baseUrl] (senza slash finale), o null. */
    fun forUrl(baseUrl: String?): Preset? {
        val clean = baseUrl?.trim()?.trimEnd('/') ?: return null
        return all.firstOrNull { it.baseUrl == clean }
    }
}
