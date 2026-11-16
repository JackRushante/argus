package dev.argus.brain

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.net.InetAddress
import java.net.UnknownServiceException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * #19: the platform permits cleartext, the guards decide. A refused request fails before any connection;
 * the default client keeps the old loopback-only rule and only the Custom provider gets the local network.
 */
class CleartextGuardTest {
    private lateinit var server: MockWebServer

    @BeforeTest
    fun setUp() {
        server = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }
    }

    @AfterTest
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    private fun get(client: OkHttpClient, url: String) =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code }

    @Test
    fun `the default client still reaches the loopback over http`() {
        server.enqueue(MockResponse().setResponseCode(204))
        assertEquals(204, get(CliBridgeTransport.defaultClient(), "http://127.0.0.1:${server.port}/v1/models"))
    }

    @Test
    fun `the default client refuses http to any other host before connecting`() {
        val client = CliBridgeTransport.defaultClient()
        listOf("http://192.168.0.10:47832/v1", "http://example.com/v1", "http://studio.local/v1").forEach { url ->
            assertFailsWith<UnknownServiceException>(url) { get(client, url) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `only the custom provider client allows the local network`() {
        val factory = DefaultTransportFactory(ProviderSecrets { null })
        val custom = factory.clientFor(ProviderConfig(ProviderId.CUSTOM_OPENAI_COMPAT, "http://192.168.0.10:47832/v1", "m"))
        val customGuard = assertNotNull(CleartextGuard.of(custom))
        assertTrue(customGuard.permits("192.168.0.10"))
        assertTrue(customGuard.permits("fd00::1"))
        assertTrue(customGuard.permits("127.0.0.1"))
        assertFalse(customGuard.permits("example.com"))
        assertFalse(customGuard.permits("192.168.0.10.evil.com"))
        // One guard per level, never stacked (a stacked loopback guard would still block the LAN).
        assertEquals(1, custom.interceptors.count { it is CleartextGuard })
        assertEquals(1, custom.networkInterceptors.count { it is CleartextGuard })
        // A public http host is refused before connecting, even through the Custom client.
        assertFailsWith<UnknownServiceException> { get(custom, "http://example.com/v1") }

        listOf(ProviderId.OPENAI, ProviderId.OPENROUTER, ProviderId.GEMINI, ProviderId.ANTHROPIC, ProviderId.HERMES)
            .forEach { id ->
                val client = factory.clientFor(ProviderConfig(id, ProviderCatalog.spec(id).defaultBaseUrl ?: "https://x.example", null))
                val guard = assertNotNull(CleartextGuard.of(client), id.name)
                assertFalse(guard.permits("192.168.0.10"), id.name)
                assertTrue(guard.permits("127.0.0.1"), id.name)
            }
    }
}
