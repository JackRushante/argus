package dev.argus.automation.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.foreground.launchReceiverWork

/**
 * Dipendenze dei receiver telefonia risolte via entry point espliciti: l'injection Hilt dei
 * BroadcastReceiver vive in super.onReceive della classe generata e il primo P2-2 non la
 * chiamava mai (bug trovato live: lateinit mai inizializzato, nessuna regola SMS scattava).
 * Il grafo Singleton esiste sempre quando un receiver manifest gira: Application.onCreate
 * precede ogni delivery.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PhoneIngressEntryPoint {
    fun phoneEventIngress(): PhoneEventIngress
}

private fun entryPoint(context: Context): PhoneIngressEntryPoint = EntryPointAccessors
    .fromApplication(context.applicationContext, PhoneIngressEntryPoint::class.java)

/**
 * Guscio minimo sul framework: estrae primitive e delega a [PhoneEventIngress] (testato in
 * JVM). Nessun contenuto SMS nei log (solo conteggi/stati); `goAsync` copre il dispatch breve.
 */
class SmsBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = runCatching {
            Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty().filterNotNull().map {
                SmsPart(
                    sender = it.originatingAddress,
                    body = it.messageBody,
                    atMillis = it.timestampMillis,
                )
            }
        }.getOrDefault(emptyList())
        val sim = intent.simSlot(context)
        Log.d(TAG, "sms ricevuto: parts=${parts.size} sim=${sim != null}")
        if (parts.isEmpty()) return
        val entry = entryPoint(context)
        launchReceiverWork(context, "sms") {
            entry.phoneEventIngress().onSms(parts, sim)
        }
    }

    private companion object {
        const val TAG = "ArgusPhone"
    }
}

class PhoneStateBroadcastReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val atMillis = System.currentTimeMillis()
        val sim = intent.simSlot(context)
        Log.d(TAG, "call state: $state number=${number != null} sim=${sim != null}")
        val entry = entryPoint(context)
        launchReceiverWork(context, "phone_state") {
            entry.phoneEventIngress().onCallStateChanged(state, number, atMillis, sim)
        }
    }

    private companion object {
        const val TAG = "ArgusPhone"
    }
}

/**
 * SIM 1|2 da cui arriva il broadcast, o `null` se non determinabile (vedi [SimSlotResolver]). Usa solo
 * le API e gli extra pubblici: slot (`EXTRA_SLOT_INDEX`, legacy `slot`) e abbonamento
 * (`EXTRA_SUBSCRIPTION_INDEX`, legacy `subscription`).
 */
internal fun Intent.simSlot(context: Context): Int? = SimSlotResolver.resolve(
    slotIndexExtra = intExtra(SubscriptionManager.EXTRA_SLOT_INDEX, "slot"),
    subscriptionIdExtra = intExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, "subscription"),
) { subscriptionId -> slotOfSubscription(context, subscriptionId) }

private fun Intent.intExtra(vararg names: String): Int? = names.firstNotNullOfOrNull { name ->
    if (hasExtra(name)) getIntExtra(name, -1).takeIf { it >= 0 } else null
}

/** Richiede `READ_PHONE_STATE`: senza il grant (o con un abbonamento non attivo) è `null`. */
private fun slotOfSubscription(context: Context, subscriptionId: Int): Int? = try {
    context.getSystemService(SubscriptionManager::class.java)
        ?.getActiveSubscriptionInfo(subscriptionId)
        ?.simSlotIndex
        ?.takeIf { it >= 0 }
} catch (_: SecurityException) {
    null
}
