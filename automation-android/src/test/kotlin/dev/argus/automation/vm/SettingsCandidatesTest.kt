package dev.argus.automation.vm

import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.notification.ObservedConversation
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsCandidatesTest {
    @Test
    fun `picker offers only whatsapp one-to-one conversations not yet whitelisted`() {
        val observed = listOf(
            conversation("shortcut:com.whatsapp:aa", "Moglie", isGroup = false),
            conversation("shortcut:com.whatsapp:bb", "Gruppo famiglia", isGroup = true),
            conversation("shortcut:com.whatsapp:cc", "Collega", isGroup = null),
            conversation(
                "shortcut:com.example.chat:dd",
                "Altra app",
                isGroup = false,
                packageName = "com.example.chat",
            ),
            conversation("shortcut:com.whatsapp:ee", "Mamma", isGroup = false),
        )
        val whitelisted = listOf(WhitelistedContact("Mamma", "shortcut:com.whatsapp:ee"))

        val candidates = observedWhitelistCandidates(observed, whitelisted)

        assertEquals(1, candidates.size, "gruppi, tri-state, altre app e già whitelistati esclusi")
        assertEquals("Moglie", candidates.single().displayName)
        assertEquals("shortcut:com.whatsapp:aa", candidates.single().conversationId)
    }

    @Test
    fun `picker also offers telegram signal and google messages chats with their app`() {
        val hash = "a".repeat(64)
        val observed = listOf(
            conversation("shortcut:org.telegram.messenger:$hash", "Luca", false, "org.telegram.messenger"),
            conversation("person:org.telegram.messenger:$hash", "Senza shortcut", false, "org.telegram.messenger"),
            conversation("shortcut:org.thoughtcrime.securesms:$hash", "Anna", false, "org.thoughtcrime.securesms"),
            conversation("shortcut:com.google.android.apps.messaging:$hash", "Papà", false, "com.google.android.apps.messaging"),
            conversation("shortcut:com.samsung.android.messaging:$hash", "Samsung", false, "com.samsung.android.messaging"),
            conversation("shortcut:com.whatsapp:aa", "Moglie", isGroup = false),
        )

        val candidates = observedWhitelistCandidates(observed, emptyList())

        assertEquals(
            listOf("Luca" to "Telegram", "Anna" to "Signal", "Papà" to "Google Messages", "Moglie" to "WhatsApp"),
            candidates.map { it.displayName to it.app },
        )
        assertEquals("Telegram", whitelistApp("shortcut:org.telegram.messenger:$hash"))
        assertEquals(null, whitelistApp("jid:42"))
    }

    @Test
    fun `picker keeps recency order and honours the limit`() {
        val observed = (1..30).map { index ->
            conversation("shortcut:com.whatsapp:$index", "Contatto $index", isGroup = false)
        }

        val candidates = observedWhitelistCandidates(observed, emptyList(), limit = 5)

        assertEquals(5, candidates.size)
        assertEquals("Contatto 1", candidates.first().displayName)
    }

    private fun conversation(
        id: String,
        displayName: String,
        isGroup: Boolean?,
        packageName: String = "com.whatsapp",
    ) = ObservedConversation(
        id = id,
        packageName = packageName,
        displayName = displayName,
        isGroup = isGroup,
        lastSeenAtMillis = 1_000,
    )
}
