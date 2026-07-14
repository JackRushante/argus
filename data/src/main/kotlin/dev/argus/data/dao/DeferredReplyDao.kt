package dev.argus.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.argus.data.entities.DeferredReplyEntity

@Dao
interface DeferredReplyDao {
    /** Un solo deferred per action: una redelivery non può sovrascrivere il payload originale. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entity: DeferredReplyEntity): Long

    @Query(
        "SELECT * FROM deferred_replies WHERE executionId = :executionId " +
            "AND consumedAtMillis IS NULL AND expiresAtMillis > :nowMillis " +
            "ORDER BY actionIndex ASC LIMIT 1",
    )
    suspend fun firstActionable(executionId: String, nowMillis: Long): DeferredReplyEntity?

    @Query(
        "UPDATE deferred_replies SET consumedAtMillis = :atMillis " +
            "WHERE executionId = :executionId AND actionIndex = :actionIndex " +
            "AND consumedAtMillis IS NULL",
    )
    suspend fun markConsumed(executionId: String, actionIndex: Int, atMillis: Long): Int

    /** Consumate o scadute: il ciphertext non serve più a nessuno e viene eliminato. */
    @Query(
        "DELETE FROM deferred_replies " +
            "WHERE expiresAtMillis <= :nowMillis OR consumedAtMillis IS NOT NULL",
    )
    suspend fun purge(nowMillis: Long): Int

    @Query("DELETE FROM deferred_replies")
    suspend fun clear(): Int

    @Query("SELECT COUNT(*) FROM deferred_replies")
    suspend fun count(): Int
}
