package dev.argus.automation.directboot

import dev.argus.automation.ScheduledAlarmMode
import dev.argus.automation.ScheduledTimeAlarm
import dev.argus.automation.TimeAlarmBackend
import dev.argus.automation.TimeAlarmRegistration
import dev.argus.automation.TimeAlarmStateStore
import dev.argus.automation.base.BaseActionSurface
import dev.argus.device.RingerMode
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.SettingsScreen
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.VolumeStream
import dev.argus.engine.runtime.ActionJournalEntry
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.ExecutionCompletion
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.ExecutionJournal
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

internal fun qualifyingRule(
    id: String,
    cron: String = "0 7 * * *",
    actions: List<Action> = listOf(Action.SetDnd(DndMode.OFF)),
    status: AutomationStatus = AutomationStatus.ARMED,
): Automation {
    val unsigned = Automation(
        id = AutomationId(id),
        name = "Morning $id",
        createdBy = CreatedBy.USER,
        status = status,
        trigger = Trigger.Time(cron = cron, tz = "UTC"),
        actions = actions,
        enabled = status == AutomationStatus.ARMED,
    )
    return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
}

internal class RecordingAlarms : LockedAlarms {
    val scheduled = mutableListOf<Pair<Long, Boolean>>()
    var cancelled = 0

    override fun schedule(atMillis: Long, exact: Boolean) {
        scheduled += atMillis to exact
    }

    override fun cancel() {
        cancelled++
    }
}

/** Store with the claim semantics of Room: one claim per event, a cooldown of 0 always passes for an armed rule. */
internal class ClaimingStore(vararg rules: Automation) : AutomationStore {
    private val values = MutableStateFlow(rules.associateBy { it.id })
    val claims = linkedMapOf<Pair<AutomationId, TriggerEventId>, ExecutionId>()
    val lastFired = mutableMapOf<AutomationId, Long>()

    fun put(rule: Automation) {
        values.value = values.value + (rule.id to rule)
    }

    fun remove(id: AutomationId) {
        values.value = values.value - id
    }

    override suspend fun get(id: AutomationId): Automation? = values.value[id]
    override suspend fun all(): List<Automation> = values.value.values.toList()
    override fun observeAll(): Flow<List<Automation>> = MutableStateFlow(values.value.values.toList())
    override suspend fun armed(): List<Automation> =
        values.value.values.filter { it.status == AutomationStatus.ARMED && it.enabled }

    override suspend fun delete(id: AutomationId) = remove(id)
    override suspend fun disable(id: AutomationId) = Unit
    override suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint): Boolean = false
    override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint): Boolean = false
    override suspend fun markNeedsReview(id: AutomationId) = Unit
    override suspend fun markNeedsReviewIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint): Boolean = false

    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult {
        val key = request.automationId to request.eventId
        claims[key]?.let { return FireClaimResult.Duplicate(it) }
        val rule = values.value[request.automationId]
        if (rule == null || rule.status != AutomationStatus.ARMED || !rule.enabled) return FireClaimResult.NotEligible
        claims[key] = request.executionId
        lastFired[request.automationId] = request.atMillis
        return FireClaimResult.Claimed
    }

    override suspend fun recordFired(id: AutomationId, atMillis: Long) {
        lastFired[id] = atMillis
    }

    override suspend fun lastFiredAt(id: AutomationId): Long? = lastFired[id]
}

internal class RecordingJournal : ExecutionJournal {
    val actions = mutableListOf<ActionJournalEntry>()
    val finished = mutableListOf<ExecutionCompletion>()
    override suspend fun recordAction(entry: ActionJournalEntry) {
        actions += entry
    }

    override suspend fun finish(completion: ExecutionCompletion) {
        finished += completion
    }
}

internal class RecordingAudit : AuditSink {
    val events = mutableListOf<AuditEvent>()
    override suspend fun record(e: AuditEvent) {
        events += e
    }
}

internal class MemoryTimeAlarmState(vararg initial: ScheduledTimeAlarm) : TimeAlarmStateStore {
    val values = initial.associateByTo(linkedMapOf()) { it.automationId }
    override suspend fun get(automationId: AutomationId): ScheduledTimeAlarm? = values[automationId]
    override suspend fun all(): List<ScheduledTimeAlarm> = values.values.toList()
    override suspend fun upsert(alarm: ScheduledTimeAlarm) {
        values[alarm.automationId] = alarm
    }

    override suspend fun delete(automationId: AutomationId) {
        values.remove(automationId)
    }
}

internal class MemoryTimeAlarmBackend : TimeAlarmBackend {
    val scheduled = mutableListOf<TimeAlarmRegistration>()
    override fun canScheduleExact(): Boolean = true
    override fun schedule(registration: TimeAlarmRegistration): ScheduledAlarmMode {
        scheduled += registration
        return if (registration.requestedPrecision == TimePrecision.EXACT) ScheduledAlarmMode.EXACT else ScheduledAlarmMode.INEXACT
    }

    override fun cancel(automationId: AutomationId) = Unit
}

internal fun dueRecord(rule: Automation, at: Long) = ScheduledTimeAlarm(
    automationId = rule.id,
    approvalFingerprint = requireNotNull(rule.approvalFingerprint),
    eventAtMillis = at,
    wakeAtMillis = at,
    requestedPrecision = TimePrecision.FLEXIBLE,
    scheduledMode = ScheduledAlarmMode.INEXACT,
    updatedAtMillis = at,
)

/** Every call to the Android managers, in order. */
internal class RecordingSurface : BaseActionSurface {
    val calls = mutableListOf<String>()
    var dnd: DndMode? = null

    override fun isDndPolicyGranted(): Boolean = true
    override fun setInterruptionFilter(mode: DndMode) {
        calls += "dnd:$mode"
        dnd = mode
    }

    override fun currentDndMode(): DndMode? = dnd
    override fun setRingerMode(mode: RingerMode) {
        calls += "ringer:$mode"
    }

    override fun launchPackage(pkg: String): Boolean {
        calls += "launch:$pkg"
        return true
    }

    override fun openHttpUrl(url: String) {
        calls += "url:$url"
    }

    override fun setAlarm(hour: Int, minute: Int, label: String?, skipUi: Boolean): Boolean {
        calls += "alarm"
        return true
    }

    override fun setTimer(seconds: Int, label: String?, skipUi: Boolean): Boolean {
        calls += "timer"
        return true
    }

    override fun maxStreamVolume(stream: VolumeStream): Int = 10
    override fun setStreamVolume(stream: VolumeStream, level: Int) {
        calls += "volume:$stream:$level"
    }

    override fun setTorchMode(on: Boolean): Boolean {
        calls += "torch:$on"
        return true
    }

    override fun openSettingsScreen(screen: SettingsScreen, pkg: String?): Boolean {
        calls += "settings:$screen"
        return true
    }

    override fun vibrateOneShot(durationMs: Int): Boolean {
        calls += "vibrate:$durationMs"
        return true
    }
}
