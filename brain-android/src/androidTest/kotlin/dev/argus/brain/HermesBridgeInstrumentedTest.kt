package dev.argus.brain

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.argus.engine.brain.CapabilityManifest
import dev.argus.engine.model.Action
import dev.argus.engine.model.DndMode
import dev.argus.engine.model.Trigger
import dev.argus.engine.runtime.DeviceState
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

/** Test live opt-in: i segreti arrivano solo come instrumentation arguments e non finiscono nell'APK. */
@RunWith(AndroidJUnit4::class)
class HermesBridgeInstrumentedTest {
    private val arguments get() = InstrumentationRegistry.getArguments()

    private fun transport(): CliBridgeTransport {
        val baseUrl = arguments.getString("bridgeBaseUrl")
        val token = arguments.getString("bridgeToken")
        assumeTrue("bridgeBaseUrl non fornita", !baseUrl.isNullOrBlank())
        assumeTrue("bridgeToken non fornito", !token.isNullOrBlank())
        return CliBridgeTransport(
            baseUrl = requireNotNull(baseUrl),
            authProvider = BridgeAuthProvider { requireNotNull(token) },
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
            androidVersion = 36,
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
}
