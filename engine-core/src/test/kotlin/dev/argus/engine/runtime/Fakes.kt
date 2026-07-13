package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ActionTier
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
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

    override suspend fun get(id: AutomationId) = mutex.withLock { map[id] }

    override suspend fun armed() = mutex.withLock {
        map.values.filter { it.status == AutomationStatus.ARMED && it.enabled }
    }

    override suspend fun save(a: Automation): Unit = mutex.withLock {
        map[a.id] = a
        Unit
    }

    override suspend fun setStatus(id: AutomationId, status: AutomationStatus): Unit = mutex.withLock {
        map[id]?.let { map[id] = it.copy(status = status) }
        Unit
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
}

class FakeAuditSink : AuditSink {
    val events: MutableList<AuditEvent> = Collections.synchronizedList(mutableListOf())
    override suspend fun record(e: AuditEvent) {
        events += e
    }
}
