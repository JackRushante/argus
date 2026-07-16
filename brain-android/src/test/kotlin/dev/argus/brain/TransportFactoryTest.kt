package dev.argus.brain

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransportFactoryTest {

    private val fixedSecret = ProviderSecrets { "test-token-0123456789abcdef" }

    @Test
    fun `hermes config produces a cli bridge transport`() {
        val factory = DefaultTransportFactory(fixedSecret)

        val transport = factory.create(
            ProviderConfig(ProviderId.HERMES, "https://bridge.example", model = null),
        )

        assertTrue(transport is CliBridgeTransport)
        assertEquals(ProviderId.HERMES, transport.providerId)
    }

    @Test
    fun `every other provider fails with the typed not_yet_implemented error`() {
        val factory = DefaultTransportFactory(fixedSecret)

        ProviderId.entries.filter { it != ProviderId.HERMES }.forEach { id ->
            val spec = ProviderCatalog.spec(id)
            val baseUrl = spec.defaultBaseUrl ?: "https://custom.example"
            val error = assertFailsWith<TransportNotImplementedException> {
                factory.create(ProviderConfig(id, baseUrl, model = null))
            }
            assertEquals(TransportErrorKind.CONFIGURATION, error.kind)
            assertEquals(id, error.providerId)
            val message = error.message.orEmpty()
            assertTrue(message.contains("not_yet_implemented"), "message: $message")
            assertTrue(message.contains(id.wireName), "message: $message")
            // Nessun segreto e nessuna baseUrl nel messaggio d'errore.
            assertFalse(message.contains(baseUrl), "message leaks baseUrl: $message")
            assertFalse(message.contains("test-token"), "message leaks secret: $message")
        }
    }

    @Test
    fun `factory never reads the api key at creation time`() = runBlocking {
        var reads = 0
        val countingSecrets = ProviderSecrets {
            reads += 1
            "test-token-0123456789abcdef"
        }
        val factory = DefaultTransportFactory(countingSecrets)

        factory.create(ProviderConfig(ProviderId.HERMES, "https://bridge.example", model = null))

        // La chiave si legge per-richiesta, non alla costruzione: tiene vivo l'hot-swap del token.
        assertEquals(0, reads)
    }
}
