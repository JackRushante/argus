package dev.argus.automation.calendar

import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.listenedTriggers
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.CalendarOccurrence
import dev.argus.engine.runtime.CalendarTriggerPlanner
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Esito di un reconcile: le regole calendario armate e quelle che non si sono potute programmare. */
data class CalendarReconcileReport(
    val requiredBy: List<AutomationId>,
    val failed: Set<AutomationId>,
)

fun interface CalendarTriggerRuntime {
    suspend fun reconcile(): CalendarReconcileReport
}

object NoopCalendarTriggerRuntime : CalendarTriggerRuntime {
    override suspend fun reconcile() = CalendarReconcileReport(emptyList(), emptySet())
}

/** La sveglia per regola del trigger calendario. Una sola per regola: riprogrammare sostituisce. */
interface CalendarAlarmScheduler {
    /** [exact] = l'istante di uno scatto (esatta se il permesso c'è); false = solo un ricontrollo. */
    fun schedule(automationId: AutomationId, fingerprint: ApprovalFingerprint, atMillis: Long, exact: Boolean)
    fun cancel(automationId: AutomationId)
}

/** Istanze già scattate per regola (deduplica persistita) e regole con una sveglia. */
interface CalendarFiredStore {
    fun fired(automationId: AutomationId): Set<String>

    /** Persiste lo scatto PRIMA della consegna: false = non persistito, quindi non si consegna. */
    fun markFired(automationId: AutomationId, key: String, fireAtMillis: Long, nowMillis: Long): Boolean
    fun scheduled(): Set<AutomationId>
    fun setScheduled(ids: Set<AutomationId>)
    fun forget(automationId: AutomationId)
}

/**
 * Trigger `calendar_event`: per ogni regola armata legge il calendario in una finestra di 7 giorni,
 * programma la prossima istanza con una sveglia per regola e, quando la sveglia suona, consegna le
 * istanze dovute una sola volta per fase (deduplica su id evento + inizio + fase, persistita).
 *
 * Senza istanze nei 7 giorni (o con l'istante oltre un giorno) programma un ricontrollo inesatto fra
 * [REPLAN_INTERVAL_MILLIS]. Il reconcile si ripete all'avvio del processo, all'arm, al ritorno in
 * primo piano, dopo ogni sveglia, al cambio di ora o fuso, al riavvio e quando il calendario cambia.
 * Senza permesso `READ_CALENDAR` le sveglie si tolgono e le regole risultano non programmabili.
 */
class CalendarTriggerCoordinator(
    private val store: AutomationStore,
    private val source: CalendarInstanceSource,
    private val firedStore: CalendarFiredStore,
    private val alarms: CalendarAlarmScheduler,
    private val dispatch: suspend (TriggerEnvelope) -> Unit,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : CalendarTriggerRuntime {
    private val mutex = Mutex()

    override suspend fun reconcile(): CalendarReconcileReport = mutex.withLock { reconcileLocked() }

    /** La sveglia della regola [automationId] è suonata. */
    suspend fun onAlarm(automationId: AutomationId, fingerprint: ApprovalFingerprint) {
        val toDeliver = mutex.withLock {
            val rule = store.get(automationId)
            // 0.8: l'inizio e, per una regola-intervallo, la fine (fasi diverse: validator); la deduplica è per fase.
            val triggers = rule?.calendarTriggers().orEmpty()
            val due = if (rule != null && triggers.isNotEmpty() && rule.isArmedCalendar() &&
                rule.approvalFingerprint == fingerprint
            ) {
                val now = nowMillis()
                val (from, to) = CalendarTriggerPlanner.queryWindow(now)
                source.read(from, to)
                    ?.let { instances ->
                        val fired = firedStore.fired(automationId)
                        triggers.flatMap { trigger -> CalendarTriggerPlanner.due(trigger, instances, now, fired) }
                            .distinctBy { it.key }
                            .sortedBy { it.fireAtMillis }
                    }
                    .orEmpty()
                    // Prima si persiste lo scatto, poi si consegna: al più una volta per istanza e fase.
                    .filter { firedStore.markFired(automationId, it.key, it.fireAtMillis, now) }
            } else {
                emptyList()
            }
            reconcileLocked()
            due
        }
        toDeliver.forEach { occurrence -> deliver(automationId, fingerprint, occurrence) }
    }

    private suspend fun deliver(automationId: AutomationId, fingerprint: ApprovalFingerprint, occurrence: CalendarOccurrence) {
        try {
            dispatch(
                TriggerEnvelope(
                    id = TriggerEventId("calendar:${fingerprint.value}:${occurrence.key}"),
                    event = TriggerEvent.CalendarEventFired(
                        automationId,
                        occurrence.phase,
                        fingerprint,
                        title = occurrence.title,
                    ),
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Il Log dell'Engine registra l'esito; uno scatto fallito non si ripete (deduplica).
        }
    }

    private suspend fun reconcileLocked(): CalendarReconcileReport {
        val rules = store.armed().filter { it.isArmedCalendar() }
        val ids = rules.mapTo(linkedSetOf()) { it.id }
        (firedStore.scheduled() - ids).forEach { stale ->
            runCatching { alarms.cancel(stale) }
            firedStore.forget(stale)
        }
        if (rules.isEmpty()) {
            firedStore.setScheduled(emptySet())
            return CalendarReconcileReport(emptyList(), emptySet())
        }
        val now = nowMillis()
        val (from, to) = CalendarTriggerPlanner.queryWindow(now)
        val instances = source.read(from, to)
        if (instances == null) {
            // Calendario illeggibile (permesso revocato): nessuna sveglia, le regole non sono programmate.
            rules.forEach { rule -> runCatching { alarms.cancel(rule.id) } }
            firedStore.setScheduled(emptySet())
            return CalendarReconcileReport(rules.map { it.id }, ids)
        }
        val failed = linkedSetOf<AutomationId>()
        rules.forEach { rule ->
            val fingerprint = requireNotNull(rule.approvalFingerprint)
            val fired = firedStore.fired(rule.id)
            // Una sveglia per regola: la prima istanza dovuta fra inizio e fine.
            val next = rule.calendarTriggers()
                .mapNotNull { trigger -> CalendarTriggerPlanner.next(trigger, instances, now, fired) }
                .minByOrNull { it.fireAtMillis }
            val replanAt = now + REPLAN_INTERVAL_MILLIS
            try {
                if (next != null && next.fireAtMillis <= replanAt) {
                    alarms.schedule(rule.id, fingerprint, next.fireAtMillis, exact = true)
                } else {
                    alarms.schedule(rule.id, fingerprint, replanAt, exact = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                failed += rule.id
            }
        }
        firedStore.setScheduled(ids - failed)
        return CalendarReconcileReport(rules.map { it.id }, failed)
    }

    private fun Automation.isArmedCalendar(): Boolean =
        status == AutomationStatus.ARMED && enabled && calendarTriggers().isNotEmpty() && approvalFingerprint != null

    private fun Automation.calendarTriggers(): List<Trigger.CalendarEvent> =
        listenedTriggers.filterIsInstance<Trigger.CalendarEvent>()

    companion object {
        /** Ricontrollo quando non c'è niente da programmare entro un giorno. */
        const val REPLAN_INTERVAL_MILLIS: Long = 24L * 60 * 60 * 1_000
    }
}
