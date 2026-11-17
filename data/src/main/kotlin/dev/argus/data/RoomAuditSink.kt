package dev.argus.data

import dev.argus.data.dao.AuditDao
import dev.argus.data.entities.AuditEntity
import dev.argus.engine.directboot.BeforeUnlockCodes
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.ConfirmationCodes
import dev.argus.engine.runtime.ProgramInterpreter
import dev.argus.engine.runtime.SpanCodes

/** [AuditSink] su Room (spec §10.6). Insert append-only via DAO suspend. */
class RoomAuditSink(private val dao: AuditDao) : AuditSink {
    override suspend fun record(e: AuditEvent) {
        dao.insert(
            AuditEntity(
                automationId = e.automationId.value,
                kind = e.kind,
                atMillis = e.atMillis,
                detail = redactedDetail(e),
                eventIdHash = e.eventId?.value?.let(::identifierHash),
                executionId = e.executionId?.value,
            )
        )
    }

    private fun redactedDetail(event: AuditEvent): String = when (event.kind) {
        // 0.8: anche la pausa delle sole azioni di fine di una regola-intervallo (i valori sono già rimessi).
        dev.argus.engine.runtime.AuditKind.SUPPRESSED_COOLDOWN ->
            event.detail.takeIf { RETRY_AT.matches(it) || it == SpanCodes.EXIT_ACTIONS_COOLDOWN } ?: ""
        dev.argus.engine.runtime.AuditKind.SUPPRESSED_BUDGET ->
            event.detail.takeIf { BUDGET_DETAIL.matches(it) || UNPRICED_MODEL_DETAIL.matches(it) } ?: ""
        // Il Log deve mostrare il vero motivo: un reason code snake_case di forma chiusa passa
        // sempre (i produttori emettono solo costanti), tutto il resto cade nel codice generico.
        dev.argus.engine.runtime.AuditKind.BLOCKED_POLICY ->
            event.detail.takeIf(::isReasonCode) ?: "policy_blocked"
        dev.argus.engine.runtime.AuditKind.ERROR ->
            event.detail.takeIf(::isReasonCode) ?: "execution_error"
        // Uno stato illeggibile non deve sembrare «condizioni non soddisfatte».
        dev.argus.engine.runtime.AuditKind.CONDITIONS_NOT_MET ->
            event.detail.takeIf { it == CONDITION_STATE_UNAVAILABLE } ?: ""
        // Validazione rifiutata: lista (bounded) di `code` di DraftValidator.ValidationIssue.
        // Sono costanti snake_case: accettiamo solo token di FORMA chiusa e scartiamo il resto.
        dev.argus.engine.runtime.AuditKind.VALIDATION_REJECTED ->
            event.detail.split(',')
                .filter { REASON_CODE.matches(it) }
                .take(MAX_VALIDATION_CODES)
                .joinToString(",")
        dev.argus.engine.runtime.AuditKind.ARM_FAILED ->
            event.detail.takeIf(::isReasonCode) ?: "arm_failed"
        dev.argus.engine.runtime.AuditKind.SCHEDULING_FAILED ->
            event.detail.takeIf(::isReasonCode) ?: "scheduling_failed"
        dev.argus.engine.runtime.AuditKind.ENABLE_FAILED ->
            event.detail.takeIf(::isReasonCode) ?: "enable_failed"
        // Lifecycle riuscito (task #31-B): solo reason-code a vocabolario chiuso, mai testo libero.
        dev.argus.engine.runtime.AuditKind.RULE_ARMED ->
            event.detail.takeIf { it in RULE_ARMED_REASONS } ?: "rule_armed"
        dev.argus.engine.runtime.AuditKind.RULE_DISABLED ->
            event.detail.takeIf { it in RULE_DISABLED_REASONS } ?: "rule_disabled"
        dev.argus.engine.runtime.AuditKind.RULE_ENABLED ->
            event.detail.takeIf { it in RULE_ENABLED_REASONS } ?: "rule_enabled"
        dev.argus.engine.runtime.AuditKind.RULE_DELETED ->
            event.detail.takeIf { it in RULE_DELETED_REASONS } ?: "rule_deleted"
        dev.argus.engine.runtime.AuditKind.RULE_NEEDS_REVIEW ->
            event.detail.takeIf { it in RULE_NEEDS_REVIEW_REASONS } ?: "rule_needs_review"
        // 0.8: solo l'esito del filtro chiamate, mai il numero.
        dev.argus.engine.runtime.AuditKind.CALL_SCREENED ->
            event.detail.takeIf { it in CALL_SCREENED_DETAILS } ?: ""
        // 0.8: why the option «active before unlock» switched itself off, a closed code.
        dev.argus.engine.runtime.AuditKind.BEFORE_UNLOCK_OFF ->
            event.detail.takeIf { it in BeforeUnlockCodes.OFF_DETAILS } ?: ""
        // 0.8: la fine di una regola-intervallo e uno scatto avvenuto prima dello sblocco si distinguono nel
        // Log: due codici chiusi, gli unici ammessi.
        dev.argus.engine.runtime.AuditKind.FIRED ->
            event.detail.takeIf { it == SpanCodes.EXIT || it == BeforeUnlockCodes.FIRED } ?: ""
        // 0.8: conferme, domande senza risposta e ripristini degli intervalli: solo i codici dell'elenco chiuso.
        dev.argus.engine.runtime.AuditKind.SUPPRESSED_NOT_ELIGIBLE ->
            event.detail.takeIf { it in NOT_ELIGIBLE_DETAILS } ?: ""
        dev.argus.engine.runtime.AuditKind.SUPPRESSED_DUPLICATE -> ""
    }

    private fun isReasonCode(value: String): Boolean = REASON_CODE.matches(value)

    private companion object {
        val RETRY_AT = Regex("^retry_at=[0-9]{1,19}$")
        val BUDGET_DETAIL = Regex("^(hour|day|month_cost|month_tokens):(global|[a-z][a-z0-9_]{0,32})$")
        /** Come lo emette MeteredBrain: `unpriced_model:<ProviderId.wireName>`. */
        val UNPRICED_MODEL_DETAIL = Regex("^unpriced_model:[a-z][a-z0-9_]{0,32}$")
        const val CONDITION_STATE_UNAVAILABLE = "condition_state_unavailable"
        /** Codice snake_case a vocabolario chiuso (ValidationIssue.code o reason interno). */
        val REASON_CODE = Regex("^[a-z][a-z0-9_]{0,63}$")
        val CALL_SCREENED_DETAILS = setOf("screen_call_rejected", "screen_call_silenced", "screen_call_failed_open")
        /** 0.8: i motivi chiusi di uno scatto non eseguito che il Log mostra (conferma, domanda, intervallo). */
        val NOT_ELIGIBLE_DETAILS: Set<String> = ConfirmationCodes.ALL + setOf(
            ProgramInterpreter.ASK_TIMEOUT,
            ProgramInterpreter.ASK_RESUME_STALE,
            SpanCodes.VALUE_UNREADABLE,
            SpanCodes.RESTORED_RULE_CHANGED,
        )
        const val MAX_VALIDATION_CODES = 8
        // --- Lifecycle riuscito (task #31-B): un set chiuso per kind, fallback generico. ---
        /** Unico ingresso in ARMED: l'approvazione utente (submit o edit passano di lì). */
        val RULE_ARMED_REASONS = setOf("approval")
        /** user = azione manuale; one_shot_consumed = auto-disable post fire; expired = time.at passato. */
        val RULE_DISABLED_REASONS = setOf("user", "one_shot_consumed", "expired")
        val RULE_ENABLED_REASONS = setOf("user")
        val RULE_DELETED_REASONS = setOf("user")
        /** fire_policy = blocco fire-time; capability/validation/requirements = CapabilityReconciler;
         *  planner_failed = TimeAlarmPlanner lancia su una revisione non più pianificabile. */
        val RULE_NEEDS_REVIEW_REASONS = setOf(
            "fire_policy",
            "capability_lost",
            "validation_failed",
            "requirements_changed",
            "planner_failed",
            // 0.8: l'utente ha eliminato un segreto della cassaforte che la regola usa.
            "secret_deleted",
        )
    }
}
