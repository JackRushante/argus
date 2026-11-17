package dev.argus.engine.safety

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovedStateContext
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.FeatureGate
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `notification_reply` e le risposte dell'AI su Telegram, Signal e Google Messaggi (0.8). */
class DraftValidatorNotificationReplyTest {
    private val v = DraftValidator(knownTools = setOf("whatsapp_reply", "web.search"))

    private fun errors(d: AutomationDraft, whitelist: Set<String>) =
        v.validate(d, whitelist).filter { it.severity == Severity.ERROR }.map { it.code }

    private fun shortcut(pkg: String, seed: Char = 'a') = "shortcut:$pkg:" + seed.toString().repeat(64)

    private val telegram = "org.telegram.messenger"
    private val telegramChat = shortcut(telegram)

    private fun draft(
        action: Action,
        pkg: String = telegram,
        conversationId: String? = telegramChat,
        isGroup: Boolean? = false,
    ) = AutomationDraft(
        name = "reply",
        trigger = Trigger.Notification(pkg, conversationId = conversationId, isGroup = isGroup),
        actions = listOf(action),
        cooldownMs = 120_000,
    )

    private val aiReply = Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), true)

    @Test
    fun `the feature is switched on`() {
        assertTrue(FeatureGate.ACTION_NOTIFICATION_REPLY.enabled)
    }

    @Test
    fun `a static reply to a whitelisted one-to-one chat of each supported app is valid`() {
        ReplyChannels.NOTIFICATION_REPLY_PACKAGES.forEach { pkg ->
            val chat = shortcut(pkg)
            assertEquals(
                emptyList(),
                errors(draft(Action.NotificationReply("Arrivo"), pkg, chat), setOf(chat)),
                pkg,
            )
        }
    }

    @Test
    fun `a static reply keeps every guarantee of whatsapp_reply`() {
        val reply = Action.NotificationReply("Arrivo")
        assertTrue("target_not_whitelisted" in errors(draft(reply), emptySet()))
        assertTrue("reply_target_group" in errors(draft(reply, isGroup = null), setOf(telegramChat)))
        assertTrue("reply_target_group" in errors(draft(reply, isGroup = true), setOf(telegramChat)))
        assertTrue("reply_needs_conversation_id" in errors(draft(reply, conversationId = null), emptySet()))
        val time = AutomationDraft("x", Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"), listOf(reply))
        assertTrue("reply_target_no_notification" in errors(time, setOf(telegramChat)))
        assertTrue("text_invalid" in errors(draft(Action.NotificationReply(" ")), setOf(telegramChat)))
        assertTrue("text_invalid" in errors(draft(Action.NotificationReply("x".repeat(4_001))), setOf(telegramChat)))
    }

    @Test
    fun `notification_reply refuses whatsapp, unknown apps and samsung messages`() {
        val reply = Action.NotificationReply("Arrivo")
        val whatsapp = draft(reply, "com.whatsapp", "jid:42")
        assertTrue("reply_wrong_package" in errors(whatsapp, setOf("jid:42")))
        val other = shortcut("com.example.chat")
        assertTrue("reply_wrong_package" in errors(draft(reply, "com.example.chat", other), setOf(other)))
        val samsung = shortcut("com.samsung.android.messaging")
        assertTrue(
            "reply_app_unsupported" in errors(draft(reply, "com.samsung.android.messaging", samsung), setOf(samsung)),
        )
    }

    @Test
    fun `whatsapp_reply stays whatsapp only`() {
        assertTrue("reply_wrong_package" in errors(draft(Action.WhatsAppReply("ok")), setOf(telegramChat)))
    }

    @Test
    fun `outside whatsapp the conversation must be the app's own shortcut`() {
        val reply = Action.NotificationReply("Arrivo")
        val person = "person:$telegram:" + "a".repeat(64)
        assertTrue("reply_conversation_unstable" in errors(draft(reply, conversationId = person), setOf(person)))
        // Lo shortcut di un'altra app non identifica una chat Telegram, anche se whitelistato.
        val signalChat = shortcut("org.thoughtcrime.securesms")
        assertTrue("reply_conversation_unstable" in errors(draft(reply, conversationId = signalChat), setOf(signalChat)))
        assertTrue("reply_conversation_unstable" in errors(draft(reply, conversationId = "jid:42"), setOf("jid:42")))
    }

    @Test
    fun `existing whatsapp ids are still accepted as they are`() {
        val whatsapp = draft(aiReply, "com.whatsapp", "jid:42")
        assertEquals(emptyList(), errors(whatsapp, setOf("jid:42")))
        assertEquals(emptyList(), errors(draft(Action.WhatsAppReply("ok"), "com.whatsapp", "jid:42"), setOf("jid:42")))
    }

    @Test
    fun `the ai can reply on the same apps with the same contract`() {
        assertEquals(emptyList(), errors(draft(aiReply), setOf(telegramChat)))
        val signalChat = shortcut("org.thoughtcrime.securesms")
        assertEquals(
            emptyList(),
            errors(draft(aiReply, "org.thoughtcrime.securesms", signalChat), setOf(signalChat)),
        )
        assertTrue("target_not_whitelisted" in errors(draft(aiReply), emptySet()))
        assertTrue("reply_target_group" in errors(draft(aiReply, isGroup = null), setOf(telegramChat)))
        val unbound = aiReply.copy(replyTargetSender = false)
        assertTrue("reply_target_unbound" in errors(draft(unbound), setOf(telegramChat)))
        val shell = aiReply.copy(allowedTools = listOf("whatsapp_reply", "shell.run"))
        assertTrue("tool_forbidden" in errors(draft(shell), setOf(telegramChat)))
        val samsung = shortcut("com.samsung.android.messaging")
        assertTrue(
            "reply_app_unsupported" in errors(draft(aiReply, "com.samsung.android.messaging", samsung), setOf(samsung)),
        )
        assertTrue("reply_wrong_package" in errors(draft(aiReply, "com.example.chat", shortcut("com.example.chat")), emptySet()))
    }

    @Test
    fun `invoke_llm_v2 can reply on the same apps`() {
        val context = ApprovedStateContext(
            query = StateQuery.Builtin("battery"),
            valueType = StateValueType.NUMBER,
            policyVersion = StateQueryPolicy.VERSION,
            integrity = IntegrityLabel.CLEAN,
            confidentiality = ConfidentialityLabel.PRIVATE,
        )
        val v2 = Action.InvokeLlmV2("rispondi", listOf(context), listOf("whatsapp_reply"), true, 60_000)
        assertFalse(errors(draft(v2), setOf(telegramChat)).any { it.startsWith("reply") || it == "target_not_whitelisted" })
        assertTrue("reply_conversation_unstable" in errors(draft(v2, conversationId = "jid:1"), setOf("jid:1")))
    }

    @Test
    fun `only a whitelisted whatsapp chat may start a shell command`() {
        val shell = Action.RunShell("echo hi")
        assertTrue("shell_external_trigger" in errors(draft(shell), setOf(telegramChat)))
        assertTrue("shell_external_trigger" !in errors(draft(shell, "com.whatsapp", "jid:42"), setOf("jid:42")))
        assertFalse(StaticShellSafety.allows(Trigger.Notification(telegram, telegramChat, isGroup = false), setOf(telegramChat)))
    }
}
