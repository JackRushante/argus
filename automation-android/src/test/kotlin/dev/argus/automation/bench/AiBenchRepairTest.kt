package dev.argus.automation.bench

import dev.argus.brain.OpenAICompatTransport
import dev.argus.brain.ProviderCatalog
import dev.argus.brain.ProviderConfig
import dev.argus.brain.ProviderId
import dev.argus.engine.model.Action
import dev.argus.engine.model.ArgusJson
import dev.argus.engine.model.AutomationDraft
import dev.argus.engine.model.Trigger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Il banco passa dalla stessa logica dell'app per il giro di riparazione sugli errori del validatore
 * (0.5.0) e lo conta a parte dal ritentativo di formato. Risposte REGISTRATE, nessuna rete.
 */
class AiBenchRepairTest {
    private val requests = java.util.concurrent.CopyOnWriteArrayList<String>()

    @Test
    fun `the bench repairs a validator error once and counts it apart from the format retry`() {
        val results = runCases()
        fun of(id: String) = results.single { it.caseId == id }

        val repaired = of("r.repaired")
        assertTrue(repaired.passed, repaired.failures.toString())
        assertTrue(repaired.repairUsed)
        assertFalse(repaired.retryUsed, "non e' un errore di formato")
        assertEquals(2, repaired.attempts)

        // Se anche la bozza riparata ha errori resta com'e', con la sua riparazione sola (mai un secondo giro).
        val stubborn = of("r.stubborn")
        assertTrue(stubborn.repairUsed)
        assertFalse(stubborn.retryUsed)
        assertEquals(2, stubborn.attempts)
        assertFalse(stubborn.passed)
        assertTrue(stubborn.failures.any { it.category == FailureCategory.VALIDATOR }, stubborn.failures.toString())

        // Una bozza valida non costa un secondo giro.
        val clean = of("r.clean")
        assertTrue(clean.passed, clean.failures.toString())
        assertFalse(clean.repairUsed)
        assertFalse(clean.retryUsed)
        assertEquals(1, clean.attempts)

        // La nota porta codice e messaggio inglese del validatore, e non i dati della richiesta oltre al suo testo.
        val note = requests.first { "failed validation" in it && "Fra 5 minuti" in it }
        assertTrue("after_ms_invalid: The delay must be between 1 second and 7 days." in note, note)
        assertTrue("Compile the request again avoiding exactly these errors" in note, note)

        val meta = BenchMeta("m", "u", 1, 1, null, "s", "f", 3, 0)
        val totals = BenchReport.toJson(meta, results).getValue("totals").jsonObject
        assertEquals(2, totals.getValue("repairUsed").jsonPrimitive.int)
        assertEquals(0, totals.getValue("retryUsed").jsonPrimitive.int)
        val markdown = BenchReport.toMarkdown(meta, results)
        assertTrue("Giro di riparazione sugli errori del validatore usato in 2 di 3 compile" in markdown, markdown)
    }

    private fun runCases(): List<RunResult> {
        lateinit var out: List<RunResult>
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
                repeat = 1,
                filter = null,
                parallel = 1,
            )
            val runner = BenchRunner(transport, config, world, options) { LocalDate.of(2026, 10, 26) }
            out = runBlocking { runner.run(BenchCases.parseCompile(CASES), emptyList()) }
        }
        return out
    }

    private fun answer(body: String): FakeChatServer.Reply {
        val user = lastUserMessage(body)
        val request = user.substringAfter(REQUEST_START).substringBefore(REQUEST_END).substringAfter("User request:\n")
        requests += request
        val repair = "failed validation" in request
        return when {
            // Prima un ritardo di 500 ms (sotto il minimo di 1 s: after_ms_invalid), dopo la nota 5 minuti.
            request.startsWith("Fra 5 minuti") -> ok(vibrate(if (repair) 300_000 else 500))
            // Sbaglia anche dopo la nota.
            request.startsWith("Fra 7 minuti") -> ok(vibrate(500))
            request.startsWith("Fra 9 minuti") -> ok(vibrate(540_000))
            else -> FakeChatServer.Reply(500, "{}")
        }
    }

    private fun vibrate(afterMs: Long): String {
        val draft = AutomationDraft("Vibra", Trigger.Time(afterMs = afterMs, tz = "Europe/Rome"), listOf(Action.Vibrate(500)))
        val meta = buildJsonObject {
            put("draft", ArgusJson.encodeToJsonElement(AutomationDraft.serializer(), draft))
            put("error_code", JsonNull)
        }
        return "Ecco la regola proposta.\n@@META@@ $meta"
    }

    private fun lastUserMessage(body: String): String {
        val messages = Json.parseToJsonElement(body).jsonObject.getValue("messages")
        return (messages as kotlinx.serialization.json.JsonArray).map { it.jsonObject }
            .last { it.getValue("role").jsonPrimitive.content == "user" }
            .getValue("content").jsonPrimitive.content
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

        val CASES = """
            [
              {"id":"r.repaired","category":"alpha","lang":"it","text":"Fra 5 minuti fai vibrare il telefono",
               "expect":{"outcome":"draft","trigger":{"type":"time","afterMs":300000},"actions_include":["vibrate"]}},
              {"id":"r.stubborn","category":"alpha","lang":"it","text":"Fra 7 minuti fai vibrare il telefono",
               "expect":{"outcome":"draft","trigger":{"type":"time"},"actions_include":["vibrate"]}},
              {"id":"r.clean","category":"alpha","lang":"it","text":"Fra 9 minuti fai vibrare il telefono",
               "expect":{"outcome":"draft","trigger":{"type":"time","afterMs":540000},"actions_include":["vibrate"]}}
            ]
        """.trimIndent()
    }
}
