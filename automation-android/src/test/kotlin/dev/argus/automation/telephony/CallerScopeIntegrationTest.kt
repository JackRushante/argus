package dev.argus.automation.telephony

import dev.argus.automation.manual.MapStore
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
import dev.argus.engine.runtime.ActionExecutor
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationPause
import dev.argus.engine.runtime.CallScreeningDecider
import dev.argus.engine.runtime.CallerScope
import dev.argus.engine.runtime.CallerScopeGate
import dev.argus.engine.runtime.ConditionEvaluator
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.Engine
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.FirePolicy
import dev.argus.engine.runtime.FirePolicyDecision
import dev.argus.engine.runtime.FirePolicySnapshot
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.runtime.TriggerEnvelope
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import dev.argus.engine.runtime.TriggerMatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `screen_call` «solo chi non è nei contatti» + un SMS: il filtro chiamate decide e calcola il perimetro, il
 * motore lo legge prima di eseguire le altre azioni, da qualunque strada arrivi la chiamata.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CallerScopeIntegrationTest {
    private val number = "+393331112222"
    private val clock = Clock.fixed(Instant.parse("2026-11-16T12:00:00Z"), ZoneOffset.UTC)
    private var now = 10_000L
    private val audits = mutableListOf<AuditEvent>()
    private val auditSink = object : AuditSink {
        override suspend fun record(e: AuditEvent) {
            audits += e
        }
    }
    private val decisions = ScreenCallDecisionLog { now }
    private val scopes = CallerScopeLog(now = { now }, waitMillis = 3_000)
    private val executed = mutableListOf<Action>()
    private val executor = object : ActionExecutor {
        override suspend fun execute(action: Action, ctx: FireContext): ActionResult {
            executed += action
            return if (action is Action.ScreenCall) decisions.leafResult(ctx.automationId) else ActionResult.Success
        }
    }

    private fun rule(id: String, unknownOnly: Boolean): Automation {
        val unsigned = Automation(
            AutomationId(id), id, CreatedBy.USER, AutomationStatus.ARMED,
            Trigger.PhoneState(PhoneEvent.INCOMING_CALL),
            // L'SMS viene PRIMA del filtro: anche lui deve rispettare il perimetro.
            listOf(Action.SendSms("+393334445555", "Non rispondo agli sconosciuti"), Action.ScreenCall(ScreenCallOp.REJECT, unknownOnly)),
            enabled = true,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    private fun TestScope.coordinator(
        rules: List<Automation>,
        contact: Boolean?,
        armed: suspend () -> List<Automation> = { rules },
    ) = CallScreeningCoordinator(
        armed = armed,
        snapshots = FirePolicySnapshotProvider {
            FirePolicySnapshot(
                knownTools = emptySet(),
                availableCapabilities = setOf(
                    CapabilityIds.TRIGGER_PHONE_CALL,
                    CapabilityIds.ACTION_SCREEN_CALL,
                    CapabilityIds.ACTION_SCREEN_CALL_CONTACTS,
                    CapabilityIds.ACTION_SEND_SMS,
                ),
                whitelistedConversationIds = emptySet(),
            )
        },
        pause = AutomationPause { false },
        contacts = ContactLookup { contact },
        decider = CallScreeningDecider(clock),
        decisions = decisions,
        audit = auditSink,
        scopes = scopes,
        now = { now },
        budgetMillis = 200,
        worker = StandardTestDispatcher(testScheduler),
    )

    private fun engine(rules: List<Automation>) = Engine(
        store = MapStore(*rules.toTypedArray()),
        executor = executor,
        evaluator = ConditionEvaluator(clock),
        matcher = TriggerMatcher(),
        firePolicy = FirePolicy { _, _ -> FirePolicyDecision.Allow },
        audit = auditSink,
        callerScope = scopes,
        now = { now },
    )

    /** La chiamata arriva al motore (dal filtro dopo un rifiuto, o dal solito squillo). */
    private suspend fun Engine.ring(id: String) = onTrigger(
        TriggerEnvelope(TriggerEventId(id), TriggerEvent.PhoneStateChanged(PhoneEvent.INCOMING_CALL, number)),
    ) { DeviceState() }

    private fun sms() = executed.count { it is Action.SendSms }

    @Test
    fun `a contact calls - the call rings, no sms, a closed code`() = runTest {
        val rules = listOf(rule("r", unknownOnly = true))
        assertNull(coordinator(rules, contact = true).decide(number))
        engine(rules).ring("ring:1")
        assertEquals(0, sms())
        val skipped = audits.single { it.kind == AuditKind.CONDITIONS_NOT_MET }
        assertEquals(CallerScopeGate.KNOWN_CALLER, skipped.detail)
        assertTrue(number.drop(3) !in skipped.toString())
    }

    @Test
    fun `an unknown caller is rejected and texted once`() = runTest {
        val rules = listOf(rule("r", unknownOnly = true))
        assertEquals(ScreenCallOp.REJECT, coordinator(rules, contact = false).decide(number))
        val engine = engine(rules)
        assertEquals(1, engine.ring("screened:1").size)
        assertEquals(1, sms())
        // Un secondo arrivo della stessa chiamata non riusa il perimetro: nessun secondo SMS.
        engine.ring("ring:1")
        assertEquals(1, sms())
    }

    @Test
    fun `contact membership unknown - nothing else runs, the call still rings`() = runTest {
        val rules = listOf(rule("r", unknownOnly = true))
        val answered = mutableListOf<ScreenCallOp?>()
        assertNull(coordinator(rules, contact = null).decide(number) { answered += it; true })
        assertEquals(listOf<ScreenCallOp?>(null), answered)
        engine(rules).ring("ring:1")
        assertEquals(0, sms())
        assertEquals(CallerScopeGate.CALLER_UNVERIFIED, audits.single { it.kind == AuditKind.CONDITIONS_NOT_MET }.detail)
    }

    @Test
    fun `screening that times out leaves the membership unknown`() = runTest {
        val rules = listOf(rule("r", unknownOnly = true))
        var calls = 0
        val slow = coordinator(rules, contact = false, armed = { if (calls++ == 0) awaitCancellation() else rules })
        assertNull(slow.decide(number))
        engine(rules).ring("ring:1")
        assertEquals(0, sms())
    }

    @Test
    fun `the ringing path waits for a screening still in progress, within the bound`() = runTest {
        val rules = listOf(rule("r", unknownOnly = true))
        scopes.begin()
        val engine = engine(rules)
        val ringing = launch { engine.ring("ring:1") }
        runCurrent()
        assertEquals(0, sms(), "aspetta il filtro")
        scopes.finish(mapOf(AutomationId("r") to CallerScope.IN_SCOPE))
        runCurrent()
        ringing.join()
        assertEquals(1, sms())

        // Un filtro che non finisce mai: dopo il limite la regola non esegue.
        scopes.begin()
        val stuck = launch { engine.ring("ring:2") }
        advanceTimeBy(3_001)
        stuck.join()
        assertEquals(1, sms())
        assertEquals(CallerScopeGate.CALLER_UNVERIFIED, audits.last { it.kind == AuditKind.CONDITIONS_NOT_MET }.detail)
    }

    @Test
    fun `without the option a failed-open screening still runs the other actions`() = runTest {
        val rules = listOf(rule("r", unknownOnly = false))
        var calls = 0
        val failing = coordinator(rules, contact = null, armed = { if (calls++ == 0) error("database") else rules })
        assertNull(failing.decide(number))
        assertEquals(1, engine(rules).ring("ring:1").size)
        assertEquals(1, sms())
        assertTrue(audits.none { it.kind == AuditKind.CONDITIONS_NOT_MET })
        assertTrue(audits.any { it.detail == CallScreeningCoordinator.SCREEN_CALL_FAILED_OPEN })
    }
}
