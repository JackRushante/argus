// AutomationStore.kt
package dev.argus.engine.runtime
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
interface AutomationStore {
    suspend fun get(id: AutomationId): Automation?
    suspend fun armed(): List<Automation>
    suspend fun save(a: Automation)
    suspend fun setStatus(id: AutomationId, status: AutomationStatus)
    suspend fun recordFired(id: AutomationId, atMillis: Long)
    suspend fun lastFiredAt(id: AutomationId): Long?
}
