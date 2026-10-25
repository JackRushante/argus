package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertEquals

class CompileTimeoutPolicyTest {
    private fun custom(baseUrl: String?) =
        CompileTimeoutPolicy.timeoutMillis(ProviderId.CUSTOM_OPENAI_COMPAT, baseUrl)

    @Test
    fun `custom endpoints on loopback private ranges and local names get 180 seconds`() {
        listOf(
            "http://localhost:11434/v1",
            "http://127.0.0.1:8080/v1",
            "http://127.1.2.3/v1",
            "http://[::1]:8080/v1",
            "http://10.0.0.5:11434/v1",
            "http://172.16.0.9/v1",
            "http://172.31.255.1/v1",
            "http://192.168.1.20:8080/v1",
            "http://169.254.10.10/v1",
            "http://100.101.102.103:11434/v1",
            "http://studio.local:11434/v1",
            "https://gpu-box.local/v1",
        ).forEach { url ->
            assertEquals(180_000L, custom(url), url)
        }
    }

    @Test
    fun `custom endpoints on public hosts keep the 65 second timeout`() {
        listOf(
            "https://api.example.com/v1",
            "http://8.8.8.8/v1",
            "http://172.32.0.1/v1",
            "http://172.15.0.1/v1",
            "http://100.63.0.1/v1",
            "http://100.128.0.1/v1",
            "https://notlocal.com/v1",
            "https://my.local.example.org/v1",
        ).forEach { url ->
            assertEquals(65_000L, custom(url), url)
        }
    }

    @Test
    fun `every other provider keeps 65 seconds even on a local base url`() {
        ProviderId.entries.filter { it != ProviderId.CUSTOM_OPENAI_COMPAT }.forEach { id ->
            assertEquals(65_000L, CompileTimeoutPolicy.timeoutMillis(id, "http://192.168.1.20:8080/v1"), id.name)
        }
    }

    @Test
    fun `a missing or malformed base url falls back to 65 seconds`() {
        assertEquals(65_000L, custom(null))
        assertEquals(65_000L, custom(""))
        assertEquals(65_000L, custom("not a url"))
        assertEquals(65_000L, custom("localhost:11434/v1"))
    }

    @Test
    fun `no dns lookup is needed to classify a hostname`() {
        // Un nome che non risolverebbe mai: la policy è solo sintattica e non deve bloccare né uscire in rete.
        assertEquals(65_000L, custom("https://this-host-does-not-exist.invalid/v1"))
    }
}
