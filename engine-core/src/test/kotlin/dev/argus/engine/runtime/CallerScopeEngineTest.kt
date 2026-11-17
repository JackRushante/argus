package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenCallOp
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.8 `screen_call` con `onlyUnknownCallers`: l'opzione è una condizione della regola intera. Le altre azioni
 * (anche quelle PRIMA del filtro) girano solo se chi chiama è nel perimetro; un contatto o un'appartenenza che
 * non si è potuta stabilire fermano la regola con un codice chiuso, come una condizione non soddisfatta.
 */
class CallerScopeEngineTest {
    private val call = TriggerEvent.PhoneStateChanged(PhoneEvent.INCOMING_CALL, "+393331112222")

    private fun rule(id: String, unknownOnly: Boolean, smsFirst: Boolean = true): Automation {
        val sms = Action.SendSms("+393334445555", "Non rispondo agli sconosciuti")
        val screen = Action.ScreenCall(ScreenCallOp.REJECT, unknownOnly)
        val unsigned = Automation(
            AutomationId(id), id, CreatedBy.USER, AutomationStatus.ARMED,
            Trigger.PhoneState(PhoneEvent.INCOMING_CALL),
            if (smsFirst) listOf(sms, screen) else listOf(screen, sms),
            enabled = true,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    private class Rig(rules: List<Automation>, gate: CallerScopeGate?) {
        val executor = FakeActionExecutor()
        val audit = FakeAuditSink()
        val journal = FakeExecutionJournal()
        val asked = mutableListOf<String>()
        val engine = if (gate == null) {
            Engine(
                store = FakeAutomationStore(rules),
                executor = executor,
                evaluator = ConditionEvaluator(Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC)),
                matcher = TriggerMatcher(),
                firePolicy = FirePolicy { _, _ -> FirePolicyDecision.Allow },
                audit = audit,
                journal = journal,
                now = { 1_000 },
            )
        } else {
            Engine(
                store = FakeAutomationStore(rules),
                executor = executor,
                evaluator = ConditionEvaluator(Clock.fixed(Instant.parse("2026-11-16T10:00:00Z"), ZoneOffset.UTC)),
                matcher = TriggerMatcher(),
                firePolicy = FirePolicy { _, _ -> FirePolicyDecision.Allow },
                audit = audit,
                journal = journal,
                callerScope = CallerScopeGate { automation, _ -> asked += automation.id.value; gate.closedCode(automation, call) },
                now = { 1_000 },
            )
        }
    }

    private fun envelope(id: String) = TriggerEnvelope(TriggerEventId(id), call)

    @Test
    fun `a caller in the contacts stops the whole rule with a closed code, sms placed first included`() = runTest {
        val rig = Rig(listOf(rule("r", unknownOnly = true)), CallerScopeGate { _, _ -> CallerScopeGate.KNOWN_CALLER })
        val out = rig.engine.onTrigger(envelope("call:1")) { DeviceState() }
        assertTrue(out.isEmpty())
        assertTrue(rig.executor.executed.isEmpty(), "nessuna azione, nemmeno l'SMS prima del filtro")
        val audit = rig.audit.events.single()
        assertEquals(AuditKind.CONDITIONS_NOT_MET, audit.kind)
        assertEquals(CallerScopeGate.KNOWN_CALLER, audit.detail)
        assertTrue("3331112222" !in audit.toString())
        assertTrue(rig.journal.completions.isEmpty(), "nessun claim: come una condizione non soddisfatta")
    }

    @Test
    fun `an unknown caller runs every action once`() = runTest {
        val rig = Rig(listOf(rule("r", unknownOnly = true, smsFirst = false)), CallerScopeGate { _, _ -> null })
        val out = rig.engine.onTrigger(envelope("call:1")) { DeviceState() }
        assertEquals(1, out.size)
        assertEquals(1, rig.executor.executed.count { it is Action.SendSms })
        assertEquals(1, rig.executor.executed.count { it is Action.ScreenCall })
    }

    @Test
    fun `a membership that could not be established runs nothing`() = runTest {
        val rig = Rig(listOf(rule("r", unknownOnly = true)), CallerScopeGate { _, _ -> CallerScopeGate.CALLER_UNVERIFIED })
        rig.engine.onTrigger(envelope("call:1")) { DeviceState() }
        assertTrue(rig.executor.executed.isEmpty())
        assertEquals(CallerScopeGate.CALLER_UNVERIFIED, rig.audit.events.single().detail)
    }

    @Test
    fun `a gate that throws, or no gate at all, fails closed for the actions`() = runTest {
        val throwing = Rig(listOf(rule("r", unknownOnly = true)), CallerScopeGate { _, _ -> error("boom") })
        throwing.engine.onTrigger(envelope("call:1")) { DeviceState() }
        assertTrue(throwing.executor.executed.isEmpty())
        assertEquals(CallerScopeGate.CALLER_UNVERIFIED, throwing.audit.events.single().detail)

        val none = Rig(listOf(rule("r", unknownOnly = true)), gate = null)
        none.engine.onTrigger(envelope("call:1")) { DeviceState() }
        assertTrue(none.executor.executed.isEmpty())
        assertEquals(CallerScopeGate.CALLER_UNVERIFIED, none.audit.events.single().detail)
    }

    @Test
    fun `a rule without the option is never asked and runs as before`() = runTest {
        val rig = Rig(listOf(rule("r", unknownOnly = false)), CallerScopeGate { _, _ -> CallerScopeGate.KNOWN_CALLER })
        val out = rig.engine.onTrigger(envelope("call:1")) { DeviceState() }
        assertEquals(1, out.size)
        assertEquals(2, rig.executor.executed.size)
        assertTrue(rig.asked.isEmpty())
        assertTrue(CallerScopeGate.appliesTo(rule("x", unknownOnly = true)))
        assertTrue(!CallerScopeGate.appliesTo(rule("x", unknownOnly = false)))
    }
}
