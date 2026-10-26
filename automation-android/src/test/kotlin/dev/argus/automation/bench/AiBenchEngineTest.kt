package dev.argus.automation.bench

import dev.argus.brain.OpenAICompatTransport
import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderId
import dev.argus.engine.model.Action
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Prova il motore del banco contro un server locale con risposte REGISTRATE (nessuna rete): una
 * bozza giusta, una fuori formato che va ritentata, una sbagliata, un chiarimento inatteso, un errore
 * di trasporto, e due risposte generative. Verifica rapporto, percentuali e conteggi.
 */
class AiBenchEngineTest {
    @Test
    fun `engine measures recorded answers and writes exact report and percentages`() {
        val dir = Files.createTempDirectory("ai-bench-engine").toFile()
        try {
            FakeChatServer { _, body -> answer(body) }.use { server ->
                val world = runBlocking { BenchWorld.create() }
                val config = ProviderConfig(ProviderId.CUSTOM_OPENAI_COMPAT, server.baseUrl, "mock-model")
                val transport = OpenAICompatTransport(
                    providerId = ProviderId.CUSTOM_OPENAI_COMPAT,
                    spec = ProviderCatalog.spec(ProviderId.CUSTOM_OPENAI_COMPAT),
                    config = config,
                    apiKey = { "test-key" },
                    client = OkHttpClient(),
                )
                val options = BenchOptions(
                    model = "mock-model",
                    baseUrl = server.baseUrl,
                    repeat = 2,
                    filter = null,
                    parallel = 4,
                )
                val compile = BenchCases.parseCompile(COMPILE_CASES)
                val act = BenchCases.parseAct(ACT_CASES)
                val runner = BenchRunner(transport, config, world, options) { LocalDate.of(2026, 10, 26) }
                val results = runBlocking { runner.run(compile, act) }
                val meta = BenchMeta(
                    model = options.model,
                    baseUrl = options.baseUrl,
                    repeat = options.repeat,
                    parallel = options.parallel,
                    filter = null,
                    startedAt = "2026-10-26T10:00:00Z",
                    finishedAt = "2026-10-26T10:01:00Z",
                    compileCases = compile.size,
                    actCases = act.size,
                )
                BenchReport.write(dir, meta, results)

                assertResults(results)
                assertReportFiles(dir)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun assertResults(results: List<RunResult>) {
        assertEquals(16, results.size, "8 casi per 2 ripetizioni")
        fun runs(id: String) = results.filter { it.caseId == id }

        runs("t.ok").forEach {
            assertTrue(it.passed, it.failures.toString())
            assertFalse(it.retryUsed)
            assertEquals(1, it.attempts)
            assertNull(it.raw, "il testo grezzo esce solo per i fallimenti")
        }
        runs("t.retry").forEach {
            assertTrue(it.passed, "dopo il ritentativo la bozza e' giusta: ${it.failures}")
            assertTrue(it.retryUsed)
            assertEquals(2, it.attempts)
            assertEquals(18_000L, it.tokensIn, "i token dei due tentativi si sommano")
            assertEquals(200L, it.tokensOut)
        }
        runs("t.wrong").forEach {
            assertTrue(!it.passed)
            assertEquals(listOf(FailureCategory.WRONG_TRIGGER), it.failures.map { f -> f.category })
            assertNotNull(it.raw)
            assertTrue(it.raw!!.length <= BenchRunner.RAW_MAX_CHARS)
        }
        runs("t.unexpected").forEach {
            assertEquals(listOf("unexpected_draft"), it.failures.map { f -> f.category })
            assertEquals("draft", it.actual)
            assertEquals("clarification", it.expected)
        }
        runs("t.clarif").forEach { assertTrue(it.passed, it.failures.toString()) }
        runs("t.transport").forEach {
            assertEquals(listOf(FailureCategory.TRANSPORT), it.failures.map { f -> f.category })
            assertEquals("bridge_rate_limit", it.metaError)
            assertFalse(it.retryUsed, "un errore di rete non si ritenta")
        }
        runs("a.ok").forEach { assertTrue(it.passed, it.failures.toString()); assertEquals("act.web", it.category) }
        runs("a.link").forEach {
            assertEquals(listOf(FailureCategory.ACT_LINK), it.failures.map { f -> f.category })
            assertEquals("act.plain", it.category)
        }
        assertTrue(results.none { run -> run.failures.any { it.category == FailureCategory.BENCH_ERROR } })

        val total = BenchReport.total(results)
        assertEquals(Rate(8, 16), total)
        assertEquals(50.0, total.percent)
        assertEquals(Rate(8, 14), BenchReport.totalWithoutTransport(results))
        assertEquals(Rate(6, 12), BenchReport.total(results.filter { it.kind == "compile" }))
        val byCategory = BenchReport.rateBy(results) { it.category }
        assertEquals(Rate(4, 4), byCategory["alpha"])
        assertEquals(Rate(0, 4), byCategory["beta"])
        assertEquals(Rate(2, 2), byCategory["gamma"])
        assertEquals(Rate(0, 2), byCategory["delta"])
        assertEquals(Rate(2, 2), byCategory["act.web"])
        assertEquals(Rate(0, 2), byCategory["act.plain"])
        assertEquals(
            mapOf("act_link" to 2, "transport" to 2, "unexpected_draft" to 2, "wrong_trigger" to 2),
            BenchReport.failureCategories(results),
        )
    }

    private fun assertReportFiles(dir: File) {
        val report = Json.parseToJsonElement(File(dir, "report.json").readText()).jsonObject
        val totals = report.getValue("totals").jsonObject
        assertEquals(16, totals.getValue("runs").jsonPrimitive.int)
        assertEquals(8, totals.getValue("passed").jsonPrimitive.int)
        assertEquals(50.0, totals.getValue("percent").jsonPrimitive.double)
        assertEquals(2, totals.getValue("transportErrors").jsonPrimitive.int)
        assertEquals(57.1, totals.getValue("percentWithoutTransport").jsonPrimitive.double)
        assertEquals(2, totals.getValue("retryUsed").jsonPrimitive.int)
        assertEquals("mock-model", report.getValue("meta").jsonObject.getValue("model").jsonPrimitive.content)
        val byLang = report.getValue("byLang").jsonObject.getValue("it").jsonObject
        assertEquals(16, byLang.getValue("total").jsonPrimitive.int)

        val results = report.getValue("results").jsonArray
        assertEquals(16, results.size)
        results.map { it.jsonObject }.forEach { run ->
            val passed = run.getValue("passed").jsonPrimitive.content.toBoolean()
            val raw = run["raw"]
            if (passed) {
                assertTrue(raw == null || raw is JsonNull, "raw presente per ${run["caseId"]}")
            } else {
                val failures = run.getValue("failures").jsonArray
                assertTrue(failures.isNotEmpty())
            }
            assertTrue(run.getValue("latencyMs").jsonPrimitive.content.toLong() >= 0)
        }
        val failedWithRaw = results.map { it.jsonObject }.filter {
            !it.getValue("passed").jsonPrimitive.content.toBoolean() && it["raw"] !is JsonNull && it["raw"] != null
        }
        assertTrue(failedWithRaw.isNotEmpty(), "i fallimenti portano il testo grezzo")

        val summary = File(dir, "summary.md").readText()
        assertTrue("**Successo totale: 8/16 (50.0%)**" in summary, summary)
        assertTrue("Escludendoli: 8/14 (57.1%)" in summary, summary)
        assertTrue("| alpha | 4 | 4 | 100.0% |" in summary, summary)
        assertTrue("| beta | 0 | 4 | 0.0% |" in summary, summary)
        assertTrue("| wrong_trigger | 2 |" in summary, summary)
        assertTrue("| unexpected_draft | 2 |" in summary, summary)
        assertTrue("Ritentativo con nota di correzione usato in 2 di 12 compile" in summary, summary)
        assertTrue("`t.wrong` (ripetizione 1)" in summary, summary)
        assertTrue("## Casi instabili" in summary && "Nessuno" in summary, summary)
    }

    @Test
    fun `the request carries the case language and the installed apps it names, like the app sends it`() {
        val cases = BenchCases.parseCompile(
            """
            [
              {"id":"l.en","category":"x","lang":"en","text":"When I connect to the car Bluetooth BMW, open Google Maps",
               "expect":{"outcome":"no_draft"}},
              {"id":"l.it","category":"x","lang":"it","text":"Tra 5 minuti apri la Calcolatrice",
               "expect":{"outcome":"no_draft"}},
              {"id":"l.none","category":"x","lang":"en","text":"Turn off Wi-Fi in one minute",
               "expect":{"outcome":"no_draft"}}
            ]
            """.trimIndent(),
        )
        FakeChatServer { _, _ ->
            // Due parole: la lingua della risposta non si giudica (le tre lingue dei casi sono diverse).
            ok("Which one?\n@@META@@ {\"draft\":null,\"error_code\":\"clarification_required\"}")
        }.use { server ->
            val world = runBlocking { BenchWorld.create() }
            val config = ProviderConfig(ProviderId.CUSTOM_OPENAI_COMPAT, server.baseUrl, "mock-model")
            val transport = OpenAICompatTransport(
                providerId = ProviderId.CUSTOM_OPENAI_COMPAT,
                spec = ProviderCatalog.spec(ProviderId.CUSTOM_OPENAI_COMPAT),
                config = config,
                apiKey = { "test-key" },
                client = OkHttpClient(),
            )
            val options = BenchOptions("mock-model", server.baseUrl, repeat = 1, filter = null, parallel = 1)
            val results = runBlocking {
                BenchRunner(transport, config, world, options) { LocalDate.of(2026, 10, 26) }.run(cases, emptyList())
            }
            assertTrue(results.all { it.passed }, results.flatMap { it.failures }.toString())

            val sent = server.requests.map { lastUserMessage(it) }
            assertEquals(3, sent.size)
            fun context(user: String) = Json.parseToJsonElement(
                user.substringAfter("===== UNTRUSTED STRUCTURED CONTEXT =====\n").substringBefore("\n===== END CONTEXT ====="),
            ).jsonObject.getValue("manifest").jsonObject
            fun request(user: String) = user.substringAfter(REQUEST_START).substringBefore(REQUEST_END)
            val english = sent.single { "BMW" in it }
            val italian = sent.single { "Calcolatrice" in it }
            val neutral = sent.single { "Wi-Fi" in it }

            // La lingua dei testi per l'utente e' la `lang` del caso, come l'interfaccia dell'app.
            assertEquals("en", context(english).getValue("reply_language").jsonPrimitive.content)
            assertEquals("it", context(italian).getValue("reply_language").jsonPrimitive.content)
            assertEquals("en", context(neutral).getValue("reply_language").jsonPrimitive.content)

            // I candidati app sono quelli delle funzioni vere dell'app, con le etichette della lingua del caso.
            assertTrue(
                "installed_app_candidates=[{\"label\":\"Maps\",\"package\":\"com.google.android.apps.maps\"}]" in request(english),
                request(english),
            )
            assertTrue(
                "installed_app_candidates=[{\"label\":\"Calcolatrice\",\"package\":\"com.google.android.calculator\"}]" in request(italian),
                request(italian),
            )
            assertTrue(request(english).endsWith("When I connect to the car Bluetooth BMW, open Google Maps"))
            // Una richiesta che non nomina app arriva nuda: mai l'elenco intero.
            assertEquals("Turn off Wi-Fi in one minute", request(neutral))
            assertFalse("installed_app_candidates" in neutral)
        }
    }

    @Test
    fun `options come from the environment with the documented defaults`() {
        val defaults = BenchOptions.fromEnv { null }
        assertEquals("@preset/argus", defaults.model)
        assertEquals("https://openrouter.ai/api/v1", defaults.baseUrl)
        assertEquals(1, defaults.repeat)
        assertEquals(4, defaults.parallel)
        assertNull(defaults.filter)

        val env = mapOf(
            "ARGUS_BENCH_MODEL" to "deepseek/deepseek-v4",
            "ARGUS_BENCH_BASE_URL" to "https://example.org/api/v1",
            "ARGUS_BENCH_REPEAT" to "3",
            "ARGUS_BENCH_FILTER" to "time.",
            "ARGUS_BENCH_PARALLEL" to "8",
        )
        val custom = BenchOptions.fromEnv { env[it] }
        assertEquals(BenchOptions("deepseek/deepseek-v4", "https://example.org/api/v1", 3, "time.", 8), custom)

        assertFailsWith<BenchException> { BenchOptions.fromEnv { if (it == "ARGUS_BENCH_REPEAT") "tre" else null } }
        assertFailsWith<BenchException> { BenchOptions.fromEnv { if (it == "ARGUS_BENCH_PARALLEL") "0" else null } }
        assertEquals("@preset/argus", BenchOptions.fromEnv { if (it == "ARGUS_BENCH_MODEL") "  " else null }.model)
    }

    @Test
    fun `report math handles empty runs and repeated cases`() {
        assertEquals(0.0, Rate(0, 0).percent)
        assertEquals("1/3 (33.3%)", Rate(1, 3).format())
        assertNull(BenchReport.percentile(emptyList(), 0.5))
        assertEquals(30L, BenchReport.percentile(listOf(10L, 20L, 30L, 40L, 50L), 0.5))
        assertEquals(50L, BenchReport.percentile(listOf(10L, 20L, 30L, 40L, 50L), 0.95))

        fun run(id: String, rep: Int, passed: Boolean) = RunResult(
            caseId = id, kind = "compile", category = "c", lang = "it", manifest = "full", repetition = rep,
            passed = passed, actual = "draft", expected = "draft",
            failures = if (passed) emptyList() else listOf(Failure(FailureCategory.WRONG_ACTION, "manca")),
            retryUsed = false, attempts = 1, tokensIn = 10, tokensOut = 5, latencyMs = 100L,
            metaError = null, reply = null, raw = if (passed) null else "x".repeat(5_000),
        )
        val results = listOf(run("a", 1, true), run("a", 2, false), run("b", 1, true), run("b", 2, true))
        val meta = BenchMeta("m", "u", 2, 1, null, "s", "f", 2, 0)
        val summary = BenchReport.toMarkdown(meta, results)
        assertTrue("**Successo totale: 3/4 (75.0%)**" in summary, summary)
        assertTrue("- `a`: 1/2 riuscite" in summary, summary)
        assertTrue("b`: " !in summary.substringAfter("## Casi instabili").substringBefore("## Fallimenti"))
        // Il testo grezzo nel JSON e' sempre troncato anche se il chiamante dimentica di farlo.
        val json: JsonObject = BenchReport.toJson(meta, results)
        val raw = json.getValue("results").jsonArray.map { it.jsonObject }.first { it["raw"] !is JsonNull }
            .getValue("raw").jsonPrimitive.content
        assertTrue(raw.length <= BenchRunner.RAW_MAX_CHARS + 1)
    }

    // --- le risposte registrate -----------------------------------------------------------------

    private fun answer(body: String): FakeChatServer.Reply {
        val user = lastUserMessage(body)
        if (user.startsWith("Goal: ")) return generative(user)
        // Se la richiesta nomina un'app (qui «Imposta» somiglia a «Impostazioni») l'app antepone i candidati:
        // il testo dell'utente viene dopo «User request:».
        val request = user.substringAfter(REQUEST_START).substringBefore(REQUEST_END).substringAfter("User request:\n")
        val retry = "(Argus: your previous reply could not be used" in request
        return when {
            request.startsWith("Spegni subito il non disturbare") ->
                ok(proposal(AutomationDraft("DND off", Trigger.Immediate, listOf(Action.SetDnd(DndMode.OFF)))))
            // Primo tentativo senza la riga @@META@@ (il caso del telefono), poi giusto con la nota.
            request.startsWith("Accendi subito la torcia") -> if (retry) {
                ok(proposal(AutomationDraft("Torcia", Trigger.Immediate, listOf(Action.SetFlashlight(true)))))
            } else {
                ok("Accendo la torcia.\n{\"actions\":[{\"type\":\"set_flashlight\",\"on\":true}]}")
            }
            // Bozza valida ma con il ritardo sbagliato (1 minuto invece di 10).
            request.startsWith("Fra 10 minuti") -> ok(
                proposal(AutomationDraft("Vibra", Trigger.Time(afterMs = 60_000, tz = "Europe/Rome"), listOf(Action.Vibrate(500)))),
            )
            // Una bozza dove il caso si aspetta un chiarimento.
            request.startsWith("Imposta una sveglia") ->
                ok(proposal(AutomationDraft("Sveglia", Trigger.Immediate, listOf(Action.SetAlarm(7, 0)))))
            request.startsWith("Apri il link") ->
                ok("Quale indirizzo vuoi aprire?\n@@META@@ {\"draft\":null,\"error_code\":\"clarification_required\"}")
            request.startsWith("Tra poco") -> FakeChatServer.Reply(429, "{\"error\":{\"message\":\"slow down\"}}")
            else -> FakeChatServer.Reply(500, "{}")
        }
    }

    private fun generative(user: String): FakeChatServer.Reply = when {
        user.contains("bitcoin") -> ok("Il bitcoin vale circa 61.234 euro oggi.")
        user.contains("cambio") -> ok("Il cambio di oggi è 1,08 [fonte](https://example.com/cambio).")
        else -> FakeChatServer.Reply(500, "{}")
    }

    private fun lastUserMessage(body: String): String {
        val messages = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
        return messages.map { it.jsonObject }
            .last { it.getValue("role").jsonPrimitive.content == "user" }
            .getValue("content").jsonPrimitive.content
    }

    private fun proposal(draft: AutomationDraft): String {
        val meta = buildJsonObject {
            put("draft", ArgusJson.encodeToJsonElement(AutomationDraft.serializer(), draft))
            put("error_code", JsonNull)
        }
        return "Ecco la regola proposta.\n@@META@@ $meta"
    }

    private fun ok(content: String): FakeChatServer.Reply {
        val completion: JsonElement = buildJsonObject {
            put("model", "mock-model")
            putJsonArray("choices") {
                addJsonObject {
                    put("finish_reason", "stop")
                    putJsonObject("message") {
                        put("role", "assistant")
                        put("content", content)
                    }
                }
            }
            putJsonObject("usage") {
                put("prompt_tokens", 9_000)
                put("completion_tokens", 100)
            }
        }
        return FakeChatServer.Reply(200, completion.toString())
    }

    private companion object {
        const val REQUEST_START = "===== UNTRUSTED USER REQUEST =====\n"
        const val REQUEST_END = "\n===== END REQUEST ====="

        val COMPILE_CASES = """
            [
              {"id":"t.ok","category":"alpha","lang":"it","text":"Spegni subito il non disturbare",
               "expect":{"outcome":"draft","trigger":{"type":"immediate"},"actions_include":["set_dnd"],
                         "params":{"set_dnd.mode":"OFF"}}},
              {"id":"t.retry","category":"alpha","lang":"it","text":"Accendi subito la torcia",
               "expect":{"outcome":"draft","trigger":{"type":"immediate"},"actions_include":["set_flashlight"],
                         "params":{"set_flashlight.on":true}}},
              {"id":"t.wrong","category":"beta","lang":"it","text":"Fra 10 minuti fai vibrare il telefono",
               "expect":{"outcome":"draft","trigger":{"type":"time","afterMs":600000},"actions_include":["vibrate"]}},
              {"id":"t.unexpected","category":"beta","lang":"it","text":"Imposta una sveglia",
               "expect":{"outcome":"clarification"}},
              {"id":"t.clarif","category":"gamma","lang":"it","text":"Apri il link",
               "expect":{"outcome":"clarification"}},
              {"id":"t.transport","category":"delta","lang":"it","text":"Tra poco spegni il Wi-Fi",
               "expect":{"outcome":"draft","trigger":{"type":"time"},"actions_include":["set_wifi"]}}
            ]
        """.trimIndent()

        val ACT_CASES = """
            [
              {"id":"a.ok","lang":"it","goal":"Scrivi il prezzo del bitcoin in euro","web":true,"expect_number":true},
              {"id":"a.link","lang":"it","goal":"Dimmi il cambio euro dollaro di oggi","web":false,"expect_number":true}
            ]
        """.trimIndent()
    }
}
