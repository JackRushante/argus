package dev.argus.engine.runtime

import dev.argus.engine.model.Action
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.CapabilityIds
import dev.argus.engine.model.Condition
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class RevalidatingFirePolicyTest {
    private fun automation(
        trigger: Trigger,
        actions: List<Action>,
        conditions: Condition? = null,
    ): Automation {
        val value = Automation(
            id = AutomationId("a1"),
            name = "test",
            createdBy = CreatedBy.LLM,
            status = AutomationStatus.ARMED,
            trigger = trigger,
            actions = actions,
            conditions = conditions,
        )
        return value.copy(approvalFingerprint = ApprovalFingerprints.of(value))
    }

    private fun snapshot(
        knownTools: Set<String> = setOf("whatsapp_reply", "state.read"),
        availableCapabilities: Set<String>,
        whitelist: Set<String> = emptySet(),
    ) = FirePolicySnapshot(knownTools, availableCapabilities, whitelist)

    private fun policy(value: FirePolicySnapshot) =
        RevalidatingFirePolicy(FirePolicySnapshotProvider { value })

    private fun timeEvent(automation: Automation) = TriggerEvent.TimeFired(
        automation.id,
        requireNotNull(automation.approvalFingerprint),
    )

    @Test
    fun `valid deterministic automation is allowed when capability is live`() = runTest {
        val automation = automation(
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        val result = policy(
            snapshot(
                availableCapabilities = setOf(
                    CapabilityIds.TRIGGER_TIME,
                    ActionCapabilities.SET_DND,
                ),
            ),
        )
            .evaluate(automation, timeEvent(automation))
        assertEquals(FirePolicyDecision.Allow, result)
    }

    @Test
    fun `policy revalidates forbidden generative tools after approval`() = runTest {
        val automation = automation(
            Trigger.Notification("com.whatsapp", conversationId = "jid:42", isGroup = false),
            listOf(Action.InvokeLlm("reply", listOf("notification"), listOf("whatsapp_reply", "shell.run"), true)),
        )
        val result = policy(
            snapshot(
                knownTools = setOf("whatsapp_reply", "shell.run"),
                availableCapabilities = setOf(ActionCapabilities.INVOKE_LLM, "whatsapp_reply", "shell.run"),
                whitelist = setOf("jid:42"),
            ),
        ).evaluate(
            automation,
            TriggerEvent.NotificationPosted(
                "com.whatsapp", "jid:42", isGroup = false, notificationKey = "sbn:1",
            ),
        )
        val block = assertIs<FirePolicyDecision.Block>(result)
        assertTrue(block.needsReview)
        assertEquals("validation_failed", block.code)
    }

    @Test
    fun `revoked whitelist fails closed at fire time`() = runTest {
        val automation = automation(
            Trigger.Notification("com.whatsapp", conversationId = "jid:42", isGroup = false),
            listOf(Action.WhatsAppReply("ok")),
        )
        val result = policy(
            snapshot(availableCapabilities = setOf(ActionCapabilities.WHATSAPP_REPLY), whitelist = emptySet()),
        ).evaluate(
            automation,
            TriggerEvent.NotificationPosted(
                "com.whatsapp", "jid:42", isGroup = false, notificationKey = "sbn:1",
            ),
        )
        val block = assertIs<FirePolicyDecision.Block>(result)
        assertTrue(block.needsReview)
        assertEquals("validation_failed", block.code)
    }

    @Test
    fun `revoked action capability pauses the automation`() = runTest {
        val automation = automation(
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        val block = assertIs<FirePolicyDecision.Block>(
            policy(snapshot(availableCapabilities = setOf(CapabilityIds.TRIGGER_TIME)))
                .evaluate(automation, timeEvent(automation)),
        )
        assertTrue(block.needsReview)
        assertEquals("capability_unavailable", block.code)
    }

    @Test
    fun `temporary capability outage blocks without invalidating approval`() = runTest {
        val automation = automation(
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        val block = assertIs<FirePolicyDecision.Block>(
            policy(
                FirePolicySnapshot(
                    knownTools = emptySet(),
                    availableCapabilities = setOf(CapabilityIds.TRIGGER_TIME),
                    whitelistedConversationIds = emptySet(),
                    transientlyUnavailableCapabilities = setOf(ActionCapabilities.SET_DND),
                ),
            ).evaluate(automation, timeEvent(automation)),
        )
        assertEquals("capability_unavailable", block.code)
        assertTrue(!block.needsReview)
    }

    @Test
    fun `notification trigger capability is revalidated even for a deterministic action`() = runTest {
        val automation = automation(
            Trigger.Notification("com.example.mail"),
            listOf(Action.ShowNotification("Argus", "Nuova mail")),
        )
        val block = assertIs<FirePolicyDecision.Block>(
            policy(
                snapshot(
                    availableCapabilities = setOf(
                        CapabilityIds.TRIGGER_TIME,
                        ActionCapabilities.SHOW_NOTIFICATION,
                    ),
                ),
            )
                .evaluate(
                    automation,
                    TriggerEvent.NotificationPosted("com.example.mail"),
                ),
        )
        assertEquals("capability_unavailable", block.code)
        assertTrue(block.needsReview)
    }

    @Test
    fun `state capability used by a condition is revalidated`() = runTest {
        val automation = automation(
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.ShowNotification("Argus", "A casa")),
            Condition.LocationIn(45.46, 9.19, 100.0),
        )
        val block = assertIs<FirePolicyDecision.Block>(
            policy(
                snapshot(
                    availableCapabilities = setOf(
                        CapabilityIds.TRIGGER_TIME,
                        ActionCapabilities.SHOW_NOTIFICATION,
                    ),
                ),
            )
                .evaluate(automation, timeEvent(automation)),
        )
        assertEquals("capability_unavailable", block.code)
        assertTrue(block.needsReview)
    }

    @Test
    fun `edit after approval invalidates the fingerprint`() = runTest {
        val approved = automation(
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        val editedWithoutApproval = approved.copy(actions = listOf(Action.SetDnd(DndMode.TOTAL)))
        val block = assertIs<FirePolicyDecision.Block>(
            policy(snapshot(availableCapabilities = setOf(ActionCapabilities.SET_DND)))
                .evaluate(editedWithoutApproval, timeEvent(approved)),
        )
        assertEquals("approval_fingerprint_mismatch", block.code)
        assertTrue(block.needsReview)
    }

    @Test
    fun `reply requires verified one-to-one metadata and live notification key`() = runTest {
        val automation = automation(
            Trigger.Notification("com.whatsapp", conversationId = "jid:42", isGroup = false),
            listOf(Action.WhatsAppReply("ok")),
        )
        val policy = policy(
            snapshot(
                availableCapabilities = setOf(
                    CapabilityIds.TRIGGER_NOTIFICATION,
                    ActionCapabilities.WHATSAPP_REPLY,
                ),
                whitelist = setOf("jid:42"),
            ),
        )

        val unknownGroup = policy.evaluate(
            automation,
            TriggerEvent.NotificationPosted("com.whatsapp", "jid:42", isGroup = null, notificationKey = "sbn:1"),
        )
        assertEquals("reply_event_unverified", assertIs<FirePolicyDecision.Block>(unknownGroup).code)

        val missingKey = policy.evaluate(
            automation,
            TriggerEvent.NotificationPosted("com.whatsapp", "jid:42", isGroup = false, notificationKey = null),
        )
        val transient = assertIs<FirePolicyDecision.Block>(missingKey)
        assertEquals("reply_notification_unavailable", transient.code)
        assertTrue(!transient.needsReview)
    }

    @Test
    fun `approved static shell is allowed for a trusted trigger`() = runTest {
        val automation = automation(
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.RunShell("id")),
        )
        assertEquals(
            FirePolicyDecision.Allow,
            policy(
                snapshot(
                    availableCapabilities = setOf(
                        CapabilityIds.TRIGGER_TIME,
                        ActionCapabilities.RUN_SHELL,
                    ),
                ),
            )
                .evaluate(automation, timeEvent(automation)),
        )
    }

    @Test
    fun `approved static shell is allowed only for the matching live whitelisted whatsapp chat`() = runTest {
        val automation = automation(
            Trigger.Notification("com.whatsapp", conversationId = "jid:42", isGroup = false),
            listOf(Action.RunShell("id")),
        )
        val capabilities = setOf(
            CapabilityIds.TRIGGER_NOTIFICATION,
            ActionCapabilities.RUN_SHELL,
        )
        val liveEvent = TriggerEvent.NotificationPosted(
            pkg = "com.whatsapp",
            conversationId = "jid:42",
            isGroup = false,
            notificationKey = "sbn:1",
        )

        assertEquals(
            FirePolicyDecision.Allow,
            policy(snapshot(availableCapabilities = capabilities, whitelist = setOf("jid:42")))
                .evaluate(automation, liveEvent),
        )

        val revoked = assertIs<FirePolicyDecision.Block>(
            policy(snapshot(availableCapabilities = capabilities, whitelist = emptySet()))
                .evaluate(automation, liveEvent),
        )
        assertEquals("shell_external_trigger", revoked.code)
        assertTrue(revoked.needsReview)
    }

    @Test
    fun `static shell rejects a live whatsapp identity different from the approved chat`() = runTest {
        val automation = automation(
            Trigger.Notification("com.whatsapp", conversationId = "jid:42", isGroup = false),
            listOf(Action.RunShell("id")),
        )
        val block = assertIs<FirePolicyDecision.Block>(
            policy(
                snapshot(
                    availableCapabilities = setOf(
                        CapabilityIds.TRIGGER_NOTIFICATION,
                        ActionCapabilities.RUN_SHELL,
                    ),
                    whitelist = setOf("jid:42", "jid:other"),
                ),
            ).evaluate(
                automation,
                TriggerEvent.NotificationPosted(
                    pkg = "com.whatsapp",
                    conversationId = "jid:other",
                    isGroup = false,
                    notificationKey = "sbn:2",
                ),
            ),
        )
        assertEquals("shell_external_trigger", block.code)
        assertTrue(!block.needsReview)
    }

    @Test
    fun `shell stays fail closed for message triggers and mismatched live events`() = runTest {
        val external = automation(
            Trigger.PhoneState(dev.argus.engine.model.PhoneEvent.SMS_RECEIVED),
            listOf(Action.RunShell("id")),
        )
        val structural = assertIs<FirePolicyDecision.Block>(
            policy(
                snapshot(
                    availableCapabilities = setOf(
                        CapabilityIds.TRIGGER_PHONE_SMS,
                        ActionCapabilities.RUN_SHELL,
                    ),
                ),
            ).evaluate(
                external,
                TriggerEvent.PhoneStateChanged(
                    dev.argus.engine.model.PhoneEvent.SMS_RECEIVED,
                    number = "+39001",
                    smsText = "untrusted",
                ),
            ),
        )
        assertEquals("shell_external_trigger", structural.code)
        assertTrue(structural.needsReview)

        val trusted = automation(
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.RunShell("id")),
        )
        val mismatchedEvent = assertIs<FirePolicyDecision.Block>(
            policy(
                snapshot(
                    availableCapabilities = setOf(
                        CapabilityIds.TRIGGER_TIME,
                        ActionCapabilities.RUN_SHELL,
                    ),
                ),
            )
                .evaluate(
                    trusted,
                    TriggerEvent.NotificationPosted("com.example", text = "untrusted"),
                ),
        )
        assertEquals("shell_external_trigger", mismatchedEvent.code)
        assertTrue(!mismatchedEvent.needsReview)
    }

    @Test
    fun `stored capability requirements cannot omit a derived capability`() = runTest {
        val approved = automation(
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        val unsigned = approved.copy(
            approvalFingerprint = null,
            requiredCapabilities = setOf(CapabilityIds.TRIGGER_TIME),
        )
        val inconsistent = unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))

        val block = assertIs<FirePolicyDecision.Block>(
            policy(
                snapshot(
                    availableCapabilities = setOf(
                        CapabilityIds.TRIGGER_TIME,
                        ActionCapabilities.SET_DND,
                    ),
                ),
            ).evaluate(inconsistent, timeEvent(inconsistent)),
        )
        assertEquals("capability_requirements_mismatch", block.code)
        assertTrue(block.needsReview)
    }

    @Test
    fun `snapshot failure is fail closed and coroutine cancellation propagates`() = runTest {
        val automation = automation(
            Trigger.Time(cron = "0 23 * * *", tz = "Europe/Rome"),
            listOf(Action.SetDnd(DndMode.PRIORITY)),
        )
        val failed = RevalidatingFirePolicy(FirePolicySnapshotProvider { error("probe down") })
        val block = assertIs<FirePolicyDecision.Block>(
            failed.evaluate(automation, timeEvent(automation)),
        )
        assertEquals("capability_snapshot_unavailable", block.code)
        assertTrue(!block.needsReview)

        val cancelled = RevalidatingFirePolicy(
            FirePolicySnapshotProvider { throw CancellationException("stop") },
        )
        assertFailsWith<CancellationException> {
            cancelled.evaluate(automation, timeEvent(automation))
        }
    }
}
