package dev.argus.data

import dev.argus.data.dao.ObservedConversationDao
import dev.argus.data.entities.ObservedConversationEntity
import dev.argus.engine.notification.ObservedConversation
import dev.argus.engine.notification.ObservedConversationStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomObservedConversationStore(
    private val dao: ObservedConversationDao,
) : ObservedConversationStore {
    override suspend fun recent(limit: Int): List<ObservedConversation> {
        validateLimit(limit)
        return dao.recent(limit).map(::toDomain)
    }

    override fun observeRecent(limit: Int): Flow<List<ObservedConversation>> {
        validateLimit(limit)
        return dao.observeRecent(limit).map { values -> values.map(::toDomain) }
    }

    override suspend fun record(conversation: ObservedConversation) {
        dao.record(
            ObservedConversationEntity(
                conversationId = conversation.id,
                packageName = conversation.packageName,
                displayName = conversation.displayName,
                isGroup = conversation.isGroup,
                lastSeenAtMillis = conversation.lastSeenAtMillis,
            ),
            maximumRows = MAXIMUM_ROWS,
        )
    }

    override suspend fun clear() {
        dao.clear()
    }

    private fun validateLimit(limit: Int) {
        require(limit in 1..MAXIMUM_ROWS) { "limit conversazioni fuori intervallo" }
    }

    private fun toDomain(entity: ObservedConversationEntity) = ObservedConversation(
        id = entity.conversationId,
        packageName = entity.packageName,
        displayName = entity.displayName,
        isGroup = entity.isGroup,
        lastSeenAtMillis = entity.lastSeenAtMillis,
    )

    private companion object {
        const val MAXIMUM_ROWS = 200
    }
}
