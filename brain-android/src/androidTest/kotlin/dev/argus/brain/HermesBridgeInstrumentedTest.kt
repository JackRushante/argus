package dev.argus.brain

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.model.Action
import dev.argus.engine.model.ApprovalFingerprint
import dev.argus.engine.model.AutomationId
import dev.argus.engine.model.DndMode
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
            assertEquals(CliBridgeTransport.PROTOCOL_SCHEMA_VERSION, health.schemaVersion)
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
        )

        val result = HermesBrain(transport()).compile(
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

        val result = HermesBrain(transport()).act(
            context = context,
            goal = "Rispondi in italiano in modo cordiale e molto conciso",
            contextSources = listOf("notification"),
            allowedTools = listOf("whatsapp_reply"),
        )

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
