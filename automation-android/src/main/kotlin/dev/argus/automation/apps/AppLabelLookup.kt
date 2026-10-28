package dev.argus.automation.apps

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Process
import java.util.concurrent.ConcurrentHashMap

/**
 * Nome leggibile di un'app installata («YouTube» per `com.google.android.youtube`), dato dal telefono
 * tramite `LauncherApps` (le app con un'icona nel lanciatore, le stesse che l'utente apre davvero; non
 * richiede il permesso di vedere tutti i pacchetti). Serve solo al testo di approvazione delle regole
 * «app aperta/chiusa», che mostra comunque il package per intero. Nessun dato viene persistito.
 *
 * Le risposte si ricordano per il processo; una risposta vuota si ricorda poco, perché l'app potrebbe
 * essere installata subito dopo.
 */
class AndroidAppLabelLookup(context: Context) {
    private val appContext = context.applicationContext
    private val cache = ConcurrentHashMap<String, Cached>()

    private class Cached(val label: String?, val atMillis: Long)

    /** Il nome dell'app, o `null` se il telefono non la conosce. Non lancia mai. */
    fun labelOf(pkg: String): String? {
        val now = System.currentTimeMillis()
        cache[pkg]?.let { hit ->
            if (hit.label != null || now - hit.atMillis < MISS_TTL_MILLIS) return hit.label
        }
        val label = runCatching {
            appContext.getSystemService(LauncherApps::class.java)
                ?.getActivityList(pkg, Process.myUserHandle())
                ?.firstOrNull()
                ?.label
                ?.toString()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
        cache[pkg] = Cached(label, now)
        return label
    }

    private companion object {
        const val MISS_TTL_MILLIS = 60_000L
    }
}
