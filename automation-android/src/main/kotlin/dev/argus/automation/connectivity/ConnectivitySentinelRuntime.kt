package dev.argus.automation.connectivity

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeoutOrNull

/** Stato osservabile del servizio reale; non considera attivo un semplice start richiesto. */
class ConnectivitySentinelStatus {
    private val mutableActive = MutableStateFlow(false)

    val active: StateFlow<Boolean> = mutableActive.asStateFlow()

    internal fun setActive(active: Boolean) {
        mutableActive.value = active
    }
}

/** Traduce start/stop del coordinator in un FGS e ne attende l'esito effettivo. */
class AndroidConnectivitySentinelBackend(
    context: Context,
    private val status: ConnectivitySentinelStatus,
    private val transitionTimeoutMillis: Long = TRANSITION_TIMEOUT_MILLIS,
) : ConnectivitySentinelBackend {
    private val appContext = context.applicationContext

    override suspend fun start(): Boolean {
        if (status.active.value) return true
        return try {
            ContextCompat.startForegroundService(
                appContext,
                Intent(appContext, ConnectivitySentinelService::class.java),
            )
            awaitActive(expected = true)
        } catch (error: CancellationException) {
            throw error
        } catch (error: SecurityException) {
            Log.w(TAG, "start sentinella negato: ${error::class.java.simpleName}")
            false
        } catch (error: RuntimeException) {
            // Include ForegroundServiceStartNotAllowedException su API 31+ senza referenziarla
            // direttamente sui device API 30.
            Log.w(TAG, "start sentinella fallito: ${error::class.java.simpleName}")
            false
        }
    }

    override suspend fun stop(): Boolean = try {
        appContext.stopService(Intent(appContext, ConnectivitySentinelService::class.java))
        if (!status.active.value) true else awaitActive(expected = false)
    } catch (error: CancellationException) {
        throw error
    } catch (error: SecurityException) {
        Log.w(TAG, "stop sentinella negato: ${error::class.java.simpleName}")
        false
    } catch (error: RuntimeException) {
        Log.w(TAG, "stop sentinella fallito: ${error::class.java.simpleName}")
        false
    }

    private suspend fun awaitActive(expected: Boolean): Boolean = withTimeoutOrNull(
        transitionTimeoutMillis,
    ) {
        status.active.map { it == expected }.first { it }
        true
    } ?: false

    private companion object {
        const val TAG = "ArgusConnectivity"
        const val TRANSITION_TIMEOUT_MILLIS = 5_000L
    }
}
