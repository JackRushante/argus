package dev.argus.automation.calendar

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CalendarPhase
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.CalendarInstance
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CalendarTriggerCoordinatorTest {
    private val minute = 60_000L
    private var now = 1_800_000_000_000L
    private val day = 24 * 60 * minute

    private val meeting = CalendarInstance(
        eventId = 7,
        beginMillis = now + 30 * minute,
        endMillis = now + 90 * minute,
        title = "Riunione",
        calendarName = "Lavoro",
        allDay = false,
        declined = false,
        free = false,
    )

    private fun rule(id: String, trigger: Trigger.CalendarEvent, status: AutomationStatus = AutomationStatus.ARMED): Automation {
        val unsigned = Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = status,
            trigger = trigger,
            actions = listOf(Action.Vibrate(100)),
            enabled = true,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    private class Harness(
        rules: List<Automation>,
        var instances: List<CalendarInstance>?,
        clock: () -> Long,
    ) {
        val store = RulesStore(rules)
        val fired = MemoryFiredStore()
        val alarms = RecordingAlarms()
        val delivered = mutableListOf<TriggerEnvelope>()
        val coordinator = CalendarTriggerCoordinator(
            store = store,
            source = { _, _ -> instances },
            firedStore = fired,
            alarms = alarms,
            dispatch = { delivered += it },
            nowMillis = clock,
        )
    }

    @Test
    fun `reconcile schedules the next instance exactly, shifted by the offset`() = runTest {
        val start = rule("start", Trigger.CalendarEvent(CalendarPhase.START, offsetMinutes = -10))
        val end = rule("end", Trigger.CalendarEvent(CalendarPhase.END))
        val harness = Harness(listOf(start, end), listOf(meeting)) { now }
        val report = harness.coordinator.reconcile()
        assertEquals(listOf(start.id, end.id), report.requiredBy)
        assertEquals(emptySet(), report.failed)
        assertEquals(Scheduled(now + 20 * minute, exact = true), harness.alarms.scheduled[start.id])
        assertEquals(Scheduled(now + 90 * minute, exact = true), harness.alarms.scheduled[end.id])
    }

    @Test
    fun `a span on the calendar plans its start and then its end, each phase once`() = runTest {
        // 0.8: «durante le riunioni Non disturbare, poi com'era»: inizio START, fine END dello stesso evento.
        val unsigned = rule("span", Trigger.CalendarEvent(CalendarPhase.START)).copy(
            exit = dev.argus.engine.model.ExitSpec(Trigger.CalendarEvent(CalendarPhase.END)),
            approvalFingerprint = null,
        )
        val span = unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
        val harness = Harness(listOf(span), listOf(meeting)) { now }

        assertEquals(listOf(span.id), harness.coordinator.reconcile().requiredBy)
        assertEquals(Scheduled(meeting.beginMillis, exact = true), harness.alarms.scheduled[span.id])

        now = meeting.beginMillis + 1_000
        harness.coordinator.onAlarm(span.id, span.approvalFingerprint!!)
        assertEquals(CalendarPhase.START, assertIs<TriggerEvent.CalendarEventFired>(harness.delivered.single().event).phase)
        // Dopo l'inizio la sveglia della regola va alla fine dello stesso evento.
        assertEquals(Scheduled(meeting.endMillis, exact = true), harness.alarms.scheduled[span.id])

        now = meeting.endMillis + 1_000
        harness.coordinator.onAlarm(span.id, span.approvalFingerprint!!)
        assertEquals(
            listOf(CalendarPhase.START, CalendarPhase.END),
            harness.delivered.map { assertIs<TriggerEvent.CalendarEventFired>(it.event).phase },
        )
        harness.coordinator.onAlarm(span.id, span.approvalFingerprint!!)
        assertEquals(2, harness.delivered.size)
    }

    @Test
    fun `without an instance within a day it schedules an inexact recheck`() = runTest {
        val start = rule("start", Trigger.CalendarEvent(CalendarPhase.START))
        val harness = Harness(listOf(start), listOf(meeting.copy(beginMillis = now + 3 * day, endMillis = now + 3 * day + minute))) { now }
        harness.coordinator.reconcile()
        assertEquals(
            Scheduled(now + CalendarTriggerCoordinator.REPLAN_INTERVAL_MILLIS, exact = false),
            harness.alarms.scheduled[start.id],
        )
    }

    @Test
    fun `the alarm fires the due instance once with its title, then plans the next`() = runTest {
        val start = rule("start", Trigger.CalendarEvent(CalendarPhase.START))
        val tomorrow = meeting.copy(beginMillis = meeting.beginMillis + day, endMillis = meeting.endMillis + day)
        val harness = Harness(listOf(start), listOf(meeting, tomorrow)) { now }
        now = meeting.beginMillis + 5_000
        harness.coordinator.onAlarm(start.id, start.approvalFingerprint!!)
        val event = assertIs<TriggerEvent.CalendarEventFired>(harness.delivered.single().event)
        assertEquals(CalendarPhase.START, event.phase)
        assertEquals("Riunione", event.title)
        assertEquals(start.approvalFingerprint, event.approvalFingerprint)
        assertEquals(Scheduled(tomorrow.beginMillis, exact = true), harness.alarms.scheduled[start.id])

        // La stessa sveglia ripetuta (o l'orologio tornato indietro) non fa riscattare l'istanza.
        harness.coordinator.onAlarm(start.id, start.approvalFingerprint!!)
        now = meeting.beginMillis
        harness.coordinator.onAlarm(start.id, start.approvalFingerprint!!)
        assertEquals(1, harness.delivered.size)
    }

    @Test
    fun `an alarm that is too late, stale or for a disarmed rule fires nothing`() = runTest {
        val start = rule("start", Trigger.CalendarEvent(CalendarPhase.START))
        val harness = Harness(listOf(start), listOf(meeting)) { now }
        now = meeting.beginMillis + 31 * minute
        harness.coordinator.onAlarm(start.id, start.approvalFingerprint!!)
        now = meeting.beginMillis
        harness.coordinator.onAlarm(start.id, ApprovalFingerprint("f".repeat(64)))
        val disarmed = Harness(listOf(start.copy(status = AutomationStatus.PENDING_APPROVAL)), listOf(meeting)) { now }
        disarmed.coordinator.onAlarm(start.id, start.approvalFingerprint!!)
        assertEquals(emptyList(), harness.delivered)
        assertEquals(emptyList(), disarmed.delivered)
    }

    @Test
    fun `without the calendar permission rules are reported failed and alarms removed`() = runTest {
        val start = rule("start", Trigger.CalendarEvent(CalendarPhase.START))
        val harness = Harness(listOf(start), listOf(meeting)) { now }
        harness.coordinator.reconcile()
        harness.instances = null
        val report = harness.coordinator.reconcile()
        assertEquals(setOf(start.id), report.failed)
        assertTrue(start.id !in harness.alarms.scheduled)
    }

    @Test
    fun `a rule that is no longer armed loses its alarm and its history`() = runTest {
        val start = rule("start", Trigger.CalendarEvent(CalendarPhase.START))
        val harness = Harness(listOf(start), listOf(meeting)) { now }
        harness.coordinator.reconcile()
        harness.fired.markFired(start.id, "1:2:START", now, now)
        harness.store.rules = emptyList()
        harness.coordinator.reconcile()
        assertTrue(start.id !in harness.alarms.scheduled)
        assertEquals(emptySet(), harness.fired.fired(start.id))
        assertEquals(emptySet(), harness.fired.scheduled())
    }

    private data class Scheduled(val atMillis: Long, val exact: Boolean)

    private class RecordingAlarms : CalendarAlarmScheduler {
        val scheduled = linkedMapOf<AutomationId, Scheduled>()

        override fun schedule(automationId: AutomationId, fingerprint: ApprovalFingerprint, atMillis: Long, exact: Boolean) {
            scheduled[automationId] = Scheduled(atMillis, exact)
        }

        override fun cancel(automationId: AutomationId) {
            scheduled.remove(automationId)
        }
    }

    private class MemoryFiredStore : CalendarFiredStore {
        private val fired = linkedMapOf<AutomationId, MutableSet<String>>()
        private var scheduled = emptySet<AutomationId>()

        override fun fired(automationId: AutomationId): Set<String> = fired[automationId].orEmpty().toSet()

        override fun markFired(automationId: AutomationId, key: String, fireAtMillis: Long, nowMillis: Long): Boolean =
            fired.getOrPut(automationId) { linkedSetOf() }.add(key)

        override fun scheduled(): Set<AutomationId> = scheduled

        override fun setScheduled(ids: Set<AutomationId>) {
            scheduled = ids
        }

        override fun forget(automationId: AutomationId) {
            fired.remove(automationId)
        }
    }

    private class RulesStore(var rules: List<Automation>) : AutomationStore {
        override suspend fun get(id: AutomationId): Automation? = rules.singleOrNull { it.id == id }
        override suspend fun all(): List<Automation> = rules
        override fun observeAll(): Flow<List<Automation>> = flowOf(rules)
        override suspend fun armed(): List<Automation> = rules.filter { it.status == AutomationStatus.ARMED && it.enabled }
        override suspend fun delete(id: AutomationId) = Unit
        override suspend fun disable(id: AutomationId) = Unit
        override suspend fun disableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
        override suspend fun enableIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
        override suspend fun markNeedsReview(id: AutomationId) = Unit
        override suspend fun markNeedsReviewIfApproved(id: AutomationId, fingerprint: ApprovalFingerprint) = false
        override suspend fun claimFire(request: FireClaimRequest): FireClaimResult = FireClaimResult.NotEligible
        override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
        override suspend fun lastFiredAt(id: AutomationId): Long? = null
    }
}
