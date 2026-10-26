package dev.argus.automation.budget

import dev.argus.brain.CompileLimits
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
import dev.argus.engine.brain.CompileMetaError
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
import dev.argus.engine.runtime.RuntimeDataBinding
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    fun `a provider model alias falls back to the approved configured price`() = runTest {
        val usage = TurnUsage(
            inputTokens = 1_000_000,
            outputTokens = 100_000,
            model = "gpt-5.5-2026-07-01",
        )
        val dao = RecordingUsageDao()
        val brain = metered(
            FakeBrain(actResult = { ActResult("risposta", null, usage) }),
            FakePolicy(),
            dao,
            config = openai,
        )

        brain.actV2(context(), invokeV2())

        val event = dao.events.single()
        assertEquals("gpt-5.5-2026-07-01", event.model)
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
    fun `compile retry registra entrambi i tentativi e ritorna il secondo`() = runTest {
        var attempt = 0
        val firstUsage = TurnUsage(inputTokens = 100, outputTokens = 10, model = "gpt-5.5")
        val secondUsage = TurnUsage(inputTokens = 120, outputTokens = 12, model = "gpt-5.5")
        val delegate = FakeBrain(
            compileResult = {
                attempt++
                if (attempt == 1) {
                    CompileResult("malformed", null, "draft_invalid", firstUsage)
                } else {
                    CompileResult("ok", null, null, secondUsage)
                }
            },
        )
        val dao = RecordingUsageDao()
        val brain = metered(delegate, FakePolicy(), dao, config = openai)

        val result = brain.compile("crea", TEST_MANIFEST, DeviceState())

        assertEquals("ok", result.reply)
        assertEquals(2, delegate.calls)
        assertEquals(
            listOf(UsageEventOutcome.ERROR, UsageEventOutcome.OK),
            dao.events.map { it.outcome },
        )
        assertEquals(listOf(100L, 120L), dao.events.map { it.tokensIn })
    }

    @Test
    fun `compile retry ricontrolla hard dopo aver contato il primo tentativo`() = runTest {
        val delegate = FakeBrain(
            compileResult = {
                CompileResult(
                    "malformed",
                    null,
                    "draft_invalid",
                    TurnUsage(inputTokens = 100, outputTokens = 10, model = "gpt-5.5"),
                )
            },
        )
        val dao = RecordingUsageDao()
        val policy = object : BudgetPolicy(NoopUsageDaoForTest, NoopSettingsForTest) {
            override suspend fun check(providerId: ProviderId, nowMillis: Long): BudgetVerdict =
                if (dao.events.any { it.outcome == UsageEventOutcome.ERROR }) {
                    BudgetVerdict.HardExceeded(TrippedLimit(LimitWindow.HOUR, BudgetScope.Global))
                } else {
                    BudgetVerdict.Ok
                }
        }
        val brain = metered(delegate, policy, dao, config = openai)

        val result = brain.compile("crea", TEST_MANIFEST, DeviceState())

        assertEquals(1, delegate.calls)
        assertEquals(BudgetMeta.BUDGET_EXCEEDED, result.metaError)
        assertEquals(
            listOf(UsageEventOutcome.ERROR, UsageEventOutcome.BLOCKED_BUDGET),
            dao.events.map { it.outcome },
        )
    }

    @Test
    fun `compile non ritenta su un provider cambiato durante il primo tentativo`() = runTest {
        var selected = openai
        val anthropic = ProviderConfig(
            ProviderId.ANTHROPIC,
            "https://api.anthropic.com",
            "claude-sonnet-4-5",
        )
        val delegate = FakeBrain(
            compileResult = {
                selected = anthropic
                CompileResult(
                    "malformed",
                    null,
                    CompileMetaError.DRAFT_INVALID,
                    TurnUsage(inputTokens = 100, outputTokens = 10, model = "gpt-5.5"),
                )
            },
        )
        val dao = RecordingUsageDao()
        val brain = MeteredBrain(
            delegate = delegate,
            policy = FakePolicy(),
            usage = dao,
            selectedConfig = { selected },
            audit = RecordingAuditSink(),
            alerts = RecordingAlerts(),
            nowMillis = { 1_700_000_000_000L },
        )

        val result = brain.compile("crea", TEST_MANIFEST, DeviceState())

        assertEquals(CompileMetaError.DRAFT_INVALID, result.metaError)
        assertEquals(1, delegate.calls)
        assertEquals(1, dao.events.size)
        assertEquals(ProviderId.OPENAI.wireName, dao.events.single().providerId)
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
    fun `actResolved ok chiama il delegate e registra un evento act`() = runTest {
        val usage = TurnUsage(inputTokens = 1_000_000, outputTokens = 100_000, model = "gpt-5.5")
        val delegate = FakeBrain(actResult = { ActResult("risposta", null, usage) })
        val dao = RecordingUsageDao()
        val brain = metered(delegate, FakePolicy(), dao, config = openai)

        val result = brain.actResolved(
            context(), "riassumi", emptyList(), listOf("whatsapp_reply"), emptyList(),
        )

        assertEquals("risposta", result.text)
        assertEquals(1, delegate.calls)
        val event = dao.events.single()
        assertEquals(UsageEventKind.ACT, event.kind)
        assertEquals(UsageEventOutcome.OK, event.outcome)
    }

    @Test
    fun `hard su actResolved non chiama il delegate e ritorna budget_exceeded`() = runTest {
        // La capture generativa RISOLTA passa dallo stesso choke-point: un HARD la sopprime come un act.
        val delegate = FakeBrain()
        val dao = RecordingUsageDao()
        val audit = RecordingAuditSink()
        val brain = metered(
            delegate,
            FakePolicy(BudgetVerdict.HardExceeded(TrippedLimit(LimitWindow.HOUR, BudgetScope.Global))),
            dao,
            config = openai,
            audit = audit,
        )

        val result = brain.actResolved(
            context(), "riassumi", emptyList(), listOf("whatsapp_reply"), emptyList(),
        )

        assertEquals(0, delegate.calls)
        assertNull(result.text)
        assertEquals("budget_exceeded", result.metaError)
        assertEquals(UsageEventOutcome.BLOCKED_BUDGET, dao.events.single().outcome)
        assertEquals(AuditKind.SUPPRESSED_BUDGET, audit.records.single().kind)
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
    fun `modello senza prezzo non contatta il provider sotto un tetto monetario`() = runTest {
        val delegate = FakeBrain()
        val dao = RecordingUsageDao()
        val brain = metered(
            delegate,
            FakePolicy(BudgetVerdict.UnpricedModel(ProviderId.OPENAI)),
            dao,
            config = openai.copy(model = "future-model"),
        )

        val result = brain.compile("create", TEST_MANIFEST, DeviceState())

        assertEquals(0, delegate.calls)
        assertEquals(BudgetMeta.UNPRICED_MODEL, result.metaError)
        assertNull(result.draft)
        assertEquals(BudgetMeta.unpricedModelReply(), result.reply)
        assertEquals(UsageEventOutcome.BLOCKED_BUDGET, dao.events.single().outcome)
    }

    @Test
    fun `act con modello senza prezzo registra una soppressione spiegabile`() = runTest {
        val delegate = FakeBrain()
        val dao = RecordingUsageDao()
        val audit = RecordingAuditSink()
        val brain = metered(
            delegate,
            FakePolicy(BudgetVerdict.UnpricedModel(ProviderId.OPENAI)),
            dao,
            config = openai.copy(model = "future-model"),
            audit = audit,
        )

        val result = brain.actV2(context(), invokeV2())

        assertEquals(0, delegate.calls)
        assertEquals(BudgetMeta.UNPRICED_MODEL, result.metaError)
        assertEquals("unpriced_model:openai", audit.records.single().detail)
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
    fun `two concurrent calls with one slot left - the second is blocked by HARD`() = runTest {
        val dao = AggregatingUsageDao()
        val settings = FixedSettings(BudgetSettings(global = BudgetLimits(maxCallsPerHour = 1)))
        val delegate = FakeBrain(
            actResult = {
                // La prima chiamata è ancora in volo (nessun evento persistito) quando arriva la seconda:
                // la prenotazione dello slot è l'unica cosa che la ferma.
                delay(1_000)
                ActResult("risposta", null)
            },
        )
        val brain = metered(delegate, BudgetPolicy(dao, settings), dao, config = openai, settings = settings)

        val first = async { brain.actV2(context(), invokeV2()) }
        runCurrent()
        val second = async { brain.actV2(context(), invokeV2()) }
        val results = awaitAll(first, second)

        assertEquals(1, delegate.calls)
        assertEquals("risposta", results[0].text)
        assertEquals(BudgetMeta.BUDGET_EXCEEDED, results[1].metaError)
        assertEquals(
            listOf(UsageEventOutcome.BLOCKED_BUDGET, UsageEventOutcome.OK),
            dao.events.map { it.outcome },
        )
    }

    @Test
    fun `with two slots left two concurrent calls both go through and both are recorded`() = runTest {
        val dao = AggregatingUsageDao()
        val settings = FixedSettings(BudgetSettings(global = BudgetLimits(maxCallsPerHour = 2)))
        val delegate = FakeBrain(actResult = { delay(1_000); ActResult("risposta", null) })
        val brain = metered(delegate, BudgetPolicy(dao, settings), dao, config = openai, settings = settings)

        val first = async { brain.actV2(context(), invokeV2()) }
        runCurrent()
        val second = async { brain.actV2(context(), invokeV2()) }
        val third = async { brain.actV2(context(), invokeV2()) }
        val results = awaitAll(first, second, third)

        assertEquals(2, delegate.calls)
        assertEquals(2, results.count { it.text == "risposta" })
        assertEquals(1, results.count { it.metaError == BudgetMeta.BUDGET_EXCEEDED })
        assertEquals(2, dao.events.count { it.outcome == UsageEventOutcome.OK })
    }

    @Test
    fun `a slow compile does not hold the lock so a concurrent act is not delayed`() = runTest {
        val dao = RecordingUsageDao()
        val delegate = FakeBrain(
            compileResult = {
                delay(50_000)
                CompileResult("ok", null, null)
            },
            actResult = { ActResult("risposta", null) },
        )
        val brain = metered(delegate, FakePolicy(), dao, config = openai)

        val compile = async { brain.compile("crea", TEST_MANIFEST, DeviceState()) }
        runCurrent()
        val act = async { brain.actV2(context(), invokeV2()) }
        runCurrent()

        assertTrue(act.isCompleted, "l'act non deve aspettare la rete della compile")
        assertEquals("risposta", act.await().text)
        assertFalse(compile.isCompleted)

        advanceTimeBy(50_001)
        runCurrent()
        assertTrue(compile.isCompleted)
        assertEquals(2, dao.events.size)
    }

    @Test
    fun `the reservation is released when the call throws or is cancelled`() = runTest {
        val dao = AggregatingUsageDao()
        val settings = FixedSettings(BudgetSettings(global = BudgetLimits(maxCallsPerHour = 1)))
        var mode = "throw"
        val delegate = FakeBrain(
            actResult = {
                when (mode) {
                    "throw" -> throw IllegalStateException("rete morta")
                    "hang" -> { delay(1_000_000); ActResult("mai", null) }
                    else -> ActResult("risposta", null)
                }
            },
        )
        val brain = metered(delegate, BudgetPolicy(dao, settings), dao, config = openai, settings = settings)

        assertFailsWith<IllegalStateException> { brain.actV2(context(), invokeV2()) }
        mode = "hang"
        val hanging = async { brain.actV2(context(), invokeV2()) }
        runCurrent()
        hanging.cancel()
        runCurrent()
        mode = "ok"

        // Né l'eccezione né la cancellazione hanno lasciato lo slot prenotato.
        val result = brain.actV2(context(), invokeV2())
        assertEquals("risposta", result.text)
        assertEquals(listOf(UsageEventOutcome.OK), dao.events.map { it.outcome })
    }

    @Test
    fun `clarification unsupported capability and limit exceeded are not recorded as errors`() = runTest {
        for (code in listOf("clarification_required", "unsupported_capability", "limit_exceeded")) {
            val dao = RecordingUsageDao()
            val delegate = FakeBrain(compileResult = { CompileResult("Spiegazione.", null, code) })
            val brain = metered(delegate, FakePolicy(), dao, config = openai)

            val result = brain.compile("crea", TEST_MANIFEST, DeviceState())

            assertEquals(code, result.metaError)
            assertEquals(1, delegate.calls, "nessun retry per $code")
            assertEquals(UsageEventOutcome.OK, dao.events.single().outcome, code)
        }
        // Un errore vero resta un errore.
        val dao = RecordingUsageDao()
        val brain = metered(FakeBrain(compileResult = { CompileResult("", null, "output_truncated") }), FakePolicy(), dao, config = openai)
        brain.compile("crea", TEST_MANIFEST, DeviceState())
        assertEquals(UsageEventOutcome.ERROR, dao.events.single().outcome)
    }

    @Test
    fun `meta parse error and missing meta json are retried once like an invalid draft`() = runTest {
        for (code in listOf("meta_parse_error", "meta_json_missing", "draft_invalid")) {
            var attempt = 0
            val first = TurnUsage(inputTokens = 100, outputTokens = 10, model = "gpt-5.5")
            val second = TurnUsage(inputTokens = 120, outputTokens = 12, model = "gpt-5.5")
            val delegate = FakeBrain(
                compileResult = {
                    attempt++
                    if (attempt == 1) CompileResult("rotto", null, code, first) else CompileResult("ok", null, null, second)
                },
            )
            val dao = RecordingUsageDao()
            val brain = metered(delegate, FakePolicy(), dao, config = openai)

            val result = brain.compile("crea", TEST_MANIFEST, DeviceState())

            assertEquals("ok", result.reply, code)
            assertEquals(2, delegate.calls, code)
            // entrambi gli usage sono registrati
            assertEquals(listOf(100L, 120L), dao.events.map { it.tokensIn }, code)
            assertEquals(listOf(UsageEventOutcome.ERROR, UsageEventOutcome.OK), dao.events.map { it.outcome }, code)
        }
    }

    @Test
    fun `other outcomes are never retried`() = runTest {
        for (code in listOf("output_truncated", "context_truncated", "empty_response", "bridge_http_500")) {
            val delegate = FakeBrain(compileResult = { CompileResult("x", null, code) })
            val brain = metered(delegate, FakePolicy(), RecordingUsageDao(), config = openai)

            assertEquals(code, brain.compile("crea", TEST_MANIFEST, DeviceState()).metaError)
            assertEquals(1, delegate.calls, "nessun retry per $code")
        }
    }

    @Test
    fun `the retry of a format error is blocked when the first attempt consumed the last slot`() = runTest {
        val dao = AggregatingUsageDao()
        val settings = FixedSettings(BudgetSettings(global = BudgetLimits(maxCallsPerHour = 1)))
        val delegate = FakeBrain(compileResult = { CompileResult("rotto", null, "meta_parse_error") })
        val brain = metered(delegate, BudgetPolicy(dao, settings), dao, config = openai, settings = settings)

        val result = brain.compile("crea", TEST_MANIFEST, DeviceState())

        assertEquals(1, delegate.calls)
        assertEquals(BudgetMeta.BUDGET_EXCEEDED, result.metaError)
        assertEquals(
            listOf(UsageEventOutcome.ERROR, UsageEventOutcome.BLOCKED_BUDGET),
            dao.events.map { it.outcome },
        )
    }

    // --- I4: ritentativo con istruzioni di correzione --------------------------

    /** Il dettaglio che il parser produce per il Difetto 2 (trigger `time` senza `tz`). */
    private val tzDetail =
        "Field 'tz' is required for type with serial name 'time', but it was missing at path: \$.draft.trigger"

    /** Nota di correzione come la vede il modello (scritta per intero: e' il contratto del ritentativo). */
    private fun expectedNote(reason: String): String =
        "(Argus: your previous reply could not be used: $reason. Reply again with one short sentence, " +
            "then the final line @@META@@ {\"draft\":<object-or-null>,\"error_code\":<string-or-null>}, " +
            "using only the trigger and action shapes listed in the rules; " +
            "for \"now\" use {\"type\":\"immediate\"}.)"

    /** Delegate che fallisce il primo tentativo con [first] e riesce al secondo. */
    private fun failingOnceThenOk(first: CompileResult): FakeBrain {
        var attempt = 0
        return FakeBrain(
            compileResult = {
                attempt++
                if (attempt == 1) first else CompileResult("ok", null, null)
            },
        )
    }

    @Test
    fun `the retry of a format error adds a correction note to the user text for that call only`() = runTest {
        val delegate = failingOnceThenOk(CompileResult("rotto", null, "meta_parse_error", detail = tzDetail))
        val brain = metered(delegate, FakePolicy(), RecordingUsageDao(), config = openai)

        val result = brain.compile("spegni subito il non disturbare", TEST_MANIFEST, DeviceState())

        assertEquals("ok", result.reply)
        assertEquals(
            listOf(
                "spegni subito il non disturbare",
                "spegni subito il non disturbare\n\n" + expectedNote("meta_parse_error: $tzDetail"),
            ),
            delegate.compileTexts,
        )
        assertTrue(expectedNote("meta_parse_error: $tzDetail").length <= 400)
    }

    @Test
    fun `class names in the detail lose their package so that the note stays within 400 chars`() = runTest {
        val detail = "Field 'tz' is required for type with serial name 'dev.argus.engine.model.Trigger.Time', " +
            "but it was missing at path: \$.draft.trigger"
        val delegate = failingOnceThenOk(CompileResult("rotto", null, "meta_parse_error", detail = detail))
        val brain = metered(delegate, FakePolicy(), RecordingUsageDao(), config = openai)

        brain.compile("crea", TEST_MANIFEST, DeviceState())

        val note = delegate.compileTexts.last().removePrefix("crea\n\n")
        assertEquals(
            expectedNote(
                "meta_parse_error: Field 'tz' is required for type with serial name 'Trigger.Time', " +
                    "but it was missing at path: \$.draft.trigger",
            ),
            note,
        )
        assertTrue(note.length <= 400)
    }

    @Test
    fun `the correction note names only the code when the parser gave no detail`() = runTest {
        for (code in listOf("meta_json_missing", "draft_invalid")) {
            val delegate = failingOnceThenOk(CompileResult("rotto", null, code))
            val brain = metered(delegate, FakePolicy(), RecordingUsageDao(), config = openai)

            brain.compile("crea", TEST_MANIFEST, DeviceState())

            assertEquals("crea\n\n" + expectedNote(code), delegate.compileTexts.last(), code)
        }
    }

    @Test
    fun `a long detail is cut so that the whole note stays within 400 chars and keeps its instructions`() = runTest {
        val delegate = failingOnceThenOk(CompileResult("rotto", null, "meta_parse_error", detail = "x".repeat(300)))
        val brain = metered(delegate, FakePolicy(), RecordingUsageDao(), config = openai)

        brain.compile("crea", TEST_MANIFEST, DeviceState())

        val note = delegate.compileTexts.last().removePrefix("crea\n\n")
        assertTrue(note.length <= 400, "nota di ${note.length} caratteri")
        assertTrue(note.startsWith("(Argus: your previous reply could not be used: meta_parse_error: xxx"))
        assertTrue(note.endsWith("for \"now\" use {\"type\":\"immediate\"}.)"), note)
        assertTrue(note.contains("@@META@@"))
    }

    @Test
    fun `the correction note is dropped when it would push the prompt over the compile limit`() = runTest {
        val text = "a".repeat(CompileLimits.MAX_MESSAGE_CHARS - 10)
        val delegate = failingOnceThenOk(CompileResult("rotto", null, "meta_parse_error", detail = tzDetail))
        val brain = metered(delegate, FakePolicy(), RecordingUsageDao(), config = openai)

        brain.compile(text, TEST_MANIFEST, DeviceState())

        assertEquals(listOf(text, text), delegate.compileTexts)
    }

    @Test
    fun `there is a single retry even when the second attempt fails again`() = runTest {
        val delegate = FakeBrain(compileResult = { CompileResult("rotto", null, "meta_parse_error", detail = tzDetail) })
        val brain = metered(delegate, FakePolicy(), RecordingUsageDao(), config = openai)

        val result = brain.compile("crea", TEST_MANIFEST, DeviceState())

        assertEquals("meta_parse_error", result.metaError)
        assertEquals(2, delegate.compileTexts.size)
    }

    private val draftRaw =
        "Imposto un'automazione.\n\n@@AUTOMATION_DRAFT@@ {\"draft\":{\"name\":\"x\"},\"error_code\":null}"

    @Test
    fun `draft_missing is retried when the raw reply holds a quoted draft key`() = runTest {
        val delegate = failingOnceThenOk(CompileResult("Imposto un'automazione.", null, "draft_missing", raw = draftRaw))
        val dao = RecordingUsageDao()
        val brain = metered(delegate, FakePolicy(), dao, config = openai)

        val result = brain.compile("crea", TEST_MANIFEST, DeviceState())

        assertEquals("ok", result.reply)
        assertEquals(2, delegate.calls)
        assertEquals("crea\n\n" + expectedNote("draft_missing"), delegate.compileTexts.last())
        assertEquals(listOf(UsageEventOutcome.ERROR, UsageEventOutcome.OK), dao.events.map { it.outcome })
    }

    @Test
    fun `draft_missing is retried with the correction note even without a draft in the raw reply`() = runTest {
        listOf(
            null,
            "Quale orario preferisci?",
            // Risposta vera vista sul telefono: una frase e la sola azione, senza @@META@@.
            "Propongo una regola immediata che disattiva Non disturbare (modalità OFF) appena la attivi.\n\n" +
                "{\"type\":\"set_dark_mode\",\"mode\":\"off\"}",
        ).forEach { raw ->
            val delegate = failingOnceThenOk(CompileResult("Quale orario?", null, "draft_missing", raw = raw))
            val brain = metered(delegate, FakePolicy(), RecordingUsageDao(), config = openai)

            assertEquals("ok", brain.compile("crea", TEST_MANIFEST, DeviceState()).reply, raw)
            assertEquals(2, delegate.calls, "un retry per $raw")
            assertEquals("crea\n\n" + expectedNote("draft_missing"), delegate.compileTexts.last(), raw)
        }
    }

    @Test
    fun `outcomes that are not format errors are not retried even when the raw reply mentions a draft`() = runTest {
        for (code in listOf("output_truncated", "context_truncated", "empty_response", "bridge_http_500", "clarification_required")) {
            val delegate = FakeBrain(compileResult = { CompileResult("x", null, code, raw = draftRaw) })
            val brain = metered(delegate, FakePolicy(), RecordingUsageDao(), config = openai)

            assertEquals(code, brain.compile("crea", TEST_MANIFEST, DeviceState()).metaError)
            assertEquals(1, delegate.calls, "nessun retry per $code")
        }
    }

    @Test
    fun `the retry of a misformatted draft is blocked when the first attempt consumed the last slot`() = runTest {
        val dao = AggregatingUsageDao()
        val settings = FixedSettings(BudgetSettings(global = BudgetLimits(maxCallsPerHour = 1)))
        val delegate = FakeBrain(compileResult = { CompileResult("x", null, "draft_missing", raw = draftRaw) })
        val brain = metered(delegate, BudgetPolicy(dao, settings), dao, config = openai, settings = settings)

        val result = brain.compile("crea", TEST_MANIFEST, DeviceState())

        assertEquals(1, delegate.calls)
        assertEquals(BudgetMeta.BUDGET_EXCEEDED, result.metaError)
        assertEquals(
            listOf(UsageEventOutcome.ERROR, UsageEventOutcome.BLOCKED_BUDGET),
            dao.events.map { it.outcome },
        )
    }

    // --- helpers -------------------------------------------------------------

    private fun metered(
        delegate: Brain,
        policy: BudgetPolicy,
        dao: UsageDao,
        config: ProviderConfig,
        audit: AuditSink = RecordingAuditSink(),
        alerts: BudgetAlerts = RecordingAlerts(),
        settings: BudgetSettingsStore? = null,
    ): MeteredBrain = MeteredBrain(
        delegate = delegate,
        policy = policy,
        usage = dao,
        selectedConfig = { config },
        audit = audit,
        alerts = alerts,
        nowMillis = { 1_700_000_000_000L },
        settings = settings,
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

        /** Testo utente ricevuto da ogni chiamata a [compile], nell'ordine. */
        val compileTexts = mutableListOf<String>()
        override suspend fun compile(
            nl: String,
            manifest: CapabilityManifest,
            state: DeviceState,
        ): CompileResult {
            calls++
            compileTexts += nl
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

        override suspend fun actResolved(
            context: FireContext,
            goal: String,
            contextSources: List<String>,
            allowedTools: List<String>,
            runtimeData: List<RuntimeDataBinding>,
        ): ActResult {
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

    /** DAO che aggrega davvero gli eventi inseriti: serve a una [BudgetPolicy] reale. */
    private class AggregatingUsageDao : UsageDao {
        val events = mutableListOf<UsageEventEntity>()

        override suspend fun insert(event: UsageEventEntity): Long {
            events += event
            return events.size.toLong()
        }

        override suspend fun aggregateBetween(
            startMillis: Long,
            endMillisExclusive: Long,
        ): List<ProviderUsageAggregate> = events
            .filter { it.timestampMs >= startMillis && it.timestampMs < endMillisExclusive }
            .groupBy { it.providerId }
            .map { (provider, rows) ->
                ProviderUsageAggregate(
                    providerId = provider,
                    calls = rows.size.toLong(),
                    okCalls = rows.count { it.outcome == UsageEventOutcome.OK }.toLong(),
                    errorCalls = rows.count { it.outcome == UsageEventOutcome.ERROR }.toLong(),
                    blockedCalls = rows.count { it.outcome == UsageEventOutcome.BLOCKED_BUDGET }.toLong(),
                    tokensIn = rows.mapNotNull { it.tokensIn }.takeIf { it.isNotEmpty() }?.sum(),
                    tokensOut = rows.mapNotNull { it.tokensOut }.takeIf { it.isNotEmpty() }?.sum(),
                    costMicros = rows.mapNotNull { it.costMicros }.takeIf { it.isNotEmpty() }?.sum(),
                )
            }

        override suspend fun tokensBetween(
            startMillis: Long,
            endMillisExclusive: Long,
        ): List<ProviderTokensAggregate> = emptyList()

        override suspend fun purgeBefore(cutoffMillis: Long): Int = 0
    }

    private class FixedSettings(settings: BudgetSettings) : BudgetSettingsStore {
        private val state = MutableStateFlow(settings)
        override fun observe(): StateFlow<BudgetSettings> = state
        override suspend fun setGlobalLimits(limits: BudgetLimits): Boolean = true
        override suspend fun setProviderLimits(id: ProviderId, limits: BudgetLimits): Boolean = true
        override suspend fun setSoftThresholdPct(pct: Int): Boolean = true
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
