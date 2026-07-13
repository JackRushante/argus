package dev.argus.automation

import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.TimeSpecs
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

enum class ReconcileReason(
    val recoverDueAlarm: Boolean,
    val recreateOsRegistration: Boolean = false,
) {
    APP_START(true),
    BOOT(true, recreateOsRegistration = true),
    PACKAGE_REPLACED(true, recreateOsRegistration = true),
    EXACT_ALARM_PERMISSION_CHANGED(true),
    CAPABILITY_CHANGED(true),
    TIME_CHANGED(false),
    TIMEZONE_CHANGED(false),
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
    fun next(automation: Automation, after: Instant): TimeAlarmRegistration? {
        if (automation.status != AutomationStatus.ARMED || !automation.enabled) return null
        val trigger = automation.trigger as? Trigger.Time ?: return null
        val approvalFingerprint = requireNotNull(automation.approvalFingerprint) {
            "Automazione ARMED priva di fingerprint"
        }
        val eventAt = TimeSpecs.nextFire(trigger, after) ?: return null
        return TimeAlarmRegistration(
            automationId = automation.id,
            approvalFingerprint = approvalFingerprint,
            eventAtMillis = eventAt.toEpochMilli(),
            requestedPrecision = trigger.precision,
        )
    }
}

/**
 * Coordinatore single-writer di AlarmManager. Non mantiene un service: viene invocato da
 * Application/receiver e riconcilia Room con lo stato OS in modo idempotente.
 */
class TimeAlarmCoordinator(
    private val store: AutomationStore,
    private val state: TimeAlarmStateStore,
    private val backend: TimeAlarmBackend,
    private val dispatcher: TimeEventDispatcher,
    private val now: () -> Instant,
) {
    private val mutex = Mutex()

    suspend fun reconcile(reason: ReconcileReason): ReconcileReport = mutex.withLock {
        val scheduled = mutableListOf<AutomationId>()
        val cancelled = mutableListOf<AutomationId>()
        val recovered = mutableListOf<AutomationId>()
        val failed = mutableListOf<AutomationId>()
        val currentNow = now()
        val desired = store.armed()
            .filter { it.trigger is Trigger.Time }
            .associateBy { it.id }

        state.all().sortedBy { it.automationId.value }.forEach { persisted ->
            if (persisted.automationId !in desired) {
                cancelAndForget(persisted.automationId)
                cancelled += persisted.automationId
            }
        }

        desired.values.sortedBy { it.id.value }.forEach { automation ->
            var persisted = state.get(automation.id)
            if (persisted != null && persisted.eventAtMillis <= currentNow.toEpochMilli()) {
                if (reason.recoverDueAlarm) {
                    when (deliverLocked(automation.id, persisted.eventAtMillis, currentNow)) {
                        AlarmDeliveryResult.Delivered -> recovered += automation.id
                        AlarmDeliveryResult.Ignored -> Unit
                        is AlarmDeliveryResult.Failed -> failed += automation.id
                    }
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
                ScheduleResult.FAILED -> failed += automation.id
                ScheduleResult.EXPIRED -> {
                    if (persisted != null) cancelled += automation.id
                    if ((automation.trigger as Trigger.Time).at != null) {
                        store.disableIfApproved(
                            automation.id,
                            requireNotNull(automation.approvalFingerprint),
                        )
                    }
                }
                ScheduleResult.UNCHANGED -> Unit
            }
        }

        ReconcileReport(scheduled, cancelled, recovered, failed)
    }

    suspend fun onAlarm(
        automationId: AutomationId,
        approvalFingerprint: ApprovalFingerprint,
        eventAtMillis: Long,
    ): AlarmDeliveryResult = mutex.withLock {
        deliverLocked(automationId, eventAtMillis, now(), approvalFingerprint)
    }

    private suspend fun deliverLocked(
        automationId: AutomationId,
        eventAtMillis: Long,
        currentNow: Instant,
        deliveredFingerprint: ApprovalFingerprint? = null,
    ): AlarmDeliveryResult {
        val persisted = state.get(automationId)
        if (persisted != null && persisted.eventAtMillis != eventAtMillis)
            return AlarmDeliveryResult.Ignored
        if (persisted != null && deliveredFingerprint != null &&
            persisted.approvalFingerprint != deliveredFingerprint
        ) return AlarmDeliveryResult.Ignored
        val eventFingerprint = persisted?.approvalFingerprint
            ?: deliveredFingerprint
            ?: return AlarmDeliveryResult.Ignored

        val automation = store.get(automationId)
        val trigger = automation?.trigger as? Trigger.Time
        if (automation == null || trigger == null || automation.status != AutomationStatus.ARMED ||
            !automation.enabled
        ) {
            cancelAndForget(automationId)
            return AlarmDeliveryResult.Ignored
        }
        if (automation.approvalFingerprint != eventFingerprint) {
            if (persisted != null) cancelAndForget(automationId)
            scheduleLocked(automation, currentNow, existing = null)
            return AlarmDeliveryResult.Ignored
        }

        if (persisted == null) {
            try {
                state.upsert(
                    ScheduledTimeAlarm(
                        automationId = automationId,
                        approvalFingerprint = eventFingerprint,
                        eventAtMillis = eventAtMillis,
                        wakeAtMillis = eventAtMillis,
                        requestedPrecision = trigger.precision,
                        scheduledMode = if (
                            trigger.precision == TimePrecision.EXACT && backend.canScheduleExact()
                        ) ScheduledAlarmMode.EXACT else ScheduledAlarmMode.INEXACT,
                        updatedAtMillis = currentNow.toEpochMilli(),
                    ),
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Il PendingIntent autenticato resta comunque eseguibile; il journal deduplica.
            }
        }

        val envelope = TriggerEnvelope(
            id = TriggerEventId("time:${eventFingerprint.value}:$eventAtMillis"),
            event = TriggerEvent.TimeFired(automationId, eventFingerprint),
        )
        try {
            dispatcher.dispatch(envelope)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Il record resta intenzionalmente due: APP_START/BOOT riprova lo stesso event ID.
            return AlarmDeliveryResult.Failed("dispatch_failed")
        }

        state.delete(automationId)
        if (trigger.at != null) {
            store.disableIfApproved(automationId, eventFingerprint)
            return AlarmDeliveryResult.Delivered
        }

        val after = Instant.ofEpochMilli(maxOf(currentNow.toEpochMilli(), eventAtMillis))
        scheduleLocked(automation, after, existing = null)
        return AlarmDeliveryResult.Delivered
    }

    private suspend fun scheduleLocked(
        automation: Automation,
        after: Instant,
        existing: ScheduledTimeAlarm?,
        force: Boolean = false,
    ): ScheduleResult {
        val registration = try {
            TimeAlarmPlanner.next(automation, after)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            existing?.let { cancelAndForget(automation.id) }
            store.markNeedsReview(automation.id)
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

        if (existing != null) cancelAndForget(automation.id)
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
            throw error
        } catch (_: Exception) {
            state.delete(automation.id)
            ScheduleResult.FAILED
        }
    }

    private suspend fun cancelAndForget(automationId: AutomationId) {
        try {
            backend.cancel(automationId)
        } finally {
            state.delete(automationId)
        }
    }

    private enum class ScheduleResult { SCHEDULED, UNCHANGED, EXPIRED, FAILED }
}
