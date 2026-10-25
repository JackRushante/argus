package dev.argus.automation

import dev.argus.brain.BridgeConfigurationStore
import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderConfigStore
import kotlinx.coroutines.CancellationException

/** Stato runtime minimo che abilita la lane generativa, oltre ai grant già nello state Android. */
data class GenerativeReadiness(
    val bridgeConfigured: Boolean,
    val privacyAccepted: Boolean,
)

/**
 * Boundary asincrono: il bearer richiede una lettura suspend dello store cifrato, quindi la
 * readiness non può essere calcolata dentro un probe sincrono con runBlocking sul main thread.
 */
fun interface GenerativeRuntimeReadiness {
    suspend fun current(): GenerativeReadiness
}

class AndroidGenerativeRuntimeReadiness private constructor(
    private val keyReady: suspend () -> Boolean,
    private val preferences: AppPreferencesStore,
) : GenerativeRuntimeReadiness {
    /**
     * Provider selezionato pronto = ha la chiave che richiede. Un endpoint Custom anonimo (es.
     * llama-server locale, `apiKeyRequired = false`) è pronto anche senza chiave; il consenso privacy
     * resta valutato a parte.
     */
    constructor(configuration: ProviderConfigStore, preferences: AppPreferencesStore) : this(
        keyReady = {
            val id = configuration.selectedProviderId()
            !ProviderCatalog.spec(id).apiKeyRequired || configuration.hasApiKey(id)
        },
        preferences = preferences,
    )

    /** Store solo-bearer (legacy Hermes): pronto = bearer presente. */
    constructor(configuration: BridgeConfigurationStore, preferences: AppPreferencesStore) : this(
        keyReady = { configuration.bearerToken() != null },
        preferences = preferences,
    )

    override suspend fun current(): GenerativeReadiness = GenerativeReadiness(
        bridgeConfigured = try {
            keyReady()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        },
        privacyAccepted = preferences.observe().value.privacyAccepted,
    )
}
