package dev.argus.automation

import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.isOneShot
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.NoopAuditSink
import dev.argus.engine.runtime.TimeSpecs
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Instant

enum class ScheduledAlarmMode { INEXACT, EXACT }

data class TimeAlarmRegistration(
    val automationId: AutomationId,
    val approvalFingerprint: ApprovalFingerprint,
    /** Istante logico dell'evento, usato per l'idempotency key anche in caso di retry. */
    val eventAtMillis: Long,
    /** Istante al quale svegliare davvero il processo; normalmente coincide con [eventAtMillis]. */
    val wakeAtMillis: Long = eventAtMillis,
    val requestedPrecision: TimePrecision,
) {
    init {
        require(eventAtMillis >= 0) { "eventAtMillis negativo" }
        require(wakeAtMillis >= 0) { "wakeAtMillis negativo" }
    }
}

data class ScheduledTimeAlarm(
    val automationId: AutomationId,
    val approvalFingerprint: ApprovalFingerprint,
    val eventAtMillis: Long,
    val wakeAtMillis: Long,
    val requestedPrecision: TimePrecision,
    val scheduledMode: ScheduledAlarmMode,
    val updatedAtMillis: Long,
) {
    init {
        require(eventAtMillis >= 0 && wakeAtMillis >= 0 && updatedAtMillis >= 0) {
            "Timestamp alarm negativo"
        }
        require(requestedPrecision == TimePrecision.EXACT || scheduledMode == ScheduledAlarmMode.INEXACT) {
            "Una regola FLEXIBLE non può risultare schedulata exact"
        }
    }
}

interface TimeAlarmStateStore {
    suspend fun get(automationId: AutomationId): ScheduledTimeAlarm?
    suspend fun all(): List<ScheduledTimeAlarm>
    suspend fun upsert(alarm: ScheduledTimeAlarm)
    suspend fun delete(automationId: AutomationId)
}

/** Boundary sincrono verso AlarmManager; l'implementazione gestisce il fallback exact -> inexact. */
interface TimeAlarmBackend {
    fun canScheduleExact(): Boolean
    fun schedule(registration: TimeAlarmRegistration): ScheduledAlarmMode
    fun cancel(automationId: AutomationId)
}

fun interface TimeEventDispatcher {
    suspend fun dispatch(envelope: TriggerEnvelope)
}

/**
 * Avvia in background un recupero all'avvio (B3). In produzione tiene anche la lease del servizio
 * in primo piano per la durata del lavoro; nei test lancia semplicemente una coroutine.
 */
fun interface RecoveryLauncher {
    fun launch(block: suspend () -> Unit)
}

enum class ReconcileReason(
    val recoverDueAlarm: Boolean,
    val recreateOsRegistration: Boolean = false,
) {
    /**
     * Un arresto forzato (a mano o dalla «pulizia» del produttore) cancella le sveglie ma lascia
     * lo stato persistito «registrato» (B17): all'avvio le sveglie armate si ri-registrano sempre.
     * Registrare di nuovo un PendingIntent identico costa poco.
     */
    APP_START(true, recreateOsRegistration = true),
    BOOT(true, recreateOsRegistration = true),
    PACKAGE_REPLACED(true, recreateOsRegistration = true),
    EXACT_ALARM_PERMISSION_CHANGED(true),
    CAPABILITY_CHANGED(true),

    /**
     * Se l'orario di rete corregge l'ora subito dopo l'accensione, un'occorrenza scaduta mentre il
     * telefono era spento è «dovuta» e va recuperata come ad APP_START/BOOT (B37), con lo stesso
     * event id: il journal deduplica. Cancellarla e dimenticarla la perderebbe.
     */
    TIME_CHANGED(true),

    /**
     * Gli istanti persistiti sono assoluti: un'occorrenza già scaduta al cambio di fuso (anche
     * quello impostato dalla rete subito dopo il boot) è stata davvero persa e si recupera come
     * per TIME_CHANGED.
     */
    TIMEZONE_CHANGED(true),
}

data class ReconcileReport(
    val scheduled: List<AutomationId>,
    val cancelled: List<AutomationId>,
    val recovered: List<AutomationId>,
    val failed: List<AutomationId>,
)

sealed interface AlarmDeliveryResult {
    data object Delivered : AlarmDeliveryResult
    data object Ignored : AlarmDeliveryResult
    data class Failed(val code: String) : AlarmDeliveryResult
}

object TimeAlarmPlanner {
    /**
     * Un ritardo relativo esplicito ("tra N") è puntuale per natura: il batching inexact OEM può
     * posticipare oltre il ritardo stesso (campo 2026-07-17: +2m33s su un timer di 3 minuti).
     * Il runtime lo tratta quindi come EXACT, con fallback inexact se manca il permesso. È una
     * politica di SCHEDULING, non parte del trigger: il fingerprint resta invariato.
     */
    fun effectivePrecision(trigger: Trigger.Time): TimePrecision =
        if (trigger.afterMs != null) TimePrecision.EXACT else trigger.precision

    fun next(
        automation: Automation,
        after: Instant,
        existing: ScheduledTimeAlarm? = null,
    ): TimeAlarmRegistration? {
        if (automation.status != AutomationStatus.ARMED || !automation.enabled) return null
        val trigger = automation.trigger as? Trigger.Time ?: return null
        val approvalFingerprint = requireNotNull(automation.approvalFingerprint) {
            "Automazione ARMED priva di fingerprint"
        }
        val afterMs = trigger.afterMs
        val eventAt = if (afterMs != null) {
            // Ritardo relativo: l'ancora si calcola UNA volta all'arm e poi si congela sul record
            // persistito. Senza questo freeze ogni reconcile ricalcolerebbe now+afterMs, slittando.
            existing?.let { Instant.ofEpochMilli(it.eventAtMillis) } ?: after.plusMillis(afterMs)
        } else {
            TimeSpecs.nextFire(trigger, after) ?: return null
        }
        return TimeAlarmRegistration(
            automationId = automation.id,
            approvalFingerprint = approvalFingerprint,
            eventAtMillis = eventAt.toEpochMilli(),
            requestedPrecision = effectivePrecision(trigger),
        )
    }
}

/**
 * Coordinatore single-writer di AlarmManager. Non mantiene un service: viene invocato da
 * Application/receiver e riconcilia Room con lo stato OS in modo idempotente.
 *
 * **Il lock non copre mai l'esecuzione (B3).** `dispatcher.dispatch` esegue l'intero programma
 * della regola (anche ore, con `wait`): una consegna procede in tre tempi.
 * 1. sotto lock: decide, prepara l'envelope e mette la regola in [inFlight];
 * 2. fuori dal lock: `dispatch`;
 * 3. di nuovo sotto lock: riprogramma o consuma, e toglie la regola da [inFlight].
 * Per tutta la durata il record di stato resta «dovuto» (come oggi): se il processo muore a metà,
 * APP_START/BOOT lo recupera con lo stesso event id e il journal Engine deduplica.
 */
class TimeAlarmCoordinator(
    private val store: AutomationStore,
    private val state: TimeAlarmStateStore,
    private val backend: TimeAlarmBackend,
    private val dispatcher: TimeEventDispatcher,
    private val now: () -> Instant,
    private val audit: AuditSink = NoopAuditSink,
    private val oneShotConsumptions: OneShotConsumptionRegistry = NoopOneShotConsumptionRegistry,
    /**
     * Se presente, i recuperi dovuti partono in background e `reconcile` NON ne attende la fine
     * (B3): un programma lungo (attese fino a un'ora) non deve ritardare geofence e sensori né
     * mettere in coda ogni altro reconcile. Il report non elenca allora i recuperi (`recovered`
     * resta vuoto). Senza launcher (test, caller non Android) i recuperi sono sincroni.
     */
    private val recoveryLauncher: RecoveryLauncher? = null,
) {
    private val mutex = Mutex()

    /** Coda sequenziale dei recuperi in background: l'ordine per sorgente (id) si preserva. */
    private val recoveryLane = Mutex()

    /**
     * Regole con un dispatch in corso fuori dal lock. Si legge e si scrive SOLO sotto [mutex].
     * `reconcile` le salta (niente recupero né seconda consegna) e un secondo `onAlarm` per la
     * stessa regola viene ignorato; a fine dispatch il completamento sotto lock è l'unico a
     * decidere (riprogrammare, consumare, oppure `cancelAndForget` se la regola è stata
     * disattivata o eliminata nel frattempo).
     */
    private val inFlight = mutableSetOf<AutomationId>()

    suspend fun reconcile(reason: ReconcileReason): ReconcileReport {
        val scheduled = mutableListOf<AutomationId>()
        val cancelled = mutableListOf<AutomationId>()
        val recovered = mutableListOf<AutomationId>()
        val failed = mutableListOf<AutomationId>()
        // Record dovuti da recuperare: la consegna avviene dopo aver rilasciato il lock.
        val dueRecoveries = mutableListOf<Pair<AutomationId, Long>>()

        mutex.withLock {
            val currentNow = now()
            val desired = store.armed()
                .filter { it.trigger is Trigger.Time }
                .associateBy { it.id }

            state.all().sortedBy { it.automationId.value }.forEach { persisted ->
                if (persisted.automationId in inFlight) return@forEach
                if (persisted.automationId !in desired) {
                    cancelAndForget(persisted.automationId)
                    cancelled += persisted.automationId
                }
            }

            desired.values.sortedBy { it.id.value }.forEach { automation ->
                if (automation.id in inFlight) return@forEach
                var persisted = state.get(automation.id)
                if (persisted != null && persisted.eventAtMillis <= currentNow.toEpochMilli()) {
                    if (reason.recoverDueAlarm) {
                        dueRecoveries += automation.id to persisted.eventAtMillis
                        return@forEach
                    }
                    cancelAndForget(automation.id)
                    cancelled += automation.id
                    persisted = null
                }

                when (
                    scheduleLocked(
                        automation,
                        currentNow,
                        persisted,
                        force = reason.recreateOsRegistration,
                    )
                ) {
                    ScheduleResult.SCHEDULED -> scheduled += automation.id
                    ScheduleResult.FAILED -> {
                        failed += automation.id
                        recordSchedulingFailed(automation.id, "scheduling_failed")
                    }
                    ScheduleResult.EXPIRED -> {
                        if (persisted != null) cancelled += automation.id
                        if ((automation.trigger as Trigger.Time).isOneShot()) {
                            val disabled = store.disableIfApproved(
                                automation.id,
                                requireNotNull(automation.approvalFingerprint),
                            )
                            // Lifecycle (task #31-B): la regola sparisce dalle armate, va tracciato.
                            if (disabled) {
                                recordAuditEvent(automation.id, AuditKind.RULE_DISABLED, "expired")
                            }
                        }
                        recordSchedulingFailed(automation.id, "expired")
                    }
                    ScheduleResult.UNCHANGED -> Unit
                }
            }
        }

        // Fuori dal lock: ogni recupero ripassa da prepare/dispatch/complete, quindi una regola nel
        // frattempo consegnata da un onAlarm (o già in volo) viene ignorata, mai consegnata due volte.
        val launcher = recoveryLauncher
        if (launcher != null) {
            if (dueRecoveries.isNotEmpty()) {
                val queued = dueRecoveries.toList()
                launcher.launch {
                    // Una coda sola: un secondo reconcile non fa partire un secondo giro in
                    // parallelo, aspetta il precedente e trova gli stessi record già consegnati.
                    recoveryLane.withLock {
                        queued.forEach { (automationId, eventAtMillis) ->
                            try {
                                deliver(automationId, eventAtMillis, deliveredFingerprint = null)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                // Il record resta dovuto: il prossimo APP_START/BOOT lo ritenta.
                            }
                        }
                    }
                }
            }
        } else {
            dueRecoveries.forEach { (automationId, eventAtMillis) ->
                when (deliver(automationId, eventAtMillis, deliveredFingerprint = null)) {
                    AlarmDeliveryResult.Delivered -> recovered += automationId
                    AlarmDeliveryResult.Ignored -> Unit
                    is AlarmDeliveryResult.Failed -> failed += automationId
                }
            }
        }
        return ReconcileReport(scheduled, cancelled, recovered, failed)
    }

    suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        eventAtMillis: Long,
    ): AlarmDeliveryResult = deliver(automationId, eventAtMillis, approvalFingerprint)

    private suspend fun deliver(
        automationId: AutomationId,
        eventAtMillis: Long,
        deliveredFingerprint: ApprovalFingerprint?,
    ): AlarmDeliveryResult {
        val prepared = when (
            val preparation = mutex.withLock {
                prepareDelivery(automationId, eventAtMillis, now(), deliveredFingerprint)
            }
        ) {
            is Preparation.Done -> return preparation.result
            is Preparation.Ready -> preparation
        }

        // Da qui la regola è in `inFlight`: va sempre rilasciata, anche su cancellazione.
        var released = false
        try {
            var dispatchFailed = false
            try {
                dispatcher.dispatch(prepared.envelope)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Il record resta intenzionalmente dovuto: APP_START/BOOT riprova lo stesso event ID.
                dispatchFailed = true
            }
            return mutex.withLock {
                inFlight -= automationId
                released = true
                if (dispatchFailed) {
                    deliveryFailed(automationId, "dispatch_failed")
                } else {
                    completeDelivery(prepared)
                }
            }
        } finally {
            if (!released) {
                // Cancellazione durante il dispatch (o in attesa del lock): il record resta dovuto
                // per il recupero, ma il marker in memoria non deve restare appeso.
                withContext(NonCancellable) { mutex.withLock { inFlight -= automationId } }
            }
        }
    }

    /** Sotto lock: decide e prepara la consegna; marca la regola in volo solo se si procede. */
    private suspend fun prepareDelivery(
        automationId: AutomationId,
        eventAtMillis: Long,
        currentNow: Instant,
        deliveredFingerprint: ApprovalFingerprint?,
    ): Preparation {
        if (automationId in inFlight) return Preparation.Done(AlarmDeliveryResult.Ignored)
        val persisted = state.get(automationId)
        if (persisted != null && persisted.eventAtMillis != eventAtMillis)
            return Preparation.Done(AlarmDeliveryResult.Ignored)
        if (persisted != null && deliveredFingerprint != null &&
            persisted.approvalFingerprint != deliveredFingerprint
        ) return Preparation.Done(AlarmDeliveryResult.Ignored)
        val eventFingerprint = persisted?.approvalFingerprint
            ?: deliveredFingerprint
            ?: return Preparation.Done(AlarmDeliveryResult.Ignored)

        val automation = store.get(automationId)
        val trigger = automation?.trigger as? Trigger.Time
        if (automation == null || trigger == null || automation.status != AutomationStatus.ARMED ||
            !automation.enabled
        ) {
            cancelAndForget(automationId)
            return Preparation.Done(AlarmDeliveryResult.Ignored)
        }
        if (automation.approvalFingerprint != eventFingerprint) {
            return Preparation.Done(
                when (scheduleLocked(automation, currentNow, existing = persisted)) {
                    ScheduleResult.FAILED -> deliveryFailed(automationId, "reschedule_failed")
                    else -> AlarmDeliveryResult.Ignored
                },
            )
        }

        // Stessa politica del planner (afterMs -> EXACT): il record di recovery deve rispecchiare
        // la precisione EFFETTIVA di scheduling, non quella dichiarata nel trigger.
        val recoveryPrecision = TimeAlarmPlanner.effectivePrecision(trigger)
        val recoveryRecord = persisted ?: ScheduledTimeAlarm(
            automationId = automationId,
            approvalFingerprint = eventFingerprint,
            eventAtMillis = eventAtMillis,
            wakeAtMillis = eventAtMillis,
            requestedPrecision = recoveryPrecision,
            scheduledMode = if (
                recoveryPrecision == TimePrecision.EXACT && backend.canScheduleExact()
            ) ScheduledAlarmMode.EXACT else ScheduledAlarmMode.INEXACT,
            updatedAtMillis = currentNow.toEpochMilli(),
        )
        if (persisted == null) {
            try {
                state.upsert(recoveryRecord)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Il PendingIntent autenticato resta comunque eseguibile; il journal deduplica.
            }
        }

        inFlight += automationId
        return Preparation.Ready(
            envelope = TriggerEnvelope(
                id = TriggerEventId("time:${eventFingerprint.value}:$eventAtMillis"),
                event = TriggerEvent.TimeFired(automationId, eventFingerprint),
            ),
            automationId = automationId,
            eventFingerprint = eventFingerprint,
            eventAtMillis = eventAtMillis,
            recoveryRecord = recoveryRecord,
        )
    }

    /**
     * Sotto lock, a dispatch concluso: riprogramma o consuma. L'orologio si rilegge qui perché il
     * programma può essere durato a lungo: la prossima occorrenza parte da «adesso», senza
     * accumulare scatti arretrati per i minuti passati a eseguire.
     */
    private suspend fun completeDelivery(prepared: Preparation.Ready): AlarmDeliveryResult {
        val automationId = prepared.automationId
        val eventFingerprint = prepared.eventFingerprint
        val eventAtMillis = prepared.eventAtMillis
        val recoveryRecord = prepared.recoveryRecord
        val envelope = prepared.envelope
        val currentNow = now()

        val latest = store.get(automationId)
        val latestTrigger = latest?.trigger as? Trigger.Time
        if (latest == null || latestTrigger == null || latest.status != AutomationStatus.ARMED ||
            !latest.enabled
        ) {
            cancelAndForget(automationId)
            return AlarmDeliveryResult.Delivered
        }
        if (latest.approvalFingerprint != eventFingerprint) {
            return when (scheduleLocked(latest, currentNow, existing = recoveryRecord)) {
                ScheduleResult.FAILED -> deliveryFailed(automationId, "reschedule_failed")
                else -> AlarmDeliveryResult.Delivered
            }
        }
        if (latestTrigger.isOneShot()) {
            val attempt = oneShotConsumptions.begin(envelope.id)
            var consumed = false
            try {
                consumed = store.disableIfApproved(automationId, eventFingerprint)
            } finally {
                attempt.complete(consumed)
            }
            if (consumed) {
                // Lifecycle (task #31-B): auto-disable post fire della one-shot (immediate/at/afterMs).
                recordAuditEvent(automationId, AuditKind.RULE_DISABLED, "one_shot_consumed")
                cancelAndForget(automationId)
                return AlarmDeliveryResult.Delivered
            }
            val revised = store.get(automationId)
            val revisedTrigger = revised?.trigger as? Trigger.Time
            if (revised == null || revisedTrigger == null ||
                revised.status != AutomationStatus.ARMED || !revised.enabled
            ) {
                cancelAndForget(automationId)
                return AlarmDeliveryResult.Delivered
            }
            return when (scheduleLocked(revised, currentNow, existing = recoveryRecord)) {
                ScheduleResult.FAILED -> deliveryFailed(automationId, "reschedule_failed")
                else -> AlarmDeliveryResult.Delivered
            }
        }

        val after = Instant.ofEpochMilli(maxOf(currentNow.toEpochMilli(), eventAtMillis))
        return when (scheduleLocked(latest, after, existing = recoveryRecord)) {
            ScheduleResult.SCHEDULED, ScheduleResult.UNCHANGED -> AlarmDeliveryResult.Delivered
            ScheduleResult.EXPIRED -> deliveryFailed(automationId, "reschedule_expired")
            ScheduleResult.FAILED -> deliveryFailed(automationId, "reschedule_failed")
        }
    }

    private sealed interface Preparation {
        data class Done(val result: AlarmDeliveryResult) : Preparation

        class Ready(
            val envelope: TriggerEnvelope,
            val automationId: AutomationId,
            val eventFingerprint: ApprovalFingerprint,
            val eventAtMillis: Long,
            val recoveryRecord: ScheduledTimeAlarm,
        ) : Preparation
    }

    private suspend fun scheduleLocked(
        automation: Automation,
        after: Instant,
        existing: ScheduledTimeAlarm?,
        force: Boolean = false,
    ): ScheduleResult {
        val registration = try {
            TimeAlarmPlanner.next(automation, after, existing)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            existing?.let { cancelAndForget(automation.id) }
            automation.approvalFingerprint?.let { fingerprint ->
                // Lifecycle (task #31-B): quarantena strutturale (planner non pianifica più
                // questa revisione), tracciata solo se la transizione è avvenuta davvero.
                if (store.markNeedsReviewIfApproved(automation.id, fingerprint)) {
                    recordAuditEvent(automation.id, AuditKind.RULE_NEEDS_REVIEW, "planner_failed")
                }
            }
            return ScheduleResult.FAILED
        } ?: run {
            if (existing != null) cancelAndForget(automation.id)
            return ScheduleResult.EXPIRED
        }

        val desiredMode = if (
            registration.requestedPrecision == TimePrecision.EXACT && backend.canScheduleExact()
        ) ScheduledAlarmMode.EXACT else ScheduledAlarmMode.INEXACT
        if (!force && existing != null &&
            existing.eventAtMillis == registration.eventAtMillis &&
            existing.wakeAtMillis == registration.wakeAtMillis &&
            existing.approvalFingerprint == registration.approvalFingerprint &&
            existing.requestedPrecision == registration.requestedPrecision &&
            existing.scheduledMode == desiredMode
        ) return ScheduleResult.UNCHANGED

        return try {
            val actualMode = backend.schedule(registration)
            state.upsert(
                ScheduledTimeAlarm(
                    automationId = automation.id,
                    approvalFingerprint = registration.approvalFingerprint,
                    eventAtMillis = registration.eventAtMillis,
                    wakeAtMillis = registration.wakeAtMillis,
                    requestedPrecision = registration.requestedPrecision,
                    scheduledMode = actualMode,
                    updatedAtMillis = now().toEpochMilli(),
                ),
            )
            ScheduleResult.SCHEDULED
        } catch (error: CancellationException) {
            existing?.let(::restoreRegistration)
            throw error
        } catch (_: Exception) {
            if (existing == null) {
                state.delete(automation.id)
            } else {
                restoreRegistration(existing)
            }
            ScheduleResult.FAILED
        }
    }

    private fun restoreRegistration(alarm: ScheduledTimeAlarm) {
        runCatching {
            backend.schedule(
                TimeAlarmRegistration(
                    automationId = alarm.automationId,
                    approvalFingerprint = alarm.approvalFingerprint,
                    eventAtMillis = alarm.eventAtMillis,
                    wakeAtMillis = alarm.wakeAtMillis,
                    requestedPrecision = alarm.requestedPrecision,
                ),
            )
        }
    }

    private suspend fun cancelAndForget(automationId: AutomationId) {
        try {
            backend.cancel(automationId)
        } finally {
            state.delete(automationId)
        }
    }

    /** Registra SCHEDULING_FAILED col reason chiuso e restituisce l'esito di consegna fallito. */
    private suspend fun deliveryFailed(
        automationId: AutomationId,
        reason: String,
    ): AlarmDeliveryResult {
        recordSchedulingFailed(automationId, reason)
        return AlarmDeliveryResult.Failed(reason)
    }

    private suspend fun recordSchedulingFailed(automationId: AutomationId, reason: String) =
        recordAuditEvent(automationId, AuditKind.SCHEDULING_FAILED, reason)

    private suspend fun recordAuditEvent(
        automationId: AutomationId,
        kind: AuditKind,
        reason: String,
    ) {
        try {
            audit.record(
                AuditEvent(
                    automationId,
                    kind,
                    now().toEpochMilli(),
                    detail = reason,
                ),
            )
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Il logging non deve cambiare l'esito dell'operazione.
        }
    }

    private enum class ScheduleResult { SCHEDULED, UNCHANGED, EXPIRED, FAILED }
}
