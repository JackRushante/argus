package dev.argus

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.argus.brain.CleartextGuard
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cleartext on the device (#19): the platform permits it (a network security config cannot express the
 * private ranges) and the app guards decide. Every client but the Custom provider keeps the exact loopback
 * hosts; the Custom provider adds the local network; a public host is never reachable over http://.
 */
@RunWith(AndroidJUnit4::class)
class LoopbackCleartextInstrumentedTest {
    private val policy = NetworkSecurityPolicy.getInstance()

    @Test
    fun platformPermitsCleartextAndTheAppGuardsDecide() {
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertTrue(policy.isCleartextTrafficPermitted("192.168.0.10"))

        val loopbackOnly = CleartextGuard.LOOPBACK_ONLY
        assertTrue(loopbackOnly.permits("localhost"))
        assertTrue(loopbackOnly.permits("127.0.0.1"))
        assertTrue(loopbackOnly.permits("::1"))
        assertFalse(loopbackOnly.permits("localhost.example"))
        assertFalse(loopbackOnly.permits("127.0.0.2"))
        assertFalse(loopbackOnly.permits("192.168.0.10"))
        assertFalse(loopbackOnly.permits("example.com"))

        val localNetwork = CleartextGuard.LOCAL_NETWORK
        assertTrue(localNetwork.permits("192.168.0.10"))
        assertTrue(localNetwork.permits("fd00::1"))
        assertTrue(localNetwork.permits("studio.local"))
        assertFalse(localNetwork.permits("192.168.0.10.example.com"))
        assertFalse(localNetwork.permits("example.com"))
    }

    @Test
    fun cleartextRequestReachesServerOnDeviceLoopback() {
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            val responder = thread(name = "argus-loopback-test") {
                server.accept().use { socket ->
                    val request = socket.getInputStream().bufferedReader()
                    while (!request.readLine().isNullOrEmpty()) Unit
                    socket.getOutputStream().use { response ->
                        response.write(
                            "HTTP/1.1 204 No Content\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                                .toByteArray(),
                        )
                    }
                }
            }
            val connection = URL("http://127.0.0.1:${server.localPort}/v1/models")
                .openConnection() as HttpURLConnection
            connection.connectTimeout = 3_000
            connection.readTimeout = 3_000
            try {
                assertEquals(204, connection.responseCode)
            } finally {
                connection.disconnect()
                responder.join(3_000)
            }
        }
    }
}
