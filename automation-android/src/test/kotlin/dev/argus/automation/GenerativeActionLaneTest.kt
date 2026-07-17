package dev.argus.automation

import dev.argus.automation.notification.NotificationReplyDelivery
import dev.argus.automation.notification.NotificationReplyGateway
import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovedStateContext
import dev.argus.engine.model.ApprovalFingerprints
import dev.argus.engine.model.Automation
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.AutomationStatus
import dev.argus.engine.model.CreatedBy
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.ActionJournalOutcome
import dev.argus.engine.runtime.AutomationStore
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.ExecutionStatus
import dev.argus.engine.runtime.FireClaimRequest
import dev.argus.engine.runtime.FireClaimResult
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.FirePolicy
import dev.argus.engine.runtime.FirePolicyDecision
import dev.argus.engine.runtime.SubmittedActionCompletion
import dev.argus.engine.runtime.SubmittedActionJournal
import dev.argus.engine.runtime.SubmittedActionState
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GenerativeActionLaneTest {
    @Test
    fun `worker waits for submitted handshake then sends and completes exactly once`() = runTest {
        val fixture = fixture()

        assertTrue(fixture.lane.trySubmit(fixture.context, fixture.action))
        runCurrent()
        assertEquals(0, fixture.brain.calls)
        assertEquals(0, fixture.gateway.calls)

        fixture.journal.ready()
        runCurrent()

        assertEquals(1, fixture.brain.calls)
        assertEquals(1, fixture.gateway.calls)
        assertEquals(ActionJournalOutcome.SUCCEEDED, fixture.journal.completions.single().outcome)

        assertTrue(fixture.lane.trySubmit(fixture.context, fixture.action))
        runCurrent()
        assertEquals(1, fixture.brain.calls, "una redelivery terminale non può reinviare")
    }

    @Test
    fun `policy revoke while brain is running blocks final send`() = runTest {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val fixture = fixture(
            act = {
                started.complete(Unit)
                release.await()
                ActResult("risposta", null)
            },
        )
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()
        assertTrue(started.isCompleted)

        fixture.policy.decision = FirePolicyDecision.Block(
            code = "reply_event_unverified",
            needsReview = false,
        )
        release.complete(Unit)
        runCurrent()

        assertEquals(0, fixture.gateway.calls)
        fixture.journal.completions.single().also { completion ->
            assertEquals(ActionJournalOutcome.FAILED, completion.outcome)
            assertEquals("reply_event_unverified", completion.errorCode)
        }
    }

    @Test
    fun `rule edit or deletion while brain is running blocks final send`() = runTest {
        for (delete in listOf(false, true)) {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val fixture = fixture(
                act = {
                    started.complete(Unit)
                    release.await()
                    ActResult("risposta", null)
                },
            )
            fixture.lane.trySubmit(fixture.context, fixture.action)
            fixture.journal.ready()
            runCurrent()
            assertTrue(started.isCompleted)

            fixture.store.current = if (delete) {
                null
            } else {
                approvedAutomation(fixture.action.copy(goal = "goal modificato"))
            }
            release.complete(Unit)
            runCurrent()

            assertEquals(0, fixture.gateway.calls)
            assertEquals(
                if (delete) "rule_missing" else "approval_changed",
                fixture.journal.completions.single().errorCode,
            )
        }
    }

    @Test
    fun `queue is bounded while the single consumer waits for journal`() = runTest {
        val fixture = fixture(capacity = 1)

        assertTrue(fixture.lane.trySubmit(fixture.context, fixture.action))
        runCurrent()
        assertTrue(fixture.lane.trySubmit(fixture.context, fixture.action))
        assertFalse(fixture.lane.trySubmit(fixture.context, fixture.action))
        assertEquals(0, fixture.brain.calls)
    }

    @Test
    fun `expired channel becomes deferred only after sink confirms durable storage`() = runTest {
        var deferredText: String? = null
        val fixture = fixture(
            delivery = NotificationReplyDelivery.Failed("channel_expired"),
            deferred = DeferredReplySink { _, text ->
                deferredText = text
                true
            },
        )
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals("risposta", deferredText)
        fixture.journal.completions.single().also { completion ->
            assertEquals(ActionJournalOutcome.DEFERRED, completion.outcome)
            assertEquals("channel_expired", completion.errorCode)
        }
    }

    @Test
    fun `unavailable reply channel is defer eligible like an expired pending intent`() = runTest {
        // Remove/update della notifica o registry perso durante la latenza Hermes: è il caso E13
        // più comune e deve offrire la stessa consegna manuale del canale scaduto.
        var deferredText: String? = null
        val fixture = fixture(
            delivery = NotificationReplyDelivery.Failed("reply_channel_unavailable"),
            deferred = DeferredReplySink { _, text ->
                deferredText = text
                true
            },
        )
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals("risposta", deferredText)
        fixture.journal.completions.single().also { completion ->
            assertEquals(ActionJournalOutcome.DEFERRED, completion.outcome)
            assertEquals("reply_channel_unavailable", completion.errorCode)
        }
    }

    @Test
    fun `defer ineligible failures and failed sink storage stay failed`() = runTest {
        val untrusted = fixture(
            delivery = NotificationReplyDelivery.Failed("reply_package_untrusted"),
            deferred = DeferredReplySink { _, _ -> true },
        )
        untrusted.lane.trySubmit(untrusted.context, untrusted.action)
        untrusted.journal.ready()
        runCurrent()
        untrusted.journal.completions.single().also { completion ->
            assertEquals(ActionJournalOutcome.FAILED, completion.outcome)
            assertEquals("reply_package_untrusted", completion.errorCode)
        }

        val sinkDown = fixture(
            delivery = NotificationReplyDelivery.Failed("reply_channel_unavailable"),
            deferred = DeferredReplySink { _, _ -> false },
        )
        sinkDown.lane.trySubmit(sinkDown.context, sinkDown.action)
        sinkDown.journal.ready()
        runCurrent()
        sinkDown.journal.completions.single().also { completion ->
            assertEquals(ActionJournalOutcome.FAILED, completion.outcome)
            assertEquals("reply_channel_unavailable", completion.errorCode)
        }
    }

    @Test
    fun `unsupported generative contract fails before contacting brain`() = runTest {
        val invalid = action().copy(allowedTools = listOf("state.read"))
        val fixture = fixture(action = invalid, automation = approvedAutomation(invalid))
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals(0, fixture.brain.calls)
        assertEquals("action_contract_invalid", fixture.journal.completions.single().errorCode)
    }

    @Test
    fun `optional web search tool is an accepted generative contract`() = runTest {
        // Fase 1 web tool: allowedTools = [reply, web.search] deve passare validContract e arrivare
        // al brain, esattamente come il profilo reply-only.
        val webAction = action().copy(allowedTools = listOf("whatsapp_reply", "web.search"))
        val fixture = fixture(action = webAction, automation = approvedAutomation(webAction))
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals(1, fixture.brain.calls)
        assertEquals(1, fixture.gateway.calls)
        assertEquals(ActionJournalOutcome.SUCCEEDED, fixture.journal.completions.single().outcome)
    }

    @Test
    fun `brain timeout fails without touching reply channel`() = runTest {
        val timed = action().copy(timeoutMs = 1_000)
        val fixture = fixture(
            action = timed,
            automation = approvedAutomation(timed),
            act = { awaitCancellation() },
        )
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()

        assertEquals(0, fixture.gateway.calls)
        assertEquals("act_timeout", fixture.journal.completions.single().errorCode)
    }

    @Test
    fun `v2 lane freezes and submits only an available explicitly approved query`() = runTest {
        val query = StateQuery.DumpsysField("battery", "voltage")
        val action = Action.InvokeLlmV2(
            goal = "rispondi considerando il voltaggio",
            stateContext = listOf(
                ApprovedStateContext(
                    query = query,
                    valueType = StateValueType.NUMBER,
                    policyVersion = StateQueryPolicy.VERSION,
                    integrity = IntegrityLabel.CLEAN,
                    confidentiality = ConfidentialityLabel.SECRET,
                ),
            ),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
            timeoutMs = 60_000,
        )
        val automation = approvedAutomation(action)
        val store = MutableAutomationStore(automation)
        val journal = FakeSubmittedJournal()
        val brain = FakeBrain { ActResult("risposta v2", null) }
        val gateway = FakeReplyGateway(NotificationReplyDelivery.Sent)
        val context = context(
            automation,
            action,
            DeviceState(queryValues = mapOf(query.canonicalId to "4200")),
        )
        val lane = AndroidGenerativeLane(
            scope = backgroundScope,
            journal = journal,
            automations = store,
            firePolicy = MutableFirePolicy(),
            brain = brain,
            replies = gateway,
            deferredReplies = DeferredReplySink { _, _ -> false },
        )

        assertTrue(lane.trySubmit(context, action))
        journal.ready()
        runCurrent()

        assertEquals(1, brain.calls)
        assertEquals(action, brain.lastV2)
        assertEquals(1, gateway.calls)
        assertEquals(ActionJournalOutcome.SUCCEEDED, journal.completions.single().outcome)
    }

    @Test
    fun `budget hard block risolve la completion come soppressione budget`() = runTest {
        val fixture = fixture(act = { ActResult(null, "budget_exceeded") })
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals(0, fixture.gateway.calls)
        fixture.journal.completions.single().also { completion ->
            assertEquals(ActionJournalOutcome.FAILED, completion.outcome)
            assertEquals("budget_exceeded", completion.errorCode)
            assertEquals(ExecutionStatus.SUPPRESSED_BUDGET, completion.suppressedStatus)
        }
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        action: Action.InvokeLlm = action(),
        automation: Automation = approvedAutomation(action),
        capacity: Int = 8,
        delivery: NotificationReplyDelivery = NotificationReplyDelivery.Sent,
        deferred: DeferredReplySink = DeferredReplySink { _, _ -> false },
        act: suspend () -> ActResult = { ActResult("risposta", null) },
    ): Fixture {
        val store = MutableAutomationStore(automation)
        val journal = FakeSubmittedJournal()
        val brain = FakeBrain(act)
        val gateway = FakeReplyGateway(delivery)
        val policy = MutableFirePolicy()
        val context = context(automation, action)
        val lane = AndroidGenerativeLane(
            scope = backgroundScope,
            journal = journal,
            automations = store,
            firePolicy = policy,
            brain = brain,
            replies = gateway,
            deferredReplies = deferred,
            capacity = capacity,
            submissionHandshakeTimeoutMillis = 5_000,
            nowMillis = { 2_000L },
        )
        return Fixture(lane, action, context, store, journal, brain, gateway, policy)
    }

    private data class Fixture(
        val lane: AndroidGenerativeLane,
        val action: Action.InvokeLlm,
        val context: FireContext,
        val store: MutableAutomationStore,
        val journal: FakeSubmittedJournal,
        val brain: FakeBrain,
        val gateway: FakeReplyGateway,
        val policy: MutableFirePolicy,
    )

    private class FakeSubmittedJournal : SubmittedActionJournal {
        val state = MutableStateFlow<SubmittedActionState?>(
            SubmittedActionState(ExecutionStatus.RUNNING, null),
        )
        val completions = mutableListOf<SubmittedActionCompletion>()

        fun ready() {
            state.value = SubmittedActionState(
                ExecutionStatus.SUBMITTED,
                ActionJournalOutcome.SUBMITTED,
            )
        }

        override fun observeSubmission(
            executionId: ExecutionId,
            actionIndex: Int,
        ): Flow<SubmittedActionState?> = state

        override suspend fun resolveSubmitted(completion: SubmittedActionCompletion): Boolean {
            if (state.value?.ready != true) return false
            completions += completion
            val status = when (completion.outcome) {
                ActionJournalOutcome.SUCCEEDED -> ExecutionStatus.SUCCEEDED
                ActionJournalOutcome.FAILED -> ExecutionStatus.FAILED
                ActionJournalOutcome.DEFERRED -> ExecutionStatus.DEFERRED
                ActionJournalOutcome.SUBMITTED -> error("completion ancora submitted")
            }
            state.value = SubmittedActionState(status, completion.outcome)
            return true
        }
    }

    private class FakeBrain(
        private val actResult: suspend () -> ActResult,
    ) : Brain {
        var calls = 0
        var lastV2: Action.InvokeLlmV2? = null

        override suspend fun compile(
            nl: String,
            manifest: CapabilityManifest,
            state: DeviceState,
        ): CompileResult = error("non usato")

        override suspend fun act(
            context: FireContext,
            goal: String,
            contextSources: List<String>,
            allowedTools: List<String>,
        ): ActResult {
            calls++
            return actResult()
        }

        override suspend fun actV2(
            context: FireContext,
            action: Action.InvokeLlmV2,
        ): ActResult {
            calls++
            lastV2 = action
            return actResult()
        }
    }

    private class FakeReplyGateway(
        private val delivery: NotificationReplyDelivery,
    ) : NotificationReplyGateway {
        var calls = 0

        override fun send(
            request: dev.argus.automation.notification.NotificationReplyRequest,
        ): NotificationReplyDelivery {
            calls++
            return delivery
        }
    }

    private class MutableFirePolicy : FirePolicy {
        var decision: FirePolicyDecision = FirePolicyDecision.Allow
        override suspend fun evaluate(automation: Automation, event: TriggerEvent) = decision
    }

    private class MutableAutomationStore(var current: Automation?) : AutomationStore {
        override suspend fun get(id: AutomationId): Automation? = current?.takeIf { it.id == id }
        override suspend fun all(): List<Automation> = listOfNotNull(current)
        override fun observeAll(): Flow<List<Automation>> = flowOf(listOfNotNull(current))
        override suspend fun armed(): List<Automation> = listOfNotNull(current).filter {
            it.status == AutomationStatus.ARMED && it.enabled
        }

        override suspend fun delete(id: AutomationId) { current = null }
        override suspend fun disable(id: AutomationId) {
            current = current?.copy(status = AutomationStatus.DISABLED, enabled = false)
        }

        override suspend fun disableIfApproved(
            id: AutomationId,
            fingerprint: dev.argus.engine.model.ApprovalFingerprint,
        ): Boolean = false

        override suspend fun enableIfApproved(
            id: AutomationId,
            fingerprint: dev.argus.engine.model.ApprovalFingerprint,
        ): Boolean = false

        override suspend fun markNeedsReview(id: AutomationId) = Unit
        override suspend fun markNeedsReviewIfApproved(
            id: AutomationId,
            fingerprint: dev.argus.engine.model.ApprovalFingerprint,
        ): Boolean = false

        override suspend fun claimFire(request: FireClaimRequest): FireClaimResult =
            error("non usato")

        override suspend fun recordFired(id: AutomationId, atMillis: Long) = Unit
        override suspend fun lastFiredAt(id: AutomationId): Long? = null
    }

    private companion object {
        const val CONVERSATION_ID = "shortcut:com.whatsapp:hash"

        fun action() = Action.InvokeLlm(
            goal = "rispondi",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
            replyTargetSender = true,
            timeoutMs = 60_000,
        )

        fun approvedAutomation(action: Action): Automation {
            val unsigned = Automation(
                id = AutomationId("automation-1"),
                name = "Reply",
                createdBy = CreatedBy.USER,
                status = AutomationStatus.ARMED,
                trigger = Trigger.Notification(
                    pkg = "com.whatsapp",
                    conversationId = CONVERSATION_ID,
                    isGroup = false,
                ),
                actions = listOf(action),
                enabled = true,
                cooldownMs = 60_000,
            )
            return unsigned.copy(approvalFingerprint = ApprovalFingerprints.of(unsigned))
        }

        fun context(
            automation: Automation,
            action: Action,
            state: DeviceState = DeviceState(),
        ): FireContext = FireContext(
            event = TriggerEvent.NotificationPosted(
                pkg = "com.whatsapp",
                conversationId = CONVERSATION_ID,
                sender = "Contatto",
                text = "messaggio",
                isGroup = false,
                notificationKey = "sbn:1",
            ),
            state = state,
            automationId = automation.id,
            approvalFingerprint = requireNotNull(automation.approvalFingerprint),
            eventId = TriggerEventId("notification:event-1"),
            executionId = ExecutionId("execution-1"),
            actionIndex = automation.actions.indexOf(action).coerceAtLeast(0),
        )
    }
}
