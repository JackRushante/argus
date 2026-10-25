package dev.argus.automation.vm

import dev.argus.automation.ApprovalFlowReview
import dev.argus.data.dao.AuditLogRecord
import dev.argus.data.entities.ActionResultEntity
import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTypeIds
import dev.argus.engine.safety.ConflictWarning
import dev.argus.engine.safety.Severity
import dev.argus.engine.safety.ValidationIssue
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.AUTOMATION_SCHEMA_VERSION_V1
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftReview
import dev.argus.engine.safety.PendingDraft
import dev.argus.ui.presentation.RenderLanguage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UiStateMappersTest {
    @Test
    fun `english automation row removes the english when prefix and localizes relative time`() {
        val row = Automation(
            AutomationId("english-row"),
            "SMS rule",
            CreatedBy.USER,
            AutomationStatus.ARMED,
            Trigger.PhoneState(dev.argus.engine.model.PhoneEvent.SMS_RECEIVED),
            listOf(Action.ShowNotification("Argus", "received")),
        ).toAutomationRow(
            lastFiredAt = 60_000L,
            nowMillis = 180_000L,
            language = RenderLanguage.EN,
        )

        assertEquals("SMS received from anyone", row.triggerSummary)
        assertEquals("2 min ago", row.lastFiredLabel)
    }

    @Test
    fun `english audit row localizes lifecycle summary deleted name and action outcome`() {
        val row = AuditLogRecord(
            id = 44L,
            automationId = "deleted",
            automationName = null,
            kind = AuditKind.RULE_DISABLED,
            atMillis = 1_000L,
            detail = "one_shot_consumed",
            executionId = "execution-en",
            executionStatus = null,
            succeededCount = null,
            failedCount = null,
            submittedCount = null,
        ).toLogRow(
            actions = listOf(
                ActionResultEntity(
                    executionId = "execution-en",
                    actionIndex = 0,
                    actionType = "invoke_llm_v2",
                    outcome = ActionJournalOutcome.FAILED,
                    atMillis = 1_000L,
                    errorCode = "backend_failed",
                ),
            ),
            language = RenderLanguage.EN,
        )

        assertEquals("Deleted automation", row.automationName)
        assertEquals("Rule disabled: one-shot consumed", row.summary)
        assertEquals(
            "1. AI generation → failed · backend failed",
            assertNotNull(row.expandedDetail).single(),
        )
        assertTrue(row.isGenerative)
    }

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
            actions = listOf(
                ActionResultEntity(
                    executionId = "execution-9",
                    actionIndex = 0,
                    actionType = "invoke_llm",
                    outcome = ActionJournalOutcome.SUBMITTED,
                    atMillis = 1_000L,
                ),
            ),
            language = RenderLanguage.IT,
        )

        assertEquals("42", row.id)
        assertEquals("automation-7", row.automationId)
        assertEquals(dev.argus.ui.model.LogOutcome.SUBMITTED, row.outcome)
        assertTrue(row.isGenerative)
        assertTrue(assertNotNull(row.expandedDetail).single().contains("Generazione AI"))
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
            actions = listOf(
                ActionResultEntity(
                    executionId = "execution-10",
                    actionIndex = 0,
                    actionType = "invoke_llm",
                    outcome = ActionJournalOutcome.DEFERRED,
                    atMillis = 1_100L,
                    errorCode = "reply_channel_expired",
                ),
            ),
            language = RenderLanguage.IT,
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
        ).toLogRow(emptyList(), RenderLanguage.IT)

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
        ).toLogRow(emptyList(), RenderLanguage.IT)
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
        ).toLogRow(emptyList(), RenderLanguage.IT)

        assertEquals("stato necessario non disponibile", unavailable.summary)
        assertEquals("condizioni non soddisfatte", falseCondition.summary)
    }

    @Test
    fun `unpriced budget suppression explains why the model was blocked`() {
        val row = AuditLogRecord(
            id = 12L,
            automationId = "ai-rule",
            automationName = "AI rule",
            kind = AuditKind.SUPPRESSED_BUDGET,
            atMillis = 1_000L,
            detail = "unpriced_model:openai",
            executionId = "execution-12",
            executionStatus = ExecutionStatus.SUPPRESSED_BUDGET,
            succeededCount = 0,
            failedCount = 0,
            submittedCount = 0,
        ).toLogRow(emptyList(), RenderLanguage.EN)

        assertEquals("blocked: the selected model has no catalog price", row.summary)
    }

    @Test
    fun `list rows resolve the trusted whitelist name instead of the conversation hash`() {
        val hash = "shortcut:com.whatsapp:feedbeef00aabbcc"
        val labels = mapOf(hash to "Ottica Marci")
        val trigger = Trigger.Notification("com.whatsapp", conversationId = hash, isGroup = false)

        val armedRow = Automation(
            AutomationId("a1"), "Rispondi a Ottica", CreatedBy.LLM, AutomationStatus.ARMED,
            trigger, listOf(Action.WhatsAppReply("ok")),
        ).toAutomationRow(
            lastFiredAt = null,
            nowMillis = 0L,
            conversationLabels = labels,
            language = RenderLanguage.IT,
        )
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
            schemaVersion = AUTOMATION_SCHEMA_VERSION_V1,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
        )
        val pendingRow = unsigned
            .copy(fingerprint = ApprovalFingerprints.of(unsigned.pendingAutomation()))
            .toAutomationRow(
                review = null,
                conversationLabels = labels,
                language = RenderLanguage.IT,
            )
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
        ).toLogRow(emptyList(), RenderLanguage.IT)

        val validation = record(AuditKind.VALIDATION_REJECTED, "tz_invalid,cron_invalid")
        assertEquals(dev.argus.ui.model.LogOutcome.FAILED, validation.outcome)
        assertTrue(validation.summary.contains("Validazione rifiutata"))
        // B9: ogni codice diventa il suo testo leggibile, non piu' «tz invalid».
        assertTrue(validation.summary.contains("fuso orario"), validation.summary)
        assertTrue(validation.summary.contains("pianificazione"), validation.summary)

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
    fun `lifecycle events render italian labels and coherent outcomes`() {
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
        ).toLogRow(emptyList(), RenderLanguage.IT)

        val armed = record(AuditKind.RULE_ARMED, "approval")
        assertEquals(dev.argus.ui.model.LogOutcome.SUCCESS, armed.outcome)
        assertEquals("Regola armata", armed.summary)

        val disabledByUser = record(AuditKind.RULE_DISABLED, "user")
        assertEquals(dev.argus.ui.model.LogOutcome.SUCCESS, disabledByUser.outcome)
        assertEquals("Regola disabilitata dall'utente", disabledByUser.summary)

        val oneShot = record(AuditKind.RULE_DISABLED, "one_shot_consumed")
        assertEquals("Regola disabilitata: one-shot consumata", oneShot.summary)

        val expired = record(AuditKind.RULE_DISABLED, "expired")
        assertEquals("Regola disabilitata: scadenza passata", expired.summary)

        val unknownDisable = record(AuditKind.RULE_DISABLED, "rule_disabled")
        assertEquals("Regola disabilitata", unknownDisable.summary)

        val enabled = record(AuditKind.RULE_ENABLED, "user")
        assertEquals(dev.argus.ui.model.LogOutcome.SUCCESS, enabled.outcome)
        assertEquals("Regola riabilitata", enabled.summary)

        val deleted = record(AuditKind.RULE_DELETED, "user")
        assertEquals(dev.argus.ui.model.LogOutcome.SUCCESS, deleted.outcome)
        assertEquals("Regola eliminata", deleted.summary)

        val review = record(AuditKind.RULE_NEEDS_REVIEW, "capability_lost")
        assertEquals(dev.argus.ui.model.LogOutcome.FAILED, review.outcome)
        assertTrue(review.summary.contains("Regola da rivedere"))
        assertTrue(review.summary.contains("capability lost"))
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
            schemaVersion = AUTOMATION_SCHEMA_VERSION_V1,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
        )
        val snapshot = unsigned.copy(
            fingerprint = ApprovalFingerprints.of(unsigned.pendingAutomation()),
        )

        val warnings = reviewWarnings(
            ApprovalFlowReview(DraftReview(snapshot, emptyList()), emptyList()),
            RenderLanguage.IT,
        )

        assertTrue(warnings.any { it.code == "privacy_generative" })
    }

    // --- B9: motivi leggibili nella lingua dell'utente --------------------------------------------

    private fun reviewOf(vararg issues: ValidationIssue, conflicts: List<ConflictWarning> = emptyList()): ApprovalFlowReview {
        val draft = AutomationDraft(
            name = "Regola",
            trigger = Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetWifi(false)),
        )
        val unsigned = PendingDraft(
            id = DraftId("draft-b9"),
            automationId = AutomationId("automation-b9"),
            revision = 1L,
            fingerprint = ApprovalFingerprint("0".repeat(64)),
            draft = draft,
            createdBy = CreatedBy.LLM,
            priority = 0,
            schemaVersion = AUTOMATION_SCHEMA_VERSION_V1,
            createdAtMillis = 1_000L,
            updatedAtMillis = 1_000L,
        )
        val snapshot = unsigned.copy(fingerprint = ApprovalFingerprints.of(unsigned.pendingAutomation()))
        return ApprovalFlowReview(DraftReview(snapshot, issues.toList()), conflicts)
    }

    @Test
    fun `review warnings use the localized text for every code and never the italian diagnostic in english`() {
        val issues = arrayOf(
            ValidationIssue(Severity.ERROR, "tz_invalid", "Timezone 'x' non valida"),
            ValidationIssue(Severity.WARNING, "write_setting_high_impact", "Messaggio diagnostico italiano"),
            ValidationIssue(Severity.ERROR, "codice_futuro", "Qualcosa di non previsto"),
        )
        val review = reviewOf(*issues)

        val en = reviewWarnings(review, RenderLanguage.EN).associateBy { it.code }
        assertEquals("The time zone is not valid.", en.getValue("tz_invalid").text)
        assertEquals(Severity.ERROR, en.getValue("tz_invalid").severity)
        assertTrue(en.getValue("write_setting_high_impact").text.startsWith("This rule changes a phone security setting"))
        assertEquals(
            "Unrecognized validation problem (codice_futuro).",
            en.getValue("codice_futuro").text,
        )
        assertTrue(en.values.none { it.text.contains("non valida") || it.text.contains("Messaggio diagnostico") })

        val italian = reviewWarnings(review, RenderLanguage.IT).associateBy { it.code }
        assertEquals("Il fuso orario non è valido.", italian.getValue("tz_invalid").text)
        assertTrue(italian.getValue("write_setting_high_impact").text.contains("impostazione di sicurezza del telefono"))
        assertEquals(
            "Problema di validazione non riconosciuto (codice_futuro).",
            italian.getValue("codice_futuro").text,
        )
    }

    @Test
    fun `review warnings keep the structured detail of capability and reader problems`() {
        val review = reviewOf(
            ValidationIssue(
                Severity.ERROR,
                "capability_unavailable",
                "Capacità non disponibile in questa fase: action.run_shell",
                detail = "action.run_shell",
            ),
        )
        assertTrue(
            reviewWarnings(review, RenderLanguage.EN).single { it.code == "capability_unavailable" }
                .text.endsWith("(action.run_shell)"),
        )
    }

    @Test
    fun `conflicts are localized by the setting they touch`() {
        val review = reviewOf(
            conflicts = listOf(
                ConflictWarning("wifi", listOf(AutomationId("a"), AutomationId("b")), "'A' e 'B' impostano 'wifi' a valori opposti"),
                ConflictWarning("unknown target!", listOf(AutomationId("a")), "x"),
            ),
        )
        val en = reviewWarnings(review, RenderLanguage.EN).associateBy { it.code }
        assertEquals(
            "Another active rule sets Wi-Fi to the opposite value on triggers that may overlap.",
            en.getValue("conflict_wifi").text,
        )
        assertTrue(en.getValue("conflict_unknown_target").text.contains("(conflict_unknown_target)"))
        assertTrue(reviewWarnings(review, RenderLanguage.IT).single { it.code == "conflict_wifi" }.text.startsWith("Un'altra regola attiva"))
    }

    @Test
    fun `every action type id has a human label in the audit detail`() {
        val ids = ActionTypeIds::class.java.declaredFields
            .filter { java.lang.reflect.Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .mapNotNull { runCatching { it.get(null) as? String }.getOrNull() }
        assertTrue(ids.size >= 25, "ActionTypeIds letti: ${ids.size}")
        for (language in listOf(RenderLanguage.EN, RenderLanguage.IT)) {
            for (id in ids) {
                val row = AuditLogRecord(
                    id = 1L,
                    automationId = "a",
                    automationName = "n",
                    kind = AuditKind.FIRED,
                    atMillis = 1_000L,
                    detail = "",
                    executionId = "e",
                    executionStatus = ExecutionStatus.SUCCEEDED,
                    succeededCount = 1,
                    failedCount = 0,
                    submittedCount = 0,
                ).toLogRow(
                    listOf(
                        ActionResultEntity(
                            executionId = "e",
                            actionIndex = 0,
                            actionType = id,
                            outcome = ActionJournalOutcome.SUCCEEDED,
                            atMillis = 1_000L,
                        ),
                    ),
                    language,
                )
                val detail = assertNotNull(row.expandedDetail).single()
                // Il fallback grezzo («set mobile data») significa che manca l'etichetta del tipo.
                assertTrue(
                    !detail.contains(id.replace('_', ' ')),
                    "tipo azione senza etichetta ($language): $id -> $detail",
                )
            }
        }
    }

    @Test
    fun `mobile data and copy text have their own audit labels`() {
        fun detail(type: String, language: RenderLanguage) = assertNotNull(
            AuditLogRecord(
                id = 1L, automationId = "a", automationName = "n", kind = AuditKind.FIRED, atMillis = 1_000L,
                detail = "", executionId = "e", executionStatus = ExecutionStatus.SUCCEEDED,
                succeededCount = 1, failedCount = 0, submittedCount = 0,
            ).toLogRow(
                listOf(
                    ActionResultEntity(
                        executionId = "e", actionIndex = 0, actionType = type,
                        outcome = ActionJournalOutcome.SUCCEEDED, atMillis = 1_000L,
                    ),
                ),
                language,
            ).expandedDetail,
        ).single()

        assertEquals("1. Set mobile data → ok", detail(ActionTypeIds.SET_MOBILE_DATA, RenderLanguage.EN))
        assertEquals("1. Copy text to clipboard → ok", detail(ActionTypeIds.COPY_TEXT, RenderLanguage.EN))
        assertEquals("1. Imposta dati mobili → ok", detail(ActionTypeIds.SET_MOBILE_DATA, RenderLanguage.IT))
        assertEquals("1. Copia testo negli appunti → ok", detail(ActionTypeIds.COPY_TEXT, RenderLanguage.IT))
    }

    @Test
    fun `english audit rows for rejected validation read as sentences`() {
        val row = AuditLogRecord(
            id = 1L, automationId = "a", automationName = "n", kind = AuditKind.VALIDATION_REJECTED,
            atMillis = 1_000L, detail = "tz_invalid,no_actions", executionId = null, executionStatus = null,
            succeededCount = null, failedCount = null, submittedCount = null,
        ).toLogRow(emptyList(), RenderLanguage.EN)
        assertEquals(
            "Validation rejected: The time zone is not valid. The rule has no actions.",
            row.summary,
        )
    }

    // --- D5: la riga della regola porta l'id della modifica in attesa -----------------------------------

    @Test
    fun `an automation row carries the pending edit draft id`() {
        val automation = Automation(
            AutomationId("d5"), "Regola", CreatedBy.USER, AutomationStatus.ARMED,
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetWifi(false)),
        )
        val plain = automation.toAutomationRow(null, 0L, language = RenderLanguage.EN)
        assertNull(plain.pendingEditDraftId)
        val withEdit = automation.toAutomationRow(null, 0L, language = RenderLanguage.EN, pendingEditDraftId = "draft-1")
        assertEquals("draft-1", withEdit.pendingEditDraftId)
        assertEquals(true, withEdit.enabled)
    }
}
