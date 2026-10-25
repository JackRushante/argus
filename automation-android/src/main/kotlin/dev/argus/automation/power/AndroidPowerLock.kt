package dev.argus.automation.power

import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * `PARTIAL_WAKE_LOCK` non contato: il conteggio vive in [ExecutionWakeLock], qui l'acquisizione è
 * sempre con timeout (auto-rilascio) e il rilascio è idempotente. Un errore del sistema non deve
 * mai far fallire il lavoro che il lock protegge: si registra solo il tipo dell'eccezione.
 */
class AndroidPowerLock(context: Context, tag: String) : PowerLock {
    private val wakeLock: PowerManager.WakeLock? = try {
        context.applicationContext.getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, tag)
            ?.apply { setReferenceCounted(false) }
    } catch (error: RuntimeException) {
        Log.w(TAG, "wakelock non disponibile: ${error::class.java.simpleName}")
        null
    }

    override fun acquire(timeoutMillis: Long) {
        try {
            wakeLock?.acquire(timeoutMillis)
        } catch (error: RuntimeException) {
            Log.w(TAG, "acquisizione wakelock fallita: ${error::class.java.simpleName}")
        }
    }

    override fun release() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (error: RuntimeException) {
            Log.w(TAG, "rilascio wakelock fallito: ${error::class.java.simpleName}")
        }
    }

    companion object {
        const val EXECUTION_TAG = "argus:execution"
        const val WAIT_WAKEUP_TAG = "argus:wait-wakeup"
        private const val TAG = "ArgusPower"
    }
}
