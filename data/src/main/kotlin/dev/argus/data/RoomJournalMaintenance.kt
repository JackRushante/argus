package dev.argus.data

import androidx.room.withTransaction

data class JournalRetentionPolicy(
    val maxAuditRows: Int = 2_000,
    val maxExecutions: Int = 1_000,
    val maxAgeMillis: Long = 30L * 24 * 60 * 60 * 1_000,
    val runningStaleAfterMillis: Long = 15L * 60 * 1_000,
    // 35gg: copre sempre l'intero mese corrente (tetto mensile), per questo NON riusa maxAgeMillis (30gg).
    val usageRetentionMillis: Long = 35L * 24 * 60 * 60 * 1_000,
) {
    init {
        require(maxAuditRows > 0 && maxExecutions > 0) { "I limiti retention devono essere positivi" }
        require(maxAgeMillis > 0 && runningStaleAfterMillis > 0) { "Le durate retention devono essere positive" }
        require(usageRetentionMillis > 0) { "La retention usage deve essere positiva" }
    }
}

data class JournalMaintenanceResult(
    val interrupted: Int,
    val deletedExecutions: Int,
    val deletedAuditRows: Int,
    val purgedDeferredReplies: Int = 0,
    val trimmedObservedConversations: Int = 0,
    val purgedUsageEvents: Int = 0,
)

class RoomJournalMaintenance(
    private val db: ArgusDatabase,
    private val policy: JournalRetentionPolicy = JournalRetentionPolicy(),
) {
    suspend fun run(nowMillis: Long): JournalMaintenanceResult {
        require(nowMillis >= 0) { "nowMillis non può essere negativo" }
        val staleBefore = (nowMillis - policy.runningStaleAfterMillis).coerceAtLeast(0)
        val olderThan = (nowMillis - policy.maxAgeMillis).coerceAtLeast(0)
        return db.withTransaction {
            val interrupted = db.executionJournalDao().interruptStale(staleBefore, nowMillis)
            val deletedExecutions = db.executionJournalDao().trim(olderThan, policy.maxExecutions)
            val deletedAuditRows = db.auditDao().trim(olderThan, policy.maxAuditRows)
            // Il ciphertext di una reply consumata o scaduta non deve sopravvivere al suo TTL.
            val purgedDeferred = db.deferredReplyDao().purge(nowMillis)
            // Retention del picker: display name osservati oltre l'età massima escono dal DB.
            val trimmedObserved = db.observedConversationDao().deleteSeenBefore(olderThan)
            // Retention usage: eventi oltre la finestra (35gg) escono dal DB.
            val purgedUsage = db.usageDao()
                .purgeBefore((nowMillis - policy.usageRetentionMillis).coerceAtLeast(0))
            JournalMaintenanceResult(
                interrupted,
                deletedExecutions,
                deletedAuditRows,
                purgedDeferred,
                trimmedObserved,
                purgedUsage,
            )
        }
    }
}
