package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    // --- #20: tempo massimo di risposta scelto dall'utente ---

    @Test
    fun `automatic keeps the historical limits`() {
        assertEquals(65_000L, CompileTimeoutPolicy.timeoutMillis(ProviderId.OPENAI, "https://api.openai.com/v1", ResponseTimeout.AUTOMATIC))
        assertEquals(180_000L, custom("http://192.168.1.20:8080/v1"))
        assertEquals(
            180_000L,
            CompileTimeoutPolicy.timeoutMillis(ProviderConfig(ProviderId.CUSTOM_OPENAI_COMPAT, "http://studio.local/v1", "m")),
        )
    }

    @Test
    fun `a chosen timeout replaces the automatic one for every provider`() {
        val expected = mapOf(
            ResponseTimeout.TWO_MINUTES to 120_000L,
            ResponseTimeout.FIVE_MINUTES to 300_000L,
            ResponseTimeout.TEN_MINUTES to 600_000L,
            ResponseTimeout.TWENTY_MINUTES to 1_200_000L,
        )
        expected.forEach { (choice, millis) ->
            ProviderId.entries.forEach { id ->
                assertEquals(millis, CompileTimeoutPolicy.timeoutMillis(id, "https://api.example.com/v1", choice), "$id $choice")
                assertEquals(millis, CompileTimeoutPolicy.timeoutMillis(ProviderConfig(id, "http://127.0.0.1:8080/v1", null, responseTimeout = choice)))
            }
        }
    }

    @Test
    fun `the choices map to minutes and to a stable wire value`() {
        assertEquals(listOf(0, 2, 5, 10, 20), ResponseTimeout.entries.map { it.minutes })
        assertEquals(listOf("auto", "2m", "5m", "10m", "20m"), ResponseTimeout.entries.map { it.wireValue })
        ResponseTimeout.entries.forEach { choice ->
            assertEquals(choice, ResponseTimeout.fromMinutes(choice.minutes))
            assertEquals(choice, ResponseTimeout.fromWireValue(choice.wireValue))
        }
        assertEquals(null, ResponseTimeout.fromMinutes(3))
        assertEquals(null, ResponseTimeout.fromMinutes(-1))
        // Assente o sconosciuto (prefs vecchie o corrotte): automatico, mai un limite più corto.
        assertEquals(ResponseTimeout.AUTOMATIC, ResponseTimeout.fromWireValue(null))
        assertEquals(ResponseTimeout.AUTOMATIC, ResponseTimeout.fromWireValue("7m"))
        assertTrue(ResponseTimeout.entries.maxOf { it.millis ?: 0L } >= 600_000L, "at least 10 minutes")
    }

    @Test
    fun `the http client never cuts a chosen timeout`() {
        val factory = DefaultTransportFactory(ProviderSecrets { null })
        ProviderId.entries.forEach { id ->
            val base = ProviderCatalog.spec(id).defaultBaseUrl ?: "https://custom.example/v1"
            val client = factory.clientFor(ProviderConfig(id, base, "m", responseTimeout = ResponseTimeout.TEN_MINUTES))
            assertEquals(605_000, client.callTimeoutMillis, id.name)
            assertTrue(client.readTimeoutMillis >= 605_000, id.name)
            assertTrue(client.writeTimeoutMillis >= 605_000, id.name)
        }
        // Più corto del client di base (125 s): il client non si accorcia, scade prima il compile.
        val two = factory.clientFor(ProviderConfig(ProviderId.OPENAI, "https://api.openai.com/v1", "m", responseTimeout = ResponseTimeout.TWO_MINUTES))
        assertEquals(125_000, two.callTimeoutMillis)
    }
}
