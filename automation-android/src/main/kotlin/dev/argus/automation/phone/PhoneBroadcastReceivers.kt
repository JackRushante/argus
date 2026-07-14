package dev.argus.automation.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.TelephonyManager
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import dev.argus.automation.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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

    @ApplicationScope
    fun applicationScope(): CoroutineScope
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
        Log.d(TAG, "sms ricevuto: parts=${parts.size}")
        if (parts.isEmpty()) return
        val entry = entryPoint(context)
        val pending = goAsync()
        entry.applicationScope().launch {
            try {
                entry.phoneEventIngress().onSms(parts)
            } finally {
                pending.finish()
            }
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
        Log.d(TAG, "call state: $state number=${number != null}")
        val entry = entryPoint(context)
        val pending = goAsync()
        entry.applicationScope().launch {
            try {
                entry.phoneEventIngress().onCallStateChanged(state, number, atMillis)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "ArgusPhone"
    }
}
