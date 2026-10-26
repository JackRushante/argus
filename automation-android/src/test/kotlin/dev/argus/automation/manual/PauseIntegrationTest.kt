package dev.argus.automation.manual

import dev.argus.automation.AlarmDeliveryResult
import dev.argus.automation.ReconcileReason
import dev.argus.automation.ScheduledAlarmMode
import dev.argus.automation.ScheduledTimeAlarm
import dev.argus.automation.TimeAlarmBackend
import dev.argus.automation.TimeAlarmCoordinator
import dev.argus.automation.TimeAlarmRegistration
import dev.argus.automation.TimeAlarmStateStore
import dev.argus.automation.TimeEventDispatcher
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.TimePrecision
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionExecutor
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.FirePolicy
import dev.argus.engine.runtime.FirePolicyDecision
import dev.argus.engine.runtime.PauseCodes
import dev.argus.engine.runtime.TriggerMatcher
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * «Pausa automazioni» (F21) con il motore vero e le sorgenti vere dei trigger: orario (via
 * coordinatore delle sveglie), connettività e manuale. In pausa non scatta niente, le sveglie restano
 * registrate e alla ripresa NON si recupera lo scatto perso (a differenza del riavvio).
 */
class PauseIntegrationTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneOffset.UTC)

    private class Harness(
        store: MapStore,
        val pause: FakePauseStore,
        clock: Clock,
    ) {
        val executed = mutableListOf<String>()
        val audit = RecordingAudit()
        val engine = Engine(
            store = store,
            executor = ActionExecutor { action, _ ->
                executed += action::class.simpleName.orEmpty()
                ActionResult.Success
            },
            evaluator = ConditionEvaluator(clock),
            matcher = TriggerMatcher(),
            firePolicy = FirePolicy { _, _ -> FirePolicyDecision.Allow },
            audit = audit,
            firingPause = pause,
            now = { clock.millis() },
        )
    }

    private class MemoryAlarmState : TimeAlarmStateStore {
        private val values = linkedMapOf<AutomationId, ScheduledTimeAlarm>()
        override suspend fun get(automationId: AutomationId) = values[automationId]
        override suspend fun all() = values.values.toList()
        override suspend fun upsert(alarm: ScheduledTimeAlarm) {
            values[alarm.automationId] = alarm
        }

        override suspend fun delete(automationId: AutomationId) {
            values.remove(automationId)
        }
    }

    private class RecordingBackend : TimeAlarmBackend {
        val scheduled = mutableListOf<TimeAlarmRegistration>()
        val cancelled = mutableListOf<AutomationId>()
        override fun canScheduleExact() = false
        override fun schedule(registration: TimeAlarmRegistration): ScheduledAlarmMode {
            scheduled += registration
            return ScheduledAlarmMode.INEXACT
        }

        override fun cancel(automationId: AutomationId) {
            cancelled += automationId
        }
    }

    @Test
    fun `an alarm that fires during the pause is skipped, logged as paused and not recovered on resume`() = runTest {
        val rule = manualRule(
            id = "daily",
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "UTC", precision = TimePrecision.FLEXIBLE),
        )
        val store = MapStore(rule)
        val harness = Harness(store, FakePauseStore(true), clock)
        var now = Instant.parse("2026-10-26T07:00:00Z")
        val state = MemoryAlarmState()
        val backend = RecordingBackend()
        val coordinator = TimeAlarmCoordinator(
            store = store,
            state = state,
            backend = backend,
            dispatcher = TimeEventDispatcher { envelope ->
                harness.engine.onTrigger(envelope) { DeviceState() }
            },
            now = { now },
        )

        // Le sveglie restano registrate anche in pausa.
        coordinator.reconcile(ReconcileReason.APP_START)
        val firstAlarm = requireNotNull(state.get(rule.id))
        assertEquals(1, backend.scheduled.size)

        // L'orario arriva mentre le automazioni sono in pausa.
        now = Instant.parse("2026-10-26T08:00:05Z")
        val delivered = coordinator.onAlarm(rule.id, requireNotNull(rule.approvalFingerprint), firstAlarm.eventAtMillis)
        assertIs<AlarmDeliveryResult.Delivered>(delivered)
        assertTrue(harness.executed.isEmpty(), "in pausa non scatta nulla")
        val skipped = harness.audit.events.single()
        assertEquals(AuditKind.BLOCKED_POLICY, skipped.kind)
        assertEquals(PauseCodes.PAUSED, skipped.detail)

        // La sveglia successiva è già registrata: la pausa non ha cancellato nulla.
        val nextAlarm = requireNotNull(state.get(rule.id))
        assertTrue(nextAlarm.eventAtMillis > firstAlarm.eventAtMillis)
        assertEquals(2, backend.scheduled.size)

        // Ripresa: nemmeno un riavvio dell'app (che invece recupera gli scatti dovuti) recupera quello perso.
        harness.pause.setPaused(false)
        now = Instant.parse("2026-10-26T08:30:00Z")
        val report = coordinator.reconcile(ReconcileReason.APP_START)
        assertEquals(emptyList(), report.recovered)
        assertTrue(harness.executed.isEmpty(), "lo scatto saltato non torna alla ripresa")
        assertEquals(1, harness.audit.events.size)

        // Il giorno dopo la regola scatta normalmente.
        now = Instant.parse("2026-10-27T08:00:05Z")
        coordinator.onAlarm(rule.id, requireNotNull(rule.approvalFingerprint), nextAlarm.eventAtMillis)
        assertEquals(1, harness.executed.size)
    }

    @Test
    fun `a manual start during the pause is skipped and the same rule runs after the resume`() = runTest {
        val rule = manualRule("m1")
        val rig = Rig(listOf(rule), paused = true)
        val harness = Harness(rig.store, rig.pause, clock)
        val runner = ManualRuleRunner(
            store = rig.store,
            tokens = rig.tokens,
            pause = rig.pause,
            dispatcher = { envelope -> harness.engine.onTrigger(envelope) { DeviceState() } },
            launcher = ImmediateLauncher,
            nowMillis = { 1_000L },
            nonce = { "n" },
        )

        val skipped = assertIs<ManualRunResult.Accepted>(runner.runFromApp("m1"))
        assertTrue(skipped.paused)
        assertTrue(harness.executed.isEmpty())
        assertEquals(
            listOf(PauseCodes.PAUSED),
            harness.audit.events.filter { it.kind == AuditKind.BLOCKED_POLICY }.map { it.detail },
        )

        rig.pause.setPaused(false)
        // Stesso istante e stesso nonce: se il tentativo saltato avesse preso un claim, qui sarebbe un doppione.
        val started = assertIs<ManualRunResult.Accepted>(runner.runFromApp("m1"))
        assertEquals(false, started.paused)
        assertEquals(1, harness.executed.size)
        assertEquals(1, harness.audit.events.count { it.kind == AuditKind.FIRED })
    }
}
