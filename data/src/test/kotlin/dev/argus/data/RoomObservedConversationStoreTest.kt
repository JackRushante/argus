package dev.argus.data

import androidx.test.core.app.ApplicationProvider
import dev.argus.engine.notification.ObservedConversation
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
class RoomObservedConversationStoreTest {
    private lateinit var database: ArgusDatabase
    private lateinit var store: RoomObservedConversationStore

    @Before
    fun setUp() {
        database = ArgusDatabase.inMemory(ApplicationProvider.getApplicationContext())
        store = RoomObservedConversationStore(database.observedConversationDao())
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `record upserts metadata without message text and exposes newest first`() = runTest {
        store.record(conversation("shortcut:com.whatsapp:a", "Anna", false, 1_000))
        store.record(conversation("shortcut:com.whatsapp:b", "Gruppo", true, 2_000))
        store.record(conversation("shortcut:com.whatsapp:a", "Anna aggiornata", false, 3_000))
        store.record(conversation("shortcut:com.whatsapp:a", "Evento stale", null, 1_500))

        val recent = store.observeRecent().first()
        assertEquals(2, recent.size)
        assertEquals(listOf("Anna aggiornata", "Gruppo"), recent.map { it.displayName })
        assertEquals(listOf(3_000L, 2_000L), recent.map { it.lastSeenAtMillis })
        assertEquals(recent, store.recent())
    }

    @Test
    fun `store remains bounded and validates caller limits`() = runTest {
        repeat(205) { index ->
            store.record(
                conversation(
                    id = "shortcut:com.whatsapp:${index.toString().padStart(3, '0')}",
                    displayName = "Contatto $index",
                    isGroup = false,
                    atMillis = index.toLong(),
                ),
            )
        }

        val recent = store.recent()
        assertEquals(200, recent.size)
        assertEquals(204L, recent.first().lastSeenAtMillis)
        assertEquals(5L, recent.last().lastSeenAtMillis)
        assertFailsWith<IllegalArgumentException> { store.recent(0) }
        assertFailsWith<IllegalArgumentException> { store.observeRecent(201) }
    }

    private fun conversation(
        id: String,
        displayName: String,
        isGroup: Boolean?,
        atMillis: Long,
    ) = ObservedConversation(
        id = id,
        packageName = "com.whatsapp",
        displayName = displayName,
        isGroup = isGroup,
        lastSeenAtMillis = atMillis,
    )
}
