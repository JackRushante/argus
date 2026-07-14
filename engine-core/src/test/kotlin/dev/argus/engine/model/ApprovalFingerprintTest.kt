package dev.argus.engine.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ApprovalFingerprintTest {
    private val base = AutomationDraft(
        name = "DND notte",
        trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
        actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
        rationale = "testo del modello",
        cooldownMs = 5_000,
    )

    @Test
    fun `rationale and runtime arm flags do not affect executable fingerprint`() {
        val original = automation(base)
        assertEquals(
            ApprovalFingerprints.of(original),
            ApprovalFingerprints.of(automation(base.copy(rationale = "altra prosa"))),
        )
        assertEquals(
            ApprovalFingerprints.of(original),
            ApprovalFingerprints.of(original.copy(status = AutomationStatus.ARMED, enabled = true)),
        )
    }

    @Test
    fun `every executable edit changes fingerprint`() {
        val original = ApprovalFingerprints.of(automation(base))
        assertNotEquals(original, ApprovalFingerprints.of(automation(base.copy(name = "altro nome"))))
        assertNotEquals(
            original,
            ApprovalFingerprints.of(automation(base.copy(actions = listOf(Action.SetDnd(DndMode.TOTAL))))),
        )
        assertNotEquals(original, ApprovalFingerprints.of(automation(base.copy(cooldownMs = 6_000))))
        assertNotEquals(original, ApprovalFingerprints.of(automation(base).copy(priority = 7)))
        assertNotEquals(original, ApprovalFingerprints.of(automation(base).copy(id = AutomationId("other"))))
        assertNotEquals(
            original,
            ApprovalFingerprints.of(
                automation(
                    base.copy(
                        trigger = Trigger.Time(
                            cron = "0 23 * * *",
                            tz = "Europe/Rome",
                            precision = TimePrecision.EXACT,
                        ),
                    ),
                ),
            ),
        )
        assertNotEquals(
            original,
            ApprovalFingerprints.of(
                automation(base).copy(requiredCapabilities = setOf(CapabilityIds.TRIGGER_TIME)),
            ),
        )
    }

    private fun automation(draft: AutomationDraft) = Automation(
        id = AutomationId("draft-a"),
        name = draft.name,
        createdBy = CreatedBy.LLM,
        status = AutomationStatus.PENDING_APPROVAL,
        trigger = draft.trigger,
        actions = draft.actions,
        conditions = draft.conditions,
        enabled = false,
        priority = 2,
        cooldownMs = draft.cooldownMs,
    )
}
