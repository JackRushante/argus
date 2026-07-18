package dev.argus.engine.safety

import dev.argus.engine.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class P4ApprovalContractTest {
    @Test fun `pending automation retains variables and selects schema two`() {
        val draft = AutomationDraft(
            name = "p4",
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
            actions = listOf(
                Action.While(
                    condition = Condition.BooleanLiteral(true),
                    body = listOf(Action.SetFlashlight(true), Action.SetFlashlight(false)),
                    maxIterations = 2,
                ),
            ),
            vars = listOf(
                VarBinding.Literal(
                    "enabled",
                    "true",
                    VarType.BOOLEAN,
                    ConfidentialityLabel.PUBLIC,
                ),
            ),
        )
        val unsigned = pending(draft, AutomationSchema.versionFor(draft))
        val signed = unsigned.copy(fingerprint = ApprovalFingerprints.of(unsigned.pendingAutomation()))

        assertEquals(AUTOMATION_SCHEMA_VERSION_P4, signed.schemaVersion)
        assertEquals(draft.vars, signed.pendingAutomation().vars)
        assertTrue(signed.hasValidFingerprint())
        assertFalse(signed.copy(schemaVersion = AUTOMATION_SCHEMA_VERSION_V1).hasValidFingerprint())
    }

    @Test fun `legacy draft stays schema one and keeps its pinned material`() {
        val draft = AutomationDraft(
            name = "legacy",
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetWifi(true)),
        )
        val unsigned = pending(draft, AutomationSchema.versionFor(draft))
        val signed = unsigned.copy(fingerprint = ApprovalFingerprints.of(unsigned.pendingAutomation()))

        assertEquals(AUTOMATION_SCHEMA_VERSION_V1, signed.schemaVersion)
        assertTrue(signed.hasValidFingerprint())
    }

    private fun pending(draft: AutomationDraft, schemaVersion: Int) = PendingDraft(
        id = DraftId("draft"),
        automationId = AutomationId("automation"),
        revision = 1,
        fingerprint = ApprovalFingerprint("0".repeat(64)),
        draft = draft,
        createdBy = CreatedBy.USER,
        priority = 0,
        schemaVersion = schemaVersion,
        createdAtMillis = 1,
        updatedAtMillis = 1,
    )
}
