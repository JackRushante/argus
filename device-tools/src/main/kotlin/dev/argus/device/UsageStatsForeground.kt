package dev.argus.device

import android.content.Context

/**
 * App in primo piano ricavata dagli eventi di `UsageStatsManager` (0.5.0), per la condizione
 * `app_in_foreground` quando non c'è nessun backend privilegiato. Logica pura e testabile.
 *
 * Si ragiona per ATTIVITÀ (package + classe), come [UsageActivityEvent] e il tracker delle transizioni
 * di app: spostarsi fra due schermate della stessa app riprende la nuova e poi ferma la vecchia, e la
 * fermata della vecchia non deve far sparire l'app (0.7.2).
 */
object ForegroundFromUsage {
    /** `ACTIVITY_RESUMED` = `MOVE_TO_FOREGROUND`. */
    const val RESUMED = 1

    /** `ACTIVITY_PAUSED` = `MOVE_TO_BACKGROUND`. */
    const val PAUSED = 2

    /** `ACTIVITY_STOPPED`. */
    const val STOPPED = 23

    /**
     * Il package dell'ultima attività ripresa che non è ancora andata via. `null` = non lo so: la
     * condizione fallisce chiusa (invariante 8), mai un valore inventato.
     */
    fun resolve(events: List<UsageActivityEvent>): String? {
        // Attività riprese: chiave `package/classe` → package, in ordine di ripresa (l'ultima in fondo).
        val resumed = LinkedHashMap<String, String>()
        for (event in events.sortedBy { it.timeMillis }) {
            val key = event.packageName + "/" + event.className.orEmpty()
            when (event.type) {
                RESUMED -> {
                    resumed.remove(key)
                    resumed[key] = event.packageName
                }
                PAUSED, STOPPED -> resumed.remove(key)
            }
        }
        return resumed.values.lastOrNull()
    }
}

fun interface ForegroundAppSource {
    /** Pacchetto in primo piano, `null` se non determinabile. Non lancia. */
    fun current(): String?
}

class UsageStatsForegroundSource internal constructor(
    private val events: UsageEventsSource,
    private val now: () -> Long,
) : ForegroundAppSource {
    constructor(context: Context) : this(AndroidUsageEventsSource(context), System::currentTimeMillis)

    override fun current(): String? = runCatching {
        val to = now()
        // Prima una finestra corta (economica), poi una lunga per chi è in primo piano da ore.
        for (window in WINDOWS_MILLIS) {
            val read = events.read(to - window, to) ?: return@runCatching null
            ForegroundFromUsage.resolve(read)?.let { return@runCatching it }
        }
        null
    }.getOrNull()

    private companion object {
        val WINDOWS_MILLIS = longArrayOf(10 * 60_000L, 3 * 60 * 60_000L)
    }
}
