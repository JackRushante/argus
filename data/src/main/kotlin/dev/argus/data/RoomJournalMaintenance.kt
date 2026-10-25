package dev.argus.data

import androidx.room.withTransaction

data class JournalRetentionPolicy(
    val maxAuditRows: Int = 2_000,
    val maxExecutions: Int = 1_000,
    val maxAgeMillis: Long = DEFAULT_RETENTION_DAYS * MILLIS_PER_DAY,
    val runningStaleAfterMillis: Long = 15L * 60 * 1_000,
    // 35gg: copre sempre l'intero mese corrente (tetto mensile), per questo NON riusa maxAgeMillis (30gg).
    val usageRetentionMillis: Long = 35L * 24 * 60 * 60 * 1_000,
) {
    init {
        require(maxAuditRows > 0 && maxExecutions > 0) { "I limiti retention devono essere positivi" }
        require(maxAgeMillis > 0 && runningStaleAfterMillis > 0) { "Le durate retention devono essere positive" }
        require(usageRetentionMillis > 0) { "La retention usage deve essere positiva" }
    }

    companion object {
        /** Conservazione del Log prima che l'utente la scelga: invariata rispetto alla 0.3.5. */
        const val DEFAULT_RETENTION_DAYS = 30
        const val MILLIS_PER_DAY = 24L * 60 * 60 * 1_000
    }
}

/** Righe rimosse da «Svuota il log». */
data class ClearLogResult(val deletedAuditRows: Int, val deletedActions: Int)

data class JournalMaintenanceResult(
    val interrupted: Int,
    val deletedExecutions: Int,
    val deletedAuditRows: Int,
    val purgedDeferredReplies: Int = 0,
    val trimmedObservedConversations: Int = 0,
    val purgedUsageEvents: Int = 0,
)

/**
 * @param logRetentionDays giorni di conservazione di audit ed esecuzioni scelti dall'utente (F11).
 *   Senza provider vale l'età della [policy]. Un valore non positivo ricade sul default. Il picker
 *   delle conversazioni osservate resta sull'età della [policy]: non e' parte del Log.
 */
class RoomJournalMaintenance(
    private val db: ArgusDatabase,
    private val policy: JournalRetentionPolicy = JournalRetentionPolicy(),
    private val logRetentionDays: (() -> Int)? = null,
) {
    suspend fun run(nowMillis: Long): JournalMaintenanceResult {
        require(nowMillis >= 0) { "nowMillis non può essere negativo" }
        val staleBefore = (nowMillis - policy.runningStaleAfterMillis).coerceAtLeast(0)
        val olderThan = (nowMillis - policy.maxAgeMillis).coerceAtLeast(0)
        val logOlderThan = (nowMillis - logAgeMillis()).coerceAtLeast(0)
        return db.withTransaction {
            val interrupted = db.executionJournalDao().interruptStale(staleBefore, nowMillis)
            val deletedExecutions = db.executionJournalDao().trim(logOlderThan, policy.maxExecutions)
            val deletedAuditRows = db.auditDao().trim(logOlderThan, policy.maxAuditRows)
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

    /**
     * «Svuota il log»: cancella audit e righe di azione delle esecuzioni concluse. Restano i claim
     * (deduplica degli eventi), `lastFiredAt` (cooldown) e tutto cio' che serve a un recupero: le
     * esecuzioni RUNNING, SUBMITTED (risoluzione asincrona) e DEFERRED (risposta da consegnare a
     * mano dal Log) con le loro righe.
     */
    suspend fun clearLog(): ClearLogResult = db.withTransaction {
        val deletedActions = db.executionJournalDao().deleteActionsOfConcluded()
        val deletedAudit = db.auditDao().deleteExceptOpenExecutions()
        ClearLogResult(deletedAudit, deletedActions)
    }

    private fun logAgeMillis(): Long {
        val days = logRetentionDays?.invoke()
            ?: return policy.maxAgeMillis
        val effective = if (days > 0) days else JournalRetentionPolicy.DEFAULT_RETENTION_DAYS
        return effective * JournalRetentionPolicy.MILLIS_PER_DAY
    }
}
