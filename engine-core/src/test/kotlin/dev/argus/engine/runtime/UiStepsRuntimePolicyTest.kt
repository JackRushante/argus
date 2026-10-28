package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.InterpolationPolicy
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.PhoneEvent
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.UiStep
import dev.argus.engine.model.ValueProvenance
import dev.argus.engine.model.VarType
import dev.argus.engine.model.VarValue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** `ui_steps` al fuoco: risoluzione dei segnaposto di `type` e gate del trigger al fire-time. */
class UiStepsRuntimePolicyTest {
    private val interpolator = TaintAwareInterpolator()

    private fun scope(vararg values: Pair<String, VarValue>) = VarScope(mapOf(*values))

    private fun tainted(text: String) = VarValue(
        text, VarType.TEXT, IntegrityLabel.TAINTED, ConfidentialityLabel.PRIVATE, setOf(ValueProvenance.SMS),
    )

    private fun macro(vararg steps: UiStep, pkg: String? = "com.example.shop") =
        Action.UiSteps(pkg, steps.toList())

    // --- interpolazione ------------------------------------------------------------------------

    @Test
    fun `only the type steps are template fields and each one is labeled by its position`() {
        val action = macro(
            UiStep.Click(text = "Cerca"),
            UiStep.Type("uno \${a}"),
            UiStep.Back,
            UiStep.Type("due \${b}"),
        )
        val fields = InterpolationPolicy.textFields(action)
        assertEquals(listOf("steps.1.text", "steps.3.text"), fields.map { it.label })
        assertTrue(fields.all { it.cls == InterpolationPolicy.FieldClass.SINK })
    }

    @Test
    fun `a tainted value enters the typed text as a local sink and the rest of the macro is untouched`() {
        val action = macro(
            UiStep.WaitFor(text = "Cerca"),
            UiStep.Click(id = "search"),
            UiStep.Type("Ciao \${sms}"),
        )
        val resolved = assertIs<ActionResolution.Resolved>(
            interpolator.resolve(action, scope("sms" to tainted("a b c"))),
        ).value
        val out = assertIs<Action.UiSteps>(resolved.action)
        assertEquals(UiStep.Type("Ciao a b c"), out.steps[2])
        assertEquals(action.steps.take(2), out.steps.take(2))
        assertEquals("com.example.shop", out.pkg)
        assertEquals(IntegrityLabel.TAINTED, resolved.inputIntegrity)
    }

    @Test
    fun `two type steps with the same text resolve independently`() {
        val action = macro(UiStep.Type("\${a}"), UiStep.Back, UiStep.Type("\${a}"))
        val out = assertIs<Action.UiSteps>(
            assertIs<ActionResolution.Resolved>(
                interpolator.resolve(action, scope("a" to tainted("x"))),
            ).value.action,
        )
        assertEquals(UiStep.Type("x"), out.steps[0])
        assertEquals(UiStep.Type("x"), out.steps[2])
    }

    @Test
    fun `a missing variable and an empty or oversized result block the macro`() {
        assertEquals(
            "variable_unavailable",
            assertIs<ActionResolution.Blocked>(interpolator.resolve(macro(UiStep.Type("\${a}")), scope())).code,
        )
        assertEquals(
            "resolved_action_invalid",
            assertIs<ActionResolution.Blocked>(
                interpolator.resolve(macro(UiStep.Type("\${a}")), scope("a" to tainted(""))),
            ).code,
        )
        assertEquals(
            "interpolation_too_long",
            assertIs<ActionResolution.Blocked>(
                interpolator.resolve(macro(UiStep.Type("\${a}")), scope("a" to tainted("x".repeat(1_001)))),
            ).code,
        )
    }

    @Test
    fun `a macro that got a placeholder in a selector is refused even if it slipped past the validator`() {
        val sneaky = macro(UiStep.Click(text = "\${a}"))
        assertEquals(
            "resolved_action_invalid",
            assertIs<ActionResolution.Blocked>(interpolator.resolve(sneaky, scope("a" to tainted("x")))).code,
        )
    }

    @Test
    fun `a macro on a forbidden package is refused after resolution too`() {
        assertEquals(
            "resolved_action_invalid",
            assertIs<ActionResolution.Blocked>(
                interpolator.resolve(macro(UiStep.Back, pkg = "com.android.settings"), scope()),
            ).code,
        )
    }

    // --- fire policy ---------------------------------------------------------------------------

    private fun automation(trigger: Trigger, action: Action): Automation {
        val value = Automation(
            id = AutomationId("a1"),
            name = "macro",
            createdBy = CreatedBy.LLM,
            status = AutomationStatus.ARMED,
            trigger = trigger,
            actions = listOf(action),
        )
        return value.copy(approvalFingerprint = ApprovalFingerprints.of(value))
    }

    private fun policy(capabilities: Set<String>, whitelist: Set<String> = emptySet()) =
        RevalidatingFirePolicy(
            FirePolicySnapshotProvider {
                FirePolicySnapshot(setOf("whatsapp_reply", "state.read"), capabilities, whitelist)
            },
        )

    @Test
    fun `a macro from a trusted trigger runs when the accessibility capability is live`() = runTest {
        val rule = automation(Trigger.Immediate, macro(UiStep.Click(text = "OK")))
        val event = TriggerEvent.ImmediateFired(rule.id, requireNotNull(rule.approvalFingerprint))
        assertEquals(
            FirePolicyDecision.Allow,
            policy(setOf(CapabilityIds.TRIGGER_IMMEDIATE, ActionCapabilities.UI_STEPS)).evaluate(rule, event),
        )
    }

    @Test
    fun `without the accessibility capability the macro is blocked but not sent to review`() = runTest {
        val rule = automation(Trigger.Immediate, macro(UiStep.Click(text = "OK")))
        val event = TriggerEvent.ImmediateFired(rule.id, requireNotNull(rule.approvalFingerprint))
        val block = assertIs<FirePolicyDecision.Block>(
            policy(setOf(CapabilityIds.TRIGGER_IMMEDIATE)).evaluate(rule, event),
        )
        assertEquals("capability_unavailable", block.code)
        assertEquals(false, block.needsReview)
    }

    @Test
    fun `a macro under an sms trigger is blocked for review with its own code`() = runTest {
        val rule = automation(Trigger.PhoneState(PhoneEvent.SMS_RECEIVED), macro(UiStep.Click(text = "OK")))
        val block = assertIs<FirePolicyDecision.Block>(
            policy(setOf(CapabilityIds.TRIGGER_PHONE_SMS, ActionCapabilities.UI_STEPS)).evaluate(
                rule,
                TriggerEvent.PhoneStateChanged(PhoneEvent.SMS_RECEIVED, number = "+39001", smsText = "x"),
            ),
        )
        assertEquals("ui_steps_external_trigger", block.code)
        assertTrue(block.needsReview)
    }
}
