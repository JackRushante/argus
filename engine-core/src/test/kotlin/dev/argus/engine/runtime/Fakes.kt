package dev.argus.engine.runtime
import dev.argus.engine.model.*

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

class FakeAutomationStore(seed: List<Automation> = emptyList()) : AutomationStore {
    private val map = seed.associateBy { it.id }.toMutableMap()
    private val fired = mutableMapOf<AutomationId, Long>()
    override suspend fun get(id: AutomationId) = map[id]
    override suspend fun armed() = map.values.filter { it.status == AutomationStatus.ARMED && it.enabled }
    override suspend fun save(a: Automation) { map[a.id] = a }
    override suspend fun setStatus(id: AutomationId, status: AutomationStatus) { map[id]?.let { map[id] = it.copy(status = status) } }
    override suspend fun recordFired(id: AutomationId, atMillis: Long) { fired[id] = atMillis }
    override suspend fun lastFiredAt(id: AutomationId) = fired[id]
}

class FakeAuditSink : AuditSink {
    val events = mutableListOf<AuditEvent>()
    override suspend fun record(e: AuditEvent) { events += e }
}
