package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.ConnState
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Avvio manuale (F8) e «Pausa automazioni» (F21) sul motore: il trigger `manual` parte solo da un
 * ManualFired della sua regola armata e approvata; in pausa nessun trigger scatta, lo scatto saltato
 * va nel Log con il codice `paused` e alla ripresa NON si recupera.
 */
class ManualAndPauseEngineTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-26T10:00:00Z"), ZoneOffset.UTC)
    private val allowAll = FirePolicy { _, _ -> FirePolicyDecision.Allow }

    private fun armed(
        id: String,
        trigger: Trigger,
        status: AutomationStatus = AutomationStatus.ARMED,
        cooldownMs: Long = 0,
    ): Automation {
        val unsigned = Automation(
            AutomationId(id), id, CreatedBy.USER, status, trigger,
            listOf(Action.SetFlashlight(true)),
            cooldownMs = cooldownMs,
            enabled = status == AutomationStatus.ARMED,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    private inner class Rig(
        rules: List<Automation>,
        paused: () -> Boolean = { false },
        now: () -> Long = { 1_000 },
    ) {
        val executor = FakeActionExecutor()
        val audit = FakeAuditSink()
        val journal = FakeExecutionJournal()
        val engine = Engine(
            store = FakeAutomationStore(rules),
            executor = executor,
            evaluator = ConditionEvaluator(clock),
            matcher = TriggerMatcher(),
            firePolicy = allowAll,
            audit = audit,
            journal = journal,
            firingPause = AutomationPause(paused),
            now = now,
        )
    }

    private fun manual(rule: Automation) =
        TriggerEvent.ManualFired(rule.id, requireNotNull(rule.approvalFingerprint))

    private fun timeFired(rule: Automation) =
        TriggerEvent.TimeFired(rule.id, requireNotNull(rule.approvalFingerprint))

    private fun envelope(id: String, event: TriggerEvent) = TriggerEnvelope(TriggerEventId(id), event)

    // --- F8: avvio manuale ---

    @Test
    fun `a manual event fires its armed manual rule`() = runTest {
        val rule = armed("m1", Trigger.Manual)
        val rig = Rig(listOf(rule))

        val outcome = rig.engine.onTrigger(envelope("manual:1", manual(rule))) { DeviceState() }

        assertEquals(1, outcome.size)
        assertEquals(listOf<Action>(Action.SetFlashlight(true)), rig.executor.executed)
        assertEquals(1, rig.audit.events.count { it.kind == AuditKind.FIRED })
        assertEquals(ExecutionStatus.SUCCEEDED, rig.journal.completions.single().status)
    }

    @Test
    fun `a manual event never fires a rule with another trigger`() = runTest {
        val timeRule = armed("t1", Trigger.Time(cron = "0 8 * * *", tz = "UTC"))
        val wifiRule = armed("w1", Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED))
        val immediate = armed("i1", Trigger.Immediate)
        val rig = Rig(listOf(timeRule, wifiRule, immediate))

        listOf(timeRule, wifiRule, immediate).forEach { rule ->
            val out = rig.engine.onTrigger(envelope("manual:${rule.id.value}", manual(rule))) { DeviceState() }
            assertTrue(out.isEmpty(), rule.id.value)
        }
        assertTrue(rig.executor.executed.isEmpty())
    }

    @Test
    fun `other events never fire a manual rule`() = runTest {
        val rule = armed("m1", Trigger.Manual)
        val rig = Rig(listOf(rule))

        val time = rig.engine.onTrigger(envelope("alarm:1", timeFired(rule))) { DeviceState() }
        val wifi = rig.engine.onTrigger(
            envelope("wifi:1", TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.CONNECTED, null)),
        ) { DeviceState() }

        assertTrue(time.isEmpty() && wifi.isEmpty())
        assertTrue(rig.executor.executed.isEmpty())
    }

    @Test
    fun `a disarmed manual rule is not fired`() = runTest {
        val disabled = armed("m-off", Trigger.Manual, status = AutomationStatus.DISABLED)
        val rig = Rig(listOf(disabled))

        val out = rig.engine.onTrigger(envelope("manual:off", manual(disabled))) { DeviceState() }

        assertTrue(out.isEmpty())
        assertTrue(rig.executor.executed.isEmpty())
    }

    @Test
    fun `a manual event with a stale approval fingerprint is refused and audited`() = runTest {
        val rule = armed("m1", Trigger.Manual)
        val rig = Rig(listOf(rule))
        val stale = TriggerEvent.ManualFired(rule.id, ApprovalFingerprint("0".repeat(64)))

        val out = rig.engine.onTrigger(envelope("manual:stale", stale)) { DeviceState() }

        assertTrue(out.isEmpty())
        assertTrue(rig.executor.executed.isEmpty())
        val event = rig.audit.events.single()
        assertEquals(AuditKind.BLOCKED_POLICY, event.kind)
        assertEquals("stale_trigger_registration", event.detail)
    }

    @Test
    fun `two manual taps are two distinct events, the second one subject to the cooldown`() = runTest {
        val rule = armed("m1", Trigger.Manual, cooldownMs = 60_000)
        var now = 1_000L
        val rig = Rig(listOf(rule), now = { now })

        val first = rig.engine.onTrigger(envelope("manual:a", manual(rule))) { DeviceState() }
        now = 2_000L
        val second = rig.engine.onTrigger(envelope("manual:b", manual(rule))) { DeviceState() }
        now = 70_000L
        val third = rig.engine.onTrigger(envelope("manual:c", manual(rule))) { DeviceState() }

        assertEquals(1, first.size)
        assertTrue(second.isEmpty())
        assertEquals(1, rig.audit.events.count { it.kind == AuditKind.SUPPRESSED_COOLDOWN })
        assertEquals(1, third.size)
    }

    // --- F21: pausa ---

    @Test
    fun `while paused no trigger fires and every skipped rule leaves a paused entry in the log`() = runTest {
        val manualRule = armed("m1", Trigger.Manual)
        val timeRule = armed("t1", Trigger.Time(cron = "0 8 * * *", tz = "UTC"))
        val wifiRule = armed("w1", Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED))
        val rig = Rig(listOf(manualRule, timeRule, wifiRule), paused = { true })

        val a = rig.engine.onTrigger(envelope("manual:1", manual(manualRule))) { DeviceState() }
        val b = rig.engine.onTrigger(envelope("alarm:1", timeFired(timeRule))) { DeviceState() }
        val c = rig.engine.onTrigger(
            envelope("wifi:1", TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.CONNECTED, null)),
        ) { DeviceState() }

        assertTrue(a.isEmpty() && b.isEmpty() && c.isEmpty())
        assertTrue(rig.executor.executed.isEmpty())
        assertTrue(rig.journal.completions.isEmpty())
        assertEquals(listOf("m1", "t1", "w1"), rig.audit.events.map { it.automationId.value })
        assertTrue(rig.audit.events.all { it.kind == AuditKind.BLOCKED_POLICY && it.detail == PauseCodes.PAUSED })
        assertEquals("paused", PauseCodes.PAUSED)
    }

    @Test
    fun `a paused event that matches no rule leaves nothing in the log`() = runTest {
        val rule = armed("w1", Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED))
        val rig = Rig(listOf(rule), paused = { true })

        rig.engine.onTrigger(
            envelope("bt:1", TriggerEvent.ConnectivityChanged(ConnMedium.BT, ConnState.CONNECTED, null)),
        ) { DeviceState() }

        assertTrue(rig.audit.events.isEmpty())
    }

    @Test
    fun `resuming does not recover what was skipped and the next event fires normally`() = runTest {
        val rule = armed("w1", Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED), cooldownMs = 60_000)
        var paused = true
        var now = 1_000L
        val rig = Rig(listOf(rule), paused = { paused }, now = { now })
        val wifi = TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.CONNECTED, null)

        rig.engine.onTrigger(envelope("wifi:during-pause", wifi)) { DeviceState() }
        assertTrue(rig.executor.executed.isEmpty())

        // Ripresa: lo scatto saltato non torna da solo.
        paused = false
        now = 5_000L
        assertTrue(rig.executor.executed.isEmpty())

        // Il cooldown non è stato consumato dallo scatto saltato: il prossimo evento scatta subito.
        val after = rig.engine.onTrigger(envelope("wifi:after-pause", wifi)) { DeviceState() }
        assertEquals(1, after.size)
        assertEquals(1, rig.executor.executed.size)
        assertEquals(0, rig.audit.events.count { it.kind == AuditKind.SUPPRESSED_COOLDOWN })
    }

    @Test
    fun `the same event delivered again after the resume is not a duplicate of the skipped one`() = runTest {
        val rule = armed("m1", Trigger.Manual)
        var paused = true
        val rig = Rig(listOf(rule), paused = { paused })

        rig.engine.onTrigger(envelope("manual:same", manual(rule))) { DeviceState() }
        paused = false
        val out = rig.engine.onTrigger(envelope("manual:same", manual(rule))) { DeviceState() }

        // Nessun claim è stato preso durante la pausa: la deduplica non lo scambia per un doppione.
        assertEquals(1, out.size)
        assertFalse(rig.audit.events.any { it.kind == AuditKind.SUPPRESSED_DUPLICATE })
    }

    @Test
    fun `the pause is read at every event, not once at construction`() = runTest {
        val rule = armed("m1", Trigger.Manual)
        var paused = false
        val rig = Rig(listOf(rule), paused = { paused })

        assertEquals(1, rig.engine.onTrigger(envelope("manual:1", manual(rule))) { DeviceState() }.size)
        paused = true
        assertTrue(rig.engine.onTrigger(envelope("manual:2", manual(rule))) { DeviceState() }.isEmpty())
        paused = false
        assertEquals(1, rig.engine.onTrigger(envelope("manual:3", manual(rule))) { DeviceState() }.size)
        assertEquals(2, rig.executor.executed.size)
    }

    @Test
    fun `an engine built without a pause never pauses`() = runTest {
        val rule = armed("m1", Trigger.Manual)
        val executor = FakeActionExecutor()
        val engine = Engine(
            store = FakeAutomationStore(listOf(rule)),
            executor = executor,
            evaluator = ConditionEvaluator(clock),
            matcher = TriggerMatcher(),
            firePolicy = allowAll,
            now = { 1_000 },
        )

        assertEquals(1, engine.onTrigger(envelope("manual:1", manual(rule))) { DeviceState() }.size)
        assertFalse(AutomationPause.NEVER.isPaused())
    }
}
