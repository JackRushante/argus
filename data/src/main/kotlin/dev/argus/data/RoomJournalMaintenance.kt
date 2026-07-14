package dev.argus.data

import androidx.room.withTransaction

data class JournalRetentionPolicy(
    val maxAuditRows: Int = 2_000,
    val maxExecutions: Int = 1_000,
    val maxAgeMillis: Long = 30L * 24 * 60 * 60 * 1_000,
    val runningStaleAfterMillis: Long = 15L * 60 * 1_000,
) {
    init {
        require(maxAuditRows > 0 && maxExecutions > 0) { "I limiti retention devono essere positivi" }
        require(maxAgeMillis > 0 && runningStaleAfterMillis > 0) { "Le durate retention devono essere positive" }
    }
}

data class JournalMaintenanceResult(
    val interrupted: Int,
    val deletedExecutions: Int,
    val deletedAuditRows: Int,
    val purgedDeferredReplies: Int = 0,
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
            JournalMaintenanceResult(interrupted, deletedExecutions, deletedAuditRows, purgedDeferred)
        }
    }
}
