package dev.argus.automation.foreground

import android.content.BroadcastReceiver
import android.content.Context
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private const val RECEIVER_WORK_TAG = "ArgusReceiverWork"

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
 */
class ReceiverWorkLauncher(
    private val scope: CoroutineScope,
    private val sentinel: SharedForegroundSentinel,
) {
    private val sequence = AtomicLong()

    fun launch(
        source: String,
        releaseReceiver: () -> Unit,
        block: suspend () -> Unit,
    ): Job = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        val demand = SentinelDemand.Execution(sequence.incrementAndGet())
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
            if (leased) releaseLease(demand, source)
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

    private suspend fun releaseLease(demand: SentinelDemand.Execution, source: String) {
        withContext(NonCancellable) {
            if (sentinel.setDemand(demand, required = false)) return@withContext
            delay(RELEASE_RETRY_MILLIS)
            if (!sentinel.setDemand(demand, required = false)) {
                Log.w(RECEIVER_WORK_TAG, "lease foreground $source non rilasciata")
            }
        }
    }

    companion object {
        /** Sotto il limite foreground-broadcast di 10 s documentato dal framework. */
        internal const val FALLBACK_RECEIVER_LEASE_MILLIS = 8_000L
        private const val RELEASE_RETRY_MILLIS = 250L
    }
}

internal fun BroadcastReceiver.launchReceiverWork(
    context: Context,
    source: String,
    block: suspend () -> Unit,
) {
    val pending = goAsync()
    try {
        val launcher = EntryPointAccessors.fromApplication(
            context.applicationContext,
            ReceiverWorkEntryPoint::class.java,
        ).receiverWorkLauncher()
        launcher.launch(source, pending::finish, block)
    } catch (error: RuntimeException) {
        pending.finish()
        Log.e(RECEIVER_WORK_TAG, "entry point $source fallito: ${error::class.java.simpleName}")
    }
}
