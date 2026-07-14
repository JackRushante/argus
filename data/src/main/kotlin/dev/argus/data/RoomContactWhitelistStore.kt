package dev.argus.data

import dev.argus.data.dao.ContactWhitelistDao
import dev.argus.data.entities.WhitelistedContactEntity
import dev.argus.engine.brain.ContactWhitelistStore
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.safety.ApprovalWhitelistProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomContactWhitelistStore(
    private val dao: ContactWhitelistDao,
) : ContactWhitelistStore, ApprovalWhitelistProvider {
    override suspend fun all(): List<WhitelistedContact> = dao.all().map(::toDomain)

    override fun observeAll(): Flow<List<WhitelistedContact>> =
        dao.observeAll().map { contacts -> contacts.map(::toDomain) }

    override suspend fun upsert(contact: WhitelistedContact) {
        dao.upsert(
            WhitelistedContactEntity(
                conversationId = contact.id,
                displayName = contact.displayName,
            ),
        )
    }

    override suspend fun remove(conversationId: String) {
        require(conversationId.isNotBlank()) { "Conversation ID vuoto" }
        dao.remove(conversationId)
    }

    override suspend fun currentConversationIds(): Set<String> =
        dao.conversationIds().toSet()

    private fun toDomain(entity: WhitelistedContactEntity) = WhitelistedContact(
        displayName = entity.displayName,
        id = entity.conversationId,
    )
}
