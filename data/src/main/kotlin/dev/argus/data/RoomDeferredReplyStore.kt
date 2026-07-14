package dev.argus.data

import dev.argus.data.dao.DeferredReplyDao
import dev.argus.data.entities.DeferredReplyEntity

/** Boundary della persistenza deferred: il chiamante gestisce cifratura e policy di consegna. */
interface DeferredReplyStore {
    /** false se esiste già un deferred per la stessa action: mai sovrascrivere il payload. */
    suspend fun save(entity: DeferredReplyEntity): Boolean
    suspend fun firstActionable(executionId: String, nowMillis: Long): DeferredReplyEntity?
    /** CAS one-shot: true soltanto per il primo consumo. */
    suspend fun markConsumed(executionId: String, actionIndex: Int, atMillis: Long): Boolean
    suspend fun purgeExpired(nowMillis: Long): Int
    /** Cancellazione integrale, usata dalla revoca privacy. */
    suspend fun clear(): Int
}

class RoomDeferredReplyStore(private val dao: DeferredReplyDao) : DeferredReplyStore {
    override suspend fun save(entity: DeferredReplyEntity): Boolean =
        dao.insertIfAbsent(entity) != -1L

    override suspend fun firstActionable(
        executionId: String,
        nowMillis: Long,
    ): DeferredReplyEntity? = dao.firstActionable(executionId, nowMillis)

    override suspend fun markConsumed(
        executionId: String,
        actionIndex: Int,
        atMillis: Long,
    ): Boolean = dao.markConsumed(executionId, actionIndex, atMillis) == 1

    override suspend fun purgeExpired(nowMillis: Long): Int = dao.purge(nowMillis)

    override suspend fun clear(): Int = dao.clear()
}
