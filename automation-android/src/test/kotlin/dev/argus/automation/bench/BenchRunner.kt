package dev.argus.automation.bench

import dev.argus.automation.budget.BudgetAlerts
import dev.argus.automation.budget.BudgetPolicy
import dev.argus.automation.budget.BudgetSettings
import dev.argus.automation.budget.BudgetSettingsStore
import dev.argus.automation.budget.BudgetLimits
import dev.argus.automation.budget.CompileRetryKind
import dev.argus.automation.budget.DraftErrorSource
import dev.argus.automation.budget.MeteredBrain
import dev.argus.brain.AgentTransport
import dev.argus.brain.CompileTimeoutPolicy
import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderId
import dev.argus.brain.ProviderSpec
import dev.argus.brain.ReasoningEffort
import dev.argus.brain.StructuredOutputMode
import dev.argus.brain.TransportBackedBrain
import dev.argus.data.dao.ProviderTokensAggregate
import dev.argus.data.dao.ProviderUsageAggregate
import dev.argus.data.dao.UsageDao
import dev.argus.data.entities.UsageEventEntity
import dev.argus.engine.brain.ActResult
import dev.argus.engine.brain.Brain
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.CompileResult
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.GenerativeContract
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.NoopAuditSink
import dev.argus.engine.runtime.RuntimeDataBinding
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList

/** Configurazione del banco, dalle variabili d'ambiente `ARGUS_BENCH_*` (la chiave non passa da qui). */
internal data class BenchOptions(
    val model: String,
    val baseUrl: String,
    val repeat: Int,
    val filter: String?,
    val parallel: Int,
    /** Ragionamento chiesto al modello; [ReasoningEffort.DEFAULT] omette il campo (default del provider). */
    val reasoning: ReasoningEffort = ReasoningEffort.DEFAULT,
    /** Output strutturato nativo del compile (0.5.1): come l'app, forzato acceso o forzato spento. */
    val structured: StructuredSetting = StructuredSetting.AUTO,
) {
    /**
     * Il [spec] del provider con l'output strutturato impostato come chiede il banco: `auto` lascia il
     * catalogo (cioe' l'app), `on` lo accende, `off` lo spegne e lascia il solo protocollo `@@META@@`.
     */
    fun applyTo(spec: ProviderSpec): ProviderSpec = when (structured) {
        StructuredSetting.AUTO -> spec
        StructuredSetting.ON -> spec.copy(quirks = spec.quirks.copy(structuredOutput = StructuredOutputMode.RESPONSE_FORMAT))
        StructuredSetting.OFF -> spec.copy(quirks = spec.quirks.copy(structuredOutput = StructuredOutputMode.NONE))
    }

    /** [filter]: uno o più prefissi di id separati da virgola; senza filtro passano tutti i casi. */
    fun selects(caseId: String): Boolean {
        val prefixes = filter?.split(',')?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
        return prefixes.isEmpty() || prefixes.any(caseId::startsWith)
    }

    companion object {
        const val DEFAULT_MODEL = "@preset/argus"
        const val DEFAULT_BASE_URL = "https://openrouter.ai/api/v1"
        const val DEFAULT_REPEAT = 1
        const val DEFAULT_PARALLEL = 4
        private const val MAX_REPEAT = 50
        private const val MAX_PARALLEL = 32

        fun fromEnv(env: (String) -> String?): BenchOptions {
            fun value(name: String): String? = env(name)?.trim()?.takeIf { it.isNotEmpty() }
            fun int(name: String, default: Int, range: IntRange): Int {
                val raw = value(name) ?: return default
                val parsed = raw.toIntOrNull()
                    ?: throw BenchException("$name: '$raw' non e' un intero")
                if (parsed !in range) throw BenchException("$name: $parsed fuori dall'intervallo $range")
                return parsed
            }
            return BenchOptions(
                model = value("ARGUS_BENCH_MODEL") ?: DEFAULT_MODEL,
                baseUrl = value("ARGUS_BENCH_BASE_URL") ?: DEFAULT_BASE_URL,
                repeat = int("ARGUS_BENCH_REPEAT", DEFAULT_REPEAT, 1..MAX_REPEAT),
                filter = value("ARGUS_BENCH_FILTER"),
                parallel = int("ARGUS_BENCH_PARALLEL", DEFAULT_PARALLEL, 1..MAX_PARALLEL),
                reasoning = value("ARGUS_BENCH_REASONING")?.let { raw ->
                    ReasoningEffort.entries.firstOrNull { it.wireValue == raw.lowercase() }
                        ?: throw BenchException("ARGUS_BENCH_REASONING: '$raw' non e' none, low, medium o high")
                } ?: ReasoningEffort.DEFAULT,
                structured = value("ARGUS_BENCH_STRUCTURED")?.let { raw ->
                    StructuredSetting.entries.firstOrNull { it.wire == raw.lowercase() }
                        ?: throw BenchException("ARGUS_BENCH_STRUCTURED: '$raw' non e' on, off o auto")
                } ?: StructuredSetting.AUTO,
            )
        }
    }
}

/** `ARGUS_BENCH_STRUCTURED`: `auto` = come l'app (catalogo), `on` = acceso, `off` = solo protocollo `@@META@@`. */
internal enum class StructuredSetting(val wire: String) {
    AUTO("auto"),
    ON("on"),
    OFF("off"),
}

/** Esito di una singola esecuzione (un caso, una ripetizione). */
internal data class RunResult(
    val caseId: String,
    /** `compile` o `act`. */
    val kind: String,
    val category: String,
    val lang: String,
    val manifest: String?,
    val repetition: Int,
    val passed: Boolean,
    val actual: String,
    val expected: String,
    val failures: List<Failure>,
    /** Ritentativo di FORMATO (nota di correzione). */
    val retryUsed: Boolean,
    val attempts: Int,
    val tokensIn: Long?,
    val tokensOut: Long?,
    val latencyMs: Long,
    val metaError: String?,
    val reply: String?,
    /** Testo grezzo del modello: solo per i fallimenti, troncato a [BenchRunner.RAW_MAX_CHARS]. */
    val raw: String?,
    /** Giro di RIPARAZIONE sugli errori del validatore (0.5.0), contato a parte dal ritentativo di formato. */
    val repairUsed: Boolean = false,
    /**
     * Compile: la risposta e' arrivata come output strutturato nativo (`true`) o col protocollo `@@META@@`
     * (`false`: spento, rifiutato dal provider o ignorato dal modello). `null` per gli act e per i fallimenti.
     */
    val structured: Boolean? = null,
)

/**
 * Esegue i casi contro un [AgentTransport] vero (o simulato) lungo lo stesso percorso dell'app:
 * `TransportBackedBrain` (alias dei contatti) dentro `MeteredBrain` (ritentativo con nota di
 * correzione, contabilita' dell'usage) con budget e usage fittizi in memoria, uno stack per esecuzione.
 */
internal class BenchRunner(
    private val transport: AgentTransport,
    private val config: ProviderConfig,
    private val world: BenchWorld,
    private val options: BenchOptions,
    private val today: () -> LocalDate = { LocalDate.now(world.zone) },
) {
    suspend fun run(compileCases: List<BenchCase>, actCases: List<ActCase>): List<RunResult> = coroutineScope {
        val gate = Semaphore(options.parallel)
        val jobs = ArrayList<kotlinx.coroutines.Deferred<RunResult>>()
        for (case in compileCases) {
            for (repetition in 1..options.repeat) {
                jobs += async { gate.withPermit { runCompile(case, repetition) } }
            }
        }
        for (case in actCases) {
            for (repetition in 1..options.repeat) {
                jobs += async { gate.withPermit { runAct(case, repetition) } }
            }
        }
        jobs.awaitAll()
    }

    private suspend fun runCompile(case: BenchCase, repetition: Int): RunResult {
        val stack = Stack(transport, config, world)
        // Come ChatViewModel: la lingua dei testi per l'utente e' quella dell'interfaccia (qui la `lang`
        // del caso) e i candidati app nominati nella richiesta arrivano al modello nel prompt.
        val manifest = world.manifest(case.manifest, case.lang)
        val prompt = world.compilePrompt(case.text, case.lang, case.extraApps)
        val expected = case.outcomes.joinToString("|") { it.wire }
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        return try {
            val result = withTimeout(CompileTimeoutPolicy.timeoutMillis(config.providerId, config.baseUrl)) {
                stack.brain.compile(prompt, manifest, world.deviceState)
            }
            val latency = elapsed()
            val evaluation = CompileEvaluator.evaluate(case, result, world, today())
            RunResult(
                caseId = case.id,
                kind = "compile",
                category = case.category,
                lang = case.lang,
                manifest = case.manifest.wire,
                repetition = repetition,
                passed = evaluation.passed,
                actual = evaluation.actual,
                expected = expected,
                failures = evaluation.failures,
                retryUsed = stack.retries.formatRetries > 0,
                repairUsed = stack.retries.repairRounds > 0,
                structured = result.structured,
                attempts = stack.recording.compileCalls,
                tokensIn = stack.usage.tokensIn(),
                tokensOut = stack.usage.tokensOut(),
                latencyMs = latency,
                metaError = result.metaError,
                reply = BenchText.clip(result.reply.replace('\n', ' '), REPLY_MAX_CHARS).ifBlank { null },
                raw = if (evaluation.passed) null else rawOf(result),
            )
        } catch (error: TimeoutCancellationException) {
            failed(case.id, "compile", case.category, case.lang, case.manifest.wire, repetition, expected, stack,
                elapsed(), FailureCategory.TIMEOUT, "compile oltre il timeout del chat")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failed(case.id, "compile", case.category, case.lang, case.manifest.wire, repetition, expected, stack,
                elapsed(), FailureCategory.BENCH_ERROR, "eccezione ${error.javaClass.simpleName}: ${BenchText.clip(error.message.orEmpty(), 200)}")
        }
    }

    private suspend fun runAct(case: ActCase, repetition: Int): RunResult {
        val stack = Stack(transport, config, world)
        val category = if (case.web) "act.web" else "act.plain"
        val fingerprint = ApprovalFingerprint("0".repeat(64))
        val automationId = AutomationId("bench-act")
        val context = FireContext(
            event = TriggerEvent.TimeFired(automationId, fingerprint),
            state = world.actState,
            automationId = automationId,
            approvalFingerprint = fingerprint,
            eventId = TriggerEventId("bench-event"),
            executionId = ExecutionId("bench-execution"),
            actionIndex = 0,
        )
        val tools = if (case.web) listOf(GenerativeContract.TOOL_WEB_SEARCH) else emptyList()
        val timeout = if (case.web) ACT_WEB_TIMEOUT_MS else ACT_TIMEOUT_MS
        val started = System.nanoTime()
        fun elapsed() = (System.nanoTime() - started) / 1_000_000
        return try {
            val result = withTimeout(timeout) {
                stack.brain.act(context, case.goal, case.contextSources, tools)
            }
            val latency = elapsed()
            val evaluation = ActEvaluator.evaluate(case, result)
            RunResult(
                caseId = case.id,
                kind = "act",
                category = category,
                lang = case.lang,
                manifest = null,
                repetition = repetition,
                passed = evaluation.passed,
                actual = evaluation.actual,
                expected = "text",
                failures = evaluation.failures,
                retryUsed = false,
                attempts = 1,
                tokensIn = stack.usage.tokensIn(),
                tokensOut = stack.usage.tokensOut(),
                latencyMs = latency,
                metaError = result.metaError,
                reply = result.text?.let { BenchText.clip(it.replace('\n', ' '), REPLY_MAX_CHARS) },
                raw = if (evaluation.passed) null else result.text?.let { BenchText.clip(it, RAW_MAX_CHARS) },
            )
        } catch (error: TimeoutCancellationException) {
            failed(case.id, "act", category, case.lang, null, repetition, "text", stack,
                elapsed(), FailureCategory.TIMEOUT, "act oltre il timeout dell'azione")
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failed(case.id, "act", category, case.lang, null, repetition, "text", stack,
                elapsed(), FailureCategory.BENCH_ERROR, "eccezione ${error.javaClass.simpleName}: ${BenchText.clip(error.message.orEmpty(), 200)}")
        }
    }

    private fun failed(
        caseId: String,
        kind: String,
        category: String,
        lang: String,
        manifest: String?,
        repetition: Int,
        expected: String,
        stack: Stack,
        latency: Long,
        failureCategory: String,
        message: String,
    ) = RunResult(
        caseId = caseId,
        kind = kind,
        category = category,
        lang = lang,
        manifest = manifest,
        repetition = repetition,
        passed = false,
        actual = failureCategory,
        expected = expected,
        failures = listOf(Failure(failureCategory, message)),
        retryUsed = stack.retries.formatRetries > 0,
        repairUsed = stack.retries.repairRounds > 0,
        attempts = stack.recording.compileCalls.coerceAtLeast(1),
        tokensIn = stack.usage.tokensIn(),
        tokensOut = stack.usage.tokensOut(),
        latencyMs = latency,
        metaError = null,
        reply = null,
        raw = null,
    )

    private fun rawOf(result: CompileResult): String? = result.raw?.let { BenchText.clip(it, RAW_MAX_CHARS) }

    /** Il percorso dell'app per UNA esecuzione, con usage e budget finti in memoria. */
    private class Stack(transport: AgentTransport, config: ProviderConfig, world: BenchWorld) {
        val usage = RecordingUsageDao()
        val recording = RecordingBrain(TransportBackedBrain(transport))
        val retries = RetryCounter()
        val brain: Brain = MeteredBrain(
            delegate = recording,
            policy = BudgetPolicy(usage, UnlimitedBudgetSettings),
            usage = usage,
            selectedConfig = { config },
            audit = NoopAuditSink,
            alerts = SilentBudgetAlerts,
            // Stessa logica dell'app: il giro di riparazione usa il validatore e la whitelist di `canArm`.
            draftErrors = DraftErrorSource { draft -> world.validator.validate(draft, world.whitelistedIds) },
            onRetry = retries::record,
        )
    }

    companion object {
        const val RAW_MAX_CHARS = 2_000
        private const val REPLY_MAX_CHARS = 300
        private const val ACT_TIMEOUT_MS = 60_000L
        private const val ACT_WEB_TIMEOUT_MS = 120_000L
    }
}

/** Conta, per esecuzione, i ritentativi di formato e i giri di riparazione che MeteredBrain ha avviato. */
internal class RetryCounter {
    private val format = java.util.concurrent.atomic.AtomicInteger(0)
    private val repair = java.util.concurrent.atomic.AtomicInteger(0)
    val formatRetries: Int get() = format.get()
    val repairRounds: Int get() = repair.get()

    fun record(kind: CompileRetryKind) {
        when (kind) {
            CompileRetryKind.FORMAT -> format.incrementAndGet()
            CompileRetryKind.REPAIR -> repair.incrementAndGet()
        }
    }
}

/** Conta le chiamate che arrivano davvero al transport (il ritentativo di MeteredBrain ne fa due). */
internal class RecordingBrain(private val delegate: Brain) : Brain {
    private val calls = java.util.concurrent.atomic.AtomicInteger(0)
    val compileCalls: Int get() = calls.get()

    override suspend fun compile(nl: String, manifest: CapabilityManifest, state: DeviceState): CompileResult {
        calls.incrementAndGet()
        return delegate.compile(nl, manifest, state)
    }

    override suspend fun act(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
    ): ActResult = delegate.act(context, goal, contextSources, allowedTools)

    override suspend fun actV2(context: FireContext, action: Action.InvokeLlmV2): ActResult =
        delegate.actV2(context, action)

    override suspend fun actResolved(
        context: FireContext,
        goal: String,
        contextSources: List<String>,
        allowedTools: List<String>,
        runtimeData: List<RuntimeDataBinding>,
    ): ActResult = delegate.actResolved(context, goal, contextSources, allowedTools, runtimeData)
}

internal class RecordingUsageDao : UsageDao {
    private val events = CopyOnWriteArrayList<UsageEventEntity>()

    fun tokensIn(): Long? = events.mapNotNull { it.tokensIn }.takeIf { it.isNotEmpty() }?.sum()
    fun tokensOut(): Long? = events.mapNotNull { it.tokensOut }.takeIf { it.isNotEmpty() }?.sum()

    override suspend fun insert(event: UsageEventEntity): Long {
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

internal object UnlimitedBudgetSettings : BudgetSettingsStore {
    private val state = MutableStateFlow(BudgetSettings())
    override fun observe(): StateFlow<BudgetSettings> = state
    override suspend fun setGlobalLimits(limits: BudgetLimits): Boolean = true
    override suspend fun setProviderLimits(id: ProviderId, limits: BudgetLimits): Boolean = true
    override suspend fun setSoftThresholdPct(pct: Int): Boolean = true
}

internal object SilentBudgetAlerts : BudgetAlerts {
    override suspend fun notify(title: String, text: String) = Unit
}
