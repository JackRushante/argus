package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** B13: `Automation.toDraft()` è l'unica copia tipata e non perde le variabili P4. */
class AutomationToDraftTest {
    private fun p4Rule(): Automation {
        val unsigned = Automation(
            id = AutomationId("p4"),
            name = "OTP e loop",
            createdBy = CreatedBy.LLM,
            status = AutomationStatus.ARMED,
            trigger = Trigger.PhoneState(PhoneEvent.SMS_RECEIVED, number = "+39000", textMatch = "codice"),
            actions = listOf(
                Action.If(
                    condition = Condition.VarCompare("threshold", CmpOp.GT, expected = "5"),
                    then = listOf(Action.ShowNotification("OTP", "\${otp}")),
                    orElse = listOf(Action.Wait(500)),
                ),
                Action.RunShell("echo done", captureAs = "out"),
            ),
            vars = listOf(
                VarBinding.TriggerPayload("otp", TriggerField.TEXT, "([0-9]{6})", ConfidentialityLabel.SECRET),
                VarBinding.Literal("threshold", "10", VarType.NUMBER, ConfidentialityLabel.PUBLIC),
                VarBinding.RandomInt("dice", 6),
            ),
            conditions = Condition.TimeWindow("08:00", "22:00", "Europe/Rome"),
            enabled = true,
            priority = 3,
            cooldownMs = 90_000,
            schemaVersion = AUTOMATION_SCHEMA_VERSION_P4,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    @Test
    fun `toDraft copies name trigger actions vars conditions and cooldown`() {
        val rule = p4Rule()
        val draft = rule.toDraft()

        assertEquals(rule.name, draft.name)
        assertEquals(rule.trigger, draft.trigger)
        assertEquals(rule.actions, draft.actions)
        assertEquals(rule.vars, draft.vars)
        assertEquals(rule.conditions, draft.conditions)
        assertEquals(rule.cooldownMs, draft.cooldownMs)
        assertEquals("", draft.rationale)
        assertTrue(draft.vars.isNotEmpty(), "le variabili P4 non devono andare perse")
    }

    @Test
    fun `a p4 rule round trips through its draft with the same schema and fingerprint`() {
        val rule = p4Rule()
        val draft = rule.toDraft()

        assertEquals(AUTOMATION_SCHEMA_VERSION_P4, AutomationSchema.versionFor(draft))

        val rebuilt = rule.copy(
            name = draft.name,
            trigger = draft.trigger,
            actions = draft.actions,
            vars = draft.vars,
            conditions = draft.conditions,
            cooldownMs = draft.cooldownMs,
        )
        assertEquals(rule, rebuilt)
        assertEquals(ApprovalFingerprints.of(rule), ApprovalFingerprints.of(rebuilt))
        assertEquals(rule.approvalFingerprint, ApprovalFingerprints.of(rebuilt))
    }

    @Test
    fun `a draft without the variables would be a different invalid rule`() {
        // Regressione B13: la copia locale senza `vars` cambiava lo schema e rompeva `${otp}`.
        val rule = p4Rule()
        val lossy = rule.toDraft().copy(vars = emptyList())

        assertTrue(
            ApprovalFingerprints.of(rule.copy(vars = lossy.vars)) != ApprovalFingerprints.of(rule),
            "togliere le variabili cambia il fingerprint",
        )
    }

    @Test
    fun `a flat v1 rule keeps a flat v1 draft`() {
        val flat = Automation(
            id = AutomationId("flat"),
            name = "dnd",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.DISABLED,
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
            enabled = false,
        )
        val draft = flat.toDraft()
        assertEquals(AUTOMATION_SCHEMA_VERSION_V1, AutomationSchema.versionFor(draft))
        assertEquals(emptyList(), draft.vars)
        assertEquals(null, draft.conditions)
    }
}
