package dev.argus.automation.triggers

import dev.argus.device.ForegroundFromUsage
import dev.argus.device.UsageActivityEvent
import dev.argus.engine.model.AppEventKind

/** Una transizione di app: [pkg] è passata in primo piano (OPENED) o lo ha lasciato (CLOSED) a [atMillis]. */
data class AppTransition(val pkg: String, val event: AppEventKind, val atMillis: Long)

/**
 * Trasforma gli eventi di attività di Android in transizioni «app aperta» / «app chiusa» (0.5.1), senza
 * eventi doppi. Logica pura: nessun orologio, nessun Android.
 *
 * Android non dice «l'app X è in primo piano»: dice che una sua attività è stata ripresa (RESUMED) o messa in
 * pausa (PAUSED/STOPPED). Per questo:
 *  - un'app è **aperta** quando la sua prima attività viene ripresa e prima non era in primo piano;
 *  - spostarsi fra due schermate della stessa app (una va in pausa, l'altra riprende a pochi millisecondi)
 *    NON è una chiusura: l'app resta aperta;
 *  - un'app è **chiusa** quando non ha più attività riprese e non ne riprende una entro [graceMillis]
 *    (oppure subito, se nel frattempo un'altra app è passata in primo piano);
 *  - più app possono essere in primo piano insieme (schermo diviso): ognuna ha le sue transizioni;
 *  - lo stesso evento riletto due volte non cambia nulla (RESUMED di un'attività già ripresa, PAUSED di
 *    una già in pausa).
 *
 * Le transizioni portano l'istante dell'evento di sistema (per la chiusura: quello della pausa), non
 * l'istante in cui le si scopre: così lo stesso evento ha sempre lo stesso identificativo.
 */
class AppTransitionTracker(private val graceMillis: Long = DEFAULT_GRACE_MILLIS) {
    /** Attività riprese ora: chiave `package/classe` → package. */
    private val resumed = LinkedHashMap<String, String>()

    /** App senza più attività riprese, in attesa di sapere se è una chiusura: package → istante della pausa. */
    private val pendingClose = LinkedHashMap<String, Long>()

    /**
     * Applica [events] (in ordine di istante) e restituisce le transizioni che ne risultano. [nowMillis] serve a
     * decidere quali chiusure in attesa sono ormai certe: chiamarlo anche senza eventi, a ogni lettura, per
     * far maturare le chiusure.
     */
    fun apply(events: List<UsageActivityEvent>, nowMillis: Long): List<AppTransition> {
        val out = ArrayList<AppTransition>()
        for (event in events.sortedBy { it.timeMillis }) {
            when (event.type) {
                ForegroundFromUsage.RESUMED -> onResumed(event, out)
                ForegroundFromUsage.PAUSED, ForegroundFromUsage.STOPPED -> onPaused(event)
            }
        }
        settle(nowMillis, out)
        return out
    }

    /** `true` se almeno un'app risulta in primo piano o sta per essere data per chiusa. */
    fun hasForeground(): Boolean = resumed.isNotEmpty() || pendingClose.isNotEmpty()

    /** Dimentica tutto: la prossima lettura riparte da zero (dopo una lunga interruzione). */
    fun reset() {
        resumed.clear()
        pendingClose.clear()
    }

    private fun onResumed(event: UsageActivityEvent, out: MutableList<AppTransition>) {
        val pkg = event.packageName
        // Un'altra app è passata in primo piano: le app in attesa di chiusura hanno lasciato davvero.
        pendingClose.entries.filter { it.key != pkg }.forEach { (other, pausedAt) ->
            out += AppTransition(other, AppEventKind.CLOSED, pausedAt)
            pendingClose.remove(other)
        }
        val pausedAt = pendingClose[pkg]
        var wasForeground = pkg in resumed.values
        if (pausedAt != null) {
            if (event.timeMillis - pausedAt > graceMillis) {
                // Era uscita davvero e ora rientra: chiusa, poi di nuovo aperta.
                out += AppTransition(pkg, AppEventKind.CLOSED, pausedAt)
                wasForeground = false
            } else {
                // Cambio di schermata dentro la stessa app: nessuna transizione.
                wasForeground = true
            }
            pendingClose.remove(pkg)
        }
        resumed[activityKey(event)] = pkg
        if (!wasForeground) out += AppTransition(pkg, AppEventKind.OPENED, event.timeMillis)
    }

    private fun onPaused(event: UsageActivityEvent) {
        val pkg = event.packageName
        // Un evento di pausa per un'attività che non risulta ripresa è un doppione o un resto: si ignora.
        if (resumed.remove(activityKey(event)) == null) return
        if (pkg !in resumed.values) pendingClose.putIfAbsent(pkg, event.timeMillis)
    }

    /** Le chiusure in attesa da almeno [graceMillis] sono certe: nessuna attività dell'app ha ripreso. */
    private fun settle(nowMillis: Long, out: MutableList<AppTransition>) {
        pendingClose.entries.filter { nowMillis - it.value >= graceMillis }.forEach { (pkg, pausedAt) ->
            out += AppTransition(pkg, AppEventKind.CLOSED, pausedAt)
            pendingClose.remove(pkg)
        }
    }

    private fun activityKey(event: UsageActivityEvent): String = event.packageName + "/" + event.className.orEmpty()

    companion object {
        /** Tempo entro cui la ripresa di una schermata della stessa app annulla la chiusura (cambio di schermata). */
        const val DEFAULT_GRACE_MILLIS = 1_200L
    }
}
