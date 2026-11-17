package dev.argus.automation.triggers

import android.content.Context
import android.util.Base64
import dev.argus.device.ForegroundFromUsage
import dev.argus.device.UsageActivityEvent
import dev.argus.device.UsageEventsSource
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Una regola armata `app_usage`: la soglia di oggi per [pkg]. */
data class AppUsageWatch(
    val id: AutomationId,
    val fingerprint: ApprovalFingerprint,
    val pkg: String,
    val minutes: Int,
)

/**
 * Il giorno locale (`2026-11-16`) in cui ogni regola è scattata l'ultima volta. Legato all'impronta: una
 * regola modificata e riapprovata riparte da capo. Sopravvive ai riavvii del processo.
 */
interface AppUsageFiredStore {
    fun firedDay(id: AutomationId, fingerprint: ApprovalFingerprint): String?
    fun markFired(id: AutomationId, fingerprint: ApprovalFingerprint, day: String)

    /** Dimentica le regole che non sono più armate. */
    fun retainOnly(ids: Set<AutomationId>)
}

/**
 * Tempo in primo piano di oggi per app, dagli eventi di attività (logica pura). Un'app è in primo piano da
 * quando riprende la sua prima attività a quando non ne ha più nessuna ripresa: cambiare schermata dentro
 * la stessa app non interrompe il conteggio. Gli intervalli si tagliano a `[dayStart, now]`; un'app ancora
 * aperta conta fino a [nowMillis]. Eventi doppi (ripresa di un'attività già ripresa) non cambiano nulla.
 */
object ForegroundTime {
    fun millisToday(
        events: List<UsageActivityEvent>,
        packages: Set<String>,
        dayStartMillis: Long,
        nowMillis: Long,
    ): Map<String, Long> {
        val resumed = HashMap<String, MutableSet<String>>()
        val since = HashMap<String, Long>()
        val totals = HashMap<String, Long>()
        fun add(pkg: String, from: Long, to: Long) {
            val start = maxOf(from, dayStartMillis)
            val end = minOf(to, nowMillis)
            if (end > start) totals[pkg] = (totals[pkg] ?: 0L) + (end - start)
        }
        for (event in events.sortedBy { it.timeMillis }) {
            val pkg = event.packageName
            if (pkg !in packages) continue
            val activities = resumed.getOrPut(pkg) { linkedSetOf() }
            val activity = event.className.orEmpty()
            when (event.type) {
                ForegroundFromUsage.RESUMED -> {
                    if (activities.isEmpty()) since[pkg] = event.timeMillis
                    activities += activity
                }
                ForegroundFromUsage.PAUSED, ForegroundFromUsage.STOPPED -> {
                    if (activities.remove(activity) && activities.isEmpty()) {
                        since.remove(pkg)?.let { add(pkg, it, event.timeMillis) }
                    }
                }
            }
        }
        since.forEach { (pkg, from) -> add(pkg, from, nowMillis) }
        return totals
    }
}

/**
 * Trigger `app_usage` (0.8): scatta una volta al giorno quando il tempo in primo piano di oggi di un'app
 * raggiunge la soglia della regola. «Oggi» è il giorno locale del telefono.
 *
 * Batteria: come la lettura di «app aperta/chiusa» gira SOLO a schermo acceso (l'uso cresce solo lì): un
 * controllo all'accensione, uno ogni [intervalMillis] e uno poco dopo lo spegnimento. Nessun wakelock né
 * allarme; senza regole armate la sorgente non è registrata affatto, e quando ogni regola è già scattata
 * oggi il controllo non legge nemmeno gli eventi.
 *
 * Una regola scatta da sola: l'evento consegnato è l'attraversamento esatto della sua soglia
 * (`soglia - 1 → soglia`), che il motore abbina solo alle regole con la stessa app e la stessa soglia.
 * Il giorno si segna PRIMA della consegna (mai due scatti nello stesso giorno, anche dopo un riavvio) e
 * l'id dell'evento contiene l'inizio del giorno: il motore deduplica un'eventuale seconda consegna.
 * Una regola armata quando la soglia è già superata scatta al primo controllo.
 */
class AppUsageMonitor(
    private val scope: CoroutineScope,
    private val source: UsageEventsSource,
    private val nowMillis: () -> Long,
    private val zone: () -> ZoneId,
    private val watches: () -> List<AppUsageWatch>,
    private val store: AppUsageFiredStore,
    private val emit: suspend (pkg: String, previousMinutes: Int, currentMinutes: Int, atMillis: Long) -> Unit,
    private val intervalMillis: Long = DEFAULT_INTERVAL_MILLIS,
) {
    private val lock = Any()
    private val checkLock = Mutex()
    private var loop: Job? = null
    private var finalCheck: Job? = null
    private var closed = false

    fun start(screenOn: Boolean) {
        if (screenOn) onScreenOn()
    }

    fun onScreenOn() {
        synchronized(lock) {
            if (closed) return
            finalCheck?.cancel()
            finalCheck = null
            if (loop?.isActive == true) return
            loop = scope.launch {
                while (isActive) {
                    safeCheck()
                    delay(intervalMillis)
                }
            }
        }
    }

    /** Ferma il ciclo; un ultimo controllo poco dopo conta il tempo fino allo spegnimento. */
    fun onScreenOff() {
        synchronized(lock) {
            if (closed) return
            loop?.cancel()
            loop = null
            finalCheck?.cancel()
            finalCheck = scope.launch {
                delay(FINAL_CHECK_DELAY_MILLIS)
                safeCheck()
            }
        }
    }

    fun close() {
        synchronized(lock) {
            closed = true
            loop?.cancel()
            finalCheck?.cancel()
            loop = null
            finalCheck = null
        }
    }

    private suspend fun safeCheck() {
        try {
            checkOnce()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Una lettura fallita non ferma il ciclo: il prossimo controllo riprova.
        }
    }

    /** Un controllo: legge l'uso di oggi delle app con regole non ancora scattate e consegna le soglie. */
    suspend fun checkOnce() {
        val due = checkLock.withLock { collectDue() }
        // La consegna esegue il programma della regola: fuori dal lock.
        due.forEach { (pkg, minutes, dayStart) -> emit(pkg, minutes - 1, minutes, dayStart) }
    }

    private fun collectDue(): List<Triple<String, Int, Long>> {
        val now = nowMillis()
        val zoneId = zone()
        val today = Instant.ofEpochMilli(now).atZone(zoneId).toLocalDate()
        val dayStart = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
        val armed = watches()
        store.retainOnly(armed.mapTo(hashSetOf()) { it.id })
        val pending = armed.filter { store.firedDay(it.id, it.fingerprint) != today.toString() }
        if (pending.isEmpty()) return emptyList()
        val packages = pending.mapTo(hashSetOf()) { it.pkg }
        // Un po' prima di mezzanotte: un'app già aperta allo scoccare del giorno conta da mezzanotte.
        val events = source.read((dayStart - LOOKBACK_MILLIS).coerceAtLeast(0L), now) ?: return emptyList()
        val usedMinutes = ForegroundTime.millisToday(events, packages, dayStart, now)
            .mapValues { (_, millis) -> (millis / MINUTE_MILLIS).toInt() }
        val due = mutableListOf<Triple<String, Int, Long>>()
        pending.groupBy { it.pkg to it.minutes }.forEach { (key, rules) ->
            val (pkg, minutes) = key
            if ((usedMinutes[pkg] ?: 0) < minutes) return@forEach
            // Segnato PRIMA della consegna: un riavvio a metà non fa riscattare la regola oggi.
            rules.forEach { store.markFired(it.id, it.fingerprint, today.toString()) }
            due += Triple(pkg, minutes, dayStart)
        }
        return due
    }

    companion object {
        /** A schermo acceso basta un controllo al minuto: la soglia è in minuti. */
        const val DEFAULT_INTERVAL_MILLIS = 60_000L
        const val FINAL_CHECK_DELAY_MILLIS = 3_000L
        private const val LOOKBACK_MILLIS = 3L * 60 * 60 * 1_000
        private const val MINUTE_MILLIS = 60_000L
    }
}

/** [AppUsageFiredStore] su `SharedPreferences`, `commit()` sincrono come gli altri stati dei trigger. */
class PrefsAppUsageFiredStore internal constructor(
    private val preferences: android.content.SharedPreferences,
) : AppUsageFiredStore {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    override fun firedDay(id: AutomationId, fingerprint: ApprovalFingerprint): String? {
        val stored = preferences.getString(key(id), null) ?: return null
        val separator = stored.indexOf(' ')
        if (separator <= 0) return null
        // Un giorno illeggibile vale «mai scattata oggi»: al peggio scatta, mai un crash.
        val day = stored.substring(0, separator).takeIf { runCatching { LocalDate.parse(it) }.isSuccess }
        return day?.takeIf { stored.substring(separator + 1) == fingerprint.value }
    }

    override fun markFired(id: AutomationId, fingerprint: ApprovalFingerprint, day: String) {
        check(preferences.edit().putString(key(id), "$day ${fingerprint.value}").commit()) {
            "app_usage_commit_failed"
        }
    }

    override fun retainOnly(ids: Set<AutomationId>) {
        val keep = ids.mapTo(hashSetOf(), ::key)
        val stale = preferences.all.keys.filter { it.startsWith(PREFIX) && it !in keep }
        if (stale.isEmpty()) return
        val editor = preferences.edit()
        stale.forEach(editor::remove)
        editor.commit()
    }

    private fun key(id: AutomationId): String =
        PREFIX + Base64.encodeToString(id.value.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)

    private companion object {
        const val PREFERENCES_NAME = "argus_app_usage_v1"
        const val PREFIX = "fired."
    }
}
