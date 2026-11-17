package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.CapabilityRequirements
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.NotificationActionOp
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

/** Ricontrollo allo scatto di `notification_reply`, delle risposte AI sulle altre app e di `notification_action`. */
class RevalidatingFirePolicyNotificationTest {
    private val telegram = "org.telegram.messenger"
    private val chat = "shortcut:$telegram:" + "a".repeat(64)

    private fun automation(trigger: Trigger, actions: List<Action>): Automation {
        val value = Automation(
            id = AutomationId("a1"),
            name = "test",
            createdBy = CreatedBy.LLM,
            status = AutomationStatus.ARMED,
            trigger = trigger,
            actions = actions,
        )
        return value.copy(approvalFingerprint = ApprovalFingerprints.of(value))
    }

    private fun policy(automation: Automation, whitelist: Set<String> = setOf(chat)): FirePolicy {
        val snapshot = FirePolicySnapshot(
            knownTools = setOf("whatsapp_reply"),
            availableCapabilities = automation.requiredCapabilities,
            whitelistedConversationIds = whitelist,
        )
        return RevalidatingFirePolicy(FirePolicySnapshotProvider { snapshot })
    }

    private fun posted(
        pkg: String = telegram,
        conversationId: String? = chat,
        isGroup: Boolean? = false,
        key: String? = "0|$pkg|7|null|10",
    ) = TriggerEvent.NotificationPosted(pkg, conversationId, isGroup = isGroup, text = "ciao", notificationKey = key)

    private suspend fun decide(automation: Automation, event: TriggerEvent, whitelist: Set<String> = setOf(chat)) =
        policy(automation, whitelist).evaluate(automation, event)

    private val telegramTrigger = Trigger.Notification(telegram, conversationId = chat, isGroup = false)

    @Test
    fun `a static reply fires only on the live event of the whitelisted one-to-one chat`() = runTest {
        val rule = automation(telegramTrigger, listOf(Action.NotificationReply("Arrivo")))
        assertEquals(setOf(CapabilityIds.TRIGGER_NOTIFICATION, CapabilityIds.ACTION_NOTIFICATION_REPLY), CapabilityRequirements.derive(rule))
        assertEquals(FirePolicyDecision.Allow, decide(rule, posted()))

        fun blocked(decision: FirePolicyDecision, code: String) {
            val block = assertIs<FirePolicyDecision.Block>(decision)
            assertEquals(code, block.code)
            assertFalse(block.needsReview)
        }
        blocked(decide(rule, posted(isGroup = null)), "reply_event_unverified")
        blocked(decide(rule, posted(conversationId = "shortcut:$telegram:" + "b".repeat(64))), "reply_event_unverified")
        blocked(decide(rule, posted(pkg = "org.telegram.messenger.web")), "reply_event_unverified")
        blocked(decide(rule, posted(key = null)), "reply_notification_unavailable")
        blocked(decide(rule, TriggerEvent.ManualFired(rule.id, requireNotNull(rule.approvalFingerprint))), "reply_event_unverified")
    }

    @Test
    fun `a revoked whitelist entry sends the rule back to review`() = runTest {
        val rule = automation(telegramTrigger, listOf(Action.NotificationReply("Arrivo")))
        val block = assertIs<FirePolicyDecision.Block>(decide(rule, posted(), whitelist = emptySet()))
        assertEquals("validation_failed", block.code)
    }

    @Test
    fun `the ai reply on telegram is checked like the whatsapp one`() = runTest {
        val rule = automation(
            telegramTrigger,
            listOf(Action.InvokeLlm("rispondi", listOf("notification"), listOf("whatsapp_reply"), true)),
        )
        assertEquals(FirePolicyDecision.Allow, decide(rule, posted()))
        assertIs<FirePolicyDecision.Block>(decide(rule, posted(isGroup = true)))
        assertIs<FirePolicyDecision.Block>(decide(rule, posted(conversationId = null)))
    }

    @Test
    fun `a whatsapp reply never goes out on a telegram event even with the same conversation id`() = runTest {
        val whatsapp = automation(
            Trigger.Notification("com.whatsapp", conversationId = "jid:42", isGroup = false),
            listOf(Action.WhatsAppReply("ok")),
        )
        val block = assertIs<FirePolicyDecision.Block>(
            decide(whatsapp, posted(pkg = telegram, conversationId = "jid:42"), whitelist = setOf("jid:42")),
        )
        assertEquals("reply_event_unverified", block.code)
        assertEquals(
            FirePolicyDecision.Allow,
            decide(whatsapp, posted(pkg = "com.whatsapp", conversationId = "jid:42"), whitelist = setOf("jid:42")),
        )
    }

    @Test
    fun `notification actions need the live notification of the approved app`() = runTest {
        val mail = Trigger.Notification("com.example.mail")
        listOf(
            Action.NotificationAction(NotificationActionOp.DISMISS),
            Action.NotificationAction(NotificationActionOp.SNOOZE, snoozeMinutes = 30),
            Action.NotificationAction(NotificationActionOp.CLICK, label = "Archivia"),
        ).forEach { action ->
            val rule = automation(mail, listOf(action))
            assertEquals(FirePolicyDecision.Allow, decide(rule, posted(pkg = "com.example.mail", conversationId = null, isGroup = null)))
            val other = assertIs<FirePolicyDecision.Block>(decide(rule, posted(pkg = "com.example.other")))
            assertEquals("notification_action_event_unverified", other.code)
            assertFalse(other.needsReview)
            val noKey = assertIs<FirePolicyDecision.Block>(decide(rule, posted(pkg = "com.example.mail", key = " ")))
            assertEquals("notification_action_event_unverified", noKey.code)
            val manual = assertIs<FirePolicyDecision.Block>(decide(rule, TriggerEvent.ManualFired(rule.id, requireNotNull(rule.approvalFingerprint))))
            assertEquals("notification_action_event_unverified", manual.code)
        }
    }
}
