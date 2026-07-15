package dev.argus.automation.connectivity

import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ConnMedium
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AutomationStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class ConnectivityReconcileReport(
    val requiredBy: List<AutomationId>,
    val active: Boolean,
    val failed: List<AutomationId> = emptyList(),
    val cleanupSucceeded: Boolean = true,
)

fun interface ConnectivityTriggerRuntime {
    suspend fun reconcile(): ConnectivityReconcileReport
}

object NoopConnectivityTriggerRuntime : ConnectivityTriggerRuntime {
    override suspend fun reconcile() = ConnectivityReconcileReport(emptyList(), active = false)
}

interface ConnectivitySentinelBackend {
    suspend fun start(): Boolean
    suspend fun stop(): Boolean
}

/** Accende un'unica sentinella condivisa solo per i medium non manifest-safe: Wi-Fi e POWER. */
class ConnectivitySentinelCoordinator(
    private val store: AutomationStore,
    private val backend: ConnectivitySentinelBackend,
) : ConnectivityTriggerRuntime {
    private val mutex = Mutex()

    override suspend fun reconcile(): ConnectivityReconcileReport = mutex.withLock {
        val required = store.armed()
            .filter { automation ->
                val medium = (automation.trigger as? Trigger.Connectivity)?.medium
                medium == ConnMedium.WIFI || medium == ConnMedium.POWER
            }
            .map { it.id }
            .sortedBy { it.value }

        if (required.isEmpty()) {
            val stopped = backend.stop()
            return@withLock ConnectivityReconcileReport(
                requiredBy = emptyList(),
                active = !stopped,
                cleanupSucceeded = stopped,
            )
        }

        val started = backend.start()
        ConnectivityReconcileReport(
            requiredBy = required,
            active = started,
            failed = if (started) emptyList() else required,
        )
    }
}
