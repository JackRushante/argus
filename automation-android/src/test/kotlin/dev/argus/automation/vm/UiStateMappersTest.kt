package dev.argus.automation.vm

import dev.argus.automation.ApprovalFlowReview
import dev.argus.data.dao.AuditLogRecord
import dev.argus.data.entities.ActionResultEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
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
    fun `unavailable condition state is distinct from a false condition`() {
        val unavailable = AuditLogRecord(
            id = 10L,
            automationId = "state-rule",
            automationName = "Regola stato",
            kind = AuditKind.CONDITIONS_NOT_MET,
            atMillis = 1_000L,
            detail = "condition_state_unavailable",
            executionId = null,
            executionStatus = null,
            succeededCount = null,
            failedCount = null,
            submittedCount = null,
        ).toLogRow(emptyList())
        val falseCondition = AuditLogRecord(
            id = 11L,
            automationId = "state-rule",
            automationName = "Regola stato",
            kind = AuditKind.CONDITIONS_NOT_MET,
            atMillis = 1_001L,
            detail = "",
            executionId = null,
            executionStatus = null,
            succeededCount = null,
            failedCount = null,
            submittedCount = null,
        ).toLogRow(emptyList())

        assertEquals("stato necessario non disponibile", unavailable.summary)
        assertEquals("condizioni non soddisfatte", falseCondition.summary)
    }

    @Test
    fun `list rows resolve the trusted whitelist name instead of the conversation hash`() {
        val hash = "shortcut:com.whatsapp:feedbeef00aabbcc"
        val labels = mapOf(hash to "Ottica Marci")
        val trigger = Trigger.Notification("com.whatsapp", conversationId = hash, isGroup = false)

        val armedRow = Automation(
            AutomationId("a1"), "Rispondi a Ottica", CreatedBy.LLM, AutomationStatus.ARMED,
            trigger, listOf(Action.WhatsAppReply("ok")),
        ).toAutomationRow(lastFiredAt = null, nowMillis = 0L, conversationLabels = labels)
        assertTrue(armedRow.triggerSummary.contains("Ottica Marci (identità verificata, chat 1:1)"))
        assertTrue(!armedRow.triggerSummary.contains(hash))

        val draft = AutomationDraft("Rispondi", trigger, listOf(Action.WhatsAppReply("ok")))
        val unsigned = PendingDraft(
            id = DraftId("draft-w"),
            automationId = AutomationId("automation-w"),
            revision = 1L,
            fingerprint = ApprovalFingerprint("0".repeat(64)),
            draft = draft,
            createdBy = CreatedBy.LLM,
            priority = 0,
            schemaVersion = SCHEMA_VERSION,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
        )
        val pendingRow = unsigned
            .copy(fingerprint = ApprovalFingerprints.of(unsigned.pendingAutomation()))
            .toAutomationRow(review = null, conversationLabels = labels)
        assertTrue(pendingRow.triggerSummary.contains("Ottica Marci (identità verificata, chat 1:1)"))
        assertTrue(!pendingRow.triggerSummary.contains(hash))
    }

    @Test
    fun `arm-time failures render an italian label and a FAILED outcome`() {
        fun record(kind: AuditKind, detail: String) = AuditLogRecord(
            id = 1L,
            automationId = "automation-x",
            automationName = "Sveglia",
            kind = kind,
            atMillis = 1_000L,
            detail = detail,
            executionId = null,
            executionStatus = null,
            succeededCount = null,
            failedCount = null,
            submittedCount = null,
        ).toLogRow(emptyList())

        val validation = record(AuditKind.VALIDATION_REJECTED, "tz_invalid,cron_invalid")
        assertEquals(dev.argus.ui.model.LogOutcome.FAILED, validation.outcome)
        assertTrue(validation.summary.contains("Validazione rifiutata"))
        assertTrue(validation.summary.contains("tz invalid"))
        assertTrue(validation.summary.contains("cron invalid"))

        val arm = record(AuditKind.ARM_FAILED, "registrar_failed")
        assertEquals(dev.argus.ui.model.LogOutcome.FAILED, arm.outcome)
        assertTrue(arm.summary.contains("Attivazione fallita"))
        assertTrue(arm.summary.contains("registrar failed"))

        val scheduling = record(AuditKind.SCHEDULING_FAILED, "expired")
        assertEquals(dev.argus.ui.model.LogOutcome.FAILED, scheduling.outcome)
        assertTrue(scheduling.summary.contains("Pianificazione non riuscita"))
        assertTrue(scheduling.summary.contains("expired"))

        val enable = record(AuditKind.ENABLE_FAILED, "review_required")
        assertEquals(dev.argus.ui.model.LogOutcome.FAILED, enable.outcome)
        assertTrue(enable.summary.contains("Abilitazione non riuscita"))
        assertTrue(enable.summary.contains("review required"))
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
