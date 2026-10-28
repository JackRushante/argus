package dev.argus.automation.bench

import dev.argus.brain.CliBridgeTransport
import dev.argus.brain.OpenAICompatTransport
import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions
import java.io.File
import java.time.Instant
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.fail

/**
 * Banco di prova AI: manda richieste VERE al provider vero con la configurazione reale e misura
 * quante bozze escono giuste. Parte solo con `ARGUS_BENCH_KEY` nell'ambiente; senza chiave viene
 * saltato, cosi' i gate normali (`testDebugUnitTest`) non toccano mai la rete.
 *
 * Variabili: `ARGUS_BENCH_KEY` (chiave OpenRouter), `ARGUS_BENCH_MODEL` (default `@preset/argus`),
 * `ARGUS_BENCH_BASE_URL` (default `https://openrouter.ai/api/v1`), `ARGUS_BENCH_REPEAT` (default 1),
 * `ARGUS_BENCH_FILTER` (uno o più prefissi degli id dei casi, separati da virgola), `ARGUS_BENCH_PARALLEL` (default 4),
 * `ARGUS_BENCH_REASONING` (`none`, `low`, `medium` o `high`; assente = campo omesso),
 * `ARGUS_BENCH_STRUCTURED` (`on`, `off` o `auto`; default `auto` = come l'app: output strutturato nativo del
 * compile se il catalogo lo prevede. `off` misura il solo protocollo `@@META@@`, `on` lo forza).
 *
 * Il rapporto esce in `build/reports/ai-bench/` (`report.json`, `summary.md`). Il test NON fallisce
 * per una percentuale bassa: misura e basta. Fallisce solo per errori del banco stesso (caso
 * malformato, eccezione) o se la chiave e' rifiutata da tutte le chiamate (niente da misurare).
 */
class AiBenchTest {
    @Test
    fun `ai bench against the real provider`() {
        val key = System.getenv(KEY_VARIABLE)?.trim().orEmpty()
        Assumptions.assumeTrue(key.isNotEmpty(), "$KEY_VARIABLE non impostata: banco AI saltato")

        val options = BenchOptions.fromEnv(System::getenv)
        val compileCases = BenchCases.loadCompile()
        val actCases = BenchCases.loadAct()
        val selectedCompile = compileCases.filter { options.selects(it.id) }
        val selectedAct = actCases.filter { options.selects(it.id) }
        if (selectedCompile.isEmpty() && selectedAct.isEmpty()) {
            throw BenchException("ARGUS_BENCH_FILTER '${options.filter}' non seleziona alcun caso")
        }

        // L'orario dei casi e' quello del telefono: il fuso predefinito della JVM finisce nel prompt
        // (`timezone: ...`) e deve combaciare con quello che i casi si aspettano.
        val previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone(BenchWorld.ZONE_ID))
        try {
            val startedAt = Instant.now().toString()
            val results = runBlocking {
                val world = BenchWorld.create()
                val config = ProviderConfig(ProviderId.OPENROUTER, options.baseUrl, options.model, options.reasoning)
                val transport = OpenAICompatTransport(
                    providerId = ProviderId.OPENROUTER,
                    spec = options.applyTo(ProviderCatalog.spec(ProviderId.OPENROUTER)),
                    config = config,
                    apiKey = { key },
                    client = CliBridgeTransport.defaultClient(),
                )
                BenchRunner(transport, config, world, options).run(selectedCompile, selectedAct)
            }
            val meta = BenchMeta(
                model = options.model,
                baseUrl = options.baseUrl,
                repeat = options.repeat,
                parallel = options.parallel,
                filter = options.filter,
                startedAt = startedAt,
                finishedAt = Instant.now().toString(),
                compileCases = selectedCompile.size,
                actCases = selectedAct.size,
                reasoning = options.reasoning.wireValue ?: "default",
                structured = options.structured.wire,
            )
            val reportDir = File(REPORT_DIR)
            BenchReport.write(reportDir, meta, results)
            println(File(reportDir, "summary.md").readText(Charsets.UTF_8))

            val benchErrors = results.filter { run -> run.failures.any { it.category == FailureCategory.BENCH_ERROR } }
            if (benchErrors.isNotEmpty()) {
                fail(
                    "errori del banco in ${benchErrors.size} esecuzioni (vedi ${reportDir.path}/report.json): " +
                        benchErrors.take(5).joinToString { "${it.caseId}: ${it.failures.first().message}" },
                )
            }
            val rejected = setOf("bridge_auth", "bridge_configuration")
            if (results.isNotEmpty() && results.all { it.metaError in rejected }) {
                fail("tutte le chiamate rifiutate (${results.first().metaError}): controlla ARGUS_BENCH_KEY e ARGUS_BENCH_BASE_URL")
            }
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    private companion object {
        const val KEY_VARIABLE = "ARGUS_BENCH_KEY"
        const val REPORT_DIR = "build/reports/ai-bench"
    }
}
