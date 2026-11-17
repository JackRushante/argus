package dev.argus.device

import android.annotation.SuppressLint
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/**
 * Un evento di attività di `UsageStatsManager` ridotto a ciò che serve per sapere quale app è in primo
 * piano: il package, l'attività (una stessa app ne ha spesso più d'una), il tipo e l'istante.
 * [type] è uno di [ForegroundFromUsage.RESUMED], [ForegroundFromUsage.PAUSED], [ForegroundFromUsage.STOPPED].
 */
data class UsageActivityEvent(
    val packageName: String,
    val className: String?,
    val type: Int,
    val timeMillis: Long,
)

/** Sorgente degli eventi di attività in un intervallo. Esiste per poterla sostituire nei test. */
fun interface UsageEventsSource {
    /**
     * Gli eventi con istante in `[fromMillis, toMillis)`, in ordine di istante, oppure `null` se non si
     * possono leggere (accesso ai dati di utilizzo mancante, servizio assente). Non lancia mai.
     */
    fun read(fromMillis: Long, toMillis: Long): List<UsageActivityEvent>?
}

/** Lettura reale degli eventi di attività (0.5.1, trigger «app aperta/chiusa»). */
class AndroidUsageEventsSource(context: Context) : UsageEventsSource {
    private val appContext = context.applicationContext

    // PACKAGE_USAGE_STATS is a special access the user grants in Settings, not a runtime permission: the app
    // manifest declares it and the grant is checked on the line below before the query, which lint cannot see
    // from this library module.
    @SuppressLint("MissingPermission")
    override fun read(fromMillis: Long, toMillis: Long): List<UsageActivityEvent>? = runCatching {
        if (!PermanentGrantStatus.usageStats(appContext)) return@runCatching null
        val manager = appContext.getSystemService(UsageStatsManager::class.java) ?: return@runCatching null
        val stream = manager.queryEvents(fromMillis, toMillis) ?: return@runCatching null
        val event = UsageEvents.Event()
        val records = ArrayList<UsageActivityEvent>()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(event)
            val type = event.eventType
            if (type != ForegroundFromUsage.RESUMED && type != ForegroundFromUsage.PAUSED &&
                type != ForegroundFromUsage.STOPPED
            ) {
                continue
            }
            val pkg = event.packageName ?: continue
            records += UsageActivityEvent(pkg, event.className, type, event.timeStamp)
        }
        records
    }.getOrNull()
}
