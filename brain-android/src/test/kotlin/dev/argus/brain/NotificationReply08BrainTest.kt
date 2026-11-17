package dev.argus.brain

import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** 0.8: le risposte dell'AI su Telegram, Signal e Google Messaggi, lato brain. */
class NotificationReply08BrainTest {
    private fun context(pkg: String, isGroup: Boolean? = false) = FireContext(
        event = TriggerEvent.NotificationPosted(pkg, "shortcut:$pkg:" + "a".repeat(64), sender = "Luca", text = "ci sei?", isGroup = isGroup),
        state = DeviceState(),
        automationId = AutomationId("a"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("e"),
        executionId = ExecutionId("x"),
        actionIndex = 0,
    )

    @Test
    fun `a generated reply is accepted on the supported apps only`() {
        listOf("com.whatsapp", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.google.android.apps.messaging")
            .forEach { pkg -> assertEquals(pkg, AgentMessageSupport.requireReplyNotification(context(pkg)).pkg) }
        listOf("com.samsung.android.messaging", "com.example.chat").forEach { pkg ->
            assertFailsWith<TransportException> { AgentMessageSupport.requireReplyNotification(context(pkg)) }
        }
        assertFailsWith<TransportException> {
            AgentMessageSupport.requireReplyNotification(context("org.telegram.messenger", isGroup = null))
        }
    }

    @Test
    fun `the model is told the app outside whatsapp, and whatsapp stays as it was`() {
        val telegram = context("org.telegram.messenger").event as TriggerEvent.NotificationPosted
        assertEquals("Message received on Telegram from Luca: ci sei?", AgentMessageSupport.actUserText(telegram, emptyList()))
        val whatsapp = context("com.whatsapp").event as TriggerEvent.NotificationPosted
        assertEquals("Message received from Luca: ci sei?", AgentMessageSupport.actUserText(whatsapp, emptyList()))
    }

    @Test
    fun `contacts outside whatsapp carry their app and package, never their id`() {
        val hash = "b".repeat(64)
        assertEquals(
            "Luca (Telegram, pkg org.telegram.messenger)",
            ContactAliasMapper.modelDisplayName(WhitelistedContact("Luca", "shortcut:org.telegram.messenger:$hash")),
        )
        assertEquals("Moglie", ContactAliasMapper.modelDisplayName(WhitelistedContact("Moglie", "shortcut:com.whatsapp:$hash")))
        assertEquals("Manuale", ContactAliasMapper.modelDisplayName(WhitelistedContact("Manuale", "jid:42")))
    }
}
