package dev.argus.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import dev.argus.data.entities.UsageEventEntity

/**
 * Aggregato per provider su una finestra temporale. I totali token/costo sono nullable perché
 * `SUM` in SQLite ritorna NULL quando tutte le righe della finestra hanno il campo NULL:
 * "n/d" (Hermes pre-S15) è distinto da 0 (T4).
 */
data class ProviderUsageAggregate(
    val providerId: String,
    val calls: Long,
    val okCalls: Long,
    val errorCalls: Long,
    val blockedCalls: Long,
    val tokensIn: Long?,
    val tokensOut: Long?,
    val costMicros: Long?,
)

/**
 * Aggregato dei soli TOKEN per provider su una finestra temporale (metrica primaria dei provider
 * token-only: Hermes/OpenRouter/Custom). Stessa semantica NULL di [ProviderUsageAggregate]:
 * `SUM` su righe tutte NULL ritorna NULL = "n/d", distinto da 0.
 */
data class ProviderTokensAggregate(
    val providerId: String,
    val tokensIn: Long?,
    val tokensOut: Long?,
)

@Dao
interface UsageDao {
    @Insert
    suspend fun insert(event: UsageEventEntity): Long

    /**
     * Aggregati per provider nella semi-finestra `[startMillis, endMillisExclusive)`. Una sola query
     * parametrica copre ora/giorno/mese: i confini li calcola [dev.argus.data.UsageWindows]. Il totale
     * globale si ottiene sommando gli aggregate in Kotlin. I literal outcome ('OK'/'ERROR'/'BLOCKED_BUDGET')
     * combaciano con `Enum.name` persistito dai converter (T3).
     */
    @Query(
        "SELECT providerId, COUNT(*) AS calls, " +
            "SUM(CASE WHEN outcome = 'OK' THEN 1 ELSE 0 END) AS okCalls, " +
            "SUM(CASE WHEN outcome = 'ERROR' THEN 1 ELSE 0 END) AS errorCalls, " +
            "SUM(CASE WHEN outcome = 'BLOCKED_BUDGET' THEN 1 ELSE 0 END) AS blockedCalls, " +
            "SUM(tokensIn) AS tokensIn, SUM(tokensOut) AS tokensOut, SUM(costMicros) AS costMicros " +
            "FROM usage_events " +
            "WHERE timestampMs >= :startMillis AND timestampMs < :endMillisExclusive " +
            "GROUP BY providerId ORDER BY providerId ASC",
    )
    suspend fun aggregateBetween(startMillis: Long, endMillisExclusive: Long): List<ProviderUsageAggregate>

    /**
     * Totali token per provider nella semi-finestra `[startMillis, endMillisExclusive)`: stesso
     * pattern di [aggregateBetween], confini da [dev.argus.data.UsageWindows]. Usato dal budget
     * TOKEN mensile dei provider token-only e dal breakdown UI.
     */
    @Query(
        "SELECT providerId, SUM(tokensIn) AS tokensIn, SUM(tokensOut) AS tokensOut " +
            "FROM usage_events " +
            "WHERE timestampMs >= :startMillis AND timestampMs < :endMillisExclusive " +
            "GROUP BY providerId ORDER BY providerId ASC",
    )
    suspend fun tokensBetween(startMillis: Long, endMillisExclusive: Long): List<ProviderTokensAggregate>

    @Query("DELETE FROM usage_events WHERE timestampMs < :cutoffMillis")
    suspend fun purgeBefore(cutoffMillis: Long): Int
}
