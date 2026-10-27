package dev.argus.automation.budget

import dev.argus.automation.AndroidCapabilityProbe
import dev.argus.brain.CompileLimits
import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderId
import dev.argus.data.dao.ProviderTokensAggregate
import dev.argus.data.dao.ProviderUsageAggregate
import dev.argus.data.dao.UsageDao
import dev.argus.data.entities.UsageEventEntity
import dev.argus.data.entities.UsageEventOutcome
import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileMetaError
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.AuditEvent
import dev.argus.engine.runtime.AuditSink
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.RuntimeDataBinding
import dev.argus.engine.safety.DraftValidator
import dev.argus.engine.safety.Severity
import dev.argus.engine.safety.ValidationIssue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Giro di riparazione sugli errori del validatore (0.5.0): una volta sola, dal choke-point del budget. */
class MeteredBrainRepairTest {
    private val openai = ProviderConfig(ProviderId.OPENAI, "https://api.openai.com/v1", "gpt-5.5")
    private val manifest = CapabilityManifest(
        deviceModel = "test",
        androidVersion = 16,
        androidApi = 36,
        shizukuAvailable = false,
        grantedPermissions = emptyList(),
        availableTools = emptyList(),
        unavailableTools = emptyMap(),
        whitelistedContacts = emptyList(),
    )

    private fun draft(afterMs: Long = 600_000, name: String = "Vibra") =
        AutomationDraft(name, Trigger.Time(afterMs = afterMs, tz = "Europe/Rome"), listOf(Action.Vibrate(500)))

    private val realValidator = DraftErrorSource { draft ->
        DraftValidator(AndroidCapabilityProbe.KNOWN_TOOLS).validate(draft, emptySet())
    }

    private fun issue(code: String, severity: Severity = Severity.ERROR, message: String = "diagnostica") =
        ValidationIssue(severity, code, message)

    private fun metered(
        delegate: Brain,
        dao: UsageDao = RecordingDao(),
        source: DraftErrorSource? = realValidator,
        retries: MutableList<CompileRetryKind> = mutableListOf(),
        selected: () -> ProviderConfig = { openai },
        policy: BudgetPolicy = OpenPolicy,
        settings: BudgetSettingsStore? = null,
    ) = MeteredBrain(
        delegate = delegate,
        policy = policy,
        usage = dao,
        selectedConfig = selected,
        audit = NoopAudit,
        alerts = { _, _ -> },
        nowMillis = { 1_700_000_000_000L },
        settings = settings,
        draftErrors = source,
        onRetry = { retries += it },
    )

    @Test
    fun `an ERROR of the validator triggers one repair with the original request plus a note`() = runTest {
        val broken = CompileResult("ok", draft(afterMs = 500), null)
        val fixed = CompileResult("sistemata", draft(afterMs = 300_000), null)
        val delegate = FakeBrain(broken, fixed)
        val dao = RecordingDao()
        val retries = mutableListOf<CompileRetryKind>()

        val result = metered(delegate, dao, retries = retries).compile("fra 5 minuti vibra", manifest, DeviceState())

        assertSame(fixed, result)
        assertEquals(2, delegate.texts.size)
        assertEquals("fra 5 minuti vibra", delegate.texts[0])
        assertEquals(
            "fra 5 minuti vibra\n\n(Argus: a first draft for this request failed validation. " +
                "after_ms_invalid: The delay must be between 1 second and 7 days. Reply again with one short " +
                "sentence, then the final line @@META@@ {\"draft\":<object-or-null>,\"error_code\":<string-or-null>}. " +
                "Compile the request again avoiding exactly these errors; or, if the request " +
                "cannot be met within these rules (for example a limit is exceeded), return draft null with the " +
                "matching error_code (limit_exceeded for a limit) and explain why.)",
            delegate.texts[1],
        )
        assertEquals(listOf(CompileRetryKind.REPAIR), retries)
    }

    @Test
    fun `the repair goes through the budget choke-point and is counted like any other call`() = runTest {
        val delegate = FakeBrain(CompileResult("ok", draft(afterMs = 500), null), CompileResult("ok", draft(), null))
        val dao = RecordingDao()

        metered(delegate, dao).compile("vibra", manifest, DeviceState())

        assertEquals(2, dao.events.size)
        assertEquals(listOf(UsageEventOutcome.OK, UsageEventOutcome.OK), dao.events.map { it.outcome })
    }

    @Test
    fun `a repair blocked by a HARD limit leaves the original draft with its errors visible`() = runTest {
        val dao = AggregatingDao()
        val settings = FixedSettings(BudgetSettings(global = BudgetLimits(maxCallsPerHour = 1)))
        val broken = CompileResult("ok", draft(afterMs = 500), null)
        val delegate = FakeBrain(broken, CompileResult("ok", draft(), null))
        val retries = mutableListOf<CompileRetryKind>()

        val result = metered(
            delegate, dao, retries = retries, policy = BudgetPolicy(dao, settings), settings = settings,
        ).compile("vibra", manifest, DeviceState())

        assertSame(broken, result)
        assertEquals(1, delegate.texts.size)
        assertEquals(
            listOf(UsageEventOutcome.OK, UsageEventOutcome.BLOCKED_BUDGET),
            dao.events.map { it.outcome },
        )
        assertTrue(retries.isEmpty(), "una chiamata bloccata non e' un ritentativo partito")
    }

    @Test
    fun `warnings alone never start a repair`() = runTest {
        val delegate = FakeBrain(CompileResult("ok", draft(), null))
        val retries = mutableListOf<CompileRetryKind>()
        val source = DraftErrorSource { listOf(issue("cooldown_raised", Severity.WARNING)) }

        val result = metered(delegate, source = source, retries = retries).compile("vibra", manifest, DeviceState())

        assertEquals("ok", result.reply)
        assertEquals(1, delegate.texts.size)
        assertTrue(retries.isEmpty())
    }

    @Test
    fun `a valid draft is returned as it is`() = runTest {
        val delegate = FakeBrain(CompileResult("ok", draft(), null))

        metered(delegate).compile("vibra", manifest, DeviceState())

        assertEquals(1, delegate.texts.size)
    }

    @Test
    fun `clarifications refusals unsupported and limit_exceeded are never repaired`() = runTest {
        listOf(
            CompileResult("quale?", null, CompileMetaError.CLARIFICATION_REQUIRED),
            CompileResult("no", null, CompileMetaError.UNSUPPORTED_CAPABILITY),
            CompileResult("troppo", null, CompileMetaError.LIMIT_EXCEEDED),
            CompileResult("no", null, "contact_not_whitelisted"),
        ).forEach { answer ->
            val delegate = FakeBrain(answer)
            val consulted = mutableListOf<AutomationDraft>()
            val source = DraftErrorSource { consulted += it; listOf(issue("x")) }

            val result = metered(delegate, source = source).compile("vibra", manifest, DeviceState())

            assertSame(answer, result)
            assertEquals(1, delegate.texts.size, answer.metaError)
            assertTrue(consulted.isEmpty())
        }
    }

    @Test
    fun `at most one repair even when the repaired draft still has errors`() = runTest {
        val first = CompileResult("ok", draft(afterMs = 500), null)
        val second = CompileResult("ancora", draft(afterMs = 700), null)
        val delegate = FakeBrain(first, second, CompileResult("terza", draft(), null))

        val result = metered(delegate).compile("vibra", manifest, DeviceState())

        assertSame(second, result)
        assertEquals(2, delegate.texts.size)
    }

    @Test
    fun `no repair after the format retry has already been used`() = runTest {
        val delegate = FakeBrain(
            CompileResult("rotto", null, CompileMetaError.DRAFT_INVALID),
            CompileResult("ok", draft(afterMs = 500), null),
            CompileResult("terza", draft(), null),
        )
        val retries = mutableListOf<CompileRetryKind>()

        val result = metered(delegate, retries = retries).compile("vibra", manifest, DeviceState())

        assertEquals("ok", result.reply)
        assertEquals(2, delegate.texts.size)
        assertEquals(listOf(CompileRetryKind.FORMAT), retries)
    }

    @Test
    fun `the repair is not started when the user changed provider in the meantime`() = runTest {
        var selected = openai
        val anthropic = ProviderConfig(ProviderId.ANTHROPIC, "https://api.anthropic.com", "claude-sonnet-4-5")
        val broken = CompileResult("ok", draft(afterMs = 500), null)
        val delegate = FakeBrain(broken, CompileResult("ok", draft(), null), onCall = { selected = anthropic })

        val result = metered(delegate, selected = { selected }).compile("vibra", manifest, DeviceState())

        assertSame(broken, result)
        assertEquals(1, delegate.texts.size)
    }

    @Test
    fun `the repair respects the message limit`() = runTest {
        val long = "x".repeat(CompileLimits.MAX_MESSAGE_CHARS - 20)
        val broken = CompileResult("ok", draft(afterMs = 500), null)
        val delegate = FakeBrain(broken, CompileResult("ok", draft(), null))

        val result = metered(delegate).compile(long, manifest, DeviceState())

        assertSame(broken, result)
        assertEquals(1, delegate.texts.size)
    }

    @Test
    fun `a broken or failed repair leaves the original draft`() = runTest {
        val broken = CompileResult("ok", draft(afterMs = 500), null)
        listOf(
            CompileResult("rotto", null, CompileMetaError.META_PARSE_ERROR),
            CompileResult("", null, CompileMetaError.DRAFT_MISSING),
            CompileResult("", null, "bridge_timeout"),
            CompileResult("", null, null),
        ).forEach { bad ->
            val result = metered(FakeBrain(broken, bad)).compile("vibra", manifest, DeviceState())
            assertSame(broken, result, bad.metaError)
        }
        val thrown = FakeBrain(broken, null)
        assertSame(broken, metered(thrown).compile("vibra", manifest, DeviceState()))
    }

    @Test
    fun `the model may explain why it cannot be fixed with limit_exceeded or a clarification`() = runTest {
        val broken = CompileResult("ok", draft(afterMs = 500), null)
        listOf(
            CompileResult("oltre il limite", null, CompileMetaError.LIMIT_EXCEEDED),
            CompileResult("quale?", null, CompileMetaError.CLARIFICATION_REQUIRED),
        ).forEach { answer ->
            assertSame(answer, metered(FakeBrain(broken, answer)).compile("vibra", manifest, DeviceState()))
        }
    }

    @Test
    fun `without a validator source the brain behaves as before`() = runTest {
        val delegate = FakeBrain(CompileResult("ok", draft(afterMs = 500), null))

        metered(delegate, source = null).compile("vibra", manifest, DeviceState())

        assertEquals(1, delegate.texts.size)
    }

    @Test
    fun `a validator that throws never breaks the turn`() = runTest {
        val broken = CompileResult("ok", draft(afterMs = 500), null)
        val delegate = FakeBrain(broken)
        val source = DraftErrorSource { throw IllegalStateException("db down") }

        assertSame(broken, metered(delegate, source = source).compile("vibra", manifest, DeviceState()))
        assertEquals(1, delegate.texts.size)
    }

    @Test
    fun `a cancelled validator call is not swallowed`() = runTest {
        val delegate = FakeBrain(CompileResult("ok", draft(afterMs = 500), null))
        val source = DraftErrorSource { throw kotlinx.coroutines.CancellationException("stop") }

        assertFailsWith<kotlinx.coroutines.CancellationException> {
            metered(delegate, source = source).compile("vibra", manifest, DeviceState())
        }
    }

    @Test
    fun `the note carries only codes and fixed English messages, never user data`() {
        // La diagnostica del validatore (in italiano) cita nomi scelti dall'utente: nella nota non entra.
        val errors = listOf(
            issue("var_name_invalid", message = "Nome variabile 'segreto_di_mario' non valido"),
            issue("after_ms_invalid", message = "Ritardo +39 333 1234567 fuori intervallo"),
            issue("after_ms_invalid", message = "duplicato"),
        )

        val note = repairNote(errors)

        assertFalse("segreto_di_mario" in note || "333" in note || "duplicato" in note, note)
        assertTrue("var_name_invalid: A variable name is not valid" in note, note)
        assertEquals(1, Regex("after_ms_invalid").findAll(note).count(), "un solo messaggio per codice")
    }

    @Test
    fun `the note lists at most six errors and an unknown code gets a generic message`() {
        val errors = (1..9).map { issue("codice_$it") }

        val note = repairNote(errors)

        assertEquals(6, Regex("Unrecognized validation problem").findAll(note).count(), note)
        assertTrue("codice_1: Unrecognized validation problem (codice_1)." in note, note)
        assertFalse("codice_7" in note, note)
    }

    // --- helpers -------------------------------------------------------------

    /** Risponde nell'ordine; `null` = il transport lancia. L'ultimo valore resta fisso. */
    private class FakeBrain(
        private vararg val answers: CompileResult?,
        private val onCall: () -> Unit = {},
    ) : Brain {
        val texts = mutableListOf<String>()

        override suspend fun compile(nl: String, manifest: CapabilityManifest, state: DeviceState): CompileResult {
            texts += nl
            onCall()
            return answers[(texts.size - 1).coerceAtMost(answers.lastIndex)]
                ?: throw IllegalStateException("rete")
        }

        override suspend fun act(
            context: FireContext,
            goal: String,
            contextSources: List<String>,
            allowedTools: List<String>,
        ): ActResult = error("non usato")

        override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult = error("non usato")

        override suspend fun actResolved(
            context: FireContext,
            goal: String,
            contextSources: List<String>,
            allowedTools: List<String>,
            runtimeData: List<RuntimeDataBinding>,
        ): ActResult = error("non usato")
    }

    private class RecordingDao : UsageDao {
        val events = mutableListOf<UsageEventEntity>()
        override suspend fun insert(event: UsageEventEntity): Long {
            events += event
            return events.size.toLong()
        }

        override suspend fun aggregateBetween(startMillis: Long, endMillisExclusive: Long) =
            emptyList<ProviderUsageAggregate>()

        override suspend fun tokensBetween(startMillis: Long, endMillisExclusive: Long) =
            emptyList<ProviderTokensAggregate>()

        override suspend fun purgeBefore(cutoffMillis: Long): Int = 0
    }

    private class AggregatingDao : UsageDao {
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
                    tokensIn = null,
                    tokensOut = null,
                    costMicros = null,
                )
            }

        override suspend fun tokensBetween(startMillis: Long, endMillisExclusive: Long) =
            emptyList<ProviderTokensAggregate>()

        override suspend fun purgeBefore(cutoffMillis: Long): Int = 0
    }

    private class FixedSettings(settings: BudgetSettings) : BudgetSettingsStore {
        private val state = MutableStateFlow(settings)
        override fun observe(): StateFlow<BudgetSettings> = state
        override suspend fun setGlobalLimits(limits: BudgetLimits): Boolean = true
        override suspend fun setProviderLimits(id: ProviderId, limits: BudgetLimits): Boolean = true
        override suspend fun setSoftThresholdPct(pct: Int): Boolean = true
    }

    private object NoopAudit : AuditSink {
        override suspend fun record(e: AuditEvent) = Unit
    }

    private companion object {
        val OpenPolicy: BudgetPolicy = object : BudgetPolicy(RecordingDao(), FixedSettings(BudgetSettings())) {
            override suspend fun check(providerId: ProviderId, nowMillis: Long): BudgetVerdict = BudgetVerdict.Ok
        }
    }
}
