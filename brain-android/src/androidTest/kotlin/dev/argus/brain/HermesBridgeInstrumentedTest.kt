package dev.argus.brain

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.brain.StateReaderManifest
import dev.argus.engine.brain.WhitelistedContact
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovedStateContext
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.ConfidentialityLabel
import dev.argus.engine.model.IntegrityLabel
import dev.argus.engine.model.StateQuery
import dev.argus.engine.model.StateQueryFamily
import dev.argus.engine.model.StateQueryPolicy
import dev.argus.engine.model.StateValueType
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.DeviceState
import dev.argus.engine.runtime.ExecutionId
import dev.argus.engine.runtime.FireContext
import dev.argus.engine.runtime.TriggerEvent
import dev.argus.engine.runtime.TriggerEventId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Test live opt-in: il bearer arriva da un file privato one-shot, mai dalla command line ADB. */
@RunWith(AndroidJUnit4::class)
class HermesBridgeInstrumentedTest {
    private val arguments get() = InstrumentationRegistry.getArguments()

    private fun transport(): CliBridgeTransport {
        val baseUrl = arguments.getString("bridgeBaseUrl")
        assumeTrue("bridgeBaseUrl non fornita", !baseUrl.isNullOrBlank())
        return CliBridgeTransport(
            baseUrl = requireNotNull(baseUrl),
            authProvider = BridgeAuthProvider { bridgeToken() },
            client = CliBridgeTransport.defaultClient(
                timeoutSeconds = if (arguments.getString("expectNetworkDenied") == "true") 5 else 65
            ),
        )
    }

    @Test fun authenticatedHealthHonorsExpectedNetworkState(): Unit = runBlocking {
        val expectReachable = arguments.getString("expectBridgeReachable") != "false"
        try {
            val health = transport().health()
            assertTrue("il bridge doveva risultare irraggiungibile", expectReachable)
            assertEquals(CliBridgeTransport.HEALTH_SCHEMA_VERSION, health.schemaVersion)
            assertTrue(CliBridgeTransport.COMPILE_SCHEMA_VERSION in health.compileSchemaVersions)
            assertTrue(CliBridgeTransport.ACT_SCHEMA_VERSION in health.actSchemaVersions)
            assertTrue(CliBridgeTransport.ACT_V2_SCHEMA_VERSION in health.actSchemaVersions)
            assertEquals("ok", health.status)
        } catch (error: BridgeException) {
            if (expectReachable) throw error
            assertTrue(
                "errore inatteso con rete negata: ${error.kind}",
                error.kind == BridgeErrorKind.NETWORK || error.kind == BridgeErrorKind.TIMEOUT,
            )
        }
    }

    /**
     * Probe opt-in per il compat flag Android 16. Una route VPN (Tailscale) è esclusa dalla
     * definizione LNP, quindi viene verificata dal test health sopra e deve restare raggiungibile.
     * Questo test usa invece un endpoint LAN diretto, fornito come instrumentation argument.
     */
    @Test fun directWifiLanProbeHonorsExpectedNetworkState() {
        val host = arguments.getString("lanProbeHost")
        val port = arguments.getString("lanProbePort")?.toIntOrNull()
        assumeTrue("lanProbeHost non fornito", !host.isNullOrBlank())
        assumeTrue("lanProbePort non fornita", port != null)
        val expectReachable = arguments.getString("expectLanReachable") == "true"
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val wifi = AtomicReference<Network?>()
        val available = CountDownLatch(1)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (wifi.compareAndSet(null, network)) available.countDown()
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        connectivity.registerNetworkCallback(request, callback)
        var failure: Exception? = null
        var interfaceName: String? = null
        val connected = try {
            assumeTrue("rete Wi-Fi diretta non disponibile", available.await(5, TimeUnit.SECONDS))
            val wifiNetwork = requireNotNull(wifi.get())
            interfaceName = connectivity.getLinkProperties(wifiNetwork)?.interfaceName
            try {
                wifiNetwork.socketFactory.createSocket().use { socket ->
                    socket.connect(
                        InetSocketAddress(requireNotNull(host), requireNotNull(port)),
                        2_000,
                    )
                }
                true
            } catch (error: Exception) {
                failure = error
                false
            }
        } finally {
            connectivity.unregisterNetworkCallback(callback)
        }
        val detail = failure?.let { "${it.javaClass.simpleName}: ${it.message}" } ?: "nessun errore"
        assertEquals(
            "stato inatteso della route LAN diretta su $interfaceName ($detail)",
            expectReachable,
            connected,
        )
    }

    @Test fun liveCompileReturnsDecodableDraft(): Unit = runBlocking {
        assumeTrue("compile live non richiesto", arguments.getString("runLiveCompile") == "true")
        val manifest = CapabilityManifest(
            deviceModel = "OnePlus CPH2747",
            androidVersion = 16,
            androidApi = 36,
            shizukuAvailable = true,
            grantedPermissions = listOf("android.permission.INTERNET"),
            availableTools = listOf("set_dnd"),
            unavailableTools = emptyMap(),
            whitelistedContacts = emptyList(),
            availableTriggers = listOf("time"),
        )

        val result = TransportBackedBrain(transport()).compile(
            "Dopo le 23 attiva non disturbare prioritario",
            manifest,
            DeviceState(values = mapOf("dnd" to "off")),
        )

        assertNull(result.metaError)
        assertNotNull(result.draft)
        val draft = requireNotNull(result.draft)
        assertTrue(draft.trigger is Trigger.Time)
        assertEquals(Action.SetDnd(DndMode.PRIORITY), draft.actions.single())
    }

    @Test fun liveCompileV2ReturnsExplicitClassifiedStateAction(): Unit = runBlocking {
        assumeTrue("compile v2 live non richiesto", arguments.getString("runLiveCompileV2") == "true")
        val manifest = CapabilityManifest(
            deviceModel = "OnePlus CPH2747",
            androidVersion = 16,
            androidApi = 36,
            shizukuAvailable = true,
            grantedPermissions = listOf("notification_listener"),
            availableTools = listOf("invoke_llm_v2", "whatsapp_reply"),
            unavailableTools = emptyMap(),
            whitelistedContacts = listOf(WhitelistedContact("Contatto test", "jid:test")),
            availableTriggers = listOf("notification"),
            stateReaders = StateReaderManifest(
                families = listOf(StateQueryFamily.DUMPSYS_FIELD),
            ),
        )

        val result = TransportBackedBrain(transport()).compile(
            "Quando ricevo un messaggio WhatsApp 1:1 da Contatto test, rispondi in modo " +
                "cordiale tenendo conto del voltaggio batteria in millivolt. Usa il reader " +
                "dumpsys battery, campo voltage; non aggiungere altre azioni o condizioni.",
            manifest,
            DeviceState(),
        )

        assertNull(result.metaError)
        val draft = requireNotNull(result.draft)
        assertTrue(draft.trigger is Trigger.Notification)
        val action = draft.actions.single() as Action.InvokeLlmV2
        assertEquals(StateQuery.DumpsysField("battery", "voltage"), action.stateContext.single().query)
        assertEquals(StateValueType.NUMBER, action.stateContext.single().valueType)
        assertEquals(IntegrityLabel.CLEAN, action.stateContext.single().integrity)
        assertEquals(ConfidentialityLabel.SECRET, action.stateContext.single().confidentiality)
    }

    @Test fun liveActReturnsTextWithoutRemoteTarget(): Unit = runBlocking {
        assumeTrue("act live non richiesto", arguments.getString("runLiveAct") == "true")
        val context = FireContext(
            event = TriggerEvent.NotificationPosted(
                pkg = "com.whatsapp",
                sender = "Contatto test",
                title = "Contatto test",
                text = "Arrivo tra dieci minuti",
                isGroup = false,
            ),
            state = DeviceState(),
            automationId = AutomationId("live-act-automation"),
            approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
            eventId = TriggerEventId("live-act-event"),
            executionId = ExecutionId("live-act-20260714-1"),
            actionIndex = 0,
        )

        val result = TransportBackedBrain(transport()).act(
            context = context,
            goal = "Rispondi in italiano in modo cordiale e molto conciso",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
        )

        assertNull(result.metaError)
        assertTrue(requireNotNull(result.text).length in 1..4_096)
    }

    @Test fun liveActV2ReturnsTextFromOnlyTheClassifiedQuery(): Unit = runBlocking {
        assumeTrue("act v2 live non richiesto", arguments.getString("runLiveActV2") == "true")
        val query = StateQuery.DumpsysField("battery", "voltage")
        val context = FireContext(
            event = TriggerEvent.NotificationPosted(
                pkg = "com.whatsapp",
                sender = "Contatto test",
                title = "Contatto test",
                text = "Arrivo tra dieci minuti",
                isGroup = false,
            ),
            state = DeviceState(queryValues = mapOf(query.canonicalId to "4200")),
            automationId = AutomationId("live-act-v2-automation"),
            approvalFingerprint = ApprovalFingerprint("0".repeat(64)),
            eventId = TriggerEventId("live-act-v2-event"),
            executionId = ExecutionId("live-act-v2-20260716-1"),
            actionIndex = 0,
        )
        val action = Action.InvokeLlmV2(
            goal = "Rispondi in italiano in modo cordiale e molto conciso tenendo conto del voltaggio",
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

        val result = TransportBackedBrain(transport()).actV2(context, action)

        assertNull(result.metaError)
        assertTrue(requireNotNull(result.text).length in 1..4_096)
    }

    private fun bridgeToken(): String {
        cachedBridgeToken?.let { return it }
        return synchronized(tokenLock) {
            cachedBridgeToken ?: consumeBridgeToken().also { cachedBridgeToken = it }
        }
    }

    private fun consumeBridgeToken(): String {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = context.getFileStreamPath(BRIDGE_TOKEN_FILE)
        assumeTrue("token bridge live non predisposto nel file privato", file.isFile)
        val tokenResult = runCatching {
            context.openFileInput(BRIDGE_TOKEN_FILE).bufferedReader().use { it.readText().trim() }
        }
        if (tokenResult.isFailure) {
            context.deleteFile(BRIDGE_TOKEN_FILE)
            error("Token bridge live non leggibile")
        }
        check(context.deleteFile(BRIDGE_TOKEN_FILE)) { "Token bridge live non eliminato" }
        return tokenResult.getOrThrow().also {
            require(it.isNotBlank()) { "Token bridge live vuoto" }
        }
    }

    private companion object {
        const val BRIDGE_TOKEN_FILE = "argus-e2e-bridge-token"
        val tokenLock = Any()

        @Volatile
        var cachedBridgeToken: String? = null
    }
}
