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
import java.time.LocalDate
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
        clock: () -> LocalDate = { SEARCH_DAY },
        reasoning: ReasoningEffort = ReasoningEffort.DEFAULT,
    ): OpenAICompatTransport {
        val spec = ProviderCatalog.spec(providerId)
        return OpenAICompatTransport(
            providerId = providerId,
            spec = spec,
            config = ProviderConfig(providerId, server.url("/").toString().trimEnd('/'), model, reasoning),
            apiKey = { apiKey },
            client = client,
            allowCleartextForTests = true,
            clock = clock,
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

    @Test fun `custom loopback works without key and carries typed reasoning diagnostics`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"model":"qwen-local",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Risposta locale"}}],
             "usage":{"prompt_tokens":30,"completion_tokens":12,
                      "completion_tokens_details":{"reasoning_tokens":8}}}
            """.trimIndent(),
        ))
        val id = ProviderId.CUSTOM_OPENAI_COMPAT
        val transport = OpenAICompatTransport(
            providerId = id,
            spec = ProviderCatalog.spec(id),
            config = ProviderConfig(
                id,
                server.url("v1").toString().trimEnd('/'),
                model = "qwen-local",
                reasoningEffort = ReasoningEffort.LOW,
            ),
            apiKey = { null },
        )

        val result = transport.act(
            fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"),
        )

        assertEquals("Risposta locale", result.text)
        val usage = assertNotNull(result.usage)
        assertEquals(8L, usage.reasoningTokens)
        assertEquals("stop", usage.finishReason)
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertNull(request.getHeader("Authorization"))
        val root = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("low", root.getValue("reasoning_effort").jsonPrimitive.content)
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

    @Test fun `implausible remote usage is discarded without losing a valid reply`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"id":"1","model":"gpt-5.5",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Risposta valida"}}],
             "usage":{"prompt_tokens":${dev.argus.engine.brain.TurnUsage.MAX_TOKENS_PER_TURN + 1},
                      "completion_tokens":1}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val result = t.act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))

        assertEquals("Risposta valida", result.text)
        assertNull(result.metaError)
        assertNull(result.usage)
    }

    @Test fun `openrouter default model is resolved from catalog`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"model":"provider/resolved-model",
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
        // Il primo alias del catalogo: OpenRouter lo risolve da solo sull'ultima versione della famiglia.
        assertEquals("~deepseek/deepseek-flash-latest", root.getValue("model").jsonPrimitive.content)
    }

    // --- #52 F3: web search server-side sui provider diretti ---

    @Test fun `openrouter web search sends the web plugin keeps the model and forces tool_choice auto`(): Unit = runBlocking {
        // Con web richiesto, OpenRouter attiva il loop server-side via plugin `{"id":"web"}` (il campo
        // `model` resta invariato: il suffisso `:online` rompe i preset); il reply NON va forzato
        // (impedirebbe la ricerca) e il testo finale post-web arriva come content.
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
        assertEquals("openai/gpt-5.5", root.getValue("model").jsonPrimitive.content)
        assertEquals(WEB_PLUGIN, root.getValue("plugins"))
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
        assertFalse("plugins" in root, "senza web non compare alcun plugin")
        val toolChoice = root.getValue("tool_choice").jsonObject
        assertEquals("whatsapp_reply", toolChoice.getValue("function").jsonObject.getValue("name").jsonPrimitive.content)
    }

    @Test fun `openrouter web search with a preset model keeps the preset slug and sends the web plugin`(): Unit = runBlocking {
        // Il suffisso `:online` su `@preset/...` e' un 404 di OpenRouter: il modello deve restare
        // esatto e il web passa dal plugin, per entrambi i sink (reply WhatsApp e notifica).
        val reply = """
            {"model":"openai/gpt-5.5",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"BTC a 100000."}}],
             "usage":{"prompt_tokens":50,"completion_tokens":10}}
        """.trimIndent()
        server.enqueue(jsonResponse(reply))
        server.enqueue(jsonResponse(reply))
        val t = transport(ProviderId.OPENROUTER, model = "@preset/argus")

        t.act(fireContext(), "dimmi il prezzo BTC", listOf("notification"), listOf("whatsapp_reply", "web.search"))
        t.act(timerContext(), "dimmi il prezzo BTC", emptyList(), listOf("web.search"))

        repeat(2) {
            val root = Json.parseToJsonElement(
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
            ).jsonObject
            assertEquals("@preset/argus", root.getValue("model").jsonPrimitive.content)
            assertEquals(WEB_PLUGIN, root.getValue("plugins"))
        }
    }

    @Test fun `openrouter web plugin carries a plain text search prompt with the date from the clock`(): Unit = runBlocking {
        val reply = """
            {"model":"openai/gpt-5.5",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"BTC a 100000."}}],
             "usage":{"prompt_tokens":50,"completion_tokens":10}}
        """.trimIndent()
        server.enqueue(jsonResponse(reply))
        val t = transport(ProviderId.OPENROUTER, model = "@preset/argus", clock = { LocalDate.of(2027, 1, 5) })

        t.act(timerContext(), "recupera il prezzo del Bitcoin", emptyList(), listOf("web.search"))

        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        val plugin = root.getValue("plugins").jsonArray.single().jsonObject
        assertEquals(setOf("id", "search_prompt"), plugin.keys)
        assertEquals("web", plugin.getValue("id").jsonPrimitive.content)
        assertEquals(
            "Web search results from 2027-01-05. Use them for current facts. " +
                "Answer in plain text: no links, no markdown, no citations.",
            plugin.getValue("search_prompt").jsonPrimitive.content,
        )
    }

    @Test fun `openrouter without web sends no plugin and no search prompt on the notification sink`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"model":"openai/gpt-5.5",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Ok."}}],
             "usage":{"prompt_tokens":5,"completion_tokens":1}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENROUTER, model = "openai/gpt-5.5")

        t.act(timerContext(), "genera un promemoria", emptyList(), emptyList())

        val body = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
        assertFalse("plugins" in Json.parseToJsonElement(body).jsonObject)
        assertFalse("search_prompt" in body)
    }

    @Test fun `custom openai-compat with web requested sends no plugins and keeps the model`(): Unit = runBlocking {
        // Custom non ha un meccanismo web (NONE): il web si degrada e il plugin e' solo di OpenRouter.
        server.enqueue(jsonResponse(
            """
            {"model":"m",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Ciao."}}],
             "usage":{"prompt_tokens":5,"completion_tokens":1}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.CUSTOM_OPENAI_COMPAT, model = "m")

        t.act(timerContext(), "dimmi il prezzo BTC", emptyList(), listOf("web.search"))

        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertEquals("m", root.getValue("model").jsonPrimitive.content)
        assertFalse("plugins" in root)
    }

    @Test fun `gemini web search uses native generateContent with google_search`(): Unit = runBlocking {
        // Web richiesto su Gemini: NON passa dallo shim OpenAI-compat /chat/completions, ma dall'API
        // nativa generateContent con tool google_search (grounding, loop interno lato Google, single-turn).
        // Header auth diverso (`x-goog-api-key`), body diverso (contents/systemInstruction/tools),
        // testo = concatenazione di candidates[0].content.parts[].text, usage da usageMetadata.
        server.enqueue(jsonResponse(
            """
            {"candidates":[{"content":{"role":"model","parts":[
               {"text":"Oggi a Roma "},{"text":"29 gradi."}]}}],
             "usageMetadata":{"promptTokenCount":45,"candidatesTokenCount":9},
             "modelVersion":"gemini-2.5-flash"}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.GEMINI, model = "gemini-2.5-flash")

        val result = t.act(
            fireContext(), "che tempo fa a Roma", listOf("notification"),
            listOf("whatsapp_reply", "web.search"),
        )

        assertEquals("Oggi a Roma 29 gradi.", result.text)
        assertNull(result.metaError)
        val usage = assertNotNull(result.usage)
        assertEquals(45L, usage.inputTokens)
        assertEquals(9L, usage.outputTokens)

        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        // API nativa: path `.../models/{model}:generateContent`.
        assertTrue(request.path.orEmpty().contains(":generateContent"), "path nativo: ${request.path}")
        assertTrue(request.path.orEmpty().contains("gemini-2.5-flash"))
        // Auth nativa: x-goog-api-key, MAI Bearer.
        assertEquals(API_KEY, request.getHeader("x-goog-api-key"))
        assertNull(request.getHeader("Authorization"))
        val root = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        // tool grounding google_search
        assertTrue("google_search" in root.getValue("tools").jsonArray.single().jsonObject)
        // system separato (systemInstruction) e user in contents
        assertTrue("systemInstruction" in root)
        val userParts = root.getValue("contents").jsonArray.single().jsonObject
            .getValue("parts").jsonArray
        assertTrue(userParts.first().jsonObject.getValue("text").jsonPrimitive.content.isNotEmpty())
        // niente Chat Completions
        assertFalse("messages" in root)
        assertFalse("plugins" in root)
    }

    @Test fun `openai web search uses the responses endpoint and web_search tool`(): Unit = runBlocking {
        // Web richiesto su OpenAI: NON passa da /chat/completions (che vorrebbe un modello
        // `-search-preview`), ma dalla Responses API /responses con tool web_search (loop interno lato
        // OpenAI, single-turn). Testo = item output type=message → content type=output_text; il
        // web_search_call si ignora. Usage da input_tokens/output_tokens.
        server.enqueue(jsonResponse(
            """
            {"model":"gpt-5.5",
             "output":[
               {"type":"web_search_call","status":"completed"},
               {"type":"message","role":"assistant","content":[
                 {"type":"output_text","text":"Il cambio EUR/USD e' 1.09."}]}],
             "usage":{"input_tokens":220,"output_tokens":18}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val result = t.act(
            fireContext(), "dimmi il cambio euro dollaro", listOf("notification"),
            listOf("whatsapp_reply", "web.search"),
        )

        assertEquals("Il cambio EUR/USD e' 1.09.", result.text)
        assertNull(result.metaError)
        val usage = assertNotNull(result.usage)
        assertEquals(220L, usage.inputTokens)
        assertEquals(18L, usage.outputTokens)

        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/responses", request.path)
        assertEquals("Bearer $API_KEY", request.getHeader("Authorization"))
        val root = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("gpt-5.5", root.getValue("model").jsonPrimitive.content)
        // tool web_search (Responses API)
        assertEquals(
            "web_search",
            root.getValue("tools").jsonArray.single().jsonObject.getValue("type").jsonPrimitive.content,
        )
        // input = system + user (niente formato Chat Completions/messages)
        val input = root.getValue("input").jsonArray
        assertEquals("system", input.first().jsonObject.getValue("role").jsonPrimitive.content)
        assertEquals("user", input.last().jsonObject.getValue("role").jsonPrimitive.content)
        assertFalse("messages" in root)
        assertFalse("tool_choice" in root)
        assertFalse("plugins" in root)
    }

    // --- #59 Ondata 4a: sink NOTIFICA (deliver=LOCAL_NOTIFICATION, da timer) → testo plain,
    //     nessun reply tool, NESSUNA notifica, system della NOTIFICA (no framing WhatsApp) ---

    @Test fun `notification sink without web omits the reply tool and uses the notification prompt`(): Unit = runBlocking {
        // Sink da evento TimeFired, contextSources=[], allowedTools=[]: il modello produce testo
        // semplice nel content; nessun reply tool, nessun tool_choice; il system è quello della
        // NOTIFICA (nessun riferimento a un messaggio WhatsApp ricevuto).
        server.enqueue(jsonResponse(
            """
            {"model":"openai/gpt-5.5",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Promemoria: bevi acqua."}}],
             "usage":{"prompt_tokens":20,"completion_tokens":5}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENROUTER, model = "openai/gpt-5.5")

        val result = t.act(timerContext(), "genera un promemoria", emptyList(), emptyList())

        assertEquals("Promemoria: bevi acqua.", result.text)
        assertNull(result.metaError)
        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertFalse("tools" in root, "il sink notifica non dichiara il reply tool")
        assertFalse("tool_choice" in root, "il sink notifica non forza alcun tool")
        // niente web richiesto: modello plain, nessun plugin.
        assertEquals("openai/gpt-5.5", root.getValue("model").jsonPrimitive.content)
        assertFalse("plugins" in root)
        val messages = root.getValue("messages").jsonArray
        val system = messages.first().jsonObject.getValue("content").jsonPrimitive.content
        val user = messages.last().jsonObject.getValue("content").jsonPrimitive.content
        assertTrue("NOTIFICATION" in system, "il system del sink deve parlare della NOTIFICATION")
        assertFalse("WhatsApp" in system, "il system del sink NON deve parlare di WhatsApp")
        assertFalse("Message received" in user, "il sink non referenzia alcuna notifica in arrivo")
        assertEquals("Goal: genera un promemoria", user, "il user message del sink porta l'obiettivo")
        assertFalse("Generate the requested content" in user)
    }

    @Test fun `notification sink with state carries the state lines and no notification framing`(): Unit = runBlocking {
        // contextSources=[state] su TimeFired: le state lines entrano nel user message, senza alcun
        // "Message received".
        server.enqueue(jsonResponse(
            """
            {"model":"openai/gpt-5.5",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Batteria ok."}}],
             "usage":{"prompt_tokens":20,"completion_tokens":5}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENROUTER, model = "openai/gpt-5.5")

        val result = t.act(timerContext(), "riassumi lo stato", listOf("state"), emptyList())

        assertEquals("Batteria ok.", result.text)
        val user = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject.getValue("messages").jsonArray.last().jsonObject
            .getValue("content").jsonPrimitive.content
        assertTrue("ringer=normal" in user, "le state lines devono entrare nel prompt del sink")
        assertTrue("Goal: riassumi lo stato" in user, "insieme all'obiettivo")
        assertTrue(user.startsWith("Goal: riassumi lo stato\nDevice state:"))
        assertFalse("Message received" in user)
    }

    @Test fun `notification sink rejects notification among the context sources`(): Unit = runBlocking {
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        // Senza reply tool, "notification" fra le sorgenti è un config error prima della rete.
        assertFailsWith<TransportException> {
            t.act(timerContext(), "genera", listOf("notification"), emptyList())
        }.also { assertEquals(TransportErrorKind.CONFIGURATION, it.kind) }
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `notification sink with web on openrouter sends the web plugin and no reply tool`(): Unit = runBlocking {
        // Sink notifica + web su OpenRouter: resta su Chat Completions con il plugin web (modello
        // invariato), ma senza reply tool né tool_choice; il testo finale post-web arriva dal content.
        server.enqueue(jsonResponse(
            """
            {"model":"openai/gpt-5.5",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Il cambio oggi e' 1.09."}}],
             "usage":{"prompt_tokens":50,"completion_tokens":10}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENROUTER, model = "openai/gpt-5.5")

        val result = t.act(timerContext(), "dimmi il cambio", emptyList(), listOf("web.search"))

        assertEquals("Il cambio oggi e' 1.09.", result.text)
        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertEquals("openai/gpt-5.5", root.getValue("model").jsonPrimitive.content)
        assertEquals(WEB_PLUGIN, root.getValue("plugins"))
        assertFalse("tools" in root)
        assertFalse("tool_choice" in root)
        // La ricerca del plugin usa il messaggio utente: deve contenere l'obiettivo, non una frase neutra.
        val user = root.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
        assertEquals("Goal: dimmi il cambio", user)
    }

    @Test fun `notification sink with web on openai routes to responses with the notification prompt`(): Unit = runBlocking {
        // Sink notifica + web su OpenAI: il path web dedicato (Responses API, gia' plain) viene scelto
        // a prescindere da useReplyTool, e usa il system della NOTIFICA (no framing WhatsApp).
        server.enqueue(jsonResponse(
            """
            {"model":"gpt-5.5",
             "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Cambio 1.09."}]}],
             "usage":{"input_tokens":100,"output_tokens":10}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val result = t.act(timerContext(), "dimmi il cambio", emptyList(), listOf("web.search"))

        assertEquals("Cambio 1.09.", result.text)
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/responses", request.path)
        val root = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertFalse("tool_choice" in root)
        assertFalse("messages" in root)
        val system = root.getValue("input").jsonArray.first().jsonObject
            .getValue("content").jsonPrimitive.content
        assertTrue("NOTIFICATION" in system)
        assertFalse("WhatsApp" in system)
        val user = root.getValue("input").jsonArray.last().jsonObject.getValue("content").jsonPrimitive.content
        assertEquals("Goal: dimmi il cambio", user)
    }

    @Test fun `notification sink with web on gemini carries the goal in the user content`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"Cambio 1.09."}]}}],
             "usageMetadata":{"promptTokenCount":45,"candidatesTokenCount":9},
             "modelVersion":"gemini-2.5-flash"}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.GEMINI, model = "gemini-2.5-flash")

        val result = t.act(timerContext(), "dimmi il cambio", listOf("state"), listOf("web.search"))

        assertEquals("Cambio 1.09.", result.text)
        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        val user = root.getValue("contents").jsonArray.first().jsonObject
            .getValue("parts").jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content
        assertTrue(user.startsWith("Goal: dimmi il cambio\nDevice state:"), user)
        assertTrue("ringer=normal" in user)
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

    // --- 0.3.6 voce 1: il test di connessione spiega la causa ---

    @Test fun `health carries the provider message with the key masked`(): Unit = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"error":{"message":"No endpoints found matching your data policy. Bad key $API_KEY"}}""",
            ).setResponseCode(400),
        )
        val t = transport(ProviderId.OPENROUTER, model = "deepseek/deepseek-v4.1-flash")

        val error = assertFailsWith<TransportException> { t.health() }

        assertEquals(TransportErrorKind.HTTP, error.kind)
        assertEquals(400, error.statusCode)
        val providerMessage = assertNotNull(error.providerMessage)
        assertTrue(providerMessage.startsWith("No endpoints found matching your data policy."), providerMessage)
        assertFalse(API_KEY in providerMessage, providerMessage)
        assertTrue(providerMessage.length <= 160)
        // Il messaggio del provider vive solo nel campo dedicato.
        assertFalse(error.message.orEmpty().contains("endpoints"))
        assertFalse(error.toString().contains("endpoints"))
    }

    @Test fun `health 404 on a custom endpoint keeps the status and a null message for an html body`(): Unit = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(404)
                .addHeader("Content-Type", "text/html").setBody("<html>Not Found</html>"),
        )
        val t = transport(ProviderId.CUSTOM_OPENAI_COMPAT, model = "m")

        val error = assertFailsWith<TransportException> { t.health() }

        assertEquals(404, error.statusCode)
        assertNull(error.providerMessage)
    }

    @Test fun `health maps 401 402 and 429 to their kinds and still carries the message`(): Unit = runBlocking {
        mapOf(
            401 to TransportErrorKind.AUTH,
            402 to TransportErrorKind.BUDGET,
            429 to TransportErrorKind.RATE_LIMIT,
        ).forEach { (code, kind) ->
            server.enqueue(jsonResponse("""{"error":{"message":"detail $code"}}""").setResponseCode(code))
            val error = assertFailsWith<TransportException> {
                transport(ProviderId.OPENAI, model = "gpt-5.5").health()
            }
            assertEquals(kind, error.kind, "code $code")
            assertEquals("detail $code", error.providerMessage)
        }
    }

    // --- 0.3.7 voce 3b: ragionamento obbligatorio, un solo ritentativo con `low` ---

    private fun mandatoryReasoning(message: String = "Reasoning is mandatory for this endpoint and cannot be disabled.") =
        jsonResponse("""{"error":{"message":"$message"}}""").setResponseCode(400)

    private fun reasoningEffortOf(request: okhttp3.mockwebserver.RecordedRequest): String? =
        Json.parseToJsonElement(request.body.readUtf8()).jsonObject["reasoning_effort"]?.jsonPrimitive?.content

    @Test fun `compile retries once with low reasoning when the endpoint says reasoning is mandatory`(): Unit = runBlocking {
        server.enqueue(mandatoryReasoning())
        val content = "Serve un chiarimento.\n" +
            "@@META@@ {\"draft\":null,\"error_code\":\"clarification_required\"}"
        server.enqueue(jsonResponse(chatContent(content)))
        val t = transport(ProviderId.OPENROUTER, model = "x/flash", reasoning = ReasoningEffort.NONE)

        val result = t.compile("crea", manifest(), deviceState())

        assertEquals("clarification_required", result.metaError)
        assertEquals(2, server.requestCount)
        val first = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
        val second = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
        assertTrue("\"reasoning_effort\":\"none\"" in first, first)
        assertTrue("\"reasoning_effort\":\"low\"" in second, second)
        // La stessa richiesta, salvo l'effort.
        assertEquals(first.replace("\"reasoning_effort\":\"none\"", "\"reasoning_effort\":\"low\""), second)
    }

    @Test fun `act and health retry once with low reasoning too`(): Unit = runBlocking {
        val reply = """
            {"model":"x","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Ciao!"}}],
             "usage":{"prompt_tokens":5,"completion_tokens":2}}
        """.trimIndent()
        server.enqueue(mandatoryReasoning())
        server.enqueue(jsonResponse(reply))
        server.enqueue(mandatoryReasoning("The model requires THINKING to be enabled"))
        server.enqueue(jsonResponse(reply))
        val t = transport(ProviderId.OPENROUTER, model = "x/flash", reasoning = ReasoningEffort.NONE)

        val acted = t.act(timerContext(), "scrivi", emptyList(), emptyList())
        val health = t.health()

        assertEquals("Ciao!", acted.text)
        assertEquals("x", (health as OpenAiCompatHealth).model)
        val efforts = List(4) { reasoningEffortOf(assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))) }
        assertEquals(listOf("none", "low", "none", "low"), efforts)
    }

    @Test fun `no retry with the default or low effort`(): Unit = runBlocking {
        var expectedRequests = 0
        listOf(ReasoningEffort.DEFAULT, ReasoningEffort.LOW, ReasoningEffort.HIGH).forEach { effort ->
            server.enqueue(mandatoryReasoning())
            val t = transport(ProviderId.OPENROUTER, model = "x/flash", reasoning = effort)

            val error = assertFailsWith<TransportException> { t.compile("crea", manifest(), deviceState()) }

            assertEquals(400, error.statusCode, effort.name)
            // Una sola richiesta per ogni effort: nessun ritentativo.
            expectedRequests += 1
            assertEquals(expectedRequests, server.requestCount, effort.name)
        }
    }

    @Test fun `a 400 that does not talk about reasoning is not retried`(): Unit = runBlocking {
        server.enqueue(jsonResponse("""{"error":{"message":"x is not a valid model ID"}}""").setResponseCode(400))
        val t = transport(ProviderId.OPENROUTER, model = "x", reasoning = ReasoningEffort.NONE)

        val error = assertFailsWith<TransportException> { t.compile("crea", manifest(), deviceState()) }

        assertEquals(400, error.statusCode)
        assertEquals("x is not a valid model ID", error.providerMessage)
        assertEquals(1, server.requestCount)
    }

    @Test fun `only a 400 is retried and only once`(): Unit = runBlocking {
        server.enqueue(mandatoryReasoning())
        server.enqueue(mandatoryReasoning())
        val t = transport(ProviderId.OPENROUTER, model = "x/flash", reasoning = ReasoningEffort.NONE)

        val error = assertFailsWith<TransportException> { t.compile("crea", manifest(), deviceState()) }

        // Il secondo rifiuto e' definitivo: nessun terzo tentativo e nessun ciclo.
        assertEquals(400, error.statusCode)
        assertEquals(2, server.requestCount)

        server.enqueue(jsonResponse("""{"error":{"message":"reasoning backend overloaded"}}""").setResponseCode(503))
        val unavailable = assertFailsWith<TransportException> { t.compile("crea", manifest(), deviceState()) }
        assertEquals(503, unavailable.statusCode)
        assertEquals(3, server.requestCount)
    }

    // --- 0.4.0: lo sforzo di ragionamento scelto dall'utente per OpenRouter arriva nella richiesta ---

    private fun reasoningReply() = jsonResponse(
        """
        {"model":"x","choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"Ciao!"}}],
         "usage":{"prompt_tokens":5,"completion_tokens":2}}
        """.trimIndent(),
    )

    private fun clarificationReply() = jsonResponse(
        chatContent(
            "Serve un chiarimento.\n@@META@@ {\"draft\":null,\"error_code\":\"clarification_required\"}",
        ),
    )

    @Test fun `openrouter sends the chosen reasoning effort on compile act and health`(): Unit = runBlocking {
        listOf(ReasoningEffort.NONE, ReasoningEffort.LOW, ReasoningEffort.MEDIUM, ReasoningEffort.HIGH)
            .forEach { effort ->
                server.enqueue(clarificationReply())
                server.enqueue(reasoningReply())
                server.enqueue(reasoningReply())
                val t = transport(ProviderId.OPENROUTER, model = "x/flash", reasoning = effort)

                t.compile("crea", manifest(), deviceState())
                t.act(timerContext(), "scrivi", emptyList(), emptyList())
                t.health()

                val efforts = List(3) { reasoningEffortOf(assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))) }
                assertEquals(List(3) { effort.wireValue }, efforts, effort.name)
            }
    }

    @Test fun `openrouter without a reasoning choice omits the field everywhere`(): Unit = runBlocking {
        server.enqueue(clarificationReply())
        server.enqueue(reasoningReply())
        server.enqueue(reasoningReply())
        val t = transport(ProviderId.OPENROUTER, model = "x/flash")

        t.compile("crea", manifest(), deviceState())
        t.act(timerContext(), "scrivi", emptyList(), emptyList())
        t.health()

        repeat(3) {
            val body = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
            assertFalse("reasoning_effort" in body, body)
        }
    }

    // --- 0.3.7 voce 3a: il messaggio del provider arriva anche in chat ---

    @Test fun `compile and act carry the cleaned provider message on a 4xx`(): Unit = runBlocking {
        // Il JSON porta un a-capo escapato (\n) e la chiave: il messaggio esce su una riga sola, senza chiave.
        val body = """{"error":{"message":"x is not a valid model ID\n(key $API_KEY) ${"y".repeat(300)}"}}"""
        server.enqueue(jsonResponse(body).setResponseCode(400))
        server.enqueue(jsonResponse(body).setResponseCode(400))
        val t = transport(ProviderId.OPENROUTER, model = "x")

        val compileError = assertFailsWith<TransportException> { t.compile("crea", manifest(), deviceState()) }
        val actError = assertFailsWith<TransportException> {
            t.act(timerContext(), "scrivi", emptyList(), emptyList())
        }

        listOf(compileError, actError).forEach { error ->
            assertEquals(400, error.statusCode)
            val message = assertNotNull(error.providerMessage)
            assertTrue(message.startsWith("x is not a valid model ID (key ***)"), message)
            assertFalse(API_KEY in message, message)
            assertTrue(message.none { it.isISOControl() }, message)
            assertTrue(message.length <= 160, message.length.toString())
            // Il testo del provider vive solo nel campo dedicato.
            assertFalse("valid model" in error.message.orEmpty())
            assertFalse("valid model" in error.toString())
        }
    }

    @Test fun `compile and act do not read the message of a 5xx`(): Unit = runBlocking {
        server.enqueue(jsonResponse("""{"error":{"message":"secret detail"}}""").setResponseCode(500))
        server.enqueue(jsonResponse("""{"error":{"message":"secret detail"}}""").setResponseCode(503))
        val t = transport(ProviderId.OPENROUTER, model = "x")

        val compileError = assertFailsWith<TransportException> { t.compile("crea", manifest(), deviceState()) }
        val actError = assertFailsWith<TransportException> {
            t.act(timerContext(), "scrivi", emptyList(), emptyList())
        }

        assertEquals(500, compileError.statusCode)
        assertNull(compileError.providerMessage)
        assertNull(actError.providerMessage)
    }

    @Test fun `a rejected compile hands the cleaned provider message to the brain result`(): Unit = runBlocking {
        server.enqueue(
            jsonResponse("""{"error":{"message":"x is not a valid model ID"}}""").setResponseCode(400),
        )
        val brain = TransportBackedBrain(transport(ProviderId.OPENROUTER, model = "x"))

        val result = brain.compile("crea", manifest(), deviceState())

        assertEquals("bridge_http_400", result.metaError)
        assertEquals("x is not a valid model ID", result.providerMessage)
        assertFalse("valid model" in result.toString(), result.toString())
    }

    @Test fun `act result carries only the error code of a rejected request`(): Unit = runBlocking {
        server.enqueue(
            jsonResponse("""{"error":{"message":"x is not a valid model ID"}}""").setResponseCode(400),
        )
        val brain = TransportBackedBrain(transport(ProviderId.OPENROUTER, model = "x"))

        val result = brain.act(timerContext(), "scrivi", emptyList(), emptyList())

        assertEquals("bridge_http_400", result.metaError)
        assertFalse("valid model" in result.toString(), result.toString())
    }

    @Test fun `the openai responses and gemini web paths carry the provider message too`(): Unit = runBlocking {
        server.enqueue(jsonResponse("""{"error":{"message":"web rejected"}}""").setResponseCode(400))
        val error = assertFailsWith<TransportException> {
            transport(ProviderId.OPENAI, model = "gpt-6-luna").act(
                timerContext(), "scrivi", emptyList(), listOf("web.search"),
            )
        }
        assertEquals("/responses", server.takeRequest(2, TimeUnit.SECONDS)?.path)
        assertEquals("web rejected", error.providerMessage)
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
        assertEquals(12_000L, assertNotNull(result.usage).inputTokens)
        assertEquals("stop", result.usage?.finishReason)

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
        assertTrue(prompt.contains("BINDING RULES"), "il prompt deve includere le regole Hermes")
        assertTrue(prompt.contains("@@META@@"), "il prompt deve richiedere la riga sentinel")
        assertTrue(prompt.contains("AutomationDraft"), "il prompt deve includere lo schema draft")
        assertTrue(prompt.contains("metti in silenzioso alle 23"), "il messaggio utente deve entrare nel prompt")
    }

    // --- 0.3.6 voce 4: temperatura bassa solo nel compile di OpenRouter e Custom ---

    private fun compileBody(provider: ProviderId, model: String): String = runBlocking {
        server.enqueue(jsonResponse(chatContent("Ciao! Come posso aiutarti oggi?")))
        transport(provider, model = model).compile("ciao", manifest(), deviceState())
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
    }

    @Test fun `compile sends temperature 0_2 for openrouter and custom only`() {
        assertTrue("\"temperature\":0.2" in compileBody(ProviderId.OPENROUTER, "deepseek/deepseek-v4.1-flash"))
        assertTrue("\"temperature\":0.2" in compileBody(ProviderId.CUSTOM_OPENAI_COMPAT, "qwen-local"))
    }

    @Test fun `compile never sends a temperature for openai and gemini`() {
        assertFalse("temperature" in compileBody(ProviderId.OPENAI, "gpt-5.5"))
        assertFalse("temperature" in compileBody(ProviderId.GEMINI, "gemini-2.5-flash"))
    }

    @Test fun `act and health never send a temperature even for openrouter and custom`(): Unit = runBlocking {
        listOf(
            ProviderId.OPENROUTER to "deepseek/deepseek-v4.1-flash",
            ProviderId.CUSTOM_OPENAI_COMPAT to "qwen-local",
        ).forEach { (provider, model) ->
            val ok = jsonResponse(chatContent("Promemoria: bevi acqua."))
            server.enqueue(ok)
            transport(provider, model = model).act(timerContext(), "genera un promemoria", emptyList(), emptyList())
            val actBody = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
            assertFalse("temperature" in actBody, "act $provider")

            server.enqueue(jsonResponse(chatContent("pong")))
            transport(provider, model = model).health()
            val healthBody = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
            assertFalse("temperature" in healthBody, "health $provider")
        }
    }

    // --- 0.3.6 F5: risposta grezza solo in memoria ---

    @Test fun `compile returns the raw model text capped at 16000 characters`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chatContent("Ciao! " + "x".repeat(20_000))))
        val t = transport(ProviderId.OPENROUTER, model = "deepseek/deepseek-v4.1-flash")

        val result = t.compile("ciao", manifest(), deviceState())

        val raw = assertNotNull(result.raw)
        assertEquals(16_000, raw.length)
        assertTrue(raw.startsWith("Ciao! "))
    }

    @Test fun `compile with a draft and with a truncated output both carry the raw text`(): Unit = runBlocking {
        val content = "Fatto.\n@@META@@ {\"draft\": {\"name\":\"dnd sera\"," +
            "\"trigger\":{\"type\":\"time\",\"cron\":\"0 23 * * *\",\"tz\":\"Europe/Rome\"}," +
            "\"actions\":[{\"type\":\"set_dnd\",\"mode\":\"PRIORITY\"}]}}"
        server.enqueue(jsonResponse(chatContent(content)))
        val ok = transport(ProviderId.OPENAI, model = "gpt-5.5").compile("dnd alle 23", manifest(), deviceState())
        assertNotNull(ok.draft)
        assertEquals(content, ok.raw)

        server.enqueue(jsonResponse(chatContent("risposta tagliata @@META@@ {\"draft\": {\"na").replace("\"stop\"", "\"length\"")))
        val cut = transport(ProviderId.OPENAI, model = "gpt-5.5").compile("dnd alle 23", manifest(), deviceState())
        assertNull(cut.draft)
        assertEquals("risposta tagliata @@META@@ {\"draft\": {\"na", cut.raw)
    }

    @Test fun `an empty model answer leaves the raw text null`(): Unit = runBlocking {
        server.enqueue(jsonResponse(chatContent("")))

        assertNull(transport(ProviderId.OPENAI, model = "gpt-5.5").compile("x", manifest(), deviceState()).raw)
    }

    @Test fun `compile clarification keeps draft null and preserves the reply`(): Unit = runBlocking {
        val content = "Quale contatto vuoi avvisare?\n" +
            "@@META@@ {\"draft\":null,\"error_code\":\"clarification_required\"}"
        server.enqueue(jsonResponse(chatContent(content)))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")

        val result = t.compile("avvisa quando arrivo", manifest(), deviceState())

        assertNull(result.draft)
        assertEquals("clarification_required", result.metaError)
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

    // --- P4-D2 slice 1: actResolved — dato runtime TAINTED in un messaggio DATA separato,
    //     MAI nel system (che porta solo il marker opaco {{ARGUS_RUNTIME_DATA_n}}) ---

    @Test fun `actResolved keeps the runtime value out of system and in a delimited data message`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"id":"1","model":"gpt-5.5",
             "choices":[{"index":0,"message":{"role":"assistant",
               "tool_calls":[{"id":"c1","type":"function","function":{"name":"whatsapp_reply","arguments":"{\"text\":\"Fatto.\"}"}}]}}],
             "usage":{"prompt_tokens":40,"completion_tokens":5}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")
        val resolved = RuntimeDataTestFixture.resolved()

        val result = t.actResolved(
            fireContext(), resolved.goal, listOf("notification"), listOf("whatsapp_reply"), resolved.runtimeData,
        )

        assertEquals("Fatto.", result.text)
        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        val messages = root.getValue("messages").jsonArray
        val system = messages.first { it.jsonObject.getValue("role").jsonPrimitive.content == "system" }
            .jsonObject.getValue("content").jsonPrimitive.content
        val userConcat = messages.filter { it.jsonObject.getValue("role").jsonPrimitive.content == "user" }
            .joinToString("\n") { it.jsonObject.getValue("content").jsonPrimitive.content }
        assertFalse(RuntimeDataTestFixture.SENTINEL in system, "il valore runtime non deve entrare nel system")
        assertTrue("{{ARGUS_RUNTIME_DATA_1}}" in system, "il system deve portare solo il marker opaco")
        assertTrue(RuntimeDataTestFixture.SENTINEL in userConcat, "il valore runtime deve entrare nel messaggio DATA")
        assertTrue(
            "\"token\":\"ARGUS_RUNTIME_DATA_1\",\"value\":\"${RuntimeDataTestFixture.SENTINEL}\"" in userConcat,
        )
    }

    @Test fun `actResolved on the responses endpoint isolates the runtime value from the system input`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"model":"gpt-5.5",
             "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"Fatto."}]}],
             "usage":{"input_tokens":100,"output_tokens":10}}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.OPENAI, model = "gpt-5.5")
        val resolved = RuntimeDataTestFixture.resolved()

        val result = t.actResolved(
            fireContext(), resolved.goal, listOf("notification"),
            listOf("whatsapp_reply", "web.search"), resolved.runtimeData,
        )

        assertEquals("Fatto.", result.text)
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/responses", request.path)
        val input = Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("input").jsonArray
        val system = input.first { it.jsonObject.getValue("role").jsonPrimitive.content == "system" }
            .jsonObject.getValue("content").jsonPrimitive.content
        val userConcat = input.filter { it.jsonObject.getValue("role").jsonPrimitive.content == "user" }
            .joinToString("\n") { it.jsonObject.getValue("content").jsonPrimitive.content }
        assertFalse(RuntimeDataTestFixture.SENTINEL in system)
        assertTrue("{{ARGUS_RUNTIME_DATA_1}}" in system)
        assertTrue(RuntimeDataTestFixture.SENTINEL in userConcat)
    }

    @Test fun `actResolved on gemini native keeps the runtime value out of systemInstruction`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"Fatto."}]}}],
             "usageMetadata":{"promptTokenCount":45,"candidatesTokenCount":9},
             "modelVersion":"gemini-2.5-flash"}
            """.trimIndent(),
        ))
        val t = transport(ProviderId.GEMINI, model = "gemini-2.5-flash")
        val resolved = RuntimeDataTestFixture.resolved()

        val result = t.actResolved(
            fireContext(), resolved.goal, listOf("notification"),
            listOf("whatsapp_reply", "web.search"), resolved.runtimeData,
        )

        assertEquals("Fatto.", result.text)
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertTrue(request.path.orEmpty().contains(":generateContent"), "path nativo: ${request.path}")
        val root = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val systemInstruction = root.getValue("systemInstruction").jsonObject
            .getValue("parts").jsonArray.joinToString("\n") { it.jsonObject.getValue("text").jsonPrimitive.content }
        val contentsConcat = root.getValue("contents").jsonArray.joinToString("\n") { content ->
            content.jsonObject.getValue("parts").jsonArray
                .joinToString("\n") { it.jsonObject.getValue("text").jsonPrimitive.content }
        }
        assertFalse(RuntimeDataTestFixture.SENTINEL in systemInstruction)
        assertTrue("{{ARGUS_RUNTIME_DATA_1}}" in systemInstruction)
        assertTrue(RuntimeDataTestFixture.SENTINEL in contentsConcat)
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
            put("prompt_tokens", 12_000)
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

    private fun timerContext() = FireContext(
        event = TriggerEvent.TimeFired(
            automationId = AutomationId("automation-1"),
            approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
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

        /** Giorno fisso dell'orologio iniettato nel transport dei test. */
        val SEARCH_DAY: LocalDate = LocalDate.of(2026, 10, 26)

        /** Plugin web di OpenRouter come deve comparire nel body, con il prompt di ricerca in testo semplice. */
        val WEB_PLUGIN = Json.parseToJsonElement(
            """[{"id":"web","search_prompt":"Web search results from 2026-10-26. Use them for current facts. Answer in plain text: no links, no markdown, no citations."}]""",
        )
    }
}
