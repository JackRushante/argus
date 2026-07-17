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
import dev.argus.engine.model.GenerativeDeliverMode
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
    fun `consumed one-shot immediate rule still completes despite being disabled`() = runTest {
        // Race #60: il trigger immediate si DISABILITA appena dispatchato (ArmedAutomationRegistrar
        // consuma il one-shot). L'azione generativa era già accodata quando la regola era armata:
        // fingerprint+azione invariati → deve COMPLETARE, non "rule_inactive".
        val action = notificationAction(allowedTools = listOf("web.search"))
        val armed = approvedAutomation(action, Trigger.Immediate)
        val consumed = armed.copy(status = AutomationStatus.DISABLED, enabled = false)
        val event = TriggerEvent.ImmediateFired(armed.id, requireNotNull(armed.approvalFingerprint))
        val fixture = fixture(
            action = action,
            automation = consumed,
            context = context(armed, action, event = event),
        )
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals(1, fixture.brain.calls)
        assertEquals(1, fixture.notifier.calls.size)
        assertEquals(ActionJournalOutcome.SUCCEEDED, fixture.journal.completions.single().outcome)
    }

    @Test
    fun `consumed one-shot time-at rule still completes despite being disabled`() = runTest {
        val action = notificationAction(allowedTools = listOf("web.search"))
        val armed = approvedAutomation(action, Trigger.Time(at = "2026-07-15T08:00", tz = "Europe/Rome"))
        val consumed = armed.copy(status = AutomationStatus.DISABLED, enabled = false)
        val event = TriggerEvent.TimeFired(armed.id, requireNotNull(armed.approvalFingerprint))
        val fixture = fixture(
            action = action,
            automation = consumed,
            context = context(armed, action, event = event),
        )
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals(1, fixture.brain.calls)
        assertEquals(1, fixture.notifier.calls.size)
        assertEquals(ActionJournalOutcome.SUCCEEDED, fixture.journal.completions.single().outcome)
    }

    @Test
    fun `consumed one-shot relative afterMs rule still completes despite being disabled`() = runTest {
        // afterMs è one-shot come at: il dispatch lo disabilita, ma l'azione generativa già accodata
        // (fingerprint+azione invariati) DEVE completare, non "rule_inactive".
        val action = notificationAction(allowedTools = listOf("web.search"))
        val armed = approvedAutomation(action, Trigger.Time(afterMs = 120_000, tz = "Europe/Rome"))
        val consumed = armed.copy(status = AutomationStatus.DISABLED, enabled = false)
        val event = TriggerEvent.TimeFired(armed.id, requireNotNull(armed.approvalFingerprint))
        val fixture = fixture(
            action = action,
            automation = consumed,
            context = context(armed, action, event = event),
        )
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals(1, fixture.brain.calls)
        assertEquals(1, fixture.notifier.calls.size)
        assertEquals(ActionJournalOutcome.SUCCEEDED, fixture.journal.completions.single().outcome)
    }

    @Test
    fun `disabled recurring cron rule stays inactive`() = runTest {
        // Una ricorrente (time con cron, senza at) DISABILITATA dall'utente resta "rule_inactive":
        // la tolleranza vale solo per i one-shot consumati, mai per le regole spente a mano.
        val action = notificationAction(allowedTools = listOf("web.search"))
        val armed = approvedAutomation(action, Trigger.Time(cron = "0 8 * * *", tz = "Europe/Rome"))
        val disabled = armed.copy(status = AutomationStatus.DISABLED, enabled = false)
        val event = TriggerEvent.TimeFired(armed.id, requireNotNull(armed.approvalFingerprint))
        val fixture = fixture(
            action = action,
            automation = disabled,
            context = context(armed, action, event = event),
        )
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals(0, fixture.brain.calls)
        assertTrue(fixture.notifier.calls.isEmpty())
        fixture.journal.completions.single().also { completion ->
            assertEquals(ActionJournalOutcome.FAILED, completion.outcome)
            assertEquals("rule_inactive", completion.errorCode)
        }
    }

    @Test
    fun `consumed one-shot with changed fingerprint still fails as approval changed`() = runTest {
        // La tolleranza one-shot NON bypassa il fingerprint: un edit non ri-approvato (qui il nome)
        // rende current.approvalFingerprint != of(current) → "approval_changed", mai consegna.
        val action = notificationAction(allowedTools = listOf("web.search"))
        val armed = approvedAutomation(action, Trigger.Immediate)
        val edited = armed.copy(
            name = "Rinominata",
            status = AutomationStatus.DISABLED,
            enabled = false,
        )
        val event = TriggerEvent.ImmediateFired(armed.id, requireNotNull(armed.approvalFingerprint))
        val fixture = fixture(
            action = action,
            automation = edited,
            context = context(armed, action, event = event),
        )
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals(0, fixture.brain.calls)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertEquals("approval_changed", fixture.journal.completions.single().errorCode)
    }

    @Test
    fun `deleted one-shot rule fails as rule missing`() = runTest {
        val action = notificationAction(allowedTools = listOf("web.search"))
        val armed = approvedAutomation(action, Trigger.Immediate)
        val event = TriggerEvent.ImmediateFired(armed.id, requireNotNull(armed.approvalFingerprint))
        val fixture = fixture(
            action = action,
            automation = armed,
            context = context(armed, action, event = event),
        )
        fixture.store.current = null
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals(0, fixture.brain.calls)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertEquals("rule_missing", fixture.journal.completions.single().errorCode)
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
            notifier = RecordingNotifier(),
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

    @Test
    fun `local notification deliver posts the generated text and never touches the reply channel`() = runTest {
        val action = notificationAction()
        val fixture = fixture(action = action, automation = approvedAutomation(action))
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals(1, fixture.brain.calls)
        assertEquals(0, fixture.gateway.calls, "il sink notifica non usa il canale reply")
        fixture.notifier.calls.single().also { shown ->
            assertEquals("Cambio EUR/USD", shown.title)
            assertEquals("risposta", shown.text)
            assertEquals(fixture.context.executionId, shown.context.executionId)
        }
        assertEquals(ActionJournalOutcome.SUCCEEDED, fixture.journal.completions.single().outcome)
    }

    @Test
    fun `local notification deliver fires from a non notification trigger`() = runTest {
        // A differenza del reply (che esige TriggerEvent.NotificationPosted) il sink notifica posta
        // il testo da un trigger qualsiasi: è il caso reale "tra 2 min mandami il cambio euro/usd".
        val action = notificationAction(allowedTools = listOf("web.search"))
        val automation = approvedAutomation(action)
        val timeContext = context(
            automation,
            action,
            event = TriggerEvent.TimeFired(
                automation.id,
                requireNotNull(automation.approvalFingerprint),
            ),
        )
        val fixture = fixture(action = action, automation = automation, context = timeContext)
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals(1, fixture.brain.calls)
        assertEquals(0, fixture.gateway.calls)
        assertEquals(1, fixture.notifier.calls.size)
        assertEquals("risposta", fixture.notifier.calls.single().text)
        assertEquals(ActionJournalOutcome.SUCCEEDED, fixture.journal.completions.single().outcome)
    }

    @Test
    fun `local notification deliver fails when the notifier throws`() = runTest {
        val action = notificationAction()
        val fixture = fixture(
            action = action,
            automation = approvedAutomation(action),
            notifier = RecordingNotifier(onShow = { error("notification_permission_unavailable") }),
        )
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals(0, fixture.gateway.calls)
        assertTrue(fixture.notifier.calls.isEmpty())
        fixture.journal.completions.single().also { completion ->
            assertEquals(ActionJournalOutcome.FAILED, completion.outcome)
            assertEquals("notify_failed", completion.errorCode)
        }
    }

    @Test
    fun `local notification deliver rejects a whatsapp reply tool before contacting brain`() = runTest {
        // isNotificationToolset vieta whatsapp_reply: il contratto è invalido e la lane non chiama il
        // brain né posta nulla (specchia validateInvokeLlmNotificationDeliver).
        val action = notificationAction(allowedTools = listOf("whatsapp_reply"))
        val fixture = fixture(action = action, automation = approvedAutomation(action))
        fixture.lane.trySubmit(fixture.context, fixture.action)
        fixture.journal.ready()
        runCurrent()

        assertEquals(0, fixture.brain.calls)
        assertEquals(0, fixture.gateway.calls)
        assertTrue(fixture.notifier.calls.isEmpty())
        assertEquals("action_contract_invalid", fixture.journal.completions.single().errorCode)
    }

    private fun kotlinx.coroutines.test.TestScope.fixture(
        action: Action.InvokeLlm = action(),
        automation: Automation = approvedAutomation(action),
        capacity: Int = 8,
        delivery: NotificationReplyDelivery = NotificationReplyDelivery.Sent,
        deferred: DeferredReplySink = DeferredReplySink { _, _ -> false },
        notifier: RecordingNotifier = RecordingNotifier(),
        context: FireContext = context(automation, action),
        act: suspend () -> ActResult = { ActResult("risposta", null) },
    ): Fixture {
        val store = MutableAutomationStore(automation)
        val journal = FakeSubmittedJournal()
        val brain = FakeBrain(act)
        val gateway = FakeReplyGateway(delivery)
        val policy = MutableFirePolicy()
        val lane = AndroidGenerativeLane(
            scope = backgroundScope,
            journal = journal,
            automations = store,
            firePolicy = policy,
            brain = brain,
            replies = gateway,
            deferredReplies = deferred,
            notifier = notifier,
            capacity = capacity,
            submissionHandshakeTimeoutMillis = 5_000,
            nowMillis = { 2_000L },
        )
        return Fixture(lane, action, context, store, journal, brain, gateway, policy, notifier)
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
        val notifier: RecordingNotifier,
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

    /** Notifier fake che registra ogni show; se `onShow` è impostato, lo lancia PRIMA di registrare
     *  (simula un permesso notifiche revocato → IllegalStateException del notifier reale). */
    private class RecordingNotifier(
        private val onShow: (() -> Unit)? = null,
    ) : AutomationNotifier {
        data class Shown(val title: String, val text: String, val context: FireContext)

        val calls = mutableListOf<Shown>()

        override suspend fun show(title: String, text: String, context: FireContext) {
            onShow?.invoke()
            calls += Shown(title, text, context)
        }
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

        fun notificationAction(
            allowedTools: List<String> = emptyList(),
            contextSources: List<String> = emptyList(),
            notificationTitle: String? = "Cambio EUR/USD",
        ) = Action.InvokeLlm(
            goal = "genera il cambio euro verso usd",
            contextSources = contextSources,
            allowedTools = allowedTools,
            replyTargetSender = false,
            timeoutMs = 60_000,
            deliver = GenerativeDeliverMode.LOCAL_NOTIFICATION,
            notificationTitle = notificationTitle,
        )

        fun approvedAutomation(
            action: Action,
            trigger: Trigger = Trigger.Notification(
                pkg = "com.whatsapp",
                conversationId = CONVERSATION_ID,
                isGroup = false,
            ),
        ): Automation {
            val unsigned = Automation(
                id = AutomationId("automation-1"),
                name = "Reply",
                createdBy = CreatedBy.USER,
                status = AutomationStatus.ARMED,
                trigger = trigger,
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
            event: TriggerEvent = TriggerEvent.NotificationPosted(
                pkg = "com.whatsapp",
                conversationId = CONVERSATION_ID,
                sender = "Contatto",
                text = "messaggio",
                isGroup = false,
                notificationKey = "sbn:1",
            ),
        ): FireContext = FireContext(
            event = event,
            state = state,
            automationId = automation.id,
            approvalFingerprint = requireNotNull(automation.approvalFingerprint),
            eventId = TriggerEventId("notification:event-1"),
            executionId = ExecutionId("execution-1"),
            actionIndex = automation.actions.indexOf(action).coerceAtLeast(0),
        )
    }
}
