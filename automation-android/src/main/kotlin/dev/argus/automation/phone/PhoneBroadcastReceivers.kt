package dev.argus.automation.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.TelephonyManager
import dagger.hilt.android.AndroidEntryPoint
import dev.argus.automation.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Guscio minimo sul framework: estrae primitive e delega a [PhoneEventIngress] (testato in JVM).
 * Nessun contenuto SMS nei log; `goAsync` copre il dispatch breve verso l'engine.
 */
@AndroidEntryPoint
class SmsBroadcastReceiver : BroadcastReceiver() {
    @Inject lateinit var ingress: PhoneEventIngress

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

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
        if (parts.isEmpty()) return
        val pending = goAsync()
        scope.launch {
            try {
                ingress.onSms(parts)
            } finally {
                pending.finish()
            }
        }
    }
}

@AndroidEntryPoint
class PhoneStateBroadcastReceiver : BroadcastReceiver() {
    @Inject lateinit var ingress: PhoneEventIngress

    @Inject
    @ApplicationScope
    lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        @Suppress("DEPRECATION")
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
        val atMillis = System.currentTimeMillis()
        val pending = goAsync()
        scope.launch {
            try {
                ingress.onCallStateChanged(state, number, atMillis)
            } finally {
                pending.finish()
            }
        }
    }
}
