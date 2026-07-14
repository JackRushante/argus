package dev.argus.automation.vm

import dev.argus.automation.ApprovalFlowReview
import dev.argus.data.dao.AuditLogRecord
import dev.argus.data.entities.ActionResultEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.SCHEMA_VERSION
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftReview
import dev.argus.engine.safety.PendingDraft
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiStateMappersTest {
    @Test
    fun `log row preserves audit id and surfaces generative cloud execution`() {
        val row = AuditLogRecord(
            id = 42L,
            automationId = "automation-7",
            automationName = "Risposta AI",
            kind = AuditKind.FIRED,
            atMillis = 1_000L,
            detail = "",
            executionId = "execution-9",
            executionStatus = ExecutionStatus.SUBMITTED,
            succeededCount = 0,
            failedCount = 0,
            submittedCount = 1,
        ).toLogRow(
            listOf(
                ActionResultEntity(
                    executionId = "execution-9",
                    actionIndex = 0,
                    actionType = "invoke_llm",
                    outcome = ActionJournalOutcome.SUBMITTED,
                    atMillis = 1_000L,
                ),
            ),
        )

        assertEquals("42", row.id)
        assertEquals("automation-7", row.automationId)
        assertEquals(dev.argus.ui.model.LogOutcome.SUBMITTED, row.outcome)
        assertTrue(row.isGenerative)
        assertTrue(assertNotNull(row.expandedDetail).single().contains("invoke llm"))
    }

    @Test
    fun `deferred reply is surfaced as manual delivery instead of success`() {
        val row = AuditLogRecord(
            id = 43L,
            automationId = "automation-7",
            automationName = "Risposta AI",
            kind = AuditKind.FIRED,
            atMillis = 1_100L,
            detail = "",
            executionId = "execution-10",
            executionStatus = ExecutionStatus.DEFERRED,
            succeededCount = 0,
            failedCount = 0,
            submittedCount = 0,
            deferredCount = 1,
        ).toLogRow(
            listOf(
                ActionResultEntity(
                    executionId = "execution-10",
                    actionIndex = 0,
                    actionType = "invoke_llm",
                    outcome = ActionJournalOutcome.DEFERRED,
                    atMillis = 1_100L,
                    errorCode = "reply_channel_expired",
                ),
            ),
        )

        assertEquals(dev.argus.ui.model.LogOutcome.DEFERRED, row.outcome)
        assertTrue(row.summary.contains("manualmente"))
        assertTrue(assertNotNull(row.expandedDetail).single().contains("differita"))
    }

    @Test
    fun `deleted automation cannot be opened from an audit row`() {
        val row = AuditLogRecord(
            id = 9L,
            automationId = "deleted",
            automationName = null,
            kind = AuditKind.ERROR,
            atMillis = 1_000L,
            detail = "executor_failed",
            executionId = null,
            executionStatus = null,
            succeededCount = null,
            failedCount = null,
            submittedCount = null,
        ).toLogRow(emptyList())

        assertEquals("9", row.id)
        assertNull(row.automationId)
    }

    @Test
    fun `generative draft review always copies the privacy warning`() {
        val draft = AutomationDraft(
            name = "Risposta AI",
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            actions = listOf(
                Action.InvokeLlm(
                    goal = "prepara una risposta",
                    contextSources = listOf("notification.text"),
                    allowedTools = emptyList(),
                    replyTargetSender = false,
                ),
            ),
        )
        val unsigned = PendingDraft(
            id = DraftId("draft-ai"),
            automationId = AutomationId("automation-ai"),
            revision = 1L,
            fingerprint = ApprovalFingerprint("0".repeat(64)),
            draft = draft,
            createdBy = CreatedBy.LLM,
            priority = 0,
            schemaVersion = SCHEMA_VERSION,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
        )
        val snapshot = unsigned.copy(
            fingerprint = ApprovalFingerprints.of(unsigned.pendingAutomation()),
        )

        val warnings = reviewWarnings(
            ApprovalFlowReview(DraftReview(snapshot, emptyList()), emptyList()),
        )

        assertTrue(warnings.any { it.code == "privacy_generative" })
    }
}
