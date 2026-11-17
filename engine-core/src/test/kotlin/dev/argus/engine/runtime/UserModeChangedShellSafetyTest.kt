package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.UiStep
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
import dev.argus.engine.safety.StaticShellSafety
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * `user_mode_changed` non avvia MAI shell né macro sullo schermo: lo cambiano anche il riquadro e il widget, e
 * una regola che cambia modalità può essere scritta da chiunque compili una regola. Rifiutato all'approvazione e
 * di nuovo allo scatto, anche se la regola salvata fosse stata manomessa.
 */
class UserModeChangedShellSafetyTest {
    private val shell = Action.RunShell("echo ciao")
    private val macro = Action.UiSteps("com.example.shop", listOf(UiStep.Click(text = "OK")))

    @Test
    fun `the trigger and its event are never shell-trusted, with or without a target mode`() {
        listOf(Trigger.UserModeChanged(), Trigger.UserModeChanged("Casa")).forEach { trigger ->
            assertFalse(StaticShellSafety.allows(trigger, setOf("chat-1")), "$trigger")
            assertFalse(StaticShellSafety.allows(trigger, TriggerEvent.UserModeChanged("Casa", null), setOf("chat-1")))
        }
        assertFalse(StaticShellSafety.allows(TriggerEvent.UserModeChanged("Casa", "Lavoro"), setOf("chat-1")))
        assertFalse(StaticShellSafety.allows(TriggerEvent.UserModeChanged("Casa", "Lavoro", chained = true), emptySet()))
    }

    @Test
    fun `the validator refuses shell and macros under a mode change, also nested`() {
        val validator = DraftValidator(setOf("whatsapp_reply"))
        fun errors(vararg actions: Action) = validator.validate(
            AutomationDraft(name = "m", trigger = Trigger.UserModeChanged("Casa"), actions = actions.toList()),
            emptySet(),
        ).filter { it.severity == Severity.ERROR }.map { it.code }

        assertTrue("shell_external_trigger" in errors(shell))
        assertTrue("ui_steps_external_trigger" in errors(macro))
        val nested = Action.If(dev.argus.engine.model.Condition.BooleanLiteral(true), then = listOf(shell))
        assertTrue("shell_external_trigger" in errors(nested), "${errors(nested)}")
    }

    private fun signed(action: Action): Automation {
        val value = Automation(
            id = AutomationId("m1"),
            name = "modalità",
            createdBy = CreatedBy.LLM,
            status = AutomationStatus.ARMED,
            trigger = Trigger.UserModeChanged("Casa"),
            actions = listOf(action),
        )
        return value.copy(approvalFingerprint = ApprovalFingerprints.of(value))
    }

    private val policy = RevalidatingFirePolicy(
        FirePolicySnapshotProvider {
            FirePolicySnapshot(
                setOf("whatsapp_reply", "state.read"),
                setOf(CapabilityIds.TRIGGER_USER_MODE_CHANGED, ActionCapabilities.RUN_SHELL, ActionCapabilities.UI_STEPS),
                emptySet(),
            )
        },
    )

    @Test
    fun `a stored rule that was tampered into shell or macro is blocked at fire time and sent to review`() = runTest {
        listOf(shell to "shell_external_trigger", macro to "ui_steps_external_trigger").forEach { (action, code) ->
            val block = assertIs<FirePolicyDecision.Block>(
                policy.evaluate(signed(action), TriggerEvent.UserModeChanged("Casa", null)),
            )
            assertEquals(code, block.code)
            assertTrue(block.needsReview)
        }
    }
}
