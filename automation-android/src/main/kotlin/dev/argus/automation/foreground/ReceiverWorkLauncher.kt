package dev.argus.automation.foreground

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.NoopAuditSink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

private const val RECEIVER_WORK_TAG = "ArgusReceiverWork"

/** Motivo del `ERROR` nel Log quando Android nega l'avvio del servizio in primo piano (B20). */
internal const val FOREGROUND_SERVICE_DENIED = "foreground_service_denied"

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface ReceiverWorkEntryPoint {
    fun receiverWorkLauncher(): ReceiverWorkLauncher
}

/**
 * Trasferisce il lavoro potenzialmente lungo di un receiver al runtime foreground condiviso.
 * `goAsync()` resta attivo soltanto durante l'acquisizione della lease FGS, non durante una
 * chiamata AI da 60–120 s. Se Android nega l'avvio FGS, conserva la protezione del broadcast per
 * una finestra breve e poi lascia proseguire il lavoro best-effort senza provocare un receiver ANR.
 *
 * Quando la lease è negata e il chiamante conosce la regola ([AutomationId]), il rifiuto lascia
 * una traccia `ERROR` / `foreground_service_denied` nel Log: prima il lavoro proseguiva alla cieca.
 */
class ReceiverWorkLauncher(
    private val scope: CoroutineScope,
    private val sentinel: SharedForegroundSentinel,
    private val audit: AuditSink = NoopAuditSink,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    /**
     * Variante per callback di sistema che non possiedono un PendingResult (per esempio il
     * NotificationListenerService): mantiene comunque la stessa lease FGS condivisa.
     */
    fun launch(
        source: String,
        block: suspend () -> Unit,
    ): Job = launch(source, releaseReceiver = {}, automationId = null, block = block)

    /** Come sopra, per un lavoro di una regola nota (traccia nel Log se la lease è negata). */
    fun launch(
        source: String,
        automationId: AutomationId?,
        block: suspend () -> Unit,
    ): Job = launch(source, releaseReceiver = {}, automationId = automationId, block = block)

    fun launch(
        source: String,
        releaseReceiver: () -> Unit,
        automationId: AutomationId? = null,
        block: suspend () -> Unit,
    ): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        val demand = SentinelDemand.Execution(sentinel.nextExecutionToken())
        val receiverReleased = AtomicBoolean(false)
        var leased = false

        fun releaseReceiverOnce() {
            if (!receiverReleased.compareAndSet(false, true)) return
            try {
                releaseReceiver()
            } catch (error: RuntimeException) {
                Log.w(RECEIVER_WORK_TAG, "release receiver $source fallita: ${error::class.java.simpleName}")
            }
        }

        try {
            leased = sentinel.setDemand(demand, required = true)
            if (leased) {
                // Da qui il processo è posseduto dal FGS: il broadcast può terminare subito.
                releaseReceiverOnce()
                runSafely(source, block)
            } else {
                Log.w(RECEIVER_WORK_TAG, "lease foreground $source non disponibile; fallback bounded")
                if (automationId != null) recordDenied(automationId)
                val work = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                    runSafely(source, block)
                }
                withTimeoutOrNull(FALLBACK_RECEIVER_LEASE_MILLIS) { work.join() }
                releaseReceiverOnce()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(RECEIVER_WORK_TAG, "launcher receiver $source fallito: ${error::class.java.simpleName}")
        } finally {
            releaseReceiverOnce()
            if (leased) sentinel.releaseExecution(demand)
        }
    }

    private suspend fun runSafely(source: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(RECEIVER_WORK_TAG, "lavoro receiver $source fallito: ${error::class.java.simpleName}")
        }
    }

    private suspend fun recordDenied(automationId: AutomationId) {
        try {
            audit.record(
                AuditEvent(
                    automationId,
                    AuditKind.ERROR,
                    nowMillis(),
                    detail = FOREGROUND_SERVICE_DENIED,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Il Log non deve cambiare l'esito del lavoro.
        }
    }

    companion object {
        /** Sotto il limite foreground-broadcast di 10 s documentato dal framework. */
        internal const val FALLBACK_RECEIVER_LEASE_MILLIS = 8_000L
    }
}

internal fun BroadcastReceiver.launchReceiverWork(
    context: Context,
    source: String,
    automationId: AutomationId? = null,
    block: suspend () -> Unit,
) {
    val pending = goAsync()
    try {
        val launcher = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReceiverWorkEntryPoint::class.java,
        ).receiverWorkLauncher()
        launcher.launch(source, pending::finish, automationId, block)
    } catch (error: RuntimeException) {
        pending.finish()
        Log.e(RECEIVER_WORK_TAG, "entry point $source fallito: ${error::class.java.simpleName}")
    }
}
