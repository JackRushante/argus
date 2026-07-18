package dev.argus.engine.brain

import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * S12: [TurnUsage] vive in engine-core e [ActResult] la trasporta come ULTIMO parametro con default.
 * Questi test inchiodano la retrocompatibilità posizionale, l'XOR invariato e la garanzia
 * strutturale che [ActResult] NON diventi `@Serializable` (nessun impatto sui golden hash v1).
 */
class ActResultUsageTest {

    @Test fun `usage defaults to null on positional two-arg construction`() {
        val ok = ActResult("ok", null)
        assertNull(ok.usage)
        val err = ActResult(null, "empty_response")
        assertNull(err.usage)
    }

    @Test fun `usage rides along without weakening the xor`() {
        val withUsage = ActResult(text = null, metaError = "empty_response", usage = TurnUsage(1, 2))
        assertEquals(1L, withUsage.usage?.inputTokens)
        assertEquals(2L, withUsage.usage?.outputTokens)

        val okWithUsage = ActResult(text = "ciao", metaError = null, usage = TurnUsage(5, 6, model = "m"))
        assertEquals("m", okWithUsage.usage?.model)

        assertFailsWith<IllegalArgumentException> { ActResult("x", "err", TurnUsage(1, 2)) }
        assertFailsWith<IllegalArgumentException> { ActResult(null, null, TurnUsage(1, 2)) }
    }

    @Test fun `turn usage rejects negative counts`() {
        assertFailsWith<IllegalArgumentException> { TurnUsage(-1, 0) }
        assertFailsWith<IllegalArgumentException> { TurnUsage(0, -1) }
        assertFailsWith<IllegalArgumentException> { TurnUsage(0, 0, cachedInputTokens = -1) }
    }

    @Test fun `turn usage rejects impossible remote accounting values`() {
        assertFailsWith<IllegalArgumentException> {
            TurnUsage(TurnUsage.MAX_TOKENS_PER_TURN + 1, 0)
        }
        assertFailsWith<IllegalArgumentException> {
            TurnUsage(10, 0, cachedInputTokens = 11)
        }
        assertFailsWith<IllegalArgumentException> {
            TurnUsage(1, 1, model = "m\nsecret")
        }
        assertFailsWith<IllegalArgumentException> {
            TurnUsage(1, 1, model = "m".repeat(TurnUsage.MAX_MODEL_CHARS + 1))
        }
    }

    @Test fun `act v2 default still fails closed with no usage`() = runBlocking {
        val brain = object : Brain {
            override suspend fun compile(nl: String, manifest: CapabilityManifest, state: DeviceState) =
                CompileResult("r", null, null)

            override suspend fun act(
                context: FireContext,
                goal: String,
                contextSources: List<String>,
                allowedTools: List<String>,
            ) = ActResult("ok", null)
        }
        val action = Action.InvokeLlmV2(
            goal = "g",
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
            timeoutMs = 30_000L,
            stateContext = emptyList(),
        )
        val result = brain.actV2(fireContext(), action)
        assertEquals("act_v2_unsupported", result.metaError)
        assertNull(result.text)
        assertNull(result.usage)
    }

    @Test fun `v1 approval fingerprint stays byte-for-byte stable after adding usage`() {
        // ActResult NON è @Serializable e non entra nel fingerprint: aggiungere `usage` non deve
        // perturbare in alcun modo la firma golden v1 di un'automazione di riferimento.
        val automation = Automation(
            id = AutomationId("v1-notification"),
            name = "v1-notification",
            createdBy = CreatedBy.USER,
            status = AutomationStatus.PENDING_APPROVAL,
            trigger = Trigger.Notification("com.whatsapp", "conversation:fixture", isGroup = false),
            actions = listOf(Action.ShowNotification("fixture", "fixture")),
            conditions = null,
            enabled = false,
            priority = 3,
            cooldownMs = 12_345,
        )
        assertEquals(
            "be12b5a12129996a258d14f5e8bcdcef21ab2d7dfc7ac7df697a9bfa33a90910",
            ApprovalFingerprints.of(automation).value,
        )
    }

    private fun fireContext() = FireContext(
        event = TriggerEvent.NotificationPosted(pkg = "com.whatsapp", text = "ciao", isGroup = false),
        state = DeviceState(),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )
}
