package dev.argus.device

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context

/** Un evento di utilizzo ridotto a ciò che serve per trovare l'app in primo piano. */
data class UsageEventRecord(val packageName: String, val type: Int, val timeMillis: Long)

/**
 * App in primo piano ricavata dagli eventi di `UsageStatsManager` (0.5.0), per la condizione
 * `app_in_foreground` quando non c'è nessun backend privilegiato. Logica pura e testabile.
 */
object ForegroundFromUsage {
    /** `ACTIVITY_RESUMED` = `MOVE_TO_FOREGROUND`. */
    const val RESUMED = 1

    /** `ACTIVITY_PAUSED` = `MOVE_TO_BACKGROUND`. */
    const val PAUSED = 2

    /** `ACTIVITY_STOPPED`. */
    const val STOPPED = 23

    /**
     * L'ultimo pacchetto andato in primo piano e non ancora andato via. `null` = non lo so: la
     * condizione fallisce chiusa (invariante 8), mai un valore inventato.
     */
    fun resolve(events: List<UsageEventRecord>): String? {
        var current: String? = null
        for (event in events.sortedBy { it.timeMillis }) {
            when (event.type) {
                RESUMED -> current = event.packageName
                PAUSED, STOPPED -> if (event.packageName == current) current = null
            }
        }
        return current
    }
}

fun interface ForegroundAppSource {
    /** Pacchetto in primo piano, `null` se non determinabile. Non lancia. */
    fun current(): String?
}

class UsageStatsForegroundSource(context: Context) : ForegroundAppSource {
    private val appContext = context.applicationContext

    override fun current(): String? = runCatching {
        if (!PermanentGrantStatus.usageStats(appContext)) return@runCatching null
        val manager = appContext.getSystemService(UsageStatsManager::class.java) ?: return@runCatching null
        val now = System.currentTimeMillis()
        // Prima una finestra corta (economica), poi una lunga per chi è in primo piano da ore.
        for (window in WINDOWS_MILLIS) {
            ForegroundFromUsage.resolve(events(manager, now - window, now))?.let { return@runCatching it }
        }
        null
    }.getOrNull()

    private fun events(manager: UsageStatsManager, from: Long, to: Long): List<UsageEventRecord> {
        val stream = manager.queryEvents(from, to) ?: return emptyList()
        val event = UsageEvents.Event()
        val records = ArrayList<UsageEventRecord>()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(event)
            val type = event.eventType
            if (type != ForegroundFromUsage.RESUMED && type != ForegroundFromUsage.PAUSED &&
                type != ForegroundFromUsage.STOPPED
            ) {
                continue
            }
            val pkg = event.packageName ?: continue
            records += UsageEventRecord(pkg, type, event.timeStamp)
        }
        return records
    }

    private companion object {
        val WINDOWS_MILLIS = longArrayOf(10 * 60_000L, 3 * 60 * 60_000L)
    }
}
