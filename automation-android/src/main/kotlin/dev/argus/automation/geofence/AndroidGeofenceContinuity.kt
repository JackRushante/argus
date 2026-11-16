package dev.argus.automation.geofence

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.provider.Settings

/**
 * [GeofenceContinuity] reale (B8). Le proximity alert del `LocationManager` sopravvivono alla
 * morte del processo ma non a:
 * - un riavvio del telefono: `Settings.Global.BOOT_COUNT` diverso da quello salvato all'ultima
 *   registrazione riuscita;
 * - un arresto forzato (a mano, o dalla «pulizia» di alcuni produttori), che cancella i
 *   PendingIntent dell'app: motivo dell'ultima uscita del processo;
 * - dati dell'app cancellati o prima installazione: nessun valore salvato.
 *
 * Il giudizio si prende alla prima domanda del processo e, se positivo, si salva: un processo che
 * muore prima di aver riallineato le fence lascia il «forse perse» al successivo. Un contatore
 * illeggibile vale «forse perse» (il comportamento precedente), mai il contrario.
 */
class AndroidGeofenceContinuity(
    context: Context,
    /** Motivo dell'ultima uscita del processo (`ApplicationExitInfo.reason`), `null` se nessuna. */
    private val lastExitReason: () -> Int? = { lastExitReasonOf(context.applicationContext) },
) : GeofenceContinuity {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var lapsed: Boolean? = null

    @Synchronized
    override fun registrationsMayHaveLapsed(): Boolean {
        lapsed?.let { return it }
        val detected = prefs.getBoolean(KEY_LAPSED, false) ||
            bootCount().let { it == null || it != prefs.getInt(KEY_BOOT_COUNT, NO_BOOT_COUNT) } ||
            lastExitStoppedTheApp()
        if (detected) prefs.edit().putBoolean(KEY_LAPSED, true).commit()
        lapsed = detected
        return detected
    }

    @Synchronized
    override fun markRegistered() {
        val boot = bootCount() ?: return
        if (prefs.edit().putInt(KEY_BOOT_COUNT, boot).putBoolean(KEY_LAPSED, false).commit()) {
            lapsed = false
        }
    }

    private fun bootCount(): Int? = runCatching {
        Settings.Global.getInt(appContext.contentResolver, Settings.Global.BOOT_COUNT, NO_BOOT_COUNT)
    }.getOrNull()?.takeIf { it >= 0 }

    /** Un motivo illeggibile vale «forse perse». */
    private fun lastExitStoppedTheApp(): Boolean = runCatching {
        val reason = lastExitReason() ?: return false
        reason in stopReasons()
    }.getOrDefault(true)

    private fun stopReasons(): Set<Int> = buildSet {
        add(ApplicationExitInfo.REASON_USER_REQUESTED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(ApplicationExitInfo.REASON_PACKAGE_UPDATED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            add(ApplicationExitInfo.REASON_USER_STOPPED)
        }
    }

    internal companion object {
        const val PREFS_NAME = "argus_geofence_continuity"
        private const val KEY_BOOT_COUNT = "boot_count"
        private const val KEY_LAPSED = "lapsed"
        private const val NO_BOOT_COUNT = -1

        private fun lastExitReasonOf(context: Context): Int? {
            val manager = requireNotNull(context.getSystemService(ActivityManager::class.java))
            return manager.getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull()?.reason
        }
    }
}
