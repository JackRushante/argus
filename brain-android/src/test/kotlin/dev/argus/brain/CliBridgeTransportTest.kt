package dev.argus.brain

import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.StateReaderManifest
import dev.argus.engine.brain.TurnUsage
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovedStateContext
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.CmpOp
import dev.argus.engine.model.Condition
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.StateQueryFamily
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.SensorKind
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.GeoPoint
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import java.security.MessageDigest
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

class CliBridgeTransportTest {
    private lateinit var server: MockWebServer

    private val manifest = CapabilityManifest(
        deviceModel = "OnePlus CPH2747",
        androidVersion = 16,
        androidApi = 36,
        shizukuAvailable = true,
        grantedPermissions = listOf("android.permission.INTERNET"),
        availableTools = listOf("set_dnd", "set_wifi"),
        unavailableTools = mapOf("vision.analyze" to "provider assente"),
        whitelistedContacts = listOf(WhitelistedContact("Moglie", "jid:42")),
        availableTriggers = listOf(
            "time", "notification", "phone_state.sms", "sensor.significant_motion",
        ),
        stateReaders = StateReaderManifest(
            families = listOf(
                StateQueryFamily.SETTING,
                StateQueryFamily.SYSTEM_PROPERTY,
                StateQueryFamily.DUMPSYS_FIELD,
            ),
        ),
    )

    @BeforeTest fun setUp() { server = MockWebServer().apply { start() } }
    @AfterTest fun tearDown() { runCatching { server.shutdown() } }

    private fun transport(
        client: OkHttpClient = CliBridgeTransport.defaultClient(),
        token: String? = TOKEN,
        requestId: String = REQUEST_ID,
    ) = CliBridgeTransport(
        baseUrl = server.url("/").toString(),
        authProvider = BridgeAuthProvider { token },
        client = client,
        allowCleartextForTests = true,
        requestIdFactory = { requestId },
    )

    @Test fun `request is authenticated versioned structured and redacts device state`(): Unit = runBlocking {
        server.enqueue(validNoDraftResponse())
        val state = DeviceState(
            values = mapOf("ringer" to "normal", "battery" to "80\ninject", "private" to "secret"),
            foregroundApp = "com.example.current",
            location = GeoPoint(37.123, 15.456),
        )

        transport().compile("dopo le 23 metti DND", manifest, state)

        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/compile", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
        assertEquals(REQUEST_ID, request.getHeader("Idempotency-Key"))
        assertEquals("application/json", request.getHeader("Accept"))
        val raw = request.body.readUtf8()
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals(2, root.getValue("schema_version").jsonPrimitive.content.toInt())
        assertEquals(REQUEST_ID, root.getValue("request_id").jsonPrimitive.content)
        val sentManifest = root.getValue("manifest").jsonObject
        assertEquals(36, sentManifest.getValue("android_api").jsonPrimitive.content.toInt())
        // I trigger armabili viaggiano nel wire: Hermes non propone trigger morti (P2-2).
        assertEquals(
            listOf("time", "notification", "phone_state.sms", "sensor.significant_motion"),
            sentManifest.getValue("available_triggers").jsonArray.map { it.jsonPrimitive.content },
        )
        val readers = sentManifest.getValue("state_readers").jsonObject
        assertEquals(1, readers.getValue("policy_version").jsonPrimitive.content.toInt())
        assertEquals(
            listOf("setting", "system_property", "dumpsys_field"),
            readers.getValue("families").jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(
            1_024,
            readers.getValue("limits").jsonObject
                .getValue("max_expected_length").jsonPrimitive.content.toInt(),
        )
        val sentState = root.getValue("state").jsonObject
        assertEquals("normal", sentState.getValue("values").jsonObject.getValue("ringer").jsonPrimitive.content)
        assertFalse("battery" in sentState.getValue("values").jsonObject)
        assertFalse("private" in sentState.getValue("values").jsonObject)
        assertEquals("com.example.current", sentState.getValue("foreground_app").jsonPrimitive.content)
        assertTrue(sentState.getValue("location_available").jsonPrimitive.boolean)
        assertFalse(raw.contains("37.123"))
        assertFalse(raw.contains("15.456"))
    }

    @Test fun `health uses the same authenticated strict boundary`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """{"schema_version":2,"status":"ok","model":"gpt-5.5","compile_schema_versions":[1,2],"act_schema_versions":[1,2],"source_sha256":"${"a".repeat(64)}"}""",
        ))

        val health = transport().health()

        assertEquals(2, health.schemaVersion)
        assertTrue(2 in health.compileSchemaVersions)
        assertEquals(listOf(1, 2), health.actSchemaVersions)
        assertEquals("gpt-5.5", health.model)
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/health/v2", request.path)
        assertEquals("Bearer $TOKEN", request.getHeader("Authorization"))
    }

    @Test fun `health rejects a bridge missing a version the app uses or a malformed envelope`(): Unit = runBlocking {
        val invalid = listOf(
            // compile senza la v2 usata dall'app -> incompatibile.
            """{"schema_version":2,"status":"ok","model":"gpt-5.5","compile_schema_versions":[1],"act_schema_versions":[1,2],"source_sha256":"${"a".repeat(64)}"}""",
            // act senza la v2 (act_v2) usata dall'app -> incompatibile.
            """{"schema_version":2,"status":"ok","model":"gpt-5.5","compile_schema_versions":[1,2],"act_schema_versions":[1],"source_sha256":"${"a".repeat(64)}"}""",
            // sha in formato invalido.
            """{"schema_version":2,"status":"ok","model":"gpt-5.5","compile_schema_versions":[1,2],"act_schema_versions":[1,2],"source_sha256":"not-a-sha"}""",
            // campo sconosciuto -> envelope non valido (decoder strict).
            """{"schema_version":2,"status":"ok","model":"gpt-5.5","compile_schema_versions":[1,2],"act_schema_versions":[1,2],"source_sha256":"${"a".repeat(64)}","surprise":true}""",
        )

        invalid.forEach { body ->
            server.enqueue(jsonResponse(body))
            val error = assertFailsWith<BridgeException> { transport().health() }
            assertEquals(BridgeErrorKind.PROTOCOL, error.kind)
        }
    }

    @Test fun `health accepts a bridge that announces extra schema versions (forward compat #41)`(): Unit = runBlocking {
        // Un redeploy che AGGIUNGE una versione (compile [1,2,3]) resta compatibile: l'app usa la v2,
        // contenuta nel set annunciato. Non deve piu' cadere offline (#41).
        server.enqueue(jsonResponse(
            """{"schema_version":3,"status":"ok","model":"gpt-5.5","compile_schema_versions":[1,2,3],"act_schema_versions":[1,2,3],"source_sha256":"${"a".repeat(64)}"}""",
        ))
        val health = transport().health()
        assertTrue(2 in health.compileSchemaVersions && 3 in health.compileSchemaVersions)
    }

    @Test fun `act is deterministic minimal and never sends local reply handles or target ids`(): Unit = runBlocking {
        val expectedRequestId = actRequestId("execution-1", 0)
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$expectedRequestId","result":{"text":"Va bene, a dopo."},"error_code":null}""",
        ))

        val result = transport().act(
            context = fireContext(),
            goal = "rispondi in modo cordiale",
            contextSources = listOf("notification", "state"),
            allowedTools = listOf("whatsapp_reply"),
        )

        assertEquals("Va bene, a dopo.", result.text)
        assertNull(result.metaError)
        val request = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
        assertEquals("/act", request.path)
        assertEquals(expectedRequestId, request.getHeader("Idempotency-Key"))
        val raw = request.body.readUtf8()
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals(expectedRequestId, root.getValue("request_id").jsonPrimitive.content)
        assertFalse(raw.contains("jid:private"))
        assertFalse(raw.contains("sbn:private"))
        assertFalse(raw.contains("45.123"))
        assertFalse(raw.contains("11.456"))
        assertFalse(raw.contains("private_state"))
        val context = root.getValue("context").jsonObject
        val notification = context.getValue("notification").jsonObject
        assertEquals("com.whatsapp", notification.getValue("package").jsonPrimitive.content)
        assertEquals("ciao", notification.getValue("text").jsonPrimitive.content)
        val state = assertNotNull(context["state"]).jsonObject
        assertEquals("normal", state.getValue("values").jsonObject.getValue("ringer").jsonPrimitive.content)
    }

    @Test fun `act omits state unless explicitly approved and rejects unsafe lanes before network`(): Unit = runBlocking {
        val expectedRequestId = actRequestId("execution-1", 0)
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$expectedRequestId","result":{"text":"Ciao"},"error_code":null}""",
        ))

        transport().act(
            context = fireContext(),
            goal = "rispondi",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
        )
        val body = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertTrue(body.getValue("context").jsonObject.getValue("state").jsonPrimitive.content == "null")

        val invalid = listOf(
            Triple(listOf("notification", "screen"), listOf("whatsapp_reply"), fireContext()),
            Triple(listOf("notification"), listOf("shell.run"), fireContext()),
            Triple(listOf("notification"), listOf("whatsapp_reply"), fireContext(isGroup = true)),
        )
        invalid.forEach { (sources, tools, context) ->
            val error = assertFailsWith<BridgeException> {
                transport().act(context, "rispondi", sources, tools)
            }
            assertEquals(BridgeErrorKind.CONFIGURATION, error.kind)
        }
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `act v2 sends only exact classified query values and fails closed when missing`(): Unit = runBlocking {
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
        val expectedRequestId = actRequestId("execution-1", 0)
        server.enqueue(jsonResponse(
            """{"schema_version":2,"request_id":"$expectedRequestId","result":{"text":"Va bene"},"error_code":null}""",
        ))

        val result = transport().actV2(
            fireContext().copy(
                state = fireContext().state.copy(
                    queryValues = mapOf(query.canonicalId to "4200"),
                ),
            ),
            action,
        )

        assertEquals("Va bene", result.text)
        val raw = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals(2, root.getValue("schema_version").jsonPrimitive.content.toInt())
        assertFalse("context_sources" in root)
        val state = root.getValue("context").jsonObject.getValue("state").jsonArray
        assertEquals(1, state.size)
        val sent = state.single().jsonObject
        assertEquals(query.canonicalId, sent.getValue("query_id").jsonPrimitive.content)
        assertEquals("NUMBER", sent.getValue("value_type").jsonPrimitive.content)
        assertEquals("CLEAN", sent.getValue("integrity").jsonPrimitive.content)
        assertEquals("SECRET", sent.getValue("confidentiality").jsonPrimitive.content)
        assertEquals("4200", sent.getValue("value").jsonPrimitive.content)
        assertFalse(raw.contains("private_state"))
        assertFalse(raw.contains("com.whatsapp\"" + ":\"normal"))

        val error = assertFailsWith<BridgeException> {
            transport().actV2(fireContext(), action)
        }
        assertEquals(BridgeErrorKind.CONFIGURATION, error.kind)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `act forwards web search alongside reply and rejects unsupported tools`(): Unit = runBlocking {
        val expectedRequestId = actRequestId("execution-1", 0)
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$expectedRequestId","result":{"text":"Il cambio e' 1.08."},"error_code":null}""",
        ))

        // web.search accanto al reply obbligatorio: toolset generativo valido, inoltrato tale e quale.
        val result = transport().act(
            context = fireContext(),
            goal = "rispondi col cambio aggiornato",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply", "web.search"),
        )

        assertEquals("Il cambio e' 1.08.", result.text)
        val raw = assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8()
        val root = Json.parseToJsonElement(raw).jsonObject
        assertEquals(
            listOf("whatsapp_reply", "web.search"),
            root.getValue("allowed_tools").jsonArray.map { it.jsonPrimitive.content },
        )

        // whatsapp_reply da solo resta accettato (contratto base invariato).
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$expectedRequestId","result":{"text":"Ok"},"error_code":null}""",
        ))
        val replyOnly = transport().act(
            fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"),
        )
        assertEquals("Ok", replyOnly.text)
        assertEquals(
            listOf("whatsapp_reply"),
            Json.parseToJsonElement(
                assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
            ).jsonObject.getValue("allowed_tools").jsonArray.map { it.jsonPrimitive.content },
        )

        // Un tool arbitrario (né reply né web) resta un errore di config prima della rete.
        val error = assertFailsWith<BridgeException> {
            transport().act(fireContext(), "rispondi", listOf("notification"), listOf("shell.run"))
        }
        assertEquals(BridgeErrorKind.CONFIGURATION, error.kind)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `act notification sink omits notification and forwards empty or state sources`(): Unit = runBlocking {
        // Sink NOTIFICA #59 (Ondata 4a): allowedTools senza whatsapp_reply + evento TimeFired (NON
        // NotificationPosted) + contextSources [] o [state] (MAI "notification"). L'envelope NON
        // contiene context.notification; il bridge (O4) produce testo plain dal solo goal (+ web/state).
        val expectedRequestId = actRequestId("execution-1", 0)

        // [web.search] senza reply, contextSources [] su evento TimeFired.
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$expectedRequestId","result":{"text":"Promemoria."},"error_code":null}""",
        ))
        val withWeb = transport().act(
            timerContext(), "genera un promemoria", emptyList(), listOf("web.search"),
        )
        assertEquals("Promemoria.", withWeb.text)
        val webRoot = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertTrue(webRoot.getValue("context_sources").jsonArray.isEmpty())
        assertEquals(
            listOf("web.search"),
            webRoot.getValue("allowed_tools").jsonArray.map { it.jsonPrimitive.content },
        )
        // context.notification ASSENTE (omessa via @EncodeDefault(NEVER)).
        assertFalse("notification" in webRoot.getValue("context").jsonObject)

        // [] senza reply → allowed_tools vuoto, notifica assente.
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$expectedRequestId","result":{"text":"Ok."},"error_code":null}""",
        ))
        val empty = transport().act(timerContext(), "genera", emptyList(), emptyList())
        assertEquals("Ok.", empty.text)
        val emptyRoot = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertTrue(emptyRoot.getValue("allowed_tools").jsonArray.isEmpty())
        assertFalse("notification" in emptyRoot.getValue("context").jsonObject)

        // [state] senza reply: lo stato viaggia, la notifica resta assente.
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$expectedRequestId","result":{"text":"Stato ok."},"error_code":null}""",
        ))
        transport().act(timerContext(), "genera con stato", listOf("state"), listOf("web.search"))
        val stateCtx = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject.getValue("context").jsonObject
        assertFalse("notification" in stateCtx)
        assertEquals(
            "normal",
            stateCtx.getValue("state").jsonObject.getValue("values").jsonObject
                .getValue("ringer").jsonPrimitive.content,
        )

        // Il sink RIFIUTA "notification" fra le sorgenti (mai un messaggio in arrivo) prima della rete.
        val error = assertFailsWith<BridgeException> {
            transport().act(timerContext(), "genera", listOf("notification"), listOf("web.search"))
        }
        assertEquals(BridgeErrorKind.CONFIGURATION, error.kind)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `reply act envelope stays byte-identical with the notification present`(): Unit = runBlocking {
        // La notifica resta OBBLIGATORIA e presente nel reply; con "state" fuori dalle sorgenti il
        // wire emette ancora "state":null. Byte-invarianza del reply-envelope (idempotency/hash bridge).
        val expectedRequestId = actRequestId("execution-1", 0)
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$expectedRequestId","result":{"text":"Ok"},"error_code":null}""",
        ))
        transport().act(fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"))
        val context = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject.getValue("context").jsonObject
        assertEquals(
            "com.whatsapp",
            context.getValue("notification").jsonObject.getValue("package").jsonPrimitive.content,
        )
        assertEquals("null", context.getValue("state").jsonPrimitive.content)
    }

    @Test fun `act v2 forwards web search alongside reply and rejects unsupported tools`(): Unit = runBlocking {
        val query = StateQuery.DumpsysField("battery", "voltage")
        fun action(tools: List<String>) = Action.InvokeLlmV2(
            goal = "rispondi col meteo aggiornato",
            stateContext = listOf(
                ApprovedStateContext(
                    query = query,
                    valueType = StateValueType.NUMBER,
                    policyVersion = StateQueryPolicy.VERSION,
                    integrity = IntegrityLabel.CLEAN,
                    confidentiality = ConfidentialityLabel.SECRET,
                ),
            ),
            allowedTools = tools,
            replyTargetSender = true,
            timeoutMs = 60_000,
        )
        val expectedRequestId = actRequestId("execution-1", 0)
        val context = fireContext().copy(
            state = fireContext().state.copy(queryValues = mapOf(query.canonicalId to "4200")),
        )
        server.enqueue(jsonResponse(
            """{"schema_version":2,"request_id":"$expectedRequestId","result":{"text":"Oggi sole."},"error_code":null}""",
        ))

        val result = transport().actV2(context, action(listOf("whatsapp_reply", "web.search")))

        assertEquals("Oggi sole.", result.text)
        val root = Json.parseToJsonElement(
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)).body.readUtf8(),
        ).jsonObject
        assertEquals(
            listOf("whatsapp_reply", "web.search"),
            root.getValue("allowed_tools").jsonArray.map { it.jsonPrimitive.content },
        )

        // Un tool arbitrario (né reply né web) fallisce come config error prima della rete.
        val error = assertFailsWith<BridgeException> {
            transport().actV2(context, action(listOf("shell.run")))
        }
        assertEquals(BridgeErrorKind.CONFIGURATION, error.kind)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `act response cannot choose a target or smuggle unknown fields`(): Unit = runBlocking {
        val requestId = actRequestId("execution-1", 0)
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$requestId","result":{"text":"Ciao","target":"altro"},"error_code":null}""",
        ))

        val error = assertFailsWith<BridgeException> {
            transport().act(
                fireContext(),
                "rispondi",
                listOf("notification"),
                listOf("whatsapp_reply"),
            )
        }

        assertEquals(BridgeErrorKind.PROTOCOL, error.kind)
    }

    @Test fun `valid strict response decodes a draft`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """{"schema_version":2,"request_id":"$REQUEST_ID","reply":"ok","meta":{"draft":{"name":"wifi off","trigger":{"type":"time","at":"2026-07-15T23:00","tz":"Europe/Rome"},"actions":[{"type":"set_wifi","on":false}]},"error_code":null}}"""
        ))

        val result = transport().compile("x", manifest, DeviceState())

        assertEquals("ok", result.reply)
        assertNotNull(result.draft)
        assertNull(result.metaError)
    }

    @Test fun `compile v2 decodes the closed sensor trigger contract`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """{"schema_version":2,"request_id":"$REQUEST_ID","reply":"ok","meta":{"draft":{"name":"movimento","trigger":{"type":"sensor","kind":"significant_motion","minimumEventCount":1,"samplingPeriodUs":null,"maxReportLatencyUs":null},"actions":[{"type":"show_notification","title":"Argus","text":"Movimento"}],"cooldownMs":60000},"error_code":null}}""",
        ))

        val result = transport().compile("quando mi muovo", manifest, DeviceState())

        val trigger = assertNotNull(result.draft).trigger as Trigger.Sensor
        assertEquals(SensorKind.SIGNIFICANT_MOTION, trigger.kind)
        assertEquals(1, trigger.minimumEventCount)
        assertNull(trigger.samplingPeriodUs)
        assertNull(trigger.maxReportLatencyUs)
    }

    @Test fun `compile v2 decodes a fingerprinted typed state query`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """{"schema_version":2,"request_id":"$REQUEST_ID","reply":"ok","meta":{"draft":{"name":"voltaggio basso","trigger":{"type":"time","cron":"0 8 * * *","tz":"Europe/Rome"},"actions":[{"type":"show_notification","title":"Argus","text":"Batteria"}],"conditions":{"type":"state_compare","query":{"type":"dumpsys_field","service":"battery","field":"voltage"},"valueType":"NUMBER","op":"LT","expected":"3800","policyVersion":1}},"error_code":null}}""",
        ))

        val result = transport().compile("avvisami sotto 3800 mV", manifest, DeviceState())

        assertEquals(
            Condition.StateCompare(
                query = StateQuery.DumpsysField("battery", "voltage"),
                valueType = StateValueType.NUMBER,
                op = CmpOp.LT,
                expected = "3800",
                policyVersion = StateQueryPolicy.VERSION,
            ),
            assertNotNull(result.draft).conditions,
        )
        assertNull(result.metaError)
    }

    @Test fun `compile v2 rejects state query without policy version as invalid draft`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """{"schema_version":2,"request_id":"$REQUEST_ID","reply":"ok","meta":{"draft":{"name":"voltaggio basso","trigger":{"type":"time","cron":"0 8 * * *","tz":"Europe/Rome"},"actions":[{"type":"show_notification","title":"Argus","text":"Batteria"}],"conditions":{"type":"state_compare","query":{"type":"dumpsys_field","service":"battery","field":"voltage"},"valueType":"NUMBER","op":"LT","expected":"3800"}},"error_code":null}}""",
        ))

        val result = transport().compile("avvisami sotto 3800 mV", manifest, DeviceState())

        assertNull(result.draft)
        assertEquals("draft_invalid", result.metaError)
    }

    @Test fun `compile v2 decodes explicit classified generative state without v1 defaults`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """{"schema_version":2,"request_id":"$REQUEST_ID","reply":"ok","meta":{"draft":{"name":"reply con stato","trigger":{"type":"notification","pkg":"com.whatsapp","conversationId":"jid:42","isGroup":false},"actions":[{"type":"invoke_llm_v2","goal":"rispondi considerando il voltaggio","stateContext":[{"query":{"type":"dumpsys_field","service":"battery","field":"voltage"},"valueType":"NUMBER","policyVersion":1,"integrity":"CLEAN","confidentiality":"SECRET"}],"allowedTools":["whatsapp_reply"],"replyTargetSender":true,"timeoutMs":60000}],"cooldownMs":60000},"error_code":null}}""",
        ))

        val result = transport().compile("rispondi usando il voltaggio", manifest, DeviceState())

        val action = assertNotNull(result.draft).actions.single()
        assertTrue(action is Action.InvokeLlmV2)
        assertEquals(StateQuery.DumpsysField("battery", "voltage"), action.stateContext.single().query)
        assertEquals(ConfidentialityLabel.SECRET, action.stateContext.single().confidentiality)
        assertEquals(60_000, action.timeoutMs)
        assertNull(result.metaError)
    }

    @Test fun `explicit no-draft response preserves reply and error code`(): Unit = runBlocking {
        server.enqueue(validNoDraftResponse("Serve un orario."))

        val result = transport().compile("boh", manifest, DeviceState())

        assertEquals("Serve un orario.", result.reply)
        assertNull(result.draft)
        assertEquals("clarification_required", result.metaError)
    }

    @Test fun `missing incompatible or mismatched protocol fields fail closed`(): Unit = runBlocking {
        val invalid = listOf(
            """{"request_id":"$REQUEST_ID","reply":"x","meta":{"draft":null,"error_code":"x"}}""",
            """{"schema_version":1,"request_id":"$REQUEST_ID","reply":"x","meta":{"draft":null,"error_code":"x"}}""",
            """{"schema_version":2,"request_id":"other","reply":"x","meta":{"draft":null,"error_code":"x"}}""",
            """{"schema_version":2,"request_id":"$REQUEST_ID","reply":{},"meta":{"draft":null,"error_code":"x"}}""",
            """{"schema_version":2,"request_id":"$REQUEST_ID","reply":"x","meta":{"draft":null,"error_code":"x"},"surprise":true}""",
        )

        invalid.forEach { body ->
            server.enqueue(jsonResponse(body))
            val error = assertFailsWith<BridgeException> {
                transport().compile("x", manifest, DeviceState())
            }
            assertEquals(BridgeErrorKind.PROTOCOL, error.kind)
        }
    }

    @Test fun `malformed draft is fail-soft after a valid envelope`(): Unit = runBlocking {
        server.enqueue(jsonResponse(
            """{"schema_version":2,"request_id":"$REQUEST_ID","reply":"x","meta":{"draft":{"name":"bad","trigger":{"type":"time","cron":"0 23 * * *"},"actions":[]},"error_code":null}}"""
        ))

        val result = transport().compile("x", manifest, DeviceState())

        assertNull(result.draft)
        assertEquals("draft_invalid", result.metaError)
    }

    @Test fun `non-json content type is rejected before parsing`(): Unit = runBlocking {
        server.enqueue(MockResponse().addHeader("Content-Type", "text/html").setBody("{}"))

        val error = assertFailsWith<BridgeException> {
            transport().compile("x", manifest, DeviceState())
        }

        assertEquals(BridgeErrorKind.PROTOCOL, error.kind)
    }

    @Test fun `oversized body is rejected without unbounded read`(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("Content-Type", "application/json")
                .setChunkedBody("x".repeat(CliBridgeTransport.MAX_RESPONSE_BYTES + 1), 8192)
        )

        val error = assertFailsWith<BridgeException> {
            transport().compile("x", manifest, DeviceState())
        }

        assertEquals(BridgeErrorKind.PROTOCOL, error.kind)
    }

    @Test fun `missing token fails before any network call`(): Unit = runBlocking {
        val error = assertFailsWith<BridgeException> {
            transport(token = null).compile("x", manifest, DeviceState())
        }

        assertEquals(BridgeErrorKind.CONFIGURATION, error.kind)
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun `401 and generic HTTP errors are typed without response leakage`(): Unit = runBlocking {
        server.enqueue(jsonResponse("""{"secret":"must-not-leak"}""").setResponseCode(401))
        var error = assertFailsWith<BridgeException> {
            transport().compile("x", manifest, DeviceState())
        }
        assertEquals(BridgeErrorKind.AUTH, error.kind)
        assertFalse(error.message.orEmpty().contains("must-not-leak"))

        server.enqueue(jsonResponse("""{"error":"down"}""").setResponseCode(503))
        error = assertFailsWith {
            transport().compile("x", manifest, DeviceState())
        }
        assertEquals(BridgeErrorKind.HTTP, error.kind)
        assertEquals(503, error.statusCode)
    }

    @Test fun `timeout is typed`(): Unit = runBlocking {
        val fast = OkHttpClient.Builder()
            .callTimeout(250, TimeUnit.MILLISECONDS)
            .readTimeout(250, TimeUnit.MILLISECONDS)
            .build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val error = assertFailsWith<BridgeException> {
            transport(client = fast).compile("x", manifest, DeviceState())
        }

        assertEquals(BridgeErrorKind.TIMEOUT, error.kind)
    }

    @Test fun `coroutine cancellation cancels the in-flight call and propagates`(): Unit = runBlocking {
        val client = CliBridgeTransport.defaultClient()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val pending = async {
            transport(client = client).compile("x", manifest, DeviceState())
        }
        withTimeout(2_000) {
            while (client.dispatcher.runningCallsCount() == 0) yield()
        }

        pending.cancelAndJoin()

        assertTrue(pending.isCancelled)
        assertFailsWith<CancellationException> { pending.await() }
        withTimeout(2_000) {
            while (client.dispatcher.runningCallsCount() != 0) yield()
        }
    }

    @Test fun `default client timeouts outlast the maximum action timeout`() {
        // #60: l'HTTP non deve tagliare prima del withTimeout massimo dell'azione (120s). Il default
        // è 125s > MAX_ACT_TIMEOUT_MILLIS, così una /act web lenta rispetta il budget dell'azione.
        val client = CliBridgeTransport.defaultClient()
        val expected = 125 * 1_000
        assertEquals(expected, client.callTimeoutMillis)
        assertEquals(expected, client.connectTimeoutMillis)
        assertEquals(expected, client.readTimeoutMillis)
        assertEquals(expected, client.writeTimeoutMillis)
    }

    @Test fun `cleartext base URLs are unavailable outside explicit tests`() {
        assertFailsWith<IllegalArgumentException> {
            CliBridgeTransport(
                baseUrl = "http://192.0.2.1:8090",
                authProvider = BridgeAuthProvider { TOKEN },
            )
        }
    }

    @Test fun `compile and act map the optional bridge usage into TurnUsage`(): Unit = runBlocking {
        // S15: subset chiuso sanitizzato dal bridge (forma reale di hermes --usage-file).
        val usageJson = """"usage":{"input_tokens":2785,"output_tokens":5,"total_tokens":2790,""" +
            """"api_calls":1,"model":"gpt-5.5","provider":"openai-codex","cost_status":"included",""" +
            """"estimated_cost_usd":0.0}"""
        server.enqueue(jsonResponse(
            """{"schema_version":2,"request_id":"$REQUEST_ID","reply":"ok",""" +
                """"meta":{"draft":null,"error_code":"clarification_required"},$usageJson}""",
        ))
        val compile = transport().compile("dopo le 23 dnd", manifest, DeviceState())
        assertEquals(
            TurnUsage(inputTokens = 2785, outputTokens = 5, model = "gpt-5.5"),
            compile.usage,
        )

        val expectedRequestId = actRequestId("execution-1", 0)
        server.enqueue(jsonResponse(
            """{"schema_version":1,"request_id":"$expectedRequestId",""" +
                """"result":{"text":"Va bene."},"error_code":null,$usageJson}""",
        ))
        val act = transport().act(
            fireContext(), "rispondi", listOf("notification"), listOf("whatsapp_reply"),
        )
        assertEquals(
            TurnUsage(inputTokens = 2785, outputTokens = 5, model = "gpt-5.5"),
            act.usage,
        )
    }

    @Test fun `responses without usage keep a null TurnUsage`(): Unit = runBlocking {
        // Retro-compat: il campo è opzionale, un bridge vecchio (o run senza report) non lo manda.
        server.enqueue(validNoDraftResponse())
        assertNull(transport().compile("dopo le 23 dnd", manifest, DeviceState()).usage)
    }

    private fun validNoDraftResponse(reply: String = "ok") = jsonResponse(
        """{"schema_version":2,"request_id":"$REQUEST_ID","reply":"$reply","meta":{"draft":null,"error_code":"clarification_required"}}"""
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
            location = GeoPoint(45.123, 11.456),
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
            location = GeoPoint(45.123, 11.456),
        ),
        automationId = AutomationId("automation-1"),
        approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
        eventId = TriggerEventId("event-1"),
        executionId = ExecutionId("execution-1"),
        actionIndex = 0,
    )

    private fun actRequestId(executionId: String, actionIndex: Int): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$executionId\u0000$actionIndex".toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "act-$digest"
    }

    private companion object {
        const val TOKEN = "test-token"
        const val REQUEST_ID = "req-test-1"
    }
}
