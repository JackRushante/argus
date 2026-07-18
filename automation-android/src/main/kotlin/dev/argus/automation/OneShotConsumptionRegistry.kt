package dev.argus.automation

import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CompletableDeferred

fun interface OneShotConsumptionAttempt {
    /** Resolves the conditional auto-disable; false means a competing user/revision change won. */
    fun complete(consumed: Boolean)
}

/**
 * Process-local provenance for one-shot auto-consumption. A disabled rule alone is ambiguous:
 * the runtime must distinguish its own post-dispatch CAS from a user disable while an LLM call is
 * in flight. The event id ties the authorization to the exact queued execution.
 */
interface OneShotConsumptionRegistry {
    fun begin(eventId: TriggerEventId): OneShotConsumptionAttempt
    suspend fun wasAutoConsumed(eventId: TriggerEventId): Boolean
}

object NoopOneShotConsumptionRegistry : OneShotConsumptionRegistry {
    override fun begin(eventId: TriggerEventId): OneShotConsumptionAttempt =
        OneShotConsumptionAttempt { }

    override suspend fun wasAutoConsumed(eventId: TriggerEventId): Boolean = false
}

class ProcessOneShotConsumptionRegistry(
    private val maxRetainedDecisions: Int = 128,
) : OneShotConsumptionRegistry {
    private val monitor = Any()
    private val decisions = LinkedHashMap<TriggerEventId, CompletableDeferred<Boolean>>()

    init {
        require(maxRetainedDecisions > 0) { "maxRetainedDecisions must be positive" }
    }

    override fun begin(eventId: TriggerEventId): OneShotConsumptionAttempt {
        val decision = synchronized(monitor) {
            decisions[eventId]?.let { return OneShotConsumptionAttempt { } }
            CompletableDeferred<Boolean>().also { created ->
                decisions[eventId] = created
                trimCompletedDecisions()
            }
        }
        return OneShotConsumptionAttempt { consumed ->
            if (decision.complete(consumed) && !consumed) {
                synchronized(monitor) {
                    decisions.remove(eventId, decision)
                }
            }
        }
    }

    override suspend fun wasAutoConsumed(eventId: TriggerEventId): Boolean {
        val decision = synchronized(monitor) { decisions[eventId] } ?: return false
        return decision.await()
    }

    /** Pending CAS decisions are never evicted; completed oldest entries are bounded. */
    private fun trimCompletedDecisions() {
        while (decisions.size > maxRetainedDecisions) {
            val completed = decisions.entries.firstOrNull { it.value.isCompleted } ?: return
            decisions.remove(completed.key, completed.value)
        }
    }
}
