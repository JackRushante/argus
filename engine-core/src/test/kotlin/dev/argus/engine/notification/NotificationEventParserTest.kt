package dev.argus.engine.notification

import dev.argus.engine.runtime.TriggerEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NotificationEventParserTest {
    private val parser = NotificationEventParser()

    @Test
    fun `shortcut identity wins over person uri and raw ids never leave the parser`() {
        val parsed = assertNotNull(
            parser.parse(
                snapshot(
                    shortcutId = "raw-shortcut-id",
                    personUris = listOf("tel:+391234567"),
                ),
            ),
        )
        val event = parsed.envelope.event as TriggerEvent.NotificationPosted

        assertEquals("com.whatsapp", event.pkg)
        assertEquals(false, event.isGroup)
        assertEquals("Moglie", event.sender)
        assertEquals("ciao", event.text)
        assertEquals("sbn:private", event.notificationKey)
        assertNotNull(event.conversationId)
        assertEquals(event.conversationId, parsed.observedConversation?.id)
        assertEquals(ConversationIdentitySource.SHORTCUT, parsed.identitySource)
        assertFalse(event.conversationId.orEmpty().contains("raw-shortcut-id"))
        assertFalse(event.conversationId.orEmpty().contains("+391234567"))
        assertFalse(parsed.envelope.id.value.contains("sbn:private"))
        assertFalse(parsed.envelope.id.value.contains("ciao"))
    }

    @Test
    fun `person uri is stable fallback while display names never become identity`() {
        val fromPerson = assertNotNull(
            parser.parse(snapshot(shortcutId = null, personUris = listOf("mailto:a@example.test"))),
        )
        val personEvent = fromPerson.envelope.event as TriggerEvent.NotificationPosted
        assertEquals(ConversationIdentitySource.PERSON_URI, fromPerson.identitySource)
        assertNotNull(personEvent.conversationId)
        assertFalse(personEvent.conversationId.orEmpty().contains("a@example.test"))

        val displayOnly = assertNotNull(
            parser.parse(snapshot(shortcutId = null, personUris = emptyList())),
        )
        val displayEvent = displayOnly.envelope.event as TriggerEvent.NotificationPosted
        assertNull(displayEvent.conversationId)
        assertNull(displayOnly.observedConversation)
        assertNull(displayOnly.identitySource)
    }

    @Test
    fun `group metadata stays tri-state and summaries or unusable payloads are ignored`() {
        val unknown = assertNotNull(parser.parse(snapshot(isGroup = null)))
        assertNull((unknown.envelope.event as TriggerEvent.NotificationPosted).isGroup)

        assertNull(parser.parse(snapshot(isGroupSummary = true)))
        assertNull(parser.parse(snapshot(messageText = "", fallbackText = "")))
        assertNull(parser.parse(snapshot(packageName = "dev.argus")))
    }

    @Test
    fun `event digest is stable for duplicate updates and changes when useful content changes`() {
        val first = assertNotNull(parser.parse(snapshot()))
        val replay = assertNotNull(parser.parse(snapshot()))
        val changed = assertNotNull(parser.parse(snapshot(messageText = "messaggio nuovo")))

        assertEquals(first.envelope.id, replay.envelope.id)
        assertNotEquals(first.envelope.id, changed.envelope.id)
    }

    @Test
    fun `text and display metadata are control-free and bounded`() {
        val parsed = assertNotNull(
            parser.parse(
                snapshot(
                    sender = "  Mo\u0000glie  ",
                    title = "Titolo\u0007",
                    messageText = " x\u0000y " + "z".repeat(5_000),
                ),
            ),
        )
        val event = parsed.envelope.event as TriggerEvent.NotificationPosted

        assertEquals("Moglie", event.sender)
        assertEquals("Titolo", event.title)
        assertEquals(4_096, event.text?.length)
        assertEquals("Moglie", parsed.observedConversation?.displayName)
    }

    @Test
    fun `system notification keys with embedded newlines stay usable`() {
        // Caratterizzazione WhatsApp reale (P1-7): il tag è Base64 con newline finale, quindi
        // la key di sistema contiene un carattere di controllo. È un identificatore opaco del
        // sistema: va accettato e conservato INTATTO per il matching di registry e gateway.
        val whatsappKey = "0|com.whatsapp|1|50V0XCj31HQ4wDorL8SwIw3yYoKJHj2Q6L42gBAmoIw=\n|10390"
        val parsed = assertNotNull(parser.parse(snapshot(notificationKey = whatsappKey)))
        val event = parsed.envelope.event as TriggerEvent.NotificationPosted

        assertEquals(whatsappKey, event.notificationKey)
        assertNotNull(event.conversationId)
        assertNotNull(parsed.observedConversation)
        assertFalse(parsed.envelope.id.value.contains("50V0XCj31HQ4"), "la key resta fuori dall'event ID")
    }

    @Test
    fun `self authored updates never become events`() {
        // L'eco della reply inviata (da Argus o a mano) riappare come update della notifica:
        // "quando X mi scrive" non include mai "quando io scrivo a X" — niente evento, niente loop.
        assertNull(parser.parse(snapshot(latestMessageFromSelf = true)))
    }

    @Test
    fun `event id is stable across cosmetic reposts of the same message`() {
        val first = assertNotNull(
            parser.parse(
                snapshot(title = "Moglie", postedAtMillis = 1_000L, latestMessageTimestampMillis = 500L),
            ),
        )
        val reposted = assertNotNull(
            parser.parse(
                snapshot(
                    title = "2 nuovi messaggi",
                    postedAtMillis = 9_000L,
                    latestMessageTimestampMillis = 500L,
                ),
            ),
        )
        assertEquals(
            first.envelope.id,
            reposted.envelope.id,
            "stesso messaggio ripubblicato ⇒ stesso evento ⇒ SUPPRESSED_DUPLICATE onesto",
        )

        val newMessage = assertNotNull(
            parser.parse(
                snapshot(postedAtMillis = 9_000L, latestMessageTimestampMillis = 600L),
            ),
        )
        assertNotEquals(first.envelope.id, newMessage.envelope.id, "messaggio nuovo ⇒ evento nuovo")

        // Senza timestamp del messaggio (notifiche non-chat) resta il comportamento per postTime.
        val legacyA = assertNotNull(parser.parse(snapshot(postedAtMillis = 1_000L)))
        val legacyB = assertNotNull(parser.parse(snapshot(postedAtMillis = 2_000L)))
        assertNotEquals(legacyA.envelope.id, legacyB.envelope.id)
    }

    private fun snapshot(
        packageName: String = "com.whatsapp",
        notificationKey: String = "sbn:private",
        shortcutId: String? = "shortcut-42",
        personUris: List<String> = listOf("tel:+390000"),
        sender: String? = "Moglie",
        title: String? = "Moglie",
        messageText: String? = "ciao",
        fallbackText: String? = "fallback",
        isGroup: Boolean? = false,
        isGroupSummary: Boolean = false,
        postedAtMillis: Long = 1_000L,
        latestMessageTimestampMillis: Long? = null,
        latestMessageFromSelf: Boolean = false,
    ) = NotificationSnapshot(
        packageName = packageName,
        notificationKey = notificationKey,
        shortcutId = shortcutId,
        personUris = personUris,
        sender = sender,
        title = title,
        messageText = messageText,
        fallbackText = fallbackText,
        isGroup = isGroup,
        isGroupSummary = isGroupSummary,
        postedAtMillis = postedAtMillis,
        latestMessageTimestampMillis = latestMessageTimestampMillis,
        latestMessageFromSelf = latestMessageFromSelf,
    )
}
