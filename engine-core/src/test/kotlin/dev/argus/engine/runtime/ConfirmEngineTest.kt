package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ConfirmSpec
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 0.8 `confirm`: lo scatto valido chiede invece di eseguire; Esegui ripassa da tutti i controlli di uno
 * scatto normale con l'evento originale; Annulla e scadenza sono codici chiusi, mai errori.
 */
class ConfirmEngineTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC)
    private val wifi = Trigger.Connectivity(ConnMedium.WIFI, ConnState.CONNECTED)
    private val wifiEvent = TriggerEvent.ConnectivityChanged(ConnMedium.WIFI, ConnState.CONNECTED, null)

    private fun rule(
        cooldownMs: Long = 0,
        confirm: ConfirmSpec? = ConfirmSpec(60_000),
        actions: List<Action> = listOf(Action.SetFlashlight(true)),
    ): Automation {
        val unsigned = Automation(
            AutomationId("c1"), "Torcia", CreatedBy.USER, AutomationStatus.ARMED, wifi, actions,
            cooldownMs = cooldownMs,
            confirm = confirm,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    private class RecordingGate(var answer: ConfirmationRequestResult = ConfirmationRequestResult.REQUESTED) : ConfirmationGate {
        val requests = mutableListOf<ConfirmationRequest>()
        override suspend fun request(request: ConfirmationRequest): ConfirmationRequestResult {
            requests += request
            return answer
        }
    }

    private inner class Rig(
        rules: List<Automation>,
        val gate: ConfirmationGate = RecordingGate(),
        policy: FirePolicy = FirePolicy { _, _ -> FirePolicyDecision.Allow },
        var clockMillis: Long = 1_000,
    ) {
        val store = FakeAutomationStore(rules)
        val executor = FakeActionExecutor()
        val audit = FakeAuditSink()
        val journal = FakeExecutionJournal()
        val engine = Engine(
            store = store,
            executor = executor,
            evaluator = ConditionEvaluator(clock),
            matcher = TriggerMatcher(),
            firePolicy = policy,
            audit = audit,
            journal = journal,
            now = { clockMillis },
            confirmations = gate,
        )
    }

    private fun envelope(id: String) = TriggerEnvelope(TriggerEventId(id), wifiEvent)

    private fun confirmed(rule: Automation, envelope: TriggerEnvelope, id: String = "a".repeat(32)) =
        ConfirmedFire(id, rule.id, requireNotNull(rule.approvalFingerprint), envelope)

    @Test
    fun `a rule with confirm asks instead of running`() = runTest {
        val rule = rule()
        val gate = RecordingGate()
        val rig = Rig(listOf(rule), gate)

        val outcomes = rig.engine.onTrigger(envelope("wifi:1")) { DeviceState() }

        assertTrue(outcomes.isEmpty())
        assertTrue(rig.executor.executed.isEmpty())
        val request = gate.requests.single()
        assertEquals(rule.id, request.automationId)
        assertEquals("Torcia", request.ruleName)
        assertEquals(60_000, request.timeoutMs)
        assertSame(wifiEvent, request.envelope.event)
        assertEquals(rule.approvalFingerprint, request.approvalFingerprint)
        assertEquals(ExecutionStatus.SUPPRESSED_NOT_ELIGIBLE, rig.journal.completions.single().status)
        val audit = rig.audit.events.single()
        assertEquals(AuditKind.SUPPRESSED_NOT_ELIGIBLE, audit.kind)
        assertEquals(ConfirmationCodes.REQUESTED, audit.detail)
        assertEquals(request.executionId, audit.executionId)
    }

    @Test
    fun `a rule without confirm never asks`() = runTest {
        val gate = RecordingGate()
        val rig = Rig(listOf(rule(confirm = null)), gate)

        rig.engine.onTrigger(envelope("wifi:1")) { DeviceState() }

        assertTrue(gate.requests.isEmpty())
        assertEquals(listOf<Action>(Action.SetFlashlight(true)), rig.executor.executed)
    }

    @Test
    fun `without a confirmation runtime the rule fails closed`() = runTest {
        val rig = Rig(listOf(rule()), ConfirmationGate.UNAVAILABLE)

        rig.engine.onTrigger(envelope("wifi:1")) { DeviceState() }

        assertTrue(rig.executor.executed.isEmpty())
        assertEquals(ExecutionStatus.FAILED, rig.journal.completions.single().status)
        assertEquals(AuditKind.BLOCKED_POLICY, rig.audit.events.single().kind)
        assertEquals(ConfirmationCodes.UNAVAILABLE, rig.audit.events.single().detail)

        val throwing = Rig(listOf(rule()), ConfirmationGate { error("boom") })
        throwing.engine.onTrigger(envelope("wifi:1")) { DeviceState() }
        assertTrue(throwing.executor.executed.isEmpty())
        assertEquals(ConfirmationCodes.UNAVAILABLE, throwing.audit.events.single().detail)
    }

    @Test
    fun `a new fire while one is pending follows cooldown and dedup as if the first had run`() = runTest {
        val gate = RecordingGate()
        val rig = Rig(listOf(rule(cooldownMs = 10_000)), gate)

        rig.engine.onTrigger(envelope("wifi:1")) { DeviceState() }
        // Stesso evento: duplicato. Dentro il cooldown: soppresso. Nessuna nuova domanda.
        rig.engine.onTrigger(envelope("wifi:1")) { DeviceState() }
        rig.clockMillis = 5_000
        rig.engine.onTrigger(envelope("wifi:2")) { DeviceState() }
        assertEquals(1, gate.requests.size)
        assertEquals(
            listOf(AuditKind.SUPPRESSED_NOT_ELIGIBLE, AuditKind.SUPPRESSED_DUPLICATE, AuditKind.SUPPRESSED_COOLDOWN),
            rig.audit.events.map { it.kind },
        )

        // Dopo il cooldown la domanda è ancora aperta: una sola per regola.
        gate.answer = ConfirmationRequestResult.ALREADY_PENDING
        rig.clockMillis = 20_000
        rig.engine.onTrigger(envelope("wifi:3")) { DeviceState() }
        assertEquals(ConfirmationCodes.PENDING, rig.audit.events.last().detail)
        assertTrue(rig.executor.executed.isEmpty())
    }

    @Test
    fun `run executes the rule with the event that fired it, once`() = runTest {
        val rule = rule(cooldownMs = 600_000)
        val gate = RecordingGate()
        val rig = Rig(listOf(rule), gate)
        val first = envelope("wifi:1")
        rig.engine.onTrigger(first) { DeviceState() }

        val confirmation = confirmed(rule, gate.requests.single().envelope)
        val outcome = rig.engine.onConfirmed(confirmation) { DeviceState() }

        assertNotNull(outcome)
        assertEquals(listOf<Action>(Action.SetFlashlight(true)), rig.executor.executed)
        assertEquals(confirmation.runEventId, outcome.eventId)
        assertNotEquals(gate.requests.single().executionId, outcome.executionId)
        assertEquals(AuditKind.FIRED, rig.audit.events.last().kind)
        assertEquals(ExecutionStatus.SUCCEEDED, rig.journal.completions.last().status)
        // Nessuna nuova domanda per l'esecuzione confermata.
        assertEquals(1, gate.requests.size)

        // Un secondo tocco su Esegui è un duplicato.
        assertNull(rig.engine.onConfirmed(confirmation) { DeviceState() })
        assertEquals(1, rig.executor.executed.size)
        assertEquals(AuditKind.SUPPRESSED_DUPLICATE, rig.audit.events.last().kind)
    }

    @Test
    fun `run is refused when the rule changed, was disabled or lost confirm`() = runTest {
        val approved = rule()
        val rig = Rig(listOf(approved))
        rig.engine.onTrigger(envelope("wifi:1")) { DeviceState() }

        val edited = rule(actions = listOf(Action.SetFlashlight(false)))
        val changed = Rig(listOf(edited))
        assertNull(changed.engine.onConfirmed(confirmed(approved, envelope("wifi:1"))) { DeviceState() })
        assertTrue(changed.executor.executed.isEmpty())
        assertEquals(ConfirmationCodes.STALE, changed.audit.events.single().detail)

        val disabled = Rig(listOf(approved.copy(status = AutomationStatus.DISABLED, enabled = false)))
        assertNull(disabled.engine.onConfirmed(confirmed(approved, envelope("wifi:1"))) { DeviceState() })
        assertTrue(disabled.executor.executed.isEmpty())

        val withoutConfirm = rule(confirm = null)
        val lost = Rig(listOf(withoutConfirm))
        assertNull(lost.engine.onConfirmed(confirmed(withoutConfirm, envelope("wifi:1"))) { DeviceState() })
        assertTrue(lost.executor.executed.isEmpty())
    }

    @Test
    fun `run rechecks the fire policy and the pause`() = runTest {
        val rule = rule()
        val blocked = Rig(listOf(rule), policy = FirePolicy { _, _ -> FirePolicyDecision.Block("capability_unavailable", false) })
        assertNull(blocked.engine.onConfirmed(confirmed(rule, envelope("wifi:1"))) { DeviceState() })
        assertTrue(blocked.executor.executed.isEmpty())
        assertEquals("capability_unavailable", blocked.audit.events.single().detail)
    }

    @Test
    fun `cancel and timeout are closed codes, not errors`() = runTest {
        val rig = Rig(listOf(rule()))
        rig.engine.onConfirmationClosed(AutomationId("c1"), ConfirmationClosed.CANCELLED)
        rig.engine.onConfirmationClosed(AutomationId("c1"), ConfirmationClosed.EXPIRED)

        assertEquals(listOf(AuditKind.SUPPRESSED_NOT_ELIGIBLE, AuditKind.SUPPRESSED_NOT_ELIGIBLE), rig.audit.events.map { it.kind })
        assertEquals(listOf(ConfirmationCodes.CANCELLED, ConfirmationCodes.EXPIRED), rig.audit.events.map { it.detail })
        assertTrue(rig.journal.completions.isEmpty())
    }

    @Test
    fun `a confirmation id is random looking and never the token`() {
        val fingerprint = requireNotNull(rule().approvalFingerprint)
        val ok = ConfirmedFire("0123456789abcdef", AutomationId("c1"), fingerprint, envelope("e"))
        assertEquals("confirmed:0123456789abcdef", ok.runEventId.value)
        assertTrue(runCatching { ConfirmedFire("short", AutomationId("c1"), fingerprint, envelope("e")) }.isFailure)
        assertTrue(runCatching { ConfirmedFire("Z".repeat(20), AutomationId("c1"), fingerprint, envelope("e")) }.isFailure)
    }
}
