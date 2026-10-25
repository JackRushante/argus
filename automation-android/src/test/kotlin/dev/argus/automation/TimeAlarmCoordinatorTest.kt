package dev.argus.automation

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimeAlarmCoordinatorTest {
    private val now = Instant.parse("2026-07-13T20:00:30Z")

    @Test
    fun `reconcile persists inexact fallback when exact access is unavailable`() = runTest {
        val automation = automation(
            Trigger.Time(
                at = "2026-07-13T22:00",
                tz = "UTC",
                precision = TimePrecision.EXACT,
            ),
        )
        val store = FakeAutomationStore(automation)
        val state = FakeTimeAlarmStateStore()
        val backend = FakeTimeAlarmBackend(exactAllowed = false)
        val coordinator = coordinator(store, state, backend)

        val report = coordinator.reconcile(ReconcileReason.APP_START)

        assertEquals(listOf(automation.id), report.scheduled)
        assertEquals(ScheduledAlarmMode.INEXACT, state.get(automation.id)?.scheduledMode)
        assertEquals(TimePrecision.EXACT, state.get(automation.id)?.requestedPrecision)
        assertEquals(Instant.parse("2026-07-13T22:00:00Z").toEpochMilli(), state.get(automation.id)?.eventAtMillis)
    }

    @Test
    fun `permission grant upgrades a persisted fallback to exact`() = runTest {
        val automation = automation(
            Trigger.Time(
                at = "2026-07-13T22:00",
                tz = "UTC",
                precision = TimePrecision.EXACT,
            ),
        )
        val store = FakeAutomationStore(automation)
        val state = FakeTimeAlarmStateStore()
        val backend = FakeTimeAlarmBackend(exactAllowed = false)
        val coordinator = coordinator(store, state, backend)
        coordinator.reconcile(ReconcileReason.APP_START)

        backend.exactAllowed = true
        val report = coordinator.reconcile(ReconcileReason.EXACT_ALARM_PERMISSION_CHANGED)

        assertEquals(listOf(automation.id), report.scheduled)
        assertEquals(ScheduledAlarmMode.EXACT, state.get(automation.id)?.scheduledMode)
        assertEquals(emptyList(), backend.cancelled)
    }

    @Test
    fun `boot recreates a future OS registration even when Room already has it`() = runTest {
        val automation = automation(Trigger.Time(at = "2026-07-13T22:00", tz = "UTC"))
        val dueAt = Instant.parse("2026-07-13T22:00:00Z").toEpochMilli()
        val state = FakeTimeAlarmStateStore(record(automation, dueAt))
        val backend = FakeTimeAlarmBackend()

        val report = coordinator(FakeAutomationStore(automation), state, backend)
            .reconcile(ReconcileReason.BOOT)

        assertEquals(listOf(automation.id), report.scheduled)
        assertEquals(emptyList(), backend.cancelled)
        assertEquals(listOf(dueAt), backend.scheduled.map { it.eventAtMillis })
    }

    @Test
    fun `failed replacement keeps the previous registration recoverable`() = runTest {
        val old = automation(Trigger.Time(at = "2026-07-13T22:00", tz = "UTC"))
        val current = automation(Trigger.Time(at = "2026-07-13T23:00", tz = "UTC"))
        val oldAt = Instant.parse("2026-07-13T22:00:00Z").toEpochMilli()
        val previous = record(old, oldAt)
        val state = FakeTimeAlarmStateStore(previous)
        val backend = FakeTimeAlarmBackend(failSchedules = true)

        val report = coordinator(FakeAutomationStore(current), state, backend)
            .reconcile(ReconcileReason.APP_START)

        assertEquals(listOf(current.id), report.failed)
        assertEquals(previous, state.get(current.id))
        assertEquals(emptyList(), backend.cancelled)
    }

    @Test
    fun `Room failure after OS replacement restores previous registration`() = runTest {
        val old = automation(Trigger.Time(at = "2026-07-13T22:00", tz = "UTC"))
        val current = automation(Trigger.Time(at = "2026-07-13T23:00", tz = "UTC"))
        val oldAt = Instant.parse("2026-07-13T22:00:00Z").toEpochMilli()
        val previous = record(old, oldAt)
        val state = FakeTimeAlarmStateStore(previous, failUpserts = true)
        val backend = FakeTimeAlarmBackend()

        val report = coordinator(FakeAutomationStore(current), state, backend)
            .reconcile(ReconcileReason.APP_START)

        assertEquals(listOf(current.id), report.failed)
        assertEquals(previous, state.get(current.id))
        assertEquals(
            listOf(
                Instant.parse("2026-07-13T23:00:00Z").toEpochMilli(),
                oldAt,
            ),
            backend.scheduled.map { it.eventAtMillis },
        )
    }

    @Test
    fun `stale persisted registrations are cancelled`() = runTest {
        val store = FakeAutomationStore()
        val state = FakeTimeAlarmStateStore(
            ScheduledTimeAlarm(
                automationId = AutomationId("deleted"),
                approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
                eventAtMillis = now.plusSeconds(60).toEpochMilli(),
                wakeAtMillis = now.plusSeconds(60).toEpochMilli(),
                requestedPrecision = TimePrecision.FLEXIBLE,
                scheduledMode = ScheduledAlarmMode.INEXACT,
                updatedAtMillis = now.toEpochMilli(),
            ),
        )
        val backend = FakeTimeAlarmBackend()

        val report = coordinator(store, state, backend).reconcile(ReconcileReason.APP_START)

        assertEquals(listOf(AutomationId("deleted")), report.cancelled)
        assertNull(state.get(AutomationId("deleted")))
        assertEquals(listOf(AutomationId("deleted")), backend.cancelled)
    }

    @Test
    fun `alarm delivery uses a stable event id and schedules the next cron occurrence`() = runTest {
        val automation = automation(Trigger.Time(cron = "1 20 * * *", tz = "UTC"))
        val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
        val state = FakeTimeAlarmStateStore(
            ScheduledTimeAlarm(
                automationId = automation.id,
                approvalFingerprint = requireNotNull(automation.approvalFingerprint),
                eventAtMillis = dueAt,
                wakeAtMillis = dueAt,
                requestedPrecision = TimePrecision.FLEXIBLE,
                scheduledMode = ScheduledAlarmMode.INEXACT,
                updatedAtMillis = dueAt,
            ),
        )
        val dispatched = mutableListOf<TriggerEnvelope>()
        val coordinator = TimeAlarmCoordinator(
            store = FakeAutomationStore(automation),
            state = state,
            backend = FakeTimeAlarmBackend(),
            dispatcher = TimeEventDispatcher { dispatched += it },
            now = { now },
        )

        assertIs<AlarmDeliveryResult.Delivered>(
            coordinator.onAlarm(automation.id, requireNotNull(automation.approvalFingerprint), dueAt),
        )

        assertEquals(
            "time:${requireNotNull(automation.approvalFingerprint).value}:$dueAt",
            dispatched.single().id.value,
        )
        assertEquals(
            TriggerEvent.TimeFired(automation.id, requireNotNull(automation.approvalFingerprint)),
            dispatched.single().event,
        )
        assertEquals(
            Instant.parse("2026-07-13T20:01:00Z").toEpochMilli(),
            state.get(automation.id)?.eventAtMillis,
        )
    }

    @Test
    fun `one shot is disabled only after successful delivery`() = runTest {
        val automation = automation(Trigger.Time(at = "2026-07-13T20:00", tz = "UTC"))
        val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
        val store = FakeAutomationStore(automation)
        val state = FakeTimeAlarmStateStore(record(automation, dueAt))
        val oneShotConsumptions = ProcessOneShotConsumptionRegistry()
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = state,
            backend = FakeTimeAlarmBackend(),
            dispatcher = TimeEventDispatcher { },
            now = { now },
            oneShotConsumptions = oneShotConsumptions,
        )

        assertIs<AlarmDeliveryResult.Delivered>(
            coordinator.onAlarm(automation.id, requireNotNull(automation.approvalFingerprint), dueAt),
        )

        assertEquals(AutomationStatus.DISABLED, store.get(automation.id)?.status)
        assertNull(state.get(automation.id))
        assertTrue(
            oneShotConsumptions.wasAutoConsumed(
                TriggerEventId("time:${automation.approvalFingerprint!!.value}:$dueAt"),
            ),
        )
    }

    @Test
    fun `one shot delivery cannot disable a newer approved revision`() = runTest {
        val old = automation(Trigger.Time(at = "2026-07-13T20:00", tz = "UTC"))
        val unsignedNew = old.copy(
            name = "revised",
            actions = listOf(Action.ShowNotification("Argus", "new revision")),
            approvalFingerprint = null,
        )
        val revised = unsignedNew.copy(approvalFingerprint = ApprovalFingerprints.of(unsignedNew))
        val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
        val store = FakeAutomationStore(old)
        val state = FakeTimeAlarmStateStore(record(old, dueAt))
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = state,
            backend = FakeTimeAlarmBackend(),
            dispatcher = TimeEventDispatcher { store.replace(revised) },
            now = { now },
        )

        assertIs<AlarmDeliveryResult.Delivered>(
            coordinator.onAlarm(old.id, requireNotNull(old.approvalFingerprint), dueAt),
        )

        assertEquals(revised, store.get(old.id))
        assertEquals(AutomationStatus.ARMED, store.get(old.id)?.status)
    }

    @Test
    fun `revision arriving during conditional disable is rescheduled`() = runTest {
        val old = automation(Trigger.Time(at = "2026-07-13T20:00", tz = "UTC"))
        val unsignedNew = old.copy(
            name = "future revision",
            trigger = Trigger.Time(at = "2026-07-13T22:00", tz = "UTC"),
            approvalFingerprint = null,
        )
        val revised = unsignedNew.copy(approvalFingerprint = ApprovalFingerprints.of(unsignedNew))
        val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
        val store = FakeAutomationStore(old).apply {
            beforeConditionalDisable = { replace(revised) }
        }
        val state = FakeTimeAlarmStateStore(record(old, dueAt))
        val backend = FakeTimeAlarmBackend()

        val result = coordinator(store, state, backend).onAlarm(
            old.id,
            requireNotNull(old.approvalFingerprint),
            dueAt,
        )

        assertIs<AlarmDeliveryResult.Delivered>(result)
        assertEquals(revised.approvalFingerprint, state.get(revised.id)?.approvalFingerprint)
        assertEquals(
            Instant.parse("2026-07-13T22:00:00Z").toEpochMilli(),
            state.get(revised.id)?.eventAtMillis,
        )
    }

    @Test
    fun `failed delivery keeps the due record for recovery with the same event id`() = runTest {
        val automation = automation(Trigger.Time(at = "2026-07-13T20:00", tz = "UTC"))
        val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
        val store = FakeAutomationStore(automation)
        val state = FakeTimeAlarmStateStore(record(automation, dueAt))
        var fail = true
        val dispatched = mutableListOf<String>()
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = state,
            backend = FakeTimeAlarmBackend(),
            dispatcher = TimeEventDispatcher {
                dispatched += it.id.value
                if (fail) error("process died")
            },
            now = { now },
        )

        assertIs<AlarmDeliveryResult.Failed>(
            coordinator.onAlarm(automation.id, requireNotNull(automation.approvalFingerprint), dueAt),
        )
        assertEquals(dueAt, state.get(automation.id)?.eventAtMillis)
        fail = false
        val report = coordinator.reconcile(ReconcileReason.APP_START)

        assertEquals(listOf(automation.id), report.recovered)
        val eventId = "time:${requireNotNull(automation.approvalFingerprint).value}:$dueAt"
        assertEquals(listOf(eventId, eventId), dispatched)
        assertEquals(AutomationStatus.DISABLED, store.get(automation.id)?.status)
    }

    @Test
    fun `failed recurring reschedule keeps due record and reports recovery needed`() = runTest {
        val automation = automation(Trigger.Time(cron = "1 20 * * *", tz = "UTC"))
        val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
        val previous = record(automation, dueAt)
        val state = FakeTimeAlarmStateStore(previous)
        val backend = FakeTimeAlarmBackend(failSchedules = true)
        val coordinator = coordinator(FakeAutomationStore(automation), state, backend)

        assertEquals(
            AlarmDeliveryResult.Failed("reschedule_failed"),
            coordinator.onAlarm(
                automation.id,
                requireNotNull(automation.approvalFingerprint),
                dueAt,
            ),
        )
        assertEquals(previous, state.get(automation.id))
    }

    @Test
    fun `stale redelivery is ignored`() = runTest {
        val automation = automation(Trigger.Time(cron = "1 20 * * *", tz = "UTC"))
        val current = now.plusSeconds(60).toEpochMilli()
        val state = FakeTimeAlarmStateStore(record(automation, current))

        val result = coordinator(
            FakeAutomationStore(automation),
            state,
            FakeTimeAlarmBackend(),
        ).onAlarm(
            automation.id,
            requireNotNull(automation.approvalFingerprint),
            current - 60_000,
        )

        assertIs<AlarmDeliveryResult.Ignored>(result)
        assertEquals(current, state.get(automation.id)?.eventAtMillis)
    }

    @Test
    fun `old pending intent cannot fire a new approval at the same timestamp`() = runTest {
        val old = automation(Trigger.Time(at = "2026-07-13T22:00", tz = "UTC"))
        val unsignedCurrent = old.copy(
            actions = listOf(Action.ShowNotification("Argus", "edited")),
            approvalFingerprint = null,
        )
        val current = unsignedCurrent.copy(
            approvalFingerprint = ApprovalFingerprints.of(unsignedCurrent),
        )
        val dueAt = Instant.parse("2026-07-13T22:00:00Z").toEpochMilli()
        val state = FakeTimeAlarmStateStore(record(current, dueAt))
        val dispatched = mutableListOf<TriggerEnvelope>()
        val coordinator = TimeAlarmCoordinator(
            store = FakeAutomationStore(current),
            state = state,
            backend = FakeTimeAlarmBackend(),
            dispatcher = TimeEventDispatcher { dispatched += it },
            now = { now },
        )

        val result = coordinator.onAlarm(
            current.id,
            requireNotNull(old.approvalFingerprint),
            dueAt,
        )

        assertIs<AlarmDeliveryResult.Ignored>(result)
        assertEquals(emptyList(), dispatched)
        assertEquals(current.approvalFingerprint, state.get(current.id)?.approvalFingerprint)
    }

    @Test
    fun `valid OS alarm without Room record is delivered after process death`() = runTest {
        val automation = automation(Trigger.Time(at = "2026-07-13T20:00", tz = "UTC"))
        val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
        val store = FakeAutomationStore(automation)
        val dispatched = mutableListOf<TriggerEnvelope>()
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = FakeTimeAlarmStateStore(),
            backend = FakeTimeAlarmBackend(),
            dispatcher = TimeEventDispatcher { dispatched += it },
            now = { now },
        )

        val result = coordinator.onAlarm(
            automation.id,
            requireNotNull(automation.approvalFingerprint),
            dueAt,
        )

        assertIs<AlarmDeliveryResult.Delivered>(result)
        assertEquals(1, dispatched.size)
        assertEquals(AutomationStatus.DISABLED, store.get(automation.id)?.status)
    }

    @Test
    fun `missing Room record is recreated before a failed dispatch`() = runTest {
        val automation = automation(Trigger.Time(at = "2026-07-13T20:00", tz = "UTC"))
        val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
        val state = FakeTimeAlarmStateStore()
        val coordinator = TimeAlarmCoordinator(
            store = FakeAutomationStore(automation),
            state = state,
            backend = FakeTimeAlarmBackend(),
            dispatcher = TimeEventDispatcher { error("process died") },
            now = { now },
        )

        val result = coordinator.onAlarm(
            automation.id,
            requireNotNull(automation.approvalFingerprint),
            dueAt,
        )

        assertIs<AlarmDeliveryResult.Failed>(result)
        assertEquals(dueAt, state.get(automation.id)?.eventAtMillis)
    }

    @Test
    fun `expired edit cancels the old future registration`() = runTest {
        val old = automation(Trigger.Time(at = "2026-07-13T22:00", tz = "UTC"))
        val current = automation(Trigger.Time(at = "2026-07-13T19:00", tz = "UTC"))
        val oldAt = Instant.parse("2026-07-13T22:00:00Z").toEpochMilli()
        val state = FakeTimeAlarmStateStore(record(old, oldAt))
        val backend = FakeTimeAlarmBackend()
        val store = FakeAutomationStore(current)

        val report = coordinator(store, state, backend).reconcile(ReconcileReason.APP_START)

        assertEquals(listOf(current.id), report.cancelled)
        assertEquals(listOf(current.id), backend.cancelled)
        assertNull(state.get(current.id))
        assertEquals(AutomationStatus.DISABLED, store.get(current.id)?.status)
    }

    @Test
    fun `expired one-shot reconcile records SCHEDULING_FAILED expired`() = runTest {
        val automation = automation(Trigger.Time(at = "2026-07-13T19:00", tz = "UTC"))
        val store = FakeAutomationStore(automation)
        val state = FakeTimeAlarmStateStore()
        val audit = ScheduleAuditRecorder()
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = state,
            backend = FakeTimeAlarmBackend(),
            dispatcher = TimeEventDispatcher { },
            now = { now },
            audit = audit,
        )

        coordinator.reconcile(ReconcileReason.APP_START)

        assertEquals(AutomationStatus.DISABLED, store.get(automation.id)?.status)
        val event = audit.events.single { it.kind == AuditKind.SCHEDULING_FAILED }
        assertEquals("expired", event.detail)
        assertEquals(automation.id, event.automationId)
        // Lifecycle: la sparizione dalla lista armate resta tracciata come disable esplicito.
        val disabled = audit.events.single { it.kind == AuditKind.RULE_DISABLED }
        assertEquals("expired", disabled.detail)
        assertEquals(automation.id, disabled.automationId)
    }

    @Test
    fun `one-shot delivery records RULE_DISABLED one_shot_consumed`() = runTest {
        val automation = automation(Trigger.Time(at = "2026-07-13T20:00", tz = "UTC"))
        val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
        val store = FakeAutomationStore(automation)
        val state = FakeTimeAlarmStateStore(record(automation, dueAt))
        val audit = ScheduleAuditRecorder()
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = state,
            backend = FakeTimeAlarmBackend(),
            dispatcher = TimeEventDispatcher { },
            now = { now },
            audit = audit,
        )

        assertIs<AlarmDeliveryResult.Delivered>(
            coordinator.onAlarm(automation.id, requireNotNull(automation.approvalFingerprint), dueAt),
        )

        assertEquals(AutomationStatus.DISABLED, store.get(automation.id)?.status)
        val event = audit.events.single { it.kind == AuditKind.RULE_DISABLED }
        assertEquals("one_shot_consumed", event.detail)
        assertEquals(automation.id, event.automationId)
    }

    @Test
    fun `planner failure quarantines the rule and records RULE_NEEDS_REVIEW planner_failed`() = runTest {
        // Timezone invalida: TimeSpecs.nextFire lancia e il coordinator quarantena la revisione.
        val automation = automation(Trigger.Time(at = "2026-07-13T22:00", tz = "Mars/Olympus"))
        val store = FakeAutomationStore(automation)
        val audit = ScheduleAuditRecorder()
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = FakeTimeAlarmStateStore(),
            backend = FakeTimeAlarmBackend(),
            dispatcher = TimeEventDispatcher { },
            now = { now },
            audit = audit,
        )

        val report = coordinator.reconcile(ReconcileReason.APP_START)

        assertEquals(listOf(automation.id), report.failed)
        assertEquals(AutomationStatus.NEEDS_REVIEW, store.get(automation.id)?.status)
        val event = audit.events.single { it.kind == AuditKind.RULE_NEEDS_REVIEW }
        assertEquals("planner_failed", event.detail)
        assertEquals(automation.id, event.automationId)
    }

    @Test
    fun `backend schedule failure records SCHEDULING_FAILED scheduling_failed`() = runTest {
        val automation = automation(Trigger.Time(at = "2026-07-13T22:00", tz = "UTC"))
        val store = FakeAutomationStore(automation)
        val state = FakeTimeAlarmStateStore()
        val audit = ScheduleAuditRecorder()
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = state,
            backend = FakeTimeAlarmBackend(failSchedules = true),
            dispatcher = TimeEventDispatcher { },
            now = { now },
            audit = audit,
        )

        val report = coordinator.reconcile(ReconcileReason.APP_START)

        assertEquals(listOf(automation.id), report.failed)
        val event = audit.events.single { it.kind == AuditKind.SCHEDULING_FAILED }
        assertEquals("scheduling_failed", event.detail)
    }

    // --- Trigger.Time.afterMs: ritardo relativo one-shot, ancora congelata e ri-armabile ---

    @Test
    fun `relative afterMs anchors on first reconcile and stays frozen across later reconciles`() = runTest {
        val start = Instant.parse("2026-07-13T20:00:00Z")
        var clock = start
        val automation = automation(Trigger.Time(afterMs = 120_000, tz = "UTC"))
        val state = FakeTimeAlarmStateStore()
        val backend = FakeTimeAlarmBackend()
        val coordinator = TimeAlarmCoordinator(
            store = FakeAutomationStore(automation),
            state = state,
            backend = backend,
            dispatcher = TimeEventDispatcher { },
            now = { clock },
        )

        // 1° reconcile: ancora = now + afterMs, persistita e schedulata una sola volta.
        val anchor = start.plusMillis(120_000).toEpochMilli()
        val first = coordinator.reconcile(ReconcileReason.APP_START)
        assertEquals(listOf(automation.id), first.scheduled)
        assertEquals(anchor, state.get(automation.id)?.eventAtMillis)
        assertEquals(1, backend.scheduled.size)

        // now avanza: senza il freeze l'ancora slitterebbe a now+afterMs. Deve restare congelata.
        clock = start.plusSeconds(30)
        val second = coordinator.reconcile(ReconcileReason.APP_START)
        assertEquals(emptyList(), second.scheduled) // UNCHANGED, nessuna nuova schedule
        assertEquals(anchor, state.get(automation.id)?.eventAtMillis)
        assertEquals(1, backend.scheduled.size) // il backend NON è stato richiamato
    }

    @Test
    fun `relative afterMs one shot is disabled after delivery like at`() = runTest {
        val automation = automation(Trigger.Time(afterMs = 120_000, tz = "UTC"))
        val dueAt = now.plusSeconds(30).toEpochMilli()
        val store = FakeAutomationStore(automation)
        val state = FakeTimeAlarmStateStore(record(automation, dueAt))
        val coordinator = coordinator(store, state, FakeTimeAlarmBackend())

        assertIs<AlarmDeliveryResult.Delivered>(
            coordinator.onAlarm(automation.id, requireNotNull(automation.approvalFingerprint), dueAt),
        )

        assertEquals(AutomationStatus.DISABLED, store.get(automation.id)?.status)
        assertNull(state.get(automation.id))
    }

    @Test
    fun `re-armed relative rule gets a fresh anchor after the previous one fired`() = runTest {
        var clock = Instant.parse("2026-07-13T20:00:00Z")
        val armed = automation(Trigger.Time(afterMs = 120_000, tz = "UTC"))
        val store = FakeAutomationStore(armed)
        val state = FakeTimeAlarmStateStore()
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = state,
            backend = FakeTimeAlarmBackend(),
            dispatcher = TimeEventDispatcher { },
            now = { clock },
        )

        coordinator.reconcile(ReconcileReason.APP_START)
        val firstAnchor = clock.plusMillis(120_000).toEpochMilli()
        assertEquals(firstAnchor, state.get(armed.id)?.eventAtMillis)

        // Lo scatto one-shot disabilita la regola e cancella il record persistito.
        clock = clock.plusMillis(120_000)
        assertIs<AlarmDeliveryResult.Delivered>(
            coordinator.onAlarm(armed.id, requireNotNull(armed.approvalFingerprint), firstAnchor),
        )
        assertNull(state.get(armed.id))
        assertEquals(AutomationStatus.DISABLED, store.get(armed.id)?.status)

        // Ri-arm: stessa regola tornata ARMED (fingerprint invariato), clock avanzato.
        store.replace(armed.copy(status = AutomationStatus.ARMED, enabled = true))
        clock = clock.plusSeconds(300)
        coordinator.reconcile(ReconcileReason.APP_START)
        val secondAnchor = clock.plusMillis(120_000).toEpochMilli()
        assertEquals(secondAnchor, state.get(armed.id)?.eventAtMillis)
        assertNotEquals(firstAnchor, secondAnchor)
    }

    @Test
    fun `relative afterMs schedules exact by default when permitted`() = runTest {
        // Campo (2026-07-17): un "tra 3 minuti" INEXACT è scattato con +2m33s di batching OEM.
        // Un ritardo relativo esplicito è puntuale per natura: il runtime lo tratta come EXACT
        // anche con precision FLEXIBLE nel trigger (politica di scheduling, fingerprint invariato).
        val automation = automation(Trigger.Time(afterMs = 180_000, tz = "UTC"))
        val state = FakeTimeAlarmStateStore()
        val backend = FakeTimeAlarmBackend(exactAllowed = true)

        val report = coordinator(FakeAutomationStore(automation), state, backend)
            .reconcile(ReconcileReason.APP_START)

        assertEquals(listOf(automation.id), report.scheduled)
        assertEquals(TimePrecision.EXACT, state.get(automation.id)?.requestedPrecision)
        assertEquals(ScheduledAlarmMode.EXACT, state.get(automation.id)?.scheduledMode)
        assertEquals(now.plusMillis(180_000).toEpochMilli(), state.get(automation.id)?.eventAtMillis)
    }

    @Test
    fun `relative afterMs falls back to inexact without exact permission`() = runTest {
        val automation = automation(Trigger.Time(afterMs = 180_000, tz = "UTC"))
        val state = FakeTimeAlarmStateStore()
        val backend = FakeTimeAlarmBackend(exactAllowed = false)

        val report = coordinator(FakeAutomationStore(automation), state, backend)
            .reconcile(ReconcileReason.APP_START)

        assertEquals(listOf(automation.id), report.scheduled)
        assertEquals(ScheduledAlarmMode.INEXACT, state.get(automation.id)?.scheduledMode)
    }

    @Test
    fun `relative afterMs due record is recovered and delivered on reconcile`() = runTest {
        val automation = automation(Trigger.Time(afterMs = 120_000, tz = "UTC"))
        val dueAt = now.minusSeconds(60).toEpochMilli() // ancora già scaduta
        val store = FakeAutomationStore(automation)
        val state = FakeTimeAlarmStateStore(record(automation, dueAt))
        val dispatched = mutableListOf<TriggerEnvelope>()
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = state,
            backend = FakeTimeAlarmBackend(),
            dispatcher = TimeEventDispatcher { dispatched += it },
            now = { now },
        )

        val report = coordinator.reconcile(ReconcileReason.APP_START)

        assertEquals(listOf(automation.id), report.recovered)
        assertEquals(1, dispatched.size)
        assertEquals(AutomationStatus.DISABLED, store.get(automation.id)?.status)
    }

    // --- B3: il dispatch gira FUORI dal lock dello scheduler ---------------------------------

    @Test
    fun `dispatch in flight does not block reconcile or the alarm of another rule`() = runTest {
        val slow = automation(Trigger.Time(cron = "1 20 * * *", tz = "UTC"), "slow")
        val other = automation(Trigger.Time(cron = "5 20 * * *", tz = "UTC"), "other")
        val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
        val state = FakeTimeAlarmStateStore(record(slow, dueAt), record(other, dueAt))
        val gate = DispatchGate(blockedAutomation = slow.id)
        val coordinator = gatedCoordinator(FakeAutomationStore(slow, other), state, gate)

        val running = async { coordinator.onAlarm(slow.id, fingerprint(slow), dueAt) }
        gate.entered.await()

        // Un'altra regola e una reconcile completano mentre `slow` sta ancora eseguendo.
        assertIs<AlarmDeliveryResult.Delivered>(
            withTimeout(1_000) { coordinator.onAlarm(other.id, fingerprint(other), dueAt) },
        )
        val report = withTimeout(1_000) { coordinator.reconcile(ReconcileReason.APP_START) }

        // Il record resta DOVUTO (sicurezza in caso di crash) ma reconcile non lo recupera né lo
        // riconsegna: nessuna doppia consegna.
        assertEquals(dueAt, state.get(slow.id)?.eventAtMillis)
        assertEquals(emptyList(), report.recovered)
        assertEquals(emptyList(), report.cancelled)
        assertEquals(emptyList(), report.failed)
        assertEquals(1, gate.dispatchedFor(slow).size)

        gate.release.complete(Unit)
        assertIs<AlarmDeliveryResult.Delivered>(running.await())
        assertEquals(
            Instant.parse("2026-07-13T20:01:00Z").toEpochMilli(),
            state.get(slow.id)?.eventAtMillis,
        )
        assertEquals(1, gate.dispatchedFor(slow).size, "una sola consegna per la regola lenta")
    }

    @Test
    fun `a second alarm for a rule already in flight is ignored`() = runTest {
        val slow = automation(Trigger.Time(cron = "1 20 * * *", tz = "UTC"), "slow")
        val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
        val state = FakeTimeAlarmStateStore(record(slow, dueAt))
        val gate = DispatchGate(blockedAutomation = slow.id)
        val coordinator = gatedCoordinator(FakeAutomationStore(slow), state, gate)

        val running = async { coordinator.onAlarm(slow.id, fingerprint(slow), dueAt) }
        gate.entered.await()
        val duplicate = withTimeout(1_000) {
            coordinator.onAlarm(slow.id, fingerprint(slow), dueAt)
        }

        assertIs<AlarmDeliveryResult.Ignored>(duplicate)
        assertEquals(1, gate.dispatched.size)
        gate.release.complete(Unit)
        assertIs<AlarmDeliveryResult.Delivered>(running.await())
        assertEquals(1, gate.dispatched.size)
    }

    @Test
    fun `reconcile recovering a due record does not hold the lock during its dispatch`() = runTest {
        val slow = automation(Trigger.Time(cron = "1 20 * * *", tz = "UTC"), "slow")
        val other = automation(Trigger.Time(cron = "5 20 * * *", tz = "UTC"), "other")
        val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
        val otherAt = Instant.parse("2026-07-13T20:05:00Z").toEpochMilli()
        val state = FakeTimeAlarmStateStore(record(slow, dueAt), record(other, otherAt))
        val gate = DispatchGate(blockedAutomation = slow.id)
        val coordinator = gatedCoordinator(FakeAutomationStore(slow, other), state, gate)

        val reconciling = async { coordinator.reconcile(ReconcileReason.APP_START) }
        gate.entered.await()

        assertIs<AlarmDeliveryResult.Delivered>(
            withTimeout(1_000) { coordinator.onAlarm(other.id, fingerprint(other), otherAt) },
        )
        assertEquals(dueAt, state.get(slow.id)?.eventAtMillis)

        gate.release.complete(Unit)
        val report = reconciling.await()
        assertTrue(slow.id in report.recovered)
        assertEquals(
            Instant.parse("2026-07-13T20:01:00Z").toEpochMilli(),
            state.get(slow.id)?.eventAtMillis,
        )
    }

    @Test
    fun `rule disabled or deleted while dispatching is cancelled and forgotten at the end`() = runTest {
        for (removal in listOf("disable", "delete")) {
            val slow = automation(Trigger.Time(cron = "1 20 * * *", tz = "UTC"), "slow")
            val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
            val store = FakeAutomationStore(slow)
            val state = FakeTimeAlarmStateStore(record(slow, dueAt))
            val backend = FakeTimeAlarmBackend()
            val gate = DispatchGate(blockedAutomation = slow.id)
            val coordinator = gatedCoordinator(store, state, gate, backend)

            val running = async { coordinator.onAlarm(slow.id, fingerprint(slow), dueAt) }
            gate.entered.await()
            if (removal == "disable") store.disable(slow.id) else store.delete(slow.id)

            // Durante il dispatch la reconcile NON tocca il record in volo.
            val report = withTimeout(1_000) { coordinator.reconcile(ReconcileReason.CAPABILITY_CHANGED) }
            assertEquals(emptyList(), report.cancelled, removal)
            assertEquals(dueAt, state.get(slow.id)?.eventAtMillis, removal)

            gate.release.complete(Unit)
            assertIs<AlarmDeliveryResult.Delivered>(running.await(), removal)
            assertNull(state.get(slow.id), removal)
            assertTrue(slow.id in backend.cancelled, removal)
        }
    }

    @Test
    fun `cancelled dispatch releases the in-flight marker and the record stays due for recovery`() = runTest {
        val slow = automation(Trigger.Time(cron = "1 20 * * *", tz = "UTC"), "slow")
        val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
        val state = FakeTimeAlarmStateStore(record(slow, dueAt))
        val gate = DispatchGate(blockedAutomation = slow.id, blockOnlyFirstCall = true)
        val coordinator = gatedCoordinator(FakeAutomationStore(slow), state, gate)

        val running = launch { coordinator.onAlarm(slow.id, fingerprint(slow), dueAt) }
        gate.entered.await()
        running.cancelAndJoin()

        assertEquals(dueAt, state.get(slow.id)?.eventAtMillis)
        val report = withTimeout(1_000) { coordinator.reconcile(ReconcileReason.APP_START) }
        assertEquals(listOf(slow.id), report.recovered)
        assertEquals(2, gate.dispatched.size)
        assertEquals(gate.dispatched[0], gate.dispatched[1], "stesso event id alla ripetizione")
    }

    @Test
    fun `failed dispatch releases the in-flight marker`() = runTest {
        val slow = automation(Trigger.Time(cron = "1 20 * * *", tz = "UTC"), "slow")
        val dueAt = Instant.parse("2026-07-13T20:00:00Z").toEpochMilli()
        val state = FakeTimeAlarmStateStore(record(slow, dueAt))
        var fail = true
        val dispatched = mutableListOf<String>()
        val coordinator = TimeAlarmCoordinator(
            store = FakeAutomationStore(slow),
            state = state,
            backend = FakeTimeAlarmBackend(),
            dispatcher = TimeEventDispatcher {
                dispatched += it.id.value
                if (fail) error("boom")
            },
            now = { now },
        )

        assertEquals(
            AlarmDeliveryResult.Failed("dispatch_failed"),
            coordinator.onAlarm(slow.id, fingerprint(slow), dueAt),
        )
        assertEquals(dueAt, state.get(slow.id)?.eventAtMillis)
        fail = false
        val report = coordinator.reconcile(ReconcileReason.APP_START)

        assertEquals(listOf(slow.id), report.recovered)
        assertEquals(2, dispatched.size)
    }

    private fun fingerprint(automation: Automation): ApprovalFingerprint =
        requireNotNull(automation.approvalFingerprint)

    private fun gatedCoordinator(
        store: FakeAutomationStore,
        state: FakeTimeAlarmStateStore,
        gate: DispatchGate,
        backend: FakeTimeAlarmBackend = FakeTimeAlarmBackend(),
    ) = TimeAlarmCoordinator(
        store = store,
        state = state,
        backend = backend,
        dispatcher = TimeEventDispatcher { gate.dispatch(it) },
        now = { now },
    )

    private fun coordinator(
        store: FakeAutomationStore,
        state: FakeTimeAlarmStateStore,
        backend: FakeTimeAlarmBackend,
    ) = TimeAlarmCoordinator(
        store = store,
        state = state,
        backend = backend,
        dispatcher = TimeEventDispatcher { },
        now = { now },
    )

    private fun automation(trigger: Trigger.Time, id: String = "a1"): Automation {
        val unsigned = Automation(
            id = AutomationId(id),
            name = "test",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = trigger,
            actions = listOf(Action.ShowNotification("Argus", "test")),
            enabled = true,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    private fun record(automation: Automation, at: Long) = ScheduledTimeAlarm(
        automationId = automation.id,
        approvalFingerprint = requireNotNull(automation.approvalFingerprint),
        eventAtMillis = at,
        wakeAtMillis = at,
        requestedPrecision = TimePrecision.FLEXIBLE,
        scheduledMode = ScheduledAlarmMode.INEXACT,
        updatedAtMillis = at,
    )
}

private class DispatchGate(
    private val blockedAutomation: AutomationId,
    private val blockOnlyFirstCall: Boolean = false,
) {
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val dispatched = mutableListOf<String>()
    private var blockedOnce = false

    suspend fun dispatch(envelope: TriggerEnvelope) {
        dispatched += envelope.id.value
        val target = (envelope.event as TriggerEvent.TimeFired).automationId
        if (target != blockedAutomation) return
        if (blockOnlyFirstCall && blockedOnce) return
        blockedOnce = true
        entered.complete(Unit)
        release.await()
    }

    fun dispatchedFor(automation: Automation): List<String> =
        dispatched.filter { requireNotNull(automation.approvalFingerprint).value in it }
}

private class ScheduleAuditRecorder : AuditSink {
    val events = mutableListOf<AuditEvent>()
    override suspend fun record(e: AuditEvent) { events += e }
}

private class FakeTimeAlarmBackend(
    var exactAllowed: Boolean = true,
    var failSchedules: Boolean = false,
) : TimeAlarmBackend {
    val scheduled = mutableListOf<TimeAlarmRegistration>()
    val cancelled = mutableListOf<AutomationId>()

    override fun canScheduleExact(): Boolean = exactAllowed

    override fun schedule(registration: TimeAlarmRegistration): ScheduledAlarmMode {
        if (failSchedules) error("forced schedule failure")
        scheduled += registration
        return if (registration.requestedPrecision == TimePrecision.EXACT && exactAllowed) {
            ScheduledAlarmMode.EXACT
        } else {
            ScheduledAlarmMode.INEXACT
        }
    }

    override fun cancel(automationId: AutomationId) {
        cancelled += automationId
    }
}

private class FakeTimeAlarmStateStore(
    vararg initial: ScheduledTimeAlarm,
    private val failUpserts: Boolean = false,
) : TimeAlarmStateStore {
    private val values = initial.associateByTo(linkedMapOf()) { it.automationId }

    override suspend fun get(automationId: AutomationId): ScheduledTimeAlarm? = values[automationId]
    override suspend fun all(): List<ScheduledTimeAlarm> = values.values.toList()
    override suspend fun upsert(alarm: ScheduledTimeAlarm) {
        if (failUpserts) error("forced upsert failure")
        values[alarm.automationId] = alarm
    }
    override suspend fun delete(automationId: AutomationId) { values.remove(automationId) }
}

private class FakeAutomationStore(vararg initial: Automation) : AutomationStore {
    private val values = MutableStateFlow(initial.associateBy { it.id })
    var beforeConditionalDisable: (() -> Unit)? = null

    override suspend fun get(id: AutomationId): Automation? = values.value[id]
    override suspend fun all(): List<Automation> = values.value.values.toList()
    override fun observeAll(): Flow<List<Automation>> = MutableStateFlow(values.value.values.toList())
    override suspend fun armed(): List<Automation> = values.value.values.filter {
        it.status == AutomationStatus.ARMED && it.enabled
    }
    override suspend fun delete(id: AutomationId) { values.value -= id }
    override suspend fun disable(id: AutomationId) {
        values.value[id]?.let { values.value += id to it.copy(status = AutomationStatus.DISABLED, enabled = false) }
    }
    override suspend fun disableIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean {
        beforeConditionalDisable?.also { beforeConditionalDisable = null }?.invoke()
        val current = values.value[id] ?: return false
        if (current.status != AutomationStatus.ARMED || !current.enabled ||
            current.approvalFingerprint != fingerprint ||
            current.approvalFingerprint != ApprovalFingerprints.of(current)
        ) return false
        values.value += id to current.copy(status = AutomationStatus.DISABLED, enabled = false)
        return true
    }
    override suspend fun enableIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean = false
    override suspend fun markNeedsReview(id: AutomationId) {
        values.value[id]?.let {
            values.value += id to it.copy(status = AutomationStatus.NEEDS_REVIEW, enabled = false)
        }
    }
    override suspend fun markNeedsReviewIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean {
        val current = values.value[id] ?: return false
        if (current.status != AutomationStatus.ARMED || !current.enabled ||
            current.approvalFingerprint != fingerprint ||
            current.approvalFingerprint != ApprovalFingerprints.of(current)
        ) return false
        values.value += id to current.copy(status = AutomationStatus.NEEDS_REVIEW, enabled = false)
        return true
    }
    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult = FireClaimResult.Claimed
    override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
    override suspend fun lastFiredAt(id: AutomationId): Long? = null

    fun replace(automation: Automation) {
        values.value += automation.id to automation
    }
}
