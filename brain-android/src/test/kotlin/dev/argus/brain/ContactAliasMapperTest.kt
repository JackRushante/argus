package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class ContactAliasMapperTest {
    private val contacts = listOf(
        WhitelistedContact("Moglie", "shortcut:com.whatsapp:moglie"),
        WhitelistedContact("Ottica", "shortcut:com.whatsapp:ottica"),
    )

    private fun draft(conversationId: String?) = AutomationDraft(
        name = "x",
        trigger = Trigger.Notification("com.whatsapp", conversationId = conversationId, isGroup = false),
        actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
    )

    @Test
    fun `aliases are stable positions in the whitelist order`() {
        assertEquals("contact_1", ContactAliasMapper.aliasFor(0))
        assertEquals("contact_2", ContactAliasMapper.aliasFor(1))
        assertEquals(
            mapOf("contact_1" to "shortcut:com.whatsapp:moglie", "contact_2" to "shortcut:com.whatsapp:ottica"),
            ContactAliasMapper.aliasMap(contacts),
        )
    }

    @Test
    fun `an alias in the draft becomes the real conversation id`() {
        val restored = ContactAliasMapper.restore(draft("contact_2"), contacts)

        assertEquals(
            "shortcut:com.whatsapp:ottica",
            assertIs<Trigger.Notification>(restored.trigger).conversationId,
        )
    }

    @Test
    fun `an unknown alias stays as it is so the validator rejects it`() {
        val restored = ContactAliasMapper.restore(draft("contact_7"), contacts)

        assertEquals("contact_7", assertIs<Trigger.Notification>(restored.trigger).conversationId)
    }

    @Test
    fun `a real id invented by the model is not rewritten`() {
        val restored = ContactAliasMapper.restore(draft("shortcut:com.whatsapp:moglie"), contacts)

        assertEquals(
            "shortcut:com.whatsapp:moglie",
            assertIs<Trigger.Notification>(restored.trigger).conversationId,
        )
    }

    @Test
    fun `drafts without a notification conversation are returned untouched`() {
        val time = AutomationDraft(
            name = "x",
            trigger = Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"),
            actions = listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        assertSame(time, ContactAliasMapper.restore(time, contacts))
        val noConversation = draft(null)
        assertNull(assertIs<Trigger.Notification>(ContactAliasMapper.restore(noConversation, contacts).trigger).conversationId)
    }

    @Test
    fun `restoring a compile result keeps reply error and usage`() {
        val manifest = CapabilityManifest(
            deviceModel = "t", androidVersion = 16, androidApi = 36, shizukuAvailable = false,
            grantedPermissions = emptyList(), availableTools = emptyList(), unavailableTools = emptyMap(),
            whitelistedContacts = contacts,
        )
        val result = CompileResult("ok", draft("contact_1"), null)

        val restored = ContactAliasMapper.restore(result, manifest)

        assertEquals("ok", restored.reply)
        assertEquals(
            "shortcut:com.whatsapp:moglie",
            assertIs<Trigger.Notification>(assertNotNull(restored.draft).trigger).conversationId,
        )
        val noDraft = CompileResult("boh", null, "clarification_required")
        assertSame(noDraft, ContactAliasMapper.restore(noDraft, manifest))
    }
}
