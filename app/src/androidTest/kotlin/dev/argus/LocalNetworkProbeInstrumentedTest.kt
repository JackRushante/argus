package dev.argus

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Probe LNP (gate P0-B §5.3.3, protocollo in docs/design/hermes-bridge-contract.md): il socket
 * viene creato dalla SocketFactory della Network Wi-Fi, mai da un Socket() ordinario che con
 * Tailscale attivo passerebbe da tun0 e sarebbe escluso da LNP per definizione. Va eseguito con
 * Tailscale sospeso (la VPN non-bypassable nega il binding Wi-Fi con EPERM prima di LNP).
 *
 * Argomenti: -e lanHost <ip> -e lanPort <porta> -e expect allowed|denied. Nessun segreto.
 */
@RunWith(AndroidJUnit4::class)
class LocalNetworkProbeInstrumentedTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun wifiLanProbeMatchesExpectation() {
        val arguments = InstrumentationRegistry.getArguments()
        val host = requireNotNull(arguments.getString("lanHost")) { "-e lanHost mancante" }
        val port = requireNotNull(arguments.getString("lanPort")) { "-e lanPort mancante" }.toInt()
        val expect = requireNotNull(arguments.getString("expect")) { "-e expect mancante" }
        require(expect == "allowed" || expect == "denied") { "expect: allowed|denied" }

        val network = wifiNetwork()
        assumeTrue("Nessuna rete Wi-Fi disponibile per il probe", network != null)

        val outcome = try {
            network!!.socketFactory.createSocket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            }
            "allowed"
        } catch (error: Exception) {
            // Il denial LNP deve restare un errore pulito (niente crash di processo).
            "denied(${error::class.java.simpleName})"
        }
        assertEquals(expect, outcome.substringBefore('('))
    }

    private fun wifiNetwork(): Network? {
        val manager = requireNotNull(context.getSystemService(ConnectivityManager::class.java))
        val latch = CountDownLatch(1)
        val found = AtomicReference<Network?>(null)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found.compareAndSet(null, network)
                latch.countDown()
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        manager.registerNetworkCallback(request, callback)
        return try {
            latch.await(WIFI_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            assertNotNull("Callback Wi-Fi non arrivata", found.get())
            found.get()
        } finally {
            manager.unregisterNetworkCallback(callback)
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 4_000
        const val WIFI_TIMEOUT_SECONDS = 5L
    }
}
