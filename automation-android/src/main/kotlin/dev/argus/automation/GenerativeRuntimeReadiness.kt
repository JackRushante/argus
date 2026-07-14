package dev.argus.automation

import dev.argus.brain.BridgeConfigurationStore
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

class AndroidGenerativeRuntimeReadiness(
    private val configuration: BridgeConfigurationStore,
    private val preferences: AppPreferencesStore,
) : GenerativeRuntimeReadiness {
    override suspend fun current(): GenerativeReadiness = GenerativeReadiness(
        bridgeConfigured = try {
            configuration.bearerToken() != null
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            false
        },
        privacyAccepted = preferences.observe().value.privacyAccepted,
    )
}
