package dev.argus.automation.vm

import dev.argus.automation.ApprovalFlowReview
import dev.argus.data.dao.AuditLogRecord
import dev.argus.data.entities.ActionResultEntity
import dev.argus.data.entities.ScheduledTimeAlarmEntity
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
import dev.argus.engine.runtime.OmittedSteps
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
            "1. AI generation → failed · backend_failed",
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
        assertTrue(arm.summary.contains("la regola non si è potuta registrare"), arm.summary)

        val scheduling = record(AuditKind.SCHEDULING_FAILED, "expired")
        assertEquals(dev.argus.ui.model.LogOutcome.FAILED, scheduling.outcome)
        assertTrue(scheduling.summary.contains("Pianificazione non riuscita"))
        assertTrue(scheduling.summary.contains("l'orario è già passato"), scheduling.summary)

        val enable = record(AuditKind.ENABLE_FAILED, "review_required")
        assertEquals(dev.argus.ui.model.LogOutcome.FAILED, enable.outcome)
        assertTrue(enable.summary.contains("Abilitazione non riuscita"))
        assertTrue(enable.summary.contains("la regola va prima rivista"), enable.summary)
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

    // --- B27: riga di riepilogo dei cicli lunghi ---------------------------------------------

    private fun omittedRow(count: Int, language: RenderLanguage) = AuditLogRecord(
        id = 1L,
        automationId = "loop",
        automationName = "Loop",
        kind = AuditKind.FIRED,
        atMillis = 1_000L,
        detail = "",
        executionId = "e-loop",
        executionStatus = ExecutionStatus.SUCCEEDED,
        succeededCount = 3_000,
        failedCount = 0,
        submittedCount = 0,
    ).toLogRow(
        listOf(
            ActionResultEntity(
                executionId = "e-loop",
                actionIndex = 33,
                actionType = OmittedSteps.ACTION_TYPE,
                outcome = ActionJournalOutcome.SUCCEEDED,
                atMillis = 1_000L,
                errorCode = OmittedSteps.code(count),
                actionPath = "1.while[34].2",
            ),
        ),
        language,
    )

    @Test
    fun `omitted loop steps summary row reads as repeated steps not recorded`() {
        assertEquals(
            "2900 repeated steps not recorded",
            assertNotNull(omittedRow(2_900, RenderLanguage.EN).expandedDetail).single(),
        )
        assertEquals(
            "2900 passaggi ripetuti non registrati",
            assertNotNull(omittedRow(2_900, RenderLanguage.IT).expandedDetail).single(),
        )
        assertEquals(
            "1 repeated step not recorded",
            assertNotNull(omittedRow(1, RenderLanguage.EN).expandedDetail).single(),
        )
        assertEquals(
            "1 passaggio ripetuto non registrato",
            assertNotNull(omittedRow(1, RenderLanguage.IT).expandedDetail).single(),
        )
    }

    // --- B14: il Log mostra il vero motivo ---------------------------------------------------

    private fun failureRow(kind: AuditKind, detail: String, language: RenderLanguage) = AuditLogRecord(
        id = 1L,
        automationId = "a",
        automationName = "Regola",
        kind = kind,
        atMillis = 1_000L,
        detail = detail,
        executionId = null,
        executionStatus = null,
        succeededCount = null,
        failedCount = null,
        submittedCount = null,
    ).toLogRow(emptyList(), language)

    @Test
    fun `new reason codes have an english and an italian label on every failure kind`() {
        val expected = mapOf(
            "foreground_service_denied" to ("Android did not allow the foreground service" to
                "Android non ha permesso il servizio in primo piano"),
            "location_disabled" to ("location is turned off" to "la localizzazione è spenta"),
            "shizuku_unavailable" to ("Shizuku is not running" to "Shizuku non è attivo"),
            "shizuku_timeout" to ("Shizuku did not respond in time" to "Shizuku non ha risposto in tempo"),
            "shizuku_unsupported" to ("Shizuku version is too old" to "la versione di Shizuku è troppo vecchia"),
            "dnd_change_not_applied" to ("Android did not apply the Do Not Disturb change" to
                "Android non ha applicato il cambio di Non disturbare"),
            "one_shot_in_past" to ("the one-time rule's time has already passed" to
                "l'orario della regola una tantum è già passato"),
            "execution_interrupted" to ("execution interrupted, the app was closed" to
                "esecuzione interrotta, l'app è stata chiusa"),
        )
        val kinds = listOf(
            AuditKind.ERROR,
            AuditKind.BLOCKED_POLICY,
            AuditKind.ARM_FAILED,
            AuditKind.SCHEDULING_FAILED,
            AuditKind.ENABLE_FAILED,
        )
        for ((code, labels) in expected) {
            for (kind in kinds) {
                val en = failureRow(kind, code, RenderLanguage.EN)
                val ita = failureRow(kind, code, RenderLanguage.IT)
                assertTrue(en.summary.contains(labels.first), "EN $kind $code: ${en.summary}")
                assertTrue(ita.summary.contains(labels.second), "IT $kind $code: ${ita.summary}")
                assertEquals(dev.argus.ui.model.LogOutcome.FAILED, en.outcome)
            }
        }
    }

    @Test
    fun `an unknown reason code is shown as the code itself`() {
        assertEquals(
            "execution error (flux_capacitor_overheated)",
            failureRow(AuditKind.ERROR, "flux_capacitor_overheated", RenderLanguage.EN).summary,
        )
        assertEquals(
            "bloccata dalla policy (flux_capacitor_overheated)",
            failureRow(AuditKind.BLOCKED_POLICY, "flux_capacitor_overheated", RenderLanguage.IT).summary,
        )
        assertTrue(
            failureRow(AuditKind.SCHEDULING_FAILED, "flux_capacitor_overheated", RenderLanguage.EN)
                .summary.endsWith("flux_capacitor_overheated"),
        )
    }

    @Test
    fun `every known reason code has both languages and a label different from the code`() {
        assertTrue(LogReasonLabels.knownCodes.size >= 40, "codici noti: ${LogReasonLabels.knownCodes.size}")
        for (code in LogReasonLabels.knownCodes) {
            val en = assertNotNull(LogReasonLabels.label(code, RenderLanguage.EN), code)
            val it = assertNotNull(LogReasonLabels.label(code, RenderLanguage.IT), code)
            assertTrue(en.isNotBlank() && it.isNotBlank(), code)
            assertTrue(en != code && it != code, code)
        }
    }

    @Test
    fun `action error codes use the same labels and fall back to the raw code`() {
        fun detail(code: String, language: RenderLanguage) = assertNotNull(
            AuditLogRecord(
                id = 1L, automationId = "a", automationName = "n", kind = AuditKind.FIRED, atMillis = 1_000L,
                detail = "", executionId = "e", executionStatus = ExecutionStatus.FAILED,
                succeededCount = 0, failedCount = 1, submittedCount = 0,
            ).toLogRow(
                listOf(
                    ActionResultEntity(
                        executionId = "e", actionIndex = 0, actionType = ActionTypeIds.SET_DND,
                        outcome = ActionJournalOutcome.FAILED, atMillis = 1_000L, errorCode = code,
                    ),
                ),
                language,
            ).expandedDetail,
        ).single()

        assertEquals(
            "1. Set Do Not Disturb → failed · Android did not apply the Do Not Disturb change",
            detail("dnd_change_not_applied", RenderLanguage.EN),
        )
        assertEquals(
            "1. Imposta Non disturbare → fallita · Shizuku non è attivo",
            detail("shizuku_unavailable", RenderLanguage.IT),
        )
        assertEquals("1. Set Do Not Disturb → failed · mystery_code", detail("mystery_code", RenderLanguage.EN))
    }

    // --- B32: budget come avviso, prossimo scatto vero ---------------------------------------

    @Test
    fun `an ai reply blocked by the budget is a warning and not a green success`() {
        val row = AuditLogRecord(
            id = 5L,
            automationId = "ai-rule",
            automationName = "AI rule",
            kind = AuditKind.FIRED,
            atMillis = 1_000L,
            detail = "",
            executionId = "execution-5",
            executionStatus = ExecutionStatus.SUPPRESSED_BUDGET,
            succeededCount = 0,
            failedCount = 1,
            submittedCount = 0,
        ).toLogRow(emptyList(), RenderLanguage.IT)

        assertEquals(dev.argus.ui.model.LogOutcome.PARTIAL, row.outcome)
        assertEquals("bloccata dal budget LLM", row.summary)
    }

    private fun relativeRule(): Automation {
        val unsigned = Automation(
            AutomationId("relative"),
            "Tra tre ore",
            CreatedBy.USER,
            AutomationStatus.ARMED,
            Trigger.Time(afterMs = 10 * 3_600_000L, tz = "UTC"),
            listOf(Action.ShowNotification("Argus", "ciao")),
            enabled = true,
        )
        return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
    }

    private fun alarmFor(rule: Automation, eventAtMillis: Long) = ScheduledTimeAlarmEntity(
        automationId = rule.id.value,
        approvalFingerprint = rule.approvalFingerprint!!.value,
        eventAtMillis = eventAtMillis,
        wakeAtMillis = eventAtMillis,
        requestedPrecision = "EXACT",
        scheduledMode = "EXACT",
        updatedAtMillis = 0L,
    )

    private inline fun <T> inUtc(block: () -> T): T {
        val previous = java.util.TimeZone.getDefault()
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("UTC"))
        try {
            return block()
        } finally {
            java.util.TimeZone.setDefault(previous)
        }
    }

    @Test
    fun `next fire of an after-N-hours rule comes from the persisted alarm and not from now plus N`() = inUtc {
        val rule = relativeRule()
        val now = java.time.Instant.parse("2026-07-20T10:00:00Z").toEpochMilli()
        // Armata alle 08:00 con ritardo di 4 ore reali: scatta alle 12:30 (ancora congelata all'arm).
        val scheduled = java.time.Instant.parse("2026-07-20T12:30:00Z").toEpochMilli()

        val row = rule.toAutomationRow(
            lastFiredAt = null,
            nowMillis = now,
            language = RenderLanguage.IT,
            scheduledAlarm = alarmFor(rule, scheduled),
        )
        assertEquals("oggi 12:30", row.nextFireLabel)
        assertEquals(
            "today 12:30",
            rule.toAutomationRow(null, now, language = RenderLanguage.EN, scheduledAlarm = alarmFor(rule, scheduled))
                .nextFireLabel,
        )
    }

    @Test
    fun `next fire of an after-N-hours rule is absent without a trustworthy persisted alarm`() = inUtc {
        val rule = relativeRule()
        val now = java.time.Instant.parse("2026-07-20T10:00:00Z").toEpochMilli()
        val future = now + 3_600_000L

        // Nessuno stato persistito: niente etichetta, mai now + N.
        assertNull(rule.toAutomationRow(null, now, language = RenderLanguage.IT).nextFireLabel)
        // Sveglia di un'altra revisione della regola: non si usa.
        val stale = alarmFor(rule, future).copy(approvalFingerprint = "f".repeat(64))
        assertNull(rule.toAutomationRow(null, now, language = RenderLanguage.IT, scheduledAlarm = stale).nextFireLabel)
        // Istante gia' passato: lo scatto e' dovuto o avvenuto.
        assertNull(
            rule.toAutomationRow(null, now, language = RenderLanguage.IT, scheduledAlarm = alarmFor(rule, now - 1))
                .nextFireLabel,
        )
        // Regola disabilitata: nessun prossimo scatto.
        val disabled = rule.copy(status = AutomationStatus.DISABLED, enabled = false)
        assertNull(
            disabled.toAutomationRow(null, now, language = RenderLanguage.IT, scheduledAlarm = alarmFor(rule, future))
                .nextFireLabel,
        )
    }

    @Test
    fun `next fire of a one-shot rule is its absolute instant and cron keeps working`() = inUtc {
        val now = java.time.Instant.parse("2026-07-20T10:00:00Z").toEpochMilli()
        fun rule(trigger: Trigger.Time): Automation {
            val unsigned = Automation(
                AutomationId("t"), "t", CreatedBy.USER, AutomationStatus.ARMED, trigger,
                listOf(Action.ShowNotification("Argus", "ciao")), enabled = true,
            )
            return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
        }
        assertEquals(
            "domani 08:00",
            rule(Trigger.Time(at = "2026-07-21T08:00", tz = "UTC"))
                .toAutomationRow(null, now, language = RenderLanguage.IT).nextFireLabel,
        )
        assertNull(
            rule(Trigger.Time(at = "2026-07-19T08:00", tz = "UTC"))
                .toAutomationRow(null, now, language = RenderLanguage.IT).nextFireLabel,
        )
        assertEquals(
            "oggi 23:00",
            rule(Trigger.Time(cron = "0 23 * * *", tz = "UTC"))
                .toAutomationRow(null, now, language = RenderLanguage.IT).nextFireLabel,
        )
    }
}
