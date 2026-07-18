package dev.argus.automation.budget

import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderId
import dev.argus.data.UsageWindows
import dev.argus.data.dao.ProviderTokensAggregate
import dev.argus.data.dao.ProviderUsageAggregate
import dev.argus.data.dao.UsageDao
import dev.argus.data.entities.UsageEventEntity
import dev.argus.data.entities.UsageEventKind
import dev.argus.data.entities.UsageEventOutcome
import dev.argus.brain.ProviderCatalog
import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.brain.TurnUsage
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditKind
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val TEST_MANIFEST = CapabilityManifest(
    deviceModel = "test",
    androidVersion = 16,
    androidApi = 36,
    shizukuAvailable = false,
    grantedPermissions = emptyList(),
    availableTools = emptyList(),
    unavailableTools = emptyMap(),
    whitelistedContacts = emptyList(),
)

class MeteredBrainTest {
    private val openai = ProviderConfig(ProviderId.OPENAI, "https://api.openai.com/v1", "gpt-5.5")

    @Test
    fun `ok chiama il delegate e registra evento OK con token costo e pricing version`() = runTest {
        val usage = TurnUsage(inputTokens = 1_000_000, outputTokens = 100_000, model = "gpt-5.5")
        val delegate = FakeBrain(actResult = { ActResult("risposta", null, usage) })
        val dao = RecordingUsageDao()
        val brain = metered(delegate, FakePolicy(), dao, config = openai)

        val result = brain.actV2(context(), invokeV2())

        assertEquals("risposta", result.text)
        assertEquals(1, delegate.calls)
        val event = dao.events.single()
        assertEquals(UsageEventOutcome.OK, event.outcome)
        assertEquals(UsageEventKind.ACT_V2, event.kind)
        assertEquals("openai", event.providerId)
        assertEquals(1_000_000L, event.tokensIn)
        assertEquals(100_000L, event.tokensOut)
        assertEquals(2_250_000L, event.costMicros)
        assertEquals(ProviderCatalog.PRICING_VERSION, event.pricingVersion)
    }

    @Test
    fun `compile registra i token reali quando il transport li riporta`() = runTest {
        // S15: il bridge Hermes ora allega l'usage anche in compile — niente più N/D cieco.
        val hermes = ProviderConfig(ProviderId.HERMES, "https://hermes", null)
        val usage = TurnUsage(inputTokens = 2_785, outputTokens = 5, model = "gpt-5.5")
        val delegate = FakeBrain(compileResult = { CompileResult("ok", null, null, usage) })
        val dao = RecordingUsageDao()
        val brain = metered(delegate, FakePolicy(), dao, config = hermes)

        brain.compile("crea", TEST_MANIFEST, DeviceState())

        val event = dao.events.single()
        assertEquals(UsageEventKind.COMPILE, event.kind)
        assertEquals(UsageEventOutcome.OK, event.outcome)
        assertEquals(2_785L, event.tokensIn)
        assertEquals(5L, event.tokensOut)
        assertEquals("gpt-5.5", event.model)
    }

    @Test
    fun `meta error del delegate registra evento ERROR senza costo`() = runTest {
        val delegate = FakeBrain(actResult = { ActResult(null, "brain_failed") })
        val dao = RecordingUsageDao()
        val brain = metered(delegate, FakePolicy(), dao, config = openai)

        brain.actV2(context(), invokeV2())

        val event = dao.events.single()
        assertEquals(UsageEventOutcome.ERROR, event.outcome)
        assertNull(event.costMicros)
        assertNull(event.pricingVersion)
    }

    @Test
    fun `usage assente registra token null non zero`() = runTest {
        val hermes = ProviderConfig(ProviderId.HERMES, "https://hermes", null)
        val delegate = FakeBrain(actResult = { ActResult("risposta", null, usage = null) })
        val dao = RecordingUsageDao()
        val brain = metered(delegate, FakePolicy(), dao, config = hermes)

        brain.actV2(context(), invokeV2())

        val event = dao.events.single()
        assertEquals(UsageEventOutcome.OK, event.outcome)
        assertNull(event.tokensIn)
        assertNull(event.tokensOut)
        assertNull(event.costMicros)
        assertNull(event.pricingVersion)
    }

    @Test
    fun `hard su actv2 non chiama il delegate e ritorna budget_exceeded`() = runTest {
        val config = ProviderConfig(ProviderId.OPENAI, "https://api.openai.com/v1", null)
        val delegate = FakeBrain()
        val dao = RecordingUsageDao()
        val audit = RecordingAuditSink()
        val brain = metered(
            delegate,
            FakePolicy(BudgetVerdict.HardExceeded(TrippedLimit(LimitWindow.HOUR, BudgetScope.Global))),
            dao,
            config = config,
            audit = audit,
        )

        val result = brain.actV2(context(), invokeV2())

        assertEquals(0, delegate.calls)
        assertNull(result.text)
        assertEquals("budget_exceeded", result.metaError)
        val event = dao.events.single()
        assertEquals(UsageEventOutcome.BLOCKED_BUDGET, event.outcome)
        assertEquals("unknown", event.model)
        val record = audit.records.single()
        assertEquals(AuditKind.SUPPRESSED_BUDGET, record.kind)
        assertEquals(AutomationId("automation-1"), record.automationId)
        assertEquals(TriggerEventId("event-1"), record.eventId)
        assertEquals(ExecutionId("execution-1"), record.executionId)
        assertEquals("hour:global", record.detail)
    }

    @Test
    fun `hard su compile ritorna reply e metaError senza audit`() = runTest {
        val delegate = FakeBrain()
        val dao = RecordingUsageDao()
        val audit = RecordingAuditSink()
        val brain = metered(
            delegate,
            FakePolicy(BudgetVerdict.HardExceeded(TrippedLimit(LimitWindow.MONTH_COST, BudgetScope.Global))),
            dao,
            config = openai,
            audit = audit,
        )

        val result = brain.compile("crea", TEST_MANIFEST, DeviceState())

        assertEquals("budget_exceeded", result.metaError)
        assertNull(result.draft)
        assertTrue(audit.records.isEmpty())
        val event = dao.events.single()
        assertEquals(UsageEventOutcome.BLOCKED_BUDGET, event.outcome)
        assertEquals(UsageEventKind.COMPILE, event.kind)
    }

    @Test
    fun `soft chiama il delegate e avvisa una sola volta per finestra`() = runTest {
        var currentNow = 1_700_000_000_000L
        val delegate = FakeBrain(actResult = { ActResult("risposta", null) })
        val dao = RecordingUsageDao()
        val alerts = RecordingAlerts()
        val brain = MeteredBrain(
            delegate = delegate,
            policy = FakePolicy(BudgetVerdict.SoftExceeded(TrippedLimit(LimitWindow.HOUR, BudgetScope.Global))),
            usage = dao,
            selectedConfig = { openai },
            audit = RecordingAuditSink(),
            alerts = alerts,
            nowMillis = { currentNow },
        )

        brain.actV2(context(), invokeV2())
        brain.actV2(context(), invokeV2())
        assertEquals(1, alerts.calls)

        currentNow += UsageWindows.HOUR_MILLIS
        brain.actV2(context(), invokeV2())
        assertEquals(2, alerts.calls)
        assertEquals(3, delegate.calls)
    }

    @Test
    fun `policy che lancia fa fail open`() = runTest {
        val delegate = FakeBrain(actResult = { ActResult("risposta", null) })
        val dao = RecordingUsageDao()
        val brain = metered(delegate, FakePolicy(fail = true), dao, config = openai)

        val result = brain.actV2(context(), invokeV2())

        assertEquals("risposta", result.text)
        assertEquals(1, delegate.calls)
        assertEquals(UsageEventOutcome.OK, dao.events.single().outcome)
    }

    @Test
    fun `insert usage che lancia non rompe la risposta`() = runTest {
        val delegate = FakeBrain(actResult = { ActResult("risposta", null) })
        val dao = RecordingUsageDao(failInsert = true)
        val brain = metered(delegate, FakePolicy(), dao, config = openai)

        val result = brain.actV2(context(), invokeV2())

        assertEquals("risposta", result.text)
    }

    @Test
    fun `cancellation si propaga senza eventi`() = runTest {
        val delegate = FakeBrain(actResult = { throw CancellationException("cancel") })
        val dao = RecordingUsageDao()
        val brain = metered(delegate, FakePolicy(), dao, config = openai)

        assertFailsWith<CancellationException> { brain.actV2(context(), invokeV2()) }
        assertTrue(dao.events.isEmpty())
    }

    @Test
    fun `concurrent calls cannot both cross a one-call hard limit`() = runTest {
        val dao = RecordingUsageDao()
        val delegate = FakeBrain(
            actResult = {
                // Lascia alla seconda coroutine il tempo di arrivare al pre-check. Senza il gate
                // atomico entrambe vedono zero eventi e contattano il provider.
                delay(1)
                ActResult("risposta", null)
            },
        )
        val policy = object : BudgetPolicy(NoopUsageDaoForTest, NoopSettingsForTest) {
            override suspend fun check(providerId: ProviderId, nowMillis: Long): BudgetVerdict =
                if (dao.events.any { it.outcome != UsageEventOutcome.BLOCKED_BUDGET }) {
                    BudgetVerdict.HardExceeded(TrippedLimit(LimitWindow.HOUR, BudgetScope.Global))
                } else {
                    BudgetVerdict.Ok
                }
        }
        val brain = metered(delegate, policy, dao, config = openai)

        val first = async { brain.actV2(context(), invokeV2()) }
        val second = async { brain.actV2(context(), invokeV2()) }
        val results = awaitAll(first, second)

        assertEquals(1, delegate.calls)
        assertEquals(1, results.count { it.text == "risposta" })
        assertEquals(1, results.count { it.metaError == BudgetMeta.BUDGET_EXCEEDED })
        assertEquals(
            listOf(UsageEventOutcome.OK, UsageEventOutcome.BLOCKED_BUDGET),
            dao.events.map { it.outcome },
        )
    }

    // --- helpers -------------------------------------------------------------

    private fun metered(
        delegate: Brain,
        policy: BudgetPolicy,
        dao: RecordingUsageDao,
        config: ProviderConfig,
        audit: AuditSink = RecordingAuditSink(),
        alerts: BudgetAlerts = RecordingAlerts(),
    ): MeteredBrain = MeteredBrain(
        delegate = delegate,
        policy = policy,
        usage = dao,
        selectedConfig = { config },
        audit = audit,
        alerts = alerts,
        nowMillis = { 1_700_000_000_000L },
    )

    private fun invokeV2(): Action.InvokeLlmV2 = Action.InvokeLlmV2(
        goal = "rispondi",
        stateContext = emptyList(),
        allowedTools = listOf("whatsapp_reply"),
        replyTargetSender = true,
        timeoutMs = 60_000,
    )

    private fun context(): FireContext = FireContext(
        event = TriggerEvent.NotificationPosted(pkg = "com.whatsapp", text = "x", isGroup = false),
        state = DeviceState(),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    private class FakeBrain(
        private val actResult: suspend () -> ActResult = { ActResult("ok", null) },
        private val compileResult: suspend () -> CompileResult = { CompileResult("ok", null, null) },
    ) : Brain {
        var calls = 0
        override suspend fun compile(
            nl: String,
            manifest: CapabilityManifest,
            state: DeviceState,
        ): CompileResult {
            calls++
            return compileResult()
        }

        override suspend fun act(
            context: FireContext,
            goal: String,
            contextSources: List<String>,
            allowedTools: List<String>,
        ): ActResult {
            calls++
            return actResult()
        }

        override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult {
            calls++
            return actResult()
        }
    }

    private class FakePolicy(
        private val verdict: BudgetVerdict = BudgetVerdict.Ok,
        private val fail: Boolean = false,
    ) : BudgetPolicy(NoopUsageDaoForTest, NoopSettingsForTest) {
        override suspend fun check(providerId: ProviderId, nowMillis: Long): BudgetVerdict {
            if (fail) throw IllegalStateException("boom")
            return verdict
        }
    }

    private class RecordingUsageDao(private val failInsert: Boolean = false) : UsageDao {
        val events = mutableListOf<UsageEventEntity>()
        override suspend fun insert(event: UsageEventEntity): Long {
            if (failInsert) throw IllegalStateException("db down")
            events += event
            return events.size.toLong()
        }

        override suspend fun aggregateBetween(
            startMillis: Long,
            endMillisExclusive: Long,
        ): List<ProviderUsageAggregate> = emptyList()

        override suspend fun tokensBetween(
            startMillis: Long,
            endMillisExclusive: Long,
        ): List<ProviderTokensAggregate> = emptyList()

        override suspend fun purgeBefore(cutoffMillis: Long): Int = 0
    }

    private class RecordingAuditSink : AuditSink {
        val records = mutableListOf<AuditEvent>()
        override suspend fun record(e: AuditEvent) {
            records += e
        }
    }

    private class RecordingAlerts : BudgetAlerts {
        var calls = 0
        override suspend fun notify(title: String, text: String) {
            calls++
        }
    }

}

private val NoopUsageDaoForTest = object : UsageDao {
    override suspend fun insert(event: UsageEventEntity): Long = 0
    override suspend fun aggregateBetween(
        startMillis: Long,
        endMillisExclusive: Long,
    ): List<ProviderUsageAggregate> = emptyList()
    override suspend fun tokensBetween(
        startMillis: Long,
        endMillisExclusive: Long,
    ): List<ProviderTokensAggregate> = emptyList()
    override suspend fun purgeBefore(cutoffMillis: Long): Int = 0
}

private val NoopSettingsForTest = object : BudgetSettingsStore {
    private val state = MutableStateFlow(BudgetSettings())
    override fun observe(): StateFlow<BudgetSettings> = state
    override suspend fun setGlobalLimits(limits: BudgetLimits): Boolean = true
    override suspend fun setProviderLimits(id: ProviderId, limits: BudgetLimits): Boolean = true
    override suspend fun setSoftThresholdPct(pct: Int): Boolean = true
}
