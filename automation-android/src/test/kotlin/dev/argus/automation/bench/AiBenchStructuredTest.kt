package dev.argus.automation.bench

import dev.argus.brain.OpenAICompatTransport
import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderId
import dev.argus.brain.StructuredOutputMode
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * 0.5.1 F3b: il banco misura i due modi di leggere il compile, l'output strutturato nativo e il protocollo
 * `@@META@@`, con `ARGUS_BENCH_STRUCTURED=on|off|auto` (`auto` = come l'app). Risposte registrate, nessuna rete.
 */
class AiBenchStructuredTest {
    private fun optionsFor(structured: StructuredSetting) =
        BenchOptions("mock-model", "http://unused.invalid/v1", repeat = 1, filter = null, parallel = 1, structured = structured)

    @Test
    fun `the setting comes from the environment and defaults to auto`() {
        assertEquals(StructuredSetting.AUTO, BenchOptions.fromEnv { null }.structured)
        assertEquals(StructuredSetting.AUTO, BenchOptions.fromEnv { if (it == "ARGUS_BENCH_STRUCTURED") "  " else null }.structured)
        listOf("on" to StructuredSetting.ON, "OFF" to StructuredSetting.OFF, "Auto" to StructuredSetting.AUTO)
            .forEach { (raw, expected) ->
                assertEquals(expected, BenchOptions.fromEnv { if (it == "ARGUS_BENCH_STRUCTURED") raw else null }.structured, raw)
            }
        assertFailsWith<BenchException> { BenchOptions.fromEnv { if (it == "ARGUS_BENCH_STRUCTURED") "si" else null } }
    }

    @Test
    fun `auto leaves the catalog as the app has it and on and off override only the structured flag`() {
        val catalog = ProviderCatalog.spec(ProviderId.OPENROUTER)
        assertSame(catalog, optionsFor(StructuredSetting.AUTO).applyTo(catalog))

        val on = optionsFor(StructuredSetting.ON).applyTo(ProviderCatalog.spec(ProviderId.HERMES))
        assertEquals(StructuredOutputMode.RESPONSE_FORMAT, on.quirks.structuredOutput)
        val off = optionsFor(StructuredSetting.OFF).applyTo(catalog)
        assertEquals(StructuredOutputMode.NONE, off.quirks.structuredOutput)
        // Il resto del provider non cambia.
        assertEquals(catalog.quirks.copy(structuredOutput = StructuredOutputMode.NONE), off.quirks)
        assertEquals(catalog.defaultModels, off.defaultModels)
    }

    @Test
    fun `the bench runs both modes against recorded answers and reports which one was used`() {
        val cases = BenchCases.parseCompile(CASES)
        val usedStructured = mutableMapOf<StructuredSetting, Boolean?>()
        for (setting in StructuredSetting.entries) {
            FakeChatServer { _, body -> answer(body) }.use { server ->
                val world = runBlocking { BenchWorld.create() }
                val options = optionsFor(setting).copy(baseUrl = server.baseUrl)
                val config = ProviderConfig(ProviderId.CUSTOM_OPENAI_COMPAT, server.baseUrl, "mock-model")
                val transport = OpenAICompatTransport(
                    providerId = ProviderId.CUSTOM_OPENAI_COMPAT,
                    spec = options.applyTo(ProviderCatalog.spec(ProviderId.CUSTOM_OPENAI_COMPAT)),
                    config = config,
                    apiKey = { "test-key" },
                    client = OkHttpClient(),
                )
                val results = runBlocking {
                    BenchRunner(transport, config, world, options) { LocalDate.of(2026, 10, 26) }.run(cases, emptyList())
                }

                val run = results.single()
                assertTrue(run.passed, "$setting: ${run.failures}")
                usedStructured[setting] = run.structured
                val sentFormat = "\"response_format\"" in server.requests.single()
                assertEquals(run.structured, sentFormat, "$setting: la richiesta e la lettura coincidono")

                val meta = BenchMeta(
                    model = options.model, baseUrl = options.baseUrl, repeat = 1, parallel = 1, filter = null,
                    startedAt = "s", finishedAt = "f", compileCases = 1, actCases = 0,
                    structured = setting.wire,
                )
                val summary = BenchReport.toMarkdown(meta, results)
                assertTrue("Output strutturato: `${setting.wire}`" in summary, summary)
                val used = if (run.structured == true) 1 else 0
                assertTrue("Output strutturato nativo usato in $used di 1 compile" in summary, summary)
                val json: JsonObject = BenchReport.toJson(meta, results)
                assertEquals(setting.wire, json.getValue("meta").jsonObject.getValue("structured").jsonPrimitive.content)
                assertEquals(used, json.getValue("totals").jsonObject.getValue("structuredUsed").jsonPrimitive.int)
                assertEquals(
                    run.structured,
                    json.getValue("results").jsonArray.single().jsonObject.getValue("structured").jsonPrimitive.content.toBoolean(),
                )
            }
        }
        // `off` e' il solo protocollo base; `on` e `auto` (il Custom del catalogo lo prevede) leggono l'envelope.
        assertEquals(false, usedStructured[StructuredSetting.OFF])
        assertEquals(true, usedStructured[StructuredSetting.ON])
        assertEquals(true, usedStructured[StructuredSetting.AUTO])
    }

    @Test
    fun `an act has no structured flag`() {
        assertNull(
            RunResult(
                caseId = "a", kind = "act", category = "act.plain", lang = "it", manifest = null, repetition = 1,
                passed = true, actual = "text", expected = "text", failures = emptyList(), retryUsed = false,
                attempts = 1, tokensIn = null, tokensOut = null, latencyMs = 1L, metaError = null, reply = null,
                raw = null,
            ).structured,
        )
        assertFalse(
            BenchReport.toMarkdown(
                BenchMeta("m", "u", 1, 1, null, "s", "f", 0, 1),
                emptyList(),
            ).contains("usato in 1 di"),
        )
    }

    /** Envelope se la richiesta chiede `response_format`, riga `@@META@@` altrimenti. */
    private fun answer(body: String): FakeChatServer.Reply {
        val content = if ("\"response_format\"" in body) {
            """{"reply":"Ecco la regola.","draft":$DRAFT,"error_code":null}"""
        } else {
            "Ecco la regola.\n@@META@@ {\"draft\":$DRAFT,\"error_code\":null}"
        }
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
        const val DRAFT =
            """{"name":"DND off","trigger":{"type":"immediate"},"actions":[{"type":"set_dnd","mode":"OFF"}]}"""

        val CASES = """
            [
              {"id":"s.ok","category":"alpha","lang":"it","text":"Spegni subito il non disturbare",
               "expect":{"outcome":"draft","trigger":{"type":"immediate"},"actions_include":["set_dnd"],
                         "params":{"set_dnd.mode":"OFF"}}}
            ]
        """.trimIndent()
    }
}
