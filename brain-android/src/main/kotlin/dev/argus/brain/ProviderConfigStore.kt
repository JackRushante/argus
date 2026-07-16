package dev.argus.brain

/**
 * Config UI/factory di un provider. NIENTE campo chiave a livello di tipo: i segreti passano solo
 * da [ProviderSecrets.apiKey]. `toString()`/`copy()` non possono quindi far trapelare la chiave.
 */
data class ProviderConfig(
    val providerId: ProviderId,
    val baseUrl: String,
    val model: String?,
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

    /** Parametri null conservano il valore esistente (stesso pattern di saveConfiguration). */
    suspend fun saveProviderConfig(
        id: ProviderId,
        baseUrl: String? = null,
        model: String? = null,
        apiKey: String? = null,
    ): Boolean

    suspend fun hasApiKey(id: ProviderId): Boolean
    suspend fun clearApiKey(id: ProviderId): Boolean

    // ---- Ponte legacy: BridgeConfigurationStore diventa la vista Hermes/selected ----
    override fun baseUrl(): String = providerConfig(ProviderId.HERMES).baseUrl

    override suspend fun bearerToken(): String? = apiKey(selectedProviderId())

    override suspend fun saveBaseUrl(value: String): Boolean =
        saveProviderConfig(ProviderId.HERMES, baseUrl = value)

    override suspend fun saveBearerToken(value: String): Boolean =
        saveProviderConfig(ProviderId.HERMES, apiKey = value)

    override suspend fun clearBearerToken(): Boolean = clearApiKey(ProviderId.HERMES)

    override suspend fun saveConfiguration(baseUrl: String, bearerToken: String?): Boolean {
        // Semantica legacy: token null è ammesso solo se ne esiste già uno valido cifrato.
        if (bearerToken == null && !hasApiKey(ProviderId.HERMES)) return false
        return saveProviderConfig(ProviderId.HERMES, baseUrl = baseUrl, apiKey = bearerToken)
    }
}
