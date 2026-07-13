package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTier
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Collections

class FakeActionExecutor(private val fail: Set<String> = emptySet()) : ActionExecutor {
    val executed = mutableListOf<Action>()

    override suspend fun execute(action: Action, ctx: FireContext): ActionResult {
        executed += action
        val key = action::class.simpleName ?: ""
        return when {
            key in fail -> ActionResult.Failure("forced")
            action.tier == ActionTier.GENERATIVE -> ActionResult.Submitted
            else -> ActionResult.Success
        }
    }
}

object AllowAllFirePolicy : FirePolicy {
    override suspend fun evaluate(automation: Automation, event: TriggerEvent) = FirePolicyDecision.Allow
}

class FakeAutomationStore(seed: List<Automation> = emptyList()) : AutomationStore {
    private val mutex = Mutex()
    private val map = seed.associateBy { it.id }.toMutableMap()
    private val fired = mutableMapOf<AutomationId, Long>()
    private val claims = mutableMapOf<Pair<AutomationId, TriggerEventId>, ExecutionId>()
    private val changes = MutableStateFlow(sorted())

    override suspend fun get(id: AutomationId) = mutex.withLock { map[id] }

    override suspend fun all() = mutex.withLock { sorted() }

    override fun observeAll(): Flow<List<Automation>> = changes.asStateFlow()

    override suspend fun armed() = mutex.withLock {
        map.values.filter { it.status == AutomationStatus.ARMED && it.enabled }
    }

    override suspend fun delete(id: AutomationId): Unit = mutex.withLock {
        map.remove(id)
        fired.remove(id)
        claims.keys.removeAll { it.first == id }
        publish()
        Unit
    }

    override suspend fun disable(id: AutomationId): Unit = mutex.withLock {
        map[id]?.let { map[id] = it.copy(status = AutomationStatus.DISABLED, enabled = false) }
        publish()
        Unit
    }

    override suspend fun disableIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean = mutex.withLock {
        val current = map[id] ?: return@withLock false
        if (current.status != AutomationStatus.ARMED || !current.enabled ||
            current.approvalFingerprint != fingerprint ||
            current.approvalFingerprint != ApprovalFingerprints.of(current)
        ) return@withLock false
        map[id] = current.copy(status = AutomationStatus.DISABLED, enabled = false)
        publish()
        true
    }

    override suspend fun enableIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean = mutex.withLock {
        val current = map[id] ?: return@withLock false
        if (current.status != AutomationStatus.DISABLED || current.enabled ||
            current.approvalFingerprint != fingerprint ||
            current.approvalFingerprint != ApprovalFingerprints.of(current)
        ) {
            if (current.status == AutomationStatus.DISABLED &&
                (current.approvalFingerprint == null ||
                    current.approvalFingerprint != ApprovalFingerprints.of(current))
            ) map[id] = current.copy(status = AutomationStatus.NEEDS_REVIEW, enabled = false)
            publish()
            return@withLock false
        }
        map[id] = current.copy(status = AutomationStatus.ARMED, enabled = true)
        publish()
        true
    }

    override suspend fun markNeedsReview(id: AutomationId): Unit = mutex.withLock {
        map[id]?.let { map[id] = it.copy(status = AutomationStatus.NEEDS_REVIEW, enabled = false) }
        publish()
        Unit
    }

    override suspend fun markNeedsReviewIfApproved(
        id: AutomationId,
        fingerprint: ApprovalFingerprint,
    ): Boolean = mutex.withLock {
        val current = map[id] ?: return@withLock false
        if (current.status != AutomationStatus.ARMED || !current.enabled ||
            current.approvalFingerprint != fingerprint ||
            current.approvalFingerprint != ApprovalFingerprints.of(current)
        ) return@withLock false
        map[id] = current.copy(status = AutomationStatus.NEEDS_REVIEW, enabled = false)
        publish()
        true
    }

    override suspend fun claimFire(request: FireClaimRequest): FireClaimResult = mutex.withLock {
        val key = request.automationId to request.eventId
        claims[key]?.let { return@withLock FireClaimResult.Duplicate(it) }
        // Anche eventi soppressi/non eleggibili restano consumati: una redelivery tardiva
        // non deve trasformare un vecchio evento in una nuova esecuzione.
        claims[key] = request.executionId

        val automation = map[request.automationId]
        if (automation == null || automation.status != AutomationStatus.ARMED || !automation.enabled)
            return@withLock FireClaimResult.NotEligible

        val last = fired[request.automationId]
        if (last != null && request.cooldownMs > 0 &&
            (request.atMillis <= last || request.atMillis - last < request.cooldownMs)
        ) {
            return@withLock FireClaimResult.Cooldown(saturatingAdd(last, request.cooldownMs))
        }

        fired[request.automationId] = request.atMillis
        FireClaimResult.Claimed
    }

    override suspend fun recordFired(id: AutomationId, atMillis: Long): Unit = mutex.withLock {
        fired[id] = atMillis
        Unit
    }

    override suspend fun lastFiredAt(id: AutomationId) = mutex.withLock { fired[id] }

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right

    private fun sorted(): List<Automation> =
        map.values.sortedWith(compareBy({ it.priority }, { it.id.value }))

    private fun publish() {
        changes.value = sorted()
    }
}

class FakeAuditSink : AuditSink {
    val events: MutableList<AuditEvent> = Collections.synchronizedList(mutableListOf())
    override suspend fun record(e: AuditEvent) {
        events += e
    }
}

class FakeExecutionJournal : ExecutionJournal {
    val actions: MutableList<ActionJournalEntry> = Collections.synchronizedList(mutableListOf())
    val completions: MutableList<ExecutionCompletion> = Collections.synchronizedList(mutableListOf())

    override suspend fun recordAction(entry: ActionJournalEntry) {
        actions += entry
    }

    override suspend fun finish(completion: ExecutionCompletion) {
        completions += completion
    }
}
