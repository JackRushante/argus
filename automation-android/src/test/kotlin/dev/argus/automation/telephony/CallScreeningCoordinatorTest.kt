package dev.argus.automation.telephony

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenCallOp
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationPause
import dev.argus.engine.runtime.CallScreeningDecider
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CallScreeningCoordinatorTest {
    private fun rule(id: String, op: ScreenCallOp, unknownOnly: Boolean = false): Automation {
        val base = Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = AutomationStatus.ARMED,
            trigger = Trigger.PhoneState(PhoneEvent.INCOMING_CALL),
            actions = listOf(Action.ScreenCall(op, unknownOnly), Action.Vibrate(100)),
        )
        return base.copy(approvalFingerprint = ApprovalFingerprints.of(base))
    }

    private val audits = mutableListOf<AuditEvent>()
    private var now = 5_000L
    private val decisions = ScreenCallDecisionLog { now }
    private var paused = false

    private fun coordinator(
        rules: List<Automation>,
        armed: suspend () -> List<Automation> = { rules },
        contact: Boolean? = false,
    ) = CallScreeningCoordinator(
        armed = armed,
        snapshots = FirePolicySnapshotProvider {
            FirePolicySnapshot(
                knownTools = emptySet(),
                availableCapabilities = setOf(
                    CapabilityIds.TRIGGER_PHONE_CALL,
                    CapabilityIds.ACTION_SCREEN_CALL,
                    CapabilityIds.ACTION_SCREEN_CALL_CONTACTS,
                    CapabilityIds.ACTION_VIBRATE,
                ),
                whitelistedConversationIds = emptySet(),
            )
        },
        pause = AutomationPause { paused },
        contacts = ContactLookup { contact },
        decider = CallScreeningDecider(Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC)),
        decisions = decisions,
        audit = object : AuditSink {
            override suspend fun record(e: AuditEvent) {
                audits += e
            }
        },
        now = { now },
        budgetMillis = 200,
    )

    @Test
    fun `a decision is applied, recorded for the rule and audited without the number`() = runTest {
        val op = coordinator(listOf(rule("a", ScreenCallOp.REJECT))).decide("+393331112222")
        assertEquals(ScreenCallOp.REJECT, op)
        val audit = audits.single()
        assertEquals(AuditKind.CALL_SCREENED, audit.kind)
        assertEquals(CallScreeningCoordinator.SCREEN_CALL_REJECTED, audit.detail)
        assertTrue("3331112222" !in audit.toString())
        // La foglia screen_call della stessa regola trova la decisione, una volta sola.
        assertEquals(ActionResult.Success, decisions.leafResult(AutomationId("a")))
        assertEquals(ActionResult.Failure(ScreenCallDecisionLog.SCREEN_CALL_NOT_APPLIED), decisions.leafResult(AutomationId("a")))
    }

    @Test
    fun `reject wins and every deciding rule is audited with the applied outcome`() = runTest {
        val op = coordinator(listOf(rule("s", ScreenCallOp.SILENCE), rule("r", ScreenCallOp.REJECT))).decide(null)
        assertEquals(ScreenCallOp.REJECT, op)
        assertEquals(
            setOf(AutomationId("s"), AutomationId("r")),
            audits.map { it.automationId }.toSet(),
        )
        assertTrue(audits.all { it.detail == CallScreeningCoordinator.SCREEN_CALL_REJECTED })
    }

    @Test
    fun `no deciding rule, no answer and nothing in the log`() = runTest {
        assertNull(coordinator(listOf(rule("u", ScreenCallOp.REJECT, unknownOnly = true)), contact = true).decide("+393331112222"))
        assertNull(coordinator(emptyList()).decide("+393331112222"))
        paused = true
        assertNull(coordinator(listOf(rule("a", ScreenCallOp.REJECT))).decide("+393331112222"))
        assertEquals(emptyList(), audits)
    }

    @Test
    fun `an error or a slow read fails open and the screening rules say so`() = runTest {
        val rules = listOf(rule("a", ScreenCallOp.REJECT))
        var calls = 0
        val failing = coordinator(rules, armed = { if (calls++ == 0) error("database") else rules })
        assertNull(failing.decide("+393331112222"))
        assertEquals(listOf(CallScreeningCoordinator.SCREEN_CALL_FAILED_OPEN), audits.map { it.detail })

        audits.clear()
        var slowCalls = 0
        val slow = coordinator(rules, armed = { if (slowCalls++ == 0) awaitCancellation() else rules })
        assertNull(slow.decide("+393331112222"))
        assertEquals(listOf(CallScreeningCoordinator.SCREEN_CALL_FAILED_OPEN), audits.map { it.detail })
        assertEquals(ActionResult.Failure(ScreenCallDecisionLog.SCREEN_CALL_NOT_APPLIED), decisions.leafResult(AutomationId("a")))
    }

    @Test
    fun `an old decision is not found by a later run`() {
        decisions.record(AutomationId("a"), ScreenCallOp.SILENCE)
        now += ScreenCallDecisionLog.MAX_AGE_MILLIS + 1
        assertEquals(ActionResult.Failure(ScreenCallDecisionLog.SCREEN_CALL_NOT_APPLIED), decisions.leafResult(AutomationId("a")))
    }
}
