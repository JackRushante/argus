package dev.argus.automation.telephony

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallScreeningService
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.phone.PhoneEventIngress
import dev.argus.engine.model.ScreenCallOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

@EntryPoint
@InstallIn(SingletonComponent::class)
interface CallScreeningEntryPoint {
    fun callScreeningCoordinator(): CallScreeningCoordinator
    fun phoneEventIngress(): PhoneEventIngress
}

/**
 * Servizio di filtro chiamate (ruolo «filtro chiamate» di Android, chiesto solo quando una regola lo usa).
 * Android lo chiama per ogni chiamata in arrivo prima che squilli e aspetta una risposta per pochi secondi.
 *
 * Fallisce aperto: senza decisione, oltre il tempo, su qualunque errore la chiamata squilla. Una risposta
 * «lascia squillare» parte comunque da sola dopo [FALLBACK_MILLIS], qualunque cosa stia facendo il resto. Il
 * numero resta in memoria per la durata della decisione e non va mai nei log.
 */
class ArgusCallScreeningService : CallScreeningService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())

    override fun onScreenCall(callDetails: Call.Details) {
        if (callDetails.callDirection != Call.Details.DIRECTION_INCOMING) {
            respondSafely(callDetails, null)
            return
        }
        val answered = AtomicBoolean(false)
        fun answer(op: ScreenCallOp?): Boolean =
            answered.compareAndSet(false, true).also { first -> if (first) respondSafely(callDetails, op) }
        main.postDelayed({ answer(null) }, FALLBACK_MILLIS)
        val number = callDetails.handle?.schemeSpecificPart?.takeIf { it.isNotBlank() }
        val entry = runCatching {
            EntryPointAccessors.fromApplication(applicationContext, CallScreeningEntryPoint::class.java)
        }.getOrNull()
        if (entry == null) {
            answer(null)
            return
        }
        val receivedAt = System.currentTimeMillis()
        scope.launch {
            val op = runCatching { entry.callScreeningCoordinator().decide(number) }.getOrNull()
            val applied = answer(op)
            Log.d(TAG, "call screened: decision=${op ?: "ring"} applied=$applied")
            // Rifiutata: non squillerà, quindi le regole «chiamata in arrivo» partono da qui. Se la risposta
            // di riserva è già partita la chiamata squilla e arriva al motore dal solito broadcast.
            if (applied && op == ScreenCallOp.REJECT) {
                runCatching { entry.phoneEventIngress().onScreenedIncomingCall(number, receivedAt) }
            }
        }
    }

    private fun respondSafely(details: Call.Details, op: ScreenCallOp?) {
        runCatching { respondToCall(details, responseFor(op)) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "ArgusScreening"

        /** Sotto i circa 5 secondi che Android aspetta. */
        const val FALLBACK_MILLIS = 4_000L

        fun responseFor(op: ScreenCallOp?): CallResponse = when (op) {
            null -> CallResponse.Builder().build()
            ScreenCallOp.REJECT -> CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipCallLog(false)
                .setSkipNotification(false)
                .build()
            ScreenCallOp.SILENCE -> CallResponse.Builder().setSilenceCall(true).build()
        }
    }
}
