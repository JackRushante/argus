package dev.argus.automation

import android.os.SystemClock
import dev.argus.brain.BridgeConfigurationStore
import dev.argus.brain.BridgeErrorKind
import dev.argus.brain.BridgeException
import dev.argus.brain.BridgeHealth
import dev.argus.brain.CliBridgeTransport
import dev.argus.brain.HermesBrain
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.runtime.DeviceState
import kotlinx.coroutines.CancellationException

sealed interface BridgeHealthResult {
    data class Reachable(val health: BridgeHealth, val latencyMillis: Long) : BridgeHealthResult
    data class Unreachable(val kind: BridgeErrorKind) : BridgeHealthResult
}

/**
 * Facade runtime che ricostruisce soltanto quando cambia l'URL. Il bearer viene invece letto
 * dallo store protetto a ogni richiesta, quindi né URL né token richiedono un riavvio processo.
 */
internal class ConfiguredBridgeBrain(
    private val configuration: BridgeConfigurationStore,
    private val elapsedRealtimeMillis: () -> Long = SystemClock::elapsedRealtime,
) : Brain {
    @Volatile
    private var cached: Pair<String, CliBridgeTransport>? = null

    override suspend fun compile(
        nl: String,
        manifest: CapabilityManifest,
        state: DeviceState,
    ): CompileResult = HermesBrain(currentTransport()).compile(nl, manifest, state)

    suspend fun health(): BridgeHealthResult {
        val started = elapsedRealtimeMillis()
        return try {
            BridgeHealthResult.Reachable(
                health = currentTransport().health(),
                latencyMillis = (elapsedRealtimeMillis() - started).coerceAtLeast(0),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: BridgeException) {
            BridgeHealthResult.Unreachable(error.kind)
        } catch (_: IllegalArgumentException) {
            BridgeHealthResult.Unreachable(BridgeErrorKind.CONFIGURATION)
        }
    }

    suspend fun configured(): Boolean = configuration.bearerToken() != null

    private fun currentTransport(): CliBridgeTransport {
        val baseUrl = configuration.baseUrl()
        cached?.takeIf { it.first == baseUrl }?.second?.let { return it }
        return synchronized(this) {
            cached?.takeIf { it.first == baseUrl }?.second ?: CliBridgeTransport(
                baseUrl = baseUrl,
                authProvider = configuration,
            ).also { cached = baseUrl to it }
        }
    }
}
