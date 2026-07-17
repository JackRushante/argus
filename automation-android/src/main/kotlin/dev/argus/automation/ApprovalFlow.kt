package dev.argus.automation

import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.StateQueryFamily
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.Trigger
import dev.argus.engine.model.stateComparisons
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.FirePolicySnapshotProvider
import dev.argus.engine.runtime.NoopAuditSink
import dev.argus.engine.safety.ApprovalResult
import dev.argus.engine.safety.ApprovalService
import dev.argus.engine.safety.ConflictDetector
import dev.argus.engine.safety.ConflictWarning
import dev.argus.engine.safety.DraftId
import dev.argus.engine.safety.DraftRepository
import dev.argus.engine.safety.DraftReview
import dev.argus.engine.safety.DraftWriteResult
import dev.argus.engine.safety.NewDraft
import dev.argus.engine.safety.PendingDraft
import dev.argus.engine.safety.Severity
import dev.argus.engine.safety.ValidationIssue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.UUID

data class DeviceLocation(
    val latitude: Double,
    val longitude: Double,
    /** Raggio d'incertezza orizzontale dichiarato dal provider; null = qualità ignota. */
    val horizontalAccuracyMeters: Double? = null,
    /** Età monotona del fix quando è stato consegnato; null = freschezza ignota. */
    val ageMillis: Long? = null,
) {
    val valid: Boolean = latitude.isFinite() && longitude.isFinite() &&
        latitude in -90.0..90.0 && longitude in -180.0..180.0 &&
        !(latitude == 0.0 && longitude == 0.0)

    /** Solo un fix fresco con incertezza finita può smentire un bordo annunciato dall'OS. */
    val usableAsContradictoryEvidence: Boolean = valid &&
        horizontalAccuracyMeters?.let { it.isFinite() && it >= 0.0 } == true &&
        ageMillis?.let { it in 0..MAX_CONTRADICTION_FIX_AGE_MILLIS } == true

    companion object {
        const val MAX_CONTRADICTION_FIX_AGE_MILLIS = 30_000L
    }
}

fun interface CurrentLocationProvider {
    suspend fun current(): DeviceLocation?
}

/** Deve essere idempotente: può essere richiamato dopo process death o retry. */
fun interface ArmedAutomationRegistrar {
    suspend fun register(automation: Automation): Boolean
}

data class ApprovalFlowReview(
    val draft: DraftReview,
    val conflicts: List<ConflictWarning>,
    /** Esito positivo redatto: identifica query e tipo, mai il valore campione letto. */
    val verifiedStateQueries: List<StateQueryProbeEvidence> = emptyList(),
) {
    val canArm: Boolean = draft.canArm
}

data class StateQueryProbeEvidence(
    val queryId: String,
    val family: StateQueryFamily,
    val valueType: StateValueType,
)

sealed interface DraftSubmissionResult {
    data class Ready(val review: ApprovalFlowReview, val reply: String) : DraftSubmissionResult
    data class NoDraft(val reply: String, val code: String) : DraftSubmissionResult
    data class Rejected(val code: String) : DraftSubmissionResult
    data class Conflict(val currentRevision: Long?) : DraftSubmissionResult
}

sealed interface FlowArmResult {
    data class Armed(val automation: Automation) : FlowArmResult
    data object Missing : FlowArmResult
    data class Stale(val currentRevision: Long) : FlowArmResult
    data class ValidationFailed(val issues: List<ValidationIssue>) : FlowArmResult
    data object PolicyUnavailable : FlowArmResult
    data object LocationUnavailable : FlowArmResult
    data object IntegrityFailure : FlowArmResult
    data object AutomationConflict : FlowArmResult
    data class RegistrationFailed(val automation: Automation) : FlowArmResult
}

class ApprovalFlow(
    private val drafts: DraftRepository,
    private val approvals: ApprovalService,
    private val automations: AutomationStore,
    private val capabilities: FirePolicySnapshotProvider,
    private val location: CurrentLocationProvider,
    private val registrar: ArmedAutomationRegistrar,
    private val stateQueries: StateQueryProbe = FailClosedStateQueryProbe,
    private val audit: AuditSink = NoopAuditSink,
    private val conflicts: ConflictDetector = ConflictDetector(),
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val newDraftId: () -> DraftId = { DraftId(UUID.randomUUID().toString()) },
    private val newAutomationId: () -> AutomationId = {
        AutomationId(UUID.randomUUID().toString())
    },
) {
    suspend fun submit(compile: CompileResult): DraftSubmissionResult {
        return persistCompile(
            compile = compile,
            automationId = newAutomationId(),
            priority = 0,
            expectedAutomationFingerprint = null,
        )
    }

    /**
     * Apre una revisione della versione approvata corrente. Il repository mette la regola in
     * PENDING_APPROVAL nella stessa transazione che salva il draft, senza finestra di esecuzione.
     */
    suspend fun submitEdit(
        compile: CompileResult,
        automationId: AutomationId,
        expectedFingerprint: ApprovalFingerprint,
    ): DraftSubmissionResult {
        val current = automations.get(automationId)
            ?: return DraftSubmissionResult.Rejected("automation_missing")
        val fingerprint = current.approvalFingerprint
            ?: return DraftSubmissionResult.Rejected("automation_not_approved")
        if (fingerprint != expectedFingerprint) {
            return DraftSubmissionResult.Rejected("automation_stale")
        }
        if (current.status !in setOf(AutomationStatus.ARMED, AutomationStatus.DISABLED)) {
            return DraftSubmissionResult.Rejected("automation_not_editable")
        }
        return persistCompile(
            compile = compile,
            automationId = current.id,
            priority = current.priority,
            expectedAutomationFingerprint = expectedFingerprint,
        )
    }

    /** Sostituisce esattamente la revisione di bozza mostrata all'utente; un cambio concorrente
     * torna [DraftSubmissionResult.Conflict] e non viene mai approvato implicitamente. */
    suspend fun submitRevision(
        compile: CompileResult,
        id: DraftId,
        expectedRevision: Long,
    ): DraftSubmissionResult {
        val metaError = compile.metaError
        if (metaError != null) return DraftSubmissionResult.NoDraft(compile.reply, metaError)
        val replacement = compile.draft
            ?: return DraftSubmissionResult.NoDraft(compile.reply, "compile_without_draft")
        val current = drafts.get(id)
            ?: return DraftSubmissionResult.Conflict(currentRevision = null)
        if (current.revision != expectedRevision) {
            return DraftSubmissionResult.Conflict(current.revision)
        }
        return when (
            val result = drafts.revise(
                id = id,
                expectedRevision = expectedRevision,
                draft = replacement,
                priority = current.priority,
                atMillis = nowMillis(),
            )
        ) {
            is DraftWriteResult.Saved -> {
                val review = review(result.snapshot.id)
                    ?: return DraftSubmissionResult.Rejected("draft_missing_after_save")
                DraftSubmissionResult.Ready(review, compile.reply)
            }
            is DraftWriteResult.Conflict -> DraftSubmissionResult.Conflict(result.currentRevision)
            is DraftWriteResult.Rejected -> DraftSubmissionResult.Rejected(result.code)
        }
    }

    private suspend fun persistCompile(
        compile: CompileResult,
        automationId: AutomationId,
        priority: Int,
        expectedAutomationFingerprint: ApprovalFingerprint?,
    ): DraftSubmissionResult {
        val metaError = compile.metaError
        if (metaError != null) {
            return DraftSubmissionResult.NoDraft(compile.reply, metaError)
        }
        val draft = compile.draft
            ?: return DraftSubmissionResult.NoDraft(compile.reply, "compile_without_draft")
        return when (
            val result = drafts.create(
                NewDraft(
                    id = newDraftId(),
                    automationId = automationId,
                    draft = draft,
                    createdBy = CreatedBy.LLM,
                    priority = priority,
                    atMillis = nowMillis(),
                    expectedAutomationFingerprint = expectedAutomationFingerprint,
                ),
            )
        ) {
            is DraftWriteResult.Saved -> {
                val review = review(result.snapshot.id)
                    ?: return DraftSubmissionResult.Rejected("draft_missing_after_save")
                DraftSubmissionResult.Ready(review, compile.reply)
            }
            is DraftWriteResult.Conflict -> DraftSubmissionResult.Conflict(result.currentRevision)
            is DraftWriteResult.Rejected -> DraftSubmissionResult.Rejected(result.code)
        }
    }

    suspend fun review(id: DraftId): ApprovalFlowReview? {
        val base = approvals.review(id) ?: return null
        val capabilityIssues = capabilityIssues(base.snapshot)
        val queryReport = if (capabilityIssues.any { it.severity == Severity.ERROR }) {
            StateQueryProbeReport()
        } else {
            stateQueryReport(base.snapshot)
        }
        val candidate = base.snapshot.pendingAutomation()
        val active = automations.all().filter {
            it.status == AutomationStatus.ARMED && it.enabled
        }
        val conflictWarnings = conflicts.detect(active + candidate).filter {
            candidate.id in it.automationIds
        }
        return ApprovalFlowReview(
            draft = DraftReview(
                base.snapshot,
                (base.issues + capabilityIssues + queryReport.issues).distinct(),
            ),
            conflicts = conflictWarnings,
            verifiedStateQueries = queryReport.verified,
        )
    }

    suspend fun arm(
        id: DraftId,
        expectedRevision: Long,
        expectedFingerprint: ApprovalFingerprint,
    ): FlowArmResult {
        var currentReview = review(id) ?: return FlowArmResult.Missing
        if (currentReview.draft.snapshot.revision != expectedRevision) {
            return FlowArmResult.Stale(currentReview.draft.snapshot.revision)
        }
        if (currentReview.draft.snapshot.fingerprint != expectedFingerprint) {
            return FlowArmResult.IntegrityFailure
        }
        currentReview.validationFailure()?.let {
            return rejectValidation(
                currentReview.draft.snapshot.automationId,
                it.issues,
            )
        }

        var snapshot = currentReview.draft.snapshot
        val geofence = snapshot.draft.trigger as? Trigger.Geofence
        if (geofence?.resolveCurrentLocation == true) {
            val resolved = currentLocation() ?: return FlowArmResult.LocationUnavailable
            val revisedDraft = snapshot.draft.copy(
                trigger = geofence.copy(
                    lat = resolved.latitude,
                    lng = resolved.longitude,
                    resolveCurrentLocation = false,
                ),
            )
            when (
                val revised = drafts.revise(
                    id = snapshot.id,
                    expectedRevision = snapshot.revision,
                    draft = revisedDraft,
                    priority = snapshot.priority,
                    atMillis = nowMillis(),
                )
            ) {
                is DraftWriteResult.Saved -> snapshot = revised.snapshot
                is DraftWriteResult.Conflict -> return revised.currentRevision?.let(FlowArmResult::Stale)
                    ?: FlowArmResult.Missing
                is DraftWriteResult.Rejected -> return FlowArmResult.IntegrityFailure
            }
            currentReview = review(id) ?: return FlowArmResult.Missing
            if (currentReview.draft.snapshot.revision != snapshot.revision ||
                currentReview.draft.snapshot.fingerprint != snapshot.fingerprint
            ) return FlowArmResult.IntegrityFailure
            currentReview.validationFailure()?.let {
                return rejectValidation(
                    currentReview.draft.snapshot.automationId,
                    it.issues,
                )
            }
        }

        return when (
            val result = approvals.arm(
                snapshot.id,
                snapshot.revision,
                snapshot.fingerprint,
            )
        ) {
            is ApprovalResult.Armed -> finalizeRegistration(result.automation)
            ApprovalResult.Missing -> FlowArmResult.Missing
            is ApprovalResult.Stale -> FlowArmResult.Stale(result.currentRevision)
            is ApprovalResult.ValidationFailed ->
                rejectValidation(snapshot.automationId, result.issues)
            ApprovalResult.PolicyUnavailable -> FlowArmResult.PolicyUnavailable
            ApprovalResult.IntegrityFailure -> FlowArmResult.IntegrityFailure
            ApprovalResult.AutomationConflict -> FlowArmResult.AutomationConflict
        }
    }

    private suspend fun capabilityIssues(snapshot: PendingDraft): List<ValidationIssue> {
        val policy = try {
            capabilities.current()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return listOf(
                ValidationIssue(
                    Severity.ERROR,
                    "capability_policy_unavailable",
                    "Impossibile verificare le capacità correnti del dispositivo",
                ),
            )
        }
        val required = CapabilityRequirements.derive(
            snapshot.draft.trigger,
            snapshot.draft.actions,
            snapshot.draft.conditions,
        )
        val missing = required - policy.availableCapabilities
        return missing.sorted().map { capability ->
            if (capability in policy.transientlyUnavailableCapabilities) {
                ValidationIssue(
                    Severity.WARNING,
                    "capability_temporarily_unavailable",
                    "Capacità temporaneamente non disponibile: $capability",
                )
            } else {
                ValidationIssue(
                    Severity.ERROR,
                    "capability_unavailable",
                    "Capacità non disponibile in questa fase: $capability",
                )
            }
        }
    }

    private suspend fun stateQueryReport(snapshot: PendingDraft): StateQueryProbeReport {
        val issues = mutableListOf<ValidationIssue>()
        val verified = mutableListOf<StateQueryProbeEvidence>()
        val requests = buildList {
            snapshot.draft.conditions?.stateComparisons().orEmpty().forEach { condition ->
                add(StateQueryProbeRequest(condition.query, condition.valueType))
            }
            snapshot.draft.actions.filterIsInstance<Action.InvokeLlmV2>().forEach { action ->
                action.stateContext.forEach { context ->
                    add(StateQueryProbeRequest(context.query, context.valueType))
                }
            }
        }
        requests.distinctBy { it.query.canonicalId to it.valueType }
            .forEach { request ->
                when (probeStateQuery(request)) {
                    StateQueryProbeResult.AVAILABLE -> verified += StateQueryProbeEvidence(
                        queryId = request.query.canonicalId,
                        family = request.query.family,
                        valueType = request.valueType,
                    )
                    StateQueryProbeResult.UNAVAILABLE -> issues += ValidationIssue(
                            Severity.ERROR,
                            "state_query_unavailable",
                            "Reader non disponibile sul dispositivo: ${request.query.family.wireName}",
                        )
                    StateQueryProbeResult.TYPE_MISMATCH -> issues += ValidationIssue(
                            Severity.ERROR,
                            "state_query_type_mismatch",
                            "Il reader non restituisce il tipo dichiarato: ${request.valueType.name.lowercase()}",
                        )
                }
            }
        return StateQueryProbeReport(issues = issues, verified = verified)
    }

    private suspend fun probeStateQuery(request: StateQueryProbeRequest) =
        try {
            stateQueries.probe(request)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            StateQueryProbeResult.UNAVAILABLE
        }

    private suspend fun currentLocation(): DeviceLocation? = try {
        location.current()?.takeIf(DeviceLocation::valid)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    private suspend fun finalizeRegistration(automation: Automation): FlowArmResult {
        // Lifecycle (task #31-B): la transizione in ARMED è già persistita dall'approvazione.
        // Registrata PRIMA del registrar così un eventuale disable immediato (one-shot) o un
        // ARM_FAILED successivo restano in ordine cronologico nel log.
        recordAudit(
            AuditEvent(
                automation.id,
                AuditKind.RULE_ARMED,
                nowMillis(),
                detail = "approval",
            ),
        )
        val registered = withContext(NonCancellable) {
            runCatching { registrar.register(automation) }.getOrDefault(false)
        }
        if (!registered) {
            withContext(NonCancellable) {
                automation.approvalFingerprint?.let { fingerprint ->
                    automations.disableIfApproved(automation.id, fingerprint)
                }
                // Il registrar restituisce solo un Boolean: il motivo granulare (scheduling, capability)
                // viene loggato dal coordinator; qui registriamo il fallimento d'arm a monte.
                recordAudit(
                    AuditEvent(
                        automation.id,
                        AuditKind.ARM_FAILED,
                        nowMillis(),
                        detail = "registrar_failed",
                    ),
                )
            }
            currentCoroutineContext().ensureActive()
            return FlowArmResult.RegistrationFailed(automation)
        }
        currentCoroutineContext().ensureActive()
        return FlowArmResult.Armed(automation)
    }

    private fun ApprovalFlowReview.validationFailure(): FlowArmResult.ValidationFailed? {
        val errors = draft.issues.filter { it.severity == Severity.ERROR }
        return errors.takeIf(List<ValidationIssue>::isNotEmpty)?.let(FlowArmResult::ValidationFailed)
    }

    /** Registra VALIDATION_REJECTED coi soli `code` (vocabolario chiuso) e restituisce l'esito. */
    private suspend fun rejectValidation(
        automationId: AutomationId,
        issues: List<ValidationIssue>,
    ): FlowArmResult.ValidationFailed {
        recordAudit(
            AuditEvent(
                automationId,
                AuditKind.VALIDATION_REJECTED,
                nowMillis(),
                detail = issues.joinToString(",") { it.code },
            ),
        )
        return FlowArmResult.ValidationFailed(issues)
    }

    private suspend fun recordAudit(event: AuditEvent) {
        try {
            audit.record(event)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Il logging non deve cambiare l'esito dell'arm.
        }
    }
}

private data class StateQueryProbeReport(
    val issues: List<ValidationIssue> = emptyList(),
    val verified: List<StateQueryProbeEvidence> = emptyList(),
)
