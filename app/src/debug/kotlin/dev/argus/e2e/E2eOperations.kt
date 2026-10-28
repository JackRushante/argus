package dev.argus.e2e

import dev.argus.ArgusApplicationEntryPoint
import dev.argus.automation.ApprovalFlow
import dev.argus.automation.DraftSubmissionResult
import dev.argus.automation.FlowArmResult
import dev.argus.automation.ReconcileReason
import dev.argus.automation.manual.ManualRunResult
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionResult
import dev.argus.engine.safety.Severity
import dev.argus.engine.safety.ValidationIssue
import dev.argus.shizuku.ShizukuGatewayStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Operazioni dell'aggancio di debug degli E2E (0.6.0), dietro [E2eHookProvider]. Tutto passa dalle
 * stesse classi dell'app: approvazione (impronta, validatore, capacità), registrar, motore, journal.
 * Le regole della suite portano il prefisso [RULE_PREFIX] nel nome, così `reset` toglie solo quelle.
 */
class E2eOperations(
    private val services: ArgusApplicationEntryPoint,
    private val events: E2eEventFactory = E2eEventFactory(),
) {
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun dispatch(method: String, request: JsonObject): JsonObject = when (method) {
        "load" -> load(request)
        // Un solo giro per «ripulisci e carica»: ogni chiamata dalla shell costa circa un secondo.
        "reset_load" -> {
            reset()
            load(request)
        }
        "fire" -> fire(request)
        "journal" -> journal(request)
        "reset" -> reset()
        "setup" -> setup()
        "status" -> status()
        "pause" -> pause(request)
        "shizuku_request" -> shizukuRequest()
        else -> failure("unknown_method", detail = method)
    }

    // ---- load -----------------------------------------------------------------------------------

    /** Importa, approva con la strada normale e arma. La richiesta è il JSON di un `AutomationDraft`. */
    private suspend fun load(request: JsonObject): JsonObject {
        ensurePreferences()
        val draft = try {
            STRICT_JSON.decodeFromString(AutomationDraft.serializer(), request.toString())
        } catch (error: SerializationException) {
            return failure("decode", "decode_error", error.message?.take(MAX_DETAIL))
        } catch (error: IllegalArgumentException) {
            return failure("decode", "decode_error", error.message?.take(MAX_DETAIL))
        }
        val named = if (draft.name.startsWith(RULE_PREFIX)) draft else draft.copy(name = RULE_PREFIX + draft.name)
        val flow = services.approvalFlow()
        return when (val submission = flow.submit(CompileResult(reply = "", draft = named, metaError = null))) {
            is DraftSubmissionResult.Ready -> armReady(flow, submission)
            is DraftSubmissionResult.NoDraft -> failure("submit", submission.code)
            is DraftSubmissionResult.Rejected -> failure("submit", submission.code)
            is DraftSubmissionResult.Conflict -> failure("submit", "conflict")
        }
    }

    private suspend fun armReady(flow: ApprovalFlow, submission: DraftSubmissionResult.Ready): JsonObject {
        val review = submission.review
        if (!review.canArm) return failure("review", issues = review.draft.issues)
        val snapshot = review.draft.snapshot
        return when (val arm = flow.arm(snapshot.id, snapshot.revision, snapshot.fingerprint)) {
            is FlowArmResult.Armed -> buildJsonObject {
                put("ok", true)
                put("id", arm.automation.id.value)
                put("name", arm.automation.name)
                put("fingerprint", arm.automation.approvalFingerprint?.value.orEmpty())
                put("warnings", issuesJson(review.draft.issues.filter { it.severity != Severity.ERROR }))
            }
            is FlowArmResult.ValidationFailed -> failure("arm", issues = arm.issues)
            is FlowArmResult.RegistrationFailed -> failure("arm", "registration_failed", id = arm.automation.id.value)
            is FlowArmResult.Stale -> failure("arm", "stale")
            FlowArmResult.Missing -> failure("arm", "missing")
            FlowArmResult.PolicyUnavailable -> failure("arm", "policy_unavailable")
            FlowArmResult.LocationUnavailable -> failure("arm", "location_unavailable")
            FlowArmResult.IntegrityFailure -> failure("arm", "integrity_failure")
            FlowArmResult.AutomationConflict -> failure("arm", "automation_conflict")
        }
    }

    // ---- fire -----------------------------------------------------------------------------------

    /** Consegna al motore l'evento sintetico della regola `id` (o `name`); torna a programma concluso. */
    private suspend fun fire(request: JsonObject): JsonObject {
        val ref = request.string("id") ?: request.string("name") ?: return failure("fire", "missing_id")
        val automation = resolve(ref) ?: return failure("fire", "rule_not_found")
        val payload = request["payload"] as? JsonObject ?: JsonObject(emptyMap())
        val timeoutMs = request.long("timeoutMs") ?: DEFAULT_FIRE_TIMEOUT_MS
        val type = payload.string("as") ?: triggerTypeOf(automation.trigger)
        val startedAt = System.currentTimeMillis()

        if (type == "manual" && automation.trigger is Trigger.Manual) {
            // Il percorso vero del pulsante «Esegui ora»: runner manuale, motore, journal.
            return when (val run = services.manualRuleRunner().runFromApp(automation.id.value)) {
                is ManualRunResult.Rejected -> failure("fire", "manual_rejected", run.reason.name)
                is ManualRunResult.Accepted -> {
                    val finished = withTimeoutOrNull(timeoutMs) { run.completion.join() } != null
                    buildJsonObject {
                        put("ok", true)
                        put("type", "manual")
                        put("paused", run.paused)
                        put("timeout", !finished)
                        put("elapsedMs", System.currentTimeMillis() - startedAt)
                    }
                }
            }
        }

        val built = when (val result = events.build(automation, payload)) {
            is E2eEventResult.Built -> result
            is E2eEventResult.Invalid -> return failure("fire", result.code)
        }
        val engine = services.engine()
        val state = services.deviceStateSnapshotProvider()
        val outcomes = withTimeoutOrNull(timeoutMs) {
            services.dispatchFrame().run {
                engine.onTrigger(built.envelope) { stateRequest -> state.current(stateRequest) }
            }
        }
        return buildJsonObject {
            put("ok", true)
            put("type", built.type)
            put("eventId", built.envelope.id.value)
            put("timeout", outcomes == null)
            put("elapsedMs", System.currentTimeMillis() - startedAt)
            put(
                "outcomes",
                buildJsonArray {
                    outcomes.orEmpty().forEach { outcome ->
                        add(
                            buildJsonObject {
                                put("id", outcome.automation.id.value)
                                put(
                                    "results",
                                    buildJsonArray {
                                        outcome.results.forEach { add(JsonPrimitive(describe(it))) }
                                    },
                                )
                            },
                        )
                    }
                },
            )
        }
    }

    private fun describe(result: ActionResult): String = when (result) {
        ActionResult.Success -> "success"
        ActionResult.Submitted -> "submitted"
        is ActionResult.Failure -> "failure:${result.reason}"
    }

    // ---- journal --------------------------------------------------------------------------------

    /** Esiti dell'ultima esecuzione della regola e righe di audit (codici chiusi, nessun contenuto). */
    private suspend fun journal(request: JsonObject): JsonObject {
        val ref = request.string("id") ?: request.string("name") ?: return failure("journal", "missing_id")
        val automation = resolve(ref)
        val id = automation?.id?.value ?: ref
        val db = services.database().openHelper.readableDatabase
        return buildJsonObject {
            put("ok", true)
            put("id", id)
            if (automation != null) {
                put("status", automation.status.name)
                put("enabled", automation.enabled)
            }
            db.query("SELECT COUNT(*) FROM fire_claims WHERE automationId = ?", arrayOf<Any>(id)).use { cursor ->
                put("executions", if (cursor.moveToFirst()) cursor.getInt(0) else 0)
            }
            var executionId: String? = null
            db.query(
                "SELECT executionId, status, completedAtMillis, succeededCount, failedCount, " +
                    "submittedCount, deferredCount FROM fire_claims WHERE automationId = ? " +
                    "ORDER BY claimedAtMillis DESC LIMIT 1",
                arrayOf<Any>(id),
            ).use { cursor ->
                if (cursor.moveToFirst()) {
                    executionId = cursor.getString(0)
                    put(
                        "last",
                        buildJsonObject {
                            put("status", cursor.getString(1))
                            put("succeeded", cursor.getInt(3))
                            put("failed", cursor.getInt(4))
                            put("submitted", cursor.getInt(5))
                            put("deferred", cursor.getInt(6))
                        },
                    )
                }
            }
            put(
                "actions",
                buildJsonArray {
                    val execution = executionId ?: return@buildJsonArray
                    db.query(
                        "SELECT actionPath, actionType, outcome, errorCode FROM action_results " +
                            "WHERE executionId = ? ORDER BY actionIndex",
                        arrayOf<Any>(execution),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            add(
                                buildJsonObject {
                                    put("path", cursor.getString(0))
                                    put("type", cursor.getString(1))
                                    put("outcome", cursor.getString(2))
                                    put("code", if (cursor.isNull(3)) "" else cursor.getString(3))
                                },
                            )
                        }
                    }
                },
            )
            put(
                "audit",
                buildJsonArray {
                    db.query(
                        "SELECT kind, detail FROM audit WHERE automationId = ? ORDER BY atMillis ASC, id ASC LIMIT 60",
                        arrayOf<Any>(id),
                    ).use { cursor ->
                        while (cursor.moveToNext()) {
                            add(
                                buildJsonObject {
                                    put("kind", cursor.getString(0))
                                    put("detail", cursor.getString(1).orEmpty())
                                },
                            )
                        }
                    }
                },
            )
        }
    }

    // ---- reset ----------------------------------------------------------------------------------

    /** Disarma e cancella tutte le regole e le bozze della suite; rimette a posto la pausa. */
    private suspend fun reset(): JsonObject {
        val store = services.automationStore()
        val coordinator = services.enablementCoordinator()
        var removed = 0
        store.all().filter { it.name.startsWith(RULE_PREFIX) }.forEach { rule ->
            runCatching { coordinator.setEnabled(rule.id, false) }
            store.delete(rule.id)
            removed += 1
        }
        val drafts = services.draftRepository()
        var removedDrafts = 0
        drafts.observeAll().first().filter { it.draft.name.startsWith(RULE_PREFIX) }.forEach { pending ->
            drafts.delete(pending.id, pending.revision)
            removedDrafts += 1
        }
        services.automationPauseStore().setPaused(false)
        runCatching { services.timeAlarmRuntime().reconcile(ReconcileReason.CAPABILITY_CHANGED) }
        return buildJsonObject {
            put("ok", true)
            put("removedRules", removed)
            put("removedDrafts", removedDrafts)
            put("remainingRules", store.all().size)
        }
    }

    // ---- setup / status ---------------------------------------------------------------------------

    private suspend fun setup(): JsonObject {
        ensurePreferences()
        return status()
    }

    private suspend fun ensurePreferences() {
        val preferences = services.appPreferencesStore()
        if (!preferences.observe().value.privacyAccepted) preferences.setPrivacyAccepted(true)
        if (!preferences.observe().value.onboardingCompleted) preferences.setOnboardingCompleted(true)
    }

    private suspend fun status(): JsonObject {
        val gateway = services.shizukuGateway()
        val shizuku = gateway.status()
        val snapshot = runCatching { services.firePolicySnapshots().current() }.getOrNull()
        return buildJsonObject {
            put("ok", true)
            put("shizuku", shizuku.name)
            put("shizukuAuthorized", shizuku == ShizukuGatewayStatus.AUTHORIZED)
            put("shizukuUid", gateway.observeIdentity().value?.uid ?: -1)
            put("paused", services.automationPauseStore().isPaused())
            put("privacyAccepted", services.appPreferencesStore().observe().value.privacyAccepted)
            put("capabilities", JsonArray(snapshot?.availableCapabilities.orEmpty().sorted().map { JsonPrimitive(it) }))
        }
    }

    /** Apre la richiesta di permesso di Shizuku e torna subito: il «Consenti» lo preme il runner. */
    private fun shizukuRequest(): JsonObject {
        val gateway = services.shizukuGateway()
        background.launch { runCatching { gateway.requestPermission() } }
        return buildJsonObject { put("ok", true) }
    }

    private fun pause(request: JsonObject): JsonObject {
        val on = (request["on"] as? JsonPrimitive)?.booleanOrNull ?: return failure("pause", "missing_on")
        services.automationPauseStore().setPaused(on)
        return buildJsonObject {
            put("ok", true)
            put("paused", on)
        }
    }

    // ---- helpers ----------------------------------------------------------------------------------

    private suspend fun resolve(ref: String): Automation? =
        services.automationStore().all().firstOrNull { it.id.value == ref || it.name == ref }

    private fun failure(
        stage: String,
        code: String? = null,
        detail: String? = null,
        issues: List<ValidationIssue> = emptyList(),
        id: String? = null,
    ): JsonObject = buildJsonObject {
        put("ok", false)
        put("stage", stage)
        if (code != null) put("code", code)
        if (detail != null) put("detail", detail)
        if (id != null) put("id", id)
        if (issues.isNotEmpty()) put("issues", issuesJson(issues))
    }

    private fun issuesJson(issues: List<ValidationIssue>): JsonArray = buildJsonArray {
        issues.forEach { issue ->
            add(
                buildJsonObject {
                    put("severity", issue.severity.name)
                    put("code", issue.code)
                    put("detail", issue.detail.orEmpty())
                },
            )
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    companion object {
        /** Prefisso del nome delle regole della suite: `reset` toglie solo queste. */
        const val RULE_PREFIX = "e2e/"
        private const val DEFAULT_FIRE_TIMEOUT_MS = 90_000L
        private const val MAX_DETAIL = 300

        /** Come l'app, ma senza ignorare i campi sconosciuti: un refuso nella suite deve farsi sentire. */
        private val STRICT_JSON = Json(from = ArgusJson) { ignoreUnknownKeys = false }
    }
}
