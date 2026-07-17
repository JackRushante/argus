package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.ApprovedStateContext
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.model.StateValueType
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenAICompatTransportTest {
    private lateinit var server: MockWebServer

    @BeforeTest fun setUp() { server = MockWebServer().apply { start() } }
    @AfterTest fun tearDown() { runCatching { server.shutdown() } }

    private fun transport(
        providerId: ProviderId,
        model: String? = null,
        client: OkHttpClient = CliBridgeTransport.defaultClient(),
        apiKey: String? = API_KEY,
    ): OpenAICompatTransport {
        val spec = ProviderCatalog.spec(providerId)
        return OpenAICompatTransport(
            providerId = providerId,
            spec = spec,
            config = ProviderConfig(providerId, server.url("/").toString().trimEnd('/'), model),
            apiKey = { apiKey },
            client = client,
            allowCleartextForTests = true,
        )
    }

    @Test fun `openai tool call reply parses text usage and uses max_completion_tokens`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"id":"chatcmpl-1","object":"chat.completion","model":"gpt-5.5",
             "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
               "tool_calls":[{"id":"call_1","type":"function","function":{"name":"whatsapp_reply","arguments":"{\"text\":\"Va bene, a dopo.\"}"}}]}}],
             "usage":{"prompt_tokens":120,"completion_tokens":8,"prompt_tokens_details":{"cached_tokens":64}}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val result = t.act(fireContext(), "rispondi cordiale", listOf("notification"), listOf("whatsapp_reply"))

        assertEquals("Va bene, a dopo.", result.text)
        assertNull(result.metaError)
        val usage = assertNotNull(result.usage)
        assertEquals(120L, usage.inputTokens)
        assertEquals(8L, usage.outputTokens)
        assertEquals(64L, usage.cachedInputTokens)
        assertEquals("gpt-5.5", usage.model)

        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/chat/completions", request.path)
        assertEquals("Bearer $API_KEY", request.getHeader("Authorization"))
        val root = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("gpt-5.5", root.getValue("model").jsonPrimitive.content)
        // gpt-5.x rifiuta max_tokens: quirk outputCapParam
        assertTrue("max_completion_tokens" in root)
        assertFalse("max_tokens" in root)
        val messages = root.getValue("messages").jsonArray
        assertEquals("system", messages.first().jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("user", messages.last().jsonObject.getValue("role").jsonPrimitive.content)
        val tool = root.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject
        assertEquals("whatsapp_reply", tool.getValue("name").jsonPrimitive.content)
        val toolChoice = root.getValue("tool_choice").jsonObject
        assertEquals("whatsapp_reply", toolChoice.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test fun `gemini plain content reply is accepted and uses max_tokens`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"id":"1","model":"gemini-2.5-flash",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Ciao, arrivo!"}}],
             "usage":{"prompt_tokens":30,"completion_tokens":4}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.GEMINI, model = "gemini-2.5-flash")

        val result = t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))

        assertEquals("Ciao, arrivo!", result.text)
        val usage = assertNotNull(result.usage)
        assertEquals(30L, usage.inputTokens)
        assertEquals(4L, usage.outputTokens)
        assertNull(usage.cachedInputTokens)
        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertTrue("max_tokens" in root)
        assertFalse("max_completion_tokens" in root)
    }

    @Test fun `usage is attached even when the reply is empty`(): Unit = runBlocking {
        // choices senza testo né tool_call, ma con usage: i token sono stati consumati e devono
        // viaggiare con l'ActResult anche sul ramo empty_response (S13 li deve poter contare).
        server.enqueue(jsonResponse(
            """
            {"id":"1","model":"gpt-5.5",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":null}}],
             "usage":{"prompt_tokens":77,"completion_tokens":0}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val result = t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))

        assertNull(result.text)
        assertEquals("empty_response", result.metaError)
        val usage = assertNotNull(result.usage)
        assertEquals(77L, usage.inputTokens)
        assertEquals(0L, usage.outputTokens)
    }

    @Test fun `openrouter default model is resolved from catalog`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"model":"openai/gpt-5.5",
             "choices":[{"index":0,"message":{"role":"assistant",
               "tool_calls":[{"id":"c1","type":"function","function":{"name":"whatsapp_reply","arguments":"{\"text\":\"Ok!\"}"}}]}}],
             "usage":{"prompt_tokens":10,"completion_tokens":2}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENROUTER, model = null)

        val result = t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))

        assertEquals("Ok!", result.text)
        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertEquals("openai/gpt-5.5", root.getValue("model").jsonPrimitive.content)
    }

    // --- #52 F3: web search server-side sui provider diretti ---

    @Test fun `openrouter web search appends online suffix and forces tool_choice auto`(): Unit = runBlocking {
        // Con web richiesto, OpenRouter attiva il loop server-side via slug `<model>:online`; il reply
        // NON va forzato (impedirebbe la ricerca) e il testo finale post-web arriva come content.
        server.enqueue(jsonResponse(
            """
            {"model":"openai/gpt-5.5",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Il cambio EUR/USD e' 1.08."}}],
             "usage":{"prompt_tokens":50,"completion_tokens":10}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENROUTER, model = "openai/gpt-5.5")

        val result = t.act(
            fireContext(), "dimmi il cambio euro dollaro", listOf("notification"),
            listOf("whatsapp_reply", "web.search"),
        )

        assertEquals("Il cambio EUR/USD e' 1.08.", result.text)
        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertEquals("openai/gpt-5.5:online", root.getValue("model").jsonPrimitive.content)
        assertEquals("auto", root.getValue("tool_choice").jsonPrimitive.content)
        // il reply tool resta dichiarato accanto al web server-side.
        val tool = root.getValue("tools").jsonArray.single().jsonObject.getValue("function").jsonObject
        assertEquals("whatsapp_reply", tool.getValue("name").jsonPrimitive.content)
        assertFalse("extra_body" in root)
    }

    @Test fun `openrouter without web keeps forced reply and plain model`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"model":"openai/gpt-5.5",
             "choices":[{"index":0,"message":{"role":"assistant",
               "tool_calls":[{"id":"c1","type":"function","function":{"name":"whatsapp_reply","arguments":"{\"text\":\"Ok!\"}"}}]}}],
             "usage":{"prompt_tokens":10,"completion_tokens":2}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENROUTER, model = "openai/gpt-5.5")

        t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))

        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertEquals("openai/gpt-5.5", root.getValue("model").jsonPrimitive.content)
        val toolChoice = root.getValue("tool_choice").jsonObject
        assertEquals("whatsapp_reply", toolChoice.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test fun `gemini ignores web when mechanism is NONE and keeps forced reply`(): Unit = runBlocking {
        // Web = NONE per Gemini: il grounding via shim OpenAI-compat non e' raggiungibile (smoke live
        // 2026-07-17: extra_body.google.tools da 400 anche su gemini-3). Degradazione graziosa: nessun
        // extra_body, modello invariato, reply forzato — nessuna richiesta che darebbe 400.
        server.enqueue(jsonResponse(
            """
            {"model":"gemini-2.5-flash",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Oggi a Roma 28 gradi."}}],
             "usage":{"prompt_tokens":40,"completion_tokens":8}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.GEMINI, model = "gemini-2.5-flash")

        val result = t.act(
            fireContext(), "che tempo fa a Roma", listOf("notification"),
            listOf("whatsapp_reply", "web.search"),
        )

        assertEquals("Oggi a Roma 28 gradi.", result.text)
        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertEquals("gemini-2.5-flash", root.getValue("model").jsonPrimitive.content)
        assertFalse("extra_body" in root)
    }

    @Test fun `openai ignores web when mechanism is NONE and keeps forced reply`(): Unit = runBlocking {
        // Degradazione graziosa: web richiesto ma non supportato via /chat/completions con gpt-5.x →
        // si genera normalmente col reply forzato, nessun extra_body, nessun cambio di modello.
        server.enqueue(jsonResponse(
            """
            {"model":"gpt-5.5",
             "choices":[{"index":0,"message":{"role":"assistant",
               "tool_calls":[{"id":"c1","type":"function","function":{"name":"whatsapp_reply","arguments":"{\"text\":\"Ok!\"}"}}]}}],
             "usage":{"prompt_tokens":10,"completion_tokens":2}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val result = t.act(
            fireContext(), "rispondi", listOf("notification"),
            listOf("whatsapp_reply", "web.search"),
        )

        assertEquals("Ok!", result.text)
        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertEquals("gpt-5.5", root.getValue("model").jsonPrimitive.content)
        assertFalse("extra_body" in root)
        val toolChoice = root.getValue("tool_choice").jsonObject
        assertEquals("whatsapp_reply", toolChoice.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test fun `actV2 sends the classified state value in the prompt`(): Unit = runBlocking {
        val query = StateQuery.DumpsysField("battery", "voltage")
        val action = Action.InvokeLlmV2(
            goal = "rispondi tenendo conto del voltaggio",
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
        server.enqueue(jsonResponse(
            """
            {"model":"gpt-5.5",
             "choices":[{"index":0,"message":{"role":"assistant",
               "tool_calls":[{"id":"c1","type":"function","function":{"name":"whatsapp_reply","arguments":"{\"text\":\"Ricarico ora.\"}"}}]}}],
             "usage":{"prompt_tokens":40,"completion_tokens":5}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val result = t.actV2(
            fireContext().copy(state = fireContext().state.copy(queryValues = mapOf(query.canonicalId to "4200"))),
            action,
        )

        assertEquals("Ricarico ora.", result.text)
        val raw = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
        assertTrue(raw.contains("4200"), "il valore di stato classificato deve entrare nel prompt")
    }

    @Test fun `401 maps to AUTH without leaking the key`(): Unit = runBlocking {
        server.enqueue(jsonResponse("""{"error":{"message":"Invalid Authorization header"}}""").setResponseCode(401))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val error = assertFailsWith<TransportException> {
            t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
        }

        assertEquals(TransportErrorKind.AUTH, error.kind)
        assertFalse(error.message.orEmpty().contains(API_KEY))
        // La chiave viaggia sul wire ma non deve trapelare dall'eccezione.
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("Bearer $API_KEY", request.getHeader("Authorization"))
    }

    @Test fun `payment and rate limit and server errors are typed`(): Unit = runBlocking {
        val cases = mapOf(
            402 to TransportErrorKind.BUDGET,
            429 to TransportErrorKind.RATE_LIMIT,
            404 to TransportErrorKind.HTTP,
            500 to TransportErrorKind.HTTP,
        )
        cases.forEach { (code, kind) ->
            server.enqueue(jsonResponse("""{"error":{"message":"x"}}""").setResponseCode(code))
            val t = transport(ProviderId.OPENAI, model = "gpt-5.5")
            val error = assertFailsWith<TransportException> {
                t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
            }
            assertEquals(kind, error.kind, "code $code")
            assertFalse(error.message.orEmpty().contains(API_KEY))
        }
    }

    @Test fun `timeout is typed`(): Unit = runBlocking {
        val fast = OkHttpClient.Builder()
            .callTimeout(250, TimeUnit.MILLISECONDS)
            .readTimeout(250, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5", client = fast)

        val error = assertFailsWith<TransportException> {
            t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
        }

        assertEquals(TransportErrorKind.TIMEOUT, error.kind)
    }

    @Test fun `missing api key fails before any network call`(): Unit = runBlocking {
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5", apiKey = null)

        val error = assertFailsWith<TransportException> {
            t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
        }

        assertEquals(TransportErrorKind.CONFIGURATION, error.kind)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `non whatsapp or group notifications are rejected before network`(): Unit = runBlocking {
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        assertFailsWith<TransportException> {
            t.act(fireContext(isGroup = true), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
        }.also { assertEquals(TransportErrorKind.CONFIGURATION, it.kind) }
        assertFailsWith<TransportException> {
            t.act(fireContext(), "rispondi", listOf("notification"), listOf("shell.run"))
        }.also { assertEquals(TransportErrorKind.CONFIGURATION, it.kind) }
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    // --- S6: compile client-side (prompt Hermes + sentinel @@META@@) ---

    @Test fun `compile parses the sentinel draft and posts to chat completions`(): Unit = runBlocking {
        val content = "Ho creato l'automazione per il silenzioso serale.\n" +
            "@@META@@ {\"draft\": {\"name\":\"dnd sera\"," +
            "\"trigger\":{\"type\":\"time\",\"cron\":\"0 23 * * *\",\"tz\":\"Europe/Rome\"}," +
            "\"actions\":[{\"type\":\"set_dnd\",\"mode\":\"PRIORITY\"}]}}"
        server.enqueue(jsonResponse(chatContent(content)))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val result = t.compile("metti in silenzioso alle 23", manifest(), deviceState())

        val draft = assertNotNull(result.draft)
        assertEquals("dnd sera", draft.name)
        assertNull(result.metaError)
        assertTrue(result.reply.startsWith("Ho creato"))

        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/chat/completions", request.path)
        assertEquals("Bearer $API_KEY", request.getHeader("Authorization"))
        val root = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        // gpt-5.x: quirk outputCapParam -> max_completion_tokens, mai max_tokens.
        assertTrue("max_completion_tokens" in root)
        assertFalse("max_tokens" in root)
        // Nessun tool: la strada primaria del compile è la riga sentinel nel testo.
        assertFalse("tools" in root)
        val prompt = root.getValue("messages").jsonArray.joinToString("\n") {
            it.jsonObject.getValue("content").jsonPrimitive.content
        }
        assertTrue(prompt.contains("REGOLE VINCOLANTI"), "il prompt deve includere le regole Hermes")
        assertTrue(prompt.contains("@@META@@"), "il prompt deve richiedere la riga sentinel")
        assertTrue(prompt.contains("AutomationDraft"), "il prompt deve includere lo schema draft")
        assertTrue(prompt.contains("metti in silenzioso alle 23"), "il messaggio utente deve entrare nel prompt")
    }

    @Test fun `compile clarification keeps draft null and preserves the reply`(): Unit = runBlocking {
        val content = "Quale contatto vuoi avvisare?\n" +
            "@@META@@ {\"draft\":null,\"error_code\":\"clarification_required\"}"
        server.enqueue(jsonResponse(chatContent(content)))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val result = t.compile("avvisa quando arrivo", manifest(), deviceState())

        assertNull(result.draft)
        assertNotNull(result.metaError)
        assertTrue(result.reply.contains("Quale contatto"))
    }

    @Test fun `compile without sentinel yields a typed metaError and never crashes`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chatContent("Ciao! Come posso aiutarti oggi?")))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val result = t.compile("ciao", manifest(), deviceState())

        assertNull(result.draft)
        assertEquals("draft_missing", result.metaError)
        assertTrue(result.reply.contains("Ciao"))
    }

    @Test fun `compile with malformed meta yields metaError without crashing`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chatContent("ok @@META@@ {non json}")))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val result = t.compile("crea qualcosa", manifest(), deviceState())

        assertNull(result.draft)
        assertNotNull(result.metaError)
    }

    @Test fun `compile with empty content yields empty_response metaError`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chatContent("")))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val result = t.compile("crea qualcosa", manifest(), deviceState())

        assertNull(result.draft)
        assertEquals("empty_response", result.metaError)
    }

    @Test fun `compile rejects an empty message before any network call`(): Unit = runBlocking {
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val error = assertFailsWith<TransportException> {
            t.compile("   ", manifest(), deviceState())
        }

        assertEquals(TransportErrorKind.CONFIGURATION, error.kind)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    private fun chatContent(content: String): String = buildJsonObject {
        put("id", "chatcmpl-c1")
        put("model", "gpt-5.5")
        putJsonArray("choices") {
            addJsonObject {
                put("index", 0)
                put("finish_reason", "stop")
                putJsonObject("message") {
                    put("role", "assistant")
                    put("content", content)
                }
            }
        }
        putJsonObject("usage") {
            put("prompt_tokens", 200)
            put("completion_tokens", 40)
        }
    }.toString()

    private fun manifest(): CapabilityManifest = CapabilityManifest(
        deviceModel = "Pixel 9",
        androidVersion = 16,
        androidApi = 36,
        shizukuAvailable = true,
        grantedPermissions = listOf("android.permission.POST_NOTIFICATIONS"),
        availableTools = listOf("set_dnd", "whatsapp_reply", "invoke_llm"),
        unavailableTools = emptyMap(),
        whitelistedContacts = listOf(WhitelistedContact("Moglie", "jid:private")),
    )

    private fun deviceState(): DeviceState = DeviceState(
        values = mapOf("ringer" to "normal"),
        foregroundApp = "com.whatsapp",
    )

    private fun jsonResponse(body: String) = MockResponse()
        .addHeader("Content-Type", "application/json; charset=utf-8")
        .setBody(body)

    private fun fireContext(isGroup: Boolean = false) = FireContext(
        event = TriggerEvent.NotificationPosted(
            pkg = "com.whatsapp",
            conversationId = "jid:private",
            sender = "Moglie",
            title = "Moglie",
            text = "ciao",
            isGroup = isGroup,
            notificationKey = "sbn:private",
        ),
        state = DeviceState(
            values = mapOf("ringer" to "normal", "private_state" to "secret"),
            foregroundApp = "com.whatsapp",
        ),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    private companion object {
        const val API_KEY = "sk-test-secret-key-do-not-leak-0123456789"
    }
}
