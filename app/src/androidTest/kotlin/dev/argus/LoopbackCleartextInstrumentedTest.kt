package dev.argus

import android.security.NetworkSecurityPolicy
import androidx.test.ext.junit.runners.AndroidJUnit4
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

/** Verifica l'eccezione cleartext minima usata dai server AI avviati sullo stesso device. */
@RunWith(AndroidJUnit4::class)
class LoopbackCleartextInstrumentedTest {
    private val policy = NetworkSecurityPolicy.getInstance()

    @Test
    fun cleartextIsAllowedOnlyForExactLoopbackHosts() {
        assertTrue(policy.isCleartextTrafficPermitted("localhost"))
        assertTrue(policy.isCleartextTrafficPermitted("127.0.0.1"))
        assertTrue(policy.isCleartextTrafficPermitted("::1"))

        assertFalse(policy.isCleartextTrafficPermitted("localhost.example"))
        assertFalse(policy.isCleartextTrafficPermitted("127.0.0.2"))
        assertFalse(policy.isCleartextTrafficPermitted("example.com"))
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
