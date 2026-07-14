package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.brain.WhitelistedContact
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

@RunWith(RobolectricTestRunner::class)
class RoomContactWhitelistStoreTest {
    private lateinit var database: ArgusDatabase
    private lateinit var store: RoomContactWhitelistStore

    @Before
    fun setUp() {
        database = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomContactWhitelistStore(database.contactWhitelistDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `whitelist is ordered observable upsertable and shared with approval policy`() = runTest {
        store.upsert(WhitelistedContact("Zio", "jid:2"))
        store.upsert(WhitelistedContact("Anna", "jid:1"))
        store.upsert(WhitelistedContact("Anna aggiornata", "jid:1"))

        assertEquals(
            listOf("Anna aggiornata", "Zio"),
            store.observeAll().first().map { it.displayName },
        )
        assertEquals(setOf("jid:1", "jid:2"), store.currentConversationIds())

        store.remove("jid:2")
        assertEquals(listOf("jid:1"), store.all().map { it.id })
    }

    @Test
    fun `invalid policy identifiers are rejected before Room`() = runTest {
        assertFailsWith<IllegalArgumentException> { WhitelistedContact("", "jid:1") }
        assertFailsWith<IllegalArgumentException> { WhitelistedContact("Anna", "line\nbreak") }
        assertFailsWith<IllegalArgumentException> { store.remove(" ") }
    }
}
