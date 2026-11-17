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
import dev.argus.engine.runtime.SolarSchedule
import dev.argus.engine.runtime.SpanPhase
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

/** 0.8: il coordinatore delle sveglie di FINE delle regole-intervallo (distinto da quello d'inizio nel grafo DI). */
class SpanExitTimeAlarms(val coordinator: TimeAlarmCoordinator)

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

    /**
     * Sveglia di riserva INEXACT (allow-while-idle) che rilancia la riconciliazione a [atMillis];
     * `null` la toglie. Quando l'utente revoca «Sveglie e promemoria» Android cancella le sveglie
     * exact senza alcun broadcast: questa sopravvive e fa riprogrammare tutto in inexact.
     */
    fun syncWatchdog(atMillis: Long?) = Unit
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

    /**
     * 0.8: la prossima sveglia del telefono è cambiata (`ACTION_NEXT_ALARM_CLOCK_CHANGED`): le regole
     * `next_alarm` si ripianificano. Un'occorrenza già dovuta si recupera come per TIME_CHANGED.
     */
    NEXT_ALARM_CHANGED(true),
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

    /** Precisione di scheduling di un trigger pianificato da questo coordinatore. */
    fun effectivePrecision(trigger: Trigger): TimePrecision = when (trigger) {
        is Trigger.Time -> effectivePrecision(trigger)
        // Minuti prima della sveglia: puntuale come un ritardo relativo (fallback inexact senza permesso).
        else -> TimePrecision.EXACT
    }

    /**
     * 0.8: [Trigger.NextAlarm] scatta [Trigger.NextAlarm.offsetMinutes] minuti prima della prossima sveglia
     * ([nextAlarmAt], `null` = nessuna sveglia). Un istante già passato (sveglia più vicina dell'offset, o
     * questa istanza già consegnata) non si pianifica: si aspetta la sveglia successiva. Così ogni istanza
     * della sveglia scatta al più una volta.
     */
    fun nextAlarm(
        automation: Automation,
        after: Instant,
        nextAlarmAt: () -> Long?,
        /** 0.8: il trigger da pianificare (quello d'inizio, o quello di fine di una regola-intervallo). */
        planned: Trigger? = automation.trigger,
    ): TimeAlarmRegistration? {
        if (automation.status != AutomationStatus.ARMED || !automation.enabled) return null
        val trigger = planned as? Trigger.NextAlarm ?: return null
        val approvalFingerprint = requireNotNull(automation.approvalFingerprint) {
            "Automazione ARMED priva di fingerprint"
        }
        val alarmAt = nextAlarmAt()?.takeIf { it > 0 } ?: return null
        val eventAt = alarmAt - trigger.offsetMinutes * MINUTE_MILLIS
        if (eventAt <= after.toEpochMilli() || eventAt < 0) return null
        return TimeAlarmRegistration(
            automationId = automation.id,
            approvalFingerprint = approvalFingerprint,
            eventAtMillis = eventAt,
            requestedPrecision = TimePrecision.EXACT,
        )
    }

    /**
     * Un'occorrenza `next_alarm` dovuta (processo morto, telefono spento) si recupera solo finché la sveglia
     * a cui si riferisce non è suonata da più di [NEXT_ALARM_RECOVERY_GRACE_MILLIS]: dopo non ha più senso.
     */
    fun nextAlarmStillDue(trigger: Trigger.NextAlarm, eventAtMillis: Long, nowMillis: Long): Boolean =
        nowMillis <= eventAtMillis + trigger.offsetMinutes * MINUTE_MILLIS + NEXT_ALARM_RECOVERY_GRACE_MILLIS

    private const val MINUTE_MILLIS = 60_000L
    const val NEXT_ALARM_RECOVERY_GRACE_MILLIS = 10L * 60 * 1_000

    fun next(
        automation: Automation,
        after: Instant,
        existing: ScheduledTimeAlarm? = null,
        /** 0.8: il trigger da pianificare (quello d'inizio, o quello di fine di una regola-intervallo). */
        planned: Trigger? = automation.trigger,
    ): TimeAlarmRegistration? {
        if (automation.status != AutomationStatus.ARMED || !automation.enabled) return null
        val trigger = planned as? Trigger.Time ?: return null
        val approvalFingerprint = requireNotNull(automation.approvalFingerprint) {
            "Automazione ARMED priva di fingerprint"
        }
        val afterMs = trigger.afterMs
        // Un record persistito vale solo per la revisione approvata che l'ha prodotto: dopo una
        // modifica riapprovata («tra 10 minuti» → «tra 3 ore») la vecchia ancora non va riusata.
        val sameRevision = existing?.takeIf { it.approvalFingerprint == approvalFingerprint }
        val eventAt = if (trigger.solar != null) {
            solarEventAt(trigger, after, sameRevision)
        } else if (afterMs != null) {
            // Ritardo relativo: l'ancora si calcola UNA volta all'arm e poi si congela sul record
            // persistito. Senza questo freeze ogni reconcile ricalcolerebbe now+afterMs, slittando.
            sameRevision?.let { Instant.ofEpochMilli(it.eventAtMillis) } ?: after.plusMillis(afterMs)
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

    /**
     * Alba/tramonto (0.4.1): il prossimo scatto vero, oppure, in notte/giorno polare, un RICONTROLLO
     * fra 24 ore (la sveglia suona, il coordinatore non consegna nulla alle azioni e ricalcola; vedi
     * [SolarSchedule.isFireInstant]). Un ricontrollo già programmato per la stessa revisione non
     * slitta a ogni reconcile: si tiene quello esistente finché non scade.
     */
    private fun solarEventAt(trigger: Trigger.Time, after: Instant, existing: ScheduledTimeAlarm?): Instant =
        when (val next = SolarSchedule.next(trigger, after)) {
            is SolarSchedule.Next.Fire -> next.at
            is SolarSchedule.Next.Recheck -> existing?.eventAtMillis
                ?.takeIf { it > after.toEpochMilli() && it <= next.at.toEpochMilli() }
                ?.let(Instant::ofEpochMilli)
                ?: next.at
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
    /**
     * 0.8: istante della prossima sveglia del telefono (`AlarmManager.getNextAlarmClock`), per le regole
     * `next_alarm`; `null` = nessuna sveglia. Letto a ogni pianificazione, mai conservato.
     */
    private val nextAlarmAt: () -> Long? = { null },
    /**
     * 0.8 regole-intervallo: [SpanPhase.EXIT] = questo coordinatore pianifica il trigger di FINE delle regole
     * (con il suo stato, il suo backend e i suoi id evento), mentre quello d'inizio resta com'era. I due non si
     * vedono: ogni regola ha al più una sveglia per fase.
     */
    private val phase: SpanPhase = SpanPhase.START,
) {
    /** Il trigger che questo coordinatore pianifica per [automation], se è suo. */
    private fun triggerOf(automation: Automation): Trigger? =
        (if (phase == SpanPhase.START) automation.trigger else automation.exit?.trigger)?.takeIf { it.scheduledHere() }

    private val idPrefix: String = if (phase == SpanPhase.START) "" else "-exit"

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

    /**
     * Regole viste NON armate mentre il loro record persistito sopravviveva (pulizia rinviata: in
     * volo o guasto di Room). Se tornano armate (riattivazione) quel record appartiene all'arming
     * precedente: non si recupera come «dovuto» né se ne riusa l'ancora. Solo sotto [mutex]; dopo
     * la morte del processo APP_START, con la regola ancora spenta, ripulisce comunque il record.
     */
    private val disarmed = mutableSetOf<AutomationId>()

    /** Ultimo istante passato a [TimeAlarmBackend.syncWatchdog] in questo processo; solo sotto [mutex]. */
    private var watchdogAt: Long? = null
    private var watchdogSynced = false

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
                .filter { triggerOf(it) != null }
                .associateBy { it.id }

            state.all().sortedBy { it.automationId.value }.forEach { persisted ->
                if (persisted.automationId !in desired) {
                    disarmed += persisted.automationId
                    if (persisted.automationId in inFlight) return@forEach
                    forgetDisarmed(persisted.automationId)
                    cancelled += persisted.automationId
                }
            }

            desired.values.sortedBy { it.id.value }.forEach { automation ->
                if (automation.id in inFlight) return@forEach
                var persisted = state.get(automation.id)
                if (automation.id in disarmed) {
                    // Riattivata dopo una pulizia rinviata: il record è dell'arming precedente.
                    if (persisted != null) {
                        cancelAndForget(automation.id)
                        persisted = null
                    }
                    disarmed -= automation.id
                }
                if (persisted != null && persisted.eventAtMillis <= currentNow.toEpochMilli()) {
                    // 0.8: «prima della sveglia» dopo che la sveglia è suonata non ha più senso.
                    val dueAt = persisted.eventAtMillis
                    val stale = (triggerOf(automation) as? Trigger.NextAlarm)?.let { trigger ->
                        !TimeAlarmPlanner.nextAlarmStillDue(trigger, dueAt, currentNow.toEpochMilli())
                    } == true
                    if (reason.recoverDueAlarm && !stale) {
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
                        // Solo l'inizio si consuma: una fine non è mai a ora singola (validator).
                        if (phase == SpanPhase.START && (triggerOf(automation) as? Trigger.Time)?.isOneShot() == true) {
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
                    // 0.8: nessuna sveglia da anticipare ora; si ripianifica al prossimo cambio di sveglia.
                    ScheduleResult.IDLE -> if (persisted != null) cancelled += automation.id
                }
            }
            syncWatchdogLocked()
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
                val decided = prepareDelivery(automationId, eventAtMillis, now(), deliveredFingerprint)
                syncWatchdogLocked()
                decided
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
                val result = if (dispatchFailed) {
                    deliveryFailed(automationId, "dispatch_failed")
                } else {
                    completeDelivery(prepared)
                }
                syncWatchdogLocked()
                result
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
        val trigger = automation?.let(::triggerOf)
        if (automation == null || trigger == null || automation.status != AutomationStatus.ARMED ||
            !automation.enabled
        ) {
            forgetDisarmed(automationId)
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

        // Alba/tramonto: se l'istante non è un vero scatto è un ricontrollo di notte/giorno polare.
        // Niente da consegnare: si ricalcola da adesso (un nuovo evento o un altro ricontrollo).
        // Un guasto del calcolo conta come «non è uno scatto», e il planner mette la regola in quarantena.
        if (trigger is Trigger.Time && trigger.solar != null &&
            !runCatching { SolarSchedule.isFireInstant(trigger, eventAtMillis) }.getOrDefault(false)
        ) {
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
            envelope = if (trigger is Trigger.NextAlarm) {
                // 0.8: un id per istanza della sveglia (l'istante deriva dalla sveglia e dall'offset approvato).
                TriggerEnvelope(
                    id = TriggerEventId("next_alarm$idPrefix:${eventFingerprint.value}:$eventAtMillis"),
                    event = TriggerEvent.NextAlarmFired(automationId, eventFingerprint, spanPhase = phase),
                )
            } else {
                TriggerEnvelope(
                    id = TriggerEventId("time$idPrefix:${eventFingerprint.value}:$eventAtMillis"),
                    event = TriggerEvent.TimeFired(automationId, eventFingerprint, spanPhase = phase),
                )
            },
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
        // Chi completa decide dallo stato più recente: un disable visto durante il volo non conta più.
        disarmed -= automationId

        val latest = store.get(automationId)
        val latestTrigger = latest?.let(::triggerOf)
        if (latest == null || latestTrigger == null || latest.status != AutomationStatus.ARMED ||
            !latest.enabled
        ) {
            forgetDisarmed(automationId)
            return AlarmDeliveryResult.Delivered
        }
        if (latest.approvalFingerprint != eventFingerprint) {
            return when (scheduleLocked(latest, currentNow, existing = recoveryRecord)) {
                ScheduleResult.FAILED -> deliveryFailed(automationId, "reschedule_failed")
                else -> AlarmDeliveryResult.Delivered
            }
        }
        if (latestTrigger is Trigger.Time && latestTrigger.isOneShot()) {
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
            val revisedTrigger = revised?.let(::triggerOf)
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
            // IDLE: istanza della sveglia consegnata, la prossima arriva col suo cambio di sveglia.
            ScheduleResult.SCHEDULED, ScheduleResult.UNCHANGED, ScheduleResult.IDLE -> AlarmDeliveryResult.Delivered
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
        val planned = triggerOf(automation)
        val registration = try {
            if (planned is Trigger.NextAlarm) {
                TimeAlarmPlanner.nextAlarm(automation, after, nextAlarmAt, planned)
            } else {
                TimeAlarmPlanner.next(automation, after, existing, planned)
            }
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
            // 0.8: una regola next_alarm senza sveglia futura non è scaduta: aspetta la prossima sveglia.
            return if (planned is Trigger.NextAlarm) ScheduleResult.IDLE else ScheduleResult.EXPIRED
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

    /**
     * Sotto lock: una sola sveglia di riserva, poco dopo la prossima sveglia EXACT futura (le
     * inexact sopravvivono alla revoca del permesso, le dovute e quelle in volo hanno già il loro
     * recupero). Se il permesso c'è ancora, a quell'ora la sveglia exact ha già riprogrammato e
     * il reconcile non cambia nulla. Un guasto qui non cambia mai l'esito dell'operazione.
     */
    private suspend fun syncWatchdogLocked() {
        try {
            val currentNow = now().toEpochMilli()
            val target = state.all()
                .filter {
                    it.scheduledMode == ScheduledAlarmMode.EXACT && it.automationId !in inFlight &&
                        it.wakeAtMillis > currentNow
                }
                .minOfOrNull { it.wakeAtMillis }
                ?.let { it + WATCHDOG_DELAY_MILLIS }
            if (watchdogSynced && target == watchdogAt) return
            backend.syncWatchdog(target)
            watchdogAt = target
            watchdogSynced = true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            watchdogSynced = false
        }
    }

    /** Pulizia di una regola non più armata: se fallisce, il marker [disarmed] resta acceso. */
    private suspend fun forgetDisarmed(automationId: AutomationId) {
        disarmed += automationId
        cancelAndForget(automationId)
        disarmed -= automationId
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

    private enum class ScheduleResult { SCHEDULED, UNCHANGED, EXPIRED, FAILED, IDLE }

    /** Trigger che questo coordinatore pianifica su AlarmManager: orari e, dalla 0.8, la prossima sveglia. */
    private fun Trigger.scheduledHere(): Boolean = this is Trigger.Time || this is Trigger.NextAlarm

    private companion object {
        /** Ritardo della sveglia di riserva rispetto alla prossima sveglia exact. */
        const val WATCHDOG_DELAY_MILLIS = 2L * 60 * 1_000
    }
}
