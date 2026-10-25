package dev.argus.automation.power

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Sveglia di ripresa per le attese lunghe: `ELAPSED_REALTIME_WAKEUP`, che conta anche il deep
 * sleep (a differenza del `delay` delle coroutine). Esatta se permesso, altrimenti inexact; la
 * ripresa non dipende dalla puntualità al secondo.
 */
class AndroidWakeAlarmBackend(context: Context) : WakeAlarmBackend {
    private val appContext = context.applicationContext
    private val alarmManager = requireNotNull(
        appContext.getSystemService(AlarmManager::class.java),
    ) { "AlarmManager non disponibile" }

    override fun schedule(token: Long, triggerAtElapsedMillis: Long) {
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent(appContext, token),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (canScheduleExact()) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    triggerAtElapsedMillis,
                    operation,
                )
                return
            } catch (_: SecurityException) {
                // Revoca concorrente del permesso: degrada a inexact.
            }
        }
        alarmManager.setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            triggerAtElapsedMillis,
            operation,
        )
    }

    override fun cancel(token: Long) {
        val operation = PendingIntent.getBroadcast(
            appContext,
            REQUEST_CODE,
            intent(appContext, token),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
        ) ?: return
        alarmManager.cancel(operation)
        operation.cancel()
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    companion object {
        internal const val ACTION_WAIT_WAKEUP = "dev.argus.automation.action.WAIT_WAKEUP"
        private const val REQUEST_CODE = 0

        /** L'URI rende l'intent univoco per token (filterEquals): niente PendingIntent condivisi. */
        internal fun intent(context: Context, token: Long): Intent =
            Intent(context, WaitWakeReceiver::class.java).apply {
                action = ACTION_WAIT_WAKEUP
                data = Uri.Builder()
                    .scheme("argus")
                    .authority("wait")
                    .appendPath(token.toString())
                    .build()
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }

        internal fun parseToken(intent: Intent?): Long? {
            if (intent?.action != ACTION_WAIT_WAKEUP) return null
            val data = intent.data ?: return null
            if (data.scheme != "argus" || data.host != "wait") return null
            return data.lastPathSegment?.toLongOrNull()?.takeIf { it >= 0 }
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface WaitWakeEntryPoint {
    fun waitWakeSignals(): WaitWakeSignals
}

/**
 * Riceve la sveglia di una attesa lunga e la segnala alla coroutine in attesa. Un token
 * sconosciuto (il processo è morto nel frattempo) non fa nulla: la regola in esecuzione è
 * comunque perduta e verrà recuperata dal journal.
 */
class WaitWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val token = AndroidWakeAlarmBackend.parseToken(intent) ?: return
        try {
            EntryPointAccessors.fromApplication(
                context.applicationContext,
                WaitWakeEntryPoint::class.java,
            ).waitWakeSignals().wakeUp(token)
        } catch (error: RuntimeException) {
            Log.w(TAG, "ripresa attesa fallita: ${error::class.java.simpleName}")
        }
    }

    private companion object {
        const val TAG = "ArgusWaitWake"
    }
}
