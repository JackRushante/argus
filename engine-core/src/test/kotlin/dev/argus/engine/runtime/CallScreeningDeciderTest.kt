package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfirmSpec
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.ScreenCallOp
import dev.argus.engine.model.StateKeys
import dev.argus.engine.model.Trigger
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.fail

class CallScreeningDeciderTest {
    // 23:30 UTC: dentro la fascia notturna 22:00-07:00 UTC.
    private val decider = CallScreeningDecider(Clock.fixed(Instant.parse("2026-11-16T23:30:00Z"), ZoneOffset.UTC))
    private val incoming = Trigger.PhoneState(PhoneEvent.INCOMING_CALL)
    private val night = Condition.TimeWindow("22:00", "07:00", "UTC")
    private val day = Condition.TimeWindow("08:00", "18:00", "UTC")
    private val all = setOf(
        CapabilityIds.TRIGGER_PHONE_CALL,
        CapabilityIds.ACTION_SCREEN_CALL,
        CapabilityIds.ACTION_SCREEN_CALL_CONTACTS,
        CapabilityIds.ACTION_SHOW_NOTIFICATION,
    )

    private fun rule(
        id: String,
        op: ScreenCallOp = ScreenCallOp.REJECT,
        unknownOnly: Boolean = false,
        trigger: Trigger = incoming,
        conditions: Condition? = null,
        status: AutomationStatus = AutomationStatus.ARMED,
        enabled: Boolean = true,
        approved: Boolean = true,
        extra: List<Action> = emptyList(),
    ): Automation {
        val base = Automation(
            id = AutomationId(id),
            name = id,
            createdBy = CreatedBy.USER,
            status = status,
            trigger = trigger,
            actions = listOf(Action.ScreenCall(op, unknownOnly)) + extra,
            conditions = conditions,
            enabled = enabled,
        )
        return if (approved) base.copy(approvalFingerprint = ApprovalFingerprints.of(base)) else base
    }

    private fun decide(
        vararg rules: Automation,
        number: String? = "+393331112222",
        available: Set<String> = all,
        paused: Set<String> = emptySet(),
        contact: Boolean? = false,
    ) = decider.decide(rules.toList(), number, available, { it.id.value in paused }, { contact })

    @Test
    fun `an armed rule rejects the call`() {
        val decision = decide(rule("a"))
        assertEquals(ScreenCallOp.REJECT, decision.op)
        assertEquals(listOf(CallScreeningDecider.RuleDecision(AutomationId("a"), ScreenCallOp.REJECT)), decision.rules)
    }

    @Test
    fun `reject wins over silence`() {
        val decision = decide(rule("s", ScreenCallOp.SILENCE), rule("r", ScreenCallOp.REJECT))
        assertEquals(ScreenCallOp.REJECT, decision.op)
        assertEquals(2, decision.rules.size)
        assertEquals(ScreenCallOp.SILENCE, decide(rule("s", ScreenCallOp.SILENCE)).op)
    }

    @Test
    fun `nothing decides when no rule qualifies, so the call rings`() {
        listOf(
            rule("off", enabled = false),
            rule("pending", status = AutomationStatus.PENDING_APPROVAL),
            rule("review", status = AutomationStatus.NEEDS_REVIEW),
            rule("unapproved", approved = false),
            rule("missed", trigger = Trigger.PhoneState(PhoneEvent.MISSED_CALL)),
            rule("other", trigger = Trigger.PhoneState(PhoneEvent.INCOMING_CALL, number = "+390000000000")),
            rule("sim", trigger = Trigger.PhoneState(PhoneEvent.INCOMING_CALL, sim = 1)),
            rule("daytime", conditions = day),
            rule("slow", conditions = Condition.StateEquals(StateKeys.RINGER, CmpOp.EQ, "normal")),
        ).forEach { candidate ->
            val decision = decide(candidate)
            assertNull(decision.op, candidate.name)
            assertEquals(emptyList(), decision.rules, candidate.name)
        }
        assertNull(decide(rule("paused"), paused = setOf("paused")).op)
        assertNull(decide(rule("nocap"), available = all - CapabilityIds.ACTION_SCREEN_CALL).op)
        // Una regola modificata dopo l'approvazione non decide.
        val edited = rule("edited").let { it.copy(actions = listOf(Action.ScreenCall(ScreenCallOp.SILENCE))) }
        assertNull(decide(edited).op)
        // Con la conferma (0.8) la decisione non potrebbe aspettare.
        val withConfirm = rule("confirm").copy(confirm = ConfirmSpec()).let {
            it.copy(requiredCapabilities = dev.argus.engine.model.CapabilityRequirements.derive(it))
        }.let { it.copy(approvalFingerprint = ApprovalFingerprints.of(it)) }
        assertNull(decide(withConfirm, available = all + CapabilityIds.RULE_CONFIRM).op)
    }

    @Test
    fun `a matching number filter and clock conditions decide`() {
        assertEquals(
            ScreenCallOp.REJECT,
            decide(rule("n", trigger = Trigger.PhoneState(PhoneEvent.INCOMING_CALL, number = "+393331112222"), conditions = night)).op,
        )
    }

    @Test
    fun `only unknown callers - contacts ring, strangers and hidden numbers are screened, doubt rings`() {
        val unknownOnly = rule("u", unknownOnly = true)
        assertEquals(ScreenCallOp.REJECT, decide(unknownOnly, contact = false).op)
        assertNull(decide(unknownOnly, contact = true).op)
        assertNull(decide(unknownOnly, contact = null).op)
        assertEquals(ScreenCallOp.REJECT, decide(unknownOnly, number = null, contact = null).op)
        assertEquals(ScreenCallOp.REJECT, decide(unknownOnly, number = "", contact = null).op)
        // Senza la capacità dei contatti la regola non decide.
        assertNull(decide(unknownOnly, available = all - CapabilityIds.ACTION_SCREEN_CALL_CONTACTS).op)
    }

    @Test
    fun `every rule with the option says whether the caller is in its scope`() {
        val unknownOnly = rule("u", unknownOnly = true)
        val everyone = rule("e")
        assertEquals(mapOf(AutomationId("u") to CallerScope.IN_SCOPE), decide(unknownOnly, everyone, contact = false).scopes)
        assertEquals(mapOf(AutomationId("u") to CallerScope.KNOWN_CALLER), decide(unknownOnly, everyone, contact = true).scopes)
        assertEquals(mapOf(AutomationId("u") to CallerScope.UNVERIFIED), decide(unknownOnly, contact = null).scopes)
        assertEquals(mapOf(AutomationId("u") to CallerScope.IN_SCOPE), decide(unknownOnly, number = null, contact = null).scopes)
        // Una rubrica che si rompe non è una risposta: nel dubbio fuori perimetro.
        val broken = decider.decide(listOf(unknownOnly), "+393331112222", all, { false }) { error("rubrica") }
        assertEquals(mapOf(AutomationId("u") to CallerScope.UNVERIFIED), broken.scopes)
        assertNull(broken.op)
        // Fuori dalla fascia oraria la regola non vale: nessun perimetro da dire.
        assertEquals(emptyMap(), decide(rule("d", unknownOnly = true, conditions = day)).scopes)
    }

    @Test
    fun `contacts are looked up once and only when a rule asks`() {
        var lookups = 0
        val counting: (String) -> Boolean? = { lookups++; false }
        decider.decide(listOf(rule("a")), "+393331112222", all, { false }, counting)
        assertEquals(0, lookups)
        decider.decide(listOf(rule("u1", unknownOnly = true), rule("u2", unknownOnly = true)), "+393331112222", all, { false }, counting)
        assertEquals(1, lookups)
        decider.decide(listOf(rule("u", unknownOnly = true)), null, all, { false }) { fail("hidden number: no lookup") }
    }

    @Test
    fun `the decision never prints the number`() {
        val text = decide(rule("a")).toString()
        assertEquals(false, "3331112222" in text)
        assertEquals(listOf(rule("a")).map { it.id }, decider.screeningRules(listOf(rule("a"), rule("x", trigger = Trigger.Manual))).map { it.id })
    }
}
