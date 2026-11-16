package dev.argus.brain

/**
 * Config UI/factory di un provider. NIENTE campo chiave a livello di tipo: i segreti passano solo
 * da [ProviderSecrets.apiKey]. `toString()`/`copy()` non possono quindi far trapelare la chiave.
 */
data class ProviderConfig(
    val providerId: ProviderId,
    val baseUrl: String,
    val model: String?,
    val reasoningEffort: ReasoningEffort = ReasoningEffort.DEFAULT,
    /** One choice for every provider (#20); part of the config so a change rebuilds the transport. */
    val responseTimeout: ResponseTimeout = ResponseTimeout.AUTOMATIC,
)

/** Sorgente segreti on-demand: mai cache del plaintext, lettura suspend dallo storage cifrato. */
fun interface ProviderSecrets {
    suspend fun apiKey(id: ProviderId): String?
}

/**
 * Store multi-provider. Generalizza [BridgeConfigurationStore]: il contratto legacy diventa la
 * vista sul provider HERMES (base URL) e sul provider selezionato (bearer). Finché il selezionato
 * è HERMES — sempre in Wave 1, dopo la migrazione — il comportamento è bit-per-bit quello attuale.
 */
interface ProviderConfigStore : BridgeConfigurationStore, ProviderSecrets {
    fun selectedProviderId(): ProviderId
    suspend fun selectProvider(id: ProviderId): Boolean

    /** baseUrl/model mancanti -> fallback su [ProviderCatalog.spec]; mai null per HERMES. */
    fun providerConfig(id: ProviderId): ProviderConfig
    fun selectedProviderConfig(): ProviderConfig = providerConfig(selectedProviderId())

    /**
     * Parametri null conservano il valore esistente (stesso pattern di saveConfiguration). Eccezione S6:
     * un [baseUrl] con un'origine diversa da quella salvata e senza una chiave nuova cancella la chiave
     * salvata, che non deve partire verso un altro server ([baseUrlChangesOrigin]).
     */
    suspend fun saveProviderConfig(
        id: ProviderId,
        baseUrl: String? = null,
        model: String? = null,
        apiKey: String? = null,
        reasoningEffort: ReasoningEffort? = null,
    ): Boolean

    /** Response timeout chosen in Settings (#20), the same for every provider. */
    fun responseTimeout(): ResponseTimeout = ResponseTimeout.AUTOMATIC

    suspend fun saveResponseTimeout(value: ResponseTimeout): Boolean = false

    suspend fun hasApiKey(id: ProviderId): Boolean
    suspend fun clearApiKey(id: ProviderId): Boolean

    /**
     * S6: true se salvare [baseUrl] per [id] porta a un'altra origine (schema, host, porta) rispetto
     * all'indirizzo salvato. Una chiave salvata per un servizio cloud non deve partire verso l'indirizzo
     * scritto dopo (per esempio `http://` su loopback o sulla rete locale): senza una chiave nuova, il
     * salvataggio la cancella. Un indirizzo non valido non cambia nulla (il salvataggio lo rifiuta).
     */
    fun baseUrlChangesOrigin(id: ProviderId, baseUrl: String): Boolean {
        val normalized = normalizeProviderBaseUrl(baseUrl, id) ?: return false
        return !ProviderPrefsSchema.sameOrigin(providerConfig(id).baseUrl, normalized)
    }

    // ---- Ponte legacy: BridgeConfigurationStore diventa la vista Hermes/selected ----
    override fun baseUrl(): String = providerConfig(ProviderId.HERMES).baseUrl

    override suspend fun bearerToken(): String? = apiKey(selectedProviderId())

    override suspend fun saveBaseUrl(value: String): Boolean =
        saveProviderConfig(ProviderId.HERMES, baseUrl = value)

    override suspend fun saveBearerToken(value: String): Boolean =
        saveProviderConfig(ProviderId.HERMES, apiKey = value)

    override suspend fun clearBearerToken(): Boolean = clearApiKey(ProviderId.HERMES)

    override suspend fun saveConfiguration(baseUrl: String, bearerToken: String?): Boolean {
        // Semantica legacy: token null è ammesso solo se ne esiste già uno valido cifrato, e solo per lo
        // stesso server (S6): verso un'altra origine il bearer va reinserito.
        if (bearerToken == null && !hasApiKey(ProviderId.HERMES)) return false
        if (bearerToken == null && baseUrlChangesOrigin(ProviderId.HERMES, baseUrl)) return false
        return saveProviderConfig(ProviderId.HERMES, baseUrl = baseUrl, apiKey = bearerToken)
    }
}
